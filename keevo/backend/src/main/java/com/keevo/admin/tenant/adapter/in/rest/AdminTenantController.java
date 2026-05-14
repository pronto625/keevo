package com.keevo.admin.tenant.adapter.in.rest;

import com.keevo.admin.tenant.adapter.in.rest.dto.AdminTenantDetailDto;
import com.keevo.admin.tenant.adapter.in.rest.dto.TenantListResponse;
import com.keevo.admin.tenant.application.service.AdminTenantService;
import com.keevo.admin.tenant.domain.model.AdminTenantDetail;
import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import com.keevo.admin.tenant.domain.port.in.GetTenantDetailQuery;
import com.keevo.admin.tenant.domain.port.in.ListTenantsQuery;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * AdminTenantController — REST adapter for Super Admin tenant management.
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class
 * - SUPER_ADMIN role checked explicitly via {@link #requireSuperAdmin()}
 */
@Tag(name = "Admin — Tenants", description = "Super Admin tenant management")
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
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant registeredFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant registeredTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant lastActivityFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant lastActivityTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int pageSize) {

        requireSuperAdmin();

        ListTenantsQuery query = new ListTenantsQuery(
                search, plan, status,
                registeredFrom, registeredTo,
                lastActivityFrom, lastActivityTo,
                page, Math.min(pageSize, 100)
        );

        Page<AdminTenantListItem> result = adminTenantService.execute(query);
        return ResponseEntity.ok(ApiResponseWrapper.ok(TenantListResponse.from(result)));
    }

    @Operation(summary = "Get tenant detail", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{tenantId}/detail")
    public ResponseEntity<ApiResponseWrapper<AdminTenantDetailDto>> getTenantDetail(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "0") int auditPage) {

        requireSuperAdmin();

        AdminTenantDetail detail = adminTenantService.execute(
                new GetTenantDetailQuery(tenantId, Math.max(0, auditPage)));

        return ResponseEntity.ok(ApiResponseWrapper.ok(
                AdminTenantDetailDto.from(detail, auditPage)));
    }

    @Operation(summary = "Cancel scheduled deletion", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{tenantId}/cancel-deletion")
    public ResponseEntity<ApiResponseWrapper<Void>> cancelDeletion(@PathVariable UUID tenantId) {
        requireSuperAdmin();
        adminTenantService.cancelDeletion(tenantId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Force immediate tenant deletion", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{tenantId}/force-delete")
    public ResponseEntity<ApiResponseWrapper<Void>> forceDelete(@PathVariable UUID tenantId) {
        requireSuperAdmin();
        adminTenantService.forceDelete(tenantId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
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
