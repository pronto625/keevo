package com.keevo.whatsappcloud.campaign.domain.model;

/**
 * Story 21.6 — one variable of the campaign's template: its source and the fixed text (STATIC) or the fallback when
 * the client's / store's name is missing. Meta refuses an empty parameter: the last resort is « - » (Bref).
 */
public record VariableMapping(VariableSource source, String value) {

    public static final int MAX_VALUE = 1024;

    /** The value for one recipient. */
    public String resolve(String clientName, String storeName) {
        String v = switch (source) {
            case CLIENT_NAME -> clientName;
            case STORE_NAME -> storeName;
            case STATIC -> value;
        };
        if (v != null && !v.isBlank()) {
            return bounded(v.strip());
        }
        return value != null && !value.isBlank() ? bounded(value.strip()) : "-";
    }

    private static String bounded(String v) {
        return v.length() > MAX_VALUE ? v.substring(0, MAX_VALUE) : v;
    }
}
