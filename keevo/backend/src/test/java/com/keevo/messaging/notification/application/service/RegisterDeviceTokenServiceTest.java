package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.model.DevicePlatform;
import com.keevo.messaging.notification.domain.model.DeviceToken;
import com.keevo.messaging.notification.domain.port.in.RegisterDeviceTokenCommand;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegisterDeviceTokenServiceTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @InjectMocks
    private RegisterDeviceTokenService service;

    @Test
    void register_newToken_savesToRepository() {
        when(deviceTokenRepository.findByToken("fcm-token-new")).thenReturn(Optional.empty());

        RegisterDeviceTokenCommand command = new RegisterDeviceTokenCommand(
                UUID.randomUUID(), "OWNER", "fcm-token-new", "ANDROID", "Samsung Galaxy A14");

        service.register(command);

        ArgumentCaptor<DeviceToken> captor = ArgumentCaptor.forClass(DeviceToken.class);
        verify(deviceTokenRepository).save(captor.capture());
        DeviceToken saved = captor.getValue();
        assertEquals("fcm-token-new", saved.token());
        assertEquals(DevicePlatform.ANDROID, saved.platform());
        assertEquals("OWNER", saved.role());
    }

    @Test
    void register_existingToken_upsertsUpdatedAt() {
        DeviceToken existing = new DeviceToken(
                UUID.randomUUID(), UUID.randomUUID(), "fcm-token-existing",
                DevicePlatform.ANDROID, "Old Device", "OWNER",
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-01T00:00:00Z"));
        when(deviceTokenRepository.findByToken("fcm-token-existing")).thenReturn(Optional.of(existing));

        RegisterDeviceTokenCommand command = new RegisterDeviceTokenCommand(
                existing.userId(), "OWNER", "fcm-token-existing", "ANDROID", "New Device Name");

        service.register(command);

        verify(deviceTokenRepository).upsert(any(DeviceToken.class));
    }

    @Test
    void register_blankToken_throwsValidationError() {
        assertThrows(IllegalArgumentException.class, () ->
                service.register(new RegisterDeviceTokenCommand(
                        UUID.randomUUID(), "OWNER", "", "ANDROID", "Device")));
    }

    @Test
    void register_setsRoleFromCommand() {
        when(deviceTokenRepository.findByToken("fcm-token-emp")).thenReturn(Optional.empty());

        RegisterDeviceTokenCommand command = new RegisterDeviceTokenCommand(
                UUID.randomUUID(), "EMPLOYEE", "fcm-token-emp", "IOS", "iPhone");

        service.register(command);

        ArgumentCaptor<DeviceToken> captor = ArgumentCaptor.forClass(DeviceToken.class);
        verify(deviceTokenRepository).save(captor.capture());
        assertEquals("EMPLOYEE", captor.getValue().role());
    }
}
