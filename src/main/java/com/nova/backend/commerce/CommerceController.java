package com.nova.backend.commerce;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import com.nova.backend.common.exception.BadRequestException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/v1/commerce")
public class CommerceController {

    private final CommerceService commerceService;
    private final WebhookSignatureVerifier webhookSignatureVerifier;

    public CommerceController(CommerceService commerceService, WebhookSignatureVerifier webhookSignatureVerifier) {
        this.commerceService = commerceService;
        this.webhookSignatureVerifier = webhookSignatureVerifier;
    }

    @GetMapping("/catalog")
    public ApiResponse<CommerceCatalogResponse> catalog() {
        return ApiResponse.ok(commerceService.catalog());
    }

    @GetMapping("/providers")
    public ApiResponse<?> providers() {
        return ApiResponse.ok(commerceService.providers());
    }

    @GetMapping("/me")
    public ApiResponse<CommerceMeResponse> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(commerceService.me(principal.userId()));
    }

    @GetMapping("/orders")
    public ApiResponse<CommerceOrderListResponse> orders(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(commerceService.orders(principal.userId()));
    }

    @GetMapping("/orders/{orderId}")
    public ApiResponse<CommerceOrderResponse> order(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String orderId
    ) {
        return ApiResponse.ok(commerceService.order(principal.userId(), orderId));
    }

    @PostMapping("/orders")
    public ApiResponse<CommerceOrderResponse> createOrder(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateCommerceOrderRequest request
    ) {
        return ApiResponse.ok(commerceService.createOrder(principal.userId(), request));
    }

    @PostMapping("/orders/{orderId}/checkout")
    public ApiResponse<CommerceOrderResponse> checkout(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String orderId
    ) {
        return ApiResponse.ok(commerceService.checkout(principal.userId(), orderId));
    }

    @PostMapping("/orders/{orderId}/confirm")
    public ApiResponse<CommerceOrderResponse> confirm(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String orderId,
            @Valid @RequestBody ConfirmCommerceOrderRequest request
    ) {
        return ApiResponse.ok(commerceService.confirm(principal.userId(), orderId, request));
    }

    @PostMapping("/webhooks/{provider}")
    public ApiResponse<PaymentWebhookResponse> webhook(
            @PathVariable String provider,
            @RequestBody PaymentWebhookRequest request
    ) {
        webhookSignatureVerifier.verify(request);
        return ApiResponse.ok(commerceService.webhook(parseProvider(provider), request));
    }

    private PaymentProvider parseProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        try {
            return PaymentProvider.valueOf(provider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported payment provider");
        }
    }
}
