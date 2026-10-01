package com.nova.backend.commerce;

import com.nova.backend.common.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Payment webhooks are public endpoints, so every call must prove it comes from the payment
 * gateway: signature = hex(HMAC-SHA256(secret, orderId|providerOrderId|transactionId|status|amount)).
 * Without a configured secret all webhooks are rejected (fail closed).
 */
@Component
public class WebhookSignatureVerifier {

    private final String secret;

    public WebhookSignatureVerifier(@Value("${commerce.webhook-secret:}") String secret) {
        this.secret = secret == null ? "" : secret.trim();
    }

    public void verify(PaymentWebhookRequest request) {
        if (secret.isEmpty()) {
            throw new UnauthorizedException("Payment webhooks are disabled: no webhook secret configured");
        }
        String provided = request.signature() == null ? "" : request.signature().trim().toLowerCase();
        String expected = sign(canonicalPayload(request));
        boolean valid = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
        if (!valid) {
            throw new UnauthorizedException("Invalid webhook signature");
        }
    }

    static String canonicalPayload(PaymentWebhookRequest request) {
        return String.join("|",
                Objects.toString(request.orderId(), ""),
                Objects.toString(request.providerOrderId(), ""),
                Objects.toString(request.transactionId(), ""),
                Objects.toString(request.status(), ""),
                Objects.toString(request.amount(), ""));
    }

    String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to verify webhook signature", ex);
        }
    }
}
