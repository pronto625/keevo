package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
import com.keevo.commerce.sale.domain.model.ClosureWindow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

    /** Sales covered by the window (see {@link ClosureWindow}); WINDOW_S for queries aliasing sales as s. */
    private static final String WINDOW =
            "occurred_at <= :end AND created_at <= :until AND (occurred_at >= :start OR created_at > :lateFrom)";
    private static final String WINDOW_S =
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

    public EndOfDayReportBuilder(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Build store-level report data for an explicit time window.
     *
     * @param storeId     the store UUID
     * @param windowStart start of the sales window (from last closure or midnight)
     * @param windowEnd   end of the sales window (= closure timestamp)
     * @param storeName   pre-resolved store name
     * @param date        the reporting date (LocalDate)
     * @param closeTime   the time the day was closed
     * @param isAutomatic whether the closure was automatic
     * @return fully assembled EndOfDayReportData for the whole store
     */
    @Transactional(readOnly = true)
    public EndOfDayReportData build(UUID storeId, Instant windowStart, Instant windowEnd,
                                    String storeName, LocalDate date,
                                    LocalTime closeTime, boolean isAutomatic) {
        return build(storeId, ClosureWindow.between(windowStart, windowEnd),
                storeName, date, closeTime, isAutomatic);
    }

    /** Store-level report for the sales a day closure covers (late offline sales included). */
    @Transactional(readOnly = true)
    public EndOfDayReportData build(UUID storeId, ClosureWindow window,
                                    String storeName, LocalDate date,
                                    LocalTime closeTime, boolean isAutomatic) {
        Window w   = Window.of(window);
        String sid = storeId.toString();

        int totalSales    = countCompletedSales(sid, w);
        int totalRevenue  = sumRevenue(sid, w);
        int cashAmount    = sumByPaymentMode(sid, w, "CASH");
        int momoAmount    = sumByPaymentMode(sid, w, "MOBILE_MONEY");
        int avgBasket     = totalSales > 0 ? totalRevenue / totalSales : 0;

        int pendingSalesCount = countPendingSales(sid, w);
        int pendingSalesTotal = sumPendingRevenue(sid, w);
        int lowStockCount     = countLowStock(sid);

        List<TopProductEntry> topProducts = getTop3Products(sid, w);
        List<EmployeeEntry> employees     = getEmployeeBreakdown(sid, w);

        return new EndOfDayReportData(
                storeName, date, closeTime, isAutomatic, false,
                totalSales, totalRevenue, cashAmount, momoAmount, avgBasket,
                topProducts, employees, lowStockCount, pendingSalesCount, pendingSalesTotal, null
        );
    }

    /**
     * Build employee-scoped report data for one employee in an explicit time window.
     * Returns only that employee's sales — no team section.
     */
    @Transactional(readOnly = true)
    public EndOfDayReportData buildForEmployee(UUID storeId, UUID employeeId,
                                               Instant windowStart, Instant windowEnd,
                                               String storeName, LocalDate date,
                                               LocalTime closeTime, boolean isAutomatic,
                                               String employeeName) {
        return buildForEmployee(storeId, employeeId, ClosureWindow.between(windowStart, windowEnd),
                storeName, date, closeTime, isAutomatic, employeeName);
    }

    @Transactional(readOnly = true)
    public EndOfDayReportData buildForEmployee(UUID storeId, UUID employeeId, ClosureWindow window,
                                               String storeName, LocalDate date,
                                               LocalTime closeTime, boolean isAutomatic,
                                               String employeeName) {
        Window w   = Window.of(window);
        String sid = storeId.toString();
        String eid = employeeId.toString();

        int totalSales   = countCompletedSalesByEmployee(sid, eid, w);
        int totalRevenue = sumRevenueByEmployee(sid, eid, w);
        int cashAmount   = sumByPaymentModeByEmployee(sid, eid, w, "CASH");
        int momoAmount   = sumByPaymentModeByEmployee(sid, eid, w, "MOBILE_MONEY");
        int avgBasket    = totalSales > 0 ? totalRevenue / totalSales : 0;

        int pendingSalesCount = countPendingSalesByEmployee(sid, eid, w);
        int pendingSalesTotal = sumPendingRevenueByEmployee(sid, eid, w);
        int lowStockCount     = countLowStock(sid);

        List<TopProductEntry> topProducts = getTop3ProductsByEmployee(sid, eid, w);

        return new EndOfDayReportData(
                storeName, date, closeTime, isAutomatic, true,
                totalSales, totalRevenue, cashAmount, momoAmount, avgBasket,
                topProducts, List.of(), lowStockCount, pendingSalesCount, pendingSalesTotal, employeeName
        );
    }

    /**
     * Returns UUIDs of employees who completed at least one sale in the window.
     * Used by EndOfDayReportListener to enumerate reports to generate.
     */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<UUID> getDistinctEmployeeIds(UUID storeId, Instant windowStart, Instant windowEnd) {
        return getDistinctEmployeeIds(storeId, ClosureWindow.between(windowStart, windowEnd));
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<UUID> getDistinctEmployeeIds(UUID storeId, ClosureWindow window) {
        Window w = Window.of(window);
        List<Object> rows = w.bind(entityManager.createNativeQuery(
                "SELECT DISTINCT s.employee_id FROM sales s " +
                "WHERE s.store_id = CAST(:sid AS UUID) " +
                "  AND " + WINDOW_S + " " +
                "  AND s.status = 'COMPLETED' " +
                "  AND s.employee_id IS NOT NULL"))
                .setParameter("sid", storeId.toString())
                .getResultList();
        return rows.stream()
                .filter(r -> r != null)
                .map(r -> UUID.fromString(r.toString()))
                .toList();
    }

    // ── Store-level aggregation queries ─────────────────────────────────────

    private int countCompletedSales(String storeId, Window w) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "AND " + WINDOW)
                .setParameter("sid", storeId)
                .setParameter("start", w.start())
                .setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom())
                .setParameter("until", w.until())
                .getSingleResult());
    }

    private int sumRevenue(String storeId, Window w) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' " +
                "AND " + WINDOW)
                .setParameter("sid", storeId)
                .setParameter("start", w.start())
                .setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom())
                .setParameter("until", w.until())
                .getSingleResult());
    }

    private int sumByPaymentMode(String storeId, Window w, String mode) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'COMPLETED' AND payment_mode = :mode " +
                "AND " + WINDOW)
                .setParameter("sid", storeId)
                .setParameter("mode", mode)
                .setParameter("start", w.start())
                .setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom())
                .setParameter("until", w.until())
                .getSingleResult());
    }

    private int countPendingSales(String storeId, Window w) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'PENDING_VALIDATION' " +
                "AND " + WINDOW)
                .setParameter("sid", storeId)
                .setParameter("start", w.start())
                .setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom())
                .setParameter("until", w.until())
                .getSingleResult());
    }

    private int sumPendingRevenue(String storeId, Window w) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND status = 'PENDING_VALIDATION' " +
                "AND " + WINDOW)
                .setParameter("sid", storeId)
                .setParameter("start", w.start())
                .setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom())
                .setParameter("until", w.until())
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
    private List<TopProductEntry> getTop3Products(String storeId, Window w) {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT si.product_name, SUM(si.quantity) AS total_qty, " +
                "       SUM(si.subtotal) AS total_revenue " +
                "FROM sale_items si JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.store_id = CAST(:sid AS UUID) AND " + WINDOW_S + " " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY si.product_name ORDER BY total_qty DESC LIMIT 3")
                .setParameter("sid", storeId)
                .setParameter("start", w.start())
                .setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom())
                .setParameter("until", w.until())
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
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT s.employee_id, " +
                "       COALESCE(e.first_name || ' ' || e.last_name, 'Employé') AS emp_name, " +
                "       COUNT(s.id) AS sale_count, SUM(s.total_amount) AS total_revenue " +
                "FROM sales s " +
                "LEFT JOIN employees e ON s.employee_id = e.user_id " +
                "WHERE s.store_id = CAST(:sid AS UUID) AND " + WINDOW_S + " " +
                "  AND s.status = 'COMPLETED' " +
                "GROUP BY s.employee_id, e.first_name, e.last_name " +
                "ORDER BY total_revenue DESC")
                .setParameter("sid", storeId)
                .setParameter("start", w.start())
                .setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom())
                .setParameter("until", w.until())
                .getResultList();
        return rows.stream()
                .map(r -> new EmployeeEntry(
                        (String) r[1],
                        ((Number) r[2]).intValue(),
                        ((Number) r[3]).intValue()))
                .toList();
    }

    // ── Employee-scoped aggregation queries ──────────────────────────────────

    private int countCompletedSalesByEmployee(String storeId, String empId, Window w) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND employee_id = CAST(:eid AS UUID) " +
                "AND status = 'COMPLETED' AND " + WINDOW)
                .setParameter("sid", storeId).setParameter("eid", empId)
                .setParameter("start", w.start()).setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom()).setParameter("until", w.until())
                .getSingleResult());
    }

    private int sumRevenueByEmployee(String storeId, String empId, Window w) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND employee_id = CAST(:eid AS UUID) " +
                "AND status = 'COMPLETED' AND " + WINDOW)
                .setParameter("sid", storeId).setParameter("eid", empId)
                .setParameter("start", w.start()).setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom()).setParameter("until", w.until())
                .getSingleResult());
    }

    private int sumByPaymentModeByEmployee(String storeId, String empId,
                                           Window w, String mode) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND employee_id = CAST(:eid AS UUID) " +
                "AND status = 'COMPLETED' AND payment_mode = :mode " +
                "AND " + WINDOW)
                .setParameter("sid", storeId).setParameter("eid", empId).setParameter("mode", mode)
                .setParameter("start", w.start()).setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom()).setParameter("until", w.until())
                .getSingleResult());
    }

    private int countPendingSalesByEmployee(String storeId, String empId, Window w) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND employee_id = CAST(:eid AS UUID) " +
                "AND status = 'PENDING_VALIDATION' AND " + WINDOW)
                .setParameter("sid", storeId).setParameter("eid", empId)
                .setParameter("start", w.start()).setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom()).setParameter("until", w.until())
                .getSingleResult());
    }

    private int sumPendingRevenueByEmployee(String storeId, String empId, Window w) {
        return toInt(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM sales " +
                "WHERE store_id = CAST(:sid AS UUID) AND employee_id = CAST(:eid AS UUID) " +
                "AND status = 'PENDING_VALIDATION' AND " + WINDOW)
                .setParameter("sid", storeId).setParameter("eid", empId)
                .setParameter("start", w.start()).setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom()).setParameter("until", w.until())
                .getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private List<TopProductEntry> getTop3ProductsByEmployee(String storeId, String empId,
                                                            Window w) {
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT si.product_name, SUM(si.quantity) AS total_qty, SUM(si.subtotal) AS total_revenue " +
                "FROM sale_items si JOIN sales s ON si.sale_id = s.id " +
                "WHERE s.store_id = CAST(:sid AS UUID) AND s.employee_id = CAST(:eid AS UUID) " +
                "AND " + WINDOW_S + " AND s.status = 'COMPLETED' " +
                "GROUP BY si.product_name ORDER BY total_qty DESC LIMIT 3")
                .setParameter("sid", storeId).setParameter("eid", empId)
                .setParameter("start", w.start()).setParameter("end", w.end())
                .setParameter("lateFrom", w.lateFrom()).setParameter("until", w.until())
                .getResultList();
        return rows.stream()
                .map(r -> new TopProductEntry(
                        (String) r[0],
                        ((Number) r[1]).intValue(),
                        ((Number) r[2]).intValue()))
                .toList();
    }

    private static int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }
}
