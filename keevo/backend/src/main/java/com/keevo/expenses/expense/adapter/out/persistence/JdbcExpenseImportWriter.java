package com.keevo.expenses.expense.adapter.out.persistence;

import com.keevo.expenses.api.ExpenseImportPort.ImportedExpense;
import com.keevo.expenses.expense.domain.port.out.ExpenseImportWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.4 — {@link ExpenseImportWriter} as one statement on the bound tenant's {@code expenses}
 * ({@link JdbcExpenseRepository#table()}): {@code INSERT … SELECT FROM unnest(…) ON CONFLICT (id) DO NOTHING RETURNING
 * id}; amounts as exact decimal text, dates as ISO-8601 text.
 */
@Component
class JdbcExpenseImportWriter implements ExpenseImportWriter {

    /** Story 24.9 — rows per insert statement (a large owner's expenses). */
    static final int CHUNK = 2_000;

    private final JdbcTemplate jdbc;

    JdbcExpenseImportWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> insertAbsent(List<ImportedExpense> expenses) {
        // Story 24.9 — blocks of CHUNK rows, in order, in the caller's transaction
        Set<UUID> inserted = new LinkedHashSet<>();
        for (int from = 0; from < expenses.size(); from += CHUNK) {
            inserted.addAll(insertBlock(expenses.subList(from, Math.min(expenses.size(), from + CHUNK))));
        }
        return inserted;
    }

    private Set<UUID> insertBlock(List<ImportedExpense> expenses) {
        if (expenses.isEmpty()) {
            return Set.of();
        }
        String sql = "INSERT INTO " + JdbcExpenseRepository.table() + " (id, store_id, category, amount, expense_date, "
                + "label, payment_method, created_by, created_at, updated_at, archived_at) "
                + "SELECT id, store_id, category, amount::numeric, expense_date::date, label, payment_method, "
                + "created_by, created_at::timestamptz, updated_at::timestamptz, archived_at::timestamptz "
                + "FROM unnest(?::uuid[], ?::uuid[], ?::varchar[], ?::text[], ?::text[], ?::varchar[], ?::varchar[], "
                + "?::uuid[], ?::text[], ?::text[], ?::text[]) AS x(id, store_id, category, amount, expense_date, "
                + "label, payment_method, created_by, created_at, updated_at, archived_at) "
                + "ON CONFLICT (id) DO NOTHING RETURNING id";
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setArray(1, con.createArrayOf("uuid", column(expenses, ImportedExpense::id)));
            ps.setArray(2, con.createArrayOf("uuid", column(expenses, ImportedExpense::storeId)));
            ps.setArray(3, con.createArrayOf("varchar", column(expenses, ImportedExpense::category)));
            ps.setArray(4, con.createArrayOf("text", column(expenses, e -> e.amount().toPlainString())));
            ps.setArray(5, con.createArrayOf("text", column(expenses, e -> e.expenseDate().toString())));
            ps.setArray(6, con.createArrayOf("varchar", column(expenses, ImportedExpense::label)));
            ps.setArray(7, con.createArrayOf("varchar", column(expenses, ImportedExpense::paymentMethod)));
            ps.setArray(8, con.createArrayOf("uuid", column(expenses, ImportedExpense::createdBy)));
            ps.setArray(9, con.createArrayOf("text", column(expenses, e -> text(e.createdAt()))));
            ps.setArray(10, con.createArrayOf("text", column(expenses, e -> text(e.updatedAt()))));
            ps.setArray(11, con.createArrayOf("text", column(expenses, e -> text(e.archivedAt()))));
            return ps;
        }, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        });
        return inserted;
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }

    private static Object[] column(List<ImportedExpense> rows, Function<ImportedExpense, Object> field) {
        return rows.stream().map(field).toArray();
    }
}
