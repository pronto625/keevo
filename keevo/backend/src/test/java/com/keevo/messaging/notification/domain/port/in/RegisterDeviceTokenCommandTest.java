package com.keevo.messaging.notification.domain.port.in;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RegisterDeviceTokenCommandTest {

    @Test
    void command_withValidFields_createsSuccessfully() {
        UUID actorId = UUID.randomUUID();
        RegisterDeviceTokenCommand command = new RegisterDeviceTokenCommand(
                actorId, "OWNER", "fcm-token-123", "ANDROID", "Samsung Galaxy A14");

        assertEquals(actorId, command.actorId());
        assertEquals("OWNER", command.actorRole());
        assertEquals("fcm-token-123", command.token());
        assertEquals("ANDROID", command.platform());
        assertEquals("Samsung Galaxy A14", command.deviceName());
    }

    @Test
    void command_withBlankToken_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                new RegisterDeviceTokenCommand(UUID.randomUUID(), "OWNER", "", "ANDROID", "Device"));
        assertThrows(IllegalArgumentException.class, () ->
                new RegisterDeviceTokenCommand(UUID.randomUUID(), "OWNER", "   ", "ANDROID", "Device"));
    }

    @Test
    void command_platformEnum_allValuesValid() {
        // All valid platforms should create commands without error
        for (String platform : new String[]{"ANDROID", "IOS", "LINUX", "WINDOWS"}) {
            RegisterDeviceTokenCommand cmd = new RegisterDeviceTokenCommand(
                    UUID.randomUUID(), "OWNER", "token-" + platform, platform, "Device");
            assertEquals(platform, cmd.platform());
        }
    }
}
