package com.keevo.reporting.report.adapter.in.rest;

import com.keevo.commerce.sale.domain.model.DayClosure;
import com.keevo.commerce.sale.domain.port.out.DayClosureRepository;
import com.keevo.identity.onboarding.domain.model.ReportChannel;
import com.keevo.reporting.report.adapter.in.rest.dto.ReportListResponseDto;
import com.keevo.reporting.report.adapter.in.rest.dto.ReportResponseDto;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase.WeeklyReportCommand;
import com.keevo.reporting.report.domain.port.in.GetReportHistoryUseCase;
import com.keevo.reporting.report.domain.port.in.GetReportHistoryUseCase.ReportHistoryQuery;
import com.keevo.reporting.report.domain.port.in.ResendReportUseCase;
import com.keevo.reporting.report.domain.port.in.ResendReportUseCase.ResendReportCommand;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.store.store.domain.port.out.StoreRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * ReportController — REST adapter for end-of-day report history.
 * Story 7.2 — Task 8.1 | Updated: multi-vendor support (Option A).
 *
 * <pre>
 * GET  /api/v1/reports                → paginated history
 *   OWNER: all reports (or filter by storeId / actorId)
 *   EMPLOYEE: only their actorId reports (actorId param ignored, forced to JWT actorId)
 * GET  /api/v1/reports/{id}             → single report detail
 * POST /api/v1/reports/{id}/resend      → re-trigger WhatsApp delivery (OWNER only)
 * POST /api/v1/reports/trigger-weekly   → manual trigger for weekly report (OWNER only)
 * POST /api/v1/reports/trigger-daily    → re-generate daily report for a store+date (OWNER only, recovery)
 * </pre>
 */
