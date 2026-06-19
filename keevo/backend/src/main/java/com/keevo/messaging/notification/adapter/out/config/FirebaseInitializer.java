package com.keevo.messaging.notification.adapter.out.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;

/**
 * Initializes Firebase Admin SDK for FCM push notifications.
 * Story 8.0 — AC1: conditional on keevo.fcm.enabled=true.
 *
 * <p>If credentials are invalid or missing, logs WARN and continues (graceful degradation).
 */
@Component
@ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "true")
public class FirebaseInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(FirebaseInitializer.class);

    private final FcmProperties properties;

    public FirebaseInitializer(FcmProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.enabled()) {
            log.info("[FCM] Firebase initialization skipped — keevo.fcm.enabled=false");
            return;
        }

        try {
            if (FirebaseApp.getApps().isEmpty()) {
                String credPath = properties.credentialsPath();
                if (credPath == null || credPath.isBlank()) {
                    log.error("[FailFast] GOOGLE_APPLICATION_CREDENTIALS is not set — FCM will be disabled. "
                            + "Set the env var to the path of the Firebase service account JSON.");
                    return;
                }

                FileInputStream serviceAccount = new FileInputStream(credPath);
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("[FCM] Firebase initialized successfully");
            } else {
                log.info("[FCM] Firebase already initialized");
            }
        } catch (Exception e) {
            log.warn("[FCM] Firebase initialization failed — falling back to LoggingNotificationAdapter: {}",
                    e.getMessage());
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                return FirebaseMessaging.getInstance();
            }
        } catch (Exception e) {
            log.warn("[FCM] Cannot create FirebaseMessaging bean: {}", e.getMessage());
        }
        return null;
    }
}
