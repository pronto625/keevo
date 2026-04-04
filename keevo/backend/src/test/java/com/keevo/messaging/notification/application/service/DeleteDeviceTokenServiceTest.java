package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteDeviceTokenServiceTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @InjectMocks
    private DeleteDeviceTokenService service;

    @Test
    void delete_existingToken_returnsTrue() {
        when(deviceTokenRepository.deleteByToken("fcm-token-existing")).thenReturn(1);
        assertTrue(service.delete("fcm-token-existing"));
    }

    @Test
    void delete_nonExistentToken_returnsFalse() {
        when(deviceTokenRepository.deleteByToken("non-existent")).thenReturn(0);
        assertFalse(service.delete("non-existent"));
    }
}
