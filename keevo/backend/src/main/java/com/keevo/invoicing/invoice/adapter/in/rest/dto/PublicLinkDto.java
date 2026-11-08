package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.usecase.InvoicePublicLinkUseCase;

import java.time.Instant;

/**
 * Story 20.7 — a signed public link to a document: {@code url} (absolute, opens the PDF without login),
 * {@code expiresAt}, {@code validityDays} (the tenant's lifetime).
 */
public record PublicLinkDto(String url, Instant expiresAt, int validityDays) {

    public static PublicLinkDto from(InvoicePublicLinkUseCase.Link link) {
        return new PublicLinkDto(link.url(), link.expiresAt(), link.validityDays());
    }
}
