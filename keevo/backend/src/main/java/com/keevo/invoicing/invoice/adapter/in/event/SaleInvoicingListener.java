package com.keevo.invoicing.invoice.adapter.in.event;

import com.keevo.invoicing.invoice.application.usecase.InvoiceSaleUseCase;
import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.event.catalog.sale.SaleCompletedEvent;
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

/**
 * Story 14.4 (MOD-FR8) — the POS → Invoicing bridge: each completed sale ({@code sale.completed}, shared event
 * catalogue — invoicing never reads commerce) gets its invoice, created, issued and paid automatically by
 * {@link InvoiceSaleUseCase}.
 *
 * <p>Durable module listener (async, after the sale's commit, publication registry; the tenant is bound from the
 * event): the sale itself is never affected. Delivered only when {@code invoicing} is <b>ENABLED</b> for the tenant —
 * {@link ModuleScoped} skips a DISABLED module (FREE plan included), a READ_ONLY one is skipped here (INFO): no
 * invoice and no table created. POS must be ENABLED too: once it is turned off, a stale or retried event (or a
 * cascade validation) creates nothing. Events published before 14.4 carry no lines → WARN, nothing done. The module's
 * tables are provisioned (lazily) before any write.
 *
 * <p>Idempotent through data, not {@code @DeliverOnce}: a replay (restart republish, resubmission of an incomplete
 * publication, sync re-push) finds the invoice of the sale and does nothing; a concurrent replay that loses the race
 * on the unique sale key is treated as done. Any other failure propagates: the publication stays incomplete and is
 * retried.
 */
@Component
@ModuleScoped(InvoicingModule.ID)
public class SaleInvoicingListener {

    private static final Logger log = LoggerFactory.getLogger(SaleInvoicingListener.class);

    private final EntitlementService entitlements;
    private final ModuleProvisioner provisioner;
    private final InvoiceSaleUseCase invoiceSale;

    public SaleInvoicingListener(EntitlementService entitlements, ModuleProvisioner provisioner,
                                 InvoiceSaleUseCase invoiceSale) {
        this.entitlements = entitlements;
        this.provisioner = provisioner;
        this.invoiceSale = invoiceSale;
    }

    /** The {@code pos} module id (the bridge acts only while POS is ENABLED too). */
    static final String POS = "pos";

    /**
     * No outer transaction ({@code NOT_SUPPORTED}): the use case runs in its own, so a delivery holds one connection at
     * a time — a burst of deliveries (sync push of many sales) cannot exhaust the pool.
     */
    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    public void onSaleCompleted(SaleCompletedEvent event) {
        String tenant = event.tenantId();
        ModuleState state = entitlements.state(tenant, InvoicingModule.ID);
        if (state != ModuleState.ENABLED) {
            log.info("[invoicing] no automatic invoice for tenant {}: module {}", tenant, state);
            return;
        }
        ModuleState pos = entitlements.state(tenant, POS);
        if (pos != ModuleState.ENABLED) {
            // a stale / retried event or a cascade validation after POS was turned off
            log.info("[invoicing] no automatic invoice for tenant {}: pos module {}", tenant, pos);
            return;
        }
        if (event.getLines() == null || event.getLines().isEmpty()) {
            log.warn("[invoicing] sale of tenant {} not invoiced: event without lines (published before 14.4)",
                    tenant);
            return;
        }
        provisioner.ensureProvisioned(tenant, InvoicingModule.ID);
        try {
            InvoiceSaleUseCase.Outcome outcome = invoiceSale.invoice(event);
            log.debug("[invoicing] sale of tenant {}: {}", tenant, outcome);
        } catch (DuplicateKeyException e) {
            if (!invoiceSale.isInvoiced(event.getSaleId())) {
                throw e;
            }
            log.info("[invoicing] sale of tenant {} already invoiced by a concurrent delivery", tenant);
        }
    }
}
