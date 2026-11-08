package com.keevo.finance.summary.adapter.in.rest;

import com.keevo.finance.module.FinanceModule;
import com.keevo.finance.summary.application.FinanceAccess;
import com.keevo.finance.summary.application.FinanceSummaryService;
import com.keevo.finance.summary.domain.model.FinancePeriods;
import com.keevo.finance.summary.domain.model.FinanceSummary;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.RequiresEntitlement;
import com.keevo.shared.module.RequiresPermission;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Finance summary — Story 20.11 (Bref {@code GET /v1/finance/summary}, FUS-FR26). Gated by the {@code finance} module
 * ({@code @RequiresEntitlement}, READ) and {@code FINANCE_VIEW} (OWNER only).
 *
 * <pre>
 * GET /api/v1/finance/summary?period=yyyy-MM|yyyy            → that month / year, vs the previous month / year
 * GET /api/v1/finance/summary?startDate=yyyy-MM-dd&endDate=… → Bref's parameters (default: the current month, WAT),
 *                                                              vs the window of the same length just before
 *                            &storeId=                       → one store (OWNER; unknown → VALIDATION_ERROR)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {

    private final FinanceSummaryService summaries;
    private final Clock clock;

    public FinanceController(FinanceSummaryService summaries, Clock clock) {
        this.summaries = summaries;
        this.clock = clock;
    }

    /** The summary of the period: revenue excl. VAT, expenses, result, margin, trend vs the comparison period, series. */
    @RequiresPermission(FinanceModule.FINANCE_VIEW)
    @RequiresEntitlement(value = FinanceModule.ID, access = RequiresEntitlement.Access.READ)
    @GetMapping("/summary")
    public ResponseEntity<ApiResponseWrapper<FinanceSummary>> summary(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) UUID storeId) {
        FinancePeriods.Resolved resolved;
        try {
            resolved = FinancePeriods.resolve(period, startDate, endDate, LocalDate.now(clock.withZone(KeevoZones.WAT)));
        } catch (FinancePeriods.InvalidPeriodException e) {
            throw new InvalidRequestParameterException(e.parameter(), "invalid finance period", e.getMessage());
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(summaries.summary(resolved, storeId, access(),
                TenantContext.getCurrentTenant())));
    }

    private static FinanceAccess access() {
        return CurrentActor.isOwner() ? FinanceAccess.OWNER : FinanceAccess.member(CurrentActor.assignedStoreIdOrNull());
    }
}
