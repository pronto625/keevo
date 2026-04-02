package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.reporting.report.domain.model.WeeklyReportData;
import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * WeeklyReportBuilder — GoF Builder pattern for weekly aggregation.
 * Story 7.3 — Rapport Hebdomadaire Automatique.
 *
 * <p>Uses EntityManager native queries (same tenant-isolation pattern as EndOfDayReportBuilder).
 * Queries a Mon–Sun window and computes WoW delta from the previous WEEKLY report via repository.
 *
 * <p>Architecture rule: JdbcTemplate is forbidden for tenant-schema queries.
 */
@Component
public class WeeklyReportBuilder {

    private final EntityManager entityManager;
    private final EndOfDayReportRepository reportRepository;

    public WeeklyReportBuilder(EntityManager entityManager,
                               EndOfDayReportRepository reportRepository) {
        this.entityManager = entityManager;
        this.reportRepository = reportRepository;
    }

    /**
     * Build full WeeklyReportData for a store over a Mon–Sun window.
     *
     * @param storeId     the store UUID
     * @param weekStart   Monday 00:00:00 WAT as Instant
     * @param weekEnd     Sunday 23:59:59 WAT as Instant
     * @param storeName   pre-resolved store display name
     * @param weekEndDate the Sunday LocalDate (used as reportDate for persistence)
     * @param tenantId    tenant schema name (for WoW delta lookup)
     * @param isAutomatic whether triggered by scheduler
     */
    @Transactional(readOnly = true)
    public WeeklyReportData build(UUID storeId, Instant weekStart, Instant weekEnd,
                                  String storeName, LocalDate weekEndDate,
                                  String tenantId, boolean isAutomatic) {
        Timestamp start = Timestamp.from(weekStart);
        Timestamp end   = Timestamp.from(weekEnd);
        String sid      = storeId.toString();

        int totalSales   = countCompletedSales(sid, start, end);
        int totalRevenue = sumRevenue(sid, start, end);
        int cashAmount   = sumByPaymentMode(sid, start, end, "CASH");
        int momoAmount   = sumByPaymentMode(sid, start, end, "MOBILE_MONEY");
        int avgBasket    = totalSales > 0 ? totalRevenue / totalSales : 0;
        int lowStockCount = countLowStock(sid);

        List<TopProductEntry> byRevenue = getTop5ByRevenue(sid, start, end);
        List<TopProductEntry> byQty     = getTop5ByQty(sid, start, end);
        List<EmployeeEntry> employees   = getEmployeeBreakdown(sid, start, end);

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

    private int countCompletedSales(String storeId, Timestamp start, Timestamp end) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "  AND occurred_at >= :start AND occurred_at <= :end")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult());
    }

    private int sumRevenue(String storeId, Timestamp start, Timestamp end) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "  AND occurred_at >= :start AND occurred_at <= :end")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult());
    }

    private int sumByPaymentMode(String storeId, Timestamp start, Timestamp end, String mode) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "  AND payment_mode = :mode " +
                "  AND occurred_at >= :start AND occurred_at <= :end")
                .setParameter("sid", storeId)
                .setParameter("mode", mode)
                .setParameter("start", start)
                .setParameter("end", end)
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
    private List<TopProductEntry> getTop5ByRevenue(String storeId, Timestamp start, Timestamp end) {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT si.product_name, " +
                "       SUM(si.quantity) AS total_qty, " +
                "       SUM(si.subtotal) AS total_revenue " +
                "FROM sale_items si " +
                "JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.store_id = CAST(:sid AS UUID) " +
                "  AND s.occurred_at >= :start AND s.occurred_at <= :end " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY si.product_name " +
                "ORDER BY total_revenue DESC " +
                "LIMIT 5")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        return rows.stream()
                .map(r -> new TopProductEntry(
                        (String) r[0],
                        ((Number) r[1]).intValue(),
                        ((Number) r[2]).intValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<TopProductEntry> getTop5ByQty(String storeId, Timestamp start, Timestamp end) {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT si.product_name, " +
                "       SUM(si.quantity) AS total_qty, " +
                "       SUM(si.subtotal) AS total_revenue " +
                "FROM sale_items si " +
                "JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.store_id = CAST(:sid AS UUID) " +
                "  AND s.occurred_at >= :start AND s.occurred_at <= :end " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY si.product_name " +
                "ORDER BY total_qty DESC " +
                "LIMIT 5")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        return rows.stream()
                .map(r -> new TopProductEntry(
                        (String) r[0],
                        ((Number) r[1]).intValue(),
                        ((Number) r[2]).intValue()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<EmployeeEntry> getEmployeeBreakdown(String storeId, Timestamp start, Timestamp end) {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT COALESCE(e.first_name || ' ' || e.last_name, 'Employé') AS emp_name, " +
                "       COUNT(s.id) AS sale_count, " +
                "       SUM(s.total_amount) AS total_revenue " +
                "FROM sales s " +
                "LEFT JOIN employees e ON s.employee_id = e.user_id " +
                "WHERE s.store_id = CAST(:sid AS UUID) " +
                "  AND s.occurred_at >= :start AND s.occurred_at <= :end " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY s.employee_id, e.first_name, e.last_name " +
                "ORDER BY total_revenue DESC")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
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
