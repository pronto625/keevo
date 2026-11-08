package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.adapter.in.rest.dto.InvoiceResponseDto;
import com.keevo.invoicing.invoice.application.usecase.InvoiceQueryUseCase;
import com.keevo.invoicing.invoice.application.usecase.InvoicingSettingsUseCase;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.7 — one invoice as the API returns it (credits, collection, presentation: one query each), for the
 * controllers other than {@link InvoiceController}.
 */
@Component
public class InvoiceResponseAssembler {

    private final InvoiceQueryUseCase queries;
    private final InvoicingSettingsUseCase settings;

    public InvoiceResponseAssembler(InvoiceQueryUseCase queries, InvoicingSettingsUseCase settings) {
        this.queries = queries;
        this.settings = settings;
    }

    public InvoiceResponseDto dto(Invoice invoice) {
        boolean draft = invoice.status() == InvoiceStatus.DRAFT;
        Map<UUID, InvoiceQueryUseCase.Credits> credits = draft ? Map.of() : queries.credits(List.of(invoice));
        InvoiceQueryUseCase.Credits c = credits.getOrDefault(invoice.id(), InvoiceQueryUseCase.Credits.NONE);
        return InvoiceResponseDto.fromDomain(invoice, draft ? settings.current() : null, c.notes(),
                c.fullyCredited(), queries.collections(List.of(invoice), credits).get(invoice.id()),
                queries.presentations(List.of(invoice)).get(invoice.id()));
    }
}
