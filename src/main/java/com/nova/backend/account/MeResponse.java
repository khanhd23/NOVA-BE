package com.nova.backend.account;

import java.util.List;

public record MeResponse(
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
        boolean verified,
        boolean online,
        boolean premium,
        String vipTierId,
        String vipTierName,
        int followersCount,
        int followingCount,
        int friendsCount,
        boolean onboardingComplete,
        boolean profileComplete,
        AppSettingsResponse settings,
        ProfileStatsResponse stats,
        List<BadgeResponse> badges,
        List<WalletEntryResponse> wallet,
        List<PremiumPlanResponse> plans,
        List<EntitlementResponse> entitlements
) {
}
