package com.nova.backend.content;

import com.nova.backend.account.PublicUserCard;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

record StoryItem(
        String id,
        PublicUserCard author,
        String mediaUrl,
        String caption,
        String music,
        boolean viewed,
        int expiresInHours
) {
}

record FeedPostResponse(
        String id,
        PublicUserCard author,
        String caption,
        List<String> mediaUrls,
        int likes,
        int comments,
        int saves,
        List<String> tags,
        String timeLabel
) {
}

record DiscoveryCandidate(
        String candidateId,
        PublicUserCard user,
        String bio,
        int compatibility,
        List<String> commonInterests,
        String iceBreaker,
        int mutualFriends,
        String musicTaste,
        String height,
        String job,
        String relationshipGoal,
        List<String> gallery,
        boolean voiceIntro,
        boolean videoIntro
) {
}

record HomeResponse(
        List<StoryItem> stories,
        List<FeedPostResponse> feedPosts,
        List<DiscoveryCandidate> suggestions,
        List<String> stats,
        List<String> tabs,
        int unreadMessages,
        int unreadNotifications
) {
}

record DiscoverResponse(
        List<DiscoveryCandidate> items,
        List<String> filters
) {
}

record PokeRequest(
        @NotBlank String candidateId
) {
}

record PokeResponse(
        boolean delivered,
        String message,
        String nextCandidateId
) {
}

record SwipeRequest(
        @NotBlank String candidateId,
        @NotBlank String direction,
        String reason
) {
}

record SwipeResponse(
        boolean matched,
        String message,
        String nextCandidateId
) {
}

record UploadMediaRequest(
        @NotBlank String title,
        @NotBlank String url,
        @NotBlank String mimeType,
        @NotBlank String kind,
        String previewUrl
) {
}

record MediaAssetResponse(
        String id,
        String ownerId,
        String title,
        String url,
        String mimeType,
        String kind,
        String previewUrl,
        String createdAt,
        boolean favorite
) {
}

record MediaLibraryResponse(
        List<MediaAssetResponse> items
) {
}
