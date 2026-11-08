package com.keevo.admin.dashboard.adapter.in.rest;

import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.ActivityItem;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.GrowthPoint;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.Overview;
import com.keevo.admin.dashboard.domain.model.PlatformDashboardViews.TopAccount;
import com.keevo.admin.dashboard.domain.port.in.PlatformDashboardUseCase;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
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
 * Story 20.13 — super-admin platform dashboard inherited from Bref ({@code GET /dashboard/overview}, {@code /growth},
 * {@code /top-shops} → {@code /top-accounts}, {@code /activity}). Read-only; SUPER_ADMIN checked explicitly behind the SecurityConfig URL rule;
 * parameter validation in the service.
 */
@Tag(name = "Admin — Platform dashboard", description = "Super Admin platform figures (Bref dashboard)")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminPlatformDashboardController {

    private final PlatformDashboardUseCase useCase;

    public AdminPlatformDashboardController(PlatformDashboardUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "Users, accounts, stores, documents and revenue", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/overview")
    public ResponseEntity<ApiResponseWrapper<Overview>> getOverview() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getOverview()));
    }

    @Operation(summary = "Monthly growth", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/growth")
    public ResponseEntity<ApiResponseWrapper<List<GrowthPoint>>> getGrowth(
            @RequestParam(required = false) String months) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getGrowth(months)));
    }

    @Operation(summary = "Best accounts by revenue", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/top-accounts")
    public ResponseEntity<ApiResponseWrapper<List<TopAccount>>> getTopAccounts(
            @RequestParam(required = false) String limit, @RequestParam(required = false) String days) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getTopAccounts(limit, days)));
    }

    @Operation(summary = "Recent platform events", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/activity")
    public ResponseEntity<ApiResponseWrapper<List<ActivityItem>>> getActivity(
            @RequestParam(required = false) String limit) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.getActivity(limit)));
    }
}
