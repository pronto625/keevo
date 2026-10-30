package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.usecase.InvoiceClientResolver.ClientSuggestions;

import java.util.List;
import java.util.UUID;

/**
 * Story 14.3 — answer of {@code GET /api/v1/invoices/clients}: whether the CRM is available for the tenant and the
 * matching CRM clients (empty while {@code crm} is off).
 */
public record InvoiceClientSuggestionsDto(boolean crmAvailable, List<Client> clients) {

    /** A CRM client offered by the picker. */
    public record Client(UUID id, String name, String phone) {
    }

    public static InvoiceClientSuggestionsDto from(ClientSuggestions suggestions) {
        return new InvoiceClientSuggestionsDto(suggestions.crmAvailable(), suggestions.clients().stream()
                .map(c -> new Client(c.id(), c.name(), c.phone())).toList());
    }
}
