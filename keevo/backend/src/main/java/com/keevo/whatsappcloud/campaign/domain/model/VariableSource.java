package com.keevo.whatsappcloud.campaign.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Story 21.6 — what fills a template variable for each recipient (Bref {@code CustomerVariableSource}): a fixed text
 * ({@code STATIC}), the client's name ({@code CLIENT_NAME}, Bref {@code customerName}) or the store's name
 * ({@code STORE_NAME}, Bref {@code shopName}). Bref's spellings are accepted.
 */
public enum VariableSource {
    STATIC, CLIENT_NAME, STORE_NAME;

    public static Optional<VariableSource> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT).replace("_", "")) {
            case "static" -> Optional.of(STATIC);
            case "clientname", "customername" -> Optional.of(CLIENT_NAME);
            case "storename", "shopname" -> Optional.of(STORE_NAME);
            default -> Optional.empty();
        };
    }
}
