package com.nova.backend.auth;

public record SocialIdentity(
        SocialProvider provider,
        String providerUserId,
        String email,
        String displayName,
        String avatarUrl
) {
}
