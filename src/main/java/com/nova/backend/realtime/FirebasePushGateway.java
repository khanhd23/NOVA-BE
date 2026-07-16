package com.nova.backend.realtime;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

public class FirebasePushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(FirebasePushGateway.class);

    private final boolean enabled;

    public FirebasePushGateway(String serviceAccountPath, String projectId) {
        boolean initialized = false;
        if (serviceAccountPath != null && !serviceAccountPath.isBlank()) {
            try (InputStream stream = Files.newInputStream(Path.of(serviceAccountPath))) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(stream);
                FirebaseOptions.Builder builder = FirebaseOptions.builder()
                        .setCredentials(credentials);
                if (projectId != null && !projectId.isBlank()) {
                    builder.setProjectId(projectId.trim());
                }
                FirebaseOptions options = builder.build();
                synchronized (FirebasePushGateway.class) {
                    if (FirebaseApp.getApps().isEmpty()) {
                        FirebaseApp.initializeApp(options);
                    }
                }
                initialized = true;
            } catch (IOException ex) {
                log.warn("Firebase push is disabled because service account could not be loaded: {}", ex.getMessage());
            } catch (Exception ex) {
                log.warn("Firebase push is disabled because initialization failed: {}", ex.getMessage());
            }
        }
        this.enabled = initialized;
    }

    @Override
    public void send(Collection<String> deviceTokens, PushMessage message) {
        if (!enabled || deviceTokens == null || deviceTokens.isEmpty()) {
            return;
        }

        for (String token : deviceTokens) {
            try {
                Message firebaseMessage = Message.builder()
                        .setToken(token)
                        .putAllData(message.data() == null ? Map.of() : message.data())
                        .setAndroidConfig(AndroidConfig.builder()
                                .setPriority(AndroidConfig.Priority.HIGH)
                                .build())
                        .build();
                FirebaseMessaging.getInstance().send(firebaseMessage);
            } catch (Exception ex) {
                log.warn("Failed to send FCM push to token {}: {}", token, ex.getMessage());
            }
        }
    }
}
