package com.keevo.reporting.leaderboard.adapter.in.rest;

import com.keevo.reporting.leaderboard.adapter.in.rest.dto.SellerLeaderboardEntryDto;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase;
import com.keevo.reporting.leaderboard.domain.port.in.GetSellerLeaderboardUseCase.SellerLeaderboardQuery;
import com.keevo.reporting.profitability.domain.model.RankingMetric;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.infrastructure.web.InvalidRequestParameterException;
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
 * LeaderboardController — FR44 seller leaderboard (v1s-14-2), OWNER only (EMPLOYEE → 403).
 *
 * <pre>
 * GET /api/v1/reporting/leaderboard?from=YYYY-MM-DD&amp;to=YYYY-MM-DD&amp;storeId=&amp;metric=CA|SALES_COUNT|AVG_BASKET
 * </pre>
 *
 * Missing {@code to} defaults to today in WAT (injected {@link Clock}), missing {@code from} to
 * {@code to}; {@code from > to} → 400
 * {@code VALIDATION_ERROR}; unknown metric → CA.
 */
@Tag(name = "Leaderboard", description = "Seller leaderboard (OWNER)")
@RestController
@RequestMapping("/api/v1/reporting/leaderboard")
@PreAuthorize("hasRole('OWNER')")
public class LeaderboardController {

    /** Shop time zone — same as ProfitabilityController / JdbcProfitabilityRepository. */
    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private final GetSellerLeaderboardUseCase useCase;
    private final Clock clock;

    public LeaderboardController(GetSellerLeaderboardUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    @Operation(summary = "Seller leaderboard for a date range (OWNER only)")
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

        var query = new SellerLeaderboardQuery(
                TenantContext.getCurrentTenant(), dateFrom, dateTo, storeId,
                RankingMetric.fromString(metric));

        var entries = useCase.getLeaderboard(query).stream()
                .map(SellerLeaderboardEntryDto::from)
                .toList();
        return ResponseEntity.ok(ApiResponseWrapper.ok(entries));
    }
}
