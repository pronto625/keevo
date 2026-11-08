package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.util.Map;

/**
 * Story 20.7 — the tenant's document defaults ({@code invoicing_document_preferences}, one row): the layout and ticket
 * paper used when a document chose none, and how long a public link opens the document.
 *
 * @param template       default layout, {@code null} = Keevo standard layout
 * @param ticketWidth    default paper of the narrow layouts, {@code null} = 80 mm
 * @param publicLinkDays lifetime of a public link in days (1–365), {@code null} = {@link #DEFAULT_LINK_DAYS}; the Bref
 *                       import sets {@link #IMPORTED_LINK_DAYS} (Bref's own lifetime)
 */
public record DocumentPreferences(InvoiceTemplate template, TicketWidth ticketWidth, Integer publicLinkDays) {

    /** Lifetime of a Keevo public link (Story 14.2). */
    public static final int DEFAULT_LINK_DAYS = 30;
    /** Lifetime of a public link of a tenant imported from Bref (Bref's {@code NOW() + 90 days}). */
    public static final int IMPORTED_LINK_DAYS = 90;
    public static final int MAX_LINK_DAYS = 365;

    /** No row yet: Keevo layout, 80 mm tickets, 30-day links. */
    public static final DocumentPreferences DEFAULTS = new DocumentPreferences(null, null, null);

    public DocumentPreferences {
        if (publicLinkDays != null && (publicLinkDays < 1 || publicLinkDays > MAX_LINK_DAYS)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid public link lifetime",
                    Map.of("reason", "La durée d'un lien doit être comprise entre 1 et " + MAX_LINK_DAYS + " jours"));
        }
    }

    /** The lifetime of a public link signed now, in days. */
    public int linkDays() {
        return publicLinkDays == null ? DEFAULT_LINK_DAYS : publicLinkDays;
    }
}
