package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceDelivery;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoiceTotals;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link InvoiceRepository} on the module-owned tables {@code invoices}, {@code invoice_items},
 * {@code invoice_counters} of the tenant schema (Story 14.1) — plain JDBC, no JPA entity. Runs in the caller's
 * transaction (the JPA transaction manager exposes its connection to {@link JdbcTemplate}).
 */
@Component
public class JdbcInvoiceRepository implements InvoiceRepository {

    private static final String COLUMNS = "id, number, status, issue_date, client_id, client_name, client_niu, "
            + "client_address, client_phone, seller_legal_name, seller_address, seller_phone, seller_niu, seller_rccm, "
            + "seller_payment_details, seller_footer, vat_registered, vat_rate, prices_include_vat, vat_mention, "
            + "total_ht, total_vat, total_ttc, issued_at, paid_at, payment_method, cancelled_at, cancellation_reason, "
            + "created_by, created_at, updated_at, last_sent_at, last_sent_to, send_count, seller_name_from_display, "
            + "seller_has_logo, source_sale_id";
    /** Story 14.7 — read only: set by {@link #markSuperseded}, never inserted. */
    private static final String SELECT_COLUMNS = COLUMNS + ", superseded_at";

    private final JdbcTemplate jdbc;

    public JdbcInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Invoice> findById(UUID id) {
        return findOne(id, "");
    }

    @Override
    public Optional<Invoice> findByIdForUpdate(UUID id) {
        return findOne(id, " FOR UPDATE");
    }

    private Optional<Invoice> findOne(UUID id, String lock) {
        List<Row> rows = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " WHERE id = ?" + lock, (rs, n) -> row(rs), id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0).with(lines(List.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public boolean existsBySourceSale(UUID saleId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM "
                + InvoicingTables.qualified(InvoicingTables.INVOICES) + " WHERE source_sale_id = ?)", Boolean.class,
                saleId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<Invoice> findActiveBySourceSaleForUpdate(UUID saleId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " WHERE source_sale_id = ? AND superseded_at IS NULL FOR UPDATE", UUID.class, saleId);
        return ids.isEmpty() ? Optional.empty() : findById(ids.get(0));
    }

    @Override
    public void markSuperseded(UUID id, Instant at) {
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " SET superseded_at = ? WHERE id = ? AND superseded_at IS NULL", ts(at), id), "supersede");
    }

    @Override
    public void insert(Invoice invoice) {
        jdbc.update("INSERT INTO " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " (" + COLUMNS + ") VALUES "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                values(invoice));
        insertLines(invoice);
    }

