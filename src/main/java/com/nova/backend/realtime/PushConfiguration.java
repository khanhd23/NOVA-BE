package com.nova.backend.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class PushConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "firebase", name = "enabled", havingValue = "true")
    public PushGateway firebasePushGateway(
            @Value("${firebase.service-account-path:}") String serviceAccountPath,
            @Value("${firebase.project-id:}") String projectId
    ) {
        return new FirebasePushGateway(serviceAccountPath, projectId);
    }
}
