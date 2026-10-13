package com.keevo.admin.revenue.adapter.in.rest;

import com.keevo.admin.revenue.adapter.in.rest.dto.ChurnReportDto;
import com.keevo.admin.revenue.adapter.in.rest.dto.RevenueSummaryDto;
import com.keevo.admin.revenue.adapter.in.rest.dto.RevenueTrendPointDto;
import com.keevo.admin.revenue.application.service.AdminRevenueService;
import com.keevo.admin.revenue.domain.model.ChurnReport;
import com.keevo.admin.revenue.domain.port.in.RevenueRangeQuery;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AdminRevenueController — super admin revenue dashboard (Story 9-3, FR79).
 *
 * <p>Read-only. SUPER_ADMIN checked explicitly via {@link CurrentActor#requireSuperAdmin()} (second barrier
 * behind the SecurityConfig URL rule). Parameter validation lives in the service (422 VALIDATION_ERROR).
 */
@Tag(name = "Admin — Revenue", description = "Super Admin revenue dashboard (MRR, churn, conversion)")
@RestController
@RequestMapping("/api/v1/admin/revenue")
public class AdminRevenueController {

    private final AdminRevenueService revenueService;

    public AdminRevenueController(AdminRevenueService revenueService) {
        this.revenueService = revenueService;
    }

    @Operation(summary = "Revenue KPIs: MRR/ARR today, paying/free/trial tenants, range churn, conversion, collected",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/summary")
    public ResponseEntity<ApiResponseWrapper<RevenueSummaryDto>> getSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(RevenueSummaryDto.from(
                revenueService.getSummary(new RevenueRangeQuery(from, to)))));
    }

    @Operation(summary = "MRR, paying tenants, conversions and churn of the last 12 months",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/trend")
    public ResponseEntity<ApiResponseWrapper<List<RevenueTrendPointDto>>> getTrend() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                revenueService.getTrend().stream().map(RevenueTrendPointDto::from).toList()));
    }

    @Operation(summary = "Tenants lost in a month (YYYY-MM, default current month)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/churn")
    public ResponseEntity<ApiResponseWrapper<ChurnReportDto>> getChurn(
            @RequestParam(required = false) String month) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(ChurnReportDto.from(revenueService.getChurn(month))));
    }

    @Operation(summary = "Churn of a month as CSV (French columns, no ids)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/churn/export")
    public ResponseEntity<byte[]> exportChurn(@RequestParam(required = false) String month) {
        CurrentActor.requireSuperAdmin();
        ChurnReport report = revenueService.getChurn(month);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ChurnCsvWriter.filename(report) + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .body(ChurnCsvWriter.write(report));
    }
}
