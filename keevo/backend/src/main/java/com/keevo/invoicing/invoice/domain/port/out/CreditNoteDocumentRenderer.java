package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.CreditNote;
import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;

/** Renders a credit note (« avoir ») as an A4 PDF in French (Story 14.7). */
public interface CreditNoteDocumentRenderer {

    /**
     * @param creditNote the credit note
     * @param invoice    the invoice it credits (seller snapshot, client, number and date printed)
     * @param logo       the company logo, {@code null} for none
     * @return the PDF bytes
     */
    byte[] render(CreditNote creditNote, Invoice invoice, Logo logo);
}
