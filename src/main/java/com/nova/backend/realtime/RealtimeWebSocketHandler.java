package com.nova.backend.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.backend.account.AccountService;
import com.nova.backend.social.SocialService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private final RealtimeSessionRegistry sessionRegistry;
    private final AccountService accountService;
    private final SocialService socialService;
    private final ObjectMapper objectMapper;

    public RealtimeWebSocketHandler(
            RealtimeSessionRegistry sessionRegistry,
            AccountService accountService,
            SocialService socialService,
            ObjectMapper objectMapper
    ) {
        this.sessionRegistry = sessionRegistry;
        this.accountService = accountService;
        this.socialService = socialService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = (String) session.getAttributes().get("userId");
        boolean firstSession = sessionRegistry.register(userId, session);
        if (firstSession && accountService.setOnline(userId, true)) {
            socialService.publishPresence(userId, true);
        }
        send(session, new RealtimeEvent(
                UUID.randomUUID().toString(),
                RealtimeEventType.CONNECTION_READY,
                "system",
                null,
                userId,
                null,
                null,
                null,
                "Realtime connected",
                "WebSocket connection established",
                Map.of("sessionId", String.valueOf(session.getAttributes().get("sessionId"))),
                Instant.now()
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayload() == null || message.getPayload().isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (Exception ex) {
            return;
        }
        String type = node.path("type").asText("");
        if ("ping".equalsIgnoreCase(type)) {
            send(session, new RealtimeEvent(
                    UUID.randomUUID().toString(),
                    RealtimeEventType.PING,
                    "system",
                    null,
                    (String) session.getAttributes().get("userId"),
                    null,
                    null,
                    null,
                    "pong",
                    "pong",
                    new LinkedHashMap<>(Map.of("echo", node.path("echo").asText(""))),
                    Instant.now()
            ));
            return;
        }
        if ("call_signal".equalsIgnoreCase(type)) {
            handleCallSignal(session, node);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String userId = sessionRegistry.unregister(session);
        if (userId != null && !sessionRegistry.hasActiveSession(userId) && accountService.setOnline(userId, false)) {
            socialService.publishPresence(userId, false);
        }
    }

    private void send(WebSocketSession session, RealtimeEvent event) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (IOException ex) {
            // Ignore.
        }
    }

    private void handleCallSignal(WebSocketSession session, JsonNode node) {
        String userId = (String) session.getAttributes().get("userId");
        String targetUserId = node.path("targetUserId").asText("");
        String threadId = node.path("threadId").asText(null);
        String callId = node.path("callId").asText(null);
        String signalType = node.path("signalType").asText("");
        if (targetUserId.isBlank() || signalType.isBlank()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("signalType", signalType);
        payload.put("fromUserId", userId);
        payload.put("toUserId", targetUserId);
        payload.put("threadId", threadId);
        payload.put("callId", callId);
        payload.put("sdpType", node.path("sdpType").asText(""));
        payload.put("sdp", node.path("sdp").asText(""));
        payload.put("candidate", node.path("candidate").asText(""));
        payload.put("sdpMid", node.path("sdpMid").asText(""));
        payload.put("sdpMLineIndex", node.path("sdpMLineIndex").asText(""));
        payload.put("video", node.path("video").asBoolean(false));

        sessionRegistry.publish(targetUserId, new RealtimeEvent(
                UUID.randomUUID().toString(),
                RealtimeEventType.CALL_SIGNAL,
                "call/" + callId,
                userId,
                targetUserId,
                threadId,
                callId,
                null,
                "Call signal",
                signalType,
                payload,
                Instant.now()
        ));
    }
}
