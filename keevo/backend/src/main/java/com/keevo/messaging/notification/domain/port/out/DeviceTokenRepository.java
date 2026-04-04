package com.keevo.messaging.notification.domain.port.out;

import com.keevo.messaging.notification.domain.model.DeviceToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository {
    DeviceToken save(DeviceToken deviceToken);
    Optional<DeviceToken> findByToken(String token);
    List<DeviceToken> findByUserIdAndRole(UUID userId, String role);
    List<DeviceToken> findOwnerTokens();
    int deleteByToken(String token);
    DeviceToken upsert(DeviceToken deviceToken);
}
