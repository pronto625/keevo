package com.keevo.invoicing.invoice.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.7 (FUS-FR23) — the cross-tenant index of old Bref public links ({@code public.invoice_legacy_links}): an
 * old link carries only Bref's UUID token, so the tenant and the invoice are found here by primary key.
 */
public interface LegacyInvoiceLinkRegistry {

    /** Where an old Bref link points to. {@code expiresAt} {@code null} = no expiry of its own. */
    record Target(String tenantSchema, UUID invoiceId, Instant expiresAt) {
    }

    Optional<Target> find(UUID token);

    /** Records (or re-points) a token — the import (Story 24.4). */
    void register(UUID token, String tenantSchema, UUID invoiceId, Instant expiresAt);

    /** The token stops opening anything (rotation); unknown token = no-op. */
    void unregister(UUID token);
}
