package com.keevo.messaging.notification.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeviceTokenTest {

    @Test
    void deviceToken_create_setsAllFields() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String token = "fcm-token-abc123";
        DevicePlatform platform = DevicePlatform.ANDROID;
        String deviceName = "Samsung Galaxy A14";
        String role = "OWNER";
        Instant now = Instant.now();

        DeviceToken deviceToken = new DeviceToken(id, userId, token, platform, deviceName, role, now, now);

        assertEquals(id, deviceToken.id());
        assertEquals(userId, deviceToken.userId());
        assertEquals(token, deviceToken.token());
        assertEquals(platform, deviceToken.platform());
        assertEquals(deviceName, deviceToken.deviceName());
        assertEquals(role, deviceToken.role());
        assertEquals(now, deviceToken.createdAt());
        assertEquals(now, deviceToken.updatedAt());
    }

    @Test
    void deviceToken_updateTimestamp_changesUpdatedAt() {
        Instant created = Instant.parse("2026-03-01T10:00:00Z");
        Instant updated = Instant.parse("2026-04-01T10:00:00Z");

        DeviceToken original = new DeviceToken(
                UUID.randomUUID(), UUID.randomUUID(), "token", DevicePlatform.IOS,
                "iPhone 15", "OWNER", created, created);

        DeviceToken withNewTimestamp = original.withUpdatedAt(updated);

        assertEquals(original.id(), withNewTimestamp.id());
        assertEquals(original.token(), withNewTimestamp.token());
        assertEquals(created, withNewTimestamp.createdAt());
        assertEquals(updated, withNewTimestamp.updatedAt());
    }

    @Test
    void deviceToken_platformEnum_ANDROID_IOS_LINUX_WINDOWS() {
        assertEquals(4, DevicePlatform.values().length);
        assertNotNull(DevicePlatform.valueOf("ANDROID"));
        assertNotNull(DevicePlatform.valueOf("IOS"));
        assertNotNull(DevicePlatform.valueOf("LINUX"));
        assertNotNull(DevicePlatform.valueOf("WINDOWS"));
    }
}
