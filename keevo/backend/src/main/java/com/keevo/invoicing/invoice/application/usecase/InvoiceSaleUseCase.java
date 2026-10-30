package com.keevo.invoicing.invoice.application.usecase;

import com.keevo.invoicing.invoice.domain.model.ClientInfo;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoicingSettings;
import com.keevo.invoicing.invoice.domain.model.LineInput;
import com.keevo.invoicing.invoice.domain.port.out.InvoiceRepository;
import com.keevo.invoicing.invoice.domain.port.out.InvoicingSettingsRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.event.catalog.invoicing.InvoiceCreatedEvent;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 14.4 (MOD-FR8) — the automatic invoice of a completed sale (POS → Invoicing bridge): in <b>one</b>
 * transaction, the draft is created from the sale ({@link SaleInvoiceMapper}), issued (next number of the gapless
 * sequence) and paid — with the same domain rules, numbering and events (created, issued, paid) as the manual path,
 * under the sale's actor.
 *
 * <p>Idempotent on the sale id ({@code invoices.source_sale_id}, unique): an invoice already created for the sale →
 * nothing is done. A concurrent replay that loses the race fails on the unique index while inserting the draft —
 * before the counter is touched — and its transaction rolls back entirely (no number consumed); the caller treats
 * that failure as « already done » ({@link #isInvoiced}).
 *
 * <p>Prices are always VAT included for an automatic invoice (they are what the client paid), whatever the seller
 * setting, so the invoice total equals the sale total. Runs in its own transaction ({@code REQUIRES_NEW}) so a
 * duplicate-key failure never poisons the caller's.
 */
@Service
public class InvoiceSaleUseCase {

    /** What happened to the sale. */
    public enum Outcome {
        /** The invoice was created, issued and paid. */
        INVOICED,
        /** An invoice already exists for the sale (replay): nothing done. */
        ALREADY_INVOICED,
        /** The sale cannot be invoiced (no line, totals inconsistent with its lines, content refused): nothing done. */
        NOT_INVOICEABLE
    }

    private static final Logger log = LoggerFactory.getLogger(InvoiceSaleUseCase.class);

    private final InvoiceRepository invoices;
    private final InvoicingSettingsRepository settings;
    private final InvoiceLifecycleUseCase lifecycle;
    private final InvoiceClientResolver clients;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public InvoiceSaleUseCase(InvoiceRepository invoices, InvoicingSettingsRepository settings,
                              InvoiceLifecycleUseCase lifecycle, InvoiceClientResolver clients,
                              ApplicationEventPublisher events, Clock clock) {
        this.invoices = invoices;
        this.settings = settings;
        this.lifecycle = lifecycle;
        this.clients = clients;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome invoice(SaleCompletedEvent sale) {
        UUID saleId = sale.getSaleId();
        String tenant = sale.tenantId();
        if (invoices.existsBySourceSale(saleId)) {
            return Outcome.ALREADY_INVOICED;
        }
        List<LineInput> lines;
        try {
            lines = SaleInvoiceMapper.lines(sale.getLines(), sale.getTotalAmount());
        } catch (IllegalArgumentException | ArithmeticException e) {
            log.warn("[invoicing] sale of tenant {} not invoiced: {}", tenant, e.getMessage());
            return Outcome.NOT_INVOICEABLE;
        }
        ClientInfo client = SaleInvoiceMapper.client(clients.saleClient(sale.getClientId(), tenant));
        UUID actor = sale.getActorId();
        Instant now = clock.instant();
        InvoicingSettings vatIncluded = InvoiceLifecycleUseCase.withPricesIncludingVat(
                settings.find().orElseGet(InvoicingSettings::defaults));
        Invoice draft;
        try {
            draft = ManageDraftInvoiceUseCase.valid(() -> Invoice.draft(UUID.randomUUID(), client, lines,
                    vatIncluded, actor, now)).fromSale(saleId);
        } catch (DomainException e) {
            // content refused by the domain after the mapper accepted it: retrying would fail for ever
            log.warn("[invoicing] sale of tenant {} not invoiced: invoice content refused ({})", tenant,
                    e.getMessage());
            return Outcome.NOT_INVOICEABLE;
        }
        invoices.insert(draft); // unique source_sale_id: a concurrent replay fails here, before any number
        events.publishEvent(new InvoiceCreatedEvent(draft.id(), draft.client().name(), draft.totals().totalTtc(),
                actor, tenant, now));
        lifecycle.issue(draft.id(), actor, tenant, true);
        lifecycle.pay(draft.id(), SaleInvoiceMapper.paymentMethod(sale.getPaymentMode()), actor, tenant);
        return Outcome.INVOICED;
    }

    /** Whether an invoice exists for the sale (read outside any failed transaction, after a duplicate key). */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isInvoiced(UUID saleId) {
        return invoices.existsBySourceSale(saleId);
    }
}
