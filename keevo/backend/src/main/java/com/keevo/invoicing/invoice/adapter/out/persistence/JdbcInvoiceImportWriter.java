package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.invoicing.api.InvoiceImportPort.Document;
import com.keevo.invoicing.api.InvoiceImportPort.Line;
import com.keevo.invoicing.api.InvoiceImportPort.Payment;
import com.keevo.invoicing.api.InvoiceImportPort.Seller;
import com.keevo.invoicing.invoice.domain.model.InvoicePayment;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceImportWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Story 24.4 — {@link InvoiceImportWriter} as one statement per table on the bound tenant schema
 * ({@link InvoicingTables}): {@code INSERT … SELECT FROM jsonb_to_recordset(?) … ON CONFLICT (id) DO NOTHING RETURNING
 * id} — one JSON parameter instead of forty arrays; text values go through the column type's input function (ISO-8601
 * instants and dates, decimals as text, exact). The rows keep Bref's dates ({@code created_at}, {@code updated_at}).
 */
@Component
class JdbcInvoiceImportWriter implements InvoiceImportWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JdbcInvoiceImportWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insertSettingsIfAbsent(InvoicingSettings s, Instant at) {
        jdbc.update("INSERT INTO " + InvoicingTables.qualified(InvoicingTables.SETTINGS) + " (id, legal_name, address, "
                        + "phone, niu, rccm, vat_registered, vat_rate, prices_include_vat, payment_details, footer, "
                        + "updated_at) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                s.legalName(), s.address(), s.phone(), s.niu(), s.rccm(), s.vatRegistered(), s.vatRate(),
                s.pricesIncludeVat(), s.paymentDetails(), s.footer(), Timestamp.from(at));
    }

    @Override
    public void insertPreferencesIfAbsent(int publicLinkDays, Instant at) {
        jdbc.update("INSERT INTO " + InvoicingTables.qualified("invoicing_document_preferences")
                + " (id, public_link_days, updated_at) VALUES (1, ?, ?) ON CONFLICT (id) DO NOTHING", publicLinkDays,
                Timestamp.from(at));
    }

    private static final String DOCUMENT_COLUMNS = "id, kind, status, number, issue_date, client_id, client_name, "
            + "client_phone, client_email, client_niu, client_rccm, client_address, client_contact_name, "
            + "client_website, seller_legal_name, seller_address, seller_phone, seller_niu, seller_rccm, "
            + "vat_registered, vat_rate, prices_include_vat, vat_exempt, subtotal, discount_amount, delivery_fee, "
            + "total_ht, total_vat, total_ttc, issued_at, paid_at, payment_method, cancelled_at, cancellation_reason, "
            + "created_by, store_id, due_date, legacy_public_token, legacy_reference, legacy_notes, created_at, "
            + "updated_at, deleted_at";
    private static final String DOCUMENT_TYPES = "id uuid, kind varchar, status varchar, number varchar, "
            + "issue_date date, client_id uuid, client_name varchar, client_phone varchar, client_email varchar, "
            + "client_niu varchar, client_rccm varchar, client_address varchar, client_contact_name varchar, "
            + "client_website varchar, seller_legal_name varchar, seller_address varchar, seller_phone varchar, "
            + "seller_niu varchar, seller_rccm varchar, vat_registered boolean, vat_rate numeric, "
            + "prices_include_vat boolean, vat_exempt boolean, subtotal bigint, discount_amount bigint, "
            + "delivery_fee bigint, total_ht bigint, total_vat bigint, total_ttc bigint, issued_at timestamptz, "
            + "paid_at timestamptz, payment_method varchar, cancelled_at timestamptz, cancellation_reason varchar, "
            + "created_by uuid, store_id uuid, due_date date, legacy_public_token uuid, legacy_reference varchar, "
            + "legacy_notes text, created_at timestamptz, updated_at timestamptz, deleted_at timestamptz";

    @Override
    public Set<UUID> insertDocuments(List<Document> documents) {
        List<Map<String, Object>> rows = new ArrayList<>(documents.size());
        for (Document d : documents) {
            Map<String, Object> r = new LinkedHashMap<>();
            Seller s = d.seller();
            r.put("id", d.id());
            r.put("kind", d.quote() ? "QUOTE" : "INVOICE");
            r.put("status", d.status());
            r.put("number", d.number());
            r.put("issue_date", text(d.issueDate()));
            r.put("client_id", d.clientId());
            r.put("client_name", d.clientName());
            r.put("client_phone", d.clientPhone());
            r.put("client_email", d.clientEmail());
            r.put("client_niu", d.clientNiu());
            r.put("client_rccm", d.clientRccm());
            r.put("client_address", d.clientAddress());
            r.put("client_contact_name", d.clientContactName());
            r.put("client_website", d.clientWebsite());
            r.put("seller_legal_name", s == null ? null : s.legalName());
            r.put("seller_address", s == null ? null : s.address());
            r.put("seller_phone", s == null ? null : s.phone());
            r.put("seller_niu", s == null ? null : s.niu());
            r.put("seller_rccm", s == null ? null : s.rccm());
            r.put("vat_registered", d.vatRegistered());
            r.put("vat_rate", d.vatRate().toPlainString());
            r.put("prices_include_vat", false); // Bref computes in prices without VAT
            r.put("vat_exempt", false);
            r.put("subtotal", d.subtotal());
            r.put("discount_amount", d.discount());
            r.put("delivery_fee", d.deliveryFee());
            r.put("total_ht", d.totalHt());
            r.put("total_vat", d.totalVat());
            r.put("total_ttc", d.totalTtc());
            r.put("issued_at", text(d.issuedAt()));
            r.put("paid_at", text(d.paidAt()));
            r.put("payment_method", d.paymentMethod());
            r.put("cancelled_at", text(d.cancelledAt()));
            r.put("cancellation_reason", d.cancellationReason());
            r.put("created_by", d.createdBy());
            r.put("store_id", d.storeId());
            r.put("due_date", text(d.dueDate()));
            r.put("legacy_public_token", d.legacyToken());
            r.put("legacy_reference", d.legacyReference());
            r.put("legacy_notes", d.legacyNotes());
            r.put("created_at", text(d.createdAt()));
            r.put("updated_at", text(d.updatedAt()));
            r.put("deleted_at", text(d.deletedAt()));
            rows.add(r);
        }
        return insert(InvoicingTables.INVOICES, DOCUMENT_COLUMNS, DOCUMENT_TYPES, rows);
    }

    @Override
    public Set<UUID> insertLines(List<Line> lines) {
        List<Map<String, Object>> rows = new ArrayList<>(lines.size());
        for (Line l : lines) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", l.id());
            r.put("invoice_id", l.documentId());
            r.put("position", l.position());
            r.put("description", l.description());
            r.put("quantity", l.quantity().toPlainString());
            r.put("unit_price", l.unitPrice());
            r.put("vat_treatment", "STANDARD");
            r.put("vat_rate", l.vatRate().toPlainString());
            r.put("total_ht", l.totalHt());
            r.put("total_vat", l.totalVat());
            r.put("total_ttc", l.totalTtc());
            r.put("product_id", l.productId());
            r.put("reference", l.reference());
            rows.add(r);
        }
        return insert(InvoicingTables.ITEMS, "id, invoice_id, position, description, quantity, unit_price, "
                        + "vat_treatment, vat_rate, total_ht, total_vat, total_ttc, product_id, reference",
                "id uuid, invoice_id uuid, position int, description varchar, quantity numeric, unit_price bigint, "
                        + "vat_treatment varchar, vat_rate numeric, total_ht bigint, total_vat bigint, "
                        + "total_ttc bigint, product_id uuid, reference varchar", rows);
    }

    @Override
    public Set<UUID> insertPayments(List<Payment> payments) {
        List<Map<String, Object>> rows = new ArrayList<>(payments.size());
        for (Payment p : payments) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", p.id());
            r.put("invoice_id", p.documentId());
            r.put("amount", p.amount());
            r.put("due_date", text(p.dueDate()));
            r.put("paid_at", text(p.paidAt()));
            r.put("method", p.method());
            r.put("note", p.note());
            r.put("created_by", p.createdBy());
            r.put("created_at", text(p.createdAt()));
            r.put("updated_at", text(p.updatedAt()));
            rows.add(r);
        }
        return insert(InvoicingTables.PAYMENTS, "id, invoice_id, amount, due_date, paid_at, method, note, created_by, "
                        + "created_at, updated_at",
                "id uuid, invoice_id uuid, amount bigint, due_date date, paid_at timestamptz, method varchar, "
                        + "note varchar, created_by uuid, created_at timestamptz, updated_at timestamptz", rows);
    }

    private Set<UUID> insert(String table, String columns, String types, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        String sql = "INSERT INTO " + InvoicingTables.qualified(table) + " (" + columns + ") SELECT " + columns
                + " FROM jsonb_to_recordset(?::jsonb) AS x(" + types + ") ON CONFLICT (id) DO NOTHING RETURNING id";
        String payload;
        try {
            payload = json.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Import rows not serialisable", e);
        }
        Set<UUID> inserted = new LinkedHashSet<>();
        jdbc.query(sql, rs -> {
            inserted.add(rs.getObject("id", UUID.class));
        }, payload);
        return inserted;
    }

    @Override
    public List<CollectionInput> collections(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Object[] array = ids.toArray();
        Map<UUID, List<InvoicePayment>> rows = new LinkedHashMap<>();
        // idx_invoice_payments_invoice (V10)
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT id, invoice_id, amount, due_date, paid_at, method, note "
                    + "FROM " + InvoicingTables.qualified(InvoicingTables.PAYMENTS) + " WHERE invoice_id = ANY (?)");
            ps.setArray(1, con.createArrayOf("uuid", array));
            return ps;
        }, rs -> {
            UUID invoice = rs.getObject("invoice_id", UUID.class);
            Date due = rs.getDate("due_date");
            Timestamp paid = rs.getTimestamp("paid_at");
            rows.computeIfAbsent(invoice, k -> new ArrayList<>()).add(new InvoicePayment(
                    rs.getObject("id", UUID.class), invoice, rs.getLong("amount"),
                    due == null ? null : due.toLocalDate(), paid == null ? null : paid.toInstant(),
                    rs.getString("method"), rs.getString("note"), false));
        });
        List<CollectionInput> out = new ArrayList<>(ids.size());
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT id, kind, status, total_ttc, due_date FROM "
                    + InvoicingTables.qualified(InvoicingTables.INVOICES) + " WHERE id = ANY (?)");
            ps.setArray(1, con.createArrayOf("uuid", array));
            return ps;
        }, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            Date due = rs.getDate("due_date");
            out.add(new CollectionInput(id, "QUOTE".equals(rs.getString("kind")), rs.getString("status"),
                    rs.getLong("total_ttc"), due == null ? null : due.toLocalDate(),
                    rows.getOrDefault(id, List.of())));
        });
        return out;
    }

    @Override
    public void updateStates(Map<UUID, PaymentState> states) {
        if (states.isEmpty()) {
            return;
        }
        List<Map.Entry<UUID, PaymentState>> entries = new ArrayList<>(states.entrySet());
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("UPDATE "
                    + InvoicingTables.qualified(InvoicingTables.INVOICES) + " i SET payment_state = x.state FROM "
                    + "unnest(?::uuid[], ?::varchar[]) AS x(id, state) WHERE i.id = x.id "
                    + "AND i.payment_state IS DISTINCT FROM x.state");
            ps.setArray(1, con.createArrayOf("uuid", column(entries, Map.Entry::getKey)));
            ps.setArray(2, con.createArrayOf("varchar", column(entries,
                    e -> e.getValue() == null ? null : e.getValue().name())));
            return ps;
        });
    }

    private static <T> Object[] column(List<T> rows, Function<T, Object> field) {
        return rows.stream().map(field).toArray();
    }

    private static String text(Instant at) {
        return at == null ? null : at.toString();
    }

    private static String text(LocalDate day) {
        return day == null ? null : day.toString();
    }
}
