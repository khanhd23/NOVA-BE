package com.nova.backend.social;

import com.nova.backend.account.PublicUserCard;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

enum ThreadType {
    DIRECT,
    GROUP
}

enum MessageKind {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    VOICE,
    GIF,
    STICKER,
    CALL_LOG
}

enum MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    SEEN,
    FAILED,
    RECALLED
}

enum CallType {
    VOICE,
    VIDEO
}

enum CallDirection {
    INCOMING,
    OUTGOING
}

enum CallEndReason {
    COMPLETED,
    MISSED,
    NO_ANSWER,
    DECLINED,
    REJECTED,
    BUSY,
    CANCELED,
    DROPPED
}

enum NotificationKind {
    MESSAGE,
    CALL,
    FOLLOW,
    FRIEND,
    POKE,
    PROFILE_LIKE,
    COMMUNITY,
    EVENT,
    SYSTEM
}

record ChatThreadResponse(
        String id,
        ThreadType type,
        PublicUserCard participant,
        String lastMessage,
        int unreadCount,
        boolean online,
        boolean typing,
        boolean pinned,
        String matchLabel,
        String updatedAt
) {
}

record ChatMessageResponse(
        String id,
        String threadId,
        String text,
        boolean sentByMe,
        String timeLabel,
        boolean isVoice,
        boolean isGif,
        boolean isSticker,
        String attachmentKind,
        String attachmentUrl,
        String attachmentPreviewUrl,
        String attachmentMimeType,
        String attachmentName,
        Integer attachmentDurationSeconds,
        String translatedText,
        boolean isRead,
        CallSummaryResponse callSummary,
        MessageStatus status,
        String createdAt
) {
}

record ThreadDetailResponse(
        ChatThreadResponse thread,
        List<ChatMessageResponse> messages,
        boolean hasMore,
        String nextCursor
) {
}

record SendMessageRequest(
        String text,
        String attachmentUrl,
        String attachmentPreviewUrl,
        String attachmentMimeType,
        String attachmentName,
        MessageKind attachmentKind,
        Integer attachmentDurationSeconds
) {
}

record DeleteThreadRequest(
        String reason
) {
}

record DeleteMessageRequest(
        String reason
) {
}

record RecallMessageRequest(
        String reason
) {
}

record EditMessageRequest(
        String text
) {
}

record TypingStateRequest(
        boolean typing
) {
}

record CreateCallRequest(
        @NotNull CallType callType,
        @NotNull CallDirection direction,
        String peerUserId
) {
}

record EndCallRequest(
        @NotNull CallEndReason reason
) {
}

record CallSummaryResponse(
        String id,
        String participantName,
        CallType callType,
        CallDirection direction,
        int durationSeconds,
        CallEndReason endReason,
        String startedAtLabel,
        String endedAtLabel,
        boolean isMicOn,
        boolean isVideoOn
) {
}

record CallSessionResponse(
        String id,
        String threadId,
        CallSummaryResponse summary,
        String status,
        boolean minimized
) {
}

record CallListResponse(
        List<CallSummaryResponse> items
) {
}

record NotificationResponse(
        String id,
        NotificationKind kind,
        String recipientUserId,
        String threadId,
        String title,
        String body,
        String timeLabel,
        boolean read,
        String actionTarget
) {
}

record ReadNotificationRequest(
        @NotBlank String notificationId
) {
}
