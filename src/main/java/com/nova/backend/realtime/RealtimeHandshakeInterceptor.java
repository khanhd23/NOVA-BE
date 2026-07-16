package com.nova.backend.realtime;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.auth.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@Component
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

    private final AuthService authService;

    public RealtimeHandshakeInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = extractToken(request);
        if (token == null) {
            return false;
        }
        AuthPrincipal principal = authService.resolvePrincipal(token);
        if (principal == null) {
            return false;
        }
        attributes.put("userId", principal.userId());
        attributes.put("sessionId", principal.sessionId());
        attributes.put("displayName", principal.displayName());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // No-op
    }

    private String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest raw = servletRequest.getServletRequest();
            String header = raw.getHeader(HttpHeaders.AUTHORIZATION);
            String token = extractBearerToken(header);
            if (token != null) {
                return token;
            }
            token = raw.getParameter("token");
            if (token != null && !token.isBlank()) {
                return token.trim();
            }
        }
        String token = request.getHeaders().getFirst("X-Auth-Token");
        return token == null || token.isBlank() ? null : token.trim();
    }

    private String extractBearerToken(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring("Bearer ".length()).trim();
        return token.isBlank() ? null : token;
    }
}
