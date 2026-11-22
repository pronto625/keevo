package com.keevo.identity.company.domain.model;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Activity category of an organisation — the 19 keys of a Bref shop. One organisation = one activity (a pharmacy and
 * a grocery are two organisations). No database CHECK: the list may grow without a migration.
 */
public final class CompanyCategory {

    private static final Set<String> KEYS = Set.of("fashion", "electronics", "telecom", "food", "restaurant", "beauty",
            "sports", "home", "furniture", "hardware", "automotive", "agriculture", "stationery", "jewelry", "events",
            "education", "real_estate", "services", "other");

    private CompanyCategory() {
    }

    public static boolean isValid(String key) {
        return key != null && KEYS.contains(key);
    }

    public static Optional<String> of(String key) {
        return isValid(key) ? Optional.of(key) : Optional.empty();
    }

    /** For messages: the accepted keys, sorted. */
    public static String accepted() {
        return Arrays.stream(KEYS.toArray(String[]::new)).sorted().collect(Collectors.joining(", "));
    }
}
