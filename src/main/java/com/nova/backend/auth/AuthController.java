package com.nova.backend.auth;

import com.nova.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/social/login")
    public ApiResponse<AuthSessionResponse> login(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.ok(authService.login(request), "Login successful");
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthSessionResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request), "Session refreshed");
    }

    @PostMapping("/logout")
    public ApiResponse<?> logout(Authentication authentication) {
        authService.logout(accessToken(authentication));
        return ApiResponse.ok(null, "Logged out");
    }

    @GetMapping("/session")
    public ApiResponse<SessionLookupResponse> session(Authentication authentication) {
        return ApiResponse.ok(authService.currentSession(accessToken(authentication)));
    }

    private String accessToken(Authentication authentication) {
        if (authentication == null || authentication.getCredentials() == null) {
            return null;
        }
        return String.valueOf(authentication.getCredentials());
    }
}
