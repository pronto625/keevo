package com.keevo.identity.company.domain.port.out;

import com.keevo.shared.capability.business.CompanyIdentity.Logo;

import java.io.IOException;
import java.util.Optional;

/**
 * Storage of the company logo (Story 14.2): one file per tenant, keyed by the tenant schema taken from the session,
 * replaced on upload. Local disk ({@code keevo.company-logo.storage-dir}) like the product photos.
 */
public interface CompanyLogoStorage {

    /** Store (or replace) the logo. {@code contentType}: image/png or image/jpeg. */
    void store(String tenantSchema, byte[] content, String contentType) throws IOException;

    /** The stored logo, or empty. */
    Optional<Logo> read(String tenantSchema) throws IOException;

    /** Whether a logo is stored (no read of the bytes). */
    boolean exists(String tenantSchema);

    /** Delete the logo; a missing logo is a no-op. Never throws. */
    void delete(String tenantSchema);
}
