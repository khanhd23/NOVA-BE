package com.nova.backend.social;

import com.nova.backend.account.AccountService;
import com.nova.backend.account.PublicUserCard;
import com.nova.backend.common.PageResponse;
import com.nova.backend.common.exception.ConflictException;
import com.nova.backend.common.exception.BadRequestException;
import com.nova.backend.common.exception.NotFoundException;
import com.nova.backend.common.ModuleStateStore;
import com.nova.backend.realtime.LiveDeliveryService;
import com.nova.backend.realtime.RealtimeEvent;
import com.nova.backend.realtime.RealtimeEventType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class SocialService {

    private static final Set<String> LEGACY_DEMO_USER_IDS = Set.of(
            "u-current",
            "u-seraphina",
            "u-elena",
            "u-marcus",
            "u-chloe",
            "u-alex",
            "u-mina"
    );
    private static final Set<String> LEGACY_DEMO_THREAD_IDS = Set.of(
            "thread-seraphina",
            "thread-elena",
            "thread-chloe",
            "thread-marcus"
    );
    private static final Set<String> LEGACY_DEMO_CALL_IDS = Set.of("call_seed_1", "call_seed_2");

    private final AccountService accountService;
    private final LiveDeliveryService liveDeliveryService;
    private final ModuleStateStore moduleStateStore;
    private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();
    private final Map<String, CallRecord> calls = new ConcurrentHashMap<>();
    private final List<NotificationResponse> notifications = new CopyOnWriteArrayList<>();
    private final Map<String, Set<String>> deletedMessagesForUsers = new ConcurrentHashMap<>();
    private final AtomicInteger messageSequence = new AtomicInteger(1);
    private final AtomicInteger callSequence = new AtomicInteger(1);
    private final AtomicInteger notificationSequence = new AtomicInteger(1);

    public SocialService(AccountService accountService, LiveDeliveryService liveDeliveryService, ModuleStateStore moduleStateStore) {
        this.accountService = accountService;
        this.liveDeliveryService = liveDeliveryService;
        this.moduleStateStore = moduleStateStore;
        loadPersistedState();
    }

    public List<ChatThreadResponse> threads(String userId) {
        return threads.values().stream()
                .filter(thread -> thread.participantIds().contains(userId))
                .filter(thread -> !thread.hiddenForUserIds().contains(userId))
                .map(thread -> toThreadResponse(thread, userId))
                .sorted(Comparator.comparing(ChatThreadResponse::updatedAt).reversed())
                .toList();
    }

    public PageResponse<ChatThreadResponse> searchThreads(String userId, String query, int page, int size) {
        String normalized = normalize(query);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 20));
        List<ThreadSearchCandidate> matched = threads.values().stream()
                .filter(thread -> thread.participantIds().contains(userId))
                .filter(thread -> !thread.messages().isEmpty())
                .map(thread -> {
                    String otherId = thread.otherParticipant(userId);
                    return new ThreadSearchCandidate(thread, accountService.getPublicProfile(otherId));
                })
                .filter(candidate -> normalized.isBlank() || matchesThreadSearch(candidate.participant(), normalized))
                .sorted(Comparator.comparing((ThreadSearchCandidate candidate) -> candidate.thread().updatedAt()).reversed())
                .toList();

        int fromIndex = Math.min(safePage * safeSize, matched.size());
        int toIndex = Math.min(fromIndex + safeSize, matched.size());
        List<ChatThreadResponse> items = matched.subList(fromIndex, toIndex).stream()
                .map(candidate -> toThreadResponse(candidate.thread(), userId, candidate.participant()))
                .toList();
        return new PageResponse<>(items, safePage, safeSize, matched.size());
    }

    public ThreadDetailResponse thread(String userId, String threadId) {
        return thread(userId, threadId, 20, null);
    }

    public ThreadDetailResponse thread(String userId, String threadId, int limit, String before) {
        ThreadRecord thread = resolveThreadForOpen(userId, threadId);
        if (!thread.participantIds().contains(userId)) {
            throw new NotFoundException("Thread not found");
        }
        boolean hiddenForUser = thread.hiddenForUserIds().contains(userId);
        if (hiddenForUser) {
            markThreadMessagesDeletedForUser(thread, userId);
            thread.hiddenForUserIds().remove(userId);
            thread.unreadCount = 0;
            persistState();
        }
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<MessageRecord> visibleMessages = thread.messages().stream()
                .filter(message -> isMessageVisibleToUser(message, userId))
                .toList();
        int endExclusive = visibleMessages.size();
        if (before != null && !before.isBlank()) {
            int beforeIndex = indexOfMessage(visibleMessages, before);
            if (beforeIndex >= 0) {
                endExclusive = beforeIndex;
            }
        }
        int startInclusive = Math.max(0, endExclusive - safeLimit);
        List<ChatMessageResponse> messages = visibleMessages.subList(startInclusive, endExclusive).stream()
                .map(message -> toMessageResponse(message, userId))
                .toList();
        boolean hasMore = startInclusive > 0;
        String nextCursor = hasMore ? visibleMessages.get(startInclusive).id() : null;
        return new ThreadDetailResponse(
                toThreadResponse(thread, userId),
                messages,
                hasMore,
                nextCursor
        );
    }

    public ChatMessageResponse sendMessage(String userId, String threadId, SendMessageRequest request) {
        ThreadRecord thread = resolveThreadForOpen(userId, threadId);
        if (!thread.participantIds().contains(userId)) {
            throw new NotFoundException("Thread not found");
        }
        thread.hiddenForUserIds().clear();
        MessageKind attachmentKind = request.attachmentKind();
        boolean hasAttachment = request.attachmentUrl() != null && !request.attachmentUrl().isBlank();
        boolean hasText = request.text() != null && !request.text().isBlank();
        if (!hasText && !hasAttachment) {
            throw new BadRequestException("Message content is required");
        }
        if (attachmentKind == null && hasAttachment) {
            attachmentKind = inferAttachmentKind(request.attachmentMimeType(), request.attachmentName(), request.attachmentUrl());
        }
        if (attachmentKind == MessageKind.TEXT) {
            attachmentKind = null;
        }
        if (attachmentKind == MessageKind.VOICE) {
            attachmentKind = MessageKind.AUDIO;
        }
        Instant createdAt = Instant.now();
        MessageRecord message = new MessageRecord(
                "msg-" + messageSequence.getAndIncrement(),
                thread.id(),
                userId,
                request.text() == null ? "" : request.text(),
                attachmentKind == MessageKind.AUDIO,
                false,
                false,
                attachmentKind,
                request.attachmentUrl(),
                request.attachmentPreviewUrl(),
                request.attachmentMimeType(),
                request.attachmentName(),
                request.attachmentDurationSeconds(),
                null,
                MessageStatus.SENT,
                timeLabel(createdAt),
                createdAt.toString()
        );
        thread.messages().add(message);
        thread.lastMessage = renderPreviewText(message, userId);
        thread.updatedAt = timeLabel(Instant.now());
        thread.unreadCount = 0;
        notifyUsers(
                participantIdsExcept(thread, userId),
                NotificationKind.MESSAGE,
                threadId,
                "New message",
                renderPreviewText(message, userId),
                "thread/" + threadId
        );
        publishToThread(
                thread,
                RealtimeEventType.MESSAGE_CREATED,
                userId,
                null,
                thread.id(),
                null,
                message.id(),
                "New message",
                renderPreviewText(message, userId),
                messageEventPayload(message)
        );
        persistState();
        return toMessageResponse(message, userId);
    }

    public void deleteThreadForMe(String userId, String threadId) {
        ThreadRecord thread = requireThread(threadId);
        ensureParticipant(thread, userId);
        markThreadMessagesDeletedForUser(thread, userId);
        thread.hiddenForUserIds().add(userId);
        publishToUser(
                userId,
                RealtimeEventType.THREAD_DELETED,
                userId,
                userId,
                threadId,
                null,
                null,
                "Thread deleted",
                "You removed this thread",
                threadChangePayload(threadId, "THREAD_DELETED", null)
        );
        persistState();
    }

    public void deleteMessageForMe(String userId, String threadId, String messageId) {
        ThreadRecord thread = requireThread(threadId);
        ensureParticipant(thread, userId);
        MessageRecord message = requireMessage(thread, messageId);
        if (message.status() == MessageStatus.RECALLED) {
            return;
        }
        deletedMessagesForUsers
                .computeIfAbsent(messageId, key -> ConcurrentHashMap.newKeySet())
                .add(userId);
        publishToUser(
                userId,
                RealtimeEventType.MESSAGE_DELETED,
                userId,
                userId,
                threadId,
                null,
                messageId,
                "Message deleted",
                "You removed this message",
                threadChangePayload(threadId, "MESSAGE_DELETED", messageId)
        );
        persistState();
    }

    public ChatMessageResponse recallMessage(String userId, String threadId, String messageId, RecallMessageRequest request) {
        ThreadRecord thread = requireThread(threadId);
        ensureParticipant(thread, userId);
        int index = indexOfMessage(thread, messageId);
        if (index < 0) {
            throw new NotFoundException("Message not found");
        }
        MessageRecord message = thread.messages().get(index);
        if (!Objects.equals(message.senderId(), userId)) {
            throw new ConflictException("Only the sender can recall this message");
        }
        if (message.callSummary() != null) {
            throw new BadRequestException("Call log messages cannot be recalled");
        }
        if (message.status() == MessageStatus.RECALLED) {
            return toMessageResponse(message, userId);
        }
        Instant sentAt = parseTimeLabel(message.timeLabel());
        if (Duration.between(sentAt, Instant.now()).compareTo(Duration.ofMinutes(15)) > 0) {
            throw new BadRequestException("Message can no longer be recalled");
        }

        MessageRecord recalled = new MessageRecord(
                message.id(),
                message.threadId(),
                message.senderId(),
                message.text(),
                message.voice(),
                message.gif(),
                message.sticker(),
                message.attachmentKind(),
                message.attachmentUrl(),
                message.attachmentPreviewUrl(),
                message.attachmentMimeType(),
                message.attachmentName(),
                message.attachmentDurationSeconds(),
                message.callSummary(),
                MessageStatus.RECALLED,
                message.timeLabel()
        );
        thread.messages().set(index, recalled);
        thread.lastMessage = previewText(thread, userId);
        thread.updatedAt = timeLabel(Instant.now());
        publishToUser(
                userId,
                RealtimeEventType.MESSAGE_RECALLED,
                userId,
                userId,
                thread.id(),
                null,
                recalled.id(),
                "Message recalled",
                "You unsent a message",
                messageEventPayload(recalled)
        );
        publishToParticipantsExcept(
                thread,
                userId,
                RealtimeEventType.MESSAGE_RECALLED,
                userId,
                null,
                thread.id(),
                null,
                recalled.id(),
                "Message recalled",
                "This message was unsent",
                messageEventPayload(recalled)
        );
        persistState();
        return toMessageResponse(recalled, userId);
    }

    public ChatMessageResponse editMessage(String userId, String threadId, String messageId, EditMessageRequest request) {
        ThreadRecord thread = requireThread(threadId);
        ensureParticipant(thread, userId);
        int index = indexOfMessage(thread, messageId);
        if (index < 0) {
            throw new NotFoundException("Message not found");
        }
        MessageRecord message = thread.messages().get(index);
        if (!Objects.equals(message.senderId(), userId)) {
            throw new ConflictException("Only the sender can edit this message");
        }
        if (message.status() == MessageStatus.RECALLED) {
            throw new BadRequestException("Recalled messages cannot be edited");
        }
        if (message.callSummary() != null) {
            throw new BadRequestException("Call log messages cannot be edited");
        }
        if (message.attachmentKind() != null && (message.text() == null || message.text().isBlank())) {
            throw new BadRequestException("Attachment-only messages cannot be edited");
        }
        String nextText = request == null || request.text() == null ? "" : request.text().trim();
        if (nextText.isBlank()) {
            throw new BadRequestException("Message text is required");
        }
        Instant sentAt = parseTimeLabel(message.timeLabel());
        if (Duration.between(sentAt, Instant.now()).compareTo(Duration.ofMinutes(15)) > 0) {
            throw new BadRequestException("Message can no longer be edited");
        }

        MessageRecord edited = new MessageRecord(
                message.id(),
                message.threadId(),
                message.senderId(),
                nextText,
                message.voice(),
                message.gif(),
                message.sticker(),
                message.attachmentKind(),
                message.attachmentUrl(),
                message.attachmentPreviewUrl(),
                message.attachmentMimeType(),
                message.attachmentName(),
                message.attachmentDurationSeconds(),
                message.callSummary(),
                message.status(),
                message.timeLabel()
        );
        thread.messages().set(index, edited);
        thread.lastMessage = previewText(thread, userId);
        thread.updatedAt = timeLabel(Instant.now());
        publishToThread(
                thread,
                RealtimeEventType.MESSAGE_UPDATED,
                userId,
                null,
                thread.id(),
                null,
                edited.id(),
                "Message edited",
                renderPreviewText(edited, userId),
                messageEventPayload(edited)
        );
        persistState();
        return toMessageResponse(edited, userId);
    }

    public ThreadDetailResponse markThreadRead(String userId, String threadId) {
        ThreadRecord thread = requireThread(threadId);
        ensureParticipant(thread, userId);
        thread.unreadCount = 0;
        for (int i = 0; i < thread.messages().size(); i++) {
            MessageRecord message = thread.messages().get(i);
            if (!Objects.equals(message.senderId(), userId) && message.status() != MessageStatus.RECALLED) {
                thread.messages().set(i, new MessageRecord(
                        message.id(),
                        message.threadId(),
                        message.senderId(),
                        message.text(),
                        message.voice(),
                        message.gif(),
                        message.sticker(),
                        message.attachmentKind(),
                        message.attachmentUrl(),
                        message.attachmentPreviewUrl(),
                        message.attachmentMimeType(),
                        message.attachmentName(),
                        message.attachmentDurationSeconds(),
                        message.callSummary(),
                        MessageStatus.SEEN,
                        message.timeLabel()
                ));
            }
        }
        publishToParticipantsExcept(
                thread,
                userId,
                RealtimeEventType.THREAD_READ,
                userId,
                null,
                thread.id(),
                null,
                null,
                "Thread read",
                "Read receipts updated",
                threadStatePayload(threadId, userId, "READ")
        );
        persistState();
        return thread(userId, threadId, 20, null);
    }

    public ChatThreadResponse setTyping(String userId, String threadId, boolean typing) {
        ThreadRecord thread = requireThread(threadId);
        ensureParticipant(thread, userId);
        thread.typing = typing;
        publishToParticipantsExcept(
                thread,
                userId,
                RealtimeEventType.THREAD_TYPING,
                userId,
                null,
                thread.id(),
                null,
                null,
                typing ? "Typing..." : "Typing stopped",
                typing ? "Someone is typing" : "Typing ended",
                threadStatePayload(threadId, userId, typing ? "TYPING" : "IDLE")
        );
        persistState();
        return toThreadResponse(thread, userId);
    }

    public void publishPresence(String userId, boolean online) {
        List<String> recipients = threads.values().stream()
                .filter(thread -> thread.participantIds().contains(userId))
                .flatMap(thread -> participantIdsExcept(thread, userId).stream())
                .distinct()
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        liveDeliveryService.publish(
                recipients,
                realtimeEvent(
                        RealtimeEventType.USER_PRESENCE,
                        userId,
                        null,
                        null,
                        null,
                        null,
                        online ? "Online" : "Offline",
                        online ? "User is online" : "User is offline",
                        presencePayload(userId, online)
                )
        );
    }

    public CallSessionResponse startCall(String userId, String threadId, CreateCallRequest request) {
        ThreadRecord thread = resolveThreadForCall(userId, threadId, request.peerUserId());
        if (!thread.participantIds().contains(userId)) {
            throw new NotFoundException("Thread not found");
        }
        thread.hiddenForUserIds().clear();
        String resolvedThreadId = thread.id();
        String callId = "call-" + callSequence.getAndIncrement();
        CallRecord call = new CallRecord(
                callId,
                resolvedThreadId,
                userId,
                thread.otherParticipant(userId),
                request.callType(),
                request.direction(),
                Instant.now(),
                null,
                null,
                0,
                null,
                true,
                request.callType() == CallType.VIDEO,
                false,
                "RINGING"
        );
        calls.put(callId, call);
        thread.lastCallId = callId;
        thread.updatedAt = timeLabel(Instant.now());
        notifyUsers(
                List.of(call.partnerId()),
                NotificationKind.CALL,
                resolvedThreadId,
                request.callType() == CallType.VIDEO ? "Video call" : "Voice call",
                "Calling " + accountService.getPublicProfile(call.partnerId()).displayName(),
                "call/" + callId
        );
        publishToUser(
                call.partnerId(),
                RealtimeEventType.CALL_STARTED,
                userId,
                call.partnerId(),
                resolvedThreadId,
                callId,
                null,
                request.callType() == CallType.VIDEO ? "Incoming video call" : "Incoming voice call",
                accountService.getPublicProfile(userId).displayName() + " is calling you",
                callEventPayloadForUser(call, call.partnerId(), "RINGING")
        );
        publishToUser(
                userId,
                RealtimeEventType.CALL_STARTED,
                userId,
                userId,
                resolvedThreadId,
                callId,
                null,
                request.callType() == CallType.VIDEO ? "Video call started" : "Voice call started",
                "Calling " + accountService.getPublicProfile(call.partnerId()).displayName(),
                callEventPayloadForUser(call, userId, "RINGING")
        );
        persistState();
        return callSession(call, userId);
    }

    public CallSessionResponse answerCall(String userId, String callId) {
        CallRecord call = requireCall(callId);
        ensureCallParticipant(call, userId);
        if ("ENDED".equals(call.status())) {
            return callSession(call, userId);
        }
        call.answeredAt = Instant.now();
        call.status = "IN_CALL";
        call.minimized = false;
        publishToCallParticipants(
                call,
                RealtimeEventType.CALL_ANSWERED,
                userId,
                "Call answered",
                accountService.getPublicProfile(userId).displayName() + " answered the call",
                "IN_CALL"
        );
        persistState();
        return callSession(call, userId);
    }

    public CallSessionResponse endCall(String userId, String callId, EndCallRequest request) {
        CallRecord call = requireCall(callId);
        ensureCallParticipant(call, userId);
        if ("ENDED".equals(call.status())) {
            return callSession(call, userId);
        }
        Instant endedAt = Instant.now();
        call.endedAt = endedAt;
        call.endReason = request.reason();
        if (call.answeredAt != null) {
            call.durationSeconds = (int) Duration.between(call.answeredAt, endedAt).getSeconds();
        } else {
            call.durationSeconds = 0;
        }
        call.status = "ENDED";
        CallSummaryResponse summary = summary(call, userId);
        ThreadRecord thread = requireThread(call.threadId());
        MessageRecord callLog = new MessageRecord(
                "msg-" + messageSequence.getAndIncrement(),
                thread.id(),
                call.initiatorId(),
                "",
                true,
                false,
                false,
                MessageKind.CALL_LOG,
                null,
                null,
                null,
                null,
                null,
                summary,
                // A call log is a normal incoming message for the other participant.
                // It must not be marked as read when the call ends; it becomes SEEN
                // only when the recipient opens the thread and markThreadRead runs.
                MessageStatus.SENT,
                timeLabel(endedAt)
        );
        thread.messages().add(callLog);
        thread.lastMessage = summaryText(summary);
        thread.updatedAt = timeLabel(endedAt);
        notifyUsers(
                participantIdsExcept(thread, userId),
                NotificationKind.CALL,
                thread.id(),
                "Call ended",
                summaryText(summary),
                "call/" + callId
        );
        publishToThread(
                thread,
                RealtimeEventType.MESSAGE_CREATED,
                userId,
                null,
                thread.id(),
                call.id(),
                callLog.id(),
                "Call ended",
                summaryText(summary),
                messageEventPayload(callLog)
        );
        publishToCallParticipants(
                call,
                RealtimeEventType.CALL_ENDED,
                userId,
                "Call ended",
                summaryText(summary),
                summaryText(summary)
        );
        persistState();
        return new CallSessionResponse(call.id(), call.threadId(), summary, call.status, call.minimized);
    }

    public CallSessionResponse minimizeCall(String userId, String callId, boolean minimized) {
        CallRecord call = requireCall(callId);
        ensureCallParticipant(call, userId);
        call.minimized = minimized;
        publishToUser(
                userId,
                RealtimeEventType.CALL_MINIMIZED,
                userId,
                userId,
                call.threadId(),
                call.id(),
                null,
                minimized ? "Call minimized" : "Call restored",
                minimized ? "Call minimized" : "Call restored",
                callEventPayloadForUser(call, userId, minimized ? "MINIMIZED" : "IN_CALL")
        );
        persistState();
        return callSession(call, userId);
    }

    public CallSessionResponse call(String userId, String callId) {
        CallRecord call = requireCall(callId);
        ensureCallParticipant(call, userId);
        return callSession(call, userId);
    }

    public CallListResponse callHistory(String userId) {
        return new CallListResponse(
                calls.values().stream()
                        .filter(call -> Objects.equals(call.initiatorId(), userId) || Objects.equals(call.partnerId(), userId))
                        .map(call -> summary(call, userId))
                        .sorted(Comparator.comparing(CallSummaryResponse::startedAtLabel).reversed())
                        .toList()
        );
    }

    public List<NotificationResponse> notifications(String userId) {
        return notifications.stream()
                .filter(notification -> notification.recipientUserId() == null || Objects.equals(notification.recipientUserId(), userId))
                .filter(notification -> notification.kind() != NotificationKind.MESSAGE && notification.kind() != NotificationKind.CALL)
                .sorted(Comparator.comparingInt((NotificationResponse notification) -> notificationSequenceValue(notification.id())).reversed())
                .toList();
    }

    public void publishRelationNotification(
            String recipientUserId,
            String actorUserId,
            String kind,
            String title,
            String body,
            String actionTarget
    ) {
        publishRelationNotification(recipientUserId, actorUserId, kind, title, body, actionTarget, null, Map.of());
    }

    public void publishRelationNotification(
            String recipientUserId,
            String actorUserId,
            String kind,
            String title,
            String body,
            String actionTarget,
            String threadId,
            Map<String, Object> extraPayload
    ) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return;
        }
        NotificationKind notificationKind = parseNotificationKind(kind);
        NotificationResponse notification = newNotification(notificationKind, recipientUserId, threadId, title, body, actionTarget, false);
        notifications.add(notification);
        Map<String, Object> payload = new LinkedHashMap<>(notificationPayload(notification));
        if (extraPayload != null) {
            payload.putAll(extraPayload);
        }
        publishToUser(
                recipientUserId,
                RealtimeEventType.NOTIFICATION_CREATED,
                actorUserId,
                recipientUserId,
                threadId,
                null,
                null,
                title,
                body,
                payload
        );
        persistState();
    }

    public NotificationResponse markRead(String userId, String notificationId) {
        for (int i = 0; i < notifications.size(); i++) {
            NotificationResponse notification = notifications.get(i);
            if (Objects.equals(notification.id(), notificationId)
                    && (notification.recipientUserId() == null || Objects.equals(notification.recipientUserId(), userId))) {
        NotificationResponse updated = new NotificationResponse(
                notification.id(),
                notification.kind(),
                notification.recipientUserId(),
                notification.threadId(),
                notification.title(),
                notification.body(),
                notification.timeLabel(),
                        true,
                        notification.actionTarget()
                );
                notifications.set(i, updated);
                persistState();
                return updated;
            }
        }
        throw new NotFoundException("Notification not found");
    }

    public List<NotificationResponse> markAllRead(String userId) {
        for (int i = 0; i < notifications.size(); i++) {
            NotificationResponse notification = notifications.get(i);
            if (notification.kind() == NotificationKind.MESSAGE || notification.kind() == NotificationKind.CALL) {
                continue;
            }
            if (notification.recipientUserId() != null && !Objects.equals(notification.recipientUserId(), userId)) {
                continue;
            }
            if (!notification.read()) {
                notifications.set(i, new NotificationResponse(
                        notification.id(),
                        notification.kind(),
                        notification.recipientUserId(),
                        notification.threadId(),
                        notification.title(),
                        notification.body(),
                        notification.timeLabel(),
                        true,
                        notification.actionTarget()
                ));
            }
        }
        persistState();
        return notifications(userId);
    }

    private void publishToThread(
            ThreadRecord thread,
            RealtimeEventType type,
            String actorUserId,
            String targetUserId,
            String threadId,
            String callId,
            String messageId,
            String title,
            String body,
            Map<String, Object> payload
    ) {
        publishEvent(thread.participantIds(), type, actorUserId, targetUserId, threadId, callId, messageId, title, body, payload);
    }

    private void publishToParticipantsExcept(
            ThreadRecord thread,
            String excludedUserId,
            RealtimeEventType type,
            String actorUserId,
            String targetUserId,
            String threadId,
            String callId,
            String messageId,
            String title,
            String body,
            Map<String, Object> payload
    ) {
        publishEvent(
                participantIdsExcept(thread, excludedUserId),
                type,
                actorUserId,
                targetUserId,
                threadId,
                callId,
                messageId,
                title,
                body,
                payload
        );
    }

    private void publishToUser(
            String userId,
            RealtimeEventType type,
            String actorUserId,
            String targetUserId,
            String threadId,
            String callId,
            String messageId,
            String title,
            String body,
            Map<String, Object> payload
    ) {
        publishEvent(List.of(userId), type, actorUserId, targetUserId, threadId, callId, messageId, title, body, payload);
    }

    private void publishToCallParticipants(
            CallRecord call,
            RealtimeEventType type,
            String actorUserId,
            String title,
            String body,
            String summaryText
    ) {
        for (String recipientUserId : List.of(call.initiatorId(), call.partnerId())) {
            publishToUser(
                    recipientUserId,
                    type,
                    actorUserId,
                    recipientUserId,
                    call.threadId(),
                    call.id(),
                    null,
                    title,
                    body,
                    callEventPayloadForUser(call, recipientUserId, summaryText)
            );
        }
    }

    private void publishNotificationsToRecipients(ThreadRecord thread, String excludedUserId, NotificationResponse notification) {
        publishToParticipantsExcept(
                thread,
                excludedUserId,
                RealtimeEventType.NOTIFICATION_CREATED,
                excludedUserId,
                null,
                null,
                null,
                null,
                notification.title(),
                notification.body(),
                notificationPayload(notification)
        );
    }

    private void publishEvent(
            List<String> recipients,
            RealtimeEventType type,
            String actorUserId,
            String targetUserId,
            String threadId,
            String callId,
            String messageId,
            String title,
            String body,
            Map<String, Object> payload
    ) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        liveDeliveryService.publish(
                recipients,
                realtimeEvent(type, actorUserId, targetUserId, threadId, callId, messageId, title, body, payload)
        );
    }

    private RealtimeEvent realtimeEvent(
            RealtimeEventType type,
            String actorUserId,
            String targetUserId,
            String threadId,
            String callId,
            String messageId,
            String title,
            String body,
            Map<String, Object> payload
    ) {
        return new RealtimeEvent(
                UUID.randomUUID().toString(),
                type,
                callId != null ? "call/" + callId : threadId != null ? "thread/" + threadId : "system",
                actorUserId,
                targetUserId,
                threadId,
                callId,
                messageId,
                title,
                body,
                payload == null ? Map.of() : new LinkedHashMap<>(payload),
                Instant.now()
        );
    }

    private List<String> participantIdsExcept(ThreadRecord thread, String excludedUserId) {
        return thread.participantIds().stream()
                .filter(userId -> !Objects.equals(userId, excludedUserId))
                .toList();
    }

    private void ensureCallParticipant(CallRecord call, String userId) {
        if (!Objects.equals(call.initiatorId(), userId) && !Objects.equals(call.partnerId(), userId)) {
            throw new NotFoundException("Call not found");
        }
    }

    private Map<String, Object> threadChangePayload(String threadId, String kind, String messageId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadId", threadId);
        payload.put("kind", kind);
        if (messageId != null) {
            payload.put("messageId", messageId);
        }
        return payload;
    }

    private Map<String, Object> threadStatePayload(String threadId, String userId, String state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadId", threadId);
        payload.put("userId", userId);
        payload.put("state", state);
        payload.put("timeLabel", timeLabel(Instant.now()));
        return payload;
    }

    private Map<String, Object> presencePayload(String userId, boolean online) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("online", online);
        payload.put("state", online ? "ONLINE" : "OFFLINE");
        payload.put("timeLabel", timeLabel(Instant.now()));
        return payload;
    }

    private Map<String, Object> notificationPayload(NotificationResponse notification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationId", notification.id());
        payload.put("kind", notification.kind().name());
        payload.put("threadId", notification.threadId());
        payload.put("title", notification.title());
        payload.put("body", notification.body());
        payload.put("timeLabel", notification.timeLabel());
        payload.put("read", notification.read());
        payload.put("actionTarget", notification.actionTarget());
        return payload;
    }

    private Map<String, Object> messageEventPayload(MessageRecord message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", message.id());
        payload.put("threadId", message.threadId());
        payload.put("senderId", message.senderId());
        payload.put("senderName", accountService.getPublicProfile(message.senderId()).displayName());
        payload.put("status", message.status().name());
        payload.put("timeLabel", message.timeLabel());
        if (message.createdAt() != null && !message.createdAt().isBlank()) {
            payload.put("createdAt", message.createdAt());
        }
        payload.put("text", message.status() == MessageStatus.RECALLED ? "" : message.text());
        payload.put("voice", message.voice());
        payload.put("gif", message.gif());
        payload.put("sticker", message.sticker());
        if (message.attachmentKind() != null) {
            payload.put("attachmentKind", message.attachmentKind().name());
        }
        if (message.attachmentUrl() != null && !message.attachmentUrl().isBlank()) {
            payload.put("attachmentUrl", message.attachmentUrl());
        }
        if (message.attachmentPreviewUrl() != null && !message.attachmentPreviewUrl().isBlank()) {
            payload.put("attachmentPreviewUrl", message.attachmentPreviewUrl());
        }
        if (message.attachmentMimeType() != null && !message.attachmentMimeType().isBlank()) {
            payload.put("attachmentMimeType", message.attachmentMimeType());
        }
        if (message.attachmentName() != null && !message.attachmentName().isBlank()) {
            payload.put("attachmentName", message.attachmentName());
        }
        if (message.attachmentDurationSeconds() != null) {
            payload.put("attachmentDurationSeconds", message.attachmentDurationSeconds());
        }
        payload.put("kind", messageKind(message).name());
        payload.put("recallable", message.status() != MessageStatus.RECALLED && message.callSummary() == null);
        if (message.callSummary() != null) {
            payload.putAll(callSummaryPayload(message.callSummary()));
        }
        return payload;
    }

    private Map<String, Object> callEventPayloadForUser(CallRecord call, String recipientUserId, String summaryText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        PublicUserCard caller = accountService.getPublicProfile(call.initiatorId());
        PublicUserCard callee = accountService.getPublicProfile(call.partnerId());
        String peerUserId = Objects.equals(recipientUserId, call.initiatorId()) ? call.partnerId() : call.initiatorId();
        String peerName = Objects.equals(peerUserId, call.initiatorId()) ? caller.displayName() : callee.displayName();
        String direction = Objects.equals(recipientUserId, call.partnerId()) ? CallDirection.INCOMING.name() : CallDirection.OUTGOING.name();

        payload.put("callId", call.id());
        payload.put("threadId", call.threadId());
        payload.put("callerId", call.initiatorId());
        payload.put("callerName", caller.displayName());
        payload.put("calleeId", call.partnerId());
        payload.put("calleeName", callee.displayName());
        payload.put("peerUserId", peerUserId);
        payload.put("peerName", peerName);
        payload.put("initiatorId", call.initiatorId());
        payload.put("partnerId", peerUserId);
        payload.put("callType", call.callType().name());
        payload.put("direction", direction);
        payload.put("status", call.status());
        payload.put("durationSeconds", call.durationSeconds());
        payload.put("minimized", call.minimized());
        payload.put("startedAtLabel", timeLabel(call.startedAt()));
        payload.put("answeredAtLabel", call.answeredAt() == null ? "" : timeLabel(call.answeredAt()));
        payload.put("endedAtLabel", call.endedAt() == null ? "" : timeLabel(call.endedAt()));
        payload.put("partnerName", peerName);
        if (call.endReason() != null) {
            payload.put("endReason", call.endReason().name());
        }
        if (summaryText != null) {
            payload.put("summaryText", summaryText);
        }
        return payload;
    }

    private Map<String, Object> callSummaryPayload(CallSummaryResponse summary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callId", summary.id());
        payload.put("participantName", summary.participantName());
        payload.put("callType", summary.callType().name());
        payload.put("direction", summary.direction().name());
        payload.put("durationSeconds", summary.durationSeconds());
        payload.put("endReason", summary.endReason().name());
        payload.put("startedAtLabel", summary.startedAtLabel());
        payload.put("endedAtLabel", summary.endedAtLabel() == null ? "" : summary.endedAtLabel());
        payload.put("micOn", summary.isMicOn());
        payload.put("videoOn", summary.isVideoOn());
        payload.put("summaryText", summaryText(summary));
        return payload;
    }

    private void loadPersistedState() {
        moduleStateStore.load("social", SocialState.class).ifPresentOrElse(state -> {
            threads.clear();
            if (state.threads() != null) {
                for (Map.Entry<String, ThreadState> entry : state.threads().entrySet()) {
                    threads.put(entry.getKey(), toThreadRecord(entry.getValue()));
                }
            }

            calls.clear();
            if (state.calls() != null) {
                for (Map.Entry<String, CallState> entry : state.calls().entrySet()) {
                    calls.put(entry.getKey(), toCallRecord(entry.getValue()));
                }
            }

            notifications.clear();
            if (state.notifications() != null) {
                notifications.addAll(state.notifications());
            }

            deletedMessagesForUsers.clear();
            if (state.deletedMessagesForUsers() != null) {
                deletedMessagesForUsers.putAll(state.deletedMessagesForUsers());
            }

            boolean cleanedLegacyDemoState = removeLegacyDemoState();
            boolean migratedHiddenThreads = false;
            for (ThreadRecord thread : threads.values()) {
                for (String hiddenUserId : thread.hiddenForUserIds()) {
                    migratedHiddenThreads = markThreadMessagesDeletedForUser(thread, hiddenUserId) || migratedHiddenThreads;
                }
            }

            messageSequence.set(Math.max(1, state.messageSequence()));
            callSequence.set(Math.max(1, state.callSequence()));
            notificationSequence.set(Math.max(1, state.notificationSequence()));
            if (cleanedLegacyDemoState || migratedHiddenThreads) {
                persistState();
            }
        }, this::persistState);
    }

    private boolean removeLegacyDemoState() {
        boolean removed = threads.entrySet().removeIf(entry ->
                LEGACY_DEMO_THREAD_IDS.contains(entry.getKey())
                        || entry.getValue().participantIds().stream().anyMatch(LEGACY_DEMO_USER_IDS::contains)
        );
        removed = calls.entrySet().removeIf(entry ->
                LEGACY_DEMO_CALL_IDS.contains(entry.getKey())
                        || LEGACY_DEMO_USER_IDS.contains(entry.getValue().initiatorId())
                        || LEGACY_DEMO_USER_IDS.contains(entry.getValue().partnerId())
        ) || removed;
        removed = notifications.removeIf(notification ->
                LEGACY_DEMO_USER_IDS.contains(notification.recipientUserId())
                        || referencesLegacyDemo(notification.threadId())
                        || referencesLegacyDemo(notification.actionTarget())
        ) || removed;
        deletedMessagesForUsers.values().forEach(userIds -> userIds.removeAll(LEGACY_DEMO_USER_IDS));
        return removed;
    }

    private boolean referencesLegacyDemo(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return LEGACY_DEMO_THREAD_IDS.stream().anyMatch(value::contains)
                || LEGACY_DEMO_CALL_IDS.stream().anyMatch(value::contains);
    }

    private void persistState() {
        Map<String, ThreadState> threadStates = new LinkedHashMap<>();
        for (Map.Entry<String, ThreadRecord> entry : threads.entrySet()) {
            threadStates.put(entry.getKey(), toThreadState(entry.getValue()));
        }

        Map<String, CallState> callStates = new LinkedHashMap<>();
        for (Map.Entry<String, CallRecord> entry : calls.entrySet()) {
            callStates.put(entry.getKey(), toCallState(entry.getValue()));
        }

        moduleStateStore.save("social", new SocialState(
                threadStates,
                callStates,
                new ArrayList<>(notifications),
                new LinkedHashMap<>(deletedMessagesForUsers),
                messageSequence.get(),
                callSequence.get(),
                notificationSequence.get()
        ));
    }

    private ThreadRecord toThreadRecord(ThreadState state) {
        ThreadRecord thread = new ThreadRecord(
                state.id(),
                state.type(),
                state.participantIds() == null ? List.of() : state.participantIds(),
                new CopyOnWriteArrayList<>(state.messages() == null ? List.of() : state.messages()),
                state.lastMessage(),
                state.unreadCount(),
                state.online(),
                state.typing(),
                state.pinned(),
                state.matchLabel(),
                state.updatedAt(),
                state.lastCallId()
        );
        if (state.hiddenForUserIds() != null) {
            thread.hiddenForUserIds().addAll(state.hiddenForUserIds());
        }
        return thread;
    }

    private CallRecord toCallRecord(CallState state) {
        return new CallRecord(
                state.id(),
                state.threadId(),
                state.initiatorId(),
                state.partnerId(),
                state.callType(),
                state.direction(),
                state.startedAt(),
                state.answeredAt(),
                state.endedAt(),
                state.durationSeconds(),
                state.endReason(),
                state.micOn(),
                state.videoOn(),
                state.minimized(),
                state.status()
        );
    }

    private ThreadState toThreadState(ThreadRecord thread) {
        return new ThreadState(
                thread.id(),
                thread.type(),
                new ArrayList<>(thread.participantIds()),
                new ArrayList<>(thread.messages()),
                new LinkedHashSet<>(thread.hiddenForUserIds()),
                thread.lastMessage(),
                thread.unreadCount(),
                thread.online(),
                thread.typing(),
                thread.pinned(),
                thread.matchLabel(),
                thread.updatedAt(),
                thread.lastCallId()
        );
    }

    private CallState toCallState(CallRecord call) {
        return new CallState(
                call.id(),
                call.threadId(),
                call.initiatorId(),
                call.partnerId(),
                call.callType(),
                call.direction(),
                call.startedAt(),
                call.answeredAt(),
                call.endedAt(),
                call.durationSeconds(),
                call.endReason(),
                call.micOn(),
                call.videoOn(),
                call.minimized(),
                call.status()
        );
    }

    private ThreadRecord resolveThreadForCall(String userId, String threadId, String peerUserId) {
        ThreadRecord existing = threads.get(threadId);
        if (existing != null) {
            ensureParticipant(existing, userId);
            return existing;
        }

        String peer = peerUserId == null ? "" : peerUserId.trim();
        if (peer.isBlank() || Objects.equals(peer, userId)) {
            throw new NotFoundException("Thread not found");
        }

        PublicUserCard peerProfile = accountService.getPublicProfile(peer);
        return threads.values().stream()
                .filter(thread -> thread.type() == ThreadType.DIRECT)
                .filter(thread -> thread.participantIds().contains(userId))
                .filter(thread -> thread.participantIds().contains(peer))
                .findFirst()
                .orElseGet(() -> createDirectThread(userId, peer, peerProfile));
    }

    private ThreadRecord resolveThreadForOpen(String userId, String threadId) {
        ThreadRecord existing = threads.get(threadId);
        if (existing != null) {
            ensureParticipant(existing, userId);
            return existing;
        }

        String peerUserId = directThreadAliasPeer(threadId);
        if (peerUserId.isBlank() || Objects.equals(peerUserId, userId)) {
            throw new NotFoundException("Thread not found");
        }
        PublicUserCard peerProfile = accountService.getPublicProfile(peerUserId);
        return threads.values().stream()
                .filter(thread -> thread.type() == ThreadType.DIRECT)
                .filter(thread -> thread.participantIds().contains(userId))
                .filter(thread -> thread.participantIds().contains(peerUserId))
                .findFirst()
                .orElseGet(() -> createDirectThread(userId, peerUserId, peerProfile));
    }

    private String directThreadAliasPeer(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return "";
        }
        String trimmed = threadId.trim();
        if (trimmed.startsWith("dm-")) {
            return trimmed.substring(3);
        }
        if (trimmed.startsWith("direct-")) {
            return trimmed.substring(7);
        }
        return "";
    }

    private ThreadRecord createDirectThread(String userId, String peerUserId, PublicUserCard peerProfile) {
        ThreadRecord thread = new ThreadRecord(
                "thread-" + UUID.randomUUID(),
                ThreadType.DIRECT,
                List.of(userId, peerUserId),
                new CopyOnWriteArrayList<>(),
                "",
                0,
                peerProfile.online(),
                false,
                false,
                "Direct",
                timeLabel(Instant.now()),
                null
        );
        threads.put(thread.id(), thread);
        return thread;
    }

    private ThreadRecord requireThread(String threadId) {
        ThreadRecord thread = threads.get(threadId);
        if (thread == null) {
            throw new NotFoundException("Thread not found");
        }
        return thread;
    }

    private int notificationSequenceValue(String notificationId) {
        if (notificationId == null) {
            return 0;
        }
        int dash = notificationId.lastIndexOf('-');
        if (dash < 0 || dash == notificationId.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(notificationId.substring(dash + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private CallRecord requireCall(String callId) {
        CallRecord call = calls.get(callId);
        if (call == null) {
            throw new NotFoundException("Call not found");
        }
        return call;
    }

    private void ensureParticipant(ThreadRecord thread, String userId) {
        if (!thread.participantIds().contains(userId)) {
            throw new NotFoundException("Thread not found");
        }
    }

    private MessageRecord requireMessage(ThreadRecord thread, String messageId) {
        return thread.messages().stream()
                .filter(message -> Objects.equals(message.id(), messageId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Message not found"));
    }

    private int indexOfMessage(ThreadRecord thread, String messageId) {
        for (int i = 0; i < thread.messages().size(); i++) {
            if (Objects.equals(thread.messages().get(i).id(), messageId)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfMessage(List<MessageRecord> messages, String messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (Objects.equals(messages.get(i).id(), messageId)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isMessageVisibleToUser(MessageRecord message, String userId) {
        return !deletedMessagesForUsers.getOrDefault(message.id(), Set.of()).contains(userId);
    }

    private boolean markThreadMessagesDeletedForUser(ThreadRecord thread, String userId) {
        boolean changed = false;
        for (MessageRecord message : thread.messages()) {
            Set<String> deletedForMessage = deletedMessagesForUsers
                    .computeIfAbsent(message.id(), key -> ConcurrentHashMap.newKeySet());
            if (deletedForMessage.add(userId)) {
                changed = true;
            }
        }
        return changed;
    }

    private String previewText(ThreadRecord thread, String userId) {
        for (int i = thread.messages().size() - 1; i >= 0; i--) {
            MessageRecord message = thread.messages().get(i);
            if (!isMessageVisibleToUser(message, userId)) {
                continue;
            }
            String preview = renderPreviewText(message, userId);
            if (preview != null && !preview.isBlank()) {
                return preview;
            }
        }
        return "";
    }

    private String renderPreviewText(MessageRecord message, String currentUserId) {
        if (message.status() == MessageStatus.RECALLED) {
            return Objects.equals(message.senderId(), currentUserId)
                    ? "You unsent a message"
                    : "This message was unsent";
        }
        if (message.callSummary() != null) {
            return summaryText(message.callSummary());
        }
        if (message.attachmentKind() != null) {
            return attachmentPreviewText(message);
        }
        if (!message.text().isBlank()) {
            return message.text();
        }
        if (message.voice()) {
            return "Voice message";
        }
        if (message.gif()) {
            return "GIF";
        }
        if (message.sticker()) {
            return "Sticker";
        }
        return "";
    }

    private String attachmentPreviewText(MessageRecord message) {
        return switch (message.attachmentKind()) {
            case IMAGE -> "Photo";
            case VIDEO -> "Video";
            case AUDIO, VOICE -> message.attachmentDurationSeconds() != null && message.attachmentDurationSeconds() > 0
                    ? "Voice message - " + formatDuration(message.attachmentDurationSeconds())
                    : "Voice message";
            case FILE -> {
                if (message.attachmentName() != null && !message.attachmentName().isBlank()) {
                    yield message.attachmentName();
                }
                yield "File";
            }
            case GIF -> "GIF";
            case STICKER -> "Sticker";
            case CALL_LOG -> summaryText(message.callSummary());
            case TEXT -> message.text();
        };
    }

    private MessageKind inferAttachmentKind(String mimeType, String name, String url) {
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (mimeType != null) {
            if (mimeType.startsWith("image/")) {
                return MessageKind.IMAGE;
            }
            if (mimeType.startsWith("video/")) {
                return MessageKind.VIDEO;
            }
            if (mimeType.startsWith("audio/")) {
                return MessageKind.AUDIO;
            }
        }
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".webp") || lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".webp")) {
            return MessageKind.IMAGE;
        }
        if (lowerName.endsWith(".mp4") || lowerName.endsWith(".mov") || lowerName.endsWith(".mkv") || lowerName.endsWith(".webm") || lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".mov") || lowerUrl.endsWith(".mkv") || lowerUrl.endsWith(".webm")) {
            return MessageKind.VIDEO;
        }
        if (lowerName.endsWith(".mp3") || lowerName.endsWith(".m4a") || lowerName.endsWith(".aac") || lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") || lowerUrl.endsWith(".mp3") || lowerUrl.endsWith(".m4a") || lowerUrl.endsWith(".aac") || lowerUrl.endsWith(".wav") || lowerUrl.endsWith(".ogg")) {
            return MessageKind.AUDIO;
        }
        return MessageKind.FILE;
    }

    private MessageKind messageKind(MessageRecord message) {
        if (message.callSummary() != null) {
            return MessageKind.CALL_LOG;
        }
        if (message.attachmentKind() != null) {
            return message.attachmentKind();
        }
        if (message.voice()) {
            return MessageKind.AUDIO;
        }
        if (message.gif()) {
            return MessageKind.GIF;
        }
        if (message.sticker()) {
            return MessageKind.STICKER;
        }
        return MessageKind.TEXT;
    }

    private Instant parseTimeLabel(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            java.time.LocalTime time = java.time.LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
            return time.atDate(java.time.LocalDate.now()).atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception ex) {
            return Instant.now();
        }
    }

    private ChatThreadResponse toThreadResponse(ThreadRecord thread, String currentUserId) {
        String otherId = thread.otherParticipant(currentUserId);
        PublicUserCard participant = accountService.getPublicProfile(otherId);
        return toThreadResponse(thread, currentUserId, participant);
    }

    private ChatThreadResponse toThreadResponse(ThreadRecord thread, String currentUserId, PublicUserCard participant) {
        return new ChatThreadResponse(
                thread.id(),
                thread.type(),
                participant,
                previewText(thread, currentUserId),
                thread.unreadCount(),
                participant.online(),
                thread.typing(),
                thread.pinned(),
                thread.matchLabel(),
                thread.updatedAt()
        );
    }

    private boolean matchesThreadSearch(PublicUserCard participant, String normalizedQuery) {
        return containsNormalized(participant.displayName(), normalizedQuery)
                || containsNormalized(participant.username(), normalizedQuery)
                || containsNormalized(participant.publicId(), normalizedQuery)
                || containsNormalized(participant.userId(), normalizedQuery);
    }

    private boolean containsNormalized(String value, String normalizedQuery) {
        return value != null && normalize(value).contains(normalizedQuery);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private ChatMessageResponse toMessageResponse(MessageRecord message, String currentUserId) {
        boolean deletedForMe = deletedMessagesForUsers
                .getOrDefault(message.id(), Set.of())
                .contains(currentUserId);
        String text = message.text();
        if (message.status() == MessageStatus.RECALLED) {
            text = Objects.equals(message.senderId(), currentUserId)
                    ? "You unsent a message"
                    : "This message was unsent";
        }
        if (deletedForMe) {
            text = "";
        }
        return new ChatMessageResponse(
                message.id(),
                message.threadId(),
                text,
                Objects.equals(message.senderId(), currentUserId),
                message.timeLabel(),
                message.voice(),
                message.gif(),
                message.sticker(),
                message.attachmentKind() == null ? null : message.attachmentKind().name(),
                message.attachmentUrl(),
                message.attachmentPreviewUrl(),
                message.attachmentMimeType(),
                message.attachmentName(),
                message.attachmentDurationSeconds(),
                null,
                message.status() == MessageStatus.SEEN,
                message.callSummary() == null ? null : summaryForCallLog(message.callSummary(), currentUserId),
                message.status(),
                message.createdAt()
        );
    }

    private CallSessionResponse callSession(CallRecord call, String currentUserId) {
        return new CallSessionResponse(call.id(), call.threadId(), summary(call, currentUserId), call.status(), call.minimized());
    }

    private CallSummaryResponse summary(CallRecord call, String currentUserId) {
        boolean currentUserIsCallee = Objects.equals(currentUserId, call.partnerId());
        String peerUserId = currentUserIsCallee ? call.initiatorId() : call.partnerId();
        PublicUserCard partner = accountService.getPublicProfile(peerUserId);
        return new CallSummaryResponse(
                call.id(),
                partner.displayName(),
                call.callType(),
                currentUserIsCallee ? CallDirection.INCOMING : CallDirection.OUTGOING,
                call.durationSeconds(),
                call.endReason() == null ? CallEndReason.COMPLETED : call.endReason(),
                timeLabel(call.startedAt()),
                call.endedAt() == null ? null : timeLabel(call.endedAt()),
                call.micOn(),
                call.videoOn()
        );
    }

    private CallSummaryResponse summaryForCallLog(CallSummaryResponse summary, String currentUserId) {
        CallRecord call = calls.get(summary.id());
        if (call == null) {
            return summary;
        }
        return summary(call, currentUserId);
    }

    private String summaryText(CallSummaryResponse summary) {
        if (summary.durationSeconds() > 0) {
            return "Connected " + formatDuration(summary.durationSeconds());
        }
        return switch (summary.endReason()) {
            case MISSED -> "Missed call";
            case NO_ANSWER -> "No answer";
            case DECLINED -> "Declined call";
            case REJECTED -> "Rejected call";
            case BUSY -> "Busy";
            case CANCELED -> "Canceled call";
            case DROPPED -> "Call dropped";
            default -> "Call ended";
        };
    }

    private NotificationResponse newNotification(NotificationKind kind, String recipientUserId, String threadId, String title, String body, String actionTarget, boolean read) {
        return new NotificationResponse(
                "notif-" + notificationSequence.getAndIncrement(),
                kind,
                recipientUserId,
                threadId,
                title,
                body,
                timeLabel(Instant.now()),
                read,
                actionTarget
        );
    }

    private NotificationKind parseNotificationKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return NotificationKind.SYSTEM;
        }
        try {
            return NotificationKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return NotificationKind.SYSTEM;
        }
    }

    private void notifyUsers(List<String> recipientUserIds, NotificationKind kind, String threadId, String title, String body, String actionTarget) {
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        for (String recipientUserId : recipientUserIds) {
            if (recipientUserId == null || recipientUserId.isBlank()) {
                continue;
            }
            NotificationResponse notification = newNotification(kind, recipientUserId, threadId, title, body, actionTarget, false);
            notifications.add(notification);
            publishToUser(
                    recipientUserId,
                    RealtimeEventType.NOTIFICATION_CREATED,
                    recipientUserId,
                    recipientUserId,
                    threadId,
                    null,
                    null,
                    title,
                    body,
                    notificationPayload(notification)
            );
        }
    }

    private String timeLabel(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                .toLocalTime()
                .withSecond(0)
                .withNano(0)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String formatDuration(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static final class ThreadRecord {
        final String id;
        final ThreadType type;
        final List<String> participantIds;
        final List<MessageRecord> messages;
        final Set<String> hiddenForUserIds;
        String lastMessage;
        int unreadCount;
        boolean online;
        boolean typing;
        boolean pinned;
        String matchLabel;
        String updatedAt;
        String lastCallId;

        ThreadRecord(
                String id,
                ThreadType type,
                List<String> participantIds,
                List<MessageRecord> messages,
                String lastMessage,
                int unreadCount,
                boolean online,
                boolean typing,
                boolean pinned,
                String matchLabel,
                String updatedAt,
                String lastCallId
        ) {
            this.id = id;
            this.type = type;
            this.participantIds = participantIds;
            this.messages = messages;
            this.hiddenForUserIds = ConcurrentHashMap.newKeySet();
            this.lastMessage = lastMessage;
            this.unreadCount = unreadCount;
            this.online = online;
            this.typing = typing;
            this.pinned = pinned;
            this.matchLabel = matchLabel;
            this.updatedAt = updatedAt;
            this.lastCallId = lastCallId;
        }

        String id() { return id; }
        ThreadType type() { return type; }
        List<String> participantIds() { return participantIds; }
        List<MessageRecord> messages() { return messages; }
        Set<String> hiddenForUserIds() { return hiddenForUserIds; }
        String lastMessage() { return lastMessage; }
        int unreadCount() { return unreadCount; }
        boolean online() { return online; }
        boolean typing() { return typing; }
        boolean pinned() { return pinned; }
        String matchLabel() { return matchLabel; }
        String updatedAt() { return updatedAt; }
        String lastCallId() { return lastCallId; }

        String otherParticipant(String currentUserId) {
            return participantIds.stream()
                    .filter(participant -> !Objects.equals(participant, currentUserId))
                    .findFirst()
                    .orElse(currentUserId);
        }
    }

    private record MessageRecord(
            String id,
            String threadId,
            String senderId,
            String text,
            boolean voice,
            boolean gif,
            boolean sticker,
            MessageKind attachmentKind,
            String attachmentUrl,
            String attachmentPreviewUrl,
            String attachmentMimeType,
            String attachmentName,
            Integer attachmentDurationSeconds,
            CallSummaryResponse callSummary,
            MessageStatus status,
            String timeLabel,
            String createdAt
    ) {
        private MessageRecord(
                String id,
                String threadId,
                String senderId,
                String text,
                boolean voice,
                boolean gif,
                boolean sticker,
                MessageKind attachmentKind,
                String attachmentUrl,
                String attachmentPreviewUrl,
                String attachmentMimeType,
                String attachmentName,
                Integer attachmentDurationSeconds,
                CallSummaryResponse callSummary,
                MessageStatus status,
                String timeLabel
        ) {
            this(
                    id,
                    threadId,
                    senderId,
                    text,
                    voice,
                    gif,
                    sticker,
                    attachmentKind,
                    attachmentUrl,
                    attachmentPreviewUrl,
                    attachmentMimeType,
                    attachmentName,
                    attachmentDurationSeconds,
                    callSummary,
                    status,
                    timeLabel,
                    null
            );
        }
    }

    private record ThreadSearchCandidate(
            ThreadRecord thread,
            PublicUserCard participant
    ) {
    }

    private static final class CallRecord {
        final String id;
        final String threadId;
        final String initiatorId;
        final String partnerId;
        final CallType callType;
        final CallDirection direction;
        final Instant startedAt;
        Instant answeredAt;
        Instant endedAt;
        int durationSeconds;
        CallEndReason endReason;
        final boolean micOn;
        final boolean videoOn;
        boolean minimized;
        String status;

        CallRecord(
                String id,
                String threadId,
                String initiatorId,
                String partnerId,
                CallType callType,
                CallDirection direction,
                Instant startedAt,
                Instant answeredAt,
                Instant endedAt,
                int durationSeconds,
                CallEndReason endReason,
                boolean micOn,
                boolean videoOn,
                boolean minimized,
                String status
        ) {
            this.id = id;
            this.threadId = threadId;
            this.initiatorId = initiatorId;
            this.partnerId = partnerId;
            this.callType = callType;
            this.direction = direction;
            this.startedAt = startedAt;
            this.answeredAt = answeredAt;
            this.endedAt = endedAt;
            this.durationSeconds = durationSeconds;
            this.endReason = endReason;
            this.micOn = micOn;
            this.videoOn = videoOn;
            this.minimized = minimized;
            this.status = status;
        }

        String id() { return id; }
        String threadId() { return threadId; }
        String initiatorId() { return initiatorId; }
        String partnerId() { return partnerId; }
        CallType callType() { return callType; }
        CallDirection direction() { return direction; }
        Instant startedAt() { return startedAt; }
        Instant answeredAt() { return answeredAt; }
        Instant endedAt() { return endedAt; }
        int durationSeconds() { return durationSeconds; }
        CallEndReason endReason() { return endReason; }
        boolean micOn() { return micOn; }
        boolean videoOn() { return videoOn; }
        boolean minimized() { return minimized; }
        String status() { return status; }
    }

    private record SocialState(
            Map<String, ThreadState> threads,
            Map<String, CallState> calls,
            List<NotificationResponse> notifications,
            Map<String, Set<String>> deletedMessagesForUsers,
            int messageSequence,
            int callSequence,
            int notificationSequence
    ) {
    }

    private record ThreadState(
            String id,
            ThreadType type,
            List<String> participantIds,
            List<MessageRecord> messages,
            Set<String> hiddenForUserIds,
            String lastMessage,
            int unreadCount,
            boolean online,
            boolean typing,
            boolean pinned,
            String matchLabel,
            String updatedAt,
            String lastCallId
    ) {
    }

    private record CallState(
            String id,
            String threadId,
            String initiatorId,
            String partnerId,
            CallType callType,
            CallDirection direction,
            Instant startedAt,
            Instant answeredAt,
            Instant endedAt,
            int durationSeconds,
            CallEndReason endReason,
            boolean micOn,
            boolean videoOn,
            boolean minimized,
            String status
    ) {
    }
}