@Tag(name = "Reports", description = "End-of-day report history and resend")
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private final GetReportHistoryUseCase getReportHistoryUseCase;
    private final ResendReportUseCase resendReportUseCase;
    private final GenerateWeeklyReportUseCase weeklyReportGenerator;
    private final GenerateEndOfDayReportUseCase dailyReportGenerator;
    private final StoreRepository storeRepository;
    private final DayClosureRepository dayClosureRepository;

    public ReportController(GetReportHistoryUseCase getReportHistoryUseCase,
                             ResendReportUseCase resendReportUseCase,
                             GenerateWeeklyReportUseCase weeklyReportGenerator,
                             GenerateEndOfDayReportUseCase dailyReportGenerator,
                             StoreRepository storeRepository,
                             DayClosureRepository dayClosureRepository) {
        this.getReportHistoryUseCase = getReportHistoryUseCase;
        this.resendReportUseCase = resendReportUseCase;
        this.weeklyReportGenerator = weeklyReportGenerator;
        this.dailyReportGenerator = dailyReportGenerator;
        this.storeRepository = storeRepository;
        this.dayClosureRepository = dayClosureRepository;
    }

    @Operation(summary = "Get paginated report history. OWNER: all or filtered. EMPLOYEE: own only.")
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<ReportListResponseDto>> getHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) UUID actorId) {

        Authentication auth = requireAuthentication();
        String role = roleFromAuth(auth);
        String tenantId = TenantContext.getCurrentTenant();

        ReportType reportType = type != null ? ReportType.valueOf(type.toUpperCase()) : null;

        UUID effectiveActorId;
        if ("OWNER".equals(role)) {
            // OWNER: can optionally filter by actorId or storeId
            effectiveActorId = actorId;
        } else {
            // EMPLOYEE: always forced to their own actorId
            effectiveActorId = principalId(auth);
        }

        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "reportDate"));
        var query = new ReportHistoryQuery(tenantId, storeId, effectiveActorId, reportType, pageable);
        var result = getReportHistoryUseCase.getReportHistory(query);
        return ResponseEntity.ok(ApiResponseWrapper.ok(ReportListResponseDto.from(result)));
    }

    @Operation(summary = "Get a single report by ID")
    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponseWrapper<ReportResponseDto>> getById(
            @PathVariable UUID reportId) {
        Authentication auth = requireAuthentication();
        String role = roleFromAuth(auth);
        String tenantId = TenantContext.getCurrentTenant();
        EndOfDayReport report = getReportHistoryUseCase.getReportById(reportId, tenantId)
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Report not found"));

        // EMPLOYEE can only see their own reports
        if (!"OWNER".equals(role)) {
            UUID userId = principalId(auth);
            if (!userId.equals(report.getActorId())) {
                throw new DomainException(ErrorCode.FORBIDDEN, "Access denied");
            }
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(ReportResponseDto.from(report)));
    }

    @Operation(summary = "Re-generate daily report for a store on a given date (OWNER only, recovery)")
    @PostMapping("/trigger-daily")
    public ResponseEntity<ApiResponseWrapper<ReportResponseDto>> triggerDaily(
            @RequestParam UUID storeId,
            @RequestParam(required = false) String date) {
        requireOwnerOrForbid();
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate targetDate = date != null ? LocalDate.parse(date) : LocalDate.now(WAT);

        java.util.List<DayClosure> closures = dayClosureRepository.findByStoreIdAndDate(storeId, targetDate);
        if (closures.isEmpty()) {
            throw new DomainException(ErrorCode.NOT_FOUND,
                    "Aucune clôture trouvée pour storeId=" + storeId + " date=" + targetDate);
        }
        DayClosure closure = closures.get(0);

        var cmd = new GenerateReportCommand(
                storeId,
                null,                       // store-level report (owner only)
                tenantId,
                closure.isAutomatic(),
                closure.getClosedAt(),
                null,                       // windowStart=null → defaults to start-of-day WAT
                ReportChannel.IN_APP_ONLY   // recovery: persist only, no WhatsApp re-send
        );
        EndOfDayReport report = dailyReportGenerator.generateReport(cmd);
        return ResponseEntity.ok(ApiResponseWrapper.ok(ReportResponseDto.from(report)));
    }

    @Operation(summary = "Manually trigger weekly report generation for all active stores (OWNER only)")
    @PostMapping("/trigger-weekly")
    public ResponseEntity<ApiResponseWrapper<Void>> triggerWeekly() {
        requireOwnerOrForbid();
        String tenantId = TenantContext.getCurrentTenant();
        LocalDate todayWAT = LocalDate.now(WAT);
        Instant weekStart  = todayWAT.minusDays(6).atStartOfDay(WAT).toInstant();
        Instant weekEnd    = todayWAT.atTime(23, 59, 59).atZone(WAT).toInstant();

        storeRepository.findAllActive().forEach(store -> {
            var cmd = new WeeklyReportCommand(
                    store.id(), tenantId, false, weekStart, weekEnd, null);
            weeklyReportGenerator.generateWeeklyReport(cmd);
        });
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    @Operation(summary = "Resend a report via WhatsApp")
    @PostMapping("/{reportId}/resend")
    public ResponseEntity<ApiResponseWrapper<Void>> resend(@PathVariable UUID reportId) {
        requireOwnerOrForbid();
        String tenantId = TenantContext.getCurrentTenant();
        resendReportUseCase.resendReport(new ResendReportCommand(reportId, tenantId));
        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Authentication requireAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return auth;
    }

    private String roleFromAuth(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .map(a -> a.substring(5))
                .orElse("EMPLOYEE");
    }

    private UUID principalId(Authentication auth) {
        try {
            return UUID.fromString(auth.getName());
        } catch (Exception e) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Invalid principal identity");
        }
    }

    private void requireOwnerOrForbid() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "Authentication required");
        }
        if (auth.getAuthorities().stream()
                .noneMatch(a -> "ROLE_OWNER".equals(a.getAuthority()))) {
            throw new DomainException(ErrorCode.FORBIDDEN, "OWNER role required");
        }
    }
}
