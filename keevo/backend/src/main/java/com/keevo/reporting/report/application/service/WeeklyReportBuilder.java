package com.keevo.reporting.report.application.service;

import com.keevo.commerce.api.ClosureWindow;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * WeeklyReportBuilder — GoF Builder pattern for weekly aggregation.
 * Story 7.3 — Rapport Hebdomadaire Automatique.
 *
 * <p>Uses EntityManager native queries (same tenant-isolation pattern as EndOfDayReportBuilder).
 * Queries the window resolved by WeeklyReportWindowResolver and computes WoW delta from the previous WEEKLY report via repository.
 *
 * <p>Architecture rule: JdbcTemplate is forbidden for tenant-schema queries.
 */
@Component
public class WeeklyReportBuilder {

    private final EntityManager entityManager;
    private final EndOfDayReportRepository reportRepository;

    /** Sales covered by the window (see {@link ClosureWindow}); WINDOW_S for queries aliasing sales as s. */
    static final String WINDOW =
            "occurred_at <= :end AND created_at <= :until AND (occurred_at >= :start OR created_at > :lateFrom)";
    static final String WINDOW_S =
            "s.occurred_at <= :end AND s.created_at <= :until AND (s.occurred_at >= :start OR s.created_at > :lateFrom)";

    private record Window(Timestamp start, Timestamp end, Timestamp lateFrom, Timestamp until) {
        static Window of(ClosureWindow w) {
            return new Window(Timestamp.from(w.start()), Timestamp.from(w.end()),
                    Timestamp.from(w.lateFrom()), Timestamp.from(w.receivedUntil()));
        }

        Query bind(Query query) {
            return query.setParameter("start", start).setParameter("end", end)
                    .setParameter("lateFrom", lateFrom).setParameter("until", until);
        }
    }

    public WeeklyReportBuilder(EntityManager entityManager,
                               EndOfDayReportRepository reportRepository) {
        this.entityManager = entityManager;
        this.reportRepository = reportRepository;
    }

    /**
     * Build full WeeklyReportData for a store over the window resolved by
     * {@link WeeklyReportWindowResolver} (v1s-13-11: chained on the previous weekly report).
     *
     * @param storeId     the store UUID
     * @param window      sales covered (occurrence/arrival rule of {@link ClosureWindow})
     * @param storeName   pre-resolved store display name
     * @param weekEndDate the Sunday LocalDate (used as reportDate for persistence)
     * @param tenantId    tenant schema name (for WoW delta lookup)
     * @param isAutomatic whether triggered by scheduler
     */
    @Transactional(readOnly = true)
    public WeeklyReportData build(UUID storeId, ClosureWindow window,
                                  String storeName, LocalDate weekEndDate,
                                  String tenantId, boolean isAutomatic) {
        Window w   = Window.of(window);
        String sid = storeId.toString();

        int totalSales   = countCompletedSales(sid, w);
        int totalRevenue = sumRevenue(sid, w);
        int cashAmount   = sumByPaymentMode(sid, w, "CASH");
        int momoAmount   = sumByPaymentMode(sid, w, "MOBILE_MONEY");
        int avgBasket    = totalSales > 0 ? totalRevenue / totalSales : 0;
        int lowStockCount = countLowStock(sid);

        List<TopProductEntry> byRevenue = getTop5ByRevenue(sid, w);
        List<TopProductEntry> byQty     = getTop5ByQty(sid, w);
        List<EmployeeEntry> employees   = getEmployeeBreakdown(sid, w);

        int previousWeekRevenue = resolvePreviousWeekRevenue(storeId, weekEndDate, tenantId);
        int weekOverWeekDelta   = totalRevenue - previousWeekRevenue;

        return new WeeklyReportData(
                storeName, weekEndDate.minusDays(6), weekEndDate,
                totalSales, totalRevenue, cashAmount, momoAmount, avgBasket,
                byRevenue, byQty, employees,
                previousWeekRevenue, weekOverWeekDelta,
                lowStockCount, isAutomatic
        );
    }

    // ── Aggregation queries ────────────────────────────────────────────────────

