package com.keevo.invoicing.invoice.adapter.in.rest;

import com.keevo.invoicing.invoice.application.usecase.DocumentStoreResolver;
import com.keevo.shared.infrastructure.persistence.TenantContext;
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
 * Story 20.7 — one invoice as the API returns it (credits, collection, presentation: one query each; Story 20.14:
 * the store's name, one query when the invoice has a store), for the
 * controllers other than {@link InvoiceController}.
 */
@Component
public class InvoiceResponseAssembler {

    private final InvoiceQueryUseCase queries;
    private final InvoicingSettingsUseCase settings;
    private final DocumentStoreResolver stores;

    public InvoiceResponseAssembler(InvoiceQueryUseCase queries, InvoicingSettingsUseCase settings,
                                    DocumentStoreResolver stores) {
        this.queries = queries;
        this.settings = settings;
        this.stores = stores;
    }

    public InvoiceResponseDto dto(Invoice invoice) {
        boolean draft = invoice.status() == InvoiceStatus.DRAFT;
        Map<UUID, InvoiceQueryUseCase.Credits> credits = draft ? Map.of() : queries.credits(List.of(invoice));
        InvoiceQueryUseCase.Credits c = credits.getOrDefault(invoice.id(), InvoiceQueryUseCase.Credits.NONE);
        return InvoiceResponseDto.fromDomain(invoice, draft ? settings.current() : null, c.notes(),
                c.fullyCredited(), queries.collections(List.of(invoice), credits).get(invoice.id()),
                queries.presentations(List.of(invoice)).get(invoice.id()),
                invoice.storeId() == null ? null // Story 20.14 — the store's name: one lookup, none without store
                        : stores.namesOf(List.of(invoice), TenantContext.getCurrentTenant()).get(invoice.storeId()));
    }
}
