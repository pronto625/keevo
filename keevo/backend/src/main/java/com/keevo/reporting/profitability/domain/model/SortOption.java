package com.keevo.reporting.profitability.domain.model;

/**
 * SortOption — product profitability list sort algorithms.
 * Story 7.4, Task 4.4.
 */
public enum SortOption {
    MARGIN_PCT_DESC,
    MARGIN_XAF_DESC,
    CA_DESC,
    UNITS_DESC;

    /**
     * Case-insensitive parse; returns {@link #MARGIN_PCT_DESC} if unrecognised.
     */
    public static SortOption fromString(String s) {
        if (s == null) return MARGIN_PCT_DESC;
        try {
            return SortOption.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MARGIN_PCT_DESC;
        }
    }
}
