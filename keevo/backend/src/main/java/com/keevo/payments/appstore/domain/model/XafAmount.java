package com.keevo.payments.appstore.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Story 21.2 — the XAF equivalent of a store price: XAF as is, EUR at the fixed peg (1 EUR = 655,957 XAF), any other
 * currency unknown ({@code null}: no exchange rate is guessed).
 */
public final class XafAmount {

    /** The CFA franc is pegged to the euro. */
    public static final BigDecimal EUR_PEG = new BigDecimal("655.957");
    /** Above this a store price is not a price (guards the conversion against absurd payloads). */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    private XafAmount() {
    }

    /** XAF equivalent rounded to the franc, {@code null} when the amount is not positive or the currency unknown. */
    public static Long of(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0 || currency == null || amount.compareTo(MAX_AMOUNT) > 0) {
            return null;
        }
        String c = currency.trim().toUpperCase(Locale.ROOT);
        BigDecimal xaf = switch (c) {
            case "XAF" -> amount;
            case "EUR" -> amount.multiply(EUR_PEG);
            default -> null;
        };
        if (xaf == null) {
            return null;
        }
        long rounded = xaf.setScale(0, RoundingMode.HALF_UP).longValueExact();
        return rounded > 0 ? rounded : null;
    }

    /** For the ledger: the purchase converted when possible, else the Apple price declared by the super-admin. */
    public static Long ledger(BigDecimal amount, String currency, Long declaredXaf) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        Long converted = of(amount, currency);
        return converted != null ? converted : declaredXaf;
    }
}
