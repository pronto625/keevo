package com.keevo.reporting.dashboard.adapter.in.rest;

import com.keevo.reporting.dashboard.adapter.in.rest.dto.DashboardSummaryResponseDto;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase.GetDashboardSummaryQuery;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DashboardController — REST adapter for OWNER dashboard summary.
 *
 * <pre>
 * GET /api/v1/dashboard/summary → aggregated dashboard stats (OWNER only)
 * </pre>
 *
 * Story 7.1, Task 2.
 */
@Tag(name = "Dashboard", description = "Owner dashboard statistics")
@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasRole('OWNER')")
public class DashboardController {

    private final GetDashboardSummaryUseCase getDashboardSummaryUseCase;

    public DashboardController(GetDashboardSummaryUseCase getDashboardSummaryUseCase) {
        this.getDashboardSummaryUseCase = getDashboardSummaryUseCase;
    }

    @Operation(summary = "Get aggregated dashboard summary for the OWNER morning view")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponseWrapper<DashboardSummaryResponseDto>> getSummary() {
        String tenantId = TenantContext.getCurrentTenant();
        var summary = getDashboardSummaryUseCase.execute(new GetDashboardSummaryQuery(tenantId));
        return ResponseEntity.ok(ApiResponseWrapper.ok(DashboardSummaryResponseDto.from(summary)));
    }
}
