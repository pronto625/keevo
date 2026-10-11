package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.port.in.DeleteDeviceTokenUseCase;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DeleteDeviceTokenService implements DeleteDeviceTokenUseCase {

    private final DeviceTokenRepository deviceTokenRepository;

    public DeleteDeviceTokenService(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    @Override
    public boolean delete(UUID userId, String token) {
        return deviceTokenRepository.deleteByTokenAndUserId(token, userId) > 0;
    }
}
