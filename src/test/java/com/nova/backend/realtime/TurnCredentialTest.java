package com.nova.backend.realtime;

import com.nova.backend.auth.AuthPrincipal;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnCredentialTest {

    private static final AuthPrincipal USER = new AuthPrincipal("u-42", "s-1", "Test", List.of());

    @Test
    void secretModeIssuesCoturnRestCredentials() throws Exception {
        WebRtcProperties properties = new WebRtcProperties(
                "stun:stun.l.google.com:19302",
                "turn:1.2.3.4:3478?transport=udp",
                "", "", "shared-secret", 3600, 2, 1000);

        List<IceServerResponse> servers = new RealtimeConfigController(properties).config(USER).data().iceServers();

        assertEquals(2, servers.size());
        assertNull(servers.get(0).username(), "STUN servers carry no credentials");

        IceServerResponse turn = servers.get(1);
        String[] parts = turn.username().split(":");
        assertEquals("u-42", parts[1]);
        long expiry = Long.parseLong(parts[0]);
        long now = Instant.now().getEpochSecond();
        assertTrue(expiry > now + 3500 && expiry <= now + 3600, "expiry follows the TTL");

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("shared-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(turn.username().getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, turn.credential(), "credential = base64(HMAC-SHA1(secret, username)) as coturn expects");
    }

    @Test
    void staticModeKeepsConfiguredUsernameAndPassword() {
        WebRtcProperties properties = new WebRtcProperties(
                "", "turn:1.2.3.4:3478", "nova", "pass", "", 3600, 2, 1000);

        IceServerResponse turn = new RealtimeConfigController(properties).config(USER).data().iceServers().get(0);

        assertEquals("nova", turn.username());
        assertEquals("pass", turn.credential());
    }
}
