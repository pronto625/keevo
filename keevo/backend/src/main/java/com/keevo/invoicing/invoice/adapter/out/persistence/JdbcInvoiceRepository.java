package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.DocumentScope;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceDelivery;
import com.keevo.invoicing.invoice.domain.model.SendChannel;
import com.keevo.invoicing.invoice.domain.model.SendStatus;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import com.keevo.invoicing.invoice.domain.model.InvoiceTerms;
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
import java.util.Set;
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
            + "seller_has_logo, source_sale_id, kind, source_quote_id, "
            // Story 20.5 (V11)
            + "discount_amount, delivery_fee, subtotal, vat_rate_override, vat_exempt, due_date, client_rccm, "
            + "client_email, client_contact_name, client_website, "
            // Story 20.14 (V16) — written at creation only, never updated
            + "store_id";
    /** Placeholders of {@link #COLUMNS} in an INSERT. */
    private static final String PLACEHOLDERS = String.join(", ",
            java.util.Collections.nCopies(COLUMNS.split(",").length, "?"));
    /**
     * Story 14.7 — read only: set by {@link #markSuperseded}, never inserted. Story 20.3: the conversion link of a
     * quote, set by {@link #markConverted}. Story 20.8: the channel of the last sending, set by {@link #markSent}.
     */
    private static final String SELECT_COLUMNS = COLUMNS + ", superseded_at, converted_invoice_id, converted_at, "
            + "last_sent_channel, last_send_status, last_send_status_channel, last_send_error_code";

    private final JdbcTemplate jdbc;

    public JdbcInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Story 20.3 — the display reference of a quote, computed in SQL like {@code InvoiceKind.quoteReference}. */
    private static final String QUOTE_REFERENCE = "('DV-' || upper(left(CAST(id AS text), 8)))";

    /**
     * Story 20.3 — the date of the list's {@code from} / {@code to} range: an invoice's issue date; a quote (never
     * issued) its creation day in Africa/Douala.
     */
    private static final String LIST_DATE =
            "(CASE WHEN kind = 'QUOTE' THEN CAST(created_at AT TIME ZONE 'Africa/Douala' AS date) ELSE issue_date END)";

    /**
     * Story 20.4 — the collection state of an issued / paid invoice as listed: the stored one, else PAID for status PAID
     * and UNPAID for ISSUED (legacy rows). Same expression as the index idx_invoices_payment_state_list (V10).
     */
    static final String PAYMENT_STATE =
            "COALESCE(payment_state, CASE status WHEN 'PAID' THEN 'PAID' ELSE 'UNPAID' END)";

    /** Story 15.5b — a soft-deleted draft is invisible to every read but {@link #findAnyById} and the delta. */
    private static final String LIVE = " AND deleted_at IS NULL";

    @Override
    public Optional<Invoice> findById(UUID id) {
        return findOne(id, LIVE);
    }

    @Override
    public Optional<Invoice> findAnyById(UUID id) {
        return findOne(id, "");
    }

    @Override
    public Optional<Invoice> findByIdForUpdate(UUID id) {
        return findOne(id, LIVE + " FOR UPDATE");
    }

    private Optional<Invoice> findOne(UUID id, String condition) {
        List<Row> rows = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " WHERE id = ?" + condition, (rs, n) -> row(rs), id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0).with(lines(List.of(id)).getOrDefault(id, List.of())));
    }

    @Override
    public boolean existsBySourceSale(UUID saleId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM "
                + InvoicingTables.qualified(InvoicingTables.INVOICES) + " WHERE source_sale_id = ? AND kind = 'INVOICE')",
                Boolean.class,
                saleId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<Invoice> findActiveBySourceSaleForUpdate(UUID saleId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " WHERE source_sale_id = ? AND superseded_at IS NULL AND kind = 'INVOICE' FOR UPDATE", UUID.class,
                saleId);
        return ids.isEmpty() ? Optional.empty() : findById(ids.get(0));
    }

    @Override
    public void markSuperseded(UUID id, Instant at) {
        // Story 15.5b — updated_at moves too (never backwards): the sync pull sends the replaced invoice again
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " SET superseded_at = ?, updated_at = GREATEST(updated_at, ?) WHERE id = ? AND superseded_at IS NULL",
                ts(at), ts(at), id), "supersede");
    }

    @Override
    public void insert(Invoice invoice) {
        jdbc.update("INSERT INTO " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " (" + COLUMNS + ") VALUES ("
                + PLACEHOLDERS + ")", values(invoice));
        insertLines(invoice);
    }

    @Override
    public boolean insertIfAbsent(Invoice invoice) {
        int inserted = jdbc.update("INSERT INTO " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " (" + COLUMNS
                + ") VALUES (" + PLACEHOLDERS + ") ON CONFLICT (id) DO NOTHING", values(invoice));
        if (inserted == 0) {
            return false;
        }
        insertLines(invoice);
        return true;
    }

    @Override
    public boolean insertConversion(Invoice invoice) {
        // no conflict target: a taken id AND a quote already converted (uq_invoices_source_quote) both answer false,
        // inside the caller's transaction (never a duplicate-key error that would abort it)
        int inserted = jdbc.update("INSERT INTO " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " (" + COLUMNS
                + ") VALUES (" + PLACEHOLDERS + ") ON CONFLICT DO NOTHING", values(invoice));
        if (inserted == 0) {
            return false;
        }
        insertLines(invoice);
        return true;
    }

    @Override
    public Optional<Invoice> findBySourceQuote(UUID quoteId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " WHERE source_quote_id = ?", UUID.class, quoteId);
        return ids.isEmpty() ? Optional.empty() : findById(ids.get(0));
    }

    @Override
    public void updateDraft(Invoice draft) {
        ClientInfo c = draft.client();
        InvoiceTotals t = draft.totals();
        InvoiceTerms terms = draft.terms();
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " SET client_id = ?, "
                        + "client_name = ?, client_niu = ?, client_address = ?, client_phone = ?, vat_registered = ?, "
                        + "vat_rate = ?, prices_include_vat = ?, vat_mention = ?, total_ht = ?, total_vat = ?, "
                        + "total_ttc = ?, updated_at = ?, " + TERMS_SET + " WHERE id = ? AND status = 'DRAFT'",
                c.clientId(), c.name(), c.niu(), c.address(), c.phone(), draft.vatRegistered(), draft.vatRate(),
                draft.pricesIncludeVat(), draft.vatMention(), t.totalHt(), t.totalVat(), t.totalTtc(),
                ts(draft.updatedAt()), t.discount(), t.delivery(), t.subtotal(), terms.vatRate(), terms.vatExempt(),
                date(terms.dueDate()), c.rccm(), c.email(), c.contactName(), c.website(), draft.id()), "draft update");
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
                        + "seller_name_from_display = ?, seller_has_logo = ?, subtotal = ? "
                        + "WHERE id = ? AND status = 'DRAFT' AND kind = 'INVOICE'",
                issued.number(), Date.valueOf(issued.issueDate()), s.legalName(), s.address(), s.phone(), s.niu(),
                s.rccm(), s.paymentDetails(), s.footer(), issued.vatRegistered(), issued.vatRate(),
                issued.pricesIncludeVat(), issued.vatMention(), t.totalHt(), t.totalVat(), t.totalTtc(),
                ts(issued.issuedAt()), ts(issued.updatedAt()), s.nameFromDisplayName(), s.hasLogo(), t.subtotal(),
                issued.id()),
                "issue");
        // lines are recomputed with the settings of the issue, then frozen
        replaceLines(issued);
    }

    @Override
    public void markPaid(Invoice paid) {
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES) + " SET status = 'PAID', "
                        + "paid_at = ?, payment_method = ?, updated_at = ? WHERE id = ? AND status = 'ISSUED' "
                        + "AND kind = 'INVOICE'",
                ts(paid.paidAt()), paid.paymentMethod(), ts(paid.updatedAt()), paid.id()), "payment");
    }

    @Override
    public void markCancelled(Invoice cancelled) {
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " SET status = 'CANCELLED', cancelled_at = ?, cancellation_reason = ?, updated_at = ? "
                        + "WHERE id = ? AND ((kind = 'INVOICE' AND status = 'ISSUED') "
                        + "OR (kind = 'QUOTE' AND status = 'DRAFT'))",
                ts(cancelled.cancelledAt()), cancelled.cancellationReason(), ts(cancelled.updatedAt()),
                cancelled.id()), "cancellation");
    }

    @Override
    public void markConverted(Invoice quote) {
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " SET status = 'CONVERTED', converted_invoice_id = ?, converted_at = ?, updated_at = ? "
                        + "WHERE id = ? AND kind = 'QUOTE' AND status = 'DRAFT'",
                quote.convertedInvoiceId(), ts(quote.convertedAt()), ts(quote.updatedAt()), quote.id()), "conversion");
    }

    @Override
    public void markSent(Invoice sent) {
        // Story 15.5b — updated_at moves too (never backwards): the sync pull sends the new sending count
        expectOne(jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " SET last_sent_at = ?, last_sent_to = ?, last_sent_channel = ?, send_count = send_count + 1, "
                        + "last_send_status = 'SENT', last_send_status_channel = ?, "
                        + "last_send_error_code = NULL, updated_at = GREATEST(updated_at, ?) WHERE id = ?",
                ts(sent.delivery().lastSentAt()), sent.delivery().lastSentTo(),
                // Story 20.8 — the channel of this sending (V18)
                sent.delivery().channel() == null ? null : sent.delivery().channel().name(),
                sent.delivery().channel() == null ? null : sent.delivery().channel().name(),
                ts(sent.delivery().lastSentAt()), sent.id()), "sending");
    }

    @Override
    public void markSendStatus(UUID id, SendStatus status, SendChannel channel, String errorCode, Instant now) {
        jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " SET last_send_status = ?, last_send_status_channel = ?, last_send_error_code = ?, "
                        + "updated_at = GREATEST(updated_at, ?) WHERE id = ?",
                status.name(), channel == null ? null : channel.name(), errorCode, ts(now), id);
    }

    /** Story 20.5 — the document terms and copied client fields written by a draft edit (V11 columns). */
    private static final String TERMS_SET = "discount_amount = ?, delivery_fee = ?, subtotal = ?, "
            + "vat_rate_override = ?, vat_exempt = ?, due_date = ?, client_rccm = ?, client_email = ?, "
            + "client_contact_name = ?, client_website = ?";

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
    public boolean deleteDraft(UUID id, Instant at) {
        return jdbc.update("UPDATE " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                + " SET deleted_at = ?, updated_at = GREATEST(updated_at, ?) WHERE id = ? AND status = 'DRAFT'"
                + LIVE, ts(at), ts(at), id) == 1;
    }

    @Override
    public Page list(Filter criteria, int page, int size) {
        String table = InvoicingTables.qualified(InvoicingTables.INVOICES);
        List<String> conditions = new ArrayList<>();
        List<Object> filter = new ArrayList<>();
        if (criteria.kind() != null) {
            // Story 20.3 — idx_invoices_kind_status_created
            conditions.add("kind = ?");
            filter.add(criteria.kind().name());
        }
        if (criteria.status() != null) {
            conditions.add("status = ?");
            filter.add(criteria.status().name());
        }
        if (criteria.paymentState() != null) {
            // Story 20.4 — stored state, or read through the status when never computed; one expression served by the
            // ordered index idx_invoices_payment_state_list (V10)
            conditions.add("kind = 'INVOICE' AND status IN ('ISSUED', 'PAID') AND superseded_at IS NULL AND "
                    + PAYMENT_STATE + " = ?");
            filter.add(criteria.paymentState().name());
        }
        if (criteria.saleId() != null) {
            conditions.add("source_sale_id = ? AND superseded_at IS NULL");
            filter.add(criteria.saleId());
        }
        if (criteria.search() != null && !criteria.search().isEmpty()) {
            // Story 15.5 — number or client name, case-insensitive; LIKE wildcards typed by the user are literal.
            // Story 20.3 — or the reference of a quote (DV- + the first 8 characters of its id, never stored)
            conditions.add("(number ILIKE ? ESCAPE '\\' OR client_name ILIKE ? ESCAPE '\\' OR (kind = 'QUOTE' AND "
                    + QUOTE_REFERENCE + " ILIKE ? ESCAPE '\\'))");
            String pattern = "%" + escapeLike(criteria.search()) + "%";
            filter.add(pattern);
            filter.add(pattern);
            filter.add(pattern);
        }
        if (criteria.from() != null) {
            conditions.add(LIST_DATE + " >= ?");
            filter.add(Date.valueOf(criteria.from()));
        }
        if (criteria.to() != null) {
            conditions.add(LIST_DATE + " <= ?");
            filter.add(Date.valueOf(criteria.to()));
        }
        if (criteria.scope() != null && !criteria.scope().all()) {
            // Story 20.14 — what the actor sees (idx_invoices_created_by_sync / idx_invoices_store_sync)
            DocumentScope.Sql scoped = criteria.scope().sql(null);
            conditions.add(scoped.condition());
            filter.addAll(scoped.args());
        }
        if (criteria.storeId() != null) {
            conditions.add("store_id = ?"); // Story 20.14 — the owner's store filter
            filter.add(criteria.storeId());
        }
        conditions.add("deleted_at IS NULL");
        String where = " WHERE " + String.join(" AND ", conditions);
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

    @Override
    public boolean tablesExist() {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                InvoicingTables.qualified(InvoicingTables.INVOICES));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<DeltaItem> delta(Instant since, Instant afterCursor, String afterId, int limit) {
        return delta(since, afterCursor, afterId, limit, DocumentScope.ALL);
    }

    @Override
    public List<DeltaItem> delta(Instant since, Instant afterCursor, String afterId, int limit, DocumentScope scope) {
        String table = InvoicingTables.qualified(InvoicingTables.INVOICES);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS + ", deleted_at IS NOT NULL AS deleted FROM "
                + table + " WHERE " + SYNC_CURSOR + " >= ?");
        args.add(ts(since));
        if (scope != null && !scope.all()) {
            DocumentScope.Sql scoped = scope.sql(null); // Story 20.14 — the puller's documents only
            sql.append(" AND ").append(scoped.condition());
            args.addAll(scoped.args());
        }
        if (afterCursor != null && afterId != null) {
            // row-value comparison in the index order: the keyset index starts right after the previous page
            sql.append(" AND (" + SYNC_CURSOR + ", CAST(id AS text)) > (?, ?)");
            args.add(ts(afterCursor));
            args.add(afterId);
        }
        sql.append(" ORDER BY " + SYNC_CURSOR + ", CAST(id AS text) LIMIT ?");
        args.add(limit);
        Set<UUID> deleted = new java.util.HashSet<>();
        List<Row> rows = jdbc.query(sql.toString(), (rs, n) -> {
            Row r = row(rs);
            if (rs.getBoolean("deleted")) {
                deleted.add(r.id());
            }
            return r;
        }, args.toArray());
        List<UUID> live = rows.stream().map(Row::id).filter(id -> !deleted.contains(id)).toList();
        Map<UUID, List<InvoiceLine>> lines = live.isEmpty() ? Map.of() : lines(live);
        return rows.stream().map(r -> new DeltaItem(r.with(lines.getOrDefault(r.id(), List.of())),
                deleted.contains(r.id()))).toList();
    }

    @Override
    public Optional<DocumentScope.Owner> ownerOf(UUID invoiceId) {
        return jdbc.query("SELECT created_by, store_id FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " WHERE id = ?", (rs, n) -> new DocumentScope.Owner(rs.getObject("created_by", UUID.class),
                        rs.getObject("store_id", UUID.class)), invoiceId).stream().findFirst();
    }

    @Override
    public Optional<DocumentScope.Owner> ownerOfCreditNote(UUID creditNoteId) {
        return jdbc.query("SELECT i.created_by, i.store_id FROM " + InvoicingTables.qualified(InvoicingTables.INVOICES)
                        + " i JOIN " + InvoicingTables.qualified(InvoicingTables.CREDIT_NOTES) + " cn ON cn.invoice_id = i.id "
                        + "WHERE cn.id = ?", (rs, n) -> new DocumentScope.Owner(rs.getObject("created_by", UUID.class),
                        rs.getObject("store_id", UUID.class)), creditNoteId).stream().findFirst();
    }

    // ── mapping ─────────────────────────────────────────────────────────────────────────────────────────────

    private void insertLines(Invoice invoice) {
        String table = InvoicingTables.qualified(InvoicingTables.ITEMS);
        List<Object[]> batch = new ArrayList<>();
        for (InvoiceLine line : invoice.lines()) {
            batch.add(new Object[]{UUID.randomUUID(), invoice.id(), line.position(), line.description(),
                    line.quantity(), line.unitPrice(), line.treatment().name(), line.vatRate(), line.totalHt(),
                    line.totalVat(), line.totalTtc(), line.productId(), line.reference()});
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO " + table + " (id, invoice_id, position, description, quantity, unit_price, "
                    + "vat_treatment, vat_rate, total_ht, total_vat, total_ttc, product_id, reference) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    batch);
        }
    }

    private Map<UUID, List<InvoiceLine>> lines(List<UUID> invoiceIds) {
        Map<UUID, List<InvoiceLine>> byInvoice = new LinkedHashMap<>();
        jdbc.query("SELECT invoice_id, position, description, quantity, unit_price, vat_treatment, vat_rate, total_ht, "
                        + "total_vat, total_ttc, product_id, reference FROM " + InvoicingTables.qualified(InvoicingTables.ITEMS)
                        + " WHERE invoice_id IN (" + String.join(", ", java.util.Collections.nCopies(invoiceIds.size(), "?"))
                        + ") ORDER BY invoice_id, position",
                rs -> {
                    byInvoice.computeIfAbsent(rs.getObject("invoice_id", UUID.class), k -> new ArrayList<>())
                            .add(new InvoiceLine(rs.getInt("position"), rs.getString("description"),
                                    rs.getBigDecimal("quantity"), rs.getLong("unit_price"),
                                    VatTreatment.valueOf(rs.getString("vat_treatment")), rs.getBigDecimal("vat_rate"),
                                    rs.getLong("total_ht"), rs.getLong("total_vat"), rs.getLong("total_ttc"),
                                    rs.getObject("product_id", UUID.class), rs.getString("reference")));
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
                s != null && s.nameFromDisplayName(), s != null && s.hasLogo(), i.sourceSaleId(), i.kind().name(),
                i.sourceQuoteId(),
                i.totals().discount(), i.totals().delivery(), i.totals().subtotal(), i.terms().vatRate(),
                i.terms().vatExempt(), date(i.terms().dueDate()), c.rccm(), c.email(), c.contactName(), c.website(),
                i.storeId()};
    }

    private static Date date(java.time.LocalDate day) {
        return day == null ? null : Date.valueOf(day);
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
                    i.updatedAt(), i.delivery(), i.sourceSaleId(), i.supersededAt(), i.kind(), i.convertedInvoiceId(),
                    i.convertedAt(), i.sourceQuoteId(), i.terms(), i.storeId());
        }
    }

    private static Row row(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        InvoiceStatus status = InvoiceStatus.valueOf(rs.getString("status"));
        String kindName = rs.getString("kind");
        InvoiceKind kind = kindName == null ? InvoiceKind.INVOICE : InvoiceKind.valueOf(kindName);
        Date issueDate = rs.getDate("issue_date");
        // a draft and a quote (whatever its status) have no seller snapshot
        SellerSnapshot seller = status == InvoiceStatus.DRAFT || kind == InvoiceKind.QUOTE ? null : new SellerSnapshot(
                rs.getString("seller_legal_name"), rs.getString("seller_address"), rs.getString("seller_phone"),
                rs.getString("seller_niu"), rs.getString("seller_rccm"), rs.getString("seller_payment_details"),
                rs.getString("seller_footer"), rs.getBoolean("seller_name_from_display"),
                rs.getBoolean("seller_has_logo"));
        // Story 20.5 — a row written before V11 has no subtotal: the sum of its lines as entered (no discount then)
        boolean pricesIncludeVat = rs.getBoolean("prices_include_vat");
        long totalHt = rs.getLong("total_ht");
        long totalVat = rs.getLong("total_vat");
        long totalTtc = rs.getLong("total_ttc");
        long discount = rs.getLong("discount_amount");
        long deliveryFee = rs.getLong("delivery_fee");
        long storedSubtotal = rs.getLong("subtotal");
        long subtotal = rs.wasNull() ? (pricesIncludeVat ? totalTtc : totalHt) : storedSubtotal;
        Date due = rs.getDate("due_date");
        InvoiceTerms terms = new InvoiceTerms(discount, deliveryFee, rs.getBigDecimal("vat_rate_override"),
                rs.getBoolean("vat_exempt"), due == null ? null : due.toLocalDate());
        Invoice invoice = new Invoice(id, rs.getString("number"), status,
                issueDate == null ? null : issueDate.toLocalDate(),
                new ClientInfo(rs.getObject("client_id", UUID.class), rs.getString("client_name"),
                        rs.getString("client_niu"), rs.getString("client_address"), rs.getString("client_phone"),
                        rs.getString("client_rccm"), rs.getString("client_email"),
                        rs.getString("client_contact_name"), rs.getString("client_website")),
                seller, rs.getBoolean("vat_registered"), rs.getBigDecimal("vat_rate"),
                pricesIncludeVat, rs.getString("vat_mention"), List.of(),
                new InvoiceTotals(totalHt, totalVat, totalTtc, subtotal, discount, deliveryFee),
                instant(rs, "issued_at"), instant(rs, "paid_at"), rs.getString("payment_method"),
                instant(rs, "cancelled_at"), rs.getString("cancellation_reason"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"),
                new InvoiceDelivery(instant(rs, "last_sent_at"), rs.getString("last_sent_to"), rs.getInt("send_count"),
                        SendChannel.fromStored(rs.getString("last_sent_channel")),
                        SendStatus.fromStored(rs.getString("last_send_status")),
                        SendChannel.fromStored(rs.getString("last_send_status_channel")),
                        rs.getString("last_send_error_code")),
                rs.getObject("source_sale_id", UUID.class), instant(rs, "superseded_at"), kind,
                rs.getObject("converted_invoice_id", UUID.class), instant(rs, "converted_at"),
                rs.getObject("source_quote_id", UUID.class), terms, rs.getObject("store_id", UUID.class));
        return new Row(id, invoice);
    }

    /** Backslash, {@code %} and {@code _} taken literally in a LIKE pattern (escape character: backslash). */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
