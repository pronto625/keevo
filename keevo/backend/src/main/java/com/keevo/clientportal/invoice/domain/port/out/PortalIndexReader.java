package com.keevo.clientportal.invoice.domain.port.out;

import com.keevo.clientportal.invoice.domain.model.PortalCursor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.7 — the invoices indexed for a phone, newest first, of the tenants that may show them (ACTIVE, portal not
 * switched off by the owner).
 */
public interface PortalIndexReader {

    /**
     * @param phone E.164 phone of the signed-in client
     * @param after keyset position (exclusive), null = from the newest
     * @param limit maximum rows
     */
    List<Row> page(String phone, PortalCursor after, int limit);

    /**
     * Reprise 11/11/2026 — the merchants (tenant schema → tenant id) that have indexed invoices for the phone and may
     * show them (same tenant filter as {@link #page}): the scope of the client's totals (Bref's header). One index
     * range scan, one row per merchant.
     */
    Map<String, UUID> merchantsOf(String phone);

    /** One indexed invoice with its tenant schema (to read it) and its keyset position. */
    record Row(String tenantSchema, UUID tenantId, UUID invoiceId, Instant issuedAt) {

        public PortalCursor cursor() {
            return new PortalCursor(issuedAt, tenantId, invoiceId);
        }
    }
}
