package com.nova.backend.community;

import com.nova.backend.account.PublicUserCard;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

enum CommunityPostType {
    TEXT,
    IMAGE,
    VIDEO,
    MIXED,
    VOICE,
    LINK,
    POLL
}

record CommunityTopicResponse(
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

record CommunityPostResponse(
        String id,
        String topicId,
        String postType,
        PublicUserCard author,
        String text,
        String mediaUrl,
        List<String> mediaUrls,
        String thumbnailUrl,
        List<String> tags,
        List<String> mentionedUserIds,
        int likes,
        int comments,
        List<CommunityCommentResponse> commentsPreview,
        int shares,
        boolean likedByMe,
        boolean sharedByMe,
        String timeLabel
) {
}

record CommunityCommentResponse(
        String id,
        String postId,
        PublicUserCard author,
        String text,
        String timeLabel,
        boolean mine,
        List<String> mentionedUserIds
) {
}

record EventResponse(
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

record JoinRequest(
        boolean joined
) {
}

record CreateCommunityPostRequest(
        @NotBlank String topicId,
        @NotBlank String text,
        String mediaUrl,
        List<String> mediaUrls,
        String thumbnailUrl,
        String postType,
        List<String> tags,
        List<String> mentionedUserIds
) {
}

record CreateCommunityCommentRequest(
        @NotBlank String text
) {
}

record ToggleCommunityPostLikeRequest(
        boolean liked
) {
}

record ShareCommunityPostRequest(
        String target,
        String recipientUserId,
        boolean copyLink
) {
}

record ShareCommunityPostResponse(
        String shareUrl,
        CommunityPostResponse post
) {
}

record CommunityTagSuggestionResponse(
        String tag,
        int hotness,
        int postCount,
        boolean exactMatch,
        boolean canCreate
) {
}

record CommunityFeedResponse(
        List<CommunityTopicResponse> topics,
        List<CommunityPostResponse> posts,
        List<EventResponse> events,
        List<String> trendingTags,
        List<String> postTypes,
        String refreshToken,
        String nextCursor,
        boolean hasMore
) {
}
