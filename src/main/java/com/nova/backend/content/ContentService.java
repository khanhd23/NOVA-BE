package com.nova.backend.content;

import com.nova.backend.account.AccountService;
import com.nova.backend.common.exception.NotFoundException;
import com.nova.backend.common.ModuleStateStore;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class ContentService {

    private static final long MAX_VIDEO_BYTES = 50L * 1024L * 1024L;

    private final AccountService accountService;
    private final ModuleStateStore moduleStateStore;
    private final StorageProperties storageProperties;
    private final List<StoryItem> stories = new ArrayList<>();
    private final List<FeedPostResponse> feedPosts = new ArrayList<>();
    private final List<DiscoveryCandidate> candidates = new ArrayList<>();
    private final Map<String, List<MediaAssetResponse>> mediaByOwner = new ConcurrentHashMap<>();
    private final AtomicInteger mediaSequence = new AtomicInteger(1);

    public ContentService(AccountService accountService, ModuleStateStore moduleStateStore, StorageProperties storageProperties) {
        this.accountService = accountService;
        this.moduleStateStore = moduleStateStore;
        this.storageProperties = storageProperties;
        ensureUploadDirectory();
        seed();
        loadPersistedState();
    }

    public HomeResponse home(String userId) {
        return new HomeResponse(
                stories,
                feedPosts,
                candidates.stream().limit(4).toList(),
                List.of("12 matches", "4 live calls", "28 new likes", "7 unread messages"),
                List.of("For You", "Following", "Nearby", "Trending"),
                7,
                3
        );
    }

    public DiscoverResponse discover(String userId) {
        return new DiscoverResponse(
                candidates.stream()
                        .sorted(Comparator.comparingInt(DiscoveryCandidate::compatibility).reversed())
                        .toList(),
                List.of("Nearby", "Verified", "Online", "Video intro", "Travel mode")
        );
    }

    public SwipeResponse swipe(String userId, SwipeRequest request) {
        DiscoveryCandidate candidate = findCandidate(request.candidateId());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found");
        }
        boolean matched = "right".equalsIgnoreCase(request.direction()) && candidate.compatibility() >= 78;
        String message = matched ? "It's a match" : "Swiped " + request.direction();
        String nextCandidateId = nextCandidateId(candidate.candidateId());
        return new SwipeResponse(matched, message, nextCandidateId);
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

    private void seed() {
        var seraphina = accountService.getPublicProfile("u-seraphina");
        var elena = accountService.getPublicProfile("u-elena");
        var marcus = accountService.getPublicProfile("u-marcus");
        var chloe = accountService.getPublicProfile("u-chloe");
        var alex = accountService.getPublicProfile("u-alex");
        var mina = accountService.getPublicProfile("u-mina");

        stories.add(new StoryItem("story_1", seraphina, "https://cdn.nova/story/story-1.jpg", "Late coffee and design review", "Cinematic", false, 8));
        stories.add(new StoryItem("story_2", chloe, "https://cdn.nova/story/story-2.jpg", "Weekend travel plans", "Indie pop", false, 7));
        stories.add(new StoryItem("story_3", elena, "https://cdn.nova/story/story-3.jpg", "Community meetup tonight", "Synth wave", true, 6));

        feedPosts.add(new FeedPostResponse(
                "post_1",
                seraphina,
                "Found a cleaner way to ship call UI without turning the viewmodel into a mess.",
                List.of("https://cdn.nova/post/post-1.jpg"),
                482,
                36,
                19,
                List.of("Android", "Compose", "Product"),
                "12m ago"
        ));
        feedPosts.add(new FeedPostResponse(
                "post_2",
                alex,
                "Testing a new message thread layout today.",
                List.of("https://cdn.nova/post/post-2.jpg"),
                214,
                18,
                11,
                List.of("UI", "Testing"),
                "38m ago"
        ));
        feedPosts.add(new FeedPostResponse(
                "post_3",
                mina,
                "Trying a photo walk tomorrow if anyone wants to join.",
                List.of("https://cdn.nova/post/post-3.jpg"),
                632,
                41,
                25,
                List.of("Travel", "Photography"),
                "2h ago"
        ));

        candidates.add(new DiscoveryCandidate(
                "u-elena",
                elena,
                "Community builder who loves clean product flows.",
                96,
                List.of("Product", "Messaging", "Events"),
                "Ask her about the best onboarding flow she has seen.",
                12,
                "Indie electronic",
                "168 cm",
                "PM",
                "Serious",
                List.of("https://cdn.nova/discover/elena-1.jpg", "https://cdn.nova/discover/elena-2.jpg"),
                true,
                true
        ));
        candidates.add(new DiscoveryCandidate(
                "u-chloe",
                chloe,
                "Photographer, traveler, and always on the move.",
                92,
                List.of("Travel", "Photography", "Coffee"),
                "Ask her to pick her favorite city for a weekend trip.",
                9,
                "Lo-fi",
                "171 cm",
                "Creator",
                "Open",
                List.of("https://cdn.nova/discover/chloe-1.jpg", "https://cdn.nova/discover/chloe-2.jpg"),
                true,
                true
        ));
        candidates.add(new DiscoveryCandidate(
                "u-marcus",
                marcus,
                "Product engineer and a heavy user of dark mode.",
                87,
                List.of("Android", "Build systems", "Coffee"),
                "Ask him what he would rewrite first in a large app.",
                5,
                "House",
                "180 cm",
                "Engineer",
                "Long term",
                List.of("https://cdn.nova/discover/marcus-1.jpg"),
                true,
                false
        ));
        candidates.add(new DiscoveryCandidate(
                "u-mina",
                mina,
                "Community-focused and likes active weekends.",
                84,
                List.of("Running", "Community", "Travel"),
                "Ask her which city has the best running routes.",
                8,
                "Pop",
                "165 cm",
                "Community lead",
                "Dating",
                List.of("https://cdn.nova/discover/mina-1.jpg"),
                true,
                true
        ));

        mediaByOwner.put("u-seraphina", List.of(
                new MediaAssetResponse("media-1", "u-seraphina", "Call UI mock", "https://cdn.nova/media/mock-call-ui.jpg", "image/jpeg", "image", null, "2026-07-07T10:00:00Z", false)
        ));
        mediaByOwner.put("u-chloe", List.of(
                new MediaAssetResponse("media-2", "u-chloe", "Travel reel", "https://cdn.nova/media/travel-reel.mp4", "video/mp4", "video", "https://cdn.nova/media/travel-reel-thumb.jpg", "2026-07-07T11:00:00Z", true)
        ));
    }

    private void loadPersistedState() {
        moduleStateStore.load("content", ContentState.class).ifPresentOrElse(state -> {
            mediaByOwner.clear();
            mediaByOwner.putAll(state.mediaByOwner() == null ? Map.of() : state.mediaByOwner());
            mediaSequence.set(Math.max(1, state.mediaSequence()));
        }, () -> persistState());
    }

    private void persistState() {
        moduleStateStore.save("content", new ContentState(new LinkedHashMap<>(mediaByOwner), mediaSequence.get()));
    }

    private DiscoveryCandidate findCandidate(String candidateId) {
        return candidates.stream()
                .filter(candidate -> Objects.equals(candidate.candidateId(), candidateId))
                .findFirst()
                .orElse(null);
    }

    private String nextCandidateId(String candidateId) {
        int index = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (Objects.equals(candidates.get(i).candidateId(), candidateId)) {
                index = i;
                break;
            }
        }
        if (index < 0 || index + 1 >= candidates.size()) {
            return null;
        }
        return candidates.get(index + 1).candidateId();
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
