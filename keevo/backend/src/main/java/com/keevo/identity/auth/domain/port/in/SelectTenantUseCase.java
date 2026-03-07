package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.AuthTokens;

/**
 * SelectTenantUseCase — Driving port for step 2 of two-step login (Story 1.7).
 *
 * <p>Validates the loginToken from step 1, verifies the requested tenant membership,
 * generates a full scoped accessToken + refreshToken, and returns AuthTokens.
 */
public interface SelectTenantUseCase {

    /**
     * Complete the two-step login by selecting a tenant.
     *
     * @param command carries loginToken + tenantCode
     * @return full authentication tokens scoped to the selected tenant
     */
    AuthTokens select(SelectTenantCommand command);
}
