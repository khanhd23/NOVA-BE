package com.nova.backend.realtime;

import com.nova.backend.auth.AuthPrincipal;
import com.nova.backend.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeConfigController {

    private final WebRtcProperties webRtcProperties;

    public RealtimeConfigController(WebRtcProperties webRtcProperties) {
        this.webRtcProperties = webRtcProperties;
    }

    @GetMapping("/config")
    public ApiResponse<RealtimeConfigResponse> config(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(new RealtimeConfigResponse(
                buildIceServers(),
                Math.max(0, webRtcProperties.maxRestartAttempts()),
                Math.max(250L, webRtcProperties.restartBackoffMs())
        ));
    }

    private List<IceServerResponse> buildIceServers() {
        List<IceServerResponse> items = new ArrayList<>();

        for (String url : webRtcProperties.parsedIceServers()) {
            items.add(new IceServerResponse(url, null, null));
        }
        for (String url : webRtcProperties.parsedTurnServers()) {
            items.add(new IceServerResponse(
                    url,
                    blankToNull(webRtcProperties.turnUsername()),
                    blankToNull(webRtcProperties.turnCredential())
            ));
        }

        if (items.isEmpty()) {
            items.add(new IceServerResponse("stun:stun.l.google.com:19302", null, null));
        }
        return items;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
