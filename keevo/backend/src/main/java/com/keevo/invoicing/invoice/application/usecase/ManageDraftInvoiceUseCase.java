package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceKind;
import com.keevo.invoicing.invoice.domain.model.InvoiceTerms;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.port.out.InvoicePaymentRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.shared.domain.XafAmounts;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.InvoiceCreatedEvent;
import com.keevo.shared.event.catalog.invoicing.QuoteCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Creates, edits and deletes draft invoices (Story 14.1): free client and free lines, computed with the seller's
 * current settings on every change. Only a {@code DRAFT} can be changed or deleted ({@code INVOICE_NOT_EDITABLE}).
 *
 * <p>Story 14.3: the client goes through {@link InvoiceClientResolver} first (CRM link and prefill when {@code crm}
 * is on, free text otherwise), before anything is written.
 *
 * <p>Story 20.3: a quote ({@code kind QUOTE}) is created, edited and deleted here the same way (no number, no quota);
 * an edit may carry an optimistic check ({@code expectedUpdatedAt}).
 */
@Service
public class ManageDraftInvoiceUseCase {

    private final InvoiceRepository invoices;
    private final InvoicingSettingsRepository settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final InvoiceClientResolver clients;
    private final InvoicePaymentRepository payments;

    private final InvoicePresentationUseCase presentations;

