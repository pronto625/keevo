package com.keevo.admin.tenant.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * AdminTenantListItemDto — serializable DTO for one tenant row in the admin list.
 *
 * <p>The tenant code is deliberately not exposed (« no technical data in UI »); {@code id} is only
 * used by the dashboard to open the drawer and call actions, never displayed.
 */
public record AdminTenantListItemDto(
        UUID id,
        String name,
        String ownerName,
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
                item.id(), item.name(), item.ownerName(), item.ownerPhone(),
                item.plan(), item.status(),
                item.registeredAt(), item.lastActivityAt(),
                item.storeCount(), item.employeeCount(),
                item.deletionScheduledAt()
        );
    }
}
