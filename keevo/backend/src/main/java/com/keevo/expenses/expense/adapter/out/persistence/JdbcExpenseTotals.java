package com.keevo.expenses.expense.adapter.out.persistence;

import com.keevo.expenses.api.ExpenseTotalsQueryPort;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ExpenseTotalsQueryPort} on the tenant's {@code expenses} table (Story 20.11). Set-based: one
 * {@code GROUP BY GROUPING SETS ((cur), (cur, category), (cur, bucket)[, (cur, store_id)])} over the live rows of
 * {@code [previousFrom, to]} ({@code cur} = in the period, else the comparison period) — an index range scan of
 * {@code idx_expenses_date} (every store) or {@code idx_expenses_store_date} (one store), both partial on
 * {@code archived_at IS NULL} — then hash aggregates. No table (module never used, or dropped: SQLSTATE 42P01) →
 * zeros, nothing provisioned. The table is named through {@link JdbcExpenseRepository#table()} (validated schema).
 */
@Component
public class JdbcExpenseTotals implements ExpenseTotalsQueryPort {

    static final String UNDEFINED_TABLE = "42P01";

    private final JdbcTemplate jdbc;
    private final Set<String> withTable = ConcurrentHashMap.newKeySet();

    public JdbcExpenseTotals(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Breakdown breakdown(String tenantSchema, LocalDate previousFrom, LocalDate from, LocalDate to,
                               UUID storeId, Bucket bucket) {
        TenantContext.requireCurrent(tenantSchema);
        String table = JdbcExpenseRepository.table();
        if (!tableExists(table)) {
            return Breakdown.EMPTY;
        }
        boolean byStore = storeId == null;
        String format = bucket == Bucket.MONTH ? "YYYY-MM" : "YYYY-MM-DD"; // constant, never user input
        List<Object> args = new ArrayList<>(List.of(from, previousFrom, to));
        String storeFilter = "";
        if (!byStore) {
            args.add(storeId);
            storeFilter = " AND x.store_id = ?";
        }
        String sql = "SELECT GROUPING(e.category) AS g_category, GROUPING(e.bucket) AS g_bucket, "
                + (byStore ? "GROUPING(e.store_id)" : "1") + " AS g_store, e.cur, e.category, e.bucket, "
                + (byStore ? "e.store_id" : "NULL::uuid AS store_id") + ", "
                + "COALESCE(SUM(e.amount), 0) AS amount, COUNT(*) AS cnt "
                + "FROM (SELECT x.expense_date >= ? AS cur, x.category, "
                + "to_char(x.expense_date, '" + format + "') AS bucket, x.store_id, x.amount "
                + "FROM " + table + " x WHERE x.archived_at IS NULL "
                + "AND x.expense_date >= ? AND x.expense_date <= ?" + storeFilter + ") e "
                + "GROUP BY GROUPING SETS ((e.cur), (e.cur, e.category), (e.cur, e.bucket)"
                + (byStore ? ", (e.cur, e.store_id)" : "") + ")";
        Totals[] totals = {Totals.ZERO, Totals.ZERO};
        List<CategoryTotal> categories = new ArrayList<>();
        List<BucketTotal> buckets = new ArrayList<>();
        List<StoreTotal> stores = new ArrayList<>();
        // a savepoint keeps the caller's transaction usable if the table is gone (42P01 aborts the transaction)
        Boolean found = jdbc.execute((ConnectionCallback<Boolean>) con -> {
            Savepoint savepoint = con.getAutoCommit() ? null : con.setSavepoint();
            try {
                read(sql, args, totals, categories, buckets, stores);
            } catch (DataAccessException e) {
                if (!undefinedTable(e)) {
                    throw e;
                }
                if (savepoint != null) {
                    con.rollback(savepoint);
                }
                return false;
            }
            if (savepoint != null) {
                con.releaseSavepoint(savepoint);
            }
            return true;
        });
        if (!Boolean.TRUE.equals(found)) { // the table went away after it was cached
            withTable.remove(table);
            return Breakdown.EMPTY;
        }
        categories.sort(Comparator.comparing(CategoryTotal::amount).reversed()
                .thenComparing(CategoryTotal::category));
        return new Breakdown(totals[0], totals[1], List.copyOf(categories), List.copyOf(buckets), List.copyOf(stores));
    }

    private void read(String sql, List<Object> args, Totals[] totals, List<CategoryTotal> categories,
                      List<BucketTotal> buckets, List<StoreTotal> stores) {
            jdbc.query(sql, rs -> {
                boolean current = rs.getBoolean("cur");
                BigDecimal amount = rs.getBigDecimal("amount");
                if (rs.getInt("g_category") == 0) {
                    if (current) {
                        String key = rs.getString("category");
                        categories.add(new CategoryTotal(key, ExpenseCategory.fromKey(key)
                                .map(ExpenseCategory::label).orElse(ExpenseCategory.OTHER.label()), amount));
                    }
                } else if (rs.getInt("g_bucket") == 0) {
                    if (current) {
                        buckets.add(new BucketTotal(rs.getString("bucket"), amount));
                    }
                } else if (rs.getInt("g_store") == 0) {
                    if (current) {
                        stores.add(new StoreTotal(rs.getObject("store_id", UUID.class), amount));
                    }
                } else {
                    totals[current ? 0 : 1] = new Totals(amount, rs.getLong("cnt"));
                }
            }, args.toArray());
    }

    private static boolean undefinedTable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNDEFINED_TABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** One catalogue lookup until the table is found, then none (cached per tenant — evicted on 42P01). */
    private boolean tableExists(String table) {
        if (withTable.contains(table)) {
            return true;
        }
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                table));
        if (exists) {
            withTable.add(table);
        }
        return exists;
    }
}
