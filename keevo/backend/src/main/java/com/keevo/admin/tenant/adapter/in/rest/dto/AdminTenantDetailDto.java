package com.keevo.admin.tenant.adapter.in.rest.dto;

import com.keevo.admin.tenant.domain.model.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * AdminTenantDetailDto — serializable DTO for the full tenant detail drawer.
 */
public record AdminTenantDetailDto(
        AdminTenantListItemDto tenant,
        List<StoreInfoDto> stores,
        List<EmployeeInfoDto> employees,
        AuditPageDto auditLog,
        SubscriptionDto subscription
) {

    /** Story 9-2 — current plan from the tenant schema; null when it could not be read. */
    public record SubscriptionDto(String planType, Instant expiresAt) {}

    public record StoreInfoDto(String id, String name, String type, long totalRevenue) {
        public static StoreInfoDto from(AdminStoreInfo s) {
            return new StoreInfoDto(s.id(), s.name(), s.type(), s.totalRevenue());
        }
    }

    /** Spec 9-1 completion — name + role + last activity day (user_daily_activity); no user id. */
    public record EmployeeInfoDto(String name, String role, LocalDate lastActivityAt) {
        public static EmployeeInfoDto from(AdminEmployeeInfo e) {
            return new EmployeeInfoDto(e.name(), e.role(), e.lastActivityAt());
        }
    }

    /** Spec 9-1 completion — readable audit line: no entity id, no actor id. */
    public record AuditEventDto(String action, String entityType, String entityLabel,
                                String actorName, String storeName, Instant occurredAt) {
        public static AuditEventDto from(AdminAuditEvent a) {
            return new AuditEventDto(a.action(), a.entityType(), a.entityLabel(),
                    a.actorName(), a.storeName(), a.occurredAt());
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
                stores, employees, auditLog,
                detail.plan() != null
                        ? new SubscriptionDto(detail.plan().planType(), detail.plan().expiresAt())
                        : null
        );
    }
}