    private int countCompletedSales(String storeId, Window w) {
        return toInt(w.bind(entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "  AND " + WINDOW)
                .setParameter("sid", storeId))
                .getSingleResult());
    }

    private int sumRevenue(String storeId, Window w) {
        return toInt(w.bind(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "  AND " + WINDOW)
                .setParameter("sid", storeId))
                .getSingleResult());
    }

    private int sumByPaymentMode(String storeId, Window w, String mode) {
        return toInt(w.bind(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "  AND payment_mode = :mode " +
                "  AND " + WINDOW)
                .setParameter("sid", storeId)
                .setParameter("mode", mode))
                .getSingleResult());
    }

    private int countLowStock(String storeId) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(DISTINCT sl.product_id) FROM stock_levels sl " +
                "JOIN products p ON sl.product_id = p.id " +
                "WHERE sl.store_id = CAST(:sid AS UUID) " +
                "  AND sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), 5)")
                .setParameter("sid", storeId)
                .getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private List<TopProductEntry> getTop5ByRevenue(String storeId, Window w) {
        List<Object[]> rows = w.bind(entityManager.createNativeQuery(
                "SELECT si.product_name, " +
                "       SUM(si.quantity) AS total_qty, " +
                "       SUM(si.subtotal) AS total_revenue " +
                "FROM sale_items si " +
                "JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.store_id = CAST(:sid AS UUID) " +
                "  AND " + WINDOW_S + " " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY si.product_name " +
                "ORDER BY total_revenue DESC " +
                "LIMIT 5")
                .setParameter("sid", storeId))
                .getResultList();
        return rows.stream()
                .map(r -> new TopProductEntry(
                        (String) r[0],
                        ((Number) r[1]).intValue(),
                        ((Number) r[2]).intValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<TopProductEntry> getTop5ByQty(String storeId, Window w) {
        List<Object[]> rows = w.bind(entityManager.createNativeQuery(
                "SELECT si.product_name, " +
                "       SUM(si.quantity) AS total_qty, " +
                "       SUM(si.subtotal) AS total_revenue " +
                "FROM sale_items si " +
                "JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.store_id = CAST(:sid AS UUID) " +
                "  AND " + WINDOW_S + " " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY si.product_name " +
                "ORDER BY total_qty DESC, total_revenue DESC, si.product_name " +
                "LIMIT 5")
                .setParameter("sid", storeId))
                .getResultList();
        return rows.stream()
                .map(r -> new TopProductEntry(
                        (String) r[0],
                        ((Number) r[1]).intValue(),
                        ((Number) r[2]).intValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<EmployeeEntry> getEmployeeBreakdown(String storeId, Window w) {
        List<Object[]> rows = w.bind(entityManager.createNativeQuery(
                "SELECT COALESCE(e.first_name || ' ' || e.last_name, 'Propriétaire') AS emp_name, " +
                "       COUNT(s.id) AS sale_count, " +
                "       SUM(s.total_amount) AS total_revenue " +
                "FROM sales s " +
                "LEFT JOIN employees e ON s.employee_id = e.user_id " +
                "WHERE s.store_id = CAST(:sid AS UUID) " +
                "  AND " + WINDOW_S + " " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY s.employee_id, e.first_name, e.last_name " +
                "ORDER BY total_revenue DESC")
                .setParameter("sid", storeId))
                .getResultList();
        return rows.stream()
                .map(r -> new EmployeeEntry(
                        (String) r[0],
                        ((Number) r[1]).intValue(),
                        ((Number) r[2]).intValue()))
                .toList();
    }

    // ── WoW delta ─────────────────────────────────────────────────────────────

    /**
     * Resolves the previous week's total revenue for WoW delta computation.
     * Looks up the WEEKLY report for the Sunday that was 7 days before weekEndDate.
     * Filters by storeId to support multi-store setups.
     */
    private int resolvePreviousWeekRevenue(UUID storeId, LocalDate weekEndDate, String tenantId) {
        LocalDate prevSunday = weekEndDate.minusDays(7);
        return reportRepository.findByDateAndTenant(prevSunday, tenantId, ReportType.WEEKLY)
                .stream()
                .filter(r -> storeId.equals(r.getStoreId()))
                .mapToInt(r -> r.getTotalRevenue())
                .findFirst()
                .orElse(0);
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private int toInt(Object result) {
        if (result == null) return 0;
        return ((Number) result).intValue();
    }
}
