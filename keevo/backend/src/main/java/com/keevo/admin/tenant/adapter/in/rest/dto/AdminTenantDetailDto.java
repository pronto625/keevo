package com.keevo.admin.tenant.adapter.in.rest.dto;

import com.keevo.admin.tenant.domain.model.*;

import java.time.Instant;
import java.util.List;

/**
 * AdminTenantDetailDto — serializable DTO for the full tenant detail drawer.
 */
public record AdminTenantDetailDto(
        AdminTenantListItemDto tenant,
        List<StoreInfoDto> stores,
        List<EmployeeInfoDto> employees,
        AuditPageDto auditLog
) {

    public record StoreInfoDto(String id, String name, String type, long totalRevenue) {
        public static StoreInfoDto from(AdminStoreInfo s) {
            return new StoreInfoDto(s.id(), s.name(), s.type(), s.totalRevenue());
        }
    }

    public record EmployeeInfoDto(String id, String name, String role, Instant lastLoginAt) {
        public static EmployeeInfoDto from(AdminEmployeeInfo e) {
            return new EmployeeInfoDto(e.id(), e.name(), e.role(), e.lastLoginAt());
        }
    }

    public record AuditEventDto(String id, String eventType, String actorId,
                                Instant occurredAt, String details) {
        public static AuditEventDto from(AdminAuditEvent a) {
            return new AuditEventDto(a.id(), a.eventType(), a.actorId(), a.occurredAt(), a.details());
        }
    }

    public record AuditPageDto(List<AuditEventDto> events, long total, int page) {}

    public static AdminTenantDetailDto from(AdminTenantDetail detail, int auditPage) {
        List<StoreInfoDto> stores = detail.stores().stream().map(StoreInfoDto::from).toList();
        List<EmployeeInfoDto> employees = detail.employees().stream().map(EmployeeInfoDto::from).toList();
        List<AuditEventDto> events = detail.auditEvents().stream().map(AuditEventDto::from).toList();
        AuditPageDto auditLog = new AuditPageDto(events, detail.auditTotalCount(), auditPage);
        return new AdminTenantDetailDto(
                AdminTenantListItemDto.from(detail.summary()),
                stores, employees, auditLog
        );
    }
}
