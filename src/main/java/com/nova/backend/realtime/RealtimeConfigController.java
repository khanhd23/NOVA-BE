package com.nova.backend.realtime;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeConfigController {

    private final WebRtcProperties webRtcProperties;

    public RealtimeConfigController(WebRtcProperties webRtcProperties) {
        this.webRtcProperties = webRtcProperties;
    }

    @GetMapping("/config")
    public ApiResponse<RealtimeConfigResponse> config(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(new RealtimeConfigResponse(
                buildIceServers(principal == null ? "anonymous" : principal.userId()),
                Math.max(0, webRtcProperties.maxRestartAttempts()),
                Math.max(250L, webRtcProperties.restartBackoffMs())
        ));
    }

    private List<IceServerResponse> buildIceServers(String userId) {
        List<IceServerResponse> items = new ArrayList<>();

        for (String url : webRtcProperties.parsedIceServers()) {
            items.add(new IceServerResponse(url, null, null));
        }
        String username = blankToNull(webRtcProperties.turnUsername());
        String credential = blankToNull(webRtcProperties.turnCredential());
        if (webRtcProperties.usesTurnSecret()) {
            // TURN REST API (coturn use-auth-secret): username = "<expiry>:<userId>",
            // credential = base64(HMAC-SHA1(secret, username)). coturn verifies it without a user DB.
            long ttl = Math.max(600L, webRtcProperties.turnCredentialTtlSeconds());
            username = (Instant.now().getEpochSecond() + ttl) + ":" + userId;
            credential = hmacSha1Base64(webRtcProperties.turnSecret().trim(), username);
        }
        for (String url : webRtcProperties.parsedTurnServers()) {
            items.add(new IceServerResponse(url, username, credential));
        }

        if (items.isEmpty()) {
            items.add(new IceServerResponse("stun:stun.l.google.com:19302", null, null));
        }
        return items;
    }

    private static String hmacSha1Base64(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to sign TURN credential", ex);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
