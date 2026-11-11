package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.InvoiceTemplate;
import com.keevo.invoicing.invoice.domain.model.SignatureRole;
import com.keevo.invoicing.invoice.domain.model.TicketWidth;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 20.7 — the presentation columns of the current tenant's documents ({@code invoices.invoice_template},
 * {@code ticket_width}, signature file names, {@code public_link_version}, legacy Bref token / reference) and the
 * tenant's document defaults ({@code invoicing_document_preferences}). Kept apart from the invoice aggregate: none of
 * it is legal content. Every write moves the invoice's {@code updated_at} (never backwards) so the sync pull carries
 * the change; every write runs in the caller's transaction.
 */
public interface InvoicePresentationRepository {

    /** The presentation of one live document; empty when unknown. */
    Optional<InvoicePresentation> find(UUID invoiceId);

    /** The presentation of each document of a page — one query (documents without row are absent). */
    Map<UUID, InvoicePresentation> findAll(Collection<UUID> invoiceIds);

    /** Sets the document's layout ({@code null} = the tenant default). */
    void updateLayout(UUID invoiceId, InvoiceTemplate template, TicketWidth width, Instant now);

    /** Sets (or clears, {@code fileName} null) the signature file of {@code role}. */
    void updateSignature(UUID invoiceId, SignatureRole role, String fileName, Instant now);

    /** Story 20.7 — the document's public link was handed out (first time only; one UPDATE). */
    void markShared(UUID invoiceId, Instant now);

    /**
     * Quote conversion — the new invoice takes the quote's layout and signatures (same signature files: a converted
     * quote's signatures can no longer change, an issued invoice's neither). One UPDATE … FROM on primary keys.
     */
    void copyLayoutAndSignatures(UUID fromId, UUID toId, Instant now);

    /**
     * Rotation: bumps {@code public_link_version} and clears the legacy Bref token (one UPDATE); returns the new
     * version, empty when the document is unknown.
     */
    Optional<Integer> rotateLink(UUID invoiceId, Instant now);

    /** Story 24.4 import — records the Bref token and reference of an imported invoice. */
    void attachLegacy(UUID invoiceId, UUID legacyToken, String legacyReference, Instant now);

    /** The tenant's document defaults; {@link DocumentPreferences#DEFAULTS} when never saved. */
    DocumentPreferences preferences();

    void savePreferences(DocumentPreferences preferences, UUID updatedBy, Instant now);
}
