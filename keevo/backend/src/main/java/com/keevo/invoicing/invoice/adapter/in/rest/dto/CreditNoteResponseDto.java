package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.CreditNoteLine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A credit note — « avoir » (Story 14.7) as returned by the API: its number, the credited invoice ({@code invoiceId},
 * {@code invoiceNumber}), the reason, the credited lines ({@code invoicePosition} = the invoice line credited) and
 * totals. Amounts are positive (the credited amounts): the minus sign is presentation.
 */
public record CreditNoteResponseDto(
        UUID id,
        String number,
        UUID invoiceId,
        String invoiceNumber,
        String reason,
        Instant issuedAt,
        List<Line> lines,
        long totalHt,
        long totalVat,
        long totalTtc) {

    public record Line(int position, int invoicePosition, String description, BigDecimal quantity, long unitPrice,
                       String vatTreatment, BigDecimal vatRate, String vatMention, long totalHt, long totalVat,
                       long totalTtc) {
    }

    public static CreditNoteResponseDto fromDomain(CreditNote c) {
        return new CreditNoteResponseDto(c.id(), c.number(), c.invoiceId(), c.invoiceNumber(), c.reason(),
                c.issuedAt(), c.lines().stream().map(CreditNoteResponseDto::line).toList(), c.totals().totalHt(),
                c.totals().totalVat(), c.totals().totalTtc());
    }

    private static Line line(CreditNoteLine l) {
        return new Line(l.position(), l.invoicePosition(), l.description(), l.quantity(), l.unitPrice(),
                l.treatment().name(), l.vatRate(), l.vatMention(), l.totalHt(), l.totalVat(), l.totalTtc());
    }
}
