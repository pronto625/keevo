package com.keevo.finance.summary.adapter.out.persistence;

import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueBreakdown;
import com.keevo.finance.summary.domain.model.FinanceFigures.RevenueTotals;
import com.keevo.finance.summary.domain.model.FinanceMath.Granularity;
import com.keevo.finance.summary.domain.model.FinanceMath.Period;
import com.keevo.finance.summary.domain.port.out.FinanceRevenuePort;
import com.keevo.shared.capability.revenue.RevenueQueryPort;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.11 — {@link FinanceRevenuePort} on the single revenue source of 20.9 ({@link RevenueQueryPort}, core
 * capability {@code shared.capability.revenue} since 20-14) — the only class of the finance module that reads it.
 *
 * <p>Relation: every store → {@code current().taxedDocuments()}; one store → {@code storeScopedTaxed()}, the
 * store-scoped rule of reporting (documents with their store from invoicing V16, a credit note its invoice's), plus
 * {@code store_id = ?}. Documents without store (made before V16) count in the all-stores figures under
 * « Sans boutique ». Both read the sales with their VAT. Window: the civil
 * dates in WAT — from the comparison start 00:00 to {@code period.end + 1} 00:00 Africa/Lagos, pushed into each branch
 * (sales {@code idx_sales_completed_occurred_at} / {@code (store_id, occurred_at)}, invoices
 * {@code idx_invoices_revenue_day}, credit notes {@code idx_credit_notes_issued_at}); a flag splits the two periods.
 * Bucket keys from {@code occurred_at AT TIME ZONE 'Africa/Lagos'} (an invoice falls on its issue date).
 */
@Component
public class RevenueSourceFinanceAdapter implements FinanceRevenuePort {

    /** WAT — same offset as Africa/Douala (UTC+1, no DST). */
    static final ZoneId WAT = ZoneId.of("Africa/Lagos");

    private static final String SUMS = "COALESCE(SUM(r.total_amount), 0) AS ttc, COALESCE(SUM(r.tax_amount), 0) AS tax, "
            + "COALESCE(SUM(r.paid_amount), 0) AS paid, COALESCE(SUM(r.doc_count), 0) AS cnt";

    private final RevenueQueryPort revenue;
    private final JdbcTemplate jdbc;

    public RevenueSourceFinanceAdapter(RevenueQueryPort revenue, JdbcTemplate jdbc) {
        this.revenue = revenue;
        this.jdbc = jdbc;
    }

    @Override
    public RevenueBreakdown breakdown(String tenantSchema, Period period, Period comparison, UUID storeId,
                                      Granularity granularity) {
        TenantContext.requireCurrent(tenantSchema);
        boolean byStore = storeId == null;
        String relation = byStore ? revenue.current().taxedDocuments() : revenue.storeScopedTaxed().taxedDocuments();
        String format = granularity == Granularity.MONTH ? "YYYY-MM" : "YYYY-MM-DD"; // constant, never user input
        List<Object> args = new ArrayList<>(List.of(midnight(period.startDate()), midnight(comparison.startDate()),
                midnight(period.endDate().plusDays(1))));
        String storeFilter = "";
        if (!byStore) {
            args.add(storeId);
            storeFilter = " AND d.store_id = ?";
        }
        String sql = "SELECT GROUPING(r.bucket) AS g_bucket, "
                + (byStore ? "GROUPING(r.store_id)" : "1") + " AS g_store, r.cur, r.bucket, "
                + (byStore ? "r.store_id" : "NULL::uuid AS store_id") + ", " + SUMS
                + " FROM (SELECT d.occurred_at >= ? AS cur, "
                + "to_char(d.occurred_at AT TIME ZONE 'Africa/Lagos', '" + format + "') AS bucket, d.store_id, "
                + "d.total_amount, d.tax_amount, d.paid_amount, d.doc_count FROM " + relation + " d "
                + "WHERE d.occurred_at >= ? AND d.occurred_at < ?" + storeFilter + ") r "
                + "GROUP BY GROUPING SETS ((r.cur), (r.cur, r.bucket)" + (byStore ? ", (r.cur, r.store_id)" : "") + ")";
        RevenueTotals[] totals = {RevenueTotals.ZERO, RevenueTotals.ZERO};
        Map<String, BigDecimal> byBucket = new HashMap<>();
        Map<UUID, BigDecimal> perStore = new HashMap<>();
        BigDecimal[] withoutStore = {BigDecimal.ZERO};
        jdbc.query(sql, rs -> {
            boolean current = rs.getBoolean("cur");
            RevenueTotals row = totals(rs);
            BigDecimal excludingTax = row.ttc().subtract(row.tax());
            if (rs.getInt("g_bucket") == 0) {
                if (current) {
                    byBucket.put(rs.getString("bucket"), excludingTax);
                }
            } else if (rs.getInt("g_store") == 0) {
                if (current) {
                    UUID store = rs.getObject("store_id", UUID.class);
                    if (store == null) {
                        withoutStore[0] = excludingTax; // documents without store (until 20-14)
                    } else {
                        perStore.put(store, excludingTax);
                    }
                }
            } else {
                totals[current ? 0 : 1] = row;
            }
        }, args.toArray());
        return new RevenueBreakdown(totals[0], totals[1], Map.copyOf(byBucket), Map.copyOf(perStore), withoutStore[0]);
    }

    private static Timestamp midnight(LocalDate date) {
        return Timestamp.from(date.atStartOfDay(WAT).toInstant());
    }

    private static RevenueTotals totals(ResultSet rs) throws SQLException {
        return new RevenueTotals(rs.getBigDecimal("ttc"), rs.getBigDecimal("tax"), rs.getBigDecimal("paid"),
                rs.getLong("cnt"));
    }
}
