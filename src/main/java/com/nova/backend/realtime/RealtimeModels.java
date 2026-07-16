package com.nova.backend.realtime;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;

record PushMessage(
        String title,
        String body,
        Map<String, String> data
) {
}

record RegisterDeviceTokenRequest(
        @NotBlank String token,
        @NotBlank String platform,
        String deviceId,
        String appVersion
) {
}

record DeviceTokenResponse(
        String token,
        String platform,
        String deviceId,
        String appVersion,
        Instant registeredAt
) {
}

record DeviceTokenListResponse(
        List<DeviceTokenResponse> items
) {
}

record IceServerResponse(
        String url,
        String username,
        String credential
) {
}

record RealtimeConfigResponse(
        List<IceServerResponse> iceServers,
        int maxRestartAttempts,
        long restartBackoffMs
) {
}
