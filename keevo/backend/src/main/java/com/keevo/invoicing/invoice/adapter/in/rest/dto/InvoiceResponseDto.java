package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.service.InvoicePhones;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceLine;
import com.keevo.invoicing.invoice.domain.model.InvoicePresentation;
import com.keevo.invoicing.invoice.domain.model.SignatureRole;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
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
 *
 * <p>Story 20.3 (additive): {@code kind} ({@code INVOICE} | {@code QUOTE}); {@code quoteReference} = the display
 * reference of a quote ({@code DV-XXXXXXXX}, {@code null} for an invoice); {@code convertedInvoiceId} /
 * {@code convertedAt} = the invoice a converted quote became; {@code sourceQuoteId} = on that invoice, the quote it
 * comes from.
 *
 * <p>Story 20.4 (additive): {@code paymentState} ({@code UNPAID} | {@code PARTIAL} | {@code PAID} | {@code OVERDUE},
 * {@code null} for a draft, a cancelled invoice or a quote) and its French {@code paymentStateLabel};
 * {@code amountPaid} (sum of the payments received), {@code amountDue} ({@code totalTtc − creditedTtc});
 * {@code payments} = payments received and scheduled instalments, oldest first ({@code legacy}: the single payment of an
 * invoice paid before 20.4, without id).
 *
 * <p>Story 20.5 (additive): {@code subtotal} (Σ lines as entered — HT or TTC per {@code pricesIncludeVat} — before the
 * discount), {@code discountAmount}, {@code deliveryFee} (included in {@code totalTtc}, outside VAT), {@code vatRate} =
 * the rate applied (0 when exempt), {@code vatRateOverride} = the rate requested for the document ({@code null} = the
 * seller's), {@code vatExempt}, {@code dueDate}; client {@code rccm}, {@code email}, {@code contactName},
 * {@code website}; line {@code productId}, {@code reference}. {@code totalTtc = totalHt + totalVat + deliveryFee}.
 *
 * <p>Story 20.7 (additive): {@code invoiceTemplate} (one of the 8 Bref layouts, {@code null} = the tenant default),
 * {@code ticketWidth} ({@code "58"} | {@code "80"} | {@code "A4"}, {@code null} = the tenant default),
 * {@code clientSigned} / {@code sellerSigned} (a signature is stored — never its file), {@code legacyReference} (the
 * Bref reference {@code BRF-XXXXXXXX} of an imported invoice, {@code null} otherwise).
 *
 * <p>Story 20.14 (additive): {@code storeId} / {@code storeName} = the store the document was made in ({@code null}
 * for a document made before, or outside any store); a credit note belongs to its invoice's store.
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
        Instant supersededAt,
        String kind,
        String quoteReference,
        UUID convertedInvoiceId,
        Instant convertedAt,
        UUID sourceQuoteId,
        String paymentState,
        String paymentStateLabel,
        long amountPaid,
        long amountDue,
        List<Payment> payments,
        long subtotal,
        long discountAmount,
        long deliveryFee,
        BigDecimal vatRateOverride,
        boolean vatExempt,
        LocalDate dueDate,
        String invoiceTemplate,
        String ticketWidth,
        boolean clientSigned,
        boolean sellerSigned,
        String legacyReference,
        UUID storeId,
        String storeName) {

    /** Story 20.4 — a payment received ({@code paidAt}) or an instalment ({@code dueDate}, unpaid while no paidAt). */
    public record Payment(UUID id, long amount, LocalDate dueDate, Instant paidAt, String method, String note,
                          boolean legacy) {
    }

    /** Story 14.7 — a credit note issued on the invoice. */
    public record CreditNote(UUID id, String number, Instant issuedAt, long totalTtc, String reason) {
    }

    public record Client(UUID id, String name, String niu, String address, String phone, String rccm, String email,
                         String contactName, String website) {
    }

    public record Seller(String legalName, String address, String phone, String niu, String rccm,
                         String paymentDetails, String footer) {
    }

    public record Line(int position, String description, BigDecimal quantity, long unitPrice, String vatTreatment,
                       BigDecimal vatRate, String vatMention, long totalHt, long totalVat, long totalTtc,
                       UUID productId, String reference) {
    }

    /**
     * @param currentSettings the seller's current settings (warnings of a draft); ignored once issued
     * @param creditNotes     the credit notes of the invoice (Story 14.7), oldest first
     * @param fullyCredited   nothing remains to credit on the invoice (domain predicate)
     * @param collection      Story 20.4 — the invoice's collection ({@code InvoiceQueryUseCase#collections}), required
     */
    public static InvoiceResponseDto fromDomain(Invoice i, InvoicingSettings currentSettings,
                                                List<CreditNoteRepository.Summary> creditNotes,
                                                boolean fullyCredited, PaymentStateCalculator.Collection collection) {
        return fromDomain(i, currentSettings, creditNotes, fullyCredited, collection, InvoicePresentation.NONE, null);
    }

    /** Story 20.7 — with the document's presentation ({@code null} = none stored). */
    public static InvoiceResponseDto fromDomain(Invoice i, InvoicingSettings currentSettings,
                                                List<CreditNoteRepository.Summary> creditNotes,
                                                boolean fullyCredited, PaymentStateCalculator.Collection collection,
                                                InvoicePresentation presentation) {
        return fromDomain(i, currentSettings, creditNotes, fullyCredited, collection, presentation, null);
    }

    /**
     * Story 20.14 — same, with {@code storeName} = the name of the invoice's store (looked up once per response or
     * page, {@code null} when the invoice has no store).
     */
    public static InvoiceResponseDto fromDomain(Invoice i, InvoicingSettings currentSettings,
                                                List<CreditNoteRepository.Summary> creditNotes,
                                                boolean fullyCredited, PaymentStateCalculator.Collection collection,
                                                InvoicePresentation presentation, String storeName) {
        InvoicePresentation look = presentation == null ? InvoicePresentation.NONE : presentation;
        List<CreditNote> notes = creditNotes == null ? List.of() : creditNotes.stream()
                .map(c -> new CreditNote(c.id(), c.number(), c.issuedAt(), c.totalTtc(), c.reason())).toList();
        long credited = notes.stream().mapToLong(CreditNote::totalTtc).sum();
        PaymentStateCalculator.Collection paid = java.util.Objects.requireNonNull(collection, "collection");
        ClientInfo c = i.client();
        SellerSnapshot s = i.seller();
        return new InvoiceResponseDto(i.id(), i.number(), i.status().name(), i.status().label(i.kind()),
                i.issueDate(),
                new Client(c.clientId(), c.name(), c.niu(), c.address(), c.phone(), c.rccm(), c.email(),
                        c.contactName(), c.website()),
                s == null ? null : new Seller(s.legalName(), s.address(), s.phone(), s.niu(), s.rccm(),
                        s.paymentDetails(), s.footer()),
                i.vatRegistered(), i.vatRate(), i.pricesIncludeVat(), i.vatMention(),
                i.lines().stream().map(InvoiceResponseDto::line).toList(),
                i.totals().totalHt(), i.totals().totalVat(), i.totals().totalTtc(), i.issuedAt(), i.paidAt(),
                i.paymentMethod(), i.cancelledAt(), i.cancellationReason(), i.createdAt(), i.updatedAt(),
                i.complianceWarnings(currentSettings), i.delivery().lastSentAt(),
                InvoicePhones.mask(i.delivery().lastSentTo()), i.delivery().sendCount(), i.sourceSaleId(),
                credited, fullyCredited, notes, i.supersededAt(), i.kind().name(), i.quoteReference(),
                i.convertedInvoiceId(), i.convertedAt(), i.sourceQuoteId(),
                paid.state() == null ? null : paid.state().name(), paid.stateLabel(), paid.amountPaid(),
                paid.amountDue(), paid.payments().stream().map(p -> new Payment(p.id(), p.amount(), p.dueDate(),
                        p.paidAt(), p.method(), p.note(), p.legacy())).toList(),
                i.totals().subtotal(), i.totals().discount(), i.totals().delivery(), i.terms().vatRate(),
                i.terms().vatExempt(), i.terms().dueDate(),
                look.template() == null ? null : look.template().name(),
                look.ticketWidth() == null ? null : look.ticketWidth().code(),
                look.signed(SignatureRole.CLIENT), look.signed(SignatureRole.SELLER), look.legacyReference(),
                i.storeId(), i.storeId() == null ? null : storeName);
    }

    private static Line line(InvoiceLine l) {
        return new Line(l.position(), l.description(), l.quantity(), l.unitPrice(), l.treatment().name(), l.vatRate(),
                l.vatMention(), l.totalHt(), l.totalVat(), l.totalTtc(), l.productId(), l.reference());
    }
}
