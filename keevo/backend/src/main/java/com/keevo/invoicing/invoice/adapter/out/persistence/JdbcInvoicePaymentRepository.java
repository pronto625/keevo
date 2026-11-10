package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link InvoicePaymentRepository} on {@code invoice_payments} and {@code invoices.payment_state} of the tenant schema
 * (Story 20.4, invoicing V10) — plain JDBC in the caller's transaction, like {@link JdbcInvoiceRepository}.
 */
@Component
public class JdbcInvoicePaymentRepository implements InvoicePaymentRepository {

    private static final String COLUMNS = "id, invoice_id, amount, due_date, paid_at, method, note";
    /** Chronological: payment date, or due date of an unpaid instalment; rows of one request never tie on it alone. */
    private static final String ORDER = "COALESCE(paid_at, CAST(due_date AS timestamptz)), created_at, id";

    private final JdbcTemplate jdbc;

    public JdbcInvoicePaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<InvoicePayment> findByInvoice(UUID invoiceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + InvoicingTables.qualified(InvoicingTables.PAYMENTS)
                + " WHERE invoice_id = ? ORDER BY " + ORDER, (rs, n) -> payment(rs), invoiceId);
    }

    @Override
    public Optional<InvoicePayment> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM " + InvoicingTables.qualified(InvoicingTables.PAYMENTS)
                + " WHERE id = ?", (rs, n) -> payment(rs), id).stream().findFirst();
    }

    @Override
    public boolean insertIfAbsent(InvoicePayment p, UUID createdBy, Instant now) {
        return jdbc.update("INSERT INTO " + InvoicingTables.qualified(InvoicingTables.PAYMENTS)
                        + " (" + COLUMNS + ", created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO NOTHING",
                p.id(), p.invoiceId(), p.amount(), p.dueDate() == null ? null : Date.valueOf(p.dueDate()),
                ts(p.paidAt()), p.method(), p.note(), createdBy, ts(now), ts(now)) == 1;
    }

    @Override
    public boolean anyPaid(UUID invoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM "
                + InvoicingTables.qualified(InvoicingTables.PAYMENTS) + " WHERE invoice_id = ? AND paid_at IS NOT NULL)",
                Boolean.class, invoiceId));
    }

    @Override
    public int settleUnpaid(UUID invoiceId, Instant paidAt, String method, Instant now) {
        return jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.PAYMENTS)
                + " SET paid_at = ?, method = COALESCE(?, method), updated_at = ? "
                + "WHERE invoice_id = ? AND paid_at IS NULL", ts(paidAt), method, ts(now), invoiceId);
    }

    @Override
    public void settle(UUID id, Instant paidAt, String method, Instant now) {
        int updated = jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.PAYMENTS)
                + " SET paid_at = ?, method = COALESCE(?, method), updated_at = ? WHERE id = ? AND paid_at IS NULL",
                ts(paidAt), method, ts(now), id);
        if (updated != 1) {
            throw new IllegalStateException("Invoice payment settle did not update exactly one row");
        }
    }

    @Override
    public void storeState(UUID invoiceId, PaymentState state, Instant now) {
        jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " SET payment_state = ?, updated_at = GREATEST(updated_at, ?) WHERE id = ?",
                state == null ? null : state.name(), ts(now), invoiceId);
    }

    @Override
    public void updateAmount(UUID id, long amount, Instant now) {
        jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.PAYMENTS)
                + " SET amount = ?, updated_at = ? WHERE id = ? AND paid_at IS NULL", amount, ts(now), id);
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM " + InvoicingTables.qualified(InvoicingTables.PAYMENTS)
                + " WHERE id = ? AND paid_at IS NULL", id);
    }

    @Override
    public PaymentState storedState(UUID invoiceId) {
        List<String> states = jdbc.queryForList("SELECT payment_state FROM "
                + InvoicingTables.qualified(InvoicingTables.INVOICES) + " WHERE id = ?", String.class, invoiceId);
        return states.isEmpty() ? null : PaymentState.fromStored(states.get(0));
    }

    @Override
    public void touch(UUID invoiceId, Instant now) {
        jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " SET updated_at = GREATEST(updated_at, ?) WHERE id = ?", ts(now), invoiceId);
    }

    @Override
    public Map<UUID, Ledger> ledgers(Collection<UUID> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PaymentState> states = new HashMap<>();
        Map<UUID, List<InvoicePayment>> rows = new HashMap<>();
        // one query for the page: the invoice's stored state, LEFT JOIN its rows (idx_invoice_payments_invoice)
        jdbc.query("SELECT i.id AS inv, i.payment_state, p.id, p.invoice_id, p.amount, p.due_date, p.paid_at, "
                        + "p.method, p.note FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " i LEFT JOIN "
                        + InvoicingTables.qualified(InvoicingTables.PAYMENTS) + " p ON p.invoice_id = i.id WHERE i.id IN ("
                        + String.join(", ", Collections.nCopies(invoiceIds.size(), "?"))
                        + ") ORDER BY i.id, COALESCE(p.paid_at, CAST(p.due_date AS timestamptz)), p.created_at, p.id",
                rs -> {
                    UUID invoice = rs.getObject("inv", UUID.class);
                    states.put(invoice, PaymentState.fromStored(rs.getString("payment_state")));
                    List<InvoicePayment> list = rows.computeIfAbsent(invoice, k -> new ArrayList<>());
                    if (rs.getObject("id") != null) {
                        list.add(payment(rs));
                    }
                },
                invoiceIds.toArray());
        Map<UUID, Ledger> out = new HashMap<>();
        rows.forEach((id, list) -> out.put(id, new Ledger(states.get(id), list)));
        return out;
    }

    @Override
    public List<String> markOverdue(LocalDate today, Instant now) {
        // set-based: the candidates are the union of two index-driven scans — unpaid instalments past due
        // (idx_invoice_payments_unpaid_due) and, Story 20.5, active issued invoices past their own due date
        // (idx_invoices_issued_due_date_active, V12) with less received than due (total TTC − credit notes) — then one
        // UPDATE joined on them. Only tenants at invoicing V11+ get here (schemasWithPayments).
        String invoices = InvoicingTables.qualified(InvoicingTables.INVOICES);
        String payments = InvoicingTables.qualified(InvoicingTables.PAYMENTS);
        List<String> numbers = jdbc.queryForList("WITH cand AS ("
                        + "SELECT invoice_id AS id FROM " + payments + " WHERE paid_at IS NULL AND due_date < ? "
                        + "UNION "
                        + "SELECT d.id FROM " + invoices + " d WHERE d.kind = 'INVOICE' AND d.status = 'ISSUED' "
                        + "AND d.due_date < ? AND d.deleted_at IS NULL AND d.superseded_at IS NULL "
                        + "AND d.total_ttc - COALESCE((SELECT SUM(c.total_ttc) FROM "
                        + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTES) + " c WHERE c.invoice_id = d.id), 0)"
                        + " > COALESCE((SELECT SUM(p.amount) FROM " + payments
                        + " p WHERE p.invoice_id = d.id AND p.paid_at IS NOT NULL), 0)) "
                        + "UPDATE " + invoices + " i SET payment_state = 'OVERDUE', "
                        + "updated_at = GREATEST(i.updated_at, ?) FROM cand WHERE i.id = cand.id "
                        + "AND i.kind = 'INVOICE' AND i.status = 'ISSUED' AND i.deleted_at IS NULL "
                        + "AND i.superseded_at IS NULL "
                        + "AND i.payment_state IS DISTINCT FROM 'OVERDUE' AND i.payment_state IS DISTINCT FROM 'PAID' "
                        + "RETURNING i.number",
                String.class, Date.valueOf(today), Date.valueOf(today), ts(now));
        return numbers.stream().sorted().toList(); // the numbering order, for a stable message
    }

    @Override
    public Set<String> schemasWithPayments() {
        // Story 20.5 — one catalogue query for every tenant: invoice_payments (V10) and invoices.due_date (V11); a tenant
        // below V11 (migrated lazily on its next invoicing call) is skipped like one without invoice_payments
        return new HashSet<>(jdbc.queryForList("SELECT t.schemaname FROM pg_catalog.pg_tables t "
                + "WHERE t.tablename = 'invoice_payments' AND t.schemaname ~ '^kv_' AND EXISTS (SELECT 1 FROM "
                + "information_schema.columns col WHERE col.table_schema = t.schemaname AND col.table_name = 'invoices' "
                + "AND col.column_name = 'due_date')", String.class));
    }

    /** Bound on the payment methods returned (free text: a tenant could have typed many variants). */
    static final int MAX_METHODS = 20;

    @Override
    public Collected collected(Instant from, Instant to, com.keevo.invoicing.invoice.domain.model.DocumentScope scope,
                               UUID storeId) {
        // Story 21.8 — one aggregate: range on paid_at (partial index idx_invoice_payments_paid_at, V17), joined to
        // the document by primary key; scope predicate from DocumentScope (20.14), like the invoice list
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        StringBuilder where = new StringBuilder(" WHERE p.paid_at IS NOT NULL AND p.paid_at >= ? AND p.paid_at < ? "
                + "AND i.kind = 'INVOICE' AND i.status IN ('ISSUED', 'PAID') AND i.deleted_at IS NULL "
                + "AND i.superseded_at IS NULL");
        if (scope != null && !scope.all()) {
            com.keevo.invoicing.invoice.domain.model.DocumentScope.Sql scoped = scope.sql("i");
            where.append(" AND ").append(scoped.condition());
            args.addAll(scoped.args());
        }
        if (storeId != null) {
            where.append(" AND i.store_id = ?");
            args.add(storeId);
        }
        // one bucket per method whatever its case or spacing (« Espèces », « espèces ») and one for « not given »
        // (NULL or blank), labelled by its most frequent spelling
        List<MethodTotal> methods = jdbc.query("SELECT mode() WITHIN GROUP (ORDER BY NULLIF(trim(p.method), '')) "
                        + "AS method, COUNT(*) AS n, SUM(p.amount) AS total FROM "
                        + InvoicingTables.qualified(InvoicingTables.PAYMENTS) + " p JOIN "
                        + InvoicingTables.qualified(InvoicingTables.INVOICES) + " i ON i.id = p.invoice_id" + where
                        + " GROUP BY lower(NULLIF(trim(p.method), '')) ORDER BY total DESC, 1",
                (rs, n) -> new MethodTotal(rs.getString("method"), rs.getLong("n"), rs.getLong("total")),
                args.toArray());
        long count = 0;
        long amount = 0;
        for (MethodTotal m : methods) {
            count += m.count();
            amount += m.amount();
        }
        if (methods.size() <= MAX_METHODS) {
            return new Collected(count, amount, methods, 0, 0);
        }
        long otherCount = 0;
        long otherAmount = 0;
        for (MethodTotal m : methods.subList(MAX_METHODS, methods.size())) {
            otherCount += m.count();
            otherAmount += m.amount();
        }
        return new Collected(count, amount, methods.subList(0, MAX_METHODS), otherCount, otherAmount);
    }

    private static InvoicePayment payment(ResultSet rs) throws SQLException {
        Date due = rs.getDate("due_date");
        Timestamp paid = rs.getTimestamp("paid_at");
        return new InvoicePayment(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                rs.getLong("amount"), due == null ? null : due.toLocalDate(), paid == null ? null : paid.toInstant(),
                rs.getString("method"), rs.getString("note"), false);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
