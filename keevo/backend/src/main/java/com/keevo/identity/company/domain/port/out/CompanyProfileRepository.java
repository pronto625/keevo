package com.keevo.identity.company.domain.port.out;

import java.util.Optional;
import java.util.UUID;

/** The company identity stored on the tenant row ({@code public.tenants}) — Story 14.2. */
public interface CompanyProfileRepository {

    /** Tenant row of {@code tenantSchema}, empty when unknown. */
    Optional<TenantIdentity> find(String tenantSchema);

    /** Sets (or clears with {@code null}) the designation; false when no tenant has this schema. */
    boolean updateDesignation(String tenantSchema, String designation);

    /**
     * @param name   tenant name (shop name typed at onboarding), may equal the tenant code for legacy rows
     * @param status tenant status name ({@code ACTIVE}, {@code SUSPENDED}, …)
     */
    record TenantIdentity(UUID id, String code, String name, String designation, String status) {
    }
}
