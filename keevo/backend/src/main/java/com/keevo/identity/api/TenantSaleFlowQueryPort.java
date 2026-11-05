package com.keevo.identity.api;

import java.util.Optional;

/**
 * Story 19.13 — reads a tenant's default sale flow and sector for other modules (interface profile: navigation
 * preset, initial module activation rules). Works with or without a bound tenant context (the schema is explicit and
 * validated). O(1): one indexed read of the tenant's current preferences row.
 */
public interface TenantSaleFlowQueryPort {

    /**
     * @return empty before onboarding (no preferences row)
     * @throws IllegalArgumentException invalid schema name
     */
    Optional<TenantSaleFlowView> find(String tenantSchema);

    /**
     * @param saleFlow   the default sale flow (never null: {@code POS} when unset)
     * @param sectorType the onboarding sector code ({@code CLOTHING}, …, {@code OTHER}); {@code null} when unset
     */
    record TenantSaleFlowView(SaleFlow saleFlow, String sectorType) {

        public TenantSaleFlowView {
            saleFlow = saleFlow != null ? saleFlow : SaleFlow.POS;
        }

        /** The navigation preset derived from the sale flow. */
        public NavigationPreset navigationPreset() {
            return NavigationPreset.of(saleFlow);
        }
    }
}
