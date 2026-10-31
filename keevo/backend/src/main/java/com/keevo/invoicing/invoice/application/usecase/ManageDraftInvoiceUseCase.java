package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.event.catalog.invoicing.InvoiceCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
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
 */
@Service
public class ManageDraftInvoiceUseCase {

    private final InvoiceRepository invoices;
    private final InvoicingSettingsRepository settings;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final InvoiceClientResolver clients;

    public ManageDraftInvoiceUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                                     ApplicationEventPublisher events, Clock clock, InvoiceClientResolver clients) {
        this.invoices = invoices;
        this.settings = settings;
        this.events = events;
        this.clock = clock;
        this.clients = clients;
    }

    /** The draft's content: client and lines. */
    public record DraftContent(ClientInfo client, List<LineInput> lines) {
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
                return existing.get();
            }
        }
        ClientInfo client = clients.resolve(content.client(), tenantId);
        Instant now = clock.instant();
        UUID invoiceId = id != null ? id : UUID.randomUUID();
        Invoice draft = valid(() -> Invoice.draft(invoiceId, client, content.lines(),
                currentSettings(), actorId, now));
        if (id != null) {
            // the same creation sent concurrently (online retry + queued operation): the first one wins, no PK error
            if (!invoices.insertIfAbsent(draft)) {
                return invoices.findAnyById(id).orElseThrow(InvoiceQueryUseCase::notFound);
            }
        } else {
            invoices.insert(draft);
        }
        events.publishEvent(new InvoiceCreatedEvent(draft.id(), draft.client().name(), draft.totals().totalTtc(),
                actorId, tenantId, now));
        return draft;
    }

    @Transactional
    public Invoice update(UUID id, DraftContent content, String tenantId) {
        Invoice current = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        current.assertEditable();
        ClientInfo client = clients.resolve(content.client(), tenantId, current.client().clientId());
        Invoice updated = valid(() -> current.withDraftContent(client, content.lines(), currentSettings(),
                clock.instant()));
        invoices.updateDraft(updated);
        return updated;
    }

    @Transactional
    public void delete(UUID id) {
        Invoice current = invoices.findByIdForUpdate(id).orElseThrow(InvoiceQueryUseCase::notFound);
        current.assertEditable();
        invoices.deleteDraft(id, clock.instant());
    }

    private InvoicingSettings currentSettings() {
        return settings.find().orElseGet(InvoicingSettings::defaults);
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
