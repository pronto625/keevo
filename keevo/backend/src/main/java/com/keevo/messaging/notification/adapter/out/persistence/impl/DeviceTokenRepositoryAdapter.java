package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.keevo.messaging.notification.adapter.out.persistence.entity.DeviceTokenJpaEntity;
import com.keevo.messaging.notification.adapter.out.persistence.jpa.DeviceTokenSpringRepository;
import com.keevo.messaging.notification.domain.model.DevicePlatform;
import com.keevo.messaging.notification.domain.model.DeviceToken;
import com.keevo.messaging.notification.domain.port.out.DeviceTokenRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class DeviceTokenRepositoryAdapter implements DeviceTokenRepository {

    private final DeviceTokenSpringRepository springRepo;

    public DeviceTokenRepositoryAdapter(DeviceTokenSpringRepository springRepo) {
        this.springRepo = springRepo;
    }

    @Override
    public DeviceToken save(DeviceToken deviceToken) {
        DeviceTokenJpaEntity entity = toEntity(deviceToken);
        DeviceTokenJpaEntity saved = springRepo.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<DeviceToken> findByToken(String token) {
        return springRepo.findByToken(token).map(this::toDomain);
    }

    @Override
    public List<DeviceToken> findByUserIdAndRole(UUID userId, String role) {
        return springRepo.findByUserIdAndRole(userId, role)
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<DeviceToken> findOwnerTokens() {
        return springRepo.findByRole("OWNER")
                .stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public int deleteByToken(String token) {
        return springRepo.deleteByTokenValue(token);
    }

    @Override
    public DeviceToken upsert(DeviceToken deviceToken) {
        // Upsert: find existing by token, update fields
        Optional<DeviceTokenJpaEntity> existing = springRepo.findByToken(deviceToken.token());
        if (existing.isPresent()) {
            DeviceTokenJpaEntity entity = existing.get();
            entity.setUserId(deviceToken.userId());
            entity.setPlatform(deviceToken.platform().name());
            entity.setDeviceName(deviceToken.deviceName());
            entity.setRole(deviceToken.role());
            entity.setUpdatedAt(deviceToken.updatedAt());
            return toDomain(springRepo.save(entity));
        }
        return save(deviceToken);
    }

    private DeviceTokenJpaEntity toEntity(DeviceToken dt) {
        return new DeviceTokenJpaEntity(
                dt.id(), dt.userId(), dt.token(), dt.platform().name(),
                dt.deviceName(), dt.role(), dt.createdAt(), dt.updatedAt());
    }

    private DeviceToken toDomain(DeviceTokenJpaEntity e) {
        return new DeviceToken(
                e.getId(), e.getUserId(), e.getToken(),
                DevicePlatform.valueOf(e.getPlatform()),
                e.getDeviceName(), e.getRole(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
