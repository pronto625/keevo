package com.keevo.messaging.notification.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_tokens")
public class DeviceTokenJpaEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 512, unique = true)
    private String token;

    @Column(nullable = false, length = 20)
    private String platform;

    @Column(name = "device_name", length = 100)
    private String deviceName;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeviceTokenJpaEntity() {}

    public DeviceTokenJpaEntity(UUID id, UUID userId, String token, String platform,
                                 String deviceName, String role, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.token = token;
        this.platform = platform;
        this.deviceName = deviceName;
        this.role = role;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getToken() { return token; }
    public String getPlatform() { return platform; }
    public String getDeviceName() { return deviceName; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public void setRole(String role) { this.role = role; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setPlatform(String platform) { this.platform = platform; }
}
