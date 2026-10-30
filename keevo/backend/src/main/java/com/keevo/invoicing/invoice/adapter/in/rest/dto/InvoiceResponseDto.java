package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An invoice as returned by the API (Story 14.1). For an issued invoice it carries every CGI art. 150 (5) element
 * available: seller identity snapshot, client identity, lines with HT / rate / VAT / TTC and their exemption mention,
 * totals HT / VAT / TTC, « TVA non applicable » for a non-VAT-registered seller. {@code complianceWarnings}: missing
 * seller mentions (French sentences, non-blocking).
 */
public record InvoiceResponseDto(
        UUID id,
        String number,
        String status,
        String statusLabel,
        LocalDate issueDate,
        Client client,
        Seller seller,
        boolean vatRegistered,
        BigDecimal vatRate,
        boolean pricesIncludeVat,
        String vatMention,
        List<Line> lines,
        long totalHt,
        long totalVat,
        long totalTtc,
        Instant issuedAt,
        Instant paidAt,
        String paymentMethod,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt,
        List<String> complianceWarnings) {

    public record Client(UUID id, String name, String niu, String address, String phone) {
    }

    public record Seller(String legalName, String address, String phone, String niu, String rccm,
                         String paymentDetails, String footer) {
    }

    public record Line(int position, String description, BigDecimal quantity, long unitPrice, String vatTreatment,
                       BigDecimal vatRate, String vatMention, long totalHt, long totalVat, long totalTtc) {
    }

    /** @param currentSettings the seller's current settings (warnings of a draft); ignored once issued */
    public static InvoiceResponseDto fromDomain(Invoice i, InvoicingSettings currentSettings) {
        ClientInfo c = i.client();
        SellerSnapshot s = i.seller();
        return new InvoiceResponseDto(i.id(), i.number(), i.status().name(), i.status().label(), i.issueDate(),
                new Client(c.clientId(), c.name(), c.niu(), c.address(), c.phone()),
                s == null ? null : new Seller(s.legalName(), s.address(), s.phone(), s.niu(), s.rccm(),
                        s.paymentDetails(), s.footer()),
                i.vatRegistered(), i.vatRate(), i.pricesIncludeVat(), i.vatMention(),
                i.lines().stream().map(InvoiceResponseDto::line).toList(),
                i.totals().totalHt(), i.totals().totalVat(), i.totals().totalTtc(), i.issuedAt(), i.paidAt(),
                i.paymentMethod(), i.cancelledAt(), i.cancellationReason(), i.createdAt(), i.updatedAt(),
                i.complianceWarnings(currentSettings));
    }

    private static Line line(InvoiceLine l) {
        return new Line(l.position(), l.description(), l.quantity(), l.unitPrice(), l.treatment().name(), l.vatRate(),
                l.vatMention(), l.totalHt(), l.totalVat(), l.totalTtc());
    }
}
