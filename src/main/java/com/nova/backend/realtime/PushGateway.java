package com.nova.backend.realtime;

import java.util.Collection;

public interface PushGateway {
    void send(Collection<String> deviceTokens, PushMessage message);
}
