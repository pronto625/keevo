package com.keevo.invoicing.invoice.adapter.out.portal;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner;
import com.keevo.invoicing.invoice.application.usecase.InvoicePublicLinkUseCase;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.shared.capability.portal.PortalDocuments;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.7 — {@link PortalDocuments} over the invoice tables of several tenant schemas: one {@code SELECT} per schema
 * ({@code id = ANY(?)} on the invoices primary key), glued by {@code UNION ALL}, schema names validated
 * ({@link TenantSchema#validate}) and quoted, ids bound as {@code uuid[]}. Only non-deleted invoices (never a quote,
 * never one replaced after a sale correction) that are issued, paid or cancelled come back. Each issued / paid invoice
 * gets a link to the public PDF ({@code /api/v1/invoices/shared?token=…}) signed for
 * {@link PortalDocuments#LINK_VALIDITY} at its current link version (a rotation by the merchant kills it like every
 * other link).
 *
 * <p>Reprise 11/11/2026 (what Bref's screens show) — the documents statement also reads the seller phone and the
 * credited total (one sum on {@code idx_credit_notes_invoice} per row); then the lines of the documents found
 * ({@code invoice_items}, on its {@code (invoice_id, position)} key) and their payments ({@code invoice_payments}, on
 * {@code idx_invoice_payments_invoice}) in one {@code UNION ALL} statement each. The collection figures come from
 * {@link PaymentStateCalculator} (single source: paid = rows with a payment date, due = total less credit notes; an
 * invoice paid before 20.4 shows one payment of the amount due). {@link #summarize} is one aggregate statement.
 *
 * <p>The caller brings each schema's invoicing tables to their latest version first
 * ({@code ModuleProvisioner.ensureProvisioned}). Cost for P ids over D schemas: 3 round trips, P PK lookups, the
 * lines and payments of those P invoices (index ranges), P HMACs; memory O(P + lines + payments).
 */
@Component
public class JdbcPortalDocuments implements PortalDocuments {

    static final String CANCELLED = "CANCELLED";
    static final String CANCELLED_LABEL = "Annulée";
    /** Never shown in practice: the seller name is snapshotted at issue. */
    static final String UNKNOWN_MERCHANT = "Commerçant";

    /** The listable documents of the portal (shared by {@link #find} and {@link #summarize}). */
    private static final String LISTED = "i.kind = 'INVOICE' "
            + "AND i.status IN ('ISSUED', 'PAID', 'CANCELLED') AND i.deleted_at IS NULL AND i.superseded_at IS NULL";
    private static final String LISTABLE = "i.id = ANY(?) AND " + LISTED;

    private static final String SELECT = "SELECT CAST(? AS varchar) AS tenant_schema, i.id, i.seller_legal_name, "
            + "i.seller_phone, i.number, i.legacy_reference, i.issue_date, i.due_date, i.total_ttc, i.status, "
            + "i.payment_state, i.paid_at, i.payment_method, i.public_link_version, "
            + "(SELECT COALESCE(SUM(c.total_ttc), 0) FROM \"%1$s\".credit_notes c WHERE c.invoice_id = i.id) "
            + "AS credited_ttc FROM \"%1$s\".invoices i WHERE " + LISTABLE;

    private static final String LINES = "SELECT CAST(? AS varchar) AS tenant_schema, l.invoice_id, l.position, "
            + "l.reference, l.description, l.quantity, l.unit_price, l.total_ttc FROM \"%s\".invoice_items l "
            + "WHERE l.invoice_id = ANY(?)";

    private static final String PAYMENTS = "SELECT CAST(? AS varchar) AS tenant_schema, p.invoice_id, p.amount, "
            + "p.due_date, p.paid_at, p.method, p.created_at FROM \"%s\".invoice_payments p WHERE p.invoice_id = ANY(?)";

    /**
     * One merchant's part of the client's totals: the phone's rows of the shared index for that tenant (range of
     * {@code idx_invoice_phone_index_phone}) joined to its invoices by primary key — no id travels to the application.
     */
    private static final String SUMMARY = "SELECT count(*) AS n, "
            + "COALESCE(SUM(i.total_ttc) FILTER (WHERE i.status <> 'CANCELLED'), 0) AS total "
            + "FROM public.invoice_phone_index x JOIN \"%s\".invoices i ON i.id = x.invoice_id "
            + "WHERE x.phone_normalized = ? AND x.tenant_id = ? AND " + LISTED;

    private final JdbcTemplate jdbc;
    private final InvoiceLinkSigner signer;
    private final Clock clock;

    public JdbcPortalDocuments(JdbcTemplate jdbc, InvoiceLinkSigner signer, Clock clock) {
        this.jdbc = jdbc;
        this.signer = signer;
        this.clock = clock;
    }

    /** One document as read, before its lines and payments are attached. */
    private record Head(String schema, UUID id, String merchant, String merchantPhone, String reference,
                        LocalDate issueDate, LocalDate dueDate, long totalTtc, long creditedTtc, String status,
                        String paymentState, Instant paidAt, String paymentMethod, int linkVersion) {
    }

    @Override
    public List<PortalDocument> find(Map<String, ? extends Collection<UUID>> idsBySchema, String baseUrl) {
        Map<String, Collection<UUID>> ids = validated(idsBySchema);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Head> heads = query(SELECT, ids, (rs, n) -> head(rs));
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<String, Collection<UUID>> found = new LinkedHashMap<>();
        for (Head h : heads) {
            found.computeIfAbsent(h.schema(), s -> new ArrayList<>()).add(h.id());
        }
        Map<String, List<IndexedLine>> lines = new HashMap<>();
        for (IndexedLine l : query(LINES, found, (rs, n) -> line(rs))) {
            lines.computeIfAbsent(l.key(), k -> new ArrayList<>()).add(l);
        }
        Map<String, List<IndexedPayment>> payments = new HashMap<>();
        for (IndexedPayment p : query(PAYMENTS, found, (rs, n) -> payment(rs))) {
            payments.computeIfAbsent(p.key(), k -> new ArrayList<>()).add(p);
        }
        Instant expiresAt = clock.instant().plus(LINK_VALIDITY);
        String base = InvoicePublicLinkUseCase.stripTrailingSlash(baseUrl) + InvoicePublicLinkUseCase.SHARED_PATH;
        List<PortalDocument> documents = new ArrayList<>(heads.size());
        for (Head h : heads) {
            String key = key(h.schema(), h.id());
            documents.add(document(h, lines.getOrDefault(key, List.of()), payments.getOrDefault(key, List.of()),
                    base, expiresAt));
        }
        return documents;
    }

    @Override
    public Summary summarize(Map<String, UUID> tenantIdBySchema, String phone) {
        if (tenantIdBySchema.isEmpty()) {
            return Summary.EMPTY;
        }
        List<String> schemas = new ArrayList<>();
        List<UUID> tenants = new ArrayList<>();
        tenantIdBySchema.forEach((schema, tenant) -> {
            schemas.add(TenantSchema.validate(schema));
            tenants.add(tenant);
        });
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < schemas.size(); i++) {
            sql.append(i > 0 ? " UNION ALL " : "").append(String.format(SUMMARY, schemas.get(i)));
        }
        Summary total = Summary.EMPTY;
        for (Summary s : jdbc.query((Connection con) -> {
            PreparedStatement ps = con.prepareStatement(sql.toString());
            int p = 1;
            for (UUID tenant : tenants) {
                ps.setString(p++, phone);
                ps.setObject(p++, tenant);
            }
            return ps;
        }, (rs, n) -> new Summary(rs.getLong("n"), rs.getLong("total")))) {
            total = total.plus(s);
        }
        return total;
    }

    // ── statements ────────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Collection<UUID>> validated(Map<String, ? extends Collection<UUID>> idsBySchema) {
        Map<String, Collection<UUID>> ids = new LinkedHashMap<>();
        idsBySchema.forEach((schema, list) -> {
            if (list != null && !list.isEmpty()) {
                ids.put(TenantSchema.validate(schema), list);
            }
        });
        return ids;
    }

    /**
     * Runs {@code template} once per schema glued by {@code UNION ALL} (schema validated, quoted). Every template
     * selects {@code CAST(? AS varchar) AS tenant_schema} first and filters {@code = ANY(?)}: parameters are the
     * schema name, then the ids. One round trip.
     */
    private <T> List<T> query(String template, Map<String, Collection<UUID>> ids,
                              org.springframework.jdbc.core.RowMapper<T> mapper) {
        List<String> schemas = new ArrayList<>(ids.keySet());
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < schemas.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            sql.append(String.format(template, schemas.get(i)));
        }
        return jdbc.query((Connection con) -> {
            PreparedStatement ps = con.prepareStatement(sql.toString());
            int p = 1;
            for (String schema : schemas) {
                ps.setString(p++, schema);
                ps.setArray(p++, con.createArrayOf("uuid", ids.get(schema).toArray()));
            }
            return ps;
        }, mapper);
    }

    // ── mapping ───────────────────────────────────────────────────────────────────────────────────────────

    private record IndexedLine(String key, int position, PortalDocuments.Line line) {
    }

    private record IndexedPayment(String key, Instant createdAt, PortalDocuments.Payment payment) {
    }

    private static Head head(ResultSet rs) throws SQLException {
        String number = rs.getString("number");
        Timestamp paidAt = rs.getTimestamp("paid_at");
        return new Head(rs.getString("tenant_schema"), rs.getObject("id", UUID.class),
                rs.getString("seller_legal_name"), blankToNull(rs.getString("seller_phone")),
                number != null ? number : rs.getString("legacy_reference"), date(rs.getDate("issue_date")),
                date(rs.getDate("due_date")), rs.getLong("total_ttc"), rs.getLong("credited_ttc"),
                rs.getString("status"), rs.getString("payment_state"), paidAt == null ? null : paidAt.toInstant(),
                blankToNull(rs.getString("payment_method")), rs.getInt("public_link_version"));
    }

    private static IndexedLine line(ResultSet rs) throws SQLException {
        BigDecimal quantity = rs.getBigDecimal("quantity");
        return new IndexedLine(key(rs.getString("tenant_schema"), rs.getObject("invoice_id", UUID.class)),
                rs.getInt("position"), new PortalDocuments.Line(blankToNull(rs.getString("reference")),
                rs.getString("description"), plainQuantity(quantity),
                rs.getLong("unit_price"), rs.getLong("total_ttc")));
    }

    /** {@code 10.000} → {@code 10}, {@code 2.500} → {@code 2.5}: no trailing zeros and never the {@code 1E+1} form. */
    static BigDecimal plainQuantity(BigDecimal quantity) {
        if (quantity == null) {
            return null;
        }
        BigDecimal stripped = quantity.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static IndexedPayment payment(ResultSet rs) throws SQLException {
        Timestamp paidAt = rs.getTimestamp("paid_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new IndexedPayment(key(rs.getString("tenant_schema"), rs.getObject("invoice_id", UUID.class)),
                createdAt == null ? Instant.EPOCH : createdAt.toInstant(),
                new PortalDocuments.Payment(rs.getLong("amount"), date(rs.getDate("due_date")), paidAt != null,
                        paidAt == null ? null : LocalDate.ofInstant(paidAt.toInstant(), KeevoZones.WAT),
                        blankToNull(rs.getString("method"))));
    }

    private PortalDocument document(Head h, List<IndexedLine> lines, List<IndexedPayment> rows, String base,
                                    Instant expiresAt) {
        String state = state(h.status(), h.paymentState());
        boolean cancelled = CANCELLED.equals(h.status());
        long due = PaymentStateCalculator.amountDue(h.totalTtc(), h.creditedTtc());
        List<PortalDocuments.Payment> payments = new ArrayList<>(rows.size() + 1);
        rows.stream()
                .sorted(Comparator.comparing((IndexedPayment p) -> sortDate(p.payment()),
                        Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(IndexedPayment::createdAt))
                .forEach(p -> payments.add(p.payment()));
        long paid = 0;
        for (PortalDocuments.Payment p : payments) {
            if (p.paid()) {
                paid = Math.addExact(paid, p.amount());
            }
        }
        if (payments.isEmpty() && "PAID".equals(h.status()) && due > 0) {
            // an invoice paid before Story 20.4: one payment of the amount due (PaymentStateCalculator's legacy row)
            payments.add(new PortalDocuments.Payment(due, null, true,
                    h.paidAt() == null ? null : LocalDate.ofInstant(h.paidAt(), KeevoZones.WAT), h.paymentMethod()));
            paid = due;
        }
        // a cancelled invoice is owed nothing; a PAID one never shows a remainder next to its « Payée » badge
        long remaining = cancelled || PaymentState.PAID.name().equals(state) ? 0 : Math.max(0, due - paid);
        List<PortalDocuments.Line> printed = lines.stream().sorted(Comparator.comparingInt(IndexedLine::position))
                .map(IndexedLine::line).toList();
        String merchant = h.merchant();
        return new PortalDocument(h.schema(), h.id(), merchant == null || merchant.isBlank() ? UNKNOWN_MERCHANT
                : merchant, h.merchantPhone(), h.reference(), h.issueDate(), h.dueDate(), h.totalTtc(), paid,
                remaining, state, label(state), printed, payments,
                cancelled ? null : base + signer.sign(h.schema(), h.id(), expiresAt, h.linkVersion()),
                cancelled ? null : expiresAt);
    }

    /** Instalments by due date, payments received by their day. */
    private static LocalDate sortDate(PortalDocuments.Payment p) {
        return p.dueDate() != null ? p.dueDate() : p.paidDate();
    }

    /** The collection state shown: cancelled first, then the stored state (a PAID invoice without one is PAID). */
    static String state(String status, String paymentState) {
        if (CANCELLED.equals(status)) {
            return CANCELLED;
        }
        if (paymentState != null) {
            return paymentState;
        }
        return "PAID".equals(status) ? PaymentState.PAID.name() : PaymentState.UNPAID.name();
    }

    static String label(String state) {
        return CANCELLED.equals(state) ? CANCELLED_LABEL : PaymentState.valueOf(state).label();
    }

    private static String key(String schema, UUID id) {
        return schema + '/' + id;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static LocalDate date(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
