package com.keevo.admin.analytics.adapter.in.rest;

import com.keevo.admin.analytics.adapter.in.rest.dto.*;
import com.keevo.admin.analytics.application.service.AdminAnalyticsService;
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
 * AdminAnalyticsController — REST adapter for platform analytics (Story 9-4).
 *
 * <p>ZERO business logic (parameter validation lives in the service); SUPER_ADMIN checked explicitly via
 * {@link CurrentActor#requireSuperAdmin()} behind the SecurityConfig URL rule; read-only.
 */
@Tag(name = "Admin — Analytics", description = "Super Admin platform analytics")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/analytics")
public class AdminAnalyticsController {

    private final AdminAnalyticsService service;

    public AdminAnalyticsController(AdminAnalyticsService service) {
        this.service = service;
    }

    @Operation(summary = "DAU/MAU and platform totals", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/overview")
    public ResponseEntity<ApiResponseWrapper<AnalyticsOverviewDto>> getOverview() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(AnalyticsOverviewDto.from(service.getOverview())));
    }

    @Operation(summary = "Monthly growth curves", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/growth")
    public ResponseEntity<ApiResponseWrapper<List<GrowthPointDto>>> getGrowth(
            @RequestParam(required = false) String months) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                service.getGrowth(months).stream().map(GrowthPointDto::from).toList()));
    }

    @Operation(summary = "Tenants per country (owner phone calling code)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/geography")
    public ResponseEntity<ApiResponseWrapper<List<CountryCountDto>>> getGeography() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                service.getGeography().stream().map(CountryCountDto::from).toList()));
    }

    @Operation(summary = "Weekly tenant cohort retention", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/retention")
    public ResponseEntity<ApiResponseWrapper<RetentionReportDto>> getRetention(
            @RequestParam(required = false) String weeks) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(RetentionReportDto.from(service.getRetention(weeks))));
    }

    @Operation(summary = "Top 20 stores by GMV over a range", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/top-stores")
    public ResponseEntity<ApiResponseWrapper<TopStoresReportDto>> getTopStores(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(TopStoresReportDto.from(service.getTopStores(from, to))));
    }

    @Operation(summary = "Feature adoption among active tenants (30 days)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/adoption")
    public ResponseEntity<ApiResponseWrapper<AdoptionReportDto>> getAdoption() {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(AdoptionReportDto.from(service.getAdoption())));
    }
}
