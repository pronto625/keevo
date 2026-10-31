package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.usecase.ManageDraftInvoiceUseCase.DraftContent;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /invoices} and {@code PUT /invoices/{id}} (Story 14.1): the whole draft content. Story 15.5b:
 * {@code id} = the invoice id chosen by the device (optional, creation only — ignored by {@code PUT}); a creation sent
 * again with the same id returns the invoice already created (offline-first app: the same id is used by the online
 * call and by the queued {@code CREATE_INVOICE} sync operation). Also the payload of the {@code CREATE_INVOICE} /
 * {@code UPDATE_INVOICE} sync operations, validated with the same rules.
 */
public record InvoiceRequestDto(
        UUID id,
        @NotNull(message = "Le client est requis")
        @Valid
        InvoiceClientDto client,
        @Size(max = 200, message = "Une facture compte au plus 200 lignes")
        List<@Valid @NotNull(message = "Ligne vide") InvoiceLineRequestDto> lines) {

    /** The client and lines as the use cases take them ({@code STANDARD} VAT when the line names none). */
    public DraftContent toDraftContent() {
        ClientInfo info = new ClientInfo(client.id(), client.name(), client.niu(), client.address(), client.phone());
        List<InvoiceLineRequestDto> requested = lines == null ? List.of() : lines;
        List<LineInput> inputs = requested.stream().map(l -> new LineInput(l.description(), l.quantity(),
                l.unitPrice(), l.vatTreatment() == null ? VatTreatment.STANDARD
                : VatTreatment.valueOf(l.vatTreatment()))).toList();
        return new DraftContent(info, inputs);
    }
}
