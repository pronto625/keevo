package com.keevo.shared.capability.business;

import java.util.Optional;

/**
 * The identity a tenant's business shows on its documents (Story 14.2, UX-DR5): exports and invoices use the same
 * name and logo.
 *
 * <p>Display rule: the company designation when filled in, else the tenant name (the shop name typed at
 * onboarding), else « Mon commerce » — never a technical code.
 */
public interface CompanyIdentity {

    /** Fallback when the tenant has neither a designation nor a usable name. */
    String DEFAULT_NAME = "Mon commerce";

    /** Designation › tenant name › {@link #DEFAULT_NAME}; never null nor blank. */
    String displayName(String tenantSchema);

    /** The company logo (PNG or JPEG, already normalized), empty when none was uploaded. */
    Optional<Logo> logo(String tenantSchema);

    /** Whether a logo is stored — no read of the bytes (cheap, used inside transactions). */
    boolean hasLogo(String tenantSchema);

    /** Whether the tenant is ACTIVE (not suspended, not being deleted): public links of its documents work. */
    boolean isActive(String tenantSchema);

    /** Logo bytes with their content type ({@code image/png} or {@code image/jpeg}). */
    record Logo(byte[] content, String contentType) {

        /** {@code data:} URI for an HTML/PDF template. */
        public String dataUri() {
            return "data:" + contentType + ";base64," + java.util.Base64.getEncoder().encodeToString(content);
        }
    }
}
