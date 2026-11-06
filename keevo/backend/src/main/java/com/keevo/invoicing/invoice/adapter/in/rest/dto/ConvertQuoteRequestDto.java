package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.3 — optional body of {@code POST /invoices/{quoteId}/convert} (and payload of the {@code CONVERT_QUOTE}
 * sync operation, with {@code quoteId}): {@code invoiceId} = the id the device chose for the new invoice (optional;
 * the server generates one otherwise); {@code expectedUpdatedAt} = the {@code updatedAt} of the quote last read
 * (optional): the quote changed since → 409 {@code OPTIMISTIC_LOCK}, nothing converted.
 */
public record ConvertQuoteRequestDto(UUID invoiceId, Instant expectedUpdatedAt) {
}
