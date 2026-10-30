package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.service.InvoicePhones;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.port.out.CreditNoteRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An invoice as returned by the API (Story 14.1). For an issued invoice it carries every CGI art. 150 (5) element
 * available: seller identity snapshot, client identity, lines with HT / rate / VAT / TTC and their exemption mention,
 * totals HT / VAT / TTC, « TVA non applicable » for a non-VAT-registered seller. {@code complianceWarnings}: missing
 * seller mentions (French sentences, non-blocking). Story 14.2: WhatsApp sendings — {@code lastSentAt},
 * {@code lastSentTo} (masked, « +237 6•• ••• 111 »), {@code sendCount}. Story 14.4: {@code saleId} = the sale the
 * invoice was created from automatically (POS → Invoicing bridge), {@code null} for a manual invoice. Story 14.7:
 * {@code creditNotes} (number, date, credited TTC, reason — oldest first), {@code creditedTtc} = their sum,
 * {@code fullyCredited} = nothing remains to credit (every line quantity credited — the rule that refuses a further
 * credit note); the status is unchanged. {@code supersededAt}: when a sale correction replaced this automatic invoice
 * by a new one ({@code null} while active).
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
        List<String> complianceWarnings,
        Instant lastSentAt,
        String lastSentTo,
        int sendCount,
        UUID saleId,
        long creditedTtc,
        boolean fullyCredited,
        List<CreditNote> creditNotes,
        Instant supersededAt) {

    /** Story 14.7 — a credit note issued on the invoice. */
    public record CreditNote(UUID id, String number, Instant issuedAt, long totalTtc, String reason) {
    }

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
        return fromDomain(i, currentSettings, List.of(), false);
    }

    /**
     * @param currentSettings the seller's current settings (warnings of a draft); ignored once issued
     * @param creditNotes     the credit notes of the invoice (Story 14.7), oldest first
     * @param fullyCredited   nothing remains to credit on the invoice (domain predicate)
     */
    public static InvoiceResponseDto fromDomain(Invoice i, InvoicingSettings currentSettings,
                                                List<CreditNoteRepository.Summary> creditNotes,
                                                boolean fullyCredited) {
        List<CreditNote> notes = creditNotes == null ? List.of() : creditNotes.stream()
                .map(c -> new CreditNote(c.id(), c.number(), c.issuedAt(), c.totalTtc(), c.reason())).toList();
        long credited = notes.stream().mapToLong(CreditNote::totalTtc).sum();
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
                i.complianceWarnings(currentSettings), i.delivery().lastSentAt(),
                InvoicePhones.mask(i.delivery().lastSentTo()), i.delivery().sendCount(), i.sourceSaleId(),
                credited, fullyCredited, notes, i.supersededAt());
    }

    private static Line line(InvoiceLine l) {
        return new Line(l.position(), l.description(), l.quantity(), l.unitPrice(), l.treatment().name(), l.vatRate(),
                l.vatMention(), l.totalHt(), l.totalVat(), l.totalTtc());
    }
}
