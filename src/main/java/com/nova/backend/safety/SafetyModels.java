package com.nova.backend.safety;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

record ReportRequest(
        @NotBlank String targetUserId,
        @NotBlank String reason,
        String details
) {
}

record BlockRequest(
        @NotBlank String targetUserId,
        boolean blocked
) {
}

record SafetyActionResponse(
        String status,
        String message
) {
}

record AdminMetricResponse(
        String label,
        String value,
        String detail
) {
}

record SafetySettingsResponse(
        boolean incognitoEnabled,
        boolean locationSharingEnabled,
        boolean autoTranslateEnabled,
        boolean travelModeEnabled
) {
}

record SafetyDashboardResponse(
        List<AdminMetricResponse> metrics,
        List<SafetyActionResponse> recentActions
) {
}
