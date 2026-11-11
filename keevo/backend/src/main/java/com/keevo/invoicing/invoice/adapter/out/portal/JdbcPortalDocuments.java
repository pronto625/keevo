package com.keevo.invoicing.invoice.adapter.out.portal;

import com.keevo.invoicing.invoice.application.service.InvoiceLinkSigner;
import com.keevo.invoicing.invoice.application.usecase.InvoicePublicLinkUseCase;
import com.keevo.invoicing.invoice.domain.model.PaymentState;
import com.keevo.shared.capability.portal.PortalDocuments;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.7 — {@link PortalDocuments} over the invoice tables of several tenant schemas in <b>one</b> statement: one
 * {@code SELECT} per schema ({@code id = ANY(?)} on the invoices primary key), glued by {@code UNION ALL}, schema names
 * validated ({@link TenantSchema#validate}) and quoted, ids bound as {@code uuid[]}. Only non-deleted invoices (never a
 * quote, never one replaced after a sale correction) that are issued,
 * paid or cancelled come back. Each issued / paid invoice gets a link to the public PDF
 * ({@code /api/v1/invoices/shared?token=…}) signed for {@link PortalDocuments#LINK_VALIDITY} at its current link
 * version (a rotation by the merchant kills it like every other link).
 *
 * <p>The caller brings each schema's invoicing tables to their latest version first
 * ({@code ModuleProvisioner.ensureProvisioned}). Cost: one round trip for P ids over D schemas, P PK lookups, P HMACs.
 */
@Component
public class JdbcPortalDocuments implements PortalDocuments {

    static final String CANCELLED = "CANCELLED";
    static final String CANCELLED_LABEL = "Annulée";
    /** Never shown in practice: the seller name is snapshotted at issue. */
    static final String UNKNOWN_MERCHANT = "Commerçant";

    private static final String SELECT = "SELECT CAST(? AS varchar) AS tenant_schema, i.id, i.seller_legal_name, "
            + "i.number, i.legacy_reference, i.issue_date, i.due_date, i.total_ttc, i.status, i.payment_state, "
            + "i.public_link_version FROM \"%s\".invoices i WHERE i.id = ANY(?) AND i.kind = 'INVOICE' "
            + "AND i.status IN ('ISSUED', 'PAID', 'CANCELLED') AND i.deleted_at IS NULL AND i.superseded_at IS NULL";

    private final JdbcTemplate jdbc;
    private final InvoiceLinkSigner signer;
    private final Clock clock;

    public JdbcPortalDocuments(JdbcTemplate jdbc, InvoiceLinkSigner signer, Clock clock) {
        this.jdbc = jdbc;
        this.signer = signer;
        this.clock = clock;
    }

    @Override
    public List<PortalDocument> find(Map<String, ? extends Collection<UUID>> idsBySchema, String baseUrl) {
        List<String> schemas = new ArrayList<>();
        List<Collection<UUID>> ids = new ArrayList<>();
        idsBySchema.forEach((schema, list) -> {
            if (list != null && !list.isEmpty()) {
                schemas.add(TenantSchema.validate(schema));
                ids.add(list);
            }
        });
        if (schemas.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < schemas.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ALL ");
            }
            sql.append(String.format(SELECT, schemas.get(i)));
        }
        Instant expiresAt = clock.instant().plus(LINK_VALIDITY);
        String base = InvoicePublicLinkUseCase.stripTrailingSlash(baseUrl) + InvoicePublicLinkUseCase.SHARED_PATH;
        return jdbc.query((Connection con) -> {
            PreparedStatement ps = con.prepareStatement(sql.toString());
            int p = 1;
            for (int i = 0; i < schemas.size(); i++) {
                ps.setString(p++, schemas.get(i));
                ps.setArray(p++, con.createArrayOf("uuid", ids.get(i).toArray()));
            }
            return ps;
        }, (rs, n) -> document(rs, base, expiresAt));
    }

    private PortalDocument document(ResultSet rs, String base, Instant expiresAt) throws SQLException {
        String schema = rs.getString("tenant_schema");
        UUID id = rs.getObject("id", UUID.class);
        String status = rs.getString("status");
        String state = state(status, rs.getString("payment_state"));
        boolean linked = !CANCELLED.equals(status);
        String number = rs.getString("number");
        String merchant = rs.getString("seller_legal_name");
        return new PortalDocument(schema, id, merchant == null || merchant.isBlank() ? UNKNOWN_MERCHANT : merchant,
                number != null ? number : rs.getString("legacy_reference"), date(rs.getDate("issue_date")),
                date(rs.getDate("due_date")), rs.getLong("total_ttc"), state, label(state),
                linked ? base + signer.sign(schema, id, expiresAt, rs.getInt("public_link_version")) : null,
                linked ? expiresAt : null);
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

    private static LocalDate date(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
