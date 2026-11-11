package com.keevo.shared.capability.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.7 — the documents shown to an end client in the client portal, read each in its own tenant schema, with
 * the minimum of fields and a short-lived signed link to the public PDF. Implemented by {@code invoicing}; the caller
 * has already decided which tenants may show their documents (tenant active, portal enabled).
 */
public interface PortalDocuments {

    /** Validity of a link handed out by the portal (re-signed at every listing). */
    java.time.Duration LINK_VALIDITY = java.time.Duration.ofHours(24);

    /**
     * Reads the invoices {@code idsBySchema} in one statement. Only non-deleted invoices (not quotes) that are issued,
     * paid or cancelled are returned; anything else (unknown id, draft, quote, deleted) is absent. Order is not
     * significant (the caller orders by its index).
     *
     * @param idsBySchema tenant schema → invoice ids (schemas validated by the implementation)
     * @param baseUrl     absolute base of the links ({@code https://keevo.ad2s.cm})
     */
    List<PortalDocument> find(Map<String, ? extends Collection<UUID>> idsBySchema, String baseUrl);

    /**
     * What the portal shows of one invoice. {@code tenantSchema} and {@code invoiceId} are keys for the caller, never
     * shown to the client.
     *
     * @param merchantName      seller name printed on the invoice
     * @param reference         {@code FAC-2026-000001}, or the Bref reference of an imported invoice
     * @param paymentState      {@code UNPAID | PARTIAL | PAID | OVERDUE | CANCELLED}
     * @param paymentStateLabel French label of {@code paymentState}
     * @param linkUrl           signed link to the PDF, null for a cancelled invoice
     * @param linkExpiresAt     when {@code linkUrl} stops opening, null without a link
     */
    record PortalDocument(String tenantSchema, UUID invoiceId, String merchantName, String reference,
                          LocalDate issueDate, LocalDate dueDate, long totalTtc, String paymentState,
                          String paymentStateLabel, String linkUrl, Instant linkExpiresAt) {
    }
}
