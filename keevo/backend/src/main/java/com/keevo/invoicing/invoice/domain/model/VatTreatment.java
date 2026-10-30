package com.keevo.invoicing.invoice.domain.model;

/**
 * VAT treatment of an invoice line (Story 14.1, CGI art. 150 (5)): {@code STANDARD} = the seller's rate (0 % when the
 * seller is not VAT-registered); {@code EXEMPT} = « Exonérée », 0 %; {@code STATE_BORNE} = « Prise en charge État »,
 * 0 % (VAT borne by the State).
 */
public enum VatTreatment {
    STANDARD(null),
    EXEMPT("Exonérée"),
    STATE_BORNE("Prise en charge État");

    private final String mention;

    VatTreatment(String mention) {
        this.mention = mention;
    }

    /** The mandatory mention printed on the line, {@code null} for the standard rate. */
    public String mention() {
        return mention;
    }
}
