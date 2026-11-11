package com.keevo.shared.capability.portal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.7 — the cross-tenant index of the invoices by client phone ({@code public.invoice_phone_index}), read by
 * the client portal. One row per invoice (the client of an issued invoice never changes). Implemented by the
 * {@code client-portal} module; called by {@code invoicing} inside the issue transaction (the row commits with the
 * invoice) and by the one-shot back-fill of the invoices issued before.
 */
public interface InvoicePhoneIndex {

    /**
     * Indexes one issued invoice. A phone that is not a valid E.164 number once normalized ({@code +237} for a
     * 9-digit local number) indexes nothing. Idempotent (same invoice again = updated, never duplicated).
     *
     * @param tenantSchema schema of the invoice's tenant ({@code kv_xxxxxx})
     * @param clientPhone  phone typed on the invoice (raw), null or blank = nothing indexed
     * @param issuedAt     issue instant (newest first in the portal)
     */
    void index(String tenantSchema, UUID invoiceId, String clientPhone, Instant issuedAt);

    /**
     * Same as {@link #index} for many invoices of one tenant, in one batch.
     *
     * @return rows written (invalid phones skipped)
     */
    int indexAll(String tenantSchema, List<Entry> entries);

    /** One invoice to index: its id, the raw client phone and the issue instant. */
    record Entry(UUID invoiceId, String clientPhone, Instant issuedAt) {
    }
}
