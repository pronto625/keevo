package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteDeviceTokenServiceTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @InjectMocks
    private DeleteDeviceTokenService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void delete_ownToken_returnsTrue() {
        when(deviceTokenRepository.deleteByTokenAndUserId("fcm-token-existing", userId)).thenReturn(1);
        assertTrue(service.delete(userId, "fcm-token-existing"));
    }

    @Test
    void delete_nonExistentToken_returnsFalse() {
        when(deviceTokenRepository.deleteByTokenAndUserId("non-existent", userId)).thenReturn(0);
        assertFalse(service.delete(userId, "non-existent"));
    }

    // Story 11-4 / audit 2026-10-11: another user's token is never deleted.
    @Test
    void delete_someoneElsesToken_returnsFalseAndNeverDeletesByTokenAlone() {
        when(deviceTokenRepository.deleteByTokenAndUserId("other-user-token", userId)).thenReturn(0);
        assertFalse(service.delete(userId, "other-user-token"));
        verify(deviceTokenRepository, never()).deleteByToken(any());
    }
}
