package com.nova.backend.account;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import com.nova.backend.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accountService.getMe(principal.userId()));
    }

    @GetMapping("/me/bootstrap")
    public ApiResponse<BootstrapResponse> bootstrap(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accountService.bootstrap(principal.userId()));
    }

    @PatchMapping("/me/profile")
    public ApiResponse<MeResponse> updateProfile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ApiResponse.ok(accountService.updateProfile(principal.userId(), request));
    }

    @PatchMapping("/me/settings")
    public ApiResponse<MeResponse> updateSettings(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody UpdateSettingsRequest request
    ) {
        return ApiResponse.ok(accountService.updateSettings(principal.userId(), request));
    }

    @GetMapping("/me/badges")
    public ApiResponse<?> badges(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accountService.badges(principal.userId()));
    }

    @GetMapping("/me/wallet")
    public ApiResponse<?> wallet(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accountService.wallet(principal.userId()));
    }

    @GetMapping("/me/premium")
    public ApiResponse<?> premiumPlans() {
        return ApiResponse.ok(accountService.plans());
    }

    @GetMapping("/me/entitlements")
    public ApiResponse<?> entitlements(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(accountService.entitlements(principal.userId()));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<PublicUserCard> publicProfile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String userId
    ) {
        return ApiResponse.ok(accountService.getPublicProfile(principal.userId(), userId));
    }

    @PostMapping("/users/{userId}/follow")
    public ApiResponse<PublicUserCard> follow(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String userId,
            @RequestBody FollowRequest request
    ) {
        return ApiResponse.ok(accountService.toggleFollow(principal.userId(), userId, request.followed()));
    }

    @GetMapping("/users/{userId}/relations")
    public ApiResponse<PageResponse<PublicUserCard>> relations(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String userId,
            @RequestParam(defaultValue = "followers") String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return ApiResponse.ok(accountService.profileRelations(principal.userId(), userId, type, page, size));
    }

    @GetMapping("/users/search")
    public ApiResponse<PageResponse<PublicUserCard>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String interest,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(accountService.searchUsers(q, gender, interest, page, size));
    }
}
