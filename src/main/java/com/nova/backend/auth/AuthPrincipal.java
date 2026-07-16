package com.nova.backend.auth;

import java.security.Principal;
import java.util.List;

public record AuthPrincipal(
        String userId,
        String sessionId,
        String displayName,
        List<String> roles
) implements Principal {
    @Override
    public String getName() {
        return userId;
    }
}
