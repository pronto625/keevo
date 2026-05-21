package com.keevo.admin.sync_monitoring.adapter.in.rest;

import com.keevo.admin.sync_monitoring.adapter.in.rest.dto.*;
import com.keevo.admin.sync_monitoring.application.service.AdminSyncMonitoringService;
import com.keevo.admin.sync_monitoring.domain.port.in.GetSyncTenantDetailQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * AdminSyncMonitoringController — REST adapter for sync health monitoring.
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class
 * - SUPER_ADMIN role checked explicitly via {@link #requireSuperAdmin()}
 */
@Tag(name = "Admin — Sync Monitoring", description = "Super Admin sync health monitoring")
@RestController
@RequestMapping("/api/v1/admin/sync")
public class AdminSyncMonitoringController {

    private final AdminSyncMonitoringService adminSyncMonitoringService;

    public AdminSyncMonitoringController(AdminSyncMonitoringService adminSyncMonitoringService) {
        this.adminSyncMonitoringService = adminSyncMonitoringService;
    }

    @Operation(summary = "Platform sync overview KPIs", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/overview")
    public ResponseEntity<ApiResponseWrapper<AdminSyncOverviewDto>> getOverview() {
        requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                AdminSyncOverviewDto.from(adminSyncMonitoringService.getSyncOverview())));
    }

    @Operation(summary = "Per-tenant sync health table", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants")
    public ResponseEntity<ApiResponseWrapper<List<AdminTenantSyncHealthDto>>> listTenantSyncHealth() {
        requireSuperAdmin();
        List<AdminTenantSyncHealthDto> dtos = adminSyncMonitoringService.listTenantSyncHealth().stream()
                .map(AdminTenantSyncHealthDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @Operation(summary = "Tenant sync detail (devices + failures + conflicts)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants/{tenantId}/detail")
    public ResponseEntity<ApiResponseWrapper<AdminSyncTenantDetailDto>> getTenantDetail(
            @PathVariable String tenantId) {
        requireSuperAdmin();

        UUID parsedId;
        try {
            parsedId = UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid tenant UUID: " + tenantId);
        }

        return ResponseEntity.ok(ApiResponseWrapper.ok(
                AdminSyncTenantDetailDto.from(
                        adminSyncMonitoringService.execute(new GetSyncTenantDetailQuery(parsedId)))));
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private void requireSuperAdmin() {
        Collection<? extends GrantedAuthority> authorities =
                SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        boolean isSuperAdmin = authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        if (!isSuperAdmin) {
            throw new DomainException(ErrorCode.FORBIDDEN, "SUPER_ADMIN role required");
        }
    }
}
