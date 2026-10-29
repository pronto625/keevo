package com.keevo.identity.auth.adapter.in.api;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantView;
import com.keevo.identity.api.UserQueryPort;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;

/**
 * Test-only access to the package-private tenant / user adapters of {@code identity :: api}
 * (Story 12.2d), for tests of other packages that drive them over mocked repositories.
 */
public final class IdentityAuthApiTestSupport {

    private IdentityAuthApiTestSupport() {}

    public static TenantQueryPort tenantQueryPort(TenantRepository tenantRepository) {
        return new TenantQueryAdapter(tenantRepository);
    }

    public static UserQueryPort userQueryPort(UserRepository userRepository) {
        return new UserQueryAdapter(userRepository);
    }

    /** The published view of a tenant, mapped exactly as the adapter does. */
    public static TenantView view(Tenant tenant) {
        return TenantViews.of(tenant);
    }
}
