package com.keevo.messaging.notification.application.service;

import com.keevo.messaging.notification.domain.model.DevicePlatform;
import com.keevo.messaging.notification.domain.model.DeviceToken;
import com.keevo.messaging.notification.domain.port.in.RegisterDeviceTokenCommand;
import com.keevo.messaging.notification.domain.port.in.RegisterDeviceTokenUseCase;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegisterDeviceTokenService implements RegisterDeviceTokenUseCase {

    private final DeviceTokenRepository deviceTokenRepository;

    public RegisterDeviceTokenService(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    @Override
    public void register(RegisterDeviceTokenCommand command) {
        if (command.token() == null || command.token().isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "FCM token must not be blank");
        }

        DevicePlatform platform;
        try {
            platform = DevicePlatform.valueOf(command.platform());
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid device platform: " + command.platform());
        }

        Optional<DeviceToken> existing = deviceTokenRepository.findByToken(command.token());

        if (existing.isPresent()) {
            DeviceToken updated = new DeviceToken(
                    existing.get().id(),
                    command.actorId(),
                    command.token(),
                    platform,
                    command.deviceName(),
                    command.actorRole(),
                    existing.get().createdAt(),
                    Instant.now());
            deviceTokenRepository.upsert(updated);
        } else {
            DeviceToken newToken = new DeviceToken(
                    UUID.randomUUID(),
                    command.actorId(),
                    command.token(),
                    platform,
                    command.deviceName(),
                    command.actorRole(),
                    Instant.now(),
                    Instant.now());
            deviceTokenRepository.save(newToken);
        }
    }
}
