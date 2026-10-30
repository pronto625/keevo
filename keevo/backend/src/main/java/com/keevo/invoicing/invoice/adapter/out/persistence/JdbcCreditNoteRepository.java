package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.CreditNoteLine;
import com.keevo.invoicing.invoice.domain.model.InvoiceTotals;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CreditNoteRepository} on the module-owned tables {@code credit_notes}, {@code credit_note_items},
 * {@code credit_note_counters} of the tenant schema (Story 14.7) — plain JDBC, in the caller's transaction. Credit
 * notes are only ever inserted: no update, no delete.
 */
@Component
public class JdbcCreditNoteRepository implements CreditNoteRepository {

    private static final String ITEM_COLUMNS = "credit_note_id, position, invoice_position, description, quantity, "
            + "unit_price, vat_treatment, vat_rate, total_ht, total_vat, total_ttc";

    private final JdbcTemplate jdbc;

    public JdbcCreditNoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(CreditNote c) {
        jdbc.update("INSERT INTO " + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTES) + " (id, number, "
                        + "invoice_id, reason, total_ht, total_vat, total_ttc, issued_at, created_by, source_event) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.id(), c.number(), c.invoiceId(), c.reason(), c.totals().totalHt(), c.totals().totalVat(),
                c.totals().totalTtc(), Timestamp.from(c.issuedAt()), c.createdBy(), c.sourceEvent());
        List<Object[]> batch = new ArrayList<>();
        for (CreditNoteLine l : c.lines()) {
            batch.add(new Object[]{c.id(), l.position(), l.invoicePosition(), l.description(), l.quantity(),
                    l.unitPrice(), l.treatment().name(), l.vatRate(), l.totalHt(), l.totalVat(), l.totalTtc()});
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO " + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTE_ITEMS) + " ("
                    + ITEM_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", batch);
        }
    }

    @Override
    public Optional<CreditNote> findById(UUID id) {
        List<CreditNote> found = jdbc.query("SELECT c.id, c.number, c.invoice_id, i.number AS invoice_number, "
                        + "c.reason, c.total_ht, c.total_vat, c.total_ttc, c.issued_at, c.created_by, c.source_event "
                        + "FROM " + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTES) + " c JOIN "
                        + InvoicingTables.qualified(InvoicingTables.INVOICES) + " i ON i.id = c.invoice_id "
                        + "WHERE c.id = ?",
                (rs, n) -> new CreditNote(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"),
                        rs.getString("reason"), List.of(),
                        new InvoiceTotals(rs.getLong("total_ht"), rs.getLong("total_vat"), rs.getLong("total_ttc")),
                        rs.getTimestamp("issued_at").toInstant(), rs.getObject("created_by", UUID.class),
                        rs.getString("source_event")), id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CreditNote c = found.get(0);
        List<CreditNoteLine> lines = jdbc.query("SELECT " + ITEM_COLUMNS + " FROM "
                + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTE_ITEMS)
                + " WHERE credit_note_id = ? ORDER BY position", (rs, n) -> line(rs), id);
        return Optional.of(new CreditNote(c.id(), c.number(), c.invoiceId(), c.invoiceNumber(), c.reason(), lines,
                c.totals(), c.issuedAt(), c.createdBy(), c.sourceEvent()));
    }

    @Override
    public List<CreditNoteLine> linesOfInvoice(UUID invoiceId) {
        return jdbc.query("SELECT " + prefixed("l") + " FROM "
                        + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTE_ITEMS) + " l JOIN "
                        + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTES) + " c ON c.id = l.credit_note_id "
                        + "WHERE c.invoice_id = ? ORDER BY c.issued_at, c.number, l.position",
                (rs, n) -> line(rs), invoiceId);
    }

    @Override
    public boolean existsForInvoice(UUID invoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM "
                + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTES) + " WHERE invoice_id = ?)", Boolean.class,
                invoiceId));
    }

    @Override
    public boolean existsBySourceEvent(String sourceEvent) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM "
                + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTES) + " WHERE source_event = ?)", Boolean.class,
                sourceEvent));
    }

    @Override
    public Map<UUID, List<Summary>> summaries(Collection<UUID> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Summary>> byInvoice = new LinkedHashMap<>();
        jdbc.query("SELECT id, number, invoice_id, issued_at, total_ttc, reason FROM "
                        + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTES) + " WHERE invoice_id IN ("
                        + String.join(", ", Collections.nCopies(invoiceIds.size(), "?"))
                        + ") ORDER BY issued_at, number",
                rs -> {
                    byInvoice.computeIfAbsent(rs.getObject("invoice_id", UUID.class), k -> new ArrayList<>())
                            .add(new Summary(rs.getObject("id", UUID.class), rs.getString("number"),
                                    rs.getTimestamp("issued_at").toInstant(), rs.getLong("total_ttc"),
                                    rs.getString("reason")));
                },
                invoiceIds.toArray());
        return byInvoice;
    }

    @Override
    public long nextNumber(int year) {
        String table = InvoicingTables.qualified(InvoicingTables.CREDIT_NOTE_COUNTERS);
        Long next = jdbc.queryForObject("INSERT INTO " + table + " AS c (year, last_value) VALUES (?, 1) "
                + "ON CONFLICT (year) DO UPDATE SET last_value = c.last_value + 1 RETURNING last_value",
                Long.class, year);
        if (next == null) {
            throw new IllegalStateException("Credit note counter not incremented");
        }
        return next;
    }

    private static String prefixed(String alias) {
        return alias + "." + ITEM_COLUMNS.replace(", ", ", " + alias + ".");
    }

    private static CreditNoteLine line(ResultSet rs) throws SQLException {
        return new CreditNoteLine(rs.getInt("position"), rs.getInt("invoice_position"), rs.getString("description"),
                rs.getBigDecimal("quantity"), rs.getLong("unit_price"),
                VatTreatment.valueOf(rs.getString("vat_treatment")), rs.getBigDecimal("vat_rate"),
                rs.getLong("total_ht"), rs.getLong("total_vat"), rs.getLong("total_ttc"));
    }
}
