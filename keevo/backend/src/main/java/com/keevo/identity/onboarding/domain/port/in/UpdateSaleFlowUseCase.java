package com.keevo.identity.onboarding.domain.port.in;

import com.keevo.identity.api.SaleFlow;
import com.keevo.identity.onboarding.domain.model.TenantPreferences;

/**
 * Story 19.12 — the owner sets the tenant's default sale flow (current tenant). Same value → no write, no audit.
 * Core preference: never gated by a module.
 */
public interface UpdateSaleFlowUseCase {

    TenantPreferences update(SaleFlow flow);
}
