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
 * <p>The weekly report chains on the store's latest weekly report (within
 * {@link #MAX_LOOKBACK_WEEKS} weeks — a missing week does not break the chain), with the same
 * occurrence/arrival rule as day closures ({@link ClosureWindow}):
 * <ul>
 *   <li>start = just after the previous report's end, i.e. min(prev.createdAt, end of its
 *       Sunday), never before its Sunday 00:00 WAT, + 1µs; Monday 00:00 WAT when there is no
 *       previous report;</li>
 *   <li>end = min(generatedAt, Sunday 23:59:59.999999 WAT): the generation time when generated
 *       on Sunday (a Sunday 20:00 report), else the end of the week — never before Sunday
 *       00:00 WAT, so the report stays dated by its Sunday;</li>
 *   <li>lateFrom = prev.createdAt: sales made before start but received after the previous
 *       report (offline devices syncing late) are counted here; none without a previous report;</li>
 *   <li>receivedUntil = generatedAt: sales received later belong to the next report.</li>
 * </ul>
 * {@code generatedAt = now − }{@link ClosureWindow#COMMIT_SAFETY_MARGIN} (v1s-13-13): a sale
 * stamped just before {@code now} but committed after the report queries ran is counted by
 * the next report (late arrival after this report's {@code createdAt}).
 * The report is persisted with {@code createdAt = receivedUntil}, so the next week chains on
 * it exactly — every COMPLETED sale lands in exactly one weekly report, whatever the trigger
 * day/time. Legacy reports (created before this rule) chain the same way: their Sunday
 * 20:00–23:59 gap lands in the next week. No migration needed.
 */
@Component
public class WeeklyReportWindowResolver {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    /**
     * How far back the chain looks for the store's previous weekly report (about two months).
     * Beyond it (store dormant for longer) the report starts on Monday 00:00 WAT.
     */
    static final int MAX_LOOKBACK_WEEKS = 8;

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
        // v1s-13-13: commit-safety margin — one anchor for end, receivedUntil and createdAt.
        Instant generatedAt = ClosureWindow.receivedLimit(now).truncatedTo(ChronoUnit.MICROS);

        // v1s-13-11 follow-up: chain on the store's LATEST weekly report, not only the one
        // dated exactly a week earlier — a week without report (backend down on the configured
        // day, weekly report disabled for a while, store inactive) must not drop the Sunday
        // evening and the late-synced sales after the last real report. Bounded look-back.
        Instant previousCreatedAt = null;
        LocalDate previousSunday = null;
        for (int weeksBack = 1; weeksBack <= MAX_LOOKBACK_WEEKS && previousCreatedAt == null; weeksBack++) {
            LocalDate candidate = weekEndSunday.minusWeeks(weeksBack);
            previousCreatedAt = reportRepository
                    .findByDateAndTenant(candidate, tenantId, ReportType.WEEKLY).stream()
                    .filter(r -> storeId.equals(r.getStoreId()))
                    .map(EndOfDayReport::getCreatedAt)
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .map(i -> i.truncatedTo(ChronoUnit.MICROS))
                    .orElse(null);
            previousSunday = candidate;
        }

        Instant start;
        if (previousCreatedAt != null) {
            // Same clamp as `end` below: a previous report generated in the first seconds of
            // its Sunday ended at Sunday 00:00 WAT (after its createdAt anchor).
            Instant previousEnd = max(min(previousCreatedAt, endOfDay(previousSunday)),
                    previousSunday.atStartOfDay(WAT).toInstant());
            start = previousEnd.plus(1, ChronoUnit.MICROS);
        } else {
            start = weekEndSunday.minusDays(6).atStartOfDay(WAT).toInstant();
        }
        // Clamped to Sunday 00:00 WAT: a report generated in the first seconds of the Sunday
        // (margin) must still be dated by that Sunday (report_date, idempotency, lock key).
        Instant end = max(min(generatedAt, endOfDay(weekEndSunday)),
                weekEndSunday.atStartOfDay(WAT).toInstant());
        return new Window(start, end, previousCreatedAt, generatedAt);
    }

    /** Last microsecond of {@code day} in WAT. */
    static Instant endOfDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(WAT).toInstant().minus(1, ChronoUnit.MICROS);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
