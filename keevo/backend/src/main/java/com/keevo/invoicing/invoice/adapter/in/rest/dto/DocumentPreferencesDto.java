package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.domain.model.DocumentPreferences;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Story 20.7 — {@code GET / PUT /api/v1/invoicing/settings/documents}: the tenant's document defaults.
 * {@code invoiceTemplate} ({@code null} = Keevo standard layout), {@code ticketWidth} ({@code null} = 80 mm),
 * {@code publicLinkDays} (1–365, {@code null} = 30). A PUT replaces the three values. The response also gives the
 * values in effect: {@code effectiveTicketWidth}, {@code effectivePublicLinkDays}.
 */
public record DocumentPreferencesDto(
        @Size(max = 30, message = "Modèle inconnu") String invoiceTemplate,
        @Size(max = 10, message = "Largeur de ticket inconnue : 58, 80 ou A4") String ticketWidth,
        @Min(value = 1, message = "La durée d'un lien doit être comprise entre 1 et 365 jours")
        @Max(value = DocumentPreferences.MAX_LINK_DAYS,
                message = "La durée d'un lien doit être comprise entre 1 et 365 jours")
        Integer publicLinkDays,
        String effectiveTicketWidth,
        Integer effectivePublicLinkDays) {

    public static DocumentPreferencesDto from(DocumentPreferences p) {
        return new DocumentPreferencesDto(p.template() == null ? null : p.template().name(),
                p.ticketWidth() == null ? null : p.ticketWidth().code(), p.publicLinkDays(),
                (p.ticketWidth() == null ? com.keevo.invoicing.invoice.domain.model.TicketWidth.DEFAULT
                        : p.ticketWidth()).code(), p.linkDays());
    }
}
