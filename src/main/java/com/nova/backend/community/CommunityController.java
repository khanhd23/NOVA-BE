package com.nova.backend.community;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import com.nova.backend.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CommunityController {

    private final CommunityService communityService;

    public CommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    @GetMapping("/communities")
    public ApiResponse<CommunityFeedResponse> communities(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "for_you") String tab,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(communityService.feed(principal.userId(), tab, cursor, refresh, size));
    }

    @GetMapping("/communities/{topicId}")
    public ApiResponse<CommunityTopicResponse> community(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String topicId) {
        return ApiResponse.ok(communityService.topic(principal.userId(), topicId));
    }

    @PostMapping("/communities/{topicId}/join")
    public ApiResponse<CommunityTopicResponse> joinCommunity(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String topicId,
            @Valid @RequestBody JoinRequest request
    ) {
        return ApiResponse.ok(communityService.joinTopic(principal.userId(), topicId, request));
    }

    @GetMapping("/community-posts")
    public ApiResponse<?> posts(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "for_you") String tab,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(communityService.communityPosts(principal.userId(), tab, cursor, refresh, size));
    }

    @GetMapping("/community-posts/search")
    public ApiResponse<PageResponse<CommunityPostResponse>> searchPosts(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(communityService.searchPosts(principal.userId(), q, page, size));
    }

    @GetMapping("/users/{userId}/posts")
    public ApiResponse<?> profilePosts(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String userId,
            @RequestParam(defaultValue = "30") int size
    ) {
        return ApiResponse.ok(communityService.profilePosts(principal.userId(), userId, size));
    }

    @PostMapping("/community-posts")
    public ApiResponse<CommunityPostResponse> createPost(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateCommunityPostRequest request
    ) {
        return ApiResponse.ok(communityService.createPost(principal.userId(), request));
    }

    @PostMapping("/community-posts/{postId}/like")
    public ApiResponse<CommunityPostResponse> likePost(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @RequestBody(required = false) ToggleCommunityPostLikeRequest request
    ) {
        return ApiResponse.ok(communityService.toggleLike(principal.userId(), postId, request == null || request.liked()));
    }

    @PostMapping("/community-posts/{postId}/comments")
    public ApiResponse<CommunityPostResponse> commentPost(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @Valid @RequestBody CreateCommunityCommentRequest request
    ) {
        return ApiResponse.ok(communityService.addComment(principal.userId(), postId, request));
    }

    @GetMapping("/community-posts/{postId}/comments")
    public ApiResponse<PageResponse<CommunityCommentResponse>> comments(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(communityService.comments(principal.userId(), postId, page, size));
    }

    @PostMapping("/community-posts/{postId}/share")
    public ApiResponse<ShareCommunityPostResponse> sharePost(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String postId,
            @RequestBody(required = false) ShareCommunityPostRequest request
    ) {
        return ApiResponse.ok(communityService.sharePost(principal.userId(), postId, request));
    }

    @GetMapping("/community-tags")
    public ApiResponse<?> tags(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "8") int limit
    ) {
        return ApiResponse.ok(communityService.tagSuggestions(principal.userId(), q, limit));
    }

    @GetMapping("/events")
    public ApiResponse<?> events(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(communityService.eventList(principal.userId()));
    }

    @PostMapping("/events/{eventId}/join")
    public ApiResponse<EventResponse> joinEvent(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String eventId,
            @Valid @RequestBody JoinRequest request
    ) {
        return ApiResponse.ok(communityService.joinEvent(principal.userId(), eventId, request));
    }
}
