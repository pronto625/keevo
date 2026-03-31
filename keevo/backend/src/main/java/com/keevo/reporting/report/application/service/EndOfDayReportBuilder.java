package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * EndOfDayReportBuilder — GoF Builder pattern.
 * Story 7.2 — assembles EndOfDayReportData from multiple SQL queries.
 *
 * <p>Uses EntityManager.createNativeQuery() so that Hibernate's
 * SchemaAwareMultiTenantConnectionProvider sets the correct search_path
 * for the current tenant. JdbcTemplate is forbidden for tenant-schema queries
 * (architecture rule: bypasses the Hibernate SPI).
 */
@Component
public class EndOfDayReportBuilder {

    private final EntityManager entityManager;

    public EndOfDayReportBuilder(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Build report data for a given store + date.
     *
     * @param storeId  the store UUID
     * @param date     the reporting date (LocalDate)
     * @param storeName pre-resolved store name
     * @param closeTime the time the day was closed
     * @param isAutomatic whether the closure was automatic
     * @return fully assembled EndOfDayReportData
     */
    @Transactional(readOnly = true)
    public EndOfDayReportData build(UUID storeId, LocalDate date, String storeName,
                                    LocalTime closeTime, boolean isAutomatic) {
        Instant startOfDay = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endOfDay   = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        Timestamp start = Timestamp.from(startOfDay);
        Timestamp end   = Timestamp.from(endOfDay);
        String sid      = storeId.toString();

        int totalSales    = countCompletedSales(sid, start, end);
        int totalRevenue  = sumRevenue(sid, start, end);
        int cashAmount    = sumByPaymentMode(sid, start, end, "CASH");
        int momoAmount    = sumByPaymentMode(sid, start, end, "MOBILE_MONEY");
        int avgBasket     = totalSales > 0 ? totalRevenue / totalSales : 0;

        int pendingSalesCount = countPendingSales(sid, start, end);
        int pendingSalesTotal = sumPendingRevenue(sid, start, end);
        int lowStockCount     = countLowStock(sid);

        List<TopProductEntry> topProducts = getTop3Products(sid, start, end);
        List<EmployeeEntry> employees     = getEmployeeBreakdown(sid, start, end);

        return new EndOfDayReportData(
                storeName, date, closeTime, isAutomatic,
                totalSales, totalRevenue, cashAmount, momoAmount, avgBasket,
                topProducts, employees, lowStockCount, pendingSalesCount, pendingSalesTotal
        );
    }

    // ── Private aggregation queries ─────────────────────────────────────────

    private int countCompletedSales(String storeId, Timestamp start, Timestamp end) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "AND occurred_at >= :start AND occurred_at < :end")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult());
    }

    private int sumRevenue(String storeId, Timestamp start, Timestamp end) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "AND occurred_at >= :start AND occurred_at < :end")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult());
    }

    private int sumByPaymentMode(String storeId, Timestamp start, Timestamp end, String mode) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' AND payment_mode = :mode " +
                "AND occurred_at >= :start AND occurred_at < :end")
                .setParameter("sid", storeId)
                .setParameter("mode", mode)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult());
    }

    private int countPendingSales(String storeId, Timestamp start, Timestamp end) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'PENDING_VALIDATION' " +
                "AND occurred_at >= :start AND occurred_at < :end")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult());
    }

    private int sumPendingRevenue(String storeId, Timestamp start, Timestamp end) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'PENDING_VALIDATION' " +
                "AND occurred_at >= :start AND occurred_at < :end")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult());
    }

    private int countLowStock(String storeId) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(DISTINCT sl.product_id) FROM stock_levels sl " +
                "JOIN products p ON sl.product_id = p.id " +
                "WHERE sl.store_id = CAST(:sid AS UUID) " +
                "AND sl.quantity <= COALESCE(NULLIF(p.minimum_threshold, 0), 5)")
                .setParameter("sid", storeId)
                .getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private List<TopProductEntry> getTop3Products(String storeId, Timestamp start, Timestamp end) {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT si.product_name, SUM(si.quantity) AS total_qty, " +
                "       SUM(si.subtotal) AS total_revenue " +
                "FROM sale_items si JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.store_id = CAST(:sid AS UUID) AND s.occurred_at >= :start AND s.occurred_at < :end " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY si.product_name ORDER BY total_qty DESC LIMIT 3")
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
                "SELECT s.employee_id, " +
                "       COALESCE(e.first_name || ' ' || e.last_name, 'Employé') AS emp_name, " +
                "       COUNT(s.id) AS sale_count, SUM(s.total_amount) AS total_revenue " +
                "FROM sales s " +
                "LEFT JOIN employees e ON s.employee_id = e.user_id " +
                "WHERE s.store_id = CAST(:sid AS UUID) AND s.occurred_at >= :start AND s.occurred_at < :end " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY s.employee_id, e.first_name, e.last_name " +
                "ORDER BY total_revenue DESC")
                .setParameter("sid", storeId)
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();
        return rows.stream()
                .map(r -> new EmployeeEntry(
                        (String) r[1],
                        ((Number) r[2]).intValue(),
                        ((Number) r[3]).intValue()))
                .toList();
    }

    private static int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
