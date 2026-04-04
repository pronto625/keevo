package com.keevo.messaging.notification.adapter.out.persistence.jpa;

import com.keevo.messaging.notification.adapter.out.persistence.entity.DeviceTokenJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceTokenSpringRepository extends JpaRepository<DeviceTokenJpaEntity, UUID> {

    Optional<DeviceTokenJpaEntity> findByToken(String token);

    @Query("SELECT d FROM DeviceTokenJpaEntity d WHERE d.role = :role")
    List<DeviceTokenJpaEntity> findByRole(@Param("role") String role);

    List<DeviceTokenJpaEntity> findByUserIdAndRole(UUID userId, String role);

    @Modifying
    @Transactional
    @Query("DELETE FROM DeviceTokenJpaEntity d WHERE d.token = :token")
    int deleteByTokenValue(@Param("token") String token);
}
