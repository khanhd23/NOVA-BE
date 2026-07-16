package com.nova.backend.realtime;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DeviceTokenService {

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, Map<String, DeviceTokenResponse>> tokensByUser = new ConcurrentHashMap<>();

    public DeviceTokenService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        loadPersistedTokens();
    }

    public DeviceTokenResponse register(String userId, RegisterDeviceTokenRequest request) {
        DeviceTokenResponse token = new DeviceTokenResponse(
                request.token(),
                request.platform().trim().toUpperCase(Locale.ROOT),
                request.deviceId(),
                request.appVersion(),
                Instant.now()
        );
        tokensByUser.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .put(token.token(), token);
        persistToken(userId, token);
        return token;
    }

    public List<DeviceTokenResponse> list(String userId) {
        return new ArrayList<>(tokensByUser.getOrDefault(userId, Map.of()).values()).stream()
                .sorted(Comparator.comparing(DeviceTokenResponse::registeredAt).reversed())
                .toList();
    }

    public void remove(String userId, String token) {
        Map<String, DeviceTokenResponse> tokens = tokensByUser.get(userId);
        if (tokens != null) {
            tokens.remove(token);
        }
        jdbcTemplate.update("DELETE FROM device_tokens WHERE user_id = ? AND token = ?", userId, token);
    }

    public List<String> tokensForUsers(Collection<String> userIds) {
        return userIds.stream()
                .flatMap(userId -> list(userId).stream())
                .map(DeviceTokenResponse::token)
                .distinct()
                .collect(Collectors.toList());
    }

    private void loadPersistedTokens() {
        try {
            List<PersistedDeviceToken> persisted = jdbcTemplate.query("SELECT * FROM device_tokens", (rs, rowNum) -> rowToToken(rs));
            for (PersistedDeviceToken record : persisted) {
                tokensByUser.computeIfAbsent(record.userId(), ignored -> new ConcurrentHashMap<>())
                        .put(record.token().token(), record.token());
            }
        } catch (Exception ignored) {
            tokensByUser.clear();
        }
    }

    private void persistToken(String userId, DeviceTokenResponse token) {
        int updated = jdbcTemplate.update(
                """
                        UPDATE device_tokens SET
                            user_id = ?,
                            platform = ?,
                            device_id = ?,
                            app_version = ?,
                            registered_at = ?
                        WHERE token = ?
                        """,
                userId,
                token.platform(),
                token.deviceId(),
                token.appVersion(),
                Timestamp.from(token.registeredAt()),
                token.token()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            INSERT INTO device_tokens (token, user_id, platform, device_id, app_version, registered_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    token.token(),
                    userId,
                    token.platform(),
                    token.deviceId(),
                    token.appVersion(),
                    Timestamp.from(token.registeredAt())
            );
        }
    }

    private PersistedDeviceToken rowToToken(ResultSet rs) throws SQLException {
        return new PersistedDeviceToken(
                rs.getString("user_id"),
                new DeviceTokenResponse(
                        rs.getString("token"),
                        rs.getString("platform"),
                        rs.getString("device_id"),
                        rs.getString("app_version"),
                        rs.getTimestamp("registered_at").toInstant()
                )
        );
    }

    private record PersistedDeviceToken(String userId, DeviceTokenResponse token) {
    }
}
