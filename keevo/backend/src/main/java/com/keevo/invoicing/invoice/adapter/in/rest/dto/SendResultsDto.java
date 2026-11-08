package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.usecase.SendInvoiceUseCase;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.web.ErrorMessages;

import java.util.List;
import java.util.function.Function;

/**
 * Story 20.8 — answer of {@code POST /api/v1/invoices/{id}/send} with Bref's {@code channels}: the document as it
 * stands after every channel, and one result per channel (each processed independently: one failing never stops the
 * others). {@code status} = {@code SENT} | {@code FAILED} | {@code UNKNOWN} (maybe delivered: check with the client
 * before sending again); {@code errorCode} / {@code error} = the refusal and its French message ({@code null} when
 * sent).
 */
public record SendResultsDto(InvoiceResponseDto invoice, List<ChannelResult> results) {

    public record ChannelResult(String channel, String channelLabel, String status, String statusLabel,
                                String errorCode, String error) {
    }

    public static SendResultsDto of(SendInvoiceUseCase.Results results,
                                    Function<com.keevo.invoicing.invoice.domain.model.Invoice, InvoiceResponseDto> dto) {
        return new SendResultsDto(results.invoice() == null ? null : dto.apply(results.invoice()),
                results.results().stream().map(r -> new ChannelResult(r.channel().name(), r.channel().label(),
                        r.status().name(), r.status().label(), r.errorCode(), message(r.error()))).toList());
    }

    private static String message(DomainException e) {
        if (e == null) {
            return null;
        }
        return ErrorMessages.userMessage(e.getDomainCode(), e.getUserMessage(), "Envoi impossible", e.getDetails());
    }
}
