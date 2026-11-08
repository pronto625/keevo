package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.keevo.messaging.notification.domain.port.out.StoreActivityRepository;
import com.keevo.shared.capability.revenue.RevenueQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.14 — {@link StoreActivityRepository} on the single revenue source ({@link RevenueQueryPort}, store-scoped
 * relations: sales and invoices of each store, a sale bridged to an invoice counted once), at the transaction instant
 * ({@code recorded_at}: a sale's {@code occurred_at}, an invoice's {@code issued_at}).
 *
 * <p>One grouped query for all the stores of the tenant (instead of two per store): the four one-hour windows are
 * pushed into each branch of the source — sales on {@code idx_sales_completed_occurred_at}, invoices on
 * {@code idx_invoices_counted_issued_at}, credit notes on {@code idx_credit_notes_issued_at}. Runs in its own
 * read-only transaction so the source resolves in the tenant's search_path (the caller sets the tenant context).
 */
@Component
public class JdbcStoreActivityRepository implements StoreActivityRepository {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);
    /** Days of history the hour is compared with. */
    static final int PREVIOUS_DAYS = 3;

    private final JdbcTemplate jdbc;
    private final RevenueQueryPort revenue;

    public JdbcStoreActivityRepository(JdbcTemplate jdbc, RevenueQueryPort revenue) {
        this.jdbc = jdbc;
        this.revenue = revenue;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, HourlyActivity> lastHour(Instant hourEnd) {
        Timestamp end = Timestamp.from(hourEnd);
        Timestamp start = Timestamp.from(hourEnd.minus(HOUR));
        StringBuilder windows = new StringBuilder("(r.recorded_at >= ? AND r.recorded_at < ?)");
        Object[] args = new Object[4 + 2 * (PREVIOUS_DAYS + 1)];
        int i = 0;
        args[i++] = start;
        args[i++] = end;
        args[i++] = start;
        args[i++] = end;
        args[i++] = start;
        args[i++] = end;
        for (int day = 1; day <= PREVIOUS_DAYS; day++) {
            windows.append(" OR (r.recorded_at >= ? AND r.recorded_at < ?)");
            args[i++] = Timestamp.from(hourEnd.minus(DAY.multipliedBy(day)).minus(HOUR));
            args[i++] = Timestamp.from(hourEnd.minus(DAY.multipliedBy(day)));
        }
        String sql = "SELECT r.store_id, "
                + "COALESCE(SUM(r.doc_count) FILTER (WHERE r.recorded_at >= ? AND r.recorded_at < ?), 0) AS current, "
                + "COALESCE(SUM(r.doc_count) FILTER (WHERE NOT (r.recorded_at >= ? AND r.recorded_at < ?)), 0) "
                + "AS previous "
                + "FROM " + revenue.storeScoped().documents() + " r "
                + "WHERE r.store_id IS NOT NULL AND (" + windows + ") "
                + "GROUP BY r.store_id";
        Map<UUID, HourlyActivity> activity = new HashMap<>();
        jdbc.query(sql, rs -> {
            activity.put(rs.getObject("store_id", UUID.class), new HourlyActivity(rs.getInt("current"),
                    rs.getLong("previous") / (double) PREVIOUS_DAYS));
        }, args);
        return activity;
    }
}
