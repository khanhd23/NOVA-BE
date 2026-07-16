package com.nova.backend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RealtimeSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionRegistry.class);

    private final Map<String, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();
    private final Map<String, String> userBySessionId = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public RealtimeSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(String userId, WebSocketSession session) {
        sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        userBySessionId.put(session.getId(), userId);
    }

    public void unregister(WebSocketSession session) {
        String userId = userBySessionId.remove(session.getId());
        if (userId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByUser.remove(userId);
            }
        }
    }

    public void publish(Collection<String> userIds, RealtimeEvent event) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        userIds.forEach(userId -> publish(userId, event));
    }

    public void publish(String userId, RealtimeEvent event) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession session : sessions.toArray(WebSocketSession[]::new)) {
            if (!session.isOpen()) {
                unregister(session);
                continue;
            }
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
            } catch (IOException ex) {
                log.warn("Failed to publish realtime event {} to user {}: {}", event.type(), userId, ex.getMessage());
                unregister(session);
            }
        }
    }
}
