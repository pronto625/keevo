package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.InvoiceTemplate;
import com.keevo.invoicing.invoice.domain.model.SignatureRole;
import com.keevo.invoicing.invoice.domain.model.TicketWidth;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePresentationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link InvoicePresentationRepository} on the invoicing V15 columns of {@code invoices} and the single-row table
 * {@code invoicing_document_preferences} (Story 20.7) — plain JDBC in the caller's transaction. Reads by primary key
 * ({@code id IN (…)} for a page: one query), writes one UPDATE by primary key.
 */
@Component
public class JdbcInvoicePresentationRepository implements InvoicePresentationRepository {

    static final String PREFERENCES = "invoicing_document_preferences";
    private static final String COLUMNS = "id, invoice_template, ticket_width, signature_client_path, "
            + "signature_seller_path, public_link_version, legacy_public_token, legacy_reference, link_shared_at";

    private final JdbcTemplate jdbc;

    public JdbcInvoicePresentationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static String invoices() {
        return InvoicingTables.qualified(InvoicingTables.INVOICES);
    }

    @Override
    public Optional<InvoicePresentation> find(UUID invoiceId) {
        List<InvoicePresentation> rows = jdbc.query("SELECT " + COLUMNS + " FROM " + invoices()
                + " WHERE id = ? AND deleted_at IS NULL", (rs, n) -> map(rs), invoiceId);
        return rows.stream().findFirst();
    }

    @Override
    public Map<UUID, InvoicePresentation> findAll(Collection<UUID> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, InvoicePresentation> out = new HashMap<>();
        // one query for the page, on the primary key
        jdbc.query("SELECT " + COLUMNS + " FROM " + invoices() + " WHERE id = ANY(?) AND deleted_at IS NULL",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", invoiceIds.toArray())),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getObject("id", UUID.class),
                        map(rs)));
        return out;
    }

    private static InvoicePresentation map(ResultSet rs) throws SQLException {
        return new InvoicePresentation(InvoiceTemplate.fromStored(rs.getString("invoice_template")),
                TicketWidth.fromCode(rs.getString("ticket_width")), rs.getString("signature_client_path"),
                rs.getString("signature_seller_path"), rs.getInt("public_link_version"),
                rs.getObject("legacy_public_token", UUID.class), rs.getString("legacy_reference"),
                rs.getTimestamp("link_shared_at") != null);
    }

    @Override
    public void updateLayout(UUID invoiceId, InvoiceTemplate template, TicketWidth width, Instant now) {
        jdbc.update("UPDATE " + invoices() + " SET invoice_template = ?, ticket_width = ?, "
                        + "updated_at = GREATEST(updated_at, ?) WHERE id = ?",
                template == null ? null : template.name(), width == null ? null : width.code(), ts(now), invoiceId);
    }

    @Override
    public void updateSignature(UUID invoiceId, SignatureRole role, String fileName, Instant now) {
        String set = role == SignatureRole.CLIENT ? " SET signature_client_path = ?"
                : " SET signature_seller_path = ?";
        jdbc.update("UPDATE " + invoices() + set + ", updated_at = GREATEST(updated_at, ?) WHERE id = ?",
                fileName, ts(now), invoiceId);
    }

    @Override
    public void markShared(UUID invoiceId, Instant now) {
        jdbc.update("UPDATE " + invoices() + " SET link_shared_at = ? WHERE id = ? AND link_shared_at IS NULL",
                ts(now), invoiceId);
    }

    @Override
    public void copyLayoutAndSignatures(UUID fromId, UUID toId, Instant now) {
        jdbc.update("UPDATE " + invoices() + " t SET invoice_template = s.invoice_template, "
                + "ticket_width = s.ticket_width, signature_client_path = s.signature_client_path, "
                + "signature_seller_path = s.signature_seller_path, updated_at = GREATEST(t.updated_at, ?) FROM "
                + invoices() + " s WHERE t.id = ? AND s.id = ?", ts(now), toId, fromId);
    }

    @Override
    public Optional<Integer> rotateLink(UUID invoiceId, Instant now) {
        List<Integer> v = jdbc.queryForList("UPDATE " + invoices() + " SET public_link_version = "
                + "public_link_version + 1, legacy_public_token = NULL, updated_at = GREATEST(updated_at, ?) "
                + "WHERE id = ? AND deleted_at IS NULL RETURNING public_link_version", Integer.class, ts(now),
                invoiceId);
        return v.stream().findFirst();
    }

    @Override
    public void attachLegacy(UUID invoiceId, UUID legacyToken, String legacyReference, Instant now) {
        jdbc.update("UPDATE " + invoices() + " SET legacy_public_token = ?, legacy_reference = ?, "
                + "updated_at = GREATEST(updated_at, ?) WHERE id = ?", legacyToken, legacyReference, ts(now),
                invoiceId);
    }

    @Override
    public Optional<UUID> findByLegacyToken(UUID legacyToken) {
        // uq_invoices_legacy_public_token (V15)
        return jdbc.queryForList("SELECT id FROM " + invoices() + " WHERE legacy_public_token = ? "
                + "AND deleted_at IS NULL", UUID.class, legacyToken).stream().findFirst();
    }

    @Override
    public DocumentPreferences preferences() {
        List<DocumentPreferences> rows = jdbc.query("SELECT invoice_template, ticket_width, public_link_days FROM "
                        + InvoicingTables.qualified(PREFERENCES) + " WHERE id = 1",
                (rs, n) -> new DocumentPreferences(InvoiceTemplate.fromStored(rs.getString("invoice_template")),
                        TicketWidth.fromCode(rs.getString("ticket_width")),
                        (Integer) rs.getObject("public_link_days")));
        return rows.isEmpty() ? DocumentPreferences.DEFAULTS : rows.get(0);
    }

    @Override
    public void savePreferences(DocumentPreferences p, UUID updatedBy, Instant now) {
        jdbc.update("INSERT INTO " + InvoicingTables.qualified(PREFERENCES) + " (id, invoice_template, ticket_width, "
                        + "public_link_days, updated_by, updated_at) VALUES (1, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE "
                        + "SET invoice_template = EXCLUDED.invoice_template, ticket_width = EXCLUDED.ticket_width, "
                        + "public_link_days = EXCLUDED.public_link_days, updated_by = EXCLUDED.updated_by, "
                        + "updated_at = EXCLUDED.updated_at",
                p.template() == null ? null : p.template().name(),
                p.ticketWidth() == null ? null : p.ticketWidth().code(), p.publicLinkDays(), updatedBy, ts(now));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
