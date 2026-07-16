package com.nova.backend.account;

public record PublicUserCard(
        String userId,
        String displayName,
        String username,
        String bio,
        int age,
        String avatarUrl,
        java.util.List<String> featuredPhotos,
        String vipTierId,
        String vipTierName,
        boolean verified,
        boolean premium,
        Integer distanceKm,
        boolean online,
        String city,
        String gender,
        java.util.List<String> interests,
        int followersCount,
        int followingCount,
        int friendsCount,
        boolean followedByThem,
        boolean friend,
        boolean followedByMe
) {
}
