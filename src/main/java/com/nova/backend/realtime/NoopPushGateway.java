package com.nova.backend.realtime;

import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
public class NoopPushGateway implements PushGateway {
    @Override
    public void send(Collection<String> deviceTokens, PushMessage message) {
        // No-op until Firebase is configured on the backend.
    }
}
