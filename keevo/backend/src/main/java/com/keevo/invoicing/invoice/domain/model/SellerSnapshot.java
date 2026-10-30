package com.keevo.invoicing.invoice.domain.model;

/**
 * The seller identity copied onto an invoice when it is issued (Story 14.1, CGI art. 150 (5)).
 *
 * <p>Story 14.2: {@code nameFromDisplayName} — no legal name was typed in the invoicing settings, so the company
 * display name (designation › shop name) was printed instead (a compliance warning is shown); {@code hasLogo} — a
 * company logo existed at issue (the PDF shows the current logo; a logo is not a legal mention).
 */
public record SellerSnapshot(
        String legalName,
        String address,
        String phone,
        String niu,
        String rccm,
        String paymentDetails,
        String footer,
        boolean nameFromDisplayName,
        boolean hasLogo) {

    public SellerSnapshot(String legalName, String address, String phone, String niu, String rccm,
                          String paymentDetails, String footer) {
        this(legalName, address, phone, niu, rccm, paymentDetails, footer, false, false);
    }

    public static SellerSnapshot of(InvoicingSettings settings) {
        return new SellerSnapshot(settings.legalName(), settings.address(), settings.phone(), settings.niu(),
                settings.rccm(), settings.paymentDetails(), settings.footer());
    }

    /** The same identity with the Story 14.2 flags. */
    public SellerSnapshot withIdentityFlags(boolean fromDisplayName, boolean logo) {
        return new SellerSnapshot(legalName, address, phone, niu, rccm, paymentDetails, footer, fromDisplayName, logo);
    }
}
