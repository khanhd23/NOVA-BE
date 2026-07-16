package com.nova.backend.commerce;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

enum CommercePurchaseType {
    VIP,
    DIAMOND
}

enum CommerceOrderStatus {
    CREATED,
    PENDING_PAYMENT,
    SUCCESS,
    FAILED,
    CANCELED,
    EXPIRED
}

enum PaymentProvider {
    DEMO,
    MOMO,
    ZALOPAY,
    VNPAY
}

record VipTierResponse(
        String id,
        String name,
        int level,
        String price,
        String cycle,
        String subtitle,
        String badgeLabel,
        List<String> features,
        boolean highlighted,
        String accentColor,
        int durationDays
) {
}

record DiamondPackageResponse(
        String id,
        String name,
        int diamonds,
        String price,
        String subtitle,
        String bonusLabel,
        boolean bestValue,
        String accentColor
) {
}

record PaymentProviderResponse(
        String id,
        String name,
        String subtitle,
        boolean available,
        boolean recommended,
        String docsUrl,
        List<String> capabilities
) {
}

record CommerceCatalogResponse(
        List<VipTierResponse> vipTiers,
        List<DiamondPackageResponse> diamondPackages,
        List<PaymentProviderResponse> paymentProviders
) {
}

record CommerceGrantResponse(
        String grantType,
        String vipTierId,
        String vipTierName,
        String vipExpiresAt,
        Integer diamondsAdded,
        Long diamondBalanceAfter,
        List<String> benefits
) {
}

record CommerceOrderResponse(
        String orderId,
        String userId,
        CommercePurchaseType purchaseType,
        String productId,
        String productName,
        String productSubtitle,
        int amount,
        String currency,
        CommerceOrderStatus status,
        PaymentProvider provider,
        String providerOrderId,
        String checkoutUrl,
        String qrContent,
        String createdAt,
        String updatedAt,
        String expiresAt,
        CommerceGrantResponse grant,
        String note,
        String transactionId,
        String failureReason
) {
}

record CommerceMeResponse(
        String userId,
        boolean vipActive,
        String vipTierId,
        String vipTierName,
        String vipExpiresAt,
        long diamondBalance,
        List<String> activeBenefits,
        List<CommerceOrderResponse> recentOrders
) {
}

record CommerceOrderListResponse(
        List<CommerceOrderResponse> items
) {
}

record CreateCommerceOrderRequest(
        @NotBlank String productId,
        @NotNull CommercePurchaseType purchaseType,
        @NotNull PaymentProvider provider,
        String note,
        String redirectUrl,
        String callbackUrl
) {
}

record ConfirmCommerceOrderRequest(
        @NotNull Boolean success,
        String transactionId,
        String message
) {
}

record PaymentWebhookRequest(
        String orderId,
        String providerOrderId,
        String transactionId,
        String event,
        String status,
        Integer amount,
        String signature,
        Map<String, Object> payload
) {
}

record PaymentWebhookResponse(
        String provider,
        String orderId,
        String status,
        String message,
        String transactionId
) {
}
