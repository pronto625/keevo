package com.keevo.admin.tenant.adapter.in.rest;

import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.admin.tenant.adapter.in.rest.dto.AdminTenantDetailDto;
import com.keevo.admin.tenant.adapter.in.rest.dto.TenantListResponse;
import com.keevo.admin.tenant.application.service.AdminTenantService;
import com.keevo.admin.tenant.domain.model.AdminTenantDetail;
import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery;
import com.keevo.admin.tenant.domain.port.in.ListTenantsQuery;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.EntitlementExempt;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * AdminTenantController — REST adapter for Super Admin tenant management.
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class
 * - SUPER_ADMIN role checked explicitly via {@link CurrentActor#requireSuperAdmin()} (second barrier behind the SecurityConfig URL rule)
 */
@Tag(name = "Admin — Tenants", description = "Super Admin tenant management")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class AdminTenantController {

    private final AdminTenantService adminTenantService;

    public AdminTenantController(AdminTenantService adminTenantService) {
        this.adminTenantService = adminTenantService;
    }

    @Operation(summary = "List all tenants (paginated)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<TenantListResponse>> listTenants(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String plan,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String registeredFrom,
            @RequestParam(required = false) String registeredTo,
            @RequestParam(required = false) String lastActivityFrom,
            @RequestParam(required = false) String lastActivityTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {

        CurrentActor.requireSuperAdmin();

        ListTenantsQuery query = new ListTenantsQuery(
                search, plan, status,
                bound("registeredFrom", registeredFrom, false), bound("registeredTo", registeredTo, true),
                bound("lastActivityFrom", lastActivityFrom, false), bound("lastActivityTo", lastActivityTo, true),
                page, Math.min(pageSize, 100)
        );

        Page<AdminTenantListItem> result = adminTenantService.execute(query);
        return ResponseEntity.ok(ApiResponseWrapper.ok(TenantListResponse.from(result)));
    }

    /**
     * Date filter bound: an ISO instant ({@code 2026-10-01T00:00:00Z}) or a calendar day
     * ({@code 2026-10-01}, as sent by the dashboard date inputs) taken in Africa/Douala — start of
     * day for a lower bound, end of day for an upper bound (inclusive). Anything else → 422.
     */
    static Instant bound(String name, String raw, boolean endOfDay) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        try {
            if (v.length() == 10) {
                LocalDate day = LocalDate.parse(v);
                return (endOfDay ? day.plusDays(1).atStartOfDay(WAT).minusNanos(1) : day.atStartOfDay(WAT))
                        .toInstant();
            }
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, name + " must be an ISO date or date-time");
        }
    }

    private static final ZoneId WAT = ZoneId.of("Africa/Douala");

    @Operation(summary = "Get tenant detail", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{tenantId}/detail")
    public ResponseEntity<ApiResponseWrapper<AdminTenantDetailDto>> getTenantDetail(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "0") int auditPage) {

        CurrentActor.requireSuperAdmin();

        AdminTenantDetail detail = adminTenantService.execute(
                new GetTenantDetailQuery(tenantId, Math.max(0, auditPage)));

        return ResponseEntity.ok(ApiResponseWrapper.ok(
                AdminTenantDetailDto.from(detail, auditPage)));
    }

    @Operation(summary = "Cancel scheduled deletion", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{tenantId}/cancel-deletion")
    public ResponseEntity<ApiResponseWrapper<Void>> cancelDeletion(@PathVariable UUID tenantId) {
        CurrentActor.requireSuperAdmin();
        adminTenantService.cancelDeletion(tenantId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Force immediate tenant deletion", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{tenantId}/force-delete")
    public ResponseEntity<ApiResponseWrapper<Void>> forceDelete(@PathVariable UUID tenantId) {
        CurrentActor.requireSuperAdmin();
        adminTenantService.forceDelete(tenantId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }
}
