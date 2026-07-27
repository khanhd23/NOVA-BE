package com.nova.backend.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.backend.account.AccountService;
import com.nova.backend.account.MeResponse;
import com.nova.backend.common.exception.UnauthorizedException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final Duration ACCESS_TTL = Duration.ofHours(6);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final Set<String> LEGACY_DEMO_USER_IDS = Set.of(
            "u-current",
            "u-seraphina",
            "u-elena",
            "u-marcus",
            "u-chloe",
            "u-alex",
            "u-mina"
    );

    private final AccountService accountService;
    private final AuthProperties authProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final Map<String, SocialIdentity> devIdentities = new LinkedHashMap<>();
    private final Map<String, SessionRecord> sessionsByRefreshToken = new ConcurrentHashMap<>();
    private final Map<String, String> accessTokenToRefreshToken = new ConcurrentHashMap<>();

    public AuthService(AccountService accountService, AuthProperties authProperties, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.accountService = accountService;
        this.authProperties = authProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.googleIdTokenVerifier = createGoogleVerifier(authProperties.googleClientId());
        seedDevIdentities();
        loadPersistedSessions();
    }

    public AuthSessionResponse login(SocialLoginRequest request) {
        SocialIdentity identity = verifyIdentity(request.provider(), request.providerToken());
        String userId = accountService.upsertSocialUser(identity);
        SessionRecord session = createSession(userId, identity.provider());
        MeResponse me = accountService.getMe(userId);
        return new AuthSessionResponse(
                session.tokens(),
                session.sessionView(),
                me,
                !accountService.onboardingComplete(userId),
                !accountService.profileComplete(userId),
                session.roles()
        );
    }

    public AuthSessionResponse refresh(RefreshRequest request) {
        SessionRecord existing = sessionsByRefreshToken.get(request.refreshToken());
        if (existing == null || !existing.active()) {
            throw new UnauthorizedException("Refresh token is invalid or expired");
        }
        SessionRecord rotated = existing.rotate();
        deleteSession(existing);
        sessionsByRefreshToken.remove(existing.refreshToken());
        accessTokenToRefreshToken.remove(existing.accessToken());
        persistSession(rotated);
        sessionsByRefreshToken.put(rotated.refreshToken(), rotated);
        accessTokenToRefreshToken.put(rotated.accessToken(), rotated.refreshToken());

        String userId = rotated.userId();
        MeResponse me = accountService.getMe(userId);
        return new AuthSessionResponse(
                rotated.tokens(),
                rotated.sessionView(),
                me,
                !accountService.onboardingComplete(userId),
                !accountService.profileComplete(userId),
                rotated.roles()
        );
    }

    public void logout(String accessToken) {
        String refreshToken = accessTokenToRefreshToken.remove(accessToken);
        if (refreshToken != null) {
            SessionRecord session = sessionsByRefreshToken.remove(refreshToken);
            if (session != null) {
                deleteSession(session);
            }
        }
    }

    public SessionLookupResponse currentSession(String accessToken) {
        SessionRecord session = findByAccessToken(accessToken);
        if (session == null || !session.active()) {
            throw new UnauthorizedException("Session is invalid or expired");
        }
        return new SessionLookupResponse(session.sessionView(), accountService.getMe(session.userId()));
    }

    public AuthPrincipal resolvePrincipal(String accessToken) {
        SessionRecord session = findByAccessToken(accessToken);
        if (session == null || !session.active()) {
            return null;
        }
        MeResponse me = accountService.getMe(session.userId());
        return new AuthPrincipal(session.userId(), session.sessionId(), me.displayName(), session.roles());
    }

    private SessionRecord createSession(String userId, SocialProvider provider) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ACCESS_TTL);
        Instant refreshExpiresAt = issuedAt.plus(REFRESH_TTL);
        SessionRecord record = new SessionRecord(
                UUID.randomUUID().toString(),
                userId,
                provider.name(),
                issuedAt,
                expiresAt,
                refreshExpiresAt,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                List.of("USER"),
                true
        );
        persistSession(record);
        sessionsByRefreshToken.put(record.refreshToken(), record);
        accessTokenToRefreshToken.put(record.accessToken(), record.refreshToken());
        return record;
    }

    private SessionRecord findByAccessToken(String accessToken) {
        String refreshToken = accessTokenToRefreshToken.get(accessToken);
        if (refreshToken == null) {
            return null;
        }
        return sessionsByRefreshToken.get(refreshToken);
    }

    private SocialIdentity verifyDevIdentity(SocialProvider provider, String providerToken) {
        String key = provider.name() + ":" + providerToken.trim();
        SocialIdentity identity = devIdentities.get(key);
        if (identity == null) {
            throw new UnauthorizedException("Unsupported login token");
        }
        return identity;
    }

    private SocialIdentity verifyIdentity(SocialProvider provider, String providerToken) {
        String normalizedToken = providerToken == null ? "" : providerToken.trim();
        if (normalizedToken.isBlank()) {
            throw new UnauthorizedException("Unsupported login token");
        }

        if (normalizedToken.startsWith("dev:")) {
            return verifyDevIdentity(provider, normalizedToken);
        }

        return switch (provider) {
            case GOOGLE -> verifyGoogleIdentity(normalizedToken);
            case FACEBOOK -> verifyFacebookIdentity(normalizedToken);
        };
    }

    private SocialIdentity verifyGoogleIdentity(String providerToken) {
        if (googleIdTokenVerifier == null) {
            throw new UnauthorizedException("Google login is not configured");
        }

        final GoogleIdToken token;
        try {
            token = googleIdTokenVerifier.verify(providerToken);
        } catch (GeneralSecurityException | java.io.IOException ex) {
            throw new UnauthorizedException("Invalid Google login token");
        }
        if (token == null) {
            throw new UnauthorizedException("Invalid Google login token");
        }

        GoogleIdToken.Payload payload = token.getPayload();
        String providerUserId = payload.getSubject();
        String email = safeClaim(payload.getEmail());
        String displayName = safeClaim(stringClaim(payload, "name"));
        if (displayName.isBlank()) {
            displayName = email.isBlank() ? "Nova User" : email.split("@")[0];
        }
        String avatarUrl = safeClaim(stringClaim(payload, "picture"));
        return new SocialIdentity(SocialProvider.GOOGLE, providerUserId, email, displayName, avatarUrl);
    }

    private SocialIdentity verifyFacebookIdentity(String providerToken) {
        throw new UnauthorizedException("Facebook login requires a dev token in this build");
    }

    private void loadPersistedSessions() {
        try {
            List<SessionRecord> persisted = jdbcTemplate.query("SELECT * FROM auth_sessions", (rs, rowNum) -> rowToSession(rs));
            Instant now = Instant.now();
            for (SessionRecord record : persisted) {
                if (LEGACY_DEMO_USER_IDS.contains(record.userId())) {
                    continue;
                }
                if (record.active() && record.expiresAt().isAfter(now) && record.refreshExpiresAt().isAfter(now)) {
                    sessionsByRefreshToken.put(record.refreshToken(), record);
                    accessTokenToRefreshToken.put(record.accessToken(), record.refreshToken());
                }
            }
        } catch (Exception ex) {
            sessionsByRefreshToken.clear();
            accessTokenToRefreshToken.clear();
        }
    }

    private void persistSession(SessionRecord session) {
        String updateSql = """
                UPDATE auth_sessions SET
                    user_id = ?,
                    provider = ?,
                    issued_at = ?,
                    expires_at = ?,
                    refresh_expires_at = ?,
                    access_token = ?,
                    refresh_token = ?,
                    roles_json = ?,
                    active = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE session_id = ?
                """;
        int updated = jdbcTemplate.update(
                updateSql,
                session.userId(),
                session.provider(),
                Timestamp.from(session.issuedAt()),
                Timestamp.from(session.expiresAt()),
                Timestamp.from(session.refreshExpiresAt()),
                session.accessToken(),
                session.refreshToken(),
                jsonRoles(session.roles()),
                session.active(),
                session.sessionId()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            INSERT INTO auth_sessions (
                                session_id, user_id, provider, issued_at, expires_at, refresh_expires_at,
                                access_token, refresh_token, roles_json, active, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """,
                    session.sessionId(),
                    session.userId(),
                    session.provider(),
                    Timestamp.from(session.issuedAt()),
                    Timestamp.from(session.expiresAt()),
                    Timestamp.from(session.refreshExpiresAt()),
                    session.accessToken(),
                    session.refreshToken(),
                    jsonRoles(session.roles()),
                    session.active()
            );
        }
    }

    private void deleteSession(SessionRecord session) {
        jdbcTemplate.update("DELETE FROM auth_sessions WHERE session_id = ?", session.sessionId());
    }

    private SessionRecord rowToSession(ResultSet rs) throws SQLException {
        return new SessionRecord(
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("provider"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("refresh_expires_at").toInstant(),
                rs.getString("access_token"),
                rs.getString("refresh_token"),
                readRoles(rs.getString("roles_json")),
                rs.getBoolean("active")
        );
    }

    private String jsonRoles(List<String> roles) {
        try {
            return objectMapper.writeValueAsString(roles);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize session roles", ex);
        }
    }

    private List<String> readRoles(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read session roles", ex);
        }
    }

    private GoogleIdTokenVerifier createGoogleVerifier(String googleClientId) {
        if (googleClientId == null || googleClientId.isBlank()) {
            return null;
        }
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), JacksonFactory.getDefaultInstance())
                .setAudience(List.of(googleClientId.trim()))
                .build();
    }

    private String stringClaim(GoogleIdToken.Payload payload, String name) {
        Object value = payload.get(name);
        return value == null ? "" : String.valueOf(value);
    }

    private String safeClaim(String value) {
        return value == null ? "" : value.trim();
    }

    private void seedDevIdentities() {
        devIdentities.put(
                "GOOGLE:dev:current",
                new SocialIdentity(SocialProvider.GOOGLE, "dev:current", "you@nova.app", "Nova User", "")
        );
        devIdentities.put(
                "FACEBOOK:dev:current",
                new SocialIdentity(SocialProvider.FACEBOOK, "dev:current", "you@nova.app", "Nova User", "")
        );
    }

    private record SessionRecord(
            String sessionId,
            String userId,
            String provider,
            Instant issuedAt,
            Instant expiresAt,
            Instant refreshExpiresAt,
            String accessToken,
            String refreshToken,
            List<String> roles,
            boolean active
    ) {
        TokenPair tokens() {
            return new TokenPair(accessToken, refreshToken, expiresAt, refreshExpiresAt);
        }

        SessionView sessionView() {
            return new SessionView(sessionId, provider, issuedAt, expiresAt, refreshExpiresAt, active);
        }

        SessionRecord rotate() {
            Instant now = Instant.now();
            return new SessionRecord(
                    sessionId,
                    userId,
                    provider,
                    issuedAt,
                    now.plus(ACCESS_TTL),
                    now.plus(REFRESH_TTL),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    roles,
                    active
            );
        }
    }
}
