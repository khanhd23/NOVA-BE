package com.nova.backend.social;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SocialController {

    private final SocialService socialService;

    public SocialController(SocialService socialService) {
        this.socialService = socialService;
    }

    @GetMapping("/threads")
    public ApiResponse<?> threads(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(socialService.threads(principal.userId()));
    }

    @GetMapping("/threads/{threadId}")
    public ApiResponse<ThreadDetailResponse> thread(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String threadId
    ) {
        return ApiResponse.ok(socialService.thread(principal.userId(), threadId));
    }

    @PostMapping("/threads/{threadId}/messages")
    public ApiResponse<ChatMessageResponse> sendMessage(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String threadId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return ApiResponse.ok(socialService.sendMessage(principal.userId(), threadId, request));
    }

    @DeleteMapping("/threads/{threadId}")
    public ApiResponse<?> deleteThreadForMe(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String threadId
    ) {
        socialService.deleteThreadForMe(principal.userId(), threadId);
        return ApiResponse.ok(null, "Thread deleted for you");
    }

    @DeleteMapping("/threads/{threadId}/messages/{messageId}")
    public ApiResponse<?> deleteMessageForMe(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String threadId,
            @PathVariable String messageId
    ) {
        socialService.deleteMessageForMe(principal.userId(), threadId, messageId);
        return ApiResponse.ok(null, "Message deleted for you");
    }

    @PostMapping("/threads/{threadId}/messages/{messageId}/recall")
    public ApiResponse<ChatMessageResponse> recallMessage(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String threadId,
            @PathVariable String messageId,
            @RequestBody(required = false) RecallMessageRequest request
    ) {
        return ApiResponse.ok(socialService.recallMessage(principal.userId(), threadId, messageId, request));
    }

    @PostMapping("/threads/{threadId}/read")
    public ApiResponse<ThreadDetailResponse> markRead(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String threadId
    ) {
        return ApiResponse.ok(socialService.markThreadRead(principal.userId(), threadId));
    }

    @PostMapping("/threads/{threadId}/typing")
    public ApiResponse<ChatThreadResponse> typing(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String threadId,
            @RequestBody TypingStateRequest request
    ) {
        return ApiResponse.ok(socialService.setTyping(principal.userId(), threadId, request.typing()));
    }

    @PostMapping("/threads/{threadId}/calls")
    public ApiResponse<CallSessionResponse> startCall(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String threadId,
            @Valid @RequestBody CreateCallRequest request
    ) {
        return ApiResponse.ok(socialService.startCall(principal.userId(), threadId, request));
    }

    @GetMapping("/calls")
    public ApiResponse<CallListResponse> calls(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(socialService.callHistory(principal.userId()));
    }

    @GetMapping("/calls/{callId}")
    public ApiResponse<CallSessionResponse> call(@PathVariable String callId) {
        return ApiResponse.ok(socialService.call(callId));
    }

    @PostMapping("/calls/{callId}/answer")
    public ApiResponse<CallSessionResponse> answer(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String callId
    ) {
        return ApiResponse.ok(socialService.answerCall(principal.userId(), callId));
    }

    @PostMapping("/calls/{callId}/end")
    public ApiResponse<CallSessionResponse> end(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String callId,
            @Valid @RequestBody EndCallRequest request
    ) {
        return ApiResponse.ok(socialService.endCall(principal.userId(), callId, request));
    }

    @PostMapping("/calls/{callId}/minimize")
    public ApiResponse<CallSessionResponse> minimize(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String callId,
            @RequestParam(defaultValue = "true") boolean minimized
    ) {
        return ApiResponse.ok(socialService.minimizeCall(principal.userId(), callId, minimized));
    }

    @GetMapping("/notifications")
    public ApiResponse<?> notifications(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(socialService.notifications(principal.userId()));
    }

    @PostMapping("/notifications/read")
    public ApiResponse<?> readNotification(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ReadNotificationRequest request
    ) {
        return ApiResponse.ok(socialService.markRead(principal.userId(), request.notificationId()));
    }
}
