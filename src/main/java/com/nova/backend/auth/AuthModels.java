package com.nova.backend.auth;

import com.nova.backend.account.MeResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

record SocialLoginRequest(
        @NotNull SocialProvider provider,
        @NotBlank String providerToken,
        String deviceId,
        String appVersion
) {
}

record RefreshRequest(
        @NotBlank String refreshToken
) {
}

record TokenPair(
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt
) {
}

record SessionView(
        String sessionId,
        String provider,
        Instant issuedAt,
        Instant expiresAt,
        Instant refreshExpiresAt,
        boolean active
) {
}

record AuthSessionResponse(
        TokenPair tokens,
        SessionView session,
        MeResponse me,
        boolean onboardingRequired,
        boolean profileRequired,
        List<String> roles
) {
}

record SessionLookupResponse(
        SessionView session,
        MeResponse me
) {
}
