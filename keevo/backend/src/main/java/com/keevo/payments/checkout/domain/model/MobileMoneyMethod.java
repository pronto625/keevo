package com.keevo.payments.checkout.domain.model;

import java.util.Locale;
import java.util.Optional;

/** Story 21.1 — Mobile Money operator chosen by the owner (Cameroon). */
public enum MobileMoneyMethod {
    ORANGE_MONEY("Orange Money"),
    MTN_MOMO("MTN Mobile Money");

    private final String label;

    MobileMoneyMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Case-insensitive; Bref's values {@code orange_money} / {@code momo} are accepted too. */
    public static Optional<MobileMoneyMethod> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "ORANGE_MONEY", "OM" -> Optional.of(ORANGE_MONEY);
            case "MTN_MOMO", "MOMO", "MTN" -> Optional.of(MTN_MOMO);
            default -> Optional.empty();
        };
    }
}
