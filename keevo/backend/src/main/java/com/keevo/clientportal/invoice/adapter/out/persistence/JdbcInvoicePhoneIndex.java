package com.keevo.clientportal.invoice.adapter.out.persistence;

import com.keevo.clientportal.invoice.domain.model.PortalCursor;
import com.keevo.clientportal.invoice.domain.port.out.PortalIndexReader;
import com.keevo.clientportal.module.ClientPortalModule;
import com.keevo.shared.capability.portal.InvoicePhoneIndex;
import com.keevo.shared.domain.PhoneNumbers;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.7 — {@code public.invoice_phone_index} over raw JDBC: the writes of {@link InvoicePhoneIndex} (called by
 * invoicing in the issue transaction and by the back-fill) and the portal's listing read ({@link PortalIndexReader}).
 *
 * <p>Write: one {@code INSERT … SELECT} resolving the tenant id by its unique {@code schema_name}, upsert on the
 * primary key (tenant, invoice), a row rewritten only when its phone or date changed; {@link #indexAll} resolves the
 * tenant id once and sends one batch of plain upserts. The phone is normalized exactly like the invoice's
 * WhatsApp number ({@link PhoneNumbers#toE164}, {@code +237} for a 9-digit local number); an invalid phone writes
 * nothing.
 *
 * <p>Read: one range scan of {@code idx_invoice_phone_index_phone} for the phone, newest first, keyset after the
 * cursor, joined to the tenant (primary key, ACTIVE only) and anti-joined, by primary key, to the owner's activation
 * of {@code client-portal} and to an unusable {@code tenant_modules} row of it: a tenant that is not active, whose
 * owner switched the portal off or whose portal a super-admin disabled never shows. {@code client-portal} is a free
 * module requiring only {@code core}, so these two rows are its whole effective state ({@code ModuleStateRules}: no
 * row = enabled; TRIAL / ACTIVE past {@code expires_at} and EXPIRED stay readable until {@code grace_until};
 * DISABLED / AVAILABLE = off) — no per-tenant entitlement lookup, and the page is filtered before its LIMIT.
 */
@Component
public class JdbcInvoicePhoneIndex implements InvoicePhoneIndex, PortalIndexReader {

    /** The upsert tail: a row is rewritten only when its phone or date changed (a rerun writes nothing). */
    private static final String ON_CONFLICT = "ON CONFLICT (tenant_id, invoice_id) DO UPDATE SET phone_normalized = "
            + "EXCLUDED.phone_normalized, issued_at = EXCLUDED.issued_at WHERE (invoice_phone_index.phone_normalized, "
            + "invoice_phone_index.issued_at) IS DISTINCT FROM (EXCLUDED.phone_normalized, EXCLUDED.issued_at)";

    static final String UPSERT_SQL =
            "INSERT INTO public.invoice_phone_index (tenant_id, invoice_id, phone_normalized, issued_at) "
            + "SELECT t.id, ?, ?, ? FROM public.tenants t WHERE t.schema_name = ? " + ON_CONFLICT;
    static final String TENANT_SQL = "SELECT id FROM public.tenants WHERE schema_name = ?";
    static final String UPSERT_VALUES_SQL =
            "INSERT INTO public.invoice_phone_index (tenant_id, invoice_id, phone_normalized, issued_at) "
            + "VALUES (?, ?, ?, ?) " + ON_CONFLICT;

    static final String PAGE_SQL =
            "SELECT t.schema_name, i.tenant_id, i.invoice_id, i.issued_at FROM public.invoice_phone_index i "
            + "JOIN public.tenants t ON t.id = i.tenant_id AND t.status = 'ACTIVE' "
            + "WHERE i.phone_normalized = ? "
            + "AND NOT EXISTS (SELECT 1 FROM public.tenant_module_activation a WHERE a.tenant_id = i.tenant_id "
            + "AND a.module_id = '" + ClientPortalModule.ID + "' AND NOT a.activated) "
            + "AND NOT EXISTS (SELECT 1 FROM public.tenant_modules m WHERE m.tenant_id = i.tenant_id "
            + "AND m.module_id = '" + ClientPortalModule.ID + "' AND (m.status IN ('DISABLED', 'AVAILABLE') "
            + "OR ((m.status = 'EXPIRED' OR (m.expires_at IS NOT NULL AND m.expires_at <= now())) "
            + "AND (m.grace_until IS NULL OR m.grace_until <= now())))) ";
    static final String AFTER_SQL = "AND (i.issued_at, i.tenant_id, i.invoice_id) < (?, ?, ?) ";
    static final String ORDER_SQL = "ORDER BY i.issued_at DESC, i.tenant_id DESC, i.invoice_id DESC LIMIT ?";

    private final JdbcTemplate jdbc;

    public JdbcInvoicePhoneIndex(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void index(String tenantSchema, UUID invoiceId, String clientPhone, Instant issuedAt) {
        String phone = PhoneNumbers.toE164(clientPhone, PhoneNumbers.CAMEROON);
        if (phone == null || invoiceId == null || issuedAt == null) {
            return;
        }
        jdbc.update(UPSERT_SQL, invoiceId, phone, Timestamp.from(issuedAt), TenantSchema.validate(tenantSchema));
    }

    @Override
    public int indexAll(String tenantSchema, List<Entry> entries) {
        String schema = TenantSchema.validate(tenantSchema);
        List<UUID> tenant = jdbc.queryForList(TENANT_SQL, UUID.class, schema);
        if (tenant.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            String phone = PhoneNumbers.toE164(e.clientPhone(), PhoneNumbers.CAMEROON);
            if (phone != null && e.invoiceId() != null && e.issuedAt() != null) {
                rows.add(new Object[]{tenant.get(0), e.invoiceId(), phone, Timestamp.from(e.issuedAt())});
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int count : jdbc.batchUpdate(UPSERT_VALUES_SQL, rows)) {
            written += Math.max(count, 0);
        }
        return written;
    }

    @Override
    public List<Row> page(String phone, PortalCursor after, int limit) {
        if (after == null) {
            return jdbc.query(PAGE_SQL + ORDER_SQL, (rs, n) -> row(rs), phone, limit);
        }
        return jdbc.query(PAGE_SQL + AFTER_SQL + ORDER_SQL, (rs, n) -> row(rs), phone,
                Timestamp.from(after.issuedAt()), after.tenantId(), after.invoiceId(), limit);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getString("schema_name"), rs.getObject("tenant_id", UUID.class),
                rs.getObject("invoice_id", UUID.class), rs.getTimestamp("issued_at").toInstant());
    }
}
