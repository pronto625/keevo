package com.keevo.invoicing.invoice.adapter.out.persistence;

import com.keevo.invoicing.invoice.domain.port.out.LegacyInvoiceLinkRegistry;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LegacyInvoiceLinkRegistry} on {@code public.invoice_legacy_links} (public V57, Story 20.7): one primary-key
 * read per opened link, whatever the number of tenants.
 */
@Component
public class JdbcLegacyInvoiceLinkRegistry implements LegacyInvoiceLinkRegistry {

    private final JdbcTemplate jdbc;

    public JdbcLegacyInvoiceLinkRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Target> find(UUID token) {
        List<Target> rows = jdbc.query("SELECT tenant_schema, invoice_id, expires_at FROM public.invoice_legacy_links "
                        + "WHERE token = ?",
                (rs, n) -> {
                    Timestamp exp = rs.getTimestamp("expires_at");
                    return new Target(rs.getString("tenant_schema"), rs.getObject("invoice_id", UUID.class),
                            exp == null ? null : exp.toInstant());
                }, token);
        return rows.stream().filter(t -> TenantSchema.isValid(t.tenantSchema())).findFirst();
    }

    @Override
    public void register(UUID token, String tenantSchema, UUID invoiceId, Instant expiresAt) {
        // a replay for the same target only refreshes the expiry; a token is never moved to another tenant / invoice
        int written = jdbc.update("INSERT INTO public.invoice_legacy_links AS l (token, tenant_schema, invoice_id, "
                        + "expires_at) VALUES (?, ?, ?, ?) ON CONFLICT (token) DO UPDATE SET expires_at = "
                        + "EXCLUDED.expires_at WHERE l.tenant_schema = EXCLUDED.tenant_schema "
                        + "AND l.invoice_id = EXCLUDED.invoice_id",
                token, TenantSchema.validate(tenantSchema), invoiceId,
                expiresAt == null ? null : Timestamp.from(expiresAt));
        if (written == 0) {
            throw new IllegalStateException("Bref public token already attached to another invoice");
        }
    }

    @Override
    public void unregister(UUID token) {
        jdbc.update("DELETE FROM public.invoice_legacy_links WHERE token = ?", token);
    }
}
