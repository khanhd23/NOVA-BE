package com.nova.backend.commerce;

import com.nova.backend.account.AccountService;
import com.nova.backend.common.exception.BadRequestException;
import com.nova.backend.common.exception.ConflictException;
import com.nova.backend.common.exception.NotFoundException;
import com.nova.backend.common.ModuleStateStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CommerceService {

    private static final Duration ORDER_TTL = Duration.ofMinutes(15);

    private final AccountService accountService;
    private final ModuleStateStore moduleStateStore;
    private final Map<String, CommerceProductDefinition> products = new LinkedHashMap<>();
    private final Map<String, PaymentProviderResponse> paymentProviders = new LinkedHashMap<>();
    private final Map<String, CommerceOrderRecord> orders = new ConcurrentHashMap<>();
    private final AtomicInteger orderSequence = new AtomicInteger(1000);

    public CommerceService(AccountService accountService, ModuleStateStore moduleStateStore) {
        this.accountService = accountService;
        this.moduleStateStore = moduleStateStore;
        seedCatalog();
        loadPersistedState();
    }

    public CommerceCatalogResponse catalog() {
        return new CommerceCatalogResponse(
                products.values().stream()
                        .filter(product -> product.purchaseType() == CommercePurchaseType.VIP)
                        .sorted(Comparator.comparingInt(CommerceProductDefinition::level))
                        .map(this::toVipTierResponse)
                        .toList(),
                products.values().stream()
                        .filter(product -> product.purchaseType() == CommercePurchaseType.DIAMOND)
                        .sorted(Comparator.comparingInt(CommerceProductDefinition::sortOrder))
                        .map(this::toDiamondPackageResponse)
                        .toList(),
                providers()
        );
    }

    public List<PaymentProviderResponse> providers() {
        return paymentProviders.values().stream().toList();
    }

    public CommerceMeResponse me(String userId) {
        List<CommerceOrderResponse> recentOrders = orders.values().stream()
                .filter(order -> Objects.equals(order.userId(), userId))
                .sorted(Comparator.comparing(CommerceOrderRecord::updatedAt).reversed())
                .limit(10)
                .map(this::toResponse)
                .toList();

        String vipTierId = accountService.vipTierId(userId);
        CommerceProductDefinition vipTier = vipTierId == null ? null : products.get(vipTierId);
        boolean vipActive = accountService.isPremium(userId);
        List<String> activeBenefits = vipActive && vipTier != null ? vipTier.features() : List.of();
        String expiresAt = formatInstant(accountService.vipExpiresAt(userId));

        return new CommerceMeResponse(
                userId,
                vipActive,
                vipTierId,
                vipTier == null ? null : vipTier.name(),
                expiresAt,
                accountService.diamondBalance(userId),
                activeBenefits,
                recentOrders
        );
    }

    public CommerceOrderListResponse orders(String userId) {
        List<CommerceOrderResponse> items = orders.values().stream()
                .filter(order -> Objects.equals(order.userId(), userId))
                .sorted(Comparator.comparing(CommerceOrderRecord::updatedAt).reversed())
                .map(this::toResponse)
                .toList();
        return new CommerceOrderListResponse(items);
    }

    public CommerceOrderResponse order(String userId, String orderId) {
        CommerceOrderRecord order = requireOrder(orderId);
        if (!Objects.equals(order.userId(), userId)) {
            throw new NotFoundException("Order not found");
        }
        return toResponse(order);
    }

    public CommerceOrderResponse createOrder(String userId, CreateCommerceOrderRequest request) {
        CommerceProductDefinition product = requireProduct(request.productId(), request.purchaseType());
        if (product.purchaseType() != request.purchaseType()) {
            throw new BadRequestException("Product type does not match selected purchase type");
        }

        String orderId = "ord-" + orderSequence.getAndIncrement();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ORDER_TTL);
        CommerceGrantResponse grant = estimateGrant(userId, product, expiresAt);
        CommerceOrderRecord order = new CommerceOrderRecord(
                orderId,
                userId,
                product.purchaseType(),
                product.id(),
                product.name(),
                product.subtitle(),
                product.amount(),
                product.currency(),
                request.provider(),
                providerOrderId(request.provider(), orderId),
                checkoutUrl(request.provider(), orderId),
                qrContent(request.provider(), product, orderId),
                CommerceOrderStatus.PENDING_PAYMENT,
                now,
                now,
                expiresAt,
                grant,
                request.note(),
                null,
                null,
                request.redirectUrl(),
                request.callbackUrl()
        );
        orders.put(orderId, order);
        persistState();
        return toResponse(order);
    }

    public CommerceOrderResponse checkout(String userId, String orderId) {
        CommerceOrderRecord order = requireOwnedOrder(userId, orderId);
        if (order.status == CommerceOrderStatus.SUCCESS) {
            return toResponse(order);
        }
        order.providerOrderId = providerOrderId(order.provider(), order.id());
        order.checkoutUrl = checkoutUrl(order.provider(), order.id());
        order.qrContent = qrContent(order.provider(), requireProduct(order.productId(), order.purchaseType()), order.id());
        order.updatedAt = Instant.now();
        if (order.status == CommerceOrderStatus.CREATED) {
            order.status = CommerceOrderStatus.PENDING_PAYMENT;
        }
        persistState();
        return toResponse(order);
    }

    public CommerceOrderResponse confirm(String userId, String orderId, ConfirmCommerceOrderRequest request) {
        CommerceOrderRecord order = requireOwnedOrder(userId, orderId);
        CommerceOrderResponse response = finalizeOrder(order, request.success(), request.transactionId(), request.message(), "manual-confirm");
        persistState();
        return response;
    }

    public PaymentWebhookResponse webhook(PaymentProvider provider, PaymentWebhookRequest request) {
        CommerceOrderRecord order = locateOrder(request.orderId(), request.providerOrderId());
        if (order == null) {
            throw new NotFoundException("Order not found");
        }
        if (provider != null && order.provider() != provider) {
            throw new ConflictException("Webhook provider does not match order provider");
        }

        boolean success = isSuccessStatus(request.status());
        CommerceOrderResponse response = finalizeOrder(order, success, request.transactionId(), request.status(), provider == null ? "webhook" : provider.name());
        persistState();
        return new PaymentWebhookResponse(
                provider == null ? order.provider().name() : provider.name(),
                response.orderId(),
                response.status().name(),
                success ? "Payment processed" : "Payment failed",
                response.transactionId()
        );
    }

    private CommerceOrderResponse finalizeOrder(
            CommerceOrderRecord order,
            boolean success,
            String transactionId,
            String message,
            String source
    ) {
        Instant now = Instant.now();
        if (order.status == CommerceOrderStatus.SUCCESS) {
            return toResponse(order);
        }
        if (!success) {
            order.status = CommerceOrderStatus.FAILED;
            order.failureReason = message == null || message.isBlank() ? source + " rejected payment" : message;
            order.updatedAt = now;
            order.transactionId = transactionId;
            return toResponse(order);
        }

        CommerceProductDefinition product = requireProduct(order.productId(), order.purchaseType());
        if (order.purchaseType() == CommercePurchaseType.VIP) {
            Instant vipExpiresAt = now.plus(Duration.ofDays(product.durationDays()));
            accountService.activateVip(
                    order.userId(),
                    product.id(),
                    product.name(),
                    vipExpiresAt,
                    "Purchased via " + order.provider().name(),
                    "-" + formatAmount(order.amount(), order.currency())
            );
            order.grant = new CommerceGrantResponse(
                    "VIP",
                    product.id(),
                    product.name(),
                    formatInstant(vipExpiresAt),
                    null,
                    accountService.diamondBalance(order.userId()),
                    product.features()
            );
        } else {
            long diamonds = product.diamonds();
            accountService.addDiamonds(
                    order.userId(),
                    diamonds,
                    product.name(),
                    "Diamond top-up via " + order.provider().name(),
                    "+" + formatAmount(diamonds, "DIAMONDS")
            );
            order.grant = new CommerceGrantResponse(
                    "DIAMOND",
                    null,
                    null,
                    null,
                    product.diamonds(),
                    accountService.diamondBalance(order.userId()),
                    List.of("Top-up completed")
            );
        }

        order.status = CommerceOrderStatus.SUCCESS;
        order.transactionId = transactionId;
        order.failureReason = null;
        order.updatedAt = now;
        return toResponse(order);
    }

    private CommerceOrderRecord requireOwnedOrder(String userId, String orderId) {
        CommerceOrderRecord order = requireOrder(orderId);
        if (!Objects.equals(order.userId(), userId)) {
            throw new NotFoundException("Order not found");
        }
        return order;
    }

    private CommerceOrderRecord requireOrder(String orderId) {
        CommerceOrderRecord order = orders.get(orderId);
        if (order == null) {
            throw new NotFoundException("Order not found");
        }
        if (order.status != CommerceOrderStatus.SUCCESS && Instant.now().isAfter(order.expiresAt())) {
            order.status = CommerceOrderStatus.EXPIRED;
            order.updatedAt = Instant.now();
            persistState();
        }
        return order;
    }

    private CommerceOrderRecord locateOrder(String orderId, String providerOrderId) {
        if (orderId != null && !orderId.isBlank()) {
            CommerceOrderRecord order = orders.get(orderId);
            if (order != null) {
                return order;
            }
        }
        if (providerOrderId == null || providerOrderId.isBlank()) {
            return null;
        }
        return orders.values().stream()
                .filter(order -> Objects.equals(order.providerOrderId(), providerOrderId))
                .findFirst()
                .orElse(null);
    }

    private CommerceProductDefinition requireProduct(String productId, CommercePurchaseType purchaseType) {
        CommerceProductDefinition product = products.get(productId);
        if (product == null) {
            throw new NotFoundException("Product not found");
        }
        if (purchaseType != null && product.purchaseType() != purchaseType) {
            throw new BadRequestException("Product type does not match request");
        }
        return product;
    }

    private CommerceGrantResponse estimateGrant(String userId, CommerceProductDefinition product, Instant vipExpiresAt) {
        if (product.purchaseType() == CommercePurchaseType.VIP) {
            return new CommerceGrantResponse(
                    "VIP",
                    product.id(),
                    product.name(),
                    formatInstant(vipExpiresAt),
                    null,
                    accountService.diamondBalance(userId),
                    product.features()
            );
        }
        return new CommerceGrantResponse(
                "DIAMOND",
                null,
                null,
                null,
                product.diamonds(),
                accountService.diamondBalance(userId) + product.diamonds(),
                List.of("Top-up completed")
        );
    }

    private CommerceOrderResponse toResponse(CommerceOrderRecord order) {
        return new CommerceOrderResponse(
                order.id(),
                order.userId(),
                order.purchaseType(),
                order.productId(),
                order.productName(),
                order.productSubtitle(),
                order.amount(),
                order.currency(),
                order.status(),
                order.provider(),
                order.providerOrderId(),
                order.checkoutUrl(),
                order.qrContent(),
                formatInstant(order.createdAt()),
                formatInstant(order.updatedAt()),
                formatInstant(order.expiresAt()),
                order.grant(),
                order.note(),
                order.transactionId(),
                order.failureReason()
        );
    }

    private VipTierResponse toVipTierResponse(CommerceProductDefinition product) {
        return new VipTierResponse(
                product.id(),
                product.name(),
                product.level(),
                formatAmount(product.amount(), product.currency()),
                product.cycle(),
                product.subtitle(),
                product.badgeLabel(),
                product.features(),
                product.highlighted(),
                product.accentColor(),
                product.durationDays()
        );
    }

    private DiamondPackageResponse toDiamondPackageResponse(CommerceProductDefinition product) {
        return new DiamondPackageResponse(
                product.id(),
                product.name(),
                product.diamonds(),
                formatAmount(product.amount(), product.currency()),
                product.subtitle(),
                product.bonusLabel(),
                product.highlighted(),
                product.accentColor()
        );
    }

    private String checkoutUrl(PaymentProvider provider, String orderId) {
        if (provider == null) {
            return null;
        }
        return switch (provider) {
            case DEMO -> "nova://commerce/checkout/" + orderId;
            case MOMO -> null;
            case ZALOPAY -> null;
            case VNPAY -> null;
        };
    }

    private String qrContent(PaymentProvider provider, CommerceProductDefinition product, String orderId) {
        if (provider == PaymentProvider.DEMO) {
            return "NOVA|" + orderId + "|" + product.id() + "|" + product.amount();
        }
        return null;
    }

    private String providerOrderId(PaymentProvider provider, String orderId) {
        if (provider == null) {
            return null;
        }
        return provider.name() + "-" + orderId;
    }

    private boolean isSuccessStatus(String status) {
        if (status == null) {
            return true;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("SUCCESS")
                || normalized.equals("PAID")
                || normalized.equals("OK")
                || normalized.equals("COMPLETED")
                || normalized.equals("SUCCESSFUL");
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private String timeLabel(Instant instant) {
        return DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private String formatAmount(long amount, String currency) {
        String formatted = String.format(Locale.US, "%,d", amount);
        if ("DIAMONDS".equalsIgnoreCase(currency)) {
            return formatted + " Diamonds";
        }
        return formatted + " " + currency;
    }

    private void seedCatalog() {
        registerProvider(
                PaymentProvider.DEMO,
                "Demo Sandbox",
                "Local sandbox for UI wiring and backend verification",
                true,
                true,
                null,
                List.of("sandbox", "manual-confirm", "webhook-test")
        );
        registerProvider(
                PaymentProvider.MOMO,
                "MoMo",
                "Wallet payment with redirect and IPN callback",
                false,
                true,
                "https://developers.momo.vn/v3/docs/payment/api/credit/onetime/",
                List.of("redirect", "ipn", "qr")
        );
        registerProvider(
                PaymentProvider.ZALOPAY,
                "ZaloPay",
                "Payment gateway with callback and order query",
                false,
                true,
                "https://docs.zalopay.vn/docs/specs/order-create/",
                List.of("callback", "order-query", "app-to-app")
        );
        registerProvider(
                PaymentProvider.VNPAY,
                "VNPAY",
                "Bank gateway for cards, QR and bank redirects",
                false,
                false,
                "https://vnpay.vn/",
                List.of("bank", "qr", "redirect")
        );

        registerVip(
                "vip_1",
                "VIP 1 Spark",
                1,
                49000,
                "VND",
                "/month",
                "Entry tier for faster discovery",
                "Starter",
                List.of("No ads", "1 daily boost", "See who liked you"),
                false,
                "#6D5EF9",
                30
        );
        registerVip(
                "vip_2",
                "VIP 2 Glow",
                2,
                99000,
                "VND",
                "/month",
                "Better reach and tighter filters",
                "Glow",
                List.of("2 daily boosts", "Advanced filters", "Rewind"),
                false,
                "#5B7CFF",
                30
        );
        registerVip(
                "vip_3",
                "VIP 3 Pulse",
                3,
                149000,
                "VND",
                "/month",
                "Balanced tier for active users",
                "Popular",
                List.of("Unlimited rewind", "Invisible mode", "Priority chat"),
                true,
                "#8B5CF6",
                30
        );
        registerVip(
                "vip_4",
                "VIP 4 Elite",
                4,
                229000,
                "VND",
                "/month",
                "For users who want stronger reach",
                "Elite",
                List.of("5 daily boosts", "Read receipts control", "Priority placement"),
                false,
                "#F97316",
                30
        );
        registerVip(
                "vip_5",
                "VIP 5 Prime",
                5,
                329000,
                "VND",
                "/month",
                "Fast discovery with premium tools",
                "Prime",
                List.of("Travel mode", "Unlimited likes", "Advanced filters"),
                true,
                "#EC4899",
                30
        );
        registerVip(
                "vip_6",
                "VIP 6 Aura",
                6,
                499000,
                "VND",
                "/month",
                "High visibility and social reach",
                "Aura",
                List.of("VIP frame", "Priority support", "Pinned profile"),
                false,
                "#14B8A6",
                30
        );
        registerVip(
                "vip_7",
                "VIP 7 Infinity",
                7,
                799000,
                "VND",
                "/month",
                "Top tier for full premium experience",
                "Infinity",
                List.of("Unlimited boosts", "VIP frame", "All premium features"),
                true,
                "#F43F5E",
                30
        );

        registerDiamond(
                "diamond_50",
                "Spark Pack",
                50,
                29000,
                "VND",
                "Small top-up for quick actions",
                "No bonus",
                false,
                "#64748B"
        );
        registerDiamond(
                "diamond_120",
                "Starter Pack",
                120,
                59000,
                "VND",
                "Good starter balance",
                "+10 bonus",
                false,
                "#7C3AED"
        );
        registerDiamond(
                "diamond_250",
                "Popular Pack",
                250,
                119000,
                "VND",
                "Balanced choice for active users",
                "+25 bonus",
                true,
                "#0EA5E9"
        );
        registerDiamond(
                "diamond_550",
                "Growth Pack",
                550,
                249000,
                "VND",
                "Better value for regular buyers",
                "+60 bonus",
                false,
                "#10B981"
        );
        registerDiamond(
                "diamond_1200",
                "Pro Pack",
                1200,
                499000,
                "VND",
                "For users who buy often",
                "+150 bonus",
                true,
                "#F59E0B"
        );
        registerDiamond(
                "diamond_2500",
                "Max Pack",
                2500,
                899000,
                "VND",
                "Largest pack for power users",
                "+350 bonus",
                false,
                "#EF4444"
        );
    }

    private void loadPersistedState() {
        moduleStateStore.load("commerce", CommerceState.class).ifPresentOrElse(state -> {
            orders.clear();
            if (state.orders() != null) {
                for (CommerceOrderSnapshot snapshot : state.orders()) {
                    CommerceOrderRecord record = toOrderRecord(snapshot);
                    orders.put(record.id(), record);
                }
            }
            orderSequence.set(Math.max(1000, state.orderSequence()));
        }, this::persistState);
    }

    private void persistState() {
        moduleStateStore.save("commerce", new CommerceState(
                orders.values().stream().map(this::toSnapshot).toList(),
                orderSequence.get()
        ));
    }

    private CommerceOrderSnapshot toSnapshot(CommerceOrderRecord order) {
        return new CommerceOrderSnapshot(
                order.id(),
                order.userId(),
                order.purchaseType(),
                order.productId(),
                order.productName(),
                order.productSubtitle(),
                order.amount(),
                order.currency(),
                order.provider(),
                order.providerOrderId(),
                order.checkoutUrl(),
                order.qrContent(),
                order.status(),
                order.createdAt(),
                order.updatedAt(),
                order.expiresAt(),
                order.grant(),
                order.note(),
                order.transactionId(),
                order.failureReason(),
                order.redirectUrl(),
                order.callbackUrl()
        );
    }

    private CommerceOrderRecord toOrderRecord(CommerceOrderSnapshot snapshot) {
        return new CommerceOrderRecord(
                snapshot.id(),
                snapshot.userId(),
                snapshot.purchaseType(),
                snapshot.productId(),
                snapshot.productName(),
                snapshot.productSubtitle(),
                snapshot.amount(),
                snapshot.currency(),
                snapshot.provider(),
                snapshot.providerOrderId(),
                snapshot.checkoutUrl(),
                snapshot.qrContent(),
                snapshot.status(),
                snapshot.createdAt(),
                snapshot.updatedAt(),
                snapshot.expiresAt(),
                snapshot.grant(),
                snapshot.note(),
                snapshot.transactionId(),
                snapshot.failureReason(),
                snapshot.redirectUrl(),
                snapshot.callbackUrl()
        );
    }

    private void registerProvider(
            PaymentProvider provider,
            String name,
            String subtitle,
            boolean available,
            boolean recommended,
            String docsUrl,
            List<String> capabilities
    ) {
        paymentProviders.put(
                provider.name(),
                new PaymentProviderResponse(
                        provider.name(),
                        name,
                        subtitle,
                        available,
                        recommended,
                        docsUrl,
                        capabilities
                )
        );
    }

    private void registerVip(
            String id,
            String name,
            int level,
            int amount,
            String currency,
            String cycle,
            String subtitle,
            String badgeLabel,
            List<String> features,
            boolean highlighted,
            String accentColor,
            int durationDays
    ) {
        products.put(
                id,
                new CommerceProductDefinition(
                        CommercePurchaseType.VIP,
                        id,
                        name,
                        level,
                        amount,
                        currency,
                        cycle,
                        subtitle,
                        badgeLabel,
                        features,
                        highlighted,
                        accentColor,
                        durationDays,
                        0,
                        null,
                        level
                )
        );
    }

    private void registerDiamond(
            String id,
            String name,
            int diamonds,
            int amount,
            String currency,
            String subtitle,
            String bonusLabel,
            boolean bestValue,
            String accentColor
    ) {
        products.put(
                id,
                new CommerceProductDefinition(
                        CommercePurchaseType.DIAMOND,
                        id,
                        name,
                        0,
                        amount,
                        currency,
                        null,
                        subtitle,
                        bonusLabel,
                        List.of(),
                        bestValue,
                        accentColor,
                        0,
                        diamonds,
                        bonusLabel,
                        products.size() + 100
                )
        );
    }

    private record CommerceProductDefinition(
            CommercePurchaseType purchaseType,
            String id,
            String name,
            int level,
            int amount,
            String currency,
            String cycle,
            String subtitle,
            String badgeLabel,
            List<String> features,
            boolean highlighted,
            String accentColor,
            int durationDays,
            int diamonds,
            String bonusLabel,
            int sortOrder
    ) {
    }

    private record CommerceState(
            List<CommerceOrderSnapshot> orders,
            int orderSequence
    ) {
    }

    private record CommerceOrderSnapshot(
            String id,
            String userId,
            CommercePurchaseType purchaseType,
            String productId,
            String productName,
            String productSubtitle,
            int amount,
            String currency,
            PaymentProvider provider,
            String providerOrderId,
            String checkoutUrl,
            String qrContent,
            CommerceOrderStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            CommerceGrantResponse grant,
            String note,
            String transactionId,
            String failureReason,
            String redirectUrl,
            String callbackUrl
    ) {
    }

    private static final class CommerceOrderRecord {
        final String id;
        final String userId;
        final CommercePurchaseType purchaseType;
        final String productId;
        final String productName;
        final String productSubtitle;
        final int amount;
        final String currency;
        final PaymentProvider provider;
        String providerOrderId;
        String checkoutUrl;
        String qrContent;
        CommerceOrderStatus status;
        final Instant createdAt;
        Instant updatedAt;
        final Instant expiresAt;
        CommerceGrantResponse grant;
        final String note;
        String transactionId;
        String failureReason;
        final String redirectUrl;
        final String callbackUrl;

        CommerceOrderRecord(
                String id,
                String userId,
                CommercePurchaseType purchaseType,
                String productId,
                String productName,
                String productSubtitle,
                int amount,
                String currency,
                PaymentProvider provider,
                String providerOrderId,
                String checkoutUrl,
                String qrContent,
                CommerceOrderStatus status,
                Instant createdAt,
                Instant updatedAt,
                Instant expiresAt,
                CommerceGrantResponse grant,
                String note,
                String transactionId,
                String failureReason,
                String redirectUrl,
                String callbackUrl
        ) {
            this.id = id;
            this.userId = userId;
            this.purchaseType = purchaseType;
            this.productId = productId;
            this.productName = productName;
            this.productSubtitle = productSubtitle;
            this.amount = amount;
            this.currency = currency;
            this.provider = provider;
            this.providerOrderId = providerOrderId;
            this.checkoutUrl = checkoutUrl;
            this.qrContent = qrContent;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.expiresAt = expiresAt;
            this.grant = grant;
            this.note = note;
            this.transactionId = transactionId;
            this.failureReason = failureReason;
            this.redirectUrl = redirectUrl;
            this.callbackUrl = callbackUrl;
        }

        String id() { return id; }
        String userId() { return userId; }
        CommercePurchaseType purchaseType() { return purchaseType; }
        String productId() { return productId; }
        String productName() { return productName; }
        String productSubtitle() { return productSubtitle; }
        int amount() { return amount; }
        String currency() { return currency; }
        PaymentProvider provider() { return provider; }
        String providerOrderId() { return providerOrderId; }
        String checkoutUrl() { return checkoutUrl; }
        String qrContent() { return qrContent; }
        CommerceOrderStatus status() { return status; }
        Instant createdAt() { return createdAt; }
        Instant updatedAt() { return updatedAt; }
        Instant expiresAt() { return expiresAt; }
        CommerceGrantResponse grant() { return grant; }
        String note() { return note; }
        String transactionId() { return transactionId; }
        String failureReason() { return failureReason; }
        String redirectUrl() { return redirectUrl; }
        String callbackUrl() { return callbackUrl; }
    }
}
