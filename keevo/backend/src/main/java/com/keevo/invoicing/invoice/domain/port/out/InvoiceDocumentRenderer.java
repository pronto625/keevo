package com.keevo.invoicing.invoice.domain.port.out;

import com.keevo.invoicing.invoice.domain.model.Invoice;
import com.keevo.invoicing.invoice.domain.model.InvoiceTemplate;
import com.keevo.invoicing.invoice.domain.model.PaymentStateCalculator;
import com.keevo.invoicing.invoice.domain.model.SellerSnapshot;
import com.keevo.invoicing.invoice.domain.model.TicketWidth;
import com.keevo.shared.capability.business.CompanyIdentity.Logo;

/** Renders an invoice or a quote as a PDF in French (Story 14.2; Story 20.7: 8 Bref layouts, payments, signatures). */
public interface InvoiceDocumentRenderer {

    /**
     * Everything printed on a document.
     *
     * @param invoice          the document (any status: a draft, a paid or a cancelled invoice carries its mention)
     * @param seller           the seller identity printed: the snapshot of an issued invoice, the current settings of a
     *                         draft
     * @param logo             the company logo, {@code null} for none
     * @param template         Story 20.7 — the layout, {@code null} = the Keevo standard A4 invoice
     * @param ticketWidth      the paper of a narrow layout ({@code null} = 80 mm); ignored by the A4 layouts
     * @param collection       Story 20.4 / 20.7 — amount paid, amount due, payments and instalments; {@code null} = none
     * @param clientSignature  {@code data:} URI of the client's signature, {@code null} = unsigned
     * @param sellerSignature  {@code data:} URI of the seller's signature, {@code null} = unsigned
     * @param legacyReference  the Bref reference ({@code BRF-XXXXXXXX}) of an imported invoice, printed next to the
     *                         number; {@code null} otherwise
     */
    record RenderRequest(Invoice invoice, SellerSnapshot seller, Logo logo, InvoiceTemplate template,
                         TicketWidth ticketWidth, PaymentStateCalculator.Collection collection,
                         String clientSignature, String sellerSignature, String legacyReference) {

        /** The 14.2 shape: Keevo layout, no payment block, unsigned. */
        public static RenderRequest standard(Invoice invoice, SellerSnapshot seller, Logo logo) {
            return new RenderRequest(invoice, seller, logo, null, null, null, null, null, null);
        }
    }

    /** @return the PDF bytes */
    byte[] render(RenderRequest request);

    /** Story 14.2 shape: the Keevo standard layout, without payment block nor signature. */
    default byte[] render(Invoice invoice, SellerSnapshot seller, Logo logo) {
        return render(RenderRequest.standard(invoice, seller, logo));
    }
}
