package com.nova.backend.realtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record RealtimeEvent(
        String id,
        RealtimeEventType type,
        String room,
        String actorUserId,
        String targetUserId,
        String threadId,
        String callId,
        String messageId,
        String title,
        String body,
        Map<String, Object> payload,
        Instant timestamp
) {
    PushMessage toPushMessage() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("eventId", id);
        data.put("type", type.name());
        if (room != null) {
            data.put("room", room);
        }
        if (actorUserId != null) {
            data.put("actorUserId", actorUserId);
        }
        if (targetUserId != null) {
            data.put("targetUserId", targetUserId);
        }
        if (threadId != null) {
            data.put("threadId", threadId);
        }
        if (callId != null) {
            data.put("callId", callId);
        }
        if (messageId != null) {
            data.put("messageId", messageId);
        }
        data.put("timestamp", timestamp.toString());
        if (payload != null) {
            payload.forEach((key, value) -> data.put(key, value == null ? "" : String.valueOf(value)));
        }
        return new PushMessage(title, body, data);
    }
}
