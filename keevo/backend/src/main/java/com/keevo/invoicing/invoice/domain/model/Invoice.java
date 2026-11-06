package com.keevo.invoicing.invoice.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;

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
 *
 * <p>Story 20.3 — {@link #kind}: a {@code QUOTE} is created and edited like a draft but is never issued, paid, sent nor
 * credited (« Un devis doit être converti en facture »); it ends {@code CONVERTED} (linked to a new invoice issued
 * from it, {@code convertedInvoiceId}) or {@code CANCELLED} (no number). An invoice created by a conversion carries
 * {@code sourceQuoteId}.
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
        UUID sourceSaleId,
        Instant supersededAt,
        InvoiceKind kind,
        UUID convertedInvoiceId,
        Instant convertedAt,
        UUID sourceQuoteId) {

    public Invoice {
        lines = lines == null ? List.of() : List.copyOf(lines);
        delivery = delivery == null ? InvoiceDelivery.NONE : delivery;
        kind = kind == null ? InvoiceKind.INVOICE : kind;
    }

    /** Story 14.7 shape: an invoice ({@link InvoiceKind#INVOICE}), not a quote nor converted from one (Story 20.3). */
    public Invoice(UUID id, String number, InvoiceStatus status, LocalDate issueDate, ClientInfo client,
                   SellerSnapshot seller, boolean vatRegistered, BigDecimal vatRate, boolean pricesIncludeVat,
                   String vatMention, List<InvoiceLine> lines, InvoiceTotals totals, Instant issuedAt, Instant paidAt,
                   String paymentMethod, Instant cancelledAt, String cancellationReason, UUID createdBy,
                   Instant createdAt, Instant updatedAt, InvoiceDelivery delivery, UUID sourceSaleId,
                   Instant supersededAt) {
        this(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat, vatMention,
                lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason, createdBy, createdAt,
                updatedAt, delivery, sourceSaleId, supersededAt, InvoiceKind.INVOICE, null, null, null);
    }

    /** Story 14.4 shape: not superseded (Story 14.7). */
    public Invoice(UUID id, String number, InvoiceStatus status, LocalDate issueDate, ClientInfo client,
                   SellerSnapshot seller, boolean vatRegistered, BigDecimal vatRate, boolean pricesIncludeVat,
                   String vatMention, List<InvoiceLine> lines, InvoiceTotals totals, Instant issuedAt, Instant paidAt,
                   String paymentMethod, Instant cancelledAt, String cancellationReason, UUID createdBy,
                   Instant createdAt, Instant updatedAt, InvoiceDelivery delivery, UUID sourceSaleId) {
        this(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat, vatMention,
                lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason, createdBy, createdAt,
                updatedAt, delivery, sourceSaleId, null);
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
        Invoice recomputed = draft(id, newClient, newLines, settings, createdBy, createdAt)
                .as(kind, sourceQuoteId);
        return (sourceSaleId == null ? recomputed : recomputed.fromSale(sourceSaleId)).touched(now);
    }

    /**
     * Refuses any change of a non-draft invoice. Story 20.3: a converted or cancelled quote is not editable either
     * ({@code INVOICE_NOT_EDITABLE}, with a quote wording).
     */
    public void assertEditable() {
        if (status != InvoiceStatus.DRAFT) {
            if (isQuote()) {
                throw DomainException.userFacing(ErrorCode.INVOICE_NOT_EDITABLE,
                        "Ce devis a été converti ou annulé : il ne peut plus être modifié ni supprimé");
            }
            throw new DomainException(ErrorCode.INVOICE_NOT_EDITABLE, "Only a draft invoice can be changed");
        }
    }

    // ── Story 20.3 — quotes ─────────────────────────────────────────────────────────────────────────────────

    /** French message of every invoice-only action attempted on a quote (issue, pay, send, credit note). */
    public static final String QUOTE_MUST_BE_CONVERTED = "Un devis doit être converti en facture";

    /** A new quote ({@code DRAFT}, no number), computed with the seller's current settings like a draft invoice. */
    public static Invoice quote(UUID id, ClientInfo client, List<LineInput> lines, InvoicingSettings settings,
                                UUID createdBy, Instant now) {
        return draft(id, client, lines, settings, createdBy, now).as(InvoiceKind.QUOTE, null);
    }

    public boolean isQuote() {
        return kind == InvoiceKind.QUOTE;
    }

    /** The display reference of a quote ({@code DV-XXXXXXXX}); {@code null} for an invoice. */
    public String quoteReference() {
        return isQuote() ? InvoiceKind.quoteReference(id) : null;
    }

    /** Refuses an invoice-only action on a quote → {@code INVOICE_INVALID_TRANSITION} « Un devis doit être converti… ». */
    public void assertInvoice() {
        if (isQuote()) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION, QUOTE_MUST_BE_CONVERTED,
                    Map.of("status", "devis"));
        }
    }

    /**
     * Refuses a conversion: an invoice is never converted, a quote only from {@code DRAFT} (a converted quote is
     * answered by the caller with its invoice, before this check) → {@code INVOICE_INVALID_TRANSITION}; an incomplete
     * quote (no line, no client name) → {@code INVOICE_NOT_ISSUABLE} « Ce devis est incomplet : … », before any number.
     */
    public void assertConvertible() {
        if (!isQuote()) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION,
                    "Seul un devis peut être converti en facture", Map.of("status", status.label(kind)));
        }
        if (status != InvoiceStatus.DRAFT) {
            throw quoteInvalidTransition();
        }
        List<String> missing = new ArrayList<>();
        if (lines.isEmpty()) {
            missing.add("ajoutez au moins une ligne");
        }
        if (client == null || client.name() == null) {
            missing.add("indiquez le nom du client");
        }
        if (!missing.isEmpty()) {
            throw DomainException.userFacing(ErrorCode.INVOICE_NOT_ISSUABLE,
                    "Ce devis est incomplet : " + String.join(", ", missing),
                    Map.of("reason", String.join(", ", missing)));
        }
    }

    /**
     * The new invoice draft of the conversion: {@code invoiceId}, the quote's client and lines, recomputed with the
     * current settings, linked to the quote ({@code sourceQuoteId}). Issued right after by the regular issue path.
     */
    public Invoice conversionDraft(UUID invoiceId, InvoicingSettings settings, UUID actorId, Instant now) {
        assertConvertible();
        List<LineInput> inputs = lines.stream()
                .map(l -> new LineInput(l.description(), l.quantity(), l.unitPrice(), l.treatment())).toList();
        return draft(invoiceId, client, inputs, settings, actorId, now).as(InvoiceKind.INVOICE, id);
    }

    /** DRAFT quote → CONVERTED, linked to the invoice {@code invoiceId} created from it. */
    public Invoice converted(UUID invoiceId, Instant now) {
        if (!isQuote() || status != InvoiceStatus.DRAFT) {
            assertConvertible();
        }
        return new Invoice(id, null, InvoiceStatus.CONVERTED, null, client, null, vatRegistered, vatRate,
                pricesIncludeVat, vatMention, lines, totals, null, null, null, null, null, createdBy, createdAt, now,
                delivery, sourceSaleId, supersededAt, kind, invoiceId, now, sourceQuoteId);
    }

    private DomainException quoteInvalidTransition() {
        return DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION,
                "Cette action n'est pas possible pour un devis " + status.label(kind).toLowerCase(java.util.Locale.FRENCH),
                Map.of("status", status.label(kind).toLowerCase(java.util.Locale.FRENCH)));
    }

    /** The same document as {@code newKind}, converted from {@code quoteId} ({@code null}: not a conversion). */
    private Invoice as(InvoiceKind newKind, UUID quoteId) {
        return new Invoice(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat,
                vatMention, lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason,
                createdBy, createdAt, updatedAt, delivery, sourceSaleId, supersededAt, newKind, convertedInvoiceId,
                convertedAt, quoteId);
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
        assertInvoice();
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
                null, null, null, null, createdBy, createdAt, now, delivery, sourceSaleId, supersededAt, kind, convertedInvoiceId, convertedAt, sourceQuoteId);
    }

    /**
     * Story 20.4 — refuses to record a payment received: an invoice (not a quote) {@code ISSUED} only →
     * {@code INVOICE_INVALID_TRANSITION} otherwise.
     */
    public void assertCollectable() {
        assertInvoice();
        if (status != InvoiceStatus.ISSUED) {
            throw invalidTransition();
        }
    }

    /**
     * Story 20.4 — refuses to schedule an instalment: an invoice (not a quote) {@code DRAFT} or {@code ISSUED} only →
     * {@code INVOICE_INVALID_TRANSITION} otherwise.
     */
    public void assertSchedulable() {
        assertInvoice();
        if (status != InvoiceStatus.DRAFT && status != InvoiceStatus.ISSUED) {
            throw invalidTransition();
        }
    }

    /**
     * Story 15.5 / 20.4 — when a payment made on {@code paidOn} (calendar day in Africa/Douala) is recorded at
     * {@code now}: {@code null} or today = {@code now}; an earlier day = noon of that day (a time that shows the same
     * day in any time zone of the region), or the issue time when the invoice was issued that day after noon. Refused
     * ({@code INVOICE_PAYMENT_DATE_INVALID}): a day after today, or before the issue date.
     */
    public Instant paymentInstant(LocalDate paidOn, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, KeevoZones.WAT);
        Instant paidAt = now;
        if (paidOn != null && !paidOn.equals(today)) {
            if (paidOn.isAfter(today)) {
                throw invalidPaymentDate("elle ne peut pas être dans le futur");
            }
            if (issueDate != null && paidOn.isBefore(issueDate)) {
                throw invalidPaymentDate("elle ne peut pas précéder la date d'émission de la facture");
            }
            paidAt = paidOn.atTime(12, 0).atZone(KeevoZones.WAT).toInstant();
            // issued that same day after noon: never paid before it was issued (the day stays the chosen one)
            if (issuedAt != null && paidAt.isBefore(issuedAt)
                    && LocalDate.ofInstant(issuedAt, KeevoZones.WAT).equals(paidOn)) {
                paidAt = issuedAt;
            }
        }
        return paidAt;
    }

    /**
     * Story 20.4 — ISSUED → PAID once fully paid: {@code paidAt} / {@code method} = those of the latest payment (the
     * V1 fields {@code paidAt} / {@code paymentMethod} keep their meaning).
     */
    public Invoice markedPaid(Instant paidAt, String method, Instant now) {
        assertCollectable();
        return new Invoice(id, number, InvoiceStatus.PAID, issueDate, client, seller, vatRegistered, vatRate,
                pricesIncludeVat, vatMention, lines, totals, issuedAt, paidAt, InvoicingSettings.trimToNull(method),
                null, null, createdBy, createdAt, now, delivery, sourceSaleId, supersededAt, kind, convertedInvoiceId, convertedAt, sourceQuoteId);
    }

    private static DomainException invalidPaymentDate(String reason) {
        return new DomainException(ErrorCode.INVOICE_PAYMENT_DATE_INVALID, "Invalid payment date",
                Map.of("reason", reason));
    }

    /** ISSUED → CANCELLED, number kept (an invoice without credit note). */
    public Invoice cancel(String reason, Instant now) {
        return cancel(reason, now, false);
    }

    /**
     * ISSUED → CANCELLED, number kept. Story 14.7: an invoice that already has a credit note cannot be cancelled any
     * more ({@code INVOICE_INVALID_TRANSITION}): its remaining part is credited by another credit note.
     */
    public Invoice cancel(String reason, Instant now, boolean hasCreditNotes) {
        return cancel(reason, now, hasCreditNotes, false);
    }

    /** French refusal of the cancellation of an invoice with payments received (Story 20.4). */
    public static final String PAYMENTS_RECORDED =
            "Des paiements sont enregistrés sur cette facture : établissez un avoir";

    /**
     * Story 20.4 — same, and an ISSUED invoice with at least one payment received ({@code hasPayments}) cannot be
     * cancelled ({@code INVOICE_INVALID_TRANSITION} « Des paiements sont enregistrés sur cette facture : établissez un
     * avoir »).
     */
    public Invoice cancel(String reason, Instant now, boolean hasCreditNotes, boolean hasPayments) {
        if (isQuote()) {
            return cancelQuote(reason, now);
        }
        if (status != InvoiceStatus.ISSUED) {
            throw invalidTransition();
        }
        if (hasCreditNotes) {
            throw new DomainException(ErrorCode.INVOICE_INVALID_TRANSITION, "Invoice has credit notes",
                    Map.of("status", "ayant déjà un avoir"));
        }
        if (hasPayments) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION, PAYMENTS_RECORDED,
                    Map.of("status", "ayant des paiements"));
        }
        String why = requireCancellationReason(reason);
        return new Invoice(id, number, InvoiceStatus.CANCELLED, issueDate, client, seller, vatRegistered, vatRate,
                pricesIncludeVat, vatMention, lines, totals, issuedAt, null, null, now, why, createdBy, createdAt,
                now, delivery, sourceSaleId, supersededAt, kind, convertedInvoiceId, convertedAt, sourceQuoteId);
    }

    /** Story 20.3 — DRAFT quote → CANCELLED (reason required, no number ever). */
    private Invoice cancelQuote(String reason, Instant now) {
        if (status != InvoiceStatus.DRAFT) {
            throw quoteInvalidTransition();
        }
        String why = requireCancellationReason(reason);
        return new Invoice(id, null, InvoiceStatus.CANCELLED, null, client, null, vatRegistered, vatRate,
                pricesIncludeVat, vatMention, lines, totals, null, null, null, now, why, createdBy, createdAt, now,
                delivery, sourceSaleId, supersededAt, kind, convertedInvoiceId, convertedAt, sourceQuoteId);
    }

    private static String requireCancellationReason(String reason) {
        String why = InvoicingSettings.trimToNull(reason);
        if (why == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "A cancellation reason is required",
                    Map.of("reason", "Le motif d'annulation est requis"));
        }
        return why;
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
                createdBy, createdAt, now, delivery, sourceSaleId, supersededAt, kind, convertedInvoiceId, convertedAt, sourceQuoteId);
    }

    /**
     * Story 14.4 — the draft created automatically for the completed sale {@code saleId} (POS → Invoicing bridge):
     * the sale is the invoice's idempotency key (one invoice per sale).
     */
    public Invoice fromSale(UUID saleId) {
        assertEditable();
        return new Invoice(id, number, status, issueDate, client, seller, vatRegistered, vatRate, pricesIncludeVat,
                vatMention, lines, totals, issuedAt, paidAt, paymentMethod, cancelledAt, cancellationReason,
                createdBy, createdAt, updatedAt, delivery, saleId, supersededAt, kind, convertedInvoiceId, convertedAt, sourceQuoteId);
    }

    /** Story 14.2 — the issued invoice with the seller identity flags frozen at issue. */
    public Invoice withSellerIdentity(boolean nameFromDisplayName, boolean hasLogo) {
        if (seller == null) {
            return this;
        }
        return new Invoice(id, number, status, issueDate, client, seller.withIdentityFlags(nameFromDisplayName,
                hasLogo), vatRegistered, vatRate, pricesIncludeVat, vatMention, lines, totals, issuedAt, paidAt,
                paymentMethod, cancelledAt, cancellationReason, createdBy, createdAt, updatedAt, delivery, sourceSaleId, supersededAt, kind, convertedInvoiceId, convertedAt, sourceQuoteId);
    }

    /**
     * Story 14.2 — only an issued or paid invoice can be sent (and re-sent) to the client: a draft has no legal value,
     * a cancelled invoice must not reach the client as if it were due → {@code INVOICE_INVALID_TRANSITION}.
     */
    public void assertSendable() {
        assertInvoice();
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
                createdBy, createdAt, updatedAt, delivery.sentTo(phone, now), sourceSaleId, supersededAt, kind, convertedInvoiceId, convertedAt, sourceQuoteId);
    }

    private DomainException invalidTransition() {
        if (isQuote()) {
            return quoteInvalidTransition();
        }
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
