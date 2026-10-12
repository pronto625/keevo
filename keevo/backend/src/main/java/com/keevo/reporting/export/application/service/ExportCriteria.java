package com.keevo.reporting.export.application.service;

import com.keevo.shared.application.port.export.ExportDatasetProvider;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validation of dataset-specific export criteria (Story v1s-18-2). Values are trimmed and blank
 * values dropped; UUID criteria ({@code userId}, {@code entityId}) must parse; {@code q} is at most
 * 100 characters, {@code action} / {@code entityType} at most 80, any other value at most 100.
 * A criterion must be accepted by every requested dataset ({@link #checkApplicable}) — never
 * silently ignored. Violations raise {@link InvalidCriterionException}, mapped to a 400
 * {@code VALIDATION_ERROR} by the controller (and re-checked by the service).
 */
public final class ExportCriteria {

    public static final int MAX_ENTRIES = 10;
    static final Set<String> UUID_KEYS = Set.of("userId", "entityId");
    static final Map<String, Integer> MAX_LENGTH = Map.of("q", 100, "action", 80, "entityType", 80);
    static final int DEFAULT_MAX_LENGTH = 100;
    private static final Pattern KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,39}");

    private ExportCriteria() {
    }

    /** A malformed or inapplicable criterion. */
    public static final class InvalidCriterionException extends RuntimeException {
        private final String field;

        public InvalidCriterionException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String getField() {
            return field;
        }
    }

    /**
     * @return normalized, sorted copy (never null)
     * @throws InvalidCriterionException on a malformed key or value
     */
    public static Map<String, String> normalize(Map<String, String> raw) {
        Map<String, String> out = new TreeMap<>();
        if (raw == null) return out;
        if (raw.size() > MAX_ENTRIES) {
            throw new InvalidCriterionException("criteria", "at most " + MAX_ENTRIES + " criteria");
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            if (key == null || !KEY.matcher(key).matches()) {
                throw new InvalidCriterionException("criteria", "invalid criterion key");
            }
            String value = e.getValue() == null ? null : e.getValue().trim();
            if (value == null || value.isEmpty()) continue;
            if (value.length() > MAX_LENGTH.getOrDefault(key, DEFAULT_MAX_LENGTH)) {
                throw new InvalidCriterionException("criteria." + key, key + " is too long");
            }
            if (UUID_KEYS.contains(key)) {
                try {
                    value = UUID.fromString(value).toString();
                } catch (IllegalArgumentException ex) {
                    throw new InvalidCriterionException("criteria." + key, key + " must be a UUID");
                }
            }
            out.put(key, value);
        }
        return out;
    }

    /**
     * @throws InvalidCriterionException when a criterion key is not accepted by one of the datasets
     */
    public static void checkApplicable(Map<String, String> criteria, Collection<ExportDatasetProvider> providers) {
        for (String key : criteria.keySet()) {
            for (ExportDatasetProvider p : providers) {
                if (!p.criteriaKeys().contains(key)) {
                    throw new InvalidCriterionException("criteria." + key,
                            "criterion '" + key + "' does not apply to dataset " + p.key());
                }
            }
        }
    }
}
