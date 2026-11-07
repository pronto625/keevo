package com.keevo.invoicing.invoice.domain.model;

import java.math.BigDecimal;

/**
 * The seller profile of the tenant (Story 14.1), snapshotted onto each invoice when it is issued: later edits never
 * change an issued invoice. {@code vatRate} is a percentage ({@code 19.25} = 19,25 %).
 *
 * @param vatRegistered    whether the seller collects VAT (else « TVA non applicable », every line at 0 %)
 * @param pricesIncludeVat whether line unit prices are entered VAT included (TTC) or excluded (HT)
 * @param deriveFromDocuments Story 20.6 — whether issuing an invoice records its client and products in the CRM and
 *                         the catalogue; {@code null} = not chosen (resolved from the tenant's sale flow: on for
 *                         {@code DOCUMENT}, off otherwise)
 */
public record InvoicingSettings(
        String legalName,
        String address,
        String phone,
        String niu,
        String rccm,
        boolean vatRegistered,
        BigDecimal vatRate,
        boolean pricesIncludeVat,
        String paymentDetails,
        String footer,
        Boolean deriveFromDocuments) {

    /** Cameroon standard VAT rate (17.5 % + 10 % additional council tax = 19.25 %). */
    public static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("19.25");

    /** Pre-20.6 shape: the derivation preference not chosen. */
    public InvoicingSettings(String legalName, String address, String phone, String niu, String rccm,
                             boolean vatRegistered, BigDecimal vatRate, boolean pricesIncludeVat,
                             String paymentDetails, String footer) {
        this(legalName, address, phone, niu, rccm, vatRegistered, vatRate, pricesIncludeVat, paymentDetails, footer,
                null);
    }

    /** Story 20.6 — the same settings with another derivation preference ({@code null} = not chosen). */
    public InvoicingSettings withDeriveFromDocuments(Boolean derive) {
        return new InvoicingSettings(legalName, address, phone, niu, rccm, vatRegistered, vatRate, pricesIncludeVat,
                paymentDetails, footer, derive);
    }

    public InvoicingSettings {
        legalName = trimToNull(legalName);
        address = trimToNull(address);
        phone = trimToNull(phone);
        niu = trimToNull(niu);
        rccm = trimToNull(rccm);
        paymentDetails = trimToNull(paymentDetails);
        footer = trimToNull(footer);
        if (vatRate == null) {
            vatRate = DEFAULT_VAT_RATE;
        }
        if (vatRate.signum() < 0 || vatRate.compareTo(new BigDecimal("100")) >= 0) {
            throw new IllegalArgumentException("VAT rate out of range");
        }
        if (vatRegistered && vatRate.signum() == 0) {
            throw new IllegalArgumentException("A VAT-registered seller needs a VAT rate above 0");
        }
    }

    /** Before the owner fills the profile: nothing known, not VAT-registered, 19.25 %, prices VAT included. */
    public static InvoicingSettings defaults() {
        return defaults(DEFAULT_VAT_RATE);
    }

    /**
     * Story 20.5 — the same with the standard rate of the seller's country ({@code com.keevo.shared.domain.VatRates},
     * the country of the tenant's oldest active store); {@code null} → 19.25 %.
     */
    public static InvoicingSettings defaults(BigDecimal countryRate) {
        return new InvoicingSettings(null, null, null, null, null, false,
                countryRate == null ? DEFAULT_VAT_RATE : countryRate, true, null, null);
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
