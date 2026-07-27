package com.nova.backend.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.backend.auth.SocialIdentity;
import com.nova.backend.common.PageResponse;
import com.nova.backend.common.exception.BadRequestException;
import com.nova.backend.common.exception.NotFoundException;
import com.nova.backend.social.SocialService;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AccountService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<BadgeResponse>> BADGE_LIST = new TypeReference<>() {};
    private static final TypeReference<List<WalletEntryResponse>> WALLET_LIST = new TypeReference<>() {};
    private static final TypeReference<List<EntitlementResponse>> ENTITLEMENT_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, AccountRecord> accounts = new ConcurrentHashMap<>();
    private final Map<String, String> providerLinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> linkedProviderKeysByUserId = new ConcurrentHashMap<>();
    private final Map<String, String> publicIdByUserId = new ConcurrentHashMap<>();
    private final Map<String, String> userIdByPublicId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> followingByUserId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> followersByUserId = new ConcurrentHashMap<>();
    private final SocialService socialService;
    private final List<PremiumPlanResponse> premiumPlans;
    private final AtomicInteger userSequence = new AtomicInteger(100);
    private static final long PUBLIC_ID_MIN = 100_000_000L;
    private static final long PUBLIC_ID_MAX = 999_999_999L;
    private static final String DEFAULT_NEW_USER_BIO = "I'm new here.";
    private static final Set<String> LEGACY_DEMO_USER_IDS = Set.of(
            "u-current",
            "u-seraphina",
            "u-elena",
            "u-marcus",
            "u-chloe",
            "u-alex",
            "u-mina"
    );
    private static final Set<String> LEGACY_DEMO_PROVIDER_KEYS = Set.of(
            "GOOGLE:dev:current",
            "FACEBOOK:dev:current",
            "GOOGLE:dev:seraphina",
            "FACEBOOK:dev:elena",
            "GOOGLE:dev:marcus",
            "FACEBOOK:dev:chloe",
            "GOOGLE:dev:alex",
            "GOOGLE:dev:mina"
    );
    private final AtomicLong publicIdSequence = new AtomicLong(PUBLIC_ID_MIN);

    public AccountService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, @Lazy SocialService socialService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.socialService = socialService;
        this.premiumPlans = List.of(
                new PremiumPlanResponse(
                        "vip_1",
                        "VIP 1 Spark",
                        "49,000 VND",
                        "/month",
                        "Entry tier for faster discovery",
                        List.of("No ads", "1 daily boost", "See who liked you"),
                        false
                ),
                new PremiumPlanResponse(
                        "vip_2",
                        "VIP 2 Glow",
                        "99,000 VND",
                        "/month",
                        "Better reach and tighter filters",
                        List.of("2 daily boosts", "Advanced filters", "Rewind"),
                        false
                ),
                new PremiumPlanResponse(
                        "vip_3",
                        "VIP 3 Pulse",
                        "149,000 VND",
                        "/month",
                        "Balanced tier for active users",
                        List.of("Unlimited rewind", "Invisible mode", "Priority chat"),
                        true
                ),
                new PremiumPlanResponse(
                        "vip_4",
                        "VIP 4 Elite",
                        "229,000 VND",
                        "/month",
                        "For users who want stronger reach",
                        List.of("5 daily boosts", "Read receipts control", "Priority placement"),
                        false
                ),
                new PremiumPlanResponse(
                        "vip_5",
                        "VIP 5 Prime",
                        "329,000 VND",
                        "/month",
                        "Fast discovery with premium tools",
                        List.of("Travel mode", "Unlimited likes", "Advanced filters"),
                        true
                ),
                new PremiumPlanResponse(
                        "vip_6",
                        "VIP 6 Aura",
                        "499,000 VND",
                        "/month",
                        "High visibility and social reach",
                        List.of("Call badge", "Priority support", "Pinned profile"),
                        false
                ),
                new PremiumPlanResponse(
                        "vip_7",
                        "VIP 7 Infinity",
                        "799,000 VND",
                        "/month",
                        "Top tier for full premium experience",
                        List.of("Unlimited boosts", "VIP frame", "All premium features"),
                        true
                )
        );

        loadPersistedAccounts();
        loadFollowRelations();
        removeLegacyDemoAccountsFromMemory();
        ensurePublicIds();
        refreshUserSequence();
        refreshPublicIdSequence();
    }

    public synchronized String upsertSocialUser(SocialIdentity identity) {
        String providerKey = AccountService.providerKey(identity.provider().name(), identity.providerUserId());
        String userId = providerLinks.get(providerKey);
        if (userId == null) {
            String reservedUserId = reservedDevUserId(providerKey);
            if (reservedUserId != null && accounts.containsKey(reservedUserId)) {
                AccountRecord existing = requireAccount(reservedUserId);
                AccountRecord updated = ensurePublicId(existing.withIdentity(identity));
                accounts.put(reservedUserId, updated);
                linkProvider(reservedUserId, providerKey);
                registerPublicId(updated);
                persistAccount(updated);
                return reservedUserId;
            }
            userId = reservedUserId == null ? "u-" + userSequence.getAndIncrement() : reservedUserId;
            AccountRecord record = AccountRecord.newFromIdentity(userId, generatePublicId(), identity);
            accounts.put(userId, record);
            linkProvider(userId, providerKey);
            registerPublicId(record);
            persistAccount(record);
            return userId;
        }

        AccountRecord existing = requireAccount(userId);
        AccountRecord updated = ensurePublicId(existing.withIdentity(identity));
        accounts.put(userId, updated);
        linkProvider(userId, providerKey);
        registerPublicId(updated);
        persistAccount(updated);
        return userId;
    }

    public MeResponse getMe(String userId) {
        return toMe(requireAccount(userId));
    }

    public BootstrapResponse bootstrap(String userId) {
        AccountRecord record = requireAccount(userId);
        return new BootstrapResponse(
                toMe(record),
                List.of("Discover", "Messages", "Calls", "Community"),
                !record.onboardingComplete(),
                !record.profileComplete(),
                0
        );
    }

    public MeResponse updateProfile(String userId, UpdateProfileRequest request) {
        AccountRecord existing = requireAccount(userId);
        AccountRecord updated = existing.updateProfile(request);
        accounts.put(userId, updated);
        persistAccount(updated);
        return toMe(updated);
    }

    public MeResponse updateSettings(String userId, UpdateSettingsRequest request) {
        AccountRecord existing = requireAccount(userId);
        AccountRecord updated = existing.updateSettings(request);
        accounts.put(userId, updated);
        persistAccount(updated);
        return toMe(updated);
    }

    public boolean setOnline(String userId, boolean online) {
        AccountRecord existing = accounts.get(userId);
        if (existing == null || existing.online() == online) {
            return false;
        }
        AccountRecord updated = existing.withOnline(online);
        accounts.put(userId, updated);
        persistAccount(updated);
        return true;
    }

    public PublicUserCard getPublicProfile(String userId) {
        return toPublicCard(resolveAccountByKey(userId), null);
    }

    public PublicUserCard getPublicProfile(String viewerUserId, String userId) {
        return toPublicCard(resolveAccountByKey(userId), viewerUserId);
    }

    public PageResponse<PublicUserCard> profileRelations(String viewerUserId, String userId, String type, int page, int size) {
        AccountRecord target = resolveAccountByKey(userId);
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        List<PublicUserCard> items = relationUserIds(viewerUserId, target.userId(), normalized).stream()
                .map(accounts::get)
                .filter(Objects::nonNull)
                .map(account -> toPublicCard(account, viewerUserId))
                .sorted(Comparator.comparing(PublicUserCard::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int safeSize = Math.min(50, Math.max(1, size));
        return slice(items, page, safeSize);
    }

    public PublicUserCard getPublicProfileByUsername(String username) {
        return accounts.values().stream()
                .filter(account -> account.username().equalsIgnoreCase(username))
                .findFirst()
                .map(this::toPublicCard)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    public synchronized PublicUserCard toggleFollow(String viewerUserId, String targetUserId, boolean followed) {
        if (viewerUserId == null || viewerUserId.isBlank()) {
            throw new NotFoundException("User not found");
        }
        requireAccount(viewerUserId);
        AccountRecord target = resolveAccountByKey(targetUserId);
        String resolvedTargetUserId = target.userId();
        if (viewerUserId.equals(resolvedTargetUserId)) {
            return toPublicCard(target, viewerUserId);
        }

        boolean wasFriend = isFriend(viewerUserId, resolvedTargetUserId);
        boolean wasFollowedByThem = isFollowing(resolvedTargetUserId, viewerUserId);
        boolean changed = followed ? addFollowRelation(viewerUserId, resolvedTargetUserId) : removeFollowRelation(viewerUserId, resolvedTargetUserId);
        if (!changed) {
            return toPublicCard(target, viewerUserId);
        }

        boolean isFriendNow = isFriend(viewerUserId, resolvedTargetUserId);
        if (followed) {
            if (isFriendNow && !wasFriend) {
                socialService.publishRelationNotification(resolvedTargetUserId, viewerUserId, "FRIEND", "New friend", requireAccount(viewerUserId).displayName() + " is now your friend", "profile/" + viewerUserId);
                socialService.publishRelationNotification(viewerUserId, resolvedTargetUserId, "FRIEND", "New friend", target.displayName() + " is now your friend", "profile/" + resolvedTargetUserId);
            } else if (!wasFollowedByThem) {
                socialService.publishRelationNotification(resolvedTargetUserId, viewerUserId, "FOLLOW", "New follower", requireAccount(viewerUserId).displayName() + " followed you", "profile/" + viewerUserId);
            }
        }
        return toPublicCard(target, viewerUserId);
    }

    public PageResponse<PublicUserCard> searchUsers(String query, String gender, String interest, int page, int size) {
        return searchUsers(null, query, gender, interest, page, size);
    }

    public PageResponse<PublicUserCard> searchUsers(String viewerUserId, String query, String gender, String interest, int page, int size) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalizedGender = gender == null ? "" : gender.trim().toLowerCase(Locale.ROOT);
        String normalizedInterest = interest == null ? "" : interest.trim().toLowerCase(Locale.ROOT);
        List<PublicUserCard> items = accounts.values().stream()
                .map(account -> toPublicCard(account, viewerUserId))
                .filter(card -> normalized.isBlank()
                        || card.displayName().toLowerCase(Locale.ROOT).contains(normalized)
                        || (card.publicId() != null && card.publicId().toLowerCase(Locale.ROOT).contains(normalized))
                        || card.username().toLowerCase(Locale.ROOT).contains(normalized)
                        || card.userId().toLowerCase(Locale.ROOT).contains(normalized)
                        || card.gender().toLowerCase(Locale.ROOT).contains(normalized)
                        || card.interests().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalized))
                        || card.city().toLowerCase(Locale.ROOT).contains(normalized))
                .filter(card -> normalizedGender.isBlank()
                        || "all".equals(normalizedGender)
                        || "both".equals(normalizedGender)
                        || card.gender().toLowerCase(Locale.ROOT).contains(normalizedGender))
                .filter(card -> normalizedInterest.isBlank()
                        || card.interests().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(normalizedInterest)))
                .sorted(Comparator.comparing(PublicUserCard::displayName))
                .toList();

        return slice(items, page, size);
    }

    public List<BadgeResponse> badges(String userId) {
        return new ArrayList<>(requireAccount(userId).badges());
    }

    public List<WalletEntryResponse> wallet(String userId) {
        return new ArrayList<>(requireAccount(userId).wallet());
    }

    public List<PremiumPlanResponse> plans() {
        return premiumPlans;
    }

    public List<EntitlementResponse> entitlements(String userId) {
        return new ArrayList<>(requireAccount(userId).entitlements());
    }

    public String vipTierId(String userId) {
        return requireAccount(userId).vipTierId();
    }

    public String vipTierName(String userId) {
        return requireAccount(userId).vipTierName();
    }

    public Instant vipExpiresAt(String userId) {
        return requireAccount(userId).vipExpiresAt();
    }

    public long diamondBalance(String userId) {
        return requireAccount(userId).diamondBalance();
    }

    public synchronized void activateVip(String userId, String tierId, String tierName, Instant expiresAt, String ledgerSubtitle, String amountLabel) {
        AccountRecord existing = requireAccount(userId);
        WalletEntryResponse ledgerEntry = new WalletEntryResponse(
                "wallet-" + userId + "-" + userSequence.getAndIncrement(),
                "VIP " + tierName,
                ledgerSubtitle,
                amountLabel,
                timeLabel(Instant.now()),
                false
        );
        AccountRecord updated = existing.activateVip(tierId, tierName, expiresAt, ledgerEntry);
        accounts.put(userId, updated);
        persistAccount(updated);
    }

    public synchronized void addDiamonds(String userId, long amount, String ledgerTitle, String ledgerSubtitle, String amountLabel) {
        AccountRecord existing = requireAccount(userId);
        WalletEntryResponse ledgerEntry = new WalletEntryResponse(
                "wallet-" + userId + "-" + userSequence.getAndIncrement(),
                ledgerTitle,
                ledgerSubtitle,
                amountLabel,
                timeLabel(Instant.now()),
                true
        );
        AccountRecord updated = existing.addDiamonds(amount, ledgerEntry);
        accounts.put(userId, updated);
        persistAccount(updated);
    }

    public boolean onboardingComplete(String userId) {
        return requireAccount(userId).onboardingComplete();
    }

    public boolean profileComplete(String userId) {
        return requireAccount(userId).profileComplete();
    }

    public boolean isPremium(String userId) {
        AccountRecord record = requireAccount(userId);
        return record.premium() && (record.vipExpiresAt() == null || record.vipExpiresAt().isAfter(Instant.now()));
    }

    public List<String> quickLinks() {
        return List.of("Discover", "Messages", "Calls", "Community", "Premium", "Settings");
    }

    private String reservedDevUserId(String providerKey) {
        return switch (providerKey) {
            case "GOOGLE:dev:current", "FACEBOOK:dev:current" -> "u-current";
            default -> null;
        };
    }

    private void removeLegacyDemoAccountsFromMemory() {
        for (String userId : LEGACY_DEMO_USER_IDS) {
            AccountRecord removed = accounts.remove(userId);
            if (removed != null && removed.publicId() != null) {
                userIdByPublicId.remove(removed.publicId());
            }
        }
        publicIdByUserId.keySet().removeIf(LEGACY_DEMO_USER_IDS::contains);
        userIdByPublicId.entrySet().removeIf(entry -> LEGACY_DEMO_USER_IDS.contains(entry.getValue()));
        providerLinks.entrySet().removeIf(entry ->
                LEGACY_DEMO_PROVIDER_KEYS.contains(entry.getKey()) || LEGACY_DEMO_USER_IDS.contains(entry.getValue())
        );
        linkedProviderKeysByUserId.keySet().removeIf(LEGACY_DEMO_USER_IDS::contains);
        linkedProviderKeysByUserId.values().forEach(keys -> keys.removeIf(LEGACY_DEMO_PROVIDER_KEYS::contains));
        followingByUserId.keySet().removeIf(LEGACY_DEMO_USER_IDS::contains);
        followersByUserId.keySet().removeIf(LEGACY_DEMO_USER_IDS::contains);
        followingByUserId.values().forEach(userIds -> userIds.removeAll(LEGACY_DEMO_USER_IDS));
        followersByUserId.values().forEach(userIds -> userIds.removeAll(LEGACY_DEMO_USER_IDS));
    }

    private void loadPersistedAccounts() {
        try {
            List<PersistedAccount> persisted = jdbcTemplate.query("SELECT * FROM accounts", (rs, rowNum) ->
                    new PersistedAccount(
                            rowToAccount(rs),
                            readList(rs.getString("linked_provider_keys_json"), STRING_LIST)
                    )
            );
            for (PersistedAccount persistedAccount : persisted) {
                AccountRecord record = persistedAccount.record();
                accounts.put(record.userId(), record);
                registerPublicId(record);
                Set<String> linkedKeys = new LinkedHashSet<>(persistedAccount.linkedProviderKeys());
                if (linkedKeys.isEmpty()) {
                    linkedKeys.add(record.providerKey());
                }
                linkedProviderKeysByUserId.put(record.userId(), linkedKeys);
                linkedKeys.forEach(key -> providerLinks.put(key, record.userId()));
            }
        } catch (Exception ex) {
            accounts.clear();
            providerLinks.clear();
            linkedProviderKeysByUserId.clear();
            followingByUserId.clear();
            followersByUserId.clear();
        }
    }

    private void loadFollowRelations() {
        try {
            List<FollowRelation> relations = jdbcTemplate.query(
                    "SELECT follower_user_id, followee_user_id FROM profile_follows",
                    (rs, rowNum) -> new FollowRelation(
                            rs.getString("follower_user_id"),
                            rs.getString("followee_user_id")
                    )
            );
            for (FollowRelation relation : relations) {
                if (relation.followerUserId() == null || relation.followeeUserId() == null) {
                    continue;
                }
                followingByUserId.computeIfAbsent(relation.followerUserId(), ignored -> ConcurrentHashMap.newKeySet())
                        .add(relation.followeeUserId());
                followersByUserId.computeIfAbsent(relation.followeeUserId(), ignored -> ConcurrentHashMap.newKeySet())
                        .add(relation.followerUserId());
            }
        } catch (Exception ex) {
            followingByUserId.clear();
            followersByUserId.clear();
        }
    }

    private void refreshUserSequence() {
        int maxSequence = accounts.keySet().stream()
                .map(this::extractNumericUserId)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(99);
        userSequence.set(Math.max(100, maxSequence + 1));
    }

    private void refreshPublicIdSequence() {
        long maxSequence = accounts.values().stream()
                .map(AccountRecord::publicId)
                .map(this::extractNumericPublicId)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(PUBLIC_ID_MIN - 1);
        long next = maxSequence >= PUBLIC_ID_MAX ? PUBLIC_ID_MIN : Math.max(PUBLIC_ID_MIN, maxSequence + 1);
        publicIdSequence.set(next);
    }

    private Integer extractNumericUserId(String userId) {
        if (userId == null || !userId.startsWith("u-")) {
            return null;
        }
        try {
            return Integer.parseInt(userId.substring(2));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long extractNumericPublicId(String publicId) {
        if (!isValidPublicId(publicId)) {
            return null;
        }
        try {
            return Long.parseLong(publicId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String generatePublicId() {
        long attempts = PUBLIC_ID_MAX - PUBLIC_ID_MIN + 1;
        for (long index = 0; index < attempts; index++) {
            long candidateValue = publicIdSequence.getAndUpdate(value ->
                    value >= PUBLIC_ID_MAX ? PUBLIC_ID_MIN : value + 1
            );
            if (candidateValue < PUBLIC_ID_MIN || candidateValue > PUBLIC_ID_MAX) {
                candidateValue = PUBLIC_ID_MIN;
            }
            String candidate = Long.toString(candidateValue);
            if (!userIdByPublicId.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No public IDs available");
    }

    private void registerPublicId(AccountRecord record) {
        String publicId = record.publicId();
        if (!isValidPublicId(publicId)) {
            return;
        }
        publicIdByUserId.put(record.userId(), publicId);
        userIdByPublicId.put(publicId, record.userId());
    }

    private AccountRecord ensurePublicId(AccountRecord record) {
        String currentPublicId = record.publicId();
        if (isValidPublicId(currentPublicId)) {
            String existingUserId = userIdByPublicId.get(currentPublicId);
            if (existingUserId == null || existingUserId.equals(record.userId())) {
                registerPublicId(record);
                return record;
            }
        }
        String uniquePublicId = generatePublicId();
        AccountRecord updated = record.withPublicId(uniquePublicId);
        registerPublicId(updated);
        return updated;
    }

    private void ensurePublicIds() {
        for (AccountRecord record : new ArrayList<>(accounts.values())) {
            AccountRecord ensured = ensurePublicId(record);
            if (!Objects.equals(ensured.publicId(), record.publicId())) {
                accounts.put(ensured.userId(), ensured);
                persistAccount(ensured);
            }
        }
    }

    private AccountRecord resolveAccountByKey(String key) {
        if (key == null || key.isBlank()) {
            throw new NotFoundException("User not found");
        }
        AccountRecord direct = accounts.get(key);
        if (direct != null) {
            return direct;
        }
        String userId = userIdByPublicId.get(key.trim());
        if (userId != null) {
            return requireAccount(userId);
        }
        throw new NotFoundException("User not found");
    }

    private boolean isValidPublicId(String publicId) {
        if (publicId == null) {
            return false;
        }
        String normalized = publicId.trim();
        if (normalized.length() != 9 || !normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            long value = Long.parseLong(normalized);
            return value >= PUBLIC_ID_MIN && value <= PUBLIC_ID_MAX;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void persistAccount(AccountRecord account) {
        String updateSql = """
                UPDATE accounts SET
                    public_id = ?,
                    provider_key = ?,
                    display_name = ?,
                    username = ?,
                    bio = ?,
                    avatar_url = ?,
                    featured_photos_json = ?,
                    interests_json = ?,
                    linked_provider_keys_json = ?,
                    age = ?,
                    city = ?,
                    gender = ?,
                    verified = ?,
                    online = ?,
                    premium = ?,
                    vip_tier_id = ?,
                    vip_tier_name = ?,
                    vip_expires_at = ?,
                    diamond_balance = ?,
                    onboarding_complete = ?,
                    profile_complete = ?,
                    distance_km = ?,
                    settings_json = ?,
                    stats_json = ?,
                    badges_json = ?,
                    wallet_json = ?,
                    entitlements_json = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ?
                """;
        int updated = jdbcTemplate.update(
                updateSql,
                account.publicId(),
                account.providerKey(),
                account.displayName(),
                account.username(),
                account.bio(),
                account.avatarUrl(),
                jsonList(account.featuredPhotos()),
                jsonList(account.interests()),
                jsonList(new ArrayList<>(linkedProviderKeysByUserId.getOrDefault(account.userId(), Set.of(account.providerKey())))),
                account.age(),
                account.city(),
                account.gender(),
                account.verified(),
                account.online(),
                account.premium(),
                account.vipTierId(),
                account.vipTierName(),
                account.vipExpiresAt() == null ? null : Timestamp.from(account.vipExpiresAt()),
                account.diamondBalance(),
                account.onboardingComplete(),
                account.profileComplete(),
                account.distanceKm(),
                jsonValue(account.settings()),
                jsonValue(account.stats()),
                jsonValue(account.badges()),
                jsonValue(account.wallet()),
                jsonValue(account.entitlements()),
                account.userId()
        );
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                            INSERT INTO accounts (
                                user_id, public_id, provider_key, display_name, username, bio, avatar_url,
                                featured_photos_json, interests_json, linked_provider_keys_json, age, city, gender, verified, online, premium,
                                vip_tier_id, vip_tier_name, vip_expires_at, diamond_balance,
                                onboarding_complete, profile_complete, distance_km,
                                settings_json, stats_json, badges_json, wallet_json, entitlements_json,
                                created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """,
                    account.userId(),
                    account.publicId(),
                    account.providerKey(),
                    account.displayName(),
                    account.username(),
                    account.bio(),
                    account.avatarUrl(),
                    jsonList(account.featuredPhotos()),
                    jsonList(account.interests()),
                    jsonList(new ArrayList<>(linkedProviderKeysByUserId.getOrDefault(account.userId(), Set.of(account.providerKey())))),
                    account.age(),
                    account.city(),
                    account.gender(),
                    account.verified(),
                    account.online(),
                    account.premium(),
                    account.vipTierId(),
                    account.vipTierName(),
                    account.vipExpiresAt() == null ? null : Timestamp.from(account.vipExpiresAt()),
                    account.diamondBalance(),
                    account.onboardingComplete(),
                    account.profileComplete(),
                    account.distanceKm(),
                    jsonValue(account.settings()),
                    jsonValue(account.stats()),
                    jsonValue(account.badges()),
                    jsonValue(account.wallet()),
                    jsonValue(account.entitlements())
            );
        }
    }

    private boolean addFollowRelation(String followerUserId, String followeeUserId) {
        if (followerUserId.equals(followeeUserId)) {
            return false;
        }
        boolean inserted = followingByUserId.computeIfAbsent(followerUserId, ignored -> ConcurrentHashMap.newKeySet())
                .add(followeeUserId);
        if (!inserted) {
            return false;
        }
        followersByUserId.computeIfAbsent(followeeUserId, ignored -> ConcurrentHashMap.newKeySet())
                .add(followerUserId);
        jdbcTemplate.update(
                "INSERT INTO profile_follows (follower_user_id, followee_user_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                followerUserId,
                followeeUserId
        );
        return true;
    }

    private boolean removeFollowRelation(String followerUserId, String followeeUserId) {
        Set<String> following = followingByUserId.get(followerUserId);
        boolean removed = following != null && following.remove(followeeUserId);
        if (!removed) {
            return false;
        }
        Set<String> followers = followersByUserId.get(followeeUserId);
        if (followers != null) {
            followers.remove(followerUserId);
        }
        jdbcTemplate.update(
                "DELETE FROM profile_follows WHERE follower_user_id = ? AND followee_user_id = ?",
                followerUserId,
                followeeUserId
        );
        return true;
    }

    private boolean isFollowing(String followerUserId, String followeeUserId) {
        return followingByUserId.getOrDefault(followerUserId, Set.of()).contains(followeeUserId);
    }

    private int followersCount(String userId) {
        return followersByUserId.getOrDefault(userId, Set.of()).size();
    }

    private int followingCount(String userId) {
        return followingByUserId.getOrDefault(userId, Set.of()).size();
    }

    public boolean isFriend(String userId, String otherUserId) {
        if (userId == null || otherUserId == null || userId.isBlank() || otherUserId.isBlank()) {
            return false;
        }
        return isFollowing(userId, otherUserId) && isFollowing(otherUserId, userId);
    }

    private int friendsCount(String userId) {
        return friendIds(userId).size();
    }

    private Set<String> friendIds(String userId) {
        Set<String> following = new LinkedHashSet<>(followingByUserId.getOrDefault(userId, Set.of()));
        following.retainAll(followersByUserId.getOrDefault(userId, Set.of()));
        return following;
    }

    private List<String> relationUserIds(String viewerUserId, String userId, String relationType) {
        Set<String> ids = switch (relationType) {
            case "followers" -> new LinkedHashSet<>(followersByUserId.getOrDefault(userId, Set.of()));
            case "following" -> new LinkedHashSet<>(followingByUserId.getOrDefault(userId, Set.of()));
            case "friends" -> new LinkedHashSet<>(friendIds(userId));
            case "mutual" -> {
                if (viewerUserId == null || viewerUserId.isBlank()) {
                    yield Set.<String>of();
                }
                Set<String> viewerFriends = new LinkedHashSet<>(friendIds(viewerUserId));
                viewerFriends.retainAll(friendIds(userId));
                viewerFriends.remove(viewerUserId);
                viewerFriends.remove(userId);
                yield viewerFriends;
            }
            default -> throw new BadRequestException("Unsupported relation type");
        };
        return ids.stream()
                .filter(id -> !Objects.equals(id, userId) || !"mutual".equals(relationType))
                .toList();
    }

    private AccountRecord rowToAccount(ResultSet rs) throws SQLException {
        Object distanceValue = rs.getObject("distance_km");
        Integer distanceKm = distanceValue == null ? null : ((Number) distanceValue).intValue();
        Timestamp vipExpiresAt = rs.getTimestamp("vip_expires_at");
        return new AccountRecord(
                rs.getString("user_id"),
                rs.getString("public_id"),
                rs.getString("display_name"),
                rs.getString("username"),
                rs.getString("bio"),
                rs.getString("avatar_url"),
                readList(rs.getString("featured_photos_json"), STRING_LIST),
                readList(rs.getString("interests_json"), STRING_LIST),
                // linked provider keys are loaded separately from the same row
                rs.getInt("age"),
                rs.getString("city"),
                valueOrDefault(rs.getString("gender"), "Not specified"),
                rs.getBoolean("verified"),
                rs.getBoolean("online"),
                rs.getBoolean("premium"),
                rs.getString("vip_tier_id"),
                rs.getString("vip_tier_name"),
                vipExpiresAt == null ? null : vipExpiresAt.toInstant(),
                rs.getLong("diamond_balance"),
                rs.getBoolean("onboarding_complete"),
                rs.getBoolean("profile_complete"),
                rs.getString("provider_key"),
                distanceKm,
                readValue(rs.getString("settings_json"), AppSettingsResponse.class),
                readValue(rs.getString("stats_json"), ProfileStatsResponse.class),
                readList(rs.getString("badges_json"), BADGE_LIST),
                readList(rs.getString("wallet_json"), WALLET_LIST),
                readList(rs.getString("entitlements_json"), ENTITLEMENT_LIST)
        );
    }

    private void linkProvider(String userId, String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return;
        }
        linkedProviderKeysByUserId.computeIfAbsent(userId, ignored -> new LinkedHashSet<>()).add(providerKey);
        providerLinks.put(providerKey, userId);
    }

    private String jsonList(List<String> value) {
        return jsonValue(value == null ? List.of() : value);
    }

    private String jsonValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize account state", ex);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read account state", ex);
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private <T> T readList(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            try {
                return objectMapper.readValue("[]", type);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to read account list", ex);
            }
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read account list", ex);
        }
    }

    private AccountRecord requireAccount(String userId) {
        AccountRecord record = accounts.get(userId);
        if (record == null) {
            throw new NotFoundException("User not found");
        }
        return record;
    }

    private MeResponse toMe(AccountRecord account) {
        boolean activePremium = account.premium() && (account.vipExpiresAt() == null || account.vipExpiresAt().isAfter(Instant.now()));
        return new MeResponse(
                account.userId(),
                account.publicId(),
                account.displayName(),
                account.username(),
                account.bio(),
                account.avatarUrl(),
                account.featuredPhotos(),
                account.interests(),
                account.age(),
                account.city(),
                account.gender(),
                account.verified(),
                account.online(),
                activePremium,
                activePremium ? account.vipTierId() : null,
                activePremium ? account.vipTierName() : null,
                followersCount(account.userId()),
                followingCount(account.userId()),
                friendsCount(account.userId()),
                account.onboardingComplete(),
                account.profileComplete(),
                account.settings(),
                profileStatsFor(account),
                account.badges(),
                account.wallet(),
                premiumPlans,
                account.entitlements()
        );
    }

    private PublicUserCard toPublicCard(AccountRecord account) {
        return toPublicCard(account, null);
    }

    private PublicUserCard toPublicCard(AccountRecord account, String viewerUserId) {
        boolean activePremium = account.premium() && (account.vipExpiresAt() == null || account.vipExpiresAt().isAfter(Instant.now()));
        return new PublicUserCard(
                account.userId(),
                account.publicId(),
                account.displayName(),
                account.username(),
                account.bio(),
                account.age(),
                account.avatarUrl(),
                account.featuredPhotos(),
                activePremium ? account.vipTierId() : null,
                activePremium ? account.vipTierName() : null,
                account.verified(),
                activePremium,
                account.distanceKm(),
                account.online(),
                account.city(),
                account.gender(),
                account.interests(),
                followersCount(account.userId()),
                followingCount(account.userId()),
                friendsCount(account.userId()),
                viewerUserId != null && isFollowing(account.userId(), viewerUserId),
                viewerUserId != null && isFriend(viewerUserId, account.userId()),
                viewerUserId != null && isFollowing(viewerUserId, account.userId())
        );
    }

    private ProfileStatsResponse profileStatsFor(AccountRecord account) {
        ProfileStatsResponse stats = account.stats();
        return new ProfileStatsResponse(
                compactCount(followersCount(account.userId())),
                compactCount(followingCount(account.userId())),
                stats.matches(),
                stats.calls(),
                stats.messages()
        );
    }

    private <T> PageResponse<T> slice(List<T> items, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);
        int from = Math.min(safePage * safeSize, items.size());
        int to = Math.min(from + safeSize, items.size());
        return new PageResponse<>(items.subList(from, to), safePage, safeSize, items.size());
    }

    private String compactCount(int value) {
        if (value >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000f);
        }
        if (value >= 1_000) {
            return String.format(Locale.US, "%.1fK", value / 1_000f);
        }
        return Integer.toString(value);
    }

    private static String providerKey(String provider, String providerUserId) {
        return provider + ":" + providerUserId;
    }

    private record PersistedAccount(AccountRecord record, List<String> linkedProviderKeys) {
    }

    private record FollowRelation(String followerUserId, String followeeUserId) {
    }

    private String timeLabel(Instant instant) {
        return DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    private record AccountRecord(
            String userId,
            String publicId,
            String displayName,
            String username,
            String bio,
            String avatarUrl,
            List<String> featuredPhotos,
            List<String> interests,
            int age,
            String city,
            String gender,
            boolean verified,
            boolean online,
            boolean premium,
            String vipTierId,
            String vipTierName,
            Instant vipExpiresAt,
            long diamondBalance,
            boolean onboardingComplete,
            boolean profileComplete,
            String providerKey,
            Integer distanceKm,
            AppSettingsResponse settings,
            ProfileStatsResponse stats,
            List<BadgeResponse> badges,
            List<WalletEntryResponse> wallet,
            List<EntitlementResponse> entitlements
    ) {
        static AccountRecord newFromIdentity(String userId, String publicId, SocialIdentity identity) {
            String username = slug(identity.displayName());
            String avatarUrl = identity.avatarUrl() == null ? "" : identity.avatarUrl().trim();
            return new AccountRecord(
                    userId,
                    publicId,
                    identity.displayName(),
                    username,
                    DEFAULT_NEW_USER_BIO,
                    avatarUrl,
                    List.of(),
                    List.of(),
                    0,
                    "",
                    "Not specified",
                    false,
                    true,
                    false,
                    null,
                    null,
                    null,
                    0L,
                    true,
                    false,
                    AccountService.providerKey(identity.provider().name(), identity.providerUserId()),
                    0,
                    new AppSettingsResponse(
                            true,
                            "English",
                            true,
                            true,
                            true,
                            false,
                            false,
                            false,
                            true,
                            true,
                            false,
                            true
                    ),
                    emptyStats(),
                    List.of(),
                    List.of(),
                    buildEntitlements(false, null, null, null)
            );
        }

        AccountRecord withIdentity(SocialIdentity identity) {
            String identityAvatar = identity.avatarUrl() == null ? "" : identity.avatarUrl().trim();
            String resolvedAvatar = identityAvatar.isBlank() ? avatarUrl : identityAvatar;
            return new AccountRecord(
                    userId,
                    publicId,
                    identity.displayName(),
                    username,
                    bio,
                    resolvedAvatar,
                    featuredPhotos,
                    interests,
                    age,
                    city,
                    gender,
                    verified,
                    online,
                    premium,
                    vipTierId,
                    vipTierName,
                    vipExpiresAt,
                    diamondBalance,
                    onboardingComplete,
                    profileComplete,
                    AccountService.providerKey(identity.provider().name(), identity.providerUserId()),
                    distanceKm,
                    settings,
                    stats,
                    badges,
                    wallet,
                    entitlements
            );
        }

        AccountRecord updateProfile(UpdateProfileRequest request) {
            String newDisplayName = request.displayName().trim();
            String newBio = request.bio() == null ? bio : request.bio();
            String requestedAvatar = request.photoUrl();
            String newAvatar = requestedAvatar == null || requestedAvatar.isBlank()
                    ? avatarUrl
                    : requestedAvatar.trim();
              List<String> newFeaturedPhotos = request.featuredPhotos() == null
                      ? featuredPhotos
                      : cleanList(request.featuredPhotos(), 5);
            List<String> newInterests = request.interests() == null
                    ? interests
                    : cleanList(request.interests(), 12);
            String newGender = normalizeGender(request.gender(), gender);
            boolean newProfileComplete = !newDisplayName.isBlank() && !newAvatar.isBlank();
            boolean newOnboardingComplete = onboardingComplete || newProfileComplete;
            return new AccountRecord(
                    userId,
                    publicId,
                    newDisplayName,
                    slug(newDisplayName),
                    newBio,
                    newAvatar,
                    newFeaturedPhotos,
                    newInterests,
                    request.age() == null ? age : request.age(),
                    request.city() == null ? city : request.city(),
                    newGender,
                    verified,
                    online,
                    premium,
                    vipTierId,
                    vipTierName,
                    vipExpiresAt,
                    diamondBalance,
                    newOnboardingComplete,
                    newProfileComplete,
                    providerKey,
                    distanceKm,
                    settings,
                    stats,
                    badges,
                    wallet,
                    entitlements
            );
        }

        private static String normalizeGender(String requestedGender, String fallback) {
            if (requestedGender == null || requestedGender.isBlank()) {
                return fallback;
            }
            String normalized = requestedGender.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "male", "nam" -> "Male";
                case "female", "nu", "nữ" -> "Female";
                default -> fallback;
            };
        }

        AccountRecord updateSettings(UpdateSettingsRequest request) {
            AppSettingsResponse updatedSettings = new AppSettingsResponse(
                    request.darkMode() != null ? request.darkMode() : settings.darkMode(),
                    settings.language(),
                    request.notificationsEnabled() != null ? request.notificationsEnabled() : settings.notificationsEnabled(),
                    request.soundEnabled() != null ? request.soundEnabled() : settings.soundEnabled(),
                    request.autoTranslateEnabled() != null ? request.autoTranslateEnabled() : settings.autoTranslateEnabled(),
                    request.incognitoEnabled() != null ? request.incognitoEnabled() : settings.incognitoEnabled(),
                    request.travelModeEnabled() != null ? request.travelModeEnabled() : settings.travelModeEnabled(),
                    request.premiumEnabled() != null ? request.premiumEnabled() : settings.premiumEnabled(),
                    request.locationSharingEnabled() != null ? request.locationSharingEnabled() : settings.locationSharingEnabled(),
                    settings.photoVerificationEnabled(),
                    settings.videoVerificationEnabled(),
                    settings.identityVerificationEnabled()
            );
            return new AccountRecord(
                    userId,
                    publicId,
                    displayName,
                    username,
                    bio,
                    avatarUrl,
                    featuredPhotos,
                    interests,
                    age,
                    city,
                    gender,
                    verified,
                    online,
                    premium,
                    vipTierId,
                    vipTierName,
                    vipExpiresAt,
                    diamondBalance,
                    onboardingComplete,
                    profileComplete,
                    providerKey,
                    distanceKm,
                    updatedSettings,
                    stats,
                    badges,
                    wallet,
                    entitlements
            );
        }

        AccountRecord activateVip(String tierId, String tierName, Instant expiresAt, WalletEntryResponse ledgerEntry) {
            List<WalletEntryResponse> updatedWallet = prependWalletEntry(ledgerEntry);
            AppSettingsResponse updatedSettings = new AppSettingsResponse(
                    settings.darkMode(),
                    settings.language(),
                    settings.notificationsEnabled(),
                    settings.soundEnabled(),
                    settings.autoTranslateEnabled(),
                    settings.incognitoEnabled(),
                    settings.travelModeEnabled(),
                    true,
                    settings.locationSharingEnabled(),
                    settings.photoVerificationEnabled(),
                    settings.videoVerificationEnabled(),
                    settings.identityVerificationEnabled()
            );
            return new AccountRecord(
                    userId,
                    publicId,
                    displayName,
                    username,
                    bio,
                    avatarUrl,
                    featuredPhotos,
                    interests,
                    age,
                    city,
                    gender,
                    verified,
                    online,
                    true,
                    tierId,
                    tierName,
                    expiresAt,
                    diamondBalance,
                    onboardingComplete,
                    profileComplete,
                    providerKey,
                    distanceKm,
                    updatedSettings,
                    stats,
                    badges,
                    updatedWallet,
                    buildEntitlements(true, tierId, tierName, expiresAt)
            );
        }

        AccountRecord addDiamonds(long amount, WalletEntryResponse ledgerEntry) {
            List<WalletEntryResponse> updatedWallet = prependWalletEntry(ledgerEntry);
            return new AccountRecord(
                    userId,
                    publicId,
                    displayName,
                    username,
                    bio,
                    avatarUrl,
                    featuredPhotos,
                    interests,
                    age,
                    city,
                    gender,
                    verified,
                    online,
                    premium,
                    vipTierId,
                    vipTierName,
                    vipExpiresAt,
                    diamondBalance + amount,
                    onboardingComplete,
                    profileComplete,
                    providerKey,
                    distanceKm,
                    settings,
                    stats,
                    badges,
                    updatedWallet,
                    entitlements
            );
        }

        AccountRecord withPublicId(String newPublicId) {
            return new AccountRecord(
                    userId,
                    newPublicId,
                    displayName,
                    username,
                    bio,
                    avatarUrl,
                    featuredPhotos,
                    interests,
                    age,
                    city,
                    gender,
                    verified,
                    online,
                    premium,
                    vipTierId,
                    vipTierName,
                    vipExpiresAt,
                    diamondBalance,
                    onboardingComplete,
                    profileComplete,
                    providerKey,
                    distanceKm,
                    settings,
                    stats,
                    badges,
                    wallet,
                    entitlements
            );
        }

        AccountRecord withOnline(boolean newOnline) {
            return new AccountRecord(
                    userId,
                    publicId,
                    displayName,
                    username,
                    bio,
                    avatarUrl,
                    featuredPhotos,
                    interests,
                    age,
                    city,
                    gender,
                    verified,
                    newOnline,
                    premium,
                    vipTierId,
                    vipTierName,
                    vipExpiresAt,
                    diamondBalance,
                    onboardingComplete,
                    profileComplete,
                    providerKey,
                    distanceKm,
                    settings,
                    stats,
                    badges,
                    wallet,
                    entitlements
            );
        }

        private List<WalletEntryResponse> prependWalletEntry(WalletEntryResponse entry) {
            List<WalletEntryResponse> updated = new ArrayList<>(wallet);
            updated.add(0, entry);
            return updated;
        }

        private static ProfileStatsResponse emptyStats() {
            return new ProfileStatsResponse("0", "0", "0", "0", "0");
        }

        private static List<EntitlementResponse> buildEntitlements(boolean activePremium, String tierId, String tierName, Instant expiresAt) {
            List<EntitlementResponse> items = new ArrayList<>();
            items.add(new EntitlementResponse("free_swipes", "Daily swipes", true, "20/day", "Reset every morning"));
            items.add(new EntitlementResponse("boosts", "Boosts", activePremium, activePremium ? "Unlimited" : "0", activePremium ? "Always on" : "Upgrade required"));
            items.add(new EntitlementResponse("rewind", "Rewind", activePremium, activePremium ? "On" : "Off", "Undo the last swipe"));
            items.add(new EntitlementResponse("priority_chat", "Priority chat", activePremium, activePremium ? "Enabled" : "Locked", "Premium chat ranking"));
            if (activePremium) {
                String label = tierName == null ? "VIP active" : tierName;
                String limit = tierId == null ? "Active" : tierId;
                String detail = expiresAt == null ? "No expiry" : "Expires " + expiresAt;
                items.add(new EntitlementResponse("vip_badge", label, true, limit, detail));
            }
            return List.copyOf(items);
        }

        private static String slug(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            return slug.isBlank() ? "user" : slug;
        }
    }

    private static List<String> cleanList(List<String> values, int maxSize) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(maxSize)
                .toList();
        return cleaned.isEmpty() ? List.of() : cleaned;
    }

}
