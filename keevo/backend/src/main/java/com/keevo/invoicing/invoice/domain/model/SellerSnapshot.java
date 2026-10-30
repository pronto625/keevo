package com.keevo.invoicing.invoice.domain.model;

/** The seller identity copied onto an invoice when it is issued (Story 14.1, CGI art. 150 (5)). */
public record SellerSnapshot(
        String legalName,
        String address,
        String phone,
        String niu,
        String rccm,
        String paymentDetails,
        String footer) {

    public static SellerSnapshot of(InvoicingSettings settings) {
        return new SellerSnapshot(settings.legalName(), settings.address(), settings.phone(), settings.niu(),
                settings.rccm(), settings.paymentDetails(), settings.footer());
    }
}
