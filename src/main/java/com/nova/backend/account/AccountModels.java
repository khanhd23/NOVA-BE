package com.nova.backend.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

record UpdateProfileRequest(
        @NotBlank @Size(max = 60) String displayName,
        @Size(max = 160) String bio,
        @Size(max = 60) String city,
        Integer age,
        String photoUrl,
        @Size(max = 3) List<String> featuredPhotos,
        @Size(max = 12) List<String> interests
) {
}

record UpdateSettingsRequest(
        Boolean darkMode,
        Boolean notificationsEnabled,
        Boolean soundEnabled,
        Boolean autoTranslateEnabled,
        Boolean incognitoEnabled,
        Boolean travelModeEnabled,
        Boolean premiumEnabled,
        Boolean locationSharingEnabled
) {
}

record FollowRequest(
        boolean followed
) {
}

record RelationRequest(
        String type
) {
}

record AppSettingsResponse(
        boolean darkMode,
        String language,
        boolean notificationsEnabled,
        boolean soundEnabled,
        boolean autoTranslateEnabled,
        boolean incognitoEnabled,
        boolean travelModeEnabled,
        boolean premiumEnabled,
        boolean locationSharingEnabled,
        boolean photoVerificationEnabled,
        boolean videoVerificationEnabled,
        boolean identityVerificationEnabled
) {
}

record ProfileStatsResponse(
        String followers,
        String following,
        String matches,
        String calls,
        String messages
) {
}

record BadgeResponse(
        String id,
        String title,
        String subtitle,
        int progress,
        String iconLabel,
        boolean unlocked
) {
}

record WalletEntryResponse(
        String id,
        String title,
        String subtitle,
        String amount,
        String timeLabel,
        boolean positive
) {
}

record PremiumPlanResponse(
        String id,
        String name,
        String price,
        String cycle,
        String subtitle,
        List<String> features,
        boolean highlighted
) {
}

record EntitlementResponse(
        String key,
        String label,
        boolean enabled,
        String limitValue,
        String detail
) {
}

record BootstrapResponse(
        MeResponse me,
        List<String> quickLinks,
        boolean onboardingRequired,
        boolean profileRequired,
        int unreadCount
) {
}
