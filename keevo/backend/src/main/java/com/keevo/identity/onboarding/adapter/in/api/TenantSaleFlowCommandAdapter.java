package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowCommandPort;
import com.keevo.shared.event.catalog.onboarding.SaleFlowChangedEvent;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements {@link TenantSaleFlowCommandPort} (Story 19.12) for the Bref importer: one set-based statement on the
 * explicit, validated schema ({@code "<schema>".tenant_preferences}, no search_path dependency, works without a bound
 * tenant context), changing the row only when its value differs, and returning the previous value for the audit.
 * O(1): one UPDATE (plus one EXISTS only when nothing changed, to tell a no-op from a missing row).
 */
@Component
class TenantSaleFlowCommandAdapter implements TenantSaleFlowCommandPort {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    TenantSaleFlowCommandAdapter(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    private record Changed(UUID id, String before) {
    }

    @Override
    public void set(String tenantSchema, SaleFlow flow) {
        if (flow == null) {
            throw new IllegalArgumentException("Sale flow is required");
        }
        String schema = TenantSchema.validate(tenantSchema);
        String table = "\"" + schema + "\".tenant_preferences";
        // the current row is the latest one (same rule as the JPA repository's findTopByOrderByCreatedAtDesc)
        List<Changed> changed = jdbc.query(
                "WITH cur AS (SELECT id, default_sale_flow FROM " + table
                        + " ORDER BY created_at DESC LIMIT 1 FOR UPDATE) "
                        + "UPDATE " + table + " t SET default_sale_flow = ?, updated_at = now() FROM cur "
                        + "WHERE t.id = cur.id AND cur.default_sale_flow <> ? "
                        + "RETURNING t.id, cur.default_sale_flow",
                (rs, i) -> new Changed(rs.getObject(1, UUID.class), rs.getString(2)),
                flow.name(), flow.name());
        if (changed.isEmpty()) {
            Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table + ")", Boolean.class);
            if (!Boolean.TRUE.equals(exists)) {
                throw new IllegalStateException("No tenant preferences for this tenant");
            }
            return;
        }
        Changed c = changed.get(0);
        String beforeLabel = SaleFlow.fromCode(c.before()).map(SaleFlow::label).orElse(null);
        events.publishEvent(new SaleFlowChangedEvent(c.id(), beforeLabel, flow.label(),
                SaleFlowChangedEvent.SYSTEM_ACTOR, schema, Instant.now()));
    }
}
