package com.nova.backend.content;

import com.nova.backend.account.AccountService;
import com.nova.backend.social.SocialService;
import com.nova.backend.common.exception.NotFoundException;
import com.nova.backend.common.ModuleStateStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class ContentService {

    private static final long MAX_VIDEO_BYTES = 50L * 1024L * 1024L;
    private static final Set<String> LEGACY_DEMO_USER_IDS = Set.of(
            "u-current",
            "u-seraphina",
            "u-elena",
            "u-marcus",
            "u-chloe",
            "u-alex",
            "u-mina"
    );

    private final AccountService accountService;
    private final SocialService socialService;
    private final ModuleStateStore moduleStateStore;
    private final StorageProperties storageProperties;
    private final List<StoryItem> stories = new ArrayList<>();
    private final List<FeedPostResponse> feedPosts = new ArrayList<>();
    private final Map<String, List<MediaAssetResponse>> mediaByOwner = new ConcurrentHashMap<>();
    private final AtomicInteger mediaSequence = new AtomicInteger(1);

    public ContentService(AccountService accountService, @Lazy SocialService socialService, ModuleStateStore moduleStateStore, StorageProperties storageProperties) {
        this.accountService = accountService;
        this.socialService = socialService;
        this.moduleStateStore = moduleStateStore;
        this.storageProperties = storageProperties;
        ensureUploadDirectory();
        loadPersistedState();
    }

    public HomeResponse home(String userId) {
        return new HomeResponse(
                stories,
                feedPosts,
                discoverCandidatesFor(userId).stream().limit(4).toList(),
                List.of("12 matches", "4 live calls", "28 new likes", "7 unread messages"),
                List.of("For You", "Following", "Nearby", "Trending"),
                7,
                3
        );
    }

    public DiscoverResponse discover(String userId, String gender, Integer minAge, Integer maxAge, List<String> excludeIds) {
        String normalizedGender = gender == null ? "" : gender.trim().toLowerCase(Locale.ROOT);
        int safeMinAge = minAge == null ? 0 : Math.max(0, minAge);
        int safeMaxAge = maxAge == null ? 200 : Math.max(safeMinAge, maxAge);
        List<String> excluded = excludeIds == null ? List.of() : excludeIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        return new DiscoverResponse(
                discoverCandidatesFor(userId).stream()
                        .filter(candidate -> !Objects.equals(candidate.user().userId(), userId))
                        .filter(candidate -> !excluded.contains(candidate.candidateId()))
                        .filter(candidate -> normalizedGender.isBlank()
                                || "all".equals(normalizedGender)
                                || "both".equals(normalizedGender)
                                || candidate.user().gender().toLowerCase(Locale.ROOT).contains(normalizedGender))
                        .filter(candidate -> candidate.user().age() >= safeMinAge && candidate.user().age() <= safeMaxAge)
                        .sorted(Comparator.comparingInt(DiscoveryCandidate::compatibility).reversed())
                        .toList(),
                List.of("Nearby", "Verified", "Online", "Video intro", "Travel mode")
        );
    }

    public SwipeResponse swipe(String userId, SwipeRequest request) {
        DiscoveryCandidate candidate = findCandidate(userId, request.candidateId());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found");
        }
        boolean matched = "right".equalsIgnoreCase(request.direction()) && candidate.compatibility() >= 78;
        String message = matched ? "It's a match" : "Swiped " + request.direction();
        String nextCandidateId = nextCandidateId(candidate.candidateId());
        return new SwipeResponse(matched, message, nextCandidateId);
    }

    public PokeResponse poke(String userId, PokeRequest request) {
        DiscoveryCandidate candidate = findCandidate(userId, request.candidateId());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found");
        }
        var actor = accountService.getPublicProfile(userId);
        String actorName = actor.displayName() == null || actor.displayName().isBlank() ? "Someone" : actor.displayName();
        socialService.publishRelationNotification(
                candidate.user().userId(),
                userId,
                "POKE",
                "Someone poked you",
                actorName + " poked you",
                "profile/" + userId
        );
        return new PokeResponse(true, "Poke sent", nextCandidateId(candidate.candidateId()));
    }

    public MediaLibraryResponse mediaLibrary(String userId) {
        return new MediaLibraryResponse(mediaByOwner.getOrDefault(userId, List.of()));
    }

    public MediaAssetResponse uploadMedia(String userId, UploadMediaRequest request) {
        MediaAssetResponse asset = new MediaAssetResponse(
                "media-" + mediaSequence.getAndIncrement(),
                userId,
                request.title(),
                request.url(),
                request.mimeType(),
                request.kind(),
                request.previewUrl(),
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                false
        );
        mediaByOwner.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(asset);
        persistState();
        return asset;
    }

    public MediaAssetResponse uploadMediaFile(String userId, MultipartFile file, String title, String kind, String previewUrl) {
        if (file == null || file.isEmpty()) {
            throw new NotFoundException("File is required");
        }

        String assetId = "media-" + mediaSequence.getAndIncrement();
        String originalName = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        String safeName = sanitizeFilename(originalName);
        String storedName = assetId + "-" + safeName;
        Path target = uploadRoot().resolve("media").resolve(userId).resolve(storedName);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to store media upload", ex);
        }

        String resolvedKind = normalizeKind(kind, file.getContentType(), originalName);
        if ("video".equalsIgnoreCase(resolvedKind) && file.getSize() > MAX_VIDEO_BYTES) {
            throw new IllegalArgumentException("Video must be 50 MB or smaller");
        }
        String resolvedTitle = title == null || title.isBlank() ? originalName : title.trim();
        String relativePath = "/uploads/media/" + userId + "/" + storedName;
        String resolvedPreview = previewUrl == null || previewUrl.isBlank()
                ? ("image".equalsIgnoreCase(resolvedKind) ? relativePath : null)
                : previewUrl.trim();

        MediaAssetResponse asset = new MediaAssetResponse(
                assetId,
                userId,
                resolvedTitle,
                relativePath,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                resolvedKind,
                resolvedPreview,
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                false
        );
        mediaByOwner.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(asset);
        persistState();
        return asset;
    }

    private void loadPersistedState() {
        moduleStateStore.load("content", ContentState.class).ifPresentOrElse(state -> {
            mediaByOwner.clear();
            mediaByOwner.putAll(state.mediaByOwner() == null ? Map.of() : state.mediaByOwner());
            mediaSequence.set(Math.max(1, state.mediaSequence()));
            if (mediaByOwner.keySet().removeIf(LEGACY_DEMO_USER_IDS::contains)) {
                persistState();
            }
        }, () -> persistState());
    }

    private void persistState() {
        moduleStateStore.save("content", new ContentState(new LinkedHashMap<>(mediaByOwner), mediaSequence.get()));
    }

    private DiscoveryCandidate findCandidate(String viewerUserId, String candidateId) {
        return discoverCandidatesFor(viewerUserId).stream()
                .filter(candidate -> Objects.equals(candidate.candidateId(), candidateId))
                .findFirst()
                .orElse(null);
    }

    private String nextCandidateId(String candidateId) {
        return null;
    }

    private List<DiscoveryCandidate> discoverCandidatesFor(String viewerUserId) {
        var viewer = accountService.getPublicProfile(viewerUserId);
        return accountService.searchUsers("", null, null, 0, 100).items().stream()
                .filter(card -> !Objects.equals(card.userId(), viewerUserId))
                .map(card -> toDiscoveryCandidate(viewer, card))
                .sorted(Comparator.comparingInt(DiscoveryCandidate::compatibility).reversed())
                .toList();
    }

    private DiscoveryCandidate toDiscoveryCandidate(com.nova.backend.account.PublicUserCard viewer, com.nova.backend.account.PublicUserCard card) {
        List<String> sharedInterests = sharedInterests(viewer, card);
        List<String> gallery = card.featuredPhotos() == null || card.featuredPhotos().isEmpty()
                ? List.of(card.avatarUrl())
                : card.featuredPhotos();
        return new DiscoveryCandidate(
                card.userId(),
                card,
                card.bio(),
                compatibilityScore(viewer, card, sharedInterests),
                sharedInterests,
                iceBreaker(viewer, card, sharedInterests),
                0,
                "",
                "",
                "",
                "",
                gallery,
                false,
                false
        );
    }

    private int compatibilityScore(
            com.nova.backend.account.PublicUserCard viewer,
            com.nova.backend.account.PublicUserCard candidate,
            List<String> sharedInterests
    ) {
        int score = 65;
        int viewerInterestCount = viewer.interests() == null ? 0 : viewer.interests().size();
        int candidateInterestCount = candidate.interests() == null ? 0 : candidate.interests().size();
        int maxInterestCount = Math.max(1, Math.max(viewerInterestCount, candidateInterestCount));
        score += Math.min(20, (int) Math.round((sharedInterests.size() * 20.0) / maxInterestCount));

        int ageGap = Math.abs(viewer.age() - candidate.age());
        score += Math.max(0, 8 - Math.min(8, ageGap / 2));

        if (viewer.city() != null
                && candidate.city() != null
                && !viewer.city().isBlank()
                && viewer.city().equalsIgnoreCase(candidate.city())) {
            score += 5;
        }
        if (candidate.verified()) score += 3;
        if (candidate.online()) score += 2;
        if (candidate.premium()) score += 2;

        return Math.max(65, Math.min(99, score));
    }

    private List<String> sharedInterests(com.nova.backend.account.PublicUserCard viewer, com.nova.backend.account.PublicUserCard candidate) {
        if (viewer.interests() == null || candidate.interests() == null) {
            return List.of();
        }
        Map<String, String> candidateInterestsByKey = candidate.interests().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        value -> value.trim().toLowerCase(Locale.ROOT),
                        String::trim,
                        (left, ignored) -> left,
                        LinkedHashMap::new
                ));
        return viewer.interests().stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(candidateInterestsByKey::containsKey)
                .map(candidateInterestsByKey::get)
                .toList();
    }

    private String iceBreaker(
            com.nova.backend.account.PublicUserCard viewer,
            com.nova.backend.account.PublicUserCard candidate,
            List<String> sharedInterests
    ) {
        if (!sharedInterests.isEmpty()) {
            return "Ask about " + sharedInterests.get(0) + ".";
        }
        if (viewer.city() != null
                && candidate.city() != null
                && !viewer.city().isBlank()
                && viewer.city().equalsIgnoreCase(candidate.city())) {
            return "Ask about a favorite place in " + candidate.city() + ".";
        }
        return "Start with something from their bio.";
    }

    private record ContentState(Map<String, List<MediaAssetResponse>> mediaByOwner, int mediaSequence) {
    }

    private void ensureUploadDirectory() {
        try {
            Files.createDirectories(uploadRoot());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create upload directory", ex);
        }
    }

    private Path uploadRoot() {
        String raw = storageProperties == null ? null : storageProperties.uploadDir();
        return Path.of(raw == null || raw.isBlank() ? "./uploads" : raw).toAbsolutePath().normalize();
    }

    private String sanitizeFilename(String filename) {
        String sanitized = filename.replaceAll("[\\\\/]+", "_").replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? "upload.bin" : sanitized;
    }

    private String normalizeKind(String kind, String mimeType, String originalName) {
        if (kind != null && !kind.isBlank()) {
            return kind.trim().toLowerCase(Locale.ROOT);
        }
        if (mimeType != null) {
            if (mimeType.startsWith("image/")) return "image";
            if (mimeType.startsWith("video/")) return "video";
            if (mimeType.startsWith("audio/")) return "audio";
        }
        String lower = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) return "image";
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv") || lower.endsWith(".webm")) return "video";
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".wav") || lower.endsWith(".ogg")) return "audio";
        return "file";
    }
}
