package com.keevo.messaging.notification.adapter.out.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FirebaseInitializerTest {

    @Test
    void init_whenDisabled_skipsInitialization() {
        FcmProperties props = new FcmProperties(false, "");
        FirebaseInitializer initializer = new FirebaseInitializer(props);

        // Should not throw — gracefully skips when disabled
        assertDoesNotThrow(() -> initializer.afterPropertiesSet());
    }

    @Test
    void init_whenBadCredentials_logsWarnAndContinues() {
        FcmProperties props = new FcmProperties(true, "/non/existent/path.json");
        FirebaseInitializer initializer = new FirebaseInitializer(props);

        // Should not throw — graceful degradation
        assertDoesNotThrow(() -> initializer.afterPropertiesSet());
    }
}
