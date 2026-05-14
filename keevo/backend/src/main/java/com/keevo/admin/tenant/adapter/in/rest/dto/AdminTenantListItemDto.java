package com.keevo.admin.tenant.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * AdminTenantListItemDto — serializable DTO for one tenant row in the admin list.
 */
public record AdminTenantListItemDto(
        UUID id,
        String code,
        String name,
        String ownerPhone,
        String plan,
        String status,
        Instant registeredAt,
        Instant lastActivityAt,
        int storeCount,
        int employeeCount,
        Instant deletionScheduledAt
) {
    public static AdminTenantListItemDto from(com.keevo.admin.tenant.domain.model.AdminTenantListItem item) {
        return new AdminTenantListItemDto(
                item.id(), item.code(), item.name(), item.ownerPhone(),
                item.plan(), item.status(),
                item.registeredAt(), item.lastActivityAt(),
                item.storeCount(), item.employeeCount(),
                item.deletionScheduledAt()
        );
    }
}
