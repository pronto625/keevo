package com.keevo.admin.crosstenant;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Story 20.13 — validation of the raw query parameters of the super-admin read API; every refusal is a 422
 * {@code VALIDATION_ERROR} with a French message.
 */
public final class AdminParams {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_SEARCH = 100;
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 9999;

    private AdminParams() {
    }

    /** Page size: absent → 20; 1..100. */
    public static int limit(String raw) {
        return bounded(raw, "La taille de page", DEFAULT_LIMIT, 1, MAX_LIMIT);
    }

    /** An integer between {@code min} and {@code max}; absent → {@code defaultValue}. {@code name}: French label. */
    public static int bounded(String raw, String name, int defaultValue, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid(name + " doit être un nombre entier");
        }
        if (value < min || value > max) {
            throw invalid(name + " doit être compris entre " + min + " et " + max);
        }
        return value;
    }

    /** Trimmed search text (blank → null), at most 100 characters. */
    public static String search(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_SEARCH) {
            throw invalid("La recherche ne peut pas dépasser " + MAX_SEARCH + " caractères");
        }
        return trimmed;
    }

    /** A calendar date {@code yyyy-MM-dd} (blank → null). {@code name}: French label (« La date de début »). */
    public static LocalDate date(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw invalid(name + " est invalide (format AAAA-MM-JJ)");
        }
        if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
            throw invalid(name + " doit être comprise entre " + MIN_YEAR + " et " + MAX_YEAR);
        }
        return date;
    }

    /** Refuses {@code from} after {@code to}. */
    public static void range(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("La date de début doit précéder la date de fin");
        }
    }

    /** A {@code true} / {@code false} flag (blank → null). {@code name}: French label. */
    public static Boolean flag(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw invalid(name + " doit valoir true ou false");
        };
    }

    /** {@code LIKE} pattern of a search text: lower-cased, {@code % _ \} escaped, wrapped in {@code %}. */
    public static String likePattern(String search) {
        String escaped = search.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public static DomainException invalid(String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message);
    }
}
