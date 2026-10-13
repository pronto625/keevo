package com.keevo.admin.operations.adapter.in.rest;

import com.keevo.admin.operations.adapter.in.rest.dto.*;
import com.keevo.admin.operations.application.service.AdminOperationsService;
import com.keevo.admin.operations.domain.port.in.OperationsPeriodQuery;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AdminOperationsController — REST adapter for operational activity monitoring (Story 9.7).
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class (period parsing/validation lives in the service)
 * - SUPER_ADMIN role checked explicitly via {@link CurrentActor#requireSuperAdmin()} (second barrier
 *   behind the SecurityConfig URL rule)
 * - Read-only: no write endpoint
 */
@Tag(name = "Admin — Operations", description = "Super Admin operational activity monitoring")
@RestController
@RequestMapping("/api/v1/admin/operations")
public class AdminOperationsController {

    private final AdminOperationsService adminOperationsService;

    public AdminOperationsController(AdminOperationsService adminOperationsService) {
        this.adminOperationsService = adminOperationsService;
    }

    @Operation(summary = "Platform GMV / orders / active and new tenants over a period",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/overview")
    public ResponseEntity<ApiResponseWrapper<OperationsOverviewDto>> getOverview(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(OperationsOverviewDto.from(
                adminOperationsService.getOverview(new OperationsPeriodQuery(period, from, to)))));
    }

    @Operation(summary = "Daily platform GMV series over a period",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/trend")
    public ResponseEntity<ApiResponseWrapper<List<GmvTrendPointDto>>> getTrend(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        CurrentActor.requireSuperAdmin();
        List<GmvTrendPointDto> dtos = adminOperationsService
                .getTrend(new OperationsPeriodQuery(period, from, to)).stream()
                .map(GmvTrendPointDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }

    @Operation(summary = "Per-tenant activity over a period (paged, GMV desc)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/tenants")
    public ResponseEntity<ApiResponseWrapper<TenantActivityPageDto>> listTenantActivity(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(TenantActivityPageDto.from(
                adminOperationsService.listTenantActivity(
                        new OperationsPeriodQuery(period, from, to), page, pageSize))));
    }

    @Operation(summary = "Tenants without sale or audit activity for more than 14 days",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/inactive-tenants")
    public ResponseEntity<ApiResponseWrapper<List<InactiveTenantDto>>> listInactiveTenants() {
        CurrentActor.requireSuperAdmin();
        List<InactiveTenantDto> dtos = adminOperationsService.listInactiveTenants().stream()
                .map(InactiveTenantDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(dtos));
    }
}
