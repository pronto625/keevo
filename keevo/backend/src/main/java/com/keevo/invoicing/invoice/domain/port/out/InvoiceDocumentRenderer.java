package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;

/** Renders an invoice as an A4 PDF in French (Story 14.2). */
public interface InvoiceDocumentRenderer {

    /**
     * @param invoice the invoice (any status: the PDF carries a stamp for a draft, a paid or a cancelled invoice)
     * @param seller  the seller identity printed: the snapshot of an issued invoice, the current settings of a draft
     * @param logo    the company logo, {@code null} for none
     * @return the PDF bytes
     */
    byte[] render(Invoice invoice, SellerSnapshot seller, Logo logo);
}
