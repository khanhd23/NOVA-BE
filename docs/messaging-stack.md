# Messaging Stack

This tab is REST-backed with realtime delivery layered on top. The UI contract stays stable: REST remains the source of truth, while WebSocket and FCM keep active clients in sync.

## Current backend API

- `GET /api/v1/threads`
- `GET /api/v1/threads/{threadId}`
- `POST /api/v1/threads/{threadId}/messages`
- `DELETE /api/v1/threads/{threadId}`
- `DELETE /api/v1/threads/{threadId}/messages/{messageId}`
- `POST /api/v1/threads/{threadId}/messages/{messageId}/recall`
- `POST /api/v1/threads/{threadId}/read`
- `POST /api/v1/threads/{threadId}/typing`
- `POST /api/v1/threads/{threadId}/calls`
- `GET /api/v1/calls`
- `GET /api/v1/calls/{callId}`
- `POST /api/v1/calls/{callId}/answer`
- `POST /api/v1/calls/{callId}/end`
- `POST /api/v1/calls/{callId}/minimize`
- `GET /api/v1/notifications`
- `POST /api/v1/notifications/read`
- `POST /api/v1/push/tokens`
- `GET /api/v1/push/tokens`
- `DELETE /api/v1/push/tokens/{token}`
- `WS /ws/realtime`

## Supported behaviors

- Chat list with last visible message preview
- One-side conversation delete
- One-side message delete
- Recall message for everyone
- Mark thread as read
- Typing state toggle
- Incoming/outgoing call state
- Call answer/end/minimize
- Realtime websocket fan-out for active clients
- FCM push fallback for background clients

## Third-party / infra to make it production-ready

- Firebase Cloud Messaging for push notifications.
  - Official docs: https://firebase.google.com/docs/cloud-messaging
  - Android `google-services.json` enables the client SDK; backend push delivery still needs a Firebase service account file and project id.
- Object storage for attachments and voice notes.
- Redis for presence, unread counters, typing TTL, and transient message delivery state.
- PostgreSQL for durable message history and delete/recall audit trails.
- WebSocket for realtime thread and call updates.

## Recommended integration order

1. Persist thread/message state in PostgreSQL.
2. Add Redis for typing and presence.
3. Add FCM push for new message alerts.
4. Add WebSocket for live updates.
5. Add attachment uploads after the core flow is stable.
