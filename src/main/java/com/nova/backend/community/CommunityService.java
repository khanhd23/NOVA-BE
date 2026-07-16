package com.nova.backend.community;

import com.nova.backend.account.AccountService;
import com.nova.backend.account.PublicUserCard;
import com.nova.backend.common.ModuleStateStore;
import com.nova.backend.common.exception.NotFoundException;
import com.nova.backend.realtime.LiveDeliveryService;
import com.nova.backend.realtime.RealtimeEvent;
import com.nova.backend.realtime.RealtimeEventType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CommunityService {

    private static final Pattern TAG_PATTERN = Pattern.compile("#([\\p{L}0-9_]+)");
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([\\p{L}0-9_.-]+)");

    private final AccountService accountService;
    private final LiveDeliveryService liveDeliveryService;
    private final ModuleStateStore moduleStateStore;
    private final Map<String, CommunityTopicState> topics = new LinkedHashMap<>();
    private final Map<String, CommunityPostState> posts = new LinkedHashMap<>();
    private final Map<String, EventState> events = new LinkedHashMap<>();
    private final Map<String, Integer> tagHotness = new LinkedHashMap<>();
    private final AtomicInteger postSequence = new AtomicInteger(1);
    private final AtomicInteger commentSequence = new AtomicInteger(1);
    private final AtomicInteger refreshSequence = new AtomicInteger(1);

    public CommunityService(
            AccountService accountService,
            LiveDeliveryService liveDeliveryService,
            ModuleStateStore moduleStateStore
    ) {
        this.accountService = accountService;
        this.liveDeliveryService = liveDeliveryService;
        this.moduleStateStore = moduleStateStore;
        seed();
        loadPersistedState();
    }

    public CommunityFeedResponse feed(String userId, String tab, String cursor, boolean refresh, int size) {
        int pageSize = Math.max(1, Math.min(size, 50));
        String normalizedTab = normalizeTab(tab);
        List<CommunityPostState> ranked = rankedPosts(userId, normalizedTab, refresh);
        int startIndex = 0;
        if (cursor != null && !cursor.isBlank()) {
            int cursorIndex = indexOfPost(ranked, cursor.trim());
            if (cursorIndex >= 0) {
                startIndex = Math.min(ranked.size(), cursorIndex + 1);
            }
        }
        List<CommunityPostState> page = ranked.stream()
                .skip(startIndex)
                .limit(pageSize)
                .toList();
        String nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).id();
        boolean hasMore = startIndex + page.size() < ranked.size();
        return new CommunityFeedResponse(
                topics.values().stream().map(topic -> toTopic(topic, userId)).toList(),
                page.stream().map(post -> toPostResponse(post, userId)).toList(),
                events.values().stream().map(event -> toEvent(event, userId)).toList(),
                trendingTags(8),
                List.of("TEXT", "IMAGE", "VIDEO", "VOICE", "LINK", "POLL"),
                "refresh-" + refreshSequence.getAndIncrement(),
                nextCursor,
                hasMore
        );
    }

    public CommunityTopicResponse topic(String userId, String topicId) {
        return toTopic(requireTopic(topicId), userId);
    }

    public CommunityTopicResponse joinTopic(String userId, String topicId, JoinRequest request) {
        CommunityTopicState topic = requireTopic(topicId);
        CommunityTopicState updated = new CommunityTopicState(
                topic.id(),
                topic.title(),
                topic.description(),
                topic.bannerUrl(),
                request.joined() ? increaseCount(topic.members()) : decreaseCount(topic.members()),
                topic.moderator(),
                topic.eventCount(),
                request.joined()
        );
        topics.put(topicId, updated);
        persistState();
        return toTopic(updated, userId);
    }

    public List<CommunityPostResponse> communityPosts(String userId, String tab, String cursor, boolean refresh, int size) {
        return feed(userId, tab, cursor, refresh, size).posts();
    }

    public CommunityPostResponse createPost(String userId, CreateCommunityPostRequest request) {
        CommunityTopicState topic = requireTopic(request.topicId());
        String postType = normalizePostType(request.postType());
        List<String> tags = normalizeTags(request.tags(), request.text());
        List<String> mentionedUserIds = resolveMentionTargets(request.mentionedUserIds(), request.text());
        List<String> mediaUrls = normalizeMediaUrls(request.mediaUrls(), request.mediaUrl());
        String primaryMediaUrl = mediaUrls.isEmpty() ? blankToNull(request.mediaUrl()) : mediaUrls.get(0);
        CommunityPostState post = new CommunityPostState(
                "community-post-" + postSequence.getAndIncrement(),
                topic.id(),
                userId,
                postType,
                request.text().trim(),
                primaryMediaUrl,
                new ArrayList<>(mediaUrls),
                blankToNull(request.thumbnailUrl()),
                new ArrayList<>(tags),
                new ArrayList<>(mentionedUserIds),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                0,
                Instant.now()
        );
        posts.put(post.id(), post);
        bumpTagHotness(tags);
        notifyMentions(userId, post, mentionedUserIds, "tagged you in a post");
        persistState();
        return toPostResponse(post, userId);
    }

    public CommunityPostResponse toggleLike(String userId, String postId, boolean liked) {
        CommunityPostState current = requirePost(postId);
        Set<String> likes = new LinkedHashSet<>(current.likedByUserIds());
        boolean changed = liked ? likes.add(userId) : likes.remove(userId);
        CommunityPostState updated = current.withLikes(new ArrayList<>(likes));
        posts.put(postId, updated);
        if (liked && changed && !Objects.equals(current.authorUserId(), userId)) {
            notifyAuthor(current.authorUserId(), userId, current, "liked your post", "COMMUNITY", "community/post/" + postId);
        }
        persistState();
        return toPostResponse(updated, userId);
    }

    public CommunityPostResponse addComment(String userId, String postId, CreateCommunityCommentRequest request) {
        CommunityPostState current = requirePost(postId);
        List<String> mentions = resolveMentionTargets(List.of(), request.text());
        CommunityCommentState comment = new CommunityCommentState(
                "community-comment-" + commentSequence.getAndIncrement(),
                postId,
                userId,
                request.text().trim(),
                new ArrayList<>(mentions),
                Instant.now()
        );
        List<CommunityCommentState> comments = new ArrayList<>(current.comments());
        comments.add(comment);
        CommunityPostState updated = current.withComments(comments);
        posts.put(postId, updated);
        if (!mentions.isEmpty()) {
            notifyMentions(userId, updated, mentions, "mentioned you in a comment");
        }
        if (!Objects.equals(current.authorUserId(), userId)) {
            notifyAuthor(current.authorUserId(), userId, updated, "commented on your post", "COMMUNITY", "community/post/" + postId);
        }
        persistState();
        return toPostResponse(updated, userId);
    }

    public ShareCommunityPostResponse sharePost(String userId, String postId, ShareCommunityPostRequest request) {
        CommunityPostState current = requirePost(postId);
        Set<String> shares = new LinkedHashSet<>(current.sharedByUserIds());
        shares.add(userId);
        CommunityPostState updated = current.withShared(new ArrayList<>(shares), current.shareCount() + 1);
        posts.put(postId, updated);
        persistState();
        String shareUrl = "https://nova.app/community/post/" + postId;
        if (request != null && request.recipientUserId() != null && !request.recipientUserId().isBlank()) {
            String targetUserId = request.recipientUserId().trim();
            if (!Objects.equals(targetUserId, userId)) {
                notifyAuthor(targetUserId, userId, updated, "shared a post with you", "COMMUNITY", "community/post/" + postId);
            }
        } else if (!Objects.equals(current.authorUserId(), userId)) {
            notifyAuthor(current.authorUserId(), userId, updated, "shared your post", "COMMUNITY", "community/post/" + postId);
        }
        return new ShareCommunityPostResponse(shareUrl, toPostResponse(updated, userId));
    }

    public List<CommunityTagSuggestionResponse> tagSuggestions(String userId, String query, int limit) {
        int max = Math.max(1, Math.min(limit, 20));
        String normalized = normalizeTag(query);
        List<CommunityTagSuggestionResponse> suggestions = tagHotness.entrySet().stream()
                .map(entry -> new CommunityTagSuggestionResponse(
                        entry.getKey(),
                        entry.getValue(),
                        countPostsForTag(entry.getKey()),
                        !normalized.isBlank() && entry.getKey().equals(normalized),
                        true
                ))
                .filter(item -> normalized.isBlank()
                        || item.tag().contains(normalized)
                        || item.tag().startsWith(normalized)
                        || item.tag().equals(normalized))
                .sorted(Comparator
                        .comparingInt(CommunityTagSuggestionResponse::hotness).reversed()
                        .thenComparing(item -> item.tag().startsWith(normalized) ? 0 : 1)
                        .thenComparing(CommunityTagSuggestionResponse::tag))
                .limit(max)
                .toList();

        if (!normalized.isBlank() && suggestions.stream().noneMatch(item -> item.tag().equals(normalized))) {
            List<CommunityTagSuggestionResponse> merged = new ArrayList<>();
            merged.add(new CommunityTagSuggestionResponse(normalized, 0, 0, true, true));
            merged.addAll(suggestions);
            return merged.stream().limit(max).toList();
        }
        return suggestions;
    }

    public List<EventResponse> eventList(String userId) {
        return events.values().stream().map(event -> toEvent(event, userId)).toList();
    }

    public EventResponse joinEvent(String userId, String eventId, JoinRequest request) {
        EventState event = requireEvent(eventId);
        EventState updated = new EventState(
                event.id(),
                event.title(),
                event.kind(),
                event.dateLabel(),
                event.location(),
                event.price(),
                event.bannerUrl(),
                request.joined() ? increaseCount(event.attendees()) : decreaseCount(event.attendees()),
                request.joined()
        );
        events.put(eventId, updated);
        persistState();
        return toEvent(updated, userId);
    }

    private void seed() {
        topics.put("topic-product", new CommunityTopicState("topic-product", "Product Design", "Discussions about clean UX and product details.", "https://cdn.nova/community/product.jpg", "1.2K members", "Elena Markov", 6, true));
        topics.put("topic-photo", new CommunityTopicState("topic-photo", "Photography", "Share shots, gear, and editing tips.", "https://cdn.nova/community/photo.jpg", "842 members", "Chloe Rivera", 3, false));
        topics.put("topic-travel", new CommunityTopicState("topic-travel", "Travel", "Weekend trips, hidden spots, and city guides.", "https://cdn.nova/community/travel.jpg", "3.1K members", "Mina Park", 9, false));

        seedPost(new CommunityPostState(
                "cpost-1",
                "topic-product",
                "u-elena",
                "TEXT",
                "We should keep call summary cards minimal and centered.",
                "https://cdn.nova/community/post-1.jpg",
                List.of("https://cdn.nova/community/post-1.jpg"),
                null,
                List.of("product", "compose", "ux"),
                List.of(),
                List.of("u-current"),
                List.of(),
                List.of(
                        new CommunityCommentState("cpost-1-c1", "cpost-1", "u-chloe", "Agree. The CTA should stay in one place.", List.of(), Instant.now().minusSeconds(4200)),
                        new CommunityCommentState("cpost-1-c2", "cpost-1", "u-mina", "Less noise, better focus.", List.of(), Instant.now().minusSeconds(2600))
                ),
                2,
                Instant.now().minusSeconds(1080)
        ));
        seedPost(new CommunityPostState(
                "cpost-2",
                "topic-photo",
                "u-chloe",
                "IMAGE",
                "Golden hour still wins every time.",
                "https://cdn.nova/community/post-2.jpg",
                List.of("https://cdn.nova/community/post-2.jpg", "https://cdn.nova/community/post-2b.jpg"),
                "https://cdn.nova/community/post-2-thumb.jpg",
                List.of("photography", "travel", "goldenhour"),
                List.of(),
                List.of("u-seraphina"),
                List.of("u-current"),
                List.of(),
                4,
                Instant.now().minusSeconds(3600)
        ));
        seedPost(new CommunityPostState(
                "cpost-3",
                "topic-travel",
                "u-mina",
                "VIDEO",
                "Bali routes for a 3-day trip?",
                "https://cdn.nova/community/post-3.mp4",
                List.of("https://cdn.nova/community/post-3.mp4"),
                "https://cdn.nova/community/post-3-thumb.jpg",
                List.of("travel", "weekend", "bali"),
                List.of("u-seraphina"),
                List.of(),
                List.of(),
                List.of(),
                1,
                Instant.now().minusSeconds(7200)
        ));

        events.put("event-design-night", new EventState("event-design-night", "Design Night", "Live", "Fri 8 PM", "Berlin", "$12", "https://cdn.nova/events/design-night.jpg", "248 going", false));
        events.put("event-photo-walk", new EventState("event-photo-walk", "Photo Walk", "Offline", "Sat 7 AM", "Barcelona", "Free", "https://cdn.nova/events/photo-walk.jpg", "126 going", true));
        events.put("event-community-qna", new EventState("event-community-qna", "Community Q&A", "Online", "Tonight", "Zoom", "Free", "https://cdn.nova/events/community-qna.jpg", "524 going", false));

        tagHotness.put("product", 42);
        tagHotness.put("compose", 55);
        tagHotness.put("ux", 38);
        tagHotness.put("photography", 76);
        tagHotness.put("travel", 88);
        tagHotness.put("goldenhour", 29);
        tagHotness.put("weekend", 51);
        tagHotness.put("bali", 24);
    }

    private void seedPost(CommunityPostState post) {
        posts.put(post.id(), post);
    }

    private void loadPersistedState() {
        moduleStateStore.load("community", CommunityState.class).ifPresentOrElse(state -> {
            topics.clear();
            topics.putAll(state.topics() == null ? Map.of() : state.topics());
            posts.clear();
            if (state.posts() != null) {
                posts.putAll(state.posts());
            }
            events.clear();
            events.putAll(state.events() == null ? Map.of() : state.events());
            tagHotness.clear();
            tagHotness.putAll(state.tagHotness() == null ? Map.of() : state.tagHotness());
            postSequence.set(Math.max(1, state.postSequence()));
            commentSequence.set(Math.max(1, state.commentSequence()));
            refreshSequence.set(Math.max(1, state.refreshSequence()));
        }, this::persistState);
    }

    private void persistState() {
        moduleStateStore.save("community", new CommunityState(
                new LinkedHashMap<>(topics),
                new LinkedHashMap<>(posts),
                new LinkedHashMap<>(events),
                new LinkedHashMap<>(tagHotness),
                postSequence.get(),
                commentSequence.get(),
                refreshSequence.get()
        ));
    }

    private CommunityTopicState requireTopic(String topicId) {
        CommunityTopicState topic = topics.get(topicId);
        if (topic == null) {
            throw new NotFoundException("Topic not found");
        }
        return topic;
    }

    private CommunityPostState requirePost(String postId) {
        CommunityPostState post = posts.get(postId);
        if (post == null) {
            throw new NotFoundException("Post not found");
        }
        return post;
    }

    private EventState requireEvent(String eventId) {
        EventState event = events.get(eventId);
        if (event == null) {
            throw new NotFoundException("Event not found");
        }
        return event;
    }

    private CommunityTopicResponse toTopic(CommunityTopicState topic, String userId) {
        return new CommunityTopicResponse(topic.id, topic.title, topic.description, topic.bannerUrl, topic.members, topic.moderator, topic.eventCount, topic.joined);
    }

    private EventResponse toEvent(EventState event, String userId) {
        return new EventResponse(event.id, event.title, event.kind, event.dateLabel, event.location, event.price, event.bannerUrl, event.attendees, event.joined);
    }

    private CommunityPostResponse toPostResponse(CommunityPostState post, String currentUserId) {
        List<CommunityCommentResponse> commentsPreview = post.comments().stream()
                .sorted(Comparator.comparing(CommunityCommentState::createdAt).reversed())
                .limit(3)
                .sorted(Comparator.comparing(CommunityCommentState::createdAt))
                .map(comment -> toCommentResponse(comment, currentUserId))
                .toList();
        return new CommunityPostResponse(
                post.id(),
                post.topicId(),
                post.postType(),
                accountService.getPublicProfile(post.authorUserId()),
                post.text(),
                post.mediaUrl(),
                post.mediaUrls(),
                post.thumbnailUrl(),
                post.tags(),
                post.mentionedUserIds(),
                post.likedByUserIds().size(),
                post.comments().size(),
                commentsPreview,
                post.shareCount(),
                post.likedByUserIds().contains(currentUserId),
                post.sharedByUserIds().contains(currentUserId),
                timeLabel(post.createdAt())
        );
    }

    private CommunityCommentResponse toCommentResponse(CommunityCommentState comment, String currentUserId) {
        return new CommunityCommentResponse(
                comment.id(),
                comment.postId(),
                accountService.getPublicProfile(comment.authorUserId()),
                comment.text(),
                timeLabel(comment.createdAt()),
                Objects.equals(comment.authorUserId(), currentUserId),
                comment.mentionedUserIds()
        );
    }

    private List<CommunityPostState> rankedPosts(String userId, String tab, boolean refresh) {
        List<String> viewerInterests = safeInterests(userId);
        List<CommunityPostState> ranked = posts.values().stream()
                .filter(post -> matchesTab(userId, tab, post, viewerInterests))
                .sorted(Comparator
                        .comparingInt((CommunityPostState post) -> postScore(post, viewerInterests, tab)).reversed()
                        .thenComparing(CommunityPostState::createdAt, Comparator.reverseOrder()))
                .toList();

        if (refresh) {
            return new ArrayList<>(ranked);
        }
        return ranked;
    }

    private boolean matchesTab(String userId, String tab, CommunityPostState post, List<String> viewerInterests) {
        return switch (tab) {
            case "friends" -> accountService.isFriend(userId, post.authorUserId()) || Objects.equals(post.authorUserId(), userId);
            case "following" -> {
                CommunityTopicState topic = topics.get(post.topicId());
                yield (topic != null && topic.joined()) || Objects.equals(post.authorUserId(), userId);
            }
            default -> true;
        };
    }

    private int postScore(CommunityPostState post, List<String> viewerInterests, String tab) {
        int score = post.likedByUserIds().size() * 4 + post.comments().size() * 3 + post.shareCount() * 2;
        score += Math.max(0, 240 - (int) (minutesSince(post.createdAt()) * 4));
        score += tagHotnessScore(post.tags());
        if (intersects(viewerInterests, post.tags())) {
            score += 18;
        }
        if ("friends".equals(tab)) {
            score += 16;
        } else if ("following".equals(tab)) {
            score += 8;
        }
        if (Objects.equals(post.authorUserId(), "u-current")) {
            score += 6;
        }
        return score;
    }

    private long minutesSince(Instant instant) {
        return Math.max(0, (Instant.now().toEpochMilli() - instant.toEpochMilli()) / 60_000L);
    }

    private int tagHotnessScore(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String tag : tags) {
            total += tagHotness.getOrDefault(normalizeTag(tag), 0);
        }
        return Math.min(30, total / 3);
    }

    private List<String> trendingTags(int limit) {
        return tagHotness.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .limit(Math.max(1, limit))
                .toList();
    }

    private int countPostsForTag(String tag) {
        String normalized = normalizeTag(tag);
        return (int) posts.values().stream()
                .filter(post -> post.tags().stream().map(this::normalizeTag).anyMatch(normalized::equals))
                .count();
    }

    private void bumpTagHotness(List<String> tags) {
        for (String tag : tags) {
            String normalized = normalizeTag(tag);
            tagHotness.put(normalized, tagHotness.getOrDefault(normalized, 0) + 1);
        }
    }

    private List<String> normalizeTags(List<String> requestedTags, String text) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (requestedTags != null) {
            requestedTags.stream()
                    .map(this::normalizeTag)
                    .filter(tag -> !tag.isBlank())
                    .forEach(tags::add);
        }
        extractTags(text).forEach(tags::add);
        return new ArrayList<>(tags);
    }

    private List<String> extractTags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = TAG_PATTERN.matcher(text);
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        while (matcher.find()) {
            String tag = normalizeTag(matcher.group(1));
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return new ArrayList<>(tags);
    }

    private List<String> resolveMentionTargets(List<String> mentionedUserIds, String text) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (mentionedUserIds != null) {
            mentionedUserIds.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(resolved::add);
        }
        if (text != null && !text.isBlank()) {
            Matcher matcher = MENTION_PATTERN.matcher(text);
            while (matcher.find()) {
                String handle = matcher.group(1).trim();
                accountService.searchUsers(handle, null, null, 0, 5).items().stream()
                        .findFirst()
                        .map(PublicUserCard::userId)
                        .ifPresent(resolved::add);
            }
        }
        return new ArrayList<>(resolved);
    }

    private void notifyMentions(String actorUserId, CommunityPostState post, List<String> recipientUserIds, String reason) {
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }
        PublicUserCard actor = accountService.getPublicProfile(actorUserId);
        for (String recipientUserId : recipientUserIds) {
            if (recipientUserId == null || recipientUserId.isBlank() || Objects.equals(recipientUserId, actorUserId)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("notificationId", "community-" + post.id() + "-" + recipientUserId);
            payload.put("kind", "COMMUNITY");
            payload.put("read", false);
            payload.put("actionTarget", "community/post/" + post.id());
            payload.put("postId", post.id());
            payload.put("topicId", post.topicId());
            payload.put("threadId", "community/" + post.id());
            payload.put("actorName", actor.displayName());
            payload.put("reason", reason);
            payload.put("timeLabel", timeLabel(Instant.now()));
            liveDeliveryService.publishToUser(
                    recipientUserId,
                    new RealtimeEvent(
                            UUID.randomUUID().toString(),
                            RealtimeEventType.NOTIFICATION_CREATED,
                            "community/" + post.id(),
                            actorUserId,
                            recipientUserId,
                            null,
                            null,
                            null,
                            actor.displayName() + " mentioned you",
                            actor.displayName() + " mentioned you in a post",
                            payload,
                            Instant.now()
                    )
            );
        }
    }

    private void notifyAuthor(String recipientUserId, String actorUserId, CommunityPostState post, String reason, String kind, String actionTarget) {
        if (recipientUserId == null || recipientUserId.isBlank() || Objects.equals(recipientUserId, actorUserId)) {
            return;
        }
        PublicUserCard actor = accountService.getPublicProfile(actorUserId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("notificationId", "community-" + post.id() + "-" + recipientUserId + "-" + kind.toLowerCase(Locale.ROOT));
        payload.put("kind", kind);
        payload.put("read", false);
        payload.put("actionTarget", actionTarget);
        payload.put("postId", post.id());
        payload.put("topicId", post.topicId());
        payload.put("threadId", "community/" + post.id());
        payload.put("actorName", actor.displayName());
        payload.put("reason", reason);
        payload.put("timeLabel", timeLabel(Instant.now()));
        liveDeliveryService.publishToUser(
                recipientUserId,
                new RealtimeEvent(
                        UUID.randomUUID().toString(),
                        RealtimeEventType.NOTIFICATION_CREATED,
                        "community/" + post.id(),
                        actorUserId,
                        recipientUserId,
                        null,
                        null,
                        null,
                        actor.displayName() + " " + reason,
                        actor.displayName() + " " + reason,
                        payload,
                        Instant.now()
                )
        );
    }

    private String normalizePostType(String value) {
        if (value == null || value.isBlank()) {
            return CommunityPostType.TEXT.name();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (CommunityPostType type : CommunityPostType.values()) {
            if (type.name().equals(normalized)) {
                return normalized;
            }
        }
        return CommunityPostType.TEXT.name();
    }

    private List<String> normalizeMediaUrls(List<String> requestedMediaUrls, String fallbackMediaUrl) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (requestedMediaUrls != null) {
            requestedMediaUrls.stream()
                    .map(this::blankToNull)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(urls::add);
        }
        String fallback = blankToNull(fallbackMediaUrl);
        if (fallback != null) {
            urls.add(fallback);
        }
        return new ArrayList<>(urls);
    }

    private String normalizeTag(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String normalizeTab(String tab) {
        String normalized = tab == null ? "" : tab.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "friends", "following", "for you", "for_you" -> normalized.replace(' ', '_');
            default -> "for_you";
        };
    }

    private boolean intersects(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        Set<String> normalized = left.stream().map(this::normalizeTag).collect(Collectors.toSet());
        return right.stream().map(this::normalizeTag).anyMatch(normalized::contains);
    }

    private List<String> safeInterests(String userId) {
        try {
            PublicUserCard card = accountService.getPublicProfile(userId);
            return card.interests() == null ? List.of() : card.interests();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private int indexOfPost(List<CommunityPostState> ranked, String postId) {
        for (int i = 0; i < ranked.size(); i++) {
            if (Objects.equals(ranked.get(i).id(), postId)) {
                return i;
            }
        }
        return -1;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String timeLabel(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneId.systemDefault())
                .toLocalTime()
                .withSecond(0)
                .withNano(0)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String increaseCount(String value) {
        return adjustCount(value, 1);
    }

    private String decreaseCount(String value) {
        return adjustCount(value, -1);
    }

    private String adjustCount(String value, int delta) {
        String trimmed = value == null ? "0" : value.trim();
        String suffix = "";
        if (trimmed.endsWith("members")) {
            suffix = " members";
            trimmed = trimmed.replace(" members", "");
        } else if (trimmed.endsWith("going")) {
            suffix = " going";
            trimmed = trimmed.replace(" going", "");
        }

        trimmed = trimmed.trim();
        double numeric;
        if (trimmed.endsWith("K")) {
            numeric = Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 1000d;
        } else if (trimmed.endsWith("M")) {
            numeric = Double.parseDouble(trimmed.substring(0, trimmed.length() - 1)) * 1_000_000d;
        } else {
            numeric = Double.parseDouble(trimmed);
        }

        numeric = Math.max(0, numeric + delta);
        if (numeric >= 1_000_000d) {
            return formatCompact(numeric / 1_000_000d) + "M" + suffix;
        }
        if (numeric >= 1_000d) {
            return formatCompact(numeric / 1_000d) + "K" + suffix;
        }
        return ((int) numeric) + suffix;
    }

    private String formatCompact(double value) {
        if (Math.abs(value - Math.round(value)) < 0.05d) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record CommunityState(
            Map<String, CommunityTopicState> topics,
            Map<String, CommunityPostState> posts,
            Map<String, EventState> events,
            Map<String, Integer> tagHotness,
            int postSequence,
            int commentSequence,
            int refreshSequence
    ) {
    }

    private record CommunityTopicState(
            String id,
            String title,
            String description,
            String bannerUrl,
            String members,
            String moderator,
            int eventCount,
            boolean joined
    ) {
    }

    private record CommunityPostState(
            String id,
            String topicId,
            String authorUserId,
            String postType,
            String text,
            String mediaUrl,
            List<String> mediaUrls,
            String thumbnailUrl,
            List<String> tags,
            List<String> mentionedUserIds,
            List<String> likedByUserIds,
            List<String> sharedByUserIds,
            List<CommunityCommentState> comments,
            int shareCount,
            Instant createdAt
    ) {
        CommunityPostState withLikes(List<String> likedByUserIds) {
            return new CommunityPostState(
                    id,
                    topicId,
                    authorUserId,
                    postType,
                    text,
                    mediaUrl,
                    mediaUrls,
                    thumbnailUrl,
                    tags,
                    mentionedUserIds,
                    likedByUserIds,
                    sharedByUserIds,
                    comments,
                    shareCount,
                    createdAt
            );
        }

        CommunityPostState withComments(List<CommunityCommentState> comments) {
            return new CommunityPostState(
                    id,
                    topicId,
                    authorUserId,
                    postType,
                    text,
                    mediaUrl,
                    mediaUrls,
                    thumbnailUrl,
                    tags,
                    mentionedUserIds,
                    likedByUserIds,
                    sharedByUserIds,
                    comments,
                    shareCount,
                    createdAt
            );
        }

        CommunityPostState withShared(List<String> sharedByUserIds, int shareCount) {
            return new CommunityPostState(
                    id,
                    topicId,
                    authorUserId,
                    postType,
                    text,
                    mediaUrl,
                    mediaUrls,
                    thumbnailUrl,
                    tags,
                    mentionedUserIds,
                    likedByUserIds,
                    sharedByUserIds,
                    comments,
                    shareCount,
                    createdAt
            );
        }
    }

    private record CommunityCommentState(
            String id,
            String postId,
            String authorUserId,
            String text,
            List<String> mentionedUserIds,
            Instant createdAt
    ) {
    }

    private record EventState(
            String id,
            String title,
            String kind,
            String dateLabel,
            String location,
            String price,
            String bannerUrl,
            String attendees,
            boolean joined
    ) {
    }
}
