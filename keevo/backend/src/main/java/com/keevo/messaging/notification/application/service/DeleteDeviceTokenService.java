package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.port.in.DeleteDeviceTokenUseCase;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import org.springframework.stereotype.Service;

@Service
public class DeleteDeviceTokenService implements DeleteDeviceTokenUseCase {

    private final DeviceTokenRepository deviceTokenRepository;

    public DeleteDeviceTokenService(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    @Override
    public boolean delete(String token) {
        return deviceTokenRepository.deleteByToken(token) > 0;
    }
}
