package com.keevo.expenses.expense.adapter.out.persistence;

import com.keevo.expenses.expense.domain.model.Expense;
import com.keevo.expenses.expense.domain.model.ExpenseCategory;
import com.keevo.expenses.expense.domain.model.ExpenseCursor;
import com.keevo.expenses.expense.domain.model.ExpensePaymentMethod;
import com.keevo.expenses.expense.domain.model.ExpenseSort;
import com.keevo.expenses.expense.domain.port.out.ExpenseRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ExpenseRepository} on the module-owned table {@code expenses} of the tenant schema (Story 20.10) — plain
 * JDBC, every statement names {@code "kv_xxxxxx".expenses} (schema from {@link TenantContext}, validated). Lists are
 * keyset-paged on the sort's key columns (one row-value comparison, one index range scan, never OFFSET).
 */
@Component
public class JdbcExpenseRepository implements ExpenseRepository {

    static final String TABLE = "expenses";

    private static final String COLUMNS = "id, store_id, category, amount, expense_date, label, note, payment_method, "
            + "created_by, updated_by, created_at, updated_at, archived_at";
    private static final String PLACEHOLDERS = String.join(", ",
            Collections.nCopies(COLUMNS.split(",").length, "?"));

    private final JdbcTemplate jdbc;

    public JdbcExpenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code "kv_xxxxxx".expenses} for the bound tenant; no / invalid tenant → {@link IllegalStateException}. */
    static String table() {
        String schema = TenantContext.getCurrentTenant();
        try {
            TenantSchema.validate(schema);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Expenses used without a valid tenant schema bound");
        }
        return "\"" + schema + "\"." + TABLE;
    }

    @Override
    public boolean tablesExist() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table());
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<Expense> findById(UUID id) {
        return one("SELECT " + COLUMNS + " FROM " + table() + " WHERE id = ?", id);
    }

    @Override
    public Optional<Expense> findByIdForUpdate(UUID id) {
        return one("SELECT " + COLUMNS + " FROM " + table() + " WHERE id = ? FOR UPDATE", id);
    }

    private Optional<Expense> one(String sql, UUID id) {
        List<Expense> rows = jdbc.query(sql, (rs, n) -> row(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean insertIfAbsent(Expense e) {
        return jdbc.update("INSERT INTO " + table() + " (" + COLUMNS + ") VALUES (" + PLACEHOLDERS + ") "
                        + "ON CONFLICT (id) DO NOTHING",
                e.id(), e.storeId(), e.category().key(), e.amount(), Date.valueOf(e.expenseDate()), e.label(),
                e.note(), e.paymentMethod() == null ? null : e.paymentMethod().key(), e.createdBy(), e.updatedBy(),
                ts(e.createdAt()), ts(e.updatedAt()), ts(e.archivedAt())) == 1;
    }

    @Override
    public void update(Expense e) {
        int updated = jdbc.update("UPDATE " + table() + " SET store_id = ?, category = ?, amount = ?, expense_date = ?, "
                        + "label = ?, note = ?, payment_method = ?, updated_by = ?, updated_at = ?, archived_at = ? "
                        + "WHERE id = ?",
                e.storeId(), e.category().key(), e.amount(), Date.valueOf(e.expenseDate()), e.label(), e.note(),
                e.paymentMethod() == null ? null : e.paymentMethod().key(), e.updatedBy(), ts(e.updatedAt()),
                ts(e.archivedAt()), e.id());
        if (updated != 1) {
            throw new IllegalStateException("Expense not updated");
        }
    }

    @Override
    public List<Expense> page(Filter filter, ExpenseSort sort, ExpenseCursor after, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM " + table() + " WHERE ");
        where(filter, sql, args);
        List<String> keys = sort.keys().columns();
        if (after != null) {
            // row-value comparison in the index order: the scan starts right after the previous page
            sql.append(" AND (").append(String.join(", ", keys)).append(") ")
                    .append(sort.descending() ? "<" : ">").append(" (?, ?, ?)");
            for (Object v : after.values()) {
                args.add(sqlValue(v));
            }
        }
        String direction = sort.descending() ? " DESC" : " ASC";
        sql.append(" ORDER BY ").append(String.join(direction + ", ", keys)).append(direction).append(" LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> row(rs), args.toArray());
    }

    @Override
    public Totals totals(Filter filter) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS total FROM "
                + table() + " WHERE ");
        where(filter, sql, args);
        return jdbc.queryForObject(sql.toString(), (rs, n) -> new Totals(rs.getLong("n"), rs.getBigDecimal("total")),
                args.toArray());
    }

    /** The filters, every one a bound parameter; live rows only (the partial indexes' predicate). */
    private static void where(Filter f, StringBuilder sql, List<Object> args) {
        sql.append("archived_at IS NULL");
        if (f.storeId() != null) {
            sql.append(" AND store_id = ?");
            args.add(f.storeId());
        }
        if (f.category() != null) {
            sql.append(" AND category = ?");
            args.add(f.category().key());
        }
        if (f.from() != null) {
            sql.append(" AND expense_date >= ?");
            args.add(Date.valueOf(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND expense_date <= ?");
            args.add(Date.valueOf(f.to()));
        }
        if (f.search() != null) {
            String pattern = "%" + escapeLike(f.search()) + "%";
            sql.append(" AND (label ILIKE ? ESCAPE '\\' OR note ILIKE ? ESCAPE '\\')");
            args.add(pattern);
            args.add(pattern);
        }
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public List<Expense> delta(Instant since, Instant afterCursor, String afterId, UUID storeId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM " + table() + " WHERE " + SYNC_CURSOR
                + " >= ?");
        args.add(ts(since));
        if (storeId != null) {
            sql.append(" AND store_id = ?");
            args.add(storeId);
        }
        if (afterCursor != null && afterId != null) {
            sql.append(" AND (" + SYNC_CURSOR + ", CAST(id AS text)) > (?, ?)");
            args.add(ts(afterCursor));
            args.add(afterId);
        }
        sql.append(" ORDER BY " + SYNC_CURSOR + ", CAST(id AS text) LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, n) -> row(rs), args.toArray());
    }

    private static Object sqlValue(Object value) {
        if (value instanceof LocalDate d) {
            return Date.valueOf(d);
        }
        if (value instanceof Instant i) {
            return Timestamp.from(i);
        }
        return value;
    }

    private static Expense row(ResultSet rs) throws SQLException {
        String method = rs.getString("payment_method");
        return new Expense(
                rs.getObject("id", UUID.class),
                rs.getObject("store_id", UUID.class),
                ExpenseCategory.fromKey(rs.getString("category")).orElseThrow(),
                rs.getBigDecimal("amount"),
                rs.getDate("expense_date").toLocalDate(),
                rs.getString("label"),
                rs.getString("note"),
                method == null ? null : ExpensePaymentMethod.fromKey(method).orElseThrow(),
                rs.getObject("created_by", UUID.class),
                rs.getObject("updated_by", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "archived_at"));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
