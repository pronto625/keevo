package com.keevo.identity.onboarding.adapter.in.api;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.api.TenantSaleFlowQueryPort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implements {@link TenantSaleFlowQueryPort} (Story 19.13): one read of the tenant's current preferences row on the
 * explicit, validated schema (same "latest row" rule as the JPA repository), no search_path dependency.
 */
@Component
class TenantSaleFlowQueryAdapter implements TenantSaleFlowQueryPort {

    private final JdbcTemplate jdbc;

    TenantSaleFlowQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantSaleFlowView> find(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        return jdbc.query("SELECT default_sale_flow, sector_type FROM \"" + schema
                        + "\".tenant_preferences ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> new TenantSaleFlowView(SaleFlow.fromCode(rs.getString(1)).orElse(SaleFlow.POS),
                        rs.getString(2)))
                .stream().findFirst();
    }
}
