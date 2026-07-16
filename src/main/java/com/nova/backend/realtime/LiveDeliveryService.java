package com.nova.backend.realtime;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
public class LiveDeliveryService {

    private final RealtimeSessionRegistry sessionRegistry;
    private final DeviceTokenService deviceTokenService;
    private final PushGateway pushGateway;

    public LiveDeliveryService(
            RealtimeSessionRegistry sessionRegistry,
            DeviceTokenService deviceTokenService,
            PushGateway pushGateway
    ) {
        this.sessionRegistry = sessionRegistry;
        this.deviceTokenService = deviceTokenService;
        this.pushGateway = pushGateway;
    }

    public void publish(Collection<String> recipientUserIds, RealtimeEvent event) {
        if (recipientUserIds == null || recipientUserIds.isEmpty() || event == null) {
            return;
        }
        sessionRegistry.publish(recipientUserIds, event);
        List<String> tokens = deviceTokenService.tokensForUsers(recipientUserIds);
        pushGateway.send(tokens, event.toPushMessage());
    }

    public void publishToUser(String userId, RealtimeEvent event) {
        publish(List.of(userId), event);
    }
}