    public ManageDraftInvoiceUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                                     ApplicationEventPublisher events, Clock clock, InvoiceClientResolver clients,
                                     InvoicePaymentRepository payments) {
        this(invoices, settings, events, clock, clients, payments, null);
    }

    /** Story 20.7 — {@code presentations}: a deleted draft's signature files are removed after the commit. */
    @org.springframework.beans.factory.annotation.Autowired
    public ManageDraftInvoiceUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                                     ApplicationEventPublisher events, Clock clock, InvoiceClientResolver clients,
                                     InvoicePaymentRepository payments, InvoicePresentationUseCase presentations) {
        this.presentations = presentations;
        this.payments = payments;
        this.invoices = invoices;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
        this.clients = clients;
    }

    /**
     * The draft's content: client and lines; Story 20.5 — {@code terms} (discount, delivery, own VAT rate or
     * exemption, due date; {@code null} = none: a V1 body). Story 20.3: {@code kind} = what a creation makes ({@code null} =
     * {@code INVOICE}); on an edit, {@code null} = unchanged and another kind than the document's → 409; {@code expectedUpdatedAt} = the edit's
     * optimistic check (the {@code updatedAt} the editor last saw; {@code null} = no check; ignored by a creation).
     */
    public record DraftContent(ClientInfo client, List<LineInput> lines, InvoiceKind kind, Instant expectedUpdatedAt,
                               InvoiceTerms terms) {

        public DraftContent {
            terms = terms == null ? InvoiceTerms.NONE : terms;
        }

        /** Pre-20.5 shape: no document terms (no discount, no delivery, the seller's rate, no due date). */
        public DraftContent(ClientInfo client, List<LineInput> lines, InvoiceKind kind, Instant expectedUpdatedAt) {
            this(client, lines, kind, expectedUpdatedAt, InvoiceTerms.NONE);
        }

        public DraftContent(ClientInfo client, List<LineInput> lines) {
            this(client, lines, null, null);
        }
    }

    @Transactional
    public Invoice create(DraftContent content, UUID actorId, String tenantId) {
        return create(null, content, actorId, tenantId);
    }

    /**
     * Story 15.5b — a new draft with the id chosen by the device ({@code null} = a new random id). Idempotent on that
     * id: an invoice that already exists with it is returned as it is (whatever its status — a re-sent creation never
     * makes a second invoice nor touches the first one).
     */
    @Transactional
    public Invoice create(UUID id, DraftContent content, UUID actorId, String tenantId) {
        if (id != null) {
            // a deleted draft too: a re-sent creation never resurrects it
            java.util.Optional<Invoice> existing = invoices.findAnyById(id);
            if (existing.isPresent()) {
                assertSameKind(existing.get(), requestedKind(content));
                return existing.get();
            }
        }
        assertDueDateNotPast(content);
        ClientInfo client = clients.resolve(content.client(), tenantId);
        Instant now = now();
        UUID invoiceId = id != null ? id : UUID.randomUUID();
        boolean quote = content.kind() == InvoiceKind.QUOTE;
        Invoice draft = valid(() -> quote
                ? Invoice.quote(invoiceId, client, content.lines(), content.terms(), currentSettings(), actorId, now)
                : Invoice.draft(invoiceId, client, content.lines(), content.terms(), currentSettings(), actorId,
                now));
        if (id != null) {
            // the same creation sent concurrently (online retry + queued operation): the first one wins, no PK error
            if (!invoices.insertIfAbsent(draft)) {
                Invoice winner = invoices.findAnyById(id).orElseThrow(InvoiceQueryUseCase::notFound);
                assertSameKind(winner, requestedKind(content));
                return winner;
            }
        } else {
            invoices.insert(draft);
        }
        if (quote) {
            events.publishEvent(new QuoteCreatedEvent(draft.id(), draft.quoteReference(), draft.client().name(),
                    draft.totals().totalTtc(), actorId, tenantId, now));
        } else {
            events.publishEvent(new InvoiceCreatedEvent(draft.id(), draft.client().name(), draft.totals().totalTtc(),
                    actorId, tenantId, now));
        }
        return draft;
    }

    /**
     * Replaces the content of a draft invoice or a quote. Story 20.3: with {@code content.expectedUpdatedAt()}, a row
     * changed since (another device, another user) → 409 {@code OPTIMISTIC_LOCK}, nothing written; compared on the
     * locked row at the database precision (microseconds), no extra query.
     */
    @Transactional
    public Invoice update(UUID id, DraftContent content, String tenantId) {
        Invoice current = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        current.assertEditable();
        assertSameKind(current, content.kind());
        assertFresh(current, content.expectedUpdatedAt());
        assertDueDateNotPast(content);
        ClientInfo client = clients.resolve(content.client(), tenantId, current.client().clientId());
        Invoice updated = valid(() -> current.withDraftContent(client, content.lines(), content.terms(),
                currentSettings(), now()));
        assertInstalmentsFit(updated);
        invoices.updateDraft(updated);
        return updated;
    }

    /**
     * Story 20.4 — the instalments already scheduled on a draft must fit its new total: otherwise 409
     * {@code OVERPAYMENT}, nothing written.
     */
    private void assertInstalmentsFit(Invoice updated) {
        if (updated.isQuote()) {
            return;
        }
        long committed = PaymentStateCalculator.committed(payments.findByInvoice(updated.id()));
        if (committed > updated.totals().totalTtc()) {
            throw DomainException.userFacing(ErrorCode.OVERPAYMENT, "Les échéances programmées ("
                    + XafAmounts.group(committed) + " FCFA) dépassent le nouveau total de la facture ("
                    + XafAmounts.group(updated.totals().totalTtc()) + " FCFA) : réduisez-les d'abord",
                    Map.of("remaining", XafAmounts.group(updated.totals().totalTtc())));
        }
    }

    /** Story 20.5 — a draft or a quote never gets a due date already passed (today in Africa/Douala is fine). */
    private void assertDueDateNotPast(DraftContent content) {
        java.time.LocalDate due = content.terms().dueDate();
        if (due != null && due.isBefore(java.time.LocalDate.ofInstant(clock.instant(),
                com.keevo.shared.domain.time.KeevoZones.WAT))) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Due date in the past",
                    Map.of("reason", "La date d'échéance ne peut pas être passée"));
        }
    }

    @Transactional
    public void delete(UUID id) {
        Invoice current = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        current.assertEditable();
        if (presentations != null) {
            presentations.discardSignatures(id, com.keevo.shared.infrastructure.persistence.TenantContext
                    .getCurrentTenant());
        }
        invoices.deleteDraft(id, clock.instant());
    }

    /** A creation names no kind: an invoice. */
    private static InvoiceKind requestedKind(DraftContent content) {
        return content.kind() == null ? InvoiceKind.INVOICE : content.kind();
    }

    /** Story 20.3 — the French message of a stale edit or conversion ({@code OPTIMISTIC_LOCK}). */
    public static final String STALE_DOCUMENT =
            "Ce document a été modifié entre-temps : rechargez-le avant de l'enregistrer.";

    /**
     * Story 20.3 — a request naming a kind ({@code null} = none named) for a document of another kind → 409
     * {@code INVOICE_INVALID_TRANSITION}: an invoice never becomes a quote, nor the reverse.
     */
    static void assertSameKind(Invoice existing, InvoiceKind requested) {
        if (requested != null && existing.kind() != requested) {
            throw DomainException.userFacing(ErrorCode.INVOICE_INVALID_TRANSITION, existing.isQuote()
                            ? "Ce document est un devis : il ne peut pas devenir une facture (convertissez-le)"
                            : "Ce document est une facture : il ne peut pas devenir un devis",
                    Map.of("status", existing.kind().label().toLowerCase(java.util.Locale.FRENCH)));
        }
    }

    /** Story 20.3 — the optimistic edit check ({@code expected} {@code null} = none). */
    static void assertFresh(Invoice current, Instant expected) {
        if (expected == null) {
            return;
        }
        Instant stored = current.updatedAt();
        if (stored == null || !stored.truncatedTo(ChronoUnit.MICROS).equals(expected.truncatedTo(ChronoUnit.MICROS))) {
            throw DomainException.userFacing(ErrorCode.OPTIMISTIC_LOCK, STALE_DOCUMENT,
                    Map.of("currentUpdatedAt", stored == null ? "" : stored.toString()));
        }
    }

    /**
     * Now at the database precision (microseconds): the {@code updatedAt} a response returns is exactly the stored
     * one, so it can be sent back as {@code expectedUpdatedAt}.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private InvoicingSettings currentSettings() {
        return settings.find().orElseGet(() -> InvoicingSettings.defaults(settings.countryVatRate()));
    }

    /** Domain value errors (amount overflow, bad quantity) → 422 with a French reason. */
    static <T> T valid(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Invalid invoice content",
                    Map.of("reason", "Une ligne de la facture est invalide (quantité ou montant hors limites)"));
        }
    }
}
