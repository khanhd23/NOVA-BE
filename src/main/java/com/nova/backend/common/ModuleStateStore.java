package com.nova.backend.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Service
public class ModuleStateStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ModuleStateStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public <T> Optional<T> load(String key, Class<T> type) {
        String json = jdbcTemplate.query(
                "SELECT state_json FROM module_state WHERE state_key = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                key
        );
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read module state for key " + key, ex);
        }
    }

    public <T> Optional<T> load(String key, TypeReference<T> typeReference) {
        String json = jdbcTemplate.query(
                "SELECT state_json FROM module_state WHERE state_key = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                key
        );
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, typeReference));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read module state for key " + key, ex);
        }
    }

    public void save(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            int updated = jdbcTemplate.update(
                    """
                            UPDATE module_state
                            SET state_json = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE state_key = ?
                            """,
                    json,
                    key
            );
            if (updated == 0) {
                jdbcTemplate.update(
                        """
                                INSERT INTO module_state (state_key, state_json, updated_at)
                                VALUES (?, ?, ?)
                                """,
                        key,
                        json,
                        Timestamp.from(Instant.now())
                );
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize module state for key " + key, ex);
        }
    }
}
