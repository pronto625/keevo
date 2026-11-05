package com.keevo.store.api;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The category of a store — exactly the 19 keys of a Bref shop (Story 19.12, source
 * {@code api-bref/src/shop/domain/shop.ts}). The API and the sync delta carry the {@link #key() key}; the French
 * {@link #label() label} is for people (audit detail). No database CHECK: the list may grow without a migration.
 */
public enum StoreCategory {
    FASHION("fashion", "Mode et vêtements"),
    ELECTRONICS("electronics", "Électronique"),
    TELECOM("telecom", "Téléphonie"),
    FOOD("food", "Alimentation"),
    RESTAURANT("restaurant", "Restauration"),
    BEAUTY("beauty", "Beauté et cosmétiques"),
    SPORTS("sports", "Sport et loisirs"),
    HOME("home", "Maison et décoration"),
    FURNITURE("furniture", "Ameublement"),
    HARDWARE("hardware", "Quincaillerie"),
    AUTOMOTIVE("automotive", "Automobile"),
    AGRICULTURE("agriculture", "Agriculture"),
    STATIONERY("stationery", "Papeterie"),
    JEWELRY("jewelry", "Bijouterie"),
    EVENTS("events", "Événementiel"),
    EDUCATION("education", "Éducation"),
    REAL_ESTATE("real_estate", "Immobilier"),
    SERVICES("services", "Services"),
    OTHER("other", "Autre");

    private static final Map<String, StoreCategory> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(StoreCategory::key, Function.identity()));

    private final String key;
    private final String label;

    StoreCategory(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The stored / exchanged key (e.g. {@code real_estate}). */
    public String key() {
        return key;
    }

    /** The French label (e.g. « Immobilier »). */
    public String label() {
        return label;
    }

    /** The category of an exact key (case-sensitive, as Bref stores it); empty for anything else, null included. */
    public static Optional<StoreCategory> fromKey(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(BY_KEY.get(key));
    }
}
