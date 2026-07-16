package com.nova.backend.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "webrtc")
public record WebRtcProperties(
        String iceServers,
        String turnServers,
        String turnUsername,
        String turnCredential,
        int maxRestartAttempts,
        long restartBackoffMs
) {
    public List<String> parsedIceServers() {
        return parsedList(iceServers);
    }

    public List<String> parsedTurnServers() {
        return parsedList(turnServers);
    }

    private List<String> parsedList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }
}
