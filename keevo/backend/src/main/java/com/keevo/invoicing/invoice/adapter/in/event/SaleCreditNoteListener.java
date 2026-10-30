package com.keevo.invoicing.invoice.adapter.in.event;

import com.keevo.invoicing.invoice.application.usecase.InvoiceSaleUseCase;
import com.keevo.invoicing.invoice.application.usecase.SaleCreditNoteUseCase;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.event.catalog.sale.SaleCancelledEvent;
import com.keevo.shared.event.catalog.sale.SaleCorrectedEvent;
import com.keevo.shared.module.EntitlementService;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleScoped;
import com.keevo.shared.module.ModuleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Story 14.7 — the accounting follow-up of a cancelled or corrected sale that was invoiced automatically (POS →
 * Invoicing bridge): a credit note, and for a correction a new invoice, issued by {@link SaleCreditNoteUseCase}.
 *
 * <p>Same gates and delivery model as {@link SaleInvoicingListener} (14.4): durable module listener (async, after the
 * sale's commit, publication registry, tenant bound from the event); acts only while {@code invoicing} <b>and</b>
 * {@code pos} are ENABLED (READ_ONLY / DISABLED → INFO, nothing); the module's tables are provisioned before any
 * write; no outer transaction ({@code NOT_SUPPORTED}) so a delivery holds one connection. Idempotent through data (the
 * credit note's sale-event key); a concurrent replay that loses the race on that key is treated as done; any other
 * failure propagates and the publication is retried. A correction event published before 14.7 carries no lines → WARN,
 * nothing done.
 */
@Component
@ModuleScoped(InvoicingModule.ID)
public class SaleCreditNoteListener {

    private static final Logger log = LoggerFactory.getLogger(SaleCreditNoteListener.class);

    private final EntitlementService entitlements;
    private final ModuleProvisioner provisioner;
    private final SaleCreditNoteUseCase saleCreditNote;

    public SaleCreditNoteListener(EntitlementService entitlements, ModuleProvisioner provisioner,
                                  SaleCreditNoteUseCase saleCreditNote) {
        this.entitlements = entitlements;
        this.provisioner = provisioner;
        this.saleCreditNote = saleCreditNote;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onSaleCancelled(SaleCancelledEvent event) {
        String tenant = event.tenantId();
        if (!active(tenant)) {
            return;
        }
        provisioner.ensureProvisioned(tenant, InvoicingModule.ID);
        run(tenant, "cancelled", () -> saleCreditNote.cancelled(event),
                () -> saleCreditNote.isDone(SaleCreditNoteUseCase.cancelKey(event.saleId())));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onSaleCorrected(SaleCorrectedEvent event) {
        String tenant = event.tenantId();
        if (!active(tenant)) {
            return;
        }
        if (event.lines() == null || event.lines().isEmpty() || event.totalAmount() == null
                || event.occurredAt() == null) {
            log.warn("[invoicing] corrected sale of tenant {} not re-invoiced: event without lines "
                    + "(published before 14.7)", tenant);
            return;
        }
        provisioner.ensureProvisioned(tenant, InvoicingModule.ID);
        try {
            run(tenant, "corrected", () -> saleCreditNote.corrected(event),
                    () -> saleCreditNote.isDone(SaleCreditNoteUseCase.correctionKey(event)));
        } catch (InvoiceSaleUseCase.SaleInvoiceRefused e) {
            // permanent: the corrected content is refused by the invoice domain; everything was rolled back
            log.warn("[invoicing] corrected sale of tenant {} not re-invoiced: {}", tenant, e.getMessage());
        }
    }

    private void run(String tenant, String what, Supplier<SaleCreditNoteUseCase.Outcome> action,
                     BooleanSupplier done) {
        try {
            SaleCreditNoteUseCase.Outcome outcome = action.get();
            log.debug("[invoicing] {} sale of tenant {}: {}", what, tenant, outcome);
        } catch (DuplicateKeyException e) {
            if (!done.getAsBoolean()) {
                throw e;
            }
            log.info("[invoicing] {} sale of tenant {} already handled by a concurrent delivery", what, tenant);
        }
    }

    /** Invoicing and POS both ENABLED for the tenant (READ_ONLY / DISABLED → INFO, nothing done). */
    private boolean active(String tenant) {
        ModuleState state = entitlements.state(tenant, InvoicingModule.ID);
        if (state != ModuleState.ENABLED) {
            log.info("[invoicing] no automatic credit note for tenant {}: module {}", tenant, state);
            return false;
        }
        ModuleState pos = entitlements.state(tenant, SaleInvoicingListener.POS);
        if (pos != ModuleState.ENABLED) {
            log.info("[invoicing] no automatic credit note for tenant {}: pos module {}", tenant, pos);
            return false;
        }
        return true;
    }
}
