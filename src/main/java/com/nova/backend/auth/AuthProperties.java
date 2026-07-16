package com.nova.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String googleClientId,
        boolean allowDevTokens
) {
    public AuthProperties {
        googleClientId = googleClientId == null ? "" : googleClientId.trim();
    }
}
