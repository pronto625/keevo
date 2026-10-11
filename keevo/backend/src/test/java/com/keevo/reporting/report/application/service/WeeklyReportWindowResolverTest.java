package com.keevo.reporting.report.application.service;

import com.keevo.commerce.sale.domain.model.ClosureWindow;
import com.keevo.reporting.report.application.service.WeeklyReportWindowResolver.Window;
import com.keevo.reporting.report.domain.model.DeliveryStatus;
import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WeeklyReportWindowResolverTest — v1s-13-11, PO decision D2: the weekly report chains on the
 * store's previous weekly report with the ClosureWindow occurrence/arrival rule.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportWindowResolverTest {

    private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
    private static final String TENANT = "kv_test01";
    /** A Sunday. */
    private static final LocalDate S = LocalDate.of(2026, 10, 11);

    @Mock private EndOfDayReportRepository reportRepository;

    private WeeklyReportWindowResolver resolver;
    private UUID storeId;
    /** Persisted WEEKLY reports, served by the mocked repository by report date. */
    private final List<EndOfDayReport> reports = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resolver = new WeeklyReportWindowResolver(reportRepository);
        storeId = UUID.randomUUID();
        reports.clear();
        when(reportRepository.findByDateAndTenant(any(), any(), any())).thenAnswer(inv -> reports.stream()
                .filter(r -> r.getReportDate().equals(inv.getArgument(0))
                        && r.getTenantId().equals(inv.getArgument(1))
                        && r.getReportType() == inv.getArgument(2))
                .toList());
    }

    private static Instant at(LocalDate day, int h, int m) {
        return day.atTime(h, m).atZone(WAT).toInstant();
    }

    private static Instant endOfDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(WAT).toInstant().minusNanos(1000);
    }

    private EndOfDayReport weeklyReport(UUID store, LocalDate sunday, Instant createdAt) {
        return new EndOfDayReport(UUID.randomUUID(), TENANT, store, "Store", null, null,
                ReportType.WEEKLY, sunday, "content", DeliveryStatus.SENT, 1, null, 0, 0, true, createdAt);
    }

    /** Resolves then "generates" the report as WeeklyReportGenerator does (createdAt = receivedUntil). */
    private ClosureWindow generate(LocalDate sunday, Instant now) {
        Window w = resolver.resolve(storeId, TENANT, sunday, now);
        reports.add(weeklyReport(storeId, w.end().atZone(WAT).toLocalDate(), w.receivedUntil()));
        return w.closureWindow();
    }

    // ── Matrix rows ───────────────────────────────────────────────────────────

    @Test
    void sundayTrigger_chainsOnPreviousReport_andEndsAtGenerationTime() {
        Instant prevCreatedAt = at(S.minusDays(7), 20, 0).plusMillis(500);
        reports.add(weeklyReport(storeId, S.minusDays(7), prevCreatedAt));
        Instant now = at(S, 20, 0);

        Window w = resolver.resolve(storeId, TENANT, S, now);

        assertThat(w.start()).isEqualTo(prevCreatedAt.plusNanos(1000));
        assertThat(w.end()).isEqualTo(now);
        assertThat(w.receivedUntil()).isEqualTo(now);
        assertThat(w.lateFrom()).isEqualTo(prevCreatedAt);
    }

    @Test
    void nonSundayTrigger_endsAtEndOfSunday_andChainsOnEndOfPreviousSunday() {
        // Config Saturday: the week ending S is reported on Saturday S+6; the previous one
        // (week ending S-7) was reported on Saturday S-1.
        Instant prevCreatedAt = at(S.minusDays(1), 20, 0);
        reports.add(weeklyReport(storeId, S.minusDays(7), prevCreatedAt));
        Instant now = at(S.plusDays(6), 20, 0);

        Window w = resolver.resolve(storeId, TENANT, S, now);

        assertThat(w.end()).isEqualTo(endOfDay(S));
        assertThat(w.end()).isEqualTo(Instant.parse("2026-10-11T22:59:59.999999Z"));
        assertThat(w.receivedUntil()).isEqualTo(now);
        assertThat(w.start()).isEqualTo(endOfDay(S.minusDays(7)).plusNanos(1000));
        assertThat(w.lateFrom()).isEqualTo(prevCreatedAt);
    }

    @Test
    void noPreviousReport_startsMondayMidnight_withoutLateSales() {
        Instant now = at(S, 20, 0);

        Window w = resolver.resolve(storeId, TENANT, S, now);

        assertThat(w.start()).isEqualTo(S.minusDays(6).atStartOfDay(WAT).toInstant());
        assertThat(w.lateFrom()).isNull();
        assertThat(w.closureWindow().lateFrom()).isEqualTo(ClosureWindow.NEVER);
    }

    @Test
    void previousReportOfAnotherStore_isIgnored_andEarliestOfTheStoreWins() {
        Instant otherStore = at(S.minusDays(7), 20, 0);
        Instant first = at(S.minusDays(7), 20, 1);
        reports.add(weeklyReport(UUID.randomUUID(), S.minusDays(7), otherStore));
        reports.add(weeklyReport(storeId, S.minusDays(7), first.plusSeconds(60)));
        reports.add(weeklyReport(storeId, S.minusDays(7), first));

        Window w = resolver.resolve(storeId, TENANT, S, at(S, 20, 0));

        assertThat(w.lateFrom()).isEqualTo(first);
    }

    @Test
    void instants_areTruncatedToMicroseconds() {
        Instant prevCreatedAt = at(S.minusDays(7), 20, 0).plusNanos(123_456_789);
        reports.add(weeklyReport(storeId, S.minusDays(7), prevCreatedAt));
        Instant now = at(S, 20, 0).plusNanos(987_654_321);

        Window w = resolver.resolve(storeId, TENANT, S, now);

        assertThat(w.receivedUntil()).isEqualTo(at(S, 20, 0).plusNanos(987_654_000));
        assertThat(w.end()).isEqualTo(w.receivedUntil());
        assertThat(w.lateFrom()).isEqualTo(at(S.minusDays(7), 20, 0).plusNanos(123_456_000));
        assertThat(w.start()).isEqualTo(w.lateFrom().plusNanos(1000));
    }

    // ── No gap, no overlap ────────────────────────────────────────────────────

    private record Sale(String name, Instant occurredAt, Instant createdAt) {
        static Sale online(String name, Instant occurredAt) {
            return new Sale(name, occurredAt, occurredAt.plusSeconds(2));
        }
    }

    private static void assertEachCoveredExactlyOnce(List<Sale> sales, List<ClosureWindow> windows) {
        for (Sale sale : sales) {
            long covering = windows.stream().filter(w -> w.covers(sale.occurredAt(), sale.createdAt())).count();
            assertThat(covering).as("windows covering %s", sale.name()).isEqualTo(1);
        }
    }

    @Test
    void sundayConfig_consecutiveWeeks_coverEverySaleExactlyOnce() {
        ClosureWindow w0 = generate(S.minusDays(7), at(S.minusDays(7), 20, 0).plusMillis(500)); // first report
        ClosureWindow w1 = generate(S, at(S, 20, 0));
        ClosureWindow w2 = generate(S.plusDays(7), at(S.plusDays(7), 20, 0));

        assertEachCoveredExactlyOnce(List.of(
                Sale.online("prev Sun 21:00", at(S.minusDays(7), 21, 0)),
                Sale.online("Sun 19:59", at(S, 19, 59)),
                new Sale("Sun 20:00 synced 20:00:01", at(S, 20, 0), at(S, 20, 0).plusSeconds(1)),
                Sale.online("Sun 20:30", at(S, 20, 30)),
                Sale.online("Sun 23:59", at(S, 23, 59)),
                new Sale("Sat sale synced Monday", at(S.minusDays(1), 15, 0), at(S.plusDays(1), 9, 0)),
                Sale.online("Mon 00:00", S.plusDays(1).atStartOfDay(WAT).toInstant()),
                Sale.online("next Sun 19:00", at(S.plusDays(7), 19, 0))
        ), List.of(w0, w1, w2));
        // The Sunday evening after a 20:00 report goes to the next week.
        assertThat(w1.covers(at(S, 21, 0), at(S, 21, 0))).isFalse();
        assertThat(w2.covers(at(S, 21, 0), at(S, 21, 0))).isTrue();
    }

    @Test
    void configChangedToSaturday_consecutiveWeeks_coverEverySaleExactlyOnce() {
        ClosureWindow w1 = generate(S, at(S, 20, 0));                                // Sunday config
        ClosureWindow w2 = generate(S.plusDays(7), at(S.plusDays(13), 20, 0));       // now Saturday config
        ClosureWindow w3 = generate(S.plusDays(14), at(S.plusDays(20), 20, 0));

        assertEachCoveredExactlyOnce(List.of(
                Sale.online("Sun 19:59", at(S, 19, 59)),
                Sale.online("Sun 20:30", at(S, 20, 30)),
                Sale.online("Sun 23:59", at(S, 23, 59)),
                Sale.online("Mon 00:00", S.plusDays(1).atStartOfDay(WAT).toInstant()),
                Sale.online("week-2 Sun 23:59", at(S.plusDays(7), 23, 59)),
                Sale.online("week-3 Mon 00:00", S.plusDays(8).atStartOfDay(WAT).toInstant()),
                Sale.online("week-3 Sat 21:00 (after w2 generation)", at(S.plusDays(13), 21, 0)),
                new Sale("week-2 Sat sale synced after w2", at(S.plusDays(6), 15, 0), at(S.plusDays(14), 9, 0)),
                new Sale("Sat sale synced Monday", at(S.minusDays(1), 15, 0), at(S.plusDays(1), 9, 0))
        ), List.of(w1, w2, w3));
    }

    @Test
    void legacyPreviousReport_itsSundayEveningGapLandsInTheNextWeek() {
        // A report generated before this rule: Mon 00:00 → Sun 23:59:59 on sale time only,
        // over the sales present at its generation (Sunday 20:00:00.5).
        Instant legacyCreatedAt = at(S.minusDays(7), 20, 0).plusMillis(500);
        reports.add(weeklyReport(storeId, S.minusDays(7), legacyCreatedAt));
        ClosureWindow legacy = new ClosureWindow(S.minusDays(13).atStartOfDay(WAT).toInstant(),
                S.minusDays(7).atTime(23, 59, 59).atZone(WAT).toInstant(), null, legacyCreatedAt);

        ClosureWindow w1 = generate(S, at(S, 20, 0));
        ClosureWindow w2 = generate(S.plusDays(7), at(S.plusDays(7), 20, 0));

        assertEachCoveredExactlyOnce(List.of(
                Sale.online("legacy Sun 19:59", at(S.minusDays(7), 19, 59)),
                Sale.online("legacy Sun 20:30 (the old gap)", at(S.minusDays(7), 20, 30)),
                Sale.online("legacy Sun 23:59", at(S.minusDays(7), 23, 59)),
                new Sale("legacy Sat sale synced late", at(S.minusDays(8), 15, 0), at(S.minusDays(6), 9, 0)),
                Sale.online("Sun 19:59", at(S, 19, 59)),
                Sale.online("Sun 23:59", at(S, 23, 59)),
                Sale.online("Mon 00:00", S.plusDays(1).atStartOfDay(WAT).toInstant())
        ), List.of(legacy, w1, w2));
        assertThat(w1.covers(at(S.minusDays(7), 20, 30), at(S.minusDays(7), 20, 30).plusSeconds(2))).isTrue();
    }

    @Test
    void generatedAfterTheWeekAndStillLate_endIsEndOfSundayNotNow() {
        Window w = resolver.resolve(storeId, TENANT, S, at(S.plusDays(3), 10, 0));
        assertThat(w.end()).isEqualTo(endOfDay(S));
        assertThat(Duration.between(w.end(), w.receivedUntil())).isPositive();
    }
}
