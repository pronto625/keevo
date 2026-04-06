package com.keevo.identity.onboarding.adapter.in.web;

import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.identity.onboarding.domain.model.StockAlertChannel;
import com.keevo.identity.onboarding.domain.port.in.SendTestReportUseCase;
import com.keevo.identity.onboarding.domain.port.in.UpdateReportPreferencesCommand;
import com.keevo.identity.onboarding.domain.port.in.UpdateReportPreferencesUseCase;
import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TenantPreferencesController — REST API for tenant preferences and store listing.
 * Story 7.5 — added PUT /report-preferences and POST /report-test (OWNER only).
 */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantPreferencesController {

    private final TenantPreferencesRepository tenantPreferencesRepository;
    private final JdbcTemplate jdbcTemplate;
    private final UpdateReportPreferencesUseCase updateReportPreferencesUseCase;
    private final SendTestReportUseCase sendTestReportUseCase;

    public TenantPreferencesController(TenantPreferencesRepository tenantPreferencesRepository,
                                       JdbcTemplate jdbcTemplate,
                                       UpdateReportPreferencesUseCase updateReportPreferencesUseCase,
                                       SendTestReportUseCase sendTestReportUseCase) {
        this.tenantPreferencesRepository = tenantPreferencesRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.updateReportPreferencesUseCase = updateReportPreferencesUseCase;
        this.sendTestReportUseCase = sendTestReportUseCase;
    }

    /**
     * Get current tenant preferences including sector type.
     * GET /api/v1/tenant/preferences
     */
    @GetMapping("/preferences")
    public ResponseEntity<ApiResponseWrapper<TenantPreferencesResponseDto>> getPreferences() {
        return tenantPreferencesRepository.findByCurrentTenant()
            .map(prefs -> {
                TenantPreferencesResponseDto dto = TenantPreferencesResponseDto.fromDomain(prefs);
                return ResponseEntity.ok(ApiResponseWrapper.ok(dto));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update report preferences for the current tenant. OWNER only.
     * PUT /api/v1/tenant/report-preferences
     */
    @PutMapping("/report-preferences")
    public ResponseEntity<ApiResponseWrapper<TenantPreferencesResponseDto>> updateReportPreferences(
            @Valid @RequestBody UpdateReportPreferencesRequestDto request) {

        requireAuthentication();
        requireOwnerOrForbid();

        UpdateReportPreferencesCommand command = new UpdateReportPreferencesCommand(
            request.eodReportEnabled(),
            ReportChannel.fromString(request.eodReportChannel()),
            request.eodReportTime(),
            request.weeklyReportEnabled(),
            request.weeklyReportDay(),
            request.weeklyReportTime(),
            ReportChannel.fromString(request.weeklyReportChannel()),
            request.inventoryReportEnabled(),
            ReportChannel.fromString(request.inventoryReportChannel()),
            request.stockAlertEnabled() != null && request.stockAlertEnabled(),
            StockAlertChannel.fromString(request.stockAlertChannel()),
            request.trendNotificationEnabled()
        );

        var updated = updateReportPreferencesUseCase.update(command);
        return ResponseEntity.ok(ApiResponseWrapper.ok(TenantPreferencesResponseDto.fromDomain(updated)));
    }

    /**
     * Send a test WhatsApp report to the OWNER. OWNER only.
     * POST /api/v1/tenant/report-test
     */
    @PostMapping("/report-test")
    public ResponseEntity<ApiResponseWrapper<SendTestReportUseCase.TestReportResult>> sendTestReport() {
        requireAuthentication();
        requireOwnerOrForbid();
        String tenantId = TenantContext.getCurrentTenant();
        SendTestReportUseCase.TestReportResult result = sendTestReportUseCase.sendTestReport(tenantId);
        return ResponseEntity.ok(ApiResponseWrapper.ok(result));
    }

    /**
     * List active stores for the current tenant.
     * GET /api/v1/tenant/stores
     *
     * <p>Uses JdbcTemplate with a fully-qualified schema name so that the query
     * works outside a JPA-managed transaction (no search_path dependency).
     * This lightweight endpoint lets Flutter resolve valid store UUIDs before
     * calling stock entry / adjust endpoints.
     *
     * <p>Epic 3 will supersede this with a full StoreRepository.
     */
    @GetMapping("/stores")
    public ResponseEntity<ApiResponseWrapper<List<StoreDto>>> getStores() {
        String schema = TenantContext.getCurrentTenant();
        List<StoreDto> stores = jdbcTemplate.query(
                "SELECT id::text, name FROM \"" + schema + "\".stores WHERE is_active = TRUE ORDER BY created_at ASC",
                (rs, rowNum) -> new StoreDto(rs.getString("id"), rs.getString("name"))
        );
        return ResponseEntity.ok(ApiResponseWrapper.ok(stores));
    }

    // ── Auth helpers (same pattern as ReportController) ────────────────────────

    private Authentication requireAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return auth;
    }

    private void requireOwnerOrForbid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "ROLE_OWNER".equals(a.getAuthority()))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "OWNER role required");
        }
    }
}
