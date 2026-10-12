package com.keevo.reporting.dashboard.application.service;

import com.keevo.reporting.dashboard.domain.model.DashboardSummary;
import com.keevo.reporting.dashboard.domain.model.DashboardSummary.DailyCAEntry;
import com.keevo.reporting.dashboard.domain.port.in.GetDashboardSummaryUseCase.GetDashboardSummaryQuery;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1s-16-19 — dashboard chart buckets follow the WAT calendar on a real PostgreSQL (the DB
 * session runs in UTC). Assertions are scoped to a store created by each test: the shared
 * database may hold sales committed by other tests.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@DisplayName("DashboardService on PostgreSQL — WAT buckets")
class DashboardServicePostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("a Monday 00:30 WAT sale (Sunday 23:30 UTC) counts in its own week and day")
    void mondayJustAfterMidnightWat() {
        UUID store = insertStore();
        insertSale(store, Instant.parse("2026-01-04T23:30:00Z"), 1000); // Mon 2026-01-05 00:30 WAT
        // Oldest weekly slot Monday (2025-10-20) at 00:30 WAT: inside the WAT-computed window.
        insertSale(store, Instant.parse("2025-10-19T23:30:00Z"), 70);
        // Sunday 2025-10-19 23:30 WAT: week of 2025-10-13, before the window — excluded.
        insertSale(store, Instant.parse("2025-10-19T22:30:00Z"), 5);

        DashboardSummary summary = summary(at("2026-01-05T12:00:00Z"));
        var overview = storeOverview(summary, store);

        assertThat(overview.weeklyCA()).containsExactly(
                new DailyCAEntry("2025-10-20", 70),
                new DailyCAEntry("2026-01-05", 1000));
        assertThat(overview.weeklyCA()).extracting(DailyCAEntry::date).doesNotContain("2025-10-13");
        assertThat(overview.dailyCA()).containsExactly(new DailyCAEntry("2026-01-05", 1000));

        // All-stores series (shared DB: containment, amount >= what this test inserted).
        assertHasAtLeast(summary.weeklyCA(), "2026-01-05", 1000);
        assertHasAtLeast(summary.weeklyCA(), "2025-10-20", 70);
        assertHasAtLeast(summary.dailyCALast30(), "2026-01-05", 1000);
        assertHasAtLeast(summary.monthlyCA(), "2026-01-01", 1000);
        assertHasAtLeast(summary.yearlyCA(), "2026-01-01", 1000);
    }

    @Test
    @DisplayName("a week straddling 31 Dec / 1 Jan is a single weekly row keyed by its Monday")
    void yearStraddlingWeek() {
        UUID store = insertStore();
        insertSale(store, Instant.parse("2025-12-30T09:00:00Z"), 500); // Tue 30 Dec 10:00 WAT
        insertSale(store, Instant.parse("2026-01-02T09:00:00Z"), 300); // Fri 2 Jan 10:00 WAT

        DashboardSummary summary = summary(at("2026-01-07T12:00:00Z"));

        assertThat(storeOverview(summary, store).weeklyCA())
                .containsExactly(new DailyCAEntry("2025-12-29", 800));
        assertHasAtLeast(summary.weeklyCA(), "2025-12-29", 800);
    }

    @Test
    @DisplayName("1 January 00:30 WAT (31 Dec 23:30 UTC) counts in its WAT month and year")
    void newYearJustAfterMidnightWat() {
        UUID store = insertStore();
        insertSale(store, Instant.parse("2025-12-31T23:30:00Z"), 1000); // 2026-01-01 00:30 WAT

        DashboardSummary summary = summary(at("2026-01-10T12:00:00Z"));
        var overview = storeOverview(summary, store);

        assertThat(overview.monthlyCA()).containsExactly(new DailyCAEntry("2026-01-01", 1000));
        assertThat(overview.yearlyCA()).containsExactly(new DailyCAEntry("2026-01-01", 1000));
        assertThat(overview.dailyCA()).containsExactly(new DailyCAEntry("2026-01-01", 1000));
        assertHasAtLeast(summary.monthlyCA(), "2026-01-01", 1000);
        assertHasAtLeast(summary.yearlyCA(), "2026-01-01", 1000);
        assertHasAtLeast(summary.dailyCALast30(), "2026-01-01", 1000);
    }

    // ── helpers ──

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private DashboardSummary summary(Clock clock) {
        return new DashboardService(jdbc, clock).execute(new GetDashboardSummaryQuery("public"));
    }

    private static void assertHasAtLeast(List<DailyCAEntry> series, String period, long amount) {
        assertThat(series).filteredOn(e -> e.date().equals(period)).singleElement()
                .satisfies(e -> assertThat(e.amount()).isGreaterThanOrEqualTo(amount));
    }

    private static DashboardSummary.StoreOverviewEntry storeOverview(DashboardSummary summary, UUID store) {
        return summary.storeOverviews().stream()
                .filter(o -> o.storeId().equals(store.toString()))
                .findFirst().orElseThrow();
    }

    private UUID insertStore() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        jdbc.update("INSERT INTO stores (id, created_at, updated_at, is_active, name, type) "
                + "VALUES (?, ?, ?, true, 'Boutique WAT', 'STORE')", id, now, now);
        return id;
    }

    private void insertSale(UUID store, Instant occurredAt, int total) {
        jdbc.update("INSERT INTO sales (id, created_at, employee_id, occurred_at, payment_mode, status, store_id, "
                        + "total_amount, discount_amount) VALUES (?, ?, ?, ?, 'CASH', 'COMPLETED', ?, ?, 0)",
                UUID.randomUUID(), Timestamp.from(occurredAt), UUID.randomUUID(), Timestamp.from(occurredAt),
                store, total);
    }
}
