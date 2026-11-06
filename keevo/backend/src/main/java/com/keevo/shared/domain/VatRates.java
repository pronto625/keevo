package com.keevo.shared.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Story 20.5 (fusion Keevo × Bref) — the standard VAT rate of a seller's country, in percent ({@code 19.25} = 19,25 %),
 * the table of Bref ({@code api-bref/src/invoice/constants/vat-rates.constant.ts}): CM 19.25; SN, CI, BJ, ML, BF, TG,
 * GN, GA 18; CD, KE 16; GH 15; NG 7.5. A country outside the table has no known rate (empty): the caller keeps its
 * own default (Bref's 0 % applies only to a country listed with 0, none today). Constant lookup, no I/O.
 */
public final class VatRates {

    private static final Map<String, BigDecimal> RATES = Map.ofEntries(
            Map.entry("CM", new BigDecimal("19.25")),
            Map.entry("SN", new BigDecimal("18.00")),
            Map.entry("CI", new BigDecimal("18.00")),
            Map.entry("BJ", new BigDecimal("18.00")),
            Map.entry("ML", new BigDecimal("18.00")),
            Map.entry("BF", new BigDecimal("18.00")),
            Map.entry("TG", new BigDecimal("18.00")),
            Map.entry("GN", new BigDecimal("18.00")),
            Map.entry("GA", new BigDecimal("18.00")),
            Map.entry("CD", new BigDecimal("16.00")),
            Map.entry("KE", new BigDecimal("16.00")),
            Map.entry("GH", new BigDecimal("15.00")),
            Map.entry("NG", new BigDecimal("7.50")));

    private VatRates() {
    }

    /** The standard rate of {@code countryCode} (ISO 3166-1 alpha-2, any case); unknown or {@code null} → empty. */
    public static Optional<BigDecimal> forCountry(String countryCode) {
        if (countryCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(RATES.get(countryCode.strip().toUpperCase(Locale.ROOT)));
    }
}
