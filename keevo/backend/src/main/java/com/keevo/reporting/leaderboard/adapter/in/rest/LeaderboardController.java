package com.keevo.reporting.leaderboard.adapter.in.rest;

import com.keevo.identity.api.TenantPreferencesQueryPort;
import com.keevo.reporting.leaderboard.adapter.in.rest.dto.SellerLeaderboardEntryDto;
import com.keevo.reporting.leaderboard.application.service.LeaderboardAudience;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase.SellerLeaderboardQuery;
import com.keevo.reporting.profitability.domain.model.RankingMetric;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.RequiresEntitlement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * LeaderboardController — FR44 seller leaderboard (v1s-14-2), OWNER; EMPLOYEE when shared (Story 17.2).
 *
 * <pre>
 * GET /api/v1/reporting/leaderboard?from=YYYY-MM-DD&amp;to=YYYY-MM-DD&amp;storeId=&amp;metric=CA|SALES_COUNT|AVG_BASKET
 * </pre>
 *
 * Missing {@code to} defaults to today in WAT (injected {@link Clock}), missing {@code from} to
 * {@code to}; {@code from > to} → 400
 * {@code VALIDATION_ERROR}; unknown metric → CA.
 *
 * <p>Story 17.2 — EMPLOYEE: 403 {@code FORBIDDEN} while the owner setting {@code leaderboardVisibleToEmployees} is
 * off (default); once on, the ranking is limited to the employee's assigned store (any {@code storeId} parameter
 * is ignored) and anonymised: only the caller's own row keeps a name and an id ({@code me = true}), the others are
 * « Vendeur &lt;rang&gt; ». The {@code advanced-reports} gate is checked first for both roles; the employee view
 * further requires it ENABLED (READ_ONLY grace → same 403, the owner could not turn sharing off).
 */
@Tag(name = "Leaderboard", description = "Seller leaderboard (OWNER; EMPLOYEE when shared, anonymised)")
@RestController
@RequestMapping("/api/v1/reporting/leaderboard")
@PreAuthorize("hasAnyRole('OWNER','MANAGER','EMPLOYEE','VIEWER')")
public class LeaderboardController {

    /** Shop time zone — same as ProfitabilityController / JdbcProfitabilityRepository. */
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    /** Story 17.2 — employee refused while the owner has not shared the leaderboard. */
    static final String ADVANCED_REPORTS = LeaderboardAudience.ADVANCED_REPORTS;

    static final String NOT_SHARED_MESSAGE = LeaderboardAudience.NOT_SHARED_MESSAGE;

    private final GetSellerLeaderboardUseCase useCase;
    private final Clock clock;
    private final LeaderboardAudience audience;

    public LeaderboardController(GetSellerLeaderboardUseCase useCase, Clock clock,
                                 TenantPreferencesQueryPort preferences, EntitlementService entitlements) {
        this.useCase = useCase;
        this.clock = clock;
        this.audience = new LeaderboardAudience(preferences, entitlements);
    }

    @Operation(summary = "Seller leaderboard for a date range (OWNER; EMPLOYEE anonymised, own store, when shared)")
    @RequiresEntitlement(value = "advanced-reports", access = RequiresEntitlement.Access.READ)
    @GetMapping
    public ResponseEntity<ApiResponseWrapper<List<SellerLeaderboardEntryDto>>> getLeaderboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false, defaultValue = "CA") String metric) {

        LocalDate today = LocalDate.now(clock.withZone(WAT));
        LocalDate dateTo   = to   != null ? to   : today;
        LocalDate dateFrom = from != null ? from : dateTo;
        if (dateFrom.isAfter(dateTo)) {
            throw new InvalidRequestParameterException("from", "from must not be after to");
        }

        String tenant = TenantContext.getCurrentTenant();
        // Story 17.2 (rule shared with the AI tool since 21.8): OWNER as asked; anyone else only when shared, own
        // store, anonymised. Employee token without store → 403 (assignedStoreId fails closed).
        LeaderboardAudience.View view = audience.resolve(tenant, CurrentActor.isOwner(), storeId,
                CurrentActor::assignedStoreId);
        var query = new SellerLeaderboardQuery(tenant, dateFrom, dateTo, view.storeId(), RankingMetric.fromString(metric));
        UUID caller = view.anonymised() ? CurrentActor.actorId() : null;
        var entries = useCase.getLeaderboard(query).stream()
                .map(e -> view.anonymised() ? SellerLeaderboardEntryDto.anonymisedFor(e, caller)
                        : SellerLeaderboardEntryDto.from(e))
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(entries));
    }
}
