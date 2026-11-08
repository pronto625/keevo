package com.keevo.invoicing.invoice.adapter.in.rest.dto;

import com.keevo.invoicing.invoice.application.usecase.ManageDraftInvoiceUseCase.DraftContent;
import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.invoicing.invoice.domain.model.InvoiceTerms;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.model.VatTreatment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Body of {@code POST /invoices} and {@code PUT /invoices/{id}} (Story 14.1): the whole draft content. Story 15.5b:
 * {@code id} = the invoice id chosen by the device (optional, creation only — ignored by {@code PUT}); a creation sent
 * again with the same id returns the invoice already created (offline-first app: the same id is used by the online
 * call and by the queued {@code CREATE_INVOICE} sync operation). Also the payload of the {@code CREATE_INVOICE} /
 * {@code UPDATE_INVOICE} sync operations, validated with the same rules.
 *
 * <p>Story 20.3 (additive, V1 bodies unchanged): {@code kind} = {@code INVOICE} (absent on a creation) or
 * {@code QUOTE}; absent on an edit = unchanged, another kind than the document's → 409; {@code expectedUpdatedAt} =
 * the {@code updatedAt} the editor last saw (edit only,
 * optional): the row changed since → 409 {@code OPTIMISTIC_LOCK}.
 *
 * <p>Story 20.5 (additive, absent = today's behaviour): {@code discountAmount} (whole XAF ≥ 0, ≤ the subtotal, else
 * 422), {@code deliveryFee} (≥ 0, outside VAT), {@code vatRate} (percent 0–100, 2 decimals; absent = the seller's
 * rate), {@code vatExempt} (no VAT on the document, « Exonéré de TVA »), {@code dueDate} (never before the issue date
 * once issued). A {@code PUT} replaces the whole content: absent fields are reset (no discount, no delivery…).
 *
 * <p>Story 20.14 (additive): {@code storeId} = the store the document is made in, creation only (ignored by
 * {@code PUT}): a store member's own store (another one → 403), an owner's active store (unknown or inactive → 400);
 * absent = the member's store, or the owner's oldest active store.
 */
public record InvoiceRequestDto(
        UUID id,
        @NotNull(message = "Le client est requis")
        @Valid
        InvoiceClientDto client,
        @Size(max = 200, message = "Une facture compte au plus 200 lignes")
        List<@Valid @NotNull(message = "Ligne vide") InvoiceLineRequestDto> lines,
        @Pattern(regexp = "(?i)INVOICE|QUOTE", message = "Le type de document doit être INVOICE (facture) ou QUOTE (devis)")
        String kind,
        Instant expectedUpdatedAt,
        // Story 20.5 — discount ≤ subtotal is checked by the domain (VatCalculator): 422 with a French reason
        @PositiveOrZero(message = "La remise ne peut pas être négative")
        @Max(value = 1_000_000_000_000L, message = "La remise est trop élevée")
        Long discountAmount,
        @PositiveOrZero(message = "Les frais de livraison ne peuvent pas être négatifs")
        @Max(value = 1_000_000_000_000L, message = "Les frais de livraison sont trop élevés")
        Long deliveryFee,
        @DecimalMin(value = "0", message = "Le taux de TVA ne peut pas être négatif")
        @DecimalMax(value = "100", message = "Le taux de TVA ne peut pas dépasser 100 %")
        @Digits(integer = 3, fraction = 2, message = "Le taux de TVA accepte au plus 2 décimales")
        BigDecimal vatRate,
        Boolean vatExempt,
        LocalDate dueDate,
        UUID storeId) {

    /** A Story 20.5 body: no store requested. */
    public InvoiceRequestDto(UUID id, InvoiceClientDto client, List<InvoiceLineRequestDto> lines, String kind,
                             Instant expectedUpdatedAt, Long discountAmount, Long deliveryFee, BigDecimal vatRate,
                             Boolean vatExempt, LocalDate dueDate) {
        this(id, client, lines, kind, expectedUpdatedAt, discountAmount, deliveryFee, vatRate, vatExempt, dueDate,
                null);
    }

    /** A V1 body: an invoice, no edit check. */
    public InvoiceRequestDto(UUID id, InvoiceClientDto client, List<InvoiceLineRequestDto> lines) {
        this(id, client, lines, null, null);
    }

    /** A Story 20.3 body: no document terms. */
    public InvoiceRequestDto(UUID id, InvoiceClientDto client, List<InvoiceLineRequestDto> lines, String kind,
                             Instant expectedUpdatedAt) {
        this(id, client, lines, kind, expectedUpdatedAt, null, null, null, null, null);
    }

    /** The client and lines as the use cases take them ({@code STANDARD} VAT when the line names none). */
    public DraftContent toDraftContent() {
        ClientInfo info = new ClientInfo(client.id(), client.name(), client.niu(), client.address(), client.phone(),
                client.rccm(), client.email(), client.contactName(), client.website());
        List<InvoiceLineRequestDto> requested = lines == null ? List.of() : lines;
        List<LineInput> inputs = requested.stream().map(l -> new LineInput(l.description(), l.quantity(),
                l.unitPrice(), l.vatTreatment() == null ? VatTreatment.STANDARD
                : VatTreatment.valueOf(l.vatTreatment()), l.productId(), l.reference())).toList();
        // one rule with @Pattern: absent, or INVOICE / QUOTE in any case (nothing else, no padding)
        InvoiceKind documentKind = kind == null ? null : InvoiceKind.valueOf(kind.toUpperCase(Locale.ROOT));
        InvoiceTerms terms = new InvoiceTerms(discountAmount == null ? 0 : discountAmount,
                deliveryFee == null ? 0 : deliveryFee, vatRate, Boolean.TRUE.equals(vatExempt), dueDate);
        return new DraftContent(info, inputs, documentKind, expectedUpdatedAt, terms, storeId);
    }
}
