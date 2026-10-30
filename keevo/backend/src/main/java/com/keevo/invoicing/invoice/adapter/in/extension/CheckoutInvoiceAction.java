package com.keevo.invoicing.invoice.adapter.in.extension;

import com.keevo.invoicing.module.InvoicingModule;
import com.keevo.shared.module.ContributesExtension;
import com.keevo.shared.module.Extension;
import org.springframework.stereotype.Component;

/**
 * Story 14.5 (MOD-FR10, UX-DR3) — « Facturer » in the POS checkout slot {@code pos.checkout.post-sale-actions}
 * (declared by pos, listed in the invoicing manifest {@code extensionPoints.fills}). Listed only while pos is readable
 * and invoicing ENABLED for the tenant, and only to users holding {@code INVOICE_VIEW}.
 *
 * <p>The app maps {@code invoicing.open-sale-invoice} to opening the sale's invoice, found through
 * {@code GET /api/v1/invoices?saleId=}: since 14.4 the invoice is created by the POS → Invoicing bridge; an empty
 * result means it is not created yet (« Facture en cours de création »).
 */
@Component
@ContributesExtension(module = InvoicingModule.ID, point = CheckoutInvoiceAction.POINT)
public class CheckoutInvoiceAction implements Extension {

    public static final String POINT = "pos.checkout.post-sale-actions";
    public static final String ID = "invoicing.invoice-sale";
    public static final String ACTION = "invoicing.open-sale-invoice";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Facturer";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String action() {
        return ACTION;
    }

    @Override
    public String permission() {
        return InvoicingModule.INVOICE_VIEW;
    }
}
