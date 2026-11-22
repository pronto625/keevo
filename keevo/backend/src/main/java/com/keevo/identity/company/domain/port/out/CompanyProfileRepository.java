package com.keevo.identity.company.domain.port.out;

import java.util.Optional;
import java.util.UUID;

/** The company identity stored on the tenant row ({@code public.tenants}) — Story 14.2. */
public interface CompanyProfileRepository {

    /** Tenant row of {@code tenantSchema}, empty when unknown. */
    Optional<TenantIdentity> find(String tenantSchema);

    /** Sets (or clears with {@code null}) the designation; false when no tenant has this schema. */
    boolean updateDesignation(String tenantSchema, String designation);

    /** The identity details and the setup mark of the tenant, empty when unknown. */
    Optional<CompanyDetails> findDetails(String tenantSchema);

    /**
     * Applies the patch: a {@code null} field is left unchanged, a blank {@code String} clears a nullable column.
     * False when no tenant has this schema.
     */
    boolean updateDetails(String tenantSchema, DetailsPatch patch);

    /** Marks the sign-up steps finished (once; a second call changes nothing). False when no tenant has this schema. */
    boolean markSetupCompleted(String tenantSchema);

    /** What the owner can fill in beyond the designation. Every text field is nullable. */
    record CompanyDetails(String description, String category, String address, String phone, String email,
                          String niu, String rccm, String country, boolean setupCompleted) {
    }

    /** {@code null} = unchanged; blank = cleared (except {@code country}, which can never be cleared). */
    record DetailsPatch(String designation, String description, String category, String address, String phone,
                        String email, String niu, String rccm, String country) {
    }

    /**
     * @param name   tenant name (shop name typed at onboarding), may equal the tenant code for legacy rows
     * @param status tenant status name ({@code ACTIVE}, {@code SUSPENDED}, …)
     */
    record TenantIdentity(UUID id, String code, String name, String designation, String status) {
    }
}
