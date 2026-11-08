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
 * {@link LegacyInvoiceLinkRegistry} on {@code public.invoice_legacy_links} (public V58, Story 20.7): one primary-key
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
        jdbc.update("INSERT INTO public.invoice_legacy_links (token, tenant_schema, invoice_id, expires_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (token) DO UPDATE SET tenant_schema = EXCLUDED.tenant_schema, "
                        + "invoice_id = EXCLUDED.invoice_id, expires_at = EXCLUDED.expires_at",
                token, TenantSchema.validate(tenantSchema), invoiceId,
                expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    @Override
    public void unregister(UUID token) {
        jdbc.update("DELETE FROM public.invoice_legacy_links WHERE token = ?", token);
    }
}
