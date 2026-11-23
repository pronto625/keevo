package com.keevo.identity.company.domain.model;

import com.keevo.identity.onboarding.domain.model.SectorType;
import com.keevo.store.api.StoreCategory;

import java.util.Arrays;
import java.util.Map;
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

    /**
     * Story 22.19 (D2) — category → onboarding sector, the table of the Bref import ({@code BrefMappings.SECTORS}):
     * any other category is {@link SectorType#OTHER}.
     */
    private static final Map<String, SectorType> SECTORS = Map.ofEntries(
            Map.entry("fashion", SectorType.CLOTHING),
            Map.entry("electronics", SectorType.ELECTRONICS),
            Map.entry("telecom", SectorType.ELECTRONICS),
            Map.entry("stationery", SectorType.BOOKS_STATIONERY),
            Map.entry("education", SectorType.BOOKS_STATIONERY),
            Map.entry("home", SectorType.HOME_APPLIANCES),
            Map.entry("furniture", SectorType.HOME_APPLIANCES),
            Map.entry("food", SectorType.FOOD_GROCERY),
            Map.entry("restaurant", SectorType.FOOD_GROCERY),
            Map.entry("agriculture", SectorType.FOOD_GROCERY),
            Map.entry("hardware", SectorType.HARDWARE),
            Map.entry("automotive", SectorType.HARDWARE));

    private CompanyCategory() {
    }

    public static boolean isValid(String key) {
        return key != null && KEYS.contains(key);
    }

    public static Optional<String> of(String key) {
        return isValid(key) ? Optional.of(key) : Optional.empty();
    }

    /**
     * Story 22.19 (D9) — the key of a value given as a key ({@code real_estate}) or as its French label as the error
     * lists it (« Immobilier », case and accents ignored); empty for anything else.
     */
    public static Optional<String> resolve(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.strip();
        if (isValid(v)) {
            return Optional.of(v);
        }
        String folded = fold(v);
        return KEYS.stream()
                .filter(k -> StoreCategory.fromKey(k).map(c -> fold(c.label()).equals(folded)).orElse(false))
                .findFirst();
    }

    private static String fold(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT).strip();
    }

    /** The onboarding sector of a category; {@link SectorType#OTHER} for none or an unknown one. */
    public static SectorType sector(String key) {
        return key == null ? SectorType.OTHER : SECTORS.getOrDefault(key, SectorType.OTHER);
    }

    /** For messages (Story 22.19, D9): the French labels of the accepted categories, sorted. */
    public static String accepted() {
        return Arrays.stream(KEYS.toArray(String[]::new))
                .map(k -> StoreCategory.fromKey(k).map(StoreCategory::label).orElse(k))
                .sorted(java.text.Collator.getInstance(java.util.Locale.FRENCH))
                .collect(Collectors.joining(", "));
    }
}
