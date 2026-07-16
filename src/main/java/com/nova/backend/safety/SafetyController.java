package com.nova.backend.safety;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SafetyController {

    private final SafetyService safetyService;

    public SafetyController(SafetyService safetyService) {
        this.safetyService = safetyService;
    }

    @PostMapping("/reports")
    public ApiResponse<SafetyActionResponse> report(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody ReportRequest request
    ) {
        return ApiResponse.ok(safetyService.report(request));
    }

    @PostMapping("/blocks")
    public ApiResponse<SafetyActionResponse> block(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody BlockRequest request
    ) {
        return ApiResponse.ok(safetyService.block(request));
    }

    @GetMapping("/admin/metrics")
    public ApiResponse<SafetyDashboardResponse> metrics(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(safetyService.dashboard());
    }
}
