package com.nova.backend.content;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @GetMapping("/home")
    public ApiResponse<HomeResponse> home(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(contentService.home(principal.userId()));
    }

    @GetMapping("/discover")
    public ApiResponse<DiscoverResponse> discover(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(contentService.discover(principal.userId()));
    }

    @PostMapping("/discover/swipe")
    public ApiResponse<SwipeResponse> swipe(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody SwipeRequest request
    ) {
        return ApiResponse.ok(contentService.swipe(principal.userId(), request));
    }

    @GetMapping("/media")
    public ApiResponse<MediaLibraryResponse> media(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(contentService.mediaLibrary(principal.userId()));
    }

    @PostMapping("/media")
    public ApiResponse<MediaAssetResponse> upload(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UploadMediaRequest request
    ) {
        return ApiResponse.ok(contentService.uploadMedia(principal.userId(), request), "Media uploaded");
    }

    @PostMapping(value = "/media/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaAssetResponse> uploadFile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "previewUrl", required = false) String previewUrl
    ) {
        return ApiResponse.ok(contentService.uploadMediaFile(principal.userId(), file, title, kind, previewUrl), "Media uploaded");
    }
}
