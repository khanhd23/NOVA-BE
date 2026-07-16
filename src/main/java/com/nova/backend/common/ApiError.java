package com.nova.backend.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String code,
        String message,
        String path,
        Map<String, String> details,
        Instant timestamp
) {
}
