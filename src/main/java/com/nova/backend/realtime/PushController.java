package com.nova.backend.realtime;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/push")
public class PushController {

    private final DeviceTokenService deviceTokenService;

    public PushController(DeviceTokenService deviceTokenService) {
        this.deviceTokenService = deviceTokenService;
    }

    @PostMapping("/tokens")
    public ApiResponse<DeviceTokenResponse> register(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RegisterDeviceTokenRequest request
    ) {
        return ApiResponse.ok(deviceTokenService.register(principal.userId(), request));
    }

    @GetMapping("/tokens")
    public ApiResponse<DeviceTokenListResponse> tokens(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(new DeviceTokenListResponse(deviceTokenService.list(principal.userId())));
    }

    @DeleteMapping("/tokens/{token}")
    public ApiResponse<?> deleteToken(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String token
    ) {
        deviceTokenService.remove(principal.userId(), token);
        return ApiResponse.ok(null, "Token removed");
    }
}
