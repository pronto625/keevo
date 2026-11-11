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

    /**
     * Records a token — the import (Story 24.4); the same token for the same target again only refreshes its expiry.
     *
     * @throws IllegalStateException the token already points to another tenant or invoice (never moved)
     */
    void register(UUID token, String tenantSchema, UUID invoiceId, Instant expiresAt);

    /** Story 24.4 — one token to record: the invoice it opens, and Bref's own expiry ({@code null} = none). */
    record Link(UUID token, UUID invoiceId, Instant expiresAt) {
    }

    /**
     * Story 24.4 — {@link #register} for many tokens of one tenant in one statement (the Bref import): same rule, a
     * token already recorded for the same target only refreshes its expiry.
     *
     * @throws IllegalStateException one of the tokens already points to another tenant or invoice (never moved)
     */
    void registerAll(String tenantSchema, java.util.List<Link> links);

    /** The token stops opening anything (rotation); unknown token = no-op. */
    void unregister(UUID token);
}
