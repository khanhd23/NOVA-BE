package com.nova.backend.commerce;

import com.nova.backend.common.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookSignatureVerifierTest {

    private static PaymentWebhookRequest request(String signature) {
        return new PaymentWebhookRequest("order-1", "momo-9", "tx-1", "payment", "SUCCESS", 99000, signature, Map.of());
    }

    @Test
    void acceptsCorrectSignature() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("secret");
        String signature = verifier.sign(WebhookSignatureVerifier.canonicalPayload(request(null)));

        assertDoesNotThrow(() -> verifier.verify(request(signature)));
    }

    @Test
    void rejectsWrongOrMissingSignature() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("secret");

        assertThrows(UnauthorizedException.class, () -> verifier.verify(request("deadbeef")));
        assertThrows(UnauthorizedException.class, () -> verifier.verify(request(null)));
    }

    @Test
    void rejectsTamperedAmount() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("secret");
        String signature = verifier.sign(WebhookSignatureVerifier.canonicalPayload(request(null)));
        PaymentWebhookRequest tampered = new PaymentWebhookRequest(
                "order-1", "momo-9", "tx-1", "payment", "SUCCESS", 1, signature, Map.of());

        assertThrows(UnauthorizedException.class, () -> verifier.verify(tampered));
    }

    @Test
    void rejectsEverythingWithoutConfiguredSecret() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("");

        assertThrows(UnauthorizedException.class, () -> verifier.verify(request("anything")));
    }
}