    @Override
    public void updateDraft(Invoice draft) {
        ClientInfo c = draft.client();
        InvoiceTotals t = draft.totals();
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " SET client_id = ?, "
                        + "client_name = ?, client_niu = ?, client_address = ?, client_phone = ?, vat_registered = ?, "
                        + "vat_rate = ?, prices_include_vat = ?, vat_mention = ?, total_ht = ?, total_vat = ?, "
                        + "total_ttc = ?, updated_at = ? WHERE id = ? AND status = 'DRAFT'",
                c.clientId(), c.name(), c.niu(), c.address(), c.phone(), draft.vatRegistered(), draft.vatRate(),
                draft.pricesIncludeVat(), draft.vatMention(), t.totalHt(), t.totalVat(), t.totalTtc(),
                ts(draft.updatedAt()), draft.id()), "draft update");
        replaceLines(draft);
    }

    @Override
    public void issue(Invoice issued) {
        SellerSnapshot s = issued.seller();
        InvoiceTotals t = issued.totals();
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " SET number = ?, "
                        + "status = 'ISSUED', issue_date = ?, seller_legal_name = ?, seller_address = ?, "
                        + "seller_phone = ?, seller_niu = ?, seller_rccm = ?, seller_payment_details = ?, "
                        + "seller_footer = ?, vat_registered = ?, vat_rate = ?, prices_include_vat = ?, "
                        + "vat_mention = ?, total_ht = ?, total_vat = ?, total_ttc = ?, issued_at = ?, updated_at = ?, "
                        + "seller_name_from_display = ?, seller_has_logo = ? "
                        + "WHERE id = ? AND status = 'DRAFT'",
                issued.number(), Date.valueOf(issued.issueDate()), s.legalName(), s.address(), s.phone(), s.niu(),
                s.rccm(), s.paymentDetails(), s.footer(), issued.vatRegistered(), issued.vatRate(),
                issued.pricesIncludeVat(), issued.vatMention(), t.totalHt(), t.totalVat(), t.totalTtc(),
                ts(issued.issuedAt()), ts(issued.updatedAt()), s.nameFromDisplayName(), s.hasLogo(), issued.id()),
                "issue");
        // lines are recomputed with the settings of the issue, then frozen
        replaceLines(issued);
    }

    @Override
    public void markPaid(Invoice paid) {
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " SET status = 'PAID', "
                        + "paid_at = ?, payment_method = ?, updated_at = ? WHERE id = ? AND status = 'ISSUED'",
                ts(paid.paidAt()), paid.paymentMethod(), ts(paid.updatedAt()), paid.id()), "payment");
    }

    @Override
    public void markCancelled(Invoice cancelled) {
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " SET status = 'CANCELLED', cancelled_at = ?, cancellation_reason = ?, updated_at = ? "
                        + "WHERE id = ? AND status = 'ISSUED'",
                ts(cancelled.cancelledAt()), cancelled.cancellationReason(), ts(cancelled.updatedAt()),
                cancelled.id()), "cancellation");
    }

    @Override
    public void markSent(Invoice sent) {
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " SET last_sent_at = ?, last_sent_to = ?, send_count = send_count + 1 "
                        + "WHERE id = ?",
                ts(sent.delivery().lastSentAt()), sent.delivery().lastSentTo(), sent.id()), "sending");
    }

    /** Only for a DRAFT edit or the issue itself: an issued invoice's lines are never rewritten. */
    private void replaceLines(Invoice invoice) {
        jdbc.update("DELETE FROM " + InvoicingTables.qualified(InvoicingTables.ITEMS) + " WHERE invoice_id = ?",
                invoice.id());
        insertLines(invoice);
    }

    /** The row is locked by the caller and its status checked by the domain: anything else is a bug. */
    private static void expectOne(int updated, String what) {
        if (updated != 1) {
            throw new IllegalStateException("Invoice " + what + " did not update exactly one row");
        }
    }

    @Override
    public boolean deleteDraft(UUID id) {
        return jdbc.update("DELETE FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " WHERE id = ? AND status = 'DRAFT'", id) == 1;
    }

    @Override
    public Page list(InvoiceStatus status, UUID saleId, int page, int size) {
        String table = InvoicingTables.qualified(InvoicingTables.INVOICES);
        List<String> conditions = new ArrayList<>();
        List<Object> filter = new ArrayList<>();
        if (status != null) {
            conditions.add("status = ?");
            filter.add(status.name());
        }
        if (saleId != null) {
            conditions.add("source_sale_id = ? AND superseded_at IS NULL");
            filter.add(saleId);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + where, Long.class, filter.toArray());
        List<Object> args = new ArrayList<>(filter);
        args.add(size);
        args.add((long) page * size);
        List<Row> rows = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM " + table + where
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", (rs, n) -> row(rs), args.toArray());
        Map<UUID, List<InvoiceLine>> lines = rows.isEmpty() ? Map.of() : lines(rows.stream().map(Row::id).toList());
        List<Invoice> items = rows.stream().map(r -> r.with(lines.getOrDefault(r.id(), List.of()))).toList();
        return new Page(items, total == null ? 0 : total);
    }

    @Override
    public long nextNumber(int year) {
        String table = InvoicingTables.qualified(InvoicingTables.COUNTERS);
        Long next = jdbc.queryForObject("INSERT INTO " + table + " AS c (year, last_number) VALUES (?, 1) "
                + "ON CONFLICT (year) DO UPDATE SET last_number = c.last_number + 1 RETURNING last_number",
                Long.class, year);
        if (next == null) {
            throw new IllegalStateException("Invoice counter not incremented");
        }
        return next;
    }

    // ── mapping ─────────────────────────────────────────────────────────────────────────────────────────────

    private void insertLines(Invoice invoice) {
        String table = InvoicingTables.qualified(InvoicingTables.ITEMS);
        List<Object[]> batch = new ArrayList<>();
        for (InvoiceLine line : invoice.lines()) {
            batch.add(new Object[]{UUID.randomUUID(), invoice.id(), line.position(), line.description(),
                    line.quantity(), line.unitPrice(), line.treatment().name(), line.vatRate(), line.totalHt(),
                    line.totalVat(), line.totalTtc()});
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO " + table + " (id, invoice_id, position, description, quantity, unit_price, "
                    + "vat_treatment, vat_rate, total_ht, total_vat, total_ttc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    batch);
        }
    }

    private Map<UUID, List<InvoiceLine>> lines(List<UUID> invoiceIds) {
        Map<UUID, List<InvoiceLine>> byInvoice = new LinkedHashMap<>();
        jdbc.query("SELECT invoice_id, position, description, quantity, unit_price, vat_treatment, vat_rate, total_ht, "
                        + "total_vat, total_ttc FROM " + InvoicingTables.qualified(InvoicingTables.ITEMS)
                        + " WHERE invoice_id IN (" + String.join(", ", java.util.Collections.nCopies(invoiceIds.size(), "?"))
                        + ") ORDER BY invoice_id, position",
                rs -> {
                    byInvoice.computeIfAbsent(rs.getObject("invoice_id", UUID.class), k -> new ArrayList<>())
                            .add(new InvoiceLine(rs.getInt("position"), rs.getString("description"),
                                    rs.getBigDecimal("quantity"), rs.getLong("unit_price"),
                                    VatTreatment.valueOf(rs.getString("vat_treatment")), rs.getBigDecimal("vat_rate"),
                                    rs.getLong("total_ht"), rs.getLong("total_vat"), rs.getLong("total_ttc")));
                },
                invoiceIds.toArray());
        return byInvoice;
    }

    private static Object[] values(Invoice i) {
        ClientInfo c = i.client();
        SellerSnapshot s = i.seller();
        return new Object[]{i.id(), i.number(), i.status().name(),
                i.issueDate() == null ? null : Date.valueOf(i.issueDate()),
                c.clientId(), c.name(), c.niu(), c.address(), c.phone(),
                s == null ? null : s.legalName(), s == null ? null : s.address(), s == null ? null : s.phone(),
                s == null ? null : s.niu(), s == null ? null : s.rccm(), s == null ? null : s.paymentDetails(),
                s == null ? null : s.footer(),
                i.vatRegistered(), i.vatRate(), i.pricesIncludeVat(), i.vatMention(),
                i.totals().totalHt(), i.totals().totalVat(), i.totals().totalTtc(),
                ts(i.issuedAt()), ts(i.paidAt()), i.paymentMethod(), ts(i.cancelledAt()), i.cancellationReason(),
                i.createdBy(), ts(i.createdAt()), ts(i.updatedAt()),
                ts(i.delivery().lastSentAt()), i.delivery().lastSentTo(), i.delivery().sendCount(),
                s != null && s.nameFromDisplayName(), s != null && s.hasLogo(), i.sourceSaleId()};
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    /** An invoice row without its lines. */
    private record Row(UUID id, Invoice invoice) {
        Invoice with(List<InvoiceLine> lines) {
            Invoice i = invoice;
            return new Invoice(i.id(), i.number(), i.status(), i.issueDate(), i.client(), i.seller(), i.vatRegistered(),
                    i.vatRate(), i.pricesIncludeVat(), i.vatMention(), lines, i.totals(), i.issuedAt(), i.paidAt(),
                    i.paymentMethod(), i.cancelledAt(), i.cancellationReason(), i.createdBy(), i.createdAt(),
                    i.updatedAt(), i.delivery(), i.sourceSaleId(), i.supersededAt());
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        InvoiceStatus status = InvoiceStatus.valueOf(rs.getString("status"));
        Date issueDate = rs.getDate("issue_date");
        SellerSnapshot seller = status == InvoiceStatus.DRAFT ? null : new SellerSnapshot(
                rs.getString("seller_legal_name"), rs.getString("seller_address"), rs.getString("seller_phone"),
                rs.getString("seller_niu"), rs.getString("seller_rccm"), rs.getString("seller_payment_details"),
                rs.getString("seller_footer"), rs.getBoolean("seller_name_from_display"),
                rs.getBoolean("seller_has_logo"));
        Invoice invoice = new Invoice(id, rs.getString("number"), status,
                issueDate == null ? null : issueDate.toLocalDate(),
                new ClientInfo(rs.getObject("client_id", UUID.class), rs.getString("client_name"),
                        rs.getString("client_niu"), rs.getString("client_address"), rs.getString("client_phone")),
                seller, rs.getBoolean("vat_registered"), rs.getBigDecimal("vat_rate"),
                rs.getBoolean("prices_include_vat"), rs.getString("vat_mention"), List.of(),
                new InvoiceTotals(rs.getLong("total_ht"), rs.getLong("total_vat"), rs.getLong("total_ttc")),
                instant(rs, "issued_at"), instant(rs, "paid_at"), rs.getString("payment_method"),
                instant(rs, "cancelled_at"), rs.getString("cancellation_reason"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"),
                new InvoiceDelivery(instant(rs, "last_sent_at"), rs.getString("last_sent_to"), rs.getInt("send_count")),
                rs.getObject("source_sale_id", UUID.class), instant(rs, "superseded_at"));
        return new Row(id, invoice);
    }
}
