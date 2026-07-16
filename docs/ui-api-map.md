# UI to API map

| UI area | Backend module | Example endpoints |
| --- | --- | --- |
| Onboarding / login | auth | `POST /api/v1/auth/social/login` |
| App bootstrap / me | account | `GET /api/v1/me` |
| Profile setup | account | `PATCH /api/v1/me/profile` |
| Home feed | content | `GET /api/v1/home` |
| Discover / match | content | `GET /api/v1/discover` |
| Media / gallery | content | `GET /api/v1/media` |
| Messages | social | `GET /api/v1/threads` |
| Chat detail | social | `GET /api/v1/threads/{threadId}` |
| Send message | social | `POST /api/v1/threads/{threadId}/messages` |
| Delete conversation for me | social | `DELETE /api/v1/threads/{threadId}` |
| Delete message for me | social | `DELETE /api/v1/threads/{threadId}/messages/{messageId}` |
| Recall message | social | `POST /api/v1/threads/{threadId}/messages/{messageId}/recall` |
| Mark thread read | social | `POST /api/v1/threads/{threadId}/read` |
| Typing state | social | `POST /api/v1/threads/{threadId}/typing` |
| Voice / video call | social | `POST /api/v1/threads/{threadId}/calls` |
| Call list | social | `GET /api/v1/calls` |
| Call log / summary | social | `GET /api/v1/calls/{callId}` |
| Answer call | social | `POST /api/v1/calls/{callId}/answer` |
| End call | social | `POST /api/v1/calls/{callId}/end` |
| Minimize / restore call | social | `POST /api/v1/calls/{callId}/minimize` |
| Notifications | social | `GET /api/v1/notifications` |
| Mark notification read | social | `POST /api/v1/notifications/read` |
| Realtime websocket | realtime | `/ws/realtime` |
| Register push token | realtime | `POST /api/v1/push/tokens` |
| List push tokens | realtime | `GET /api/v1/push/tokens` |
| Remove push token | realtime | `DELETE /api/v1/push/tokens/{token}` |
| Community topics | community | `GET /api/v1/communities` |
| Community posts | community | `GET /api/v1/community-posts` |
| Events | community | `GET /api/v1/events` |
| Premium / wallet | account | `GET /api/v1/me/premium` |
| Store catalog | commerce | `GET /api/v1/commerce/catalog` |
| Payment providers | commerce | `GET /api/v1/commerce/providers` |
| Current VIP / diamonds | commerce | `GET /api/v1/commerce/me` |
| Create order | commerce | `POST /api/v1/commerce/orders` |
| Checkout / retry payment | commerce | `POST /api/v1/commerce/orders/{orderId}/checkout` |
| Confirm sandbox payment | commerce | `POST /api/v1/commerce/orders/{orderId}/confirm` |
| Payment webhook | commerce | `POST /api/v1/commerce/webhooks/{provider}` |
| Safety / reporting | safety | `POST /api/v1/reports` |
