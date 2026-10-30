package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The invoice aggregate (Story 14.1). Immutable value: every transition returns a new instance.
 *
 * <p>A {@code DRAFT} is recomputed on every change with the seller's current settings; {@link #issue} recomputes it
 * once more, snapshots the seller and freezes everything with its number and issue date. Transitions: DRAFT →
 * ISSUED → PAID, ISSUED → CANCELLED (reason kept); anything else is refused ({@code INVOICE_NOT_EDITABLE} for a change
 * of an issued invoice, {@code INVOICE_INVALID_TRANSITION} otherwise).
 */
public record Invoice(
        UUID id,
        String number,
        InvoiceStatus status,
        LocalDate issueDate,
        ClientInfo client,
        SellerSnapshot seller,
        boolean vatRegistered,
        BigDecimal vatRate,
        boolean pricesIncludeVat,
        String vatMention,
        List<InvoiceLine> lines,
        InvoiceTotals totals,
        Instant issuedAt,
        Instant paidAt,
        String paymentMethod,
        Instant cancelledAt,
        String cancellationReason,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        InvoiceDelivery delivery,
        UUID sourceSaleId) {

    public Invoice {
        lines = lines == null ? List.of() : List.copyOf(lines);
        delivery = delivery == null ? InvoiceDelivery.NONE : delivery;
    }

    /** Story 14.2 shape: not created from a sale. */
    public Invoice(UUID id, String number, InvoiceStatus status, LocalDate issueDate, ClientInfo client,
                   SellerSnapshot seller, boolean vatRegistered, BigDecimal vatRate, boolean pricesIncludeVat,
                   String vatMention, List<InvoiceLine> lines, InvoiceTotals totals, Instant issuedAt, Instant paidAt,
                   String paymentMethod, Instant cancelledAt, String cancellationReason, UUID createdBy,
                   Instant createdAt, Instant updatedAt, InvoiceDelivery delivery) {
        this(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat, vatMention,
                lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason, createdBy, createdAt,
                updatedAt, delivery, null);
    }

    /** Story 14.1 shape: never sent. */
    public Invoice(UUID id, String number, InvoiceStatus status, LocalDate issueDate, ClientInfo client,
                   SellerSnapshot seller, boolean vatRegistered, BigDecimal vatRate, boolean pricesIncludeVat,
                   String vatMention, List<InvoiceLine> lines, InvoiceTotals totals, Instant issuedAt, Instant paidAt,
                   String paymentMethod, Instant cancelledAt, String cancellationReason, UUID createdBy,
                   Instant createdAt, Instant updatedAt) {
        this(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat, vatMention,
                lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason, createdBy, createdAt,
                updatedAt, InvoiceDelivery.NONE, null);
    }

    /** Story 14.2 — shown when the invoice prints the company display name instead of a typed legal name. */
    public static final String LEGAL_NAME_FALLBACK_WARNING =
            "La raison sociale n'est pas renseignée : le nom affiché de votre entreprise est utilisé.";

    /** A new draft computed with the seller's current settings. */
    public static Invoice draft(UUID id, ClientInfo client, List<LineInput> lines, InvoicingSettings settings,
                                UUID createdBy, Instant now) {
        requireClient(client);
        List<InvoiceLine> computed = VatCalculator.lines(lines, settings.vatRegistered(), settings.vatRate(),
                settings.pricesIncludeVat());
        return new Invoice(id, null, InvoiceStatus.DRAFT, null, client, null, settings.vatRegistered(),
                settings.vatRate(), settings.pricesIncludeVat(), VatCalculator.vatMention(settings.vatRegistered()),
                computed, VatCalculator.totals(computed), null, null, null, null, null, createdBy, now, now);
    }

    /** The draft with a new client and lines, recomputed with the current settings. */
    public Invoice withDraftContent(ClientInfo newClient, List<LineInput> newLines, InvoicingSettings settings,
                                    Instant now) {
        assertEditable();
        Invoice recomputed = draft(id, newClient, newLines, settings, createdBy, createdAt);
        return (sourceSaleId == null ? recomputed : recomputed.fromSale(sourceSaleId)).touched(now);
    }

    /** Refuses any change of a non-draft invoice. */
    public void assertEditable() {
        if (status != InvoiceStatus.DRAFT) {
            throw new DomainException(ErrorCode.INVOICE_NOT_EDITABLE, "Only a draft invoice can be changed");
        }
    }

    /**
     * The reasons (French) the draft cannot be issued yet: no line, no client name, no seller legal name. Empty when
     * issuable.
     */
    public List<String> issueBlockers(InvoicingSettings settings) {
        List<String> reasons = new ArrayList<>();
        if (lines.isEmpty()) {
            reasons.add("ajoutez au moins une ligne");
        }
        if (client == null || client.name() == null) {
            reasons.add("indiquez le nom du client");
        }
        if (settings.legalName() == null) {
            reasons.add("renseignez le nom de votre commerce dans les paramètres de facturation");
        }
        return reasons;
    }

    /**
     * Refuses to issue: not a draft → {@code INVOICE_INVALID_TRANSITION}; a missing mandatory element →
     * {@code INVOICE_NOT_ISSUABLE} with the French reasons. Checked before a number is allocated.
     */
    public void assertIssuable(InvoicingSettings settings) {
        if (status != InvoiceStatus.DRAFT) {
            throw invalidTransition();
        }
        List<String> blockers = issueBlockers(settings);
        if (!blockers.isEmpty()) {
            throw new DomainException(ErrorCode.INVOICE_NOT_ISSUABLE, "Invoice not issuable",
                    Map.of("reason", String.join(", ", blockers)));
        }
    }

    /**
     * Issues the draft: recomputed with {@code settings}, seller snapshot, number and issue date — atomically with
     * the number allocation (same transaction, by the caller).
     */
    public Invoice issue(String invoiceNumber, LocalDate date, InvoicingSettings settings, Instant now) {
        assertIssuable(settings);
        List<LineInput> inputs = lines.stream()
                .map(l -> new LineInput(l.description(), l.quantity(), l.unitPrice(), l.treatment())).toList();
        List<InvoiceLine> computed = VatCalculator.lines(inputs, settings.vatRegistered(), settings.vatRate(),
                settings.pricesIncludeVat());
        return new Invoice(id, invoiceNumber, InvoiceStatus.ISSUED, date, client, SellerSnapshot.of(settings),
                settings.vatRegistered(), settings.vatRate(), settings.pricesIncludeVat(),
                VatCalculator.vatMention(settings.vatRegistered()), computed, VatCalculator.totals(computed), now,
                null, null, null, null, createdBy, createdAt, now, delivery, sourceSaleId);
    }

    /** ISSUED → PAID. */
    public Invoice pay(String method, Instant now) {
        if (status != InvoiceStatus.ISSUED) {
            throw invalidTransition();
        }
        return new Invoice(id, number, InvoiceStatus.PAID, issueDate, client, seller, vatRegistered, vatRate,
                pricesIncludeVat, vatMention, lines, totals, issuedAt, now, InvoicingSettings.trimToNull(method),
                null, null, createdBy, createdAt, now, delivery, sourceSaleId);
    }

    /** ISSUED → CANCELLED, number kept. */
    public Invoice cancel(String reason, Instant now) {
        if (status != InvoiceStatus.ISSUED) {
            throw invalidTransition();
        }
        String why = InvoicingSettings.trimToNull(reason);
        if (why == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "A cancellation reason is required",
                    Map.of("reason", "Le motif d'annulation est requis"));
        }
        return new Invoice(id, number, InvoiceStatus.CANCELLED, issueDate, client, seller, vatRegistered, vatRate,
                pricesIncludeVat, vatMention, lines, totals, issuedAt, null, null, now, why, createdBy, createdAt,
                now, delivery, sourceSaleId);
    }

    /**
     * Mandatory mentions missing from the seller identity (French sentences): they do not block the issue but are
     * shown to the user. For a draft, from the current settings; for an issued invoice, from its snapshot.
     */
    public List<String> complianceWarnings(InvoicingSettings current) {
        String niu;
        String rccm;
        String address;
        boolean nameFromDisplay;
        if (seller != null) {
            niu = seller.niu();
            rccm = seller.rccm();
            address = seller.address();
            nameFromDisplay = seller.nameFromDisplayName();
        } else if (current != null) {
            niu = current.niu();
            rccm = current.rccm();
            address = current.address();
            nameFromDisplay = current.legalName() == null;
        } else {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (nameFromDisplay) {
            warnings.add(LEGAL_NAME_FALLBACK_WARNING);
        }
        if (niu == null) {
            warnings.add("Le NIU de votre commerce n'est pas renseigné : la facture ne sera pas déductible pour un "
                    + "client professionnel.");
        }
        if (rccm == null) {
            warnings.add("Le numéro RCCM de votre commerce n'est pas renseigné : c'est une mention obligatoire de la "
                    + "facture.");
        }
        if (address == null) {
            warnings.add("L'adresse de votre commerce n'est pas renseignée : c'est une mention obligatoire de la "
                    + "facture.");
        }
        return List.copyOf(warnings);
    }

    private Invoice touched(Instant now) {
        return new Invoice(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat,
                vatMention, lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason,
                createdBy, createdAt, now, delivery, sourceSaleId);
    }

    /**
     * Story 14.4 — the draft created automatically for the completed sale {@code saleId} (POS → Invoicing bridge):
     * the sale is the invoice's idempotency key (one invoice per sale).
     */
    public Invoice fromSale(UUID saleId) {
        assertEditable();
        return new Invoice(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat,
                vatMention, lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason,
                createdBy, createdAt, updatedAt, delivery, saleId);
    }

    /** Story 14.2 — the issued invoice with the seller identity flags frozen at issue. */
    public Invoice withSellerIdentity(boolean nameFromDisplayName, boolean hasLogo) {
        if (seller == null) {
            return this;
        }
        return new Invoice(id, number, status, issueDate, client, seller.withIdentityFlags(nameFromDisplayName,
                hasLogo), vatRegistered, vatRate, pricesIncludeVat, vatMention, lines, totals, issuedAt, paidAt,
                paymentMethod, cancelledAt, cancellationReason, createdBy, createdAt, updatedAt, delivery, sourceSaleId);
    }

    /**
     * Story 14.2 — only an issued or paid invoice can be sent (and re-sent) to the client: a draft has no legal value,
     * a cancelled invoice must not reach the client as if it were due → {@code INVOICE_INVALID_TRANSITION}.
     */
    public void assertSendable() {
        if (status != InvoiceStatus.ISSUED && status != InvoiceStatus.PAID) {
            throw invalidTransition();
        }
    }

    /** Story 14.2 — one more WhatsApp sending to {@code phone} (E.164) at {@code now}. */
    public Invoice sent(String phone, Instant now) {
        assertSendable();
        return recordSending(phone, now);
    }

    /**
     * Story 14.2 — records a sending that already left (the client received the message): no status check, so a
     * concurrent cancellation between the check and the delivery never loses the record of what was sent.
     */
    public Invoice recordSending(String phone, Instant now) {
        return new Invoice(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat,
                vatMention, lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason,
                createdBy, createdAt, updatedAt, delivery.sentTo(phone, now), sourceSaleId);
    }

    private DomainException invalidTransition() {
        return new DomainException(ErrorCode.INVOICE_INVALID_TRANSITION, "Transition not allowed",
                Map.of("status", status.label().toLowerCase(java.util.Locale.FRENCH)));
    }

    private static void requireClient(ClientInfo client) {
        if (client == null || client.name() == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "A client name is required",
                    Map.of("reason", "Le nom du client est requis"));
        }
    }
}
