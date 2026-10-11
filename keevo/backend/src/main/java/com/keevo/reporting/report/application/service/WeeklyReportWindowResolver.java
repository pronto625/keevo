package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.model.ClosureWindow;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.UUID;

/**
 * WeeklyReportWindowResolver — which sales a store's weekly report covers.
 * Story v1s-13-11 — PO decision D2 (PRD FR52).
 *
 * <p>The weekly report chains on the previous weekly report of the store, with the same
 * occurrence/arrival rule as day closures ({@link ClosureWindow}):
 * <ul>
 *   <li>start = just after the previous report's end, i.e. min(prev.createdAt, end of its
 *       Sunday) + 1µs; Monday 00:00 WAT when there is no previous report;</li>
 *   <li>end = min(now, Sunday 23:59:59.999999 WAT): the generation time when generated on
 *       Sunday (a Sunday 20:00 report), else the end of the week;</li>
 *   <li>lateFrom = prev.createdAt: sales made before start but received after the previous
 *       report (offline devices syncing late) are counted here; none without a previous report;</li>
 *   <li>receivedUntil = now: sales received later belong to the next report.</li>
 * </ul>
 * The report is persisted with {@code createdAt = receivedUntil}, so the next week chains on
 * it exactly — every COMPLETED sale lands in exactly one weekly report, whatever the trigger
 * day/time. Legacy reports (created before this rule) chain the same way: their Sunday
 * 20:00–23:59 gap lands in the next week. No migration needed.
 */
@Component
public class WeeklyReportWindowResolver {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private final EndOfDayReportRepository reportRepository;

    public WeeklyReportWindowResolver(EndOfDayReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** The resolved window; {@code lateFrom} is null when there is no previous report. */
    public record Window(Instant start, Instant end, Instant lateFrom, Instant receivedUntil) {
        public ClosureWindow closureWindow() {
            return new ClosureWindow(start, end, lateFrom, receivedUntil);
        }
    }

    /**
     * @param storeId       the store
     * @param tenantId      tenant schema name
     * @param weekEndSunday the Sunday ending the reported ISO week
     * @param now           generation time (truncated to microseconds, the DB precision)
     */
    public Window resolve(UUID storeId, String tenantId, LocalDate weekEndSunday, Instant now) {
        Instant generatedAt = now.truncatedTo(ChronoUnit.MICROS);
        LocalDate previousSunday = weekEndSunday.minusDays(7);

        Instant previousCreatedAt = reportRepository
                .findByDateAndTenant(previousSunday, tenantId, ReportType.WEEKLY).stream()
                .filter(r -> storeId.equals(r.getStoreId()))
                .map(EndOfDayReport::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(i -> i.truncatedTo(ChronoUnit.MICROS))
                .orElse(null);

        Instant start;
        if (previousCreatedAt != null) {
            Instant previousEnd = min(previousCreatedAt, endOfDay(previousSunday));
            start = previousEnd.plus(1, ChronoUnit.MICROS);
        } else {
            start = weekEndSunday.minusDays(6).atStartOfDay(WAT).toInstant();
        }
        Instant end = min(generatedAt, endOfDay(weekEndSunday));
        return new Window(start, end, previousCreatedAt, generatedAt);
    }

    /** Last microsecond of {@code day} in WAT. */
    static Instant endOfDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(WAT).toInstant().minus(1, ChronoUnit.MICROS);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
