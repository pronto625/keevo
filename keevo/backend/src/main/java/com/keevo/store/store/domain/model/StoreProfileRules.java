package com.keevo.store.store.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreCategory;
import com.keevo.store.api.StoreProfilePatch;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single rule set of the Bref fields of a store (Story 19.12), shared by the REST endpoints and the sync push
 * so the two entry points cannot diverge. Pure Java.
 *
 * <p><b>Partial update by presence</b> (V1-safe): a field {@code null} (absent) keeps the stored value; an empty or
 * blank string clears {@code category}, {@code description}, {@code niu}, {@code rccm}; {@code country} can never be
 * cleared. The category key is trimmed and lower-cased, the country upper-cased and checked against the ISO 3166-1
 * alpha-2 list. Any bad value → {@code VALIDATION_ERROR} with a French message.
 */
public final class StoreProfileRules {

    public static final int MAX_DESCRIPTION = 1000;
    public static final int MAX_NIU = 30;
    public static final int MAX_RCCM = 50;
    /** ISO 3166-1 alpha-2 codes known to the JDK. */
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

    private StoreProfileRules() {
    }

    /**
     * Validates and normalizes a raw patch: {@code null} = unchanged, {@code ""} = clear, other values trimmed, the
     * category as its key, the country upper-cased.
     */
    public static StoreProfilePatch normalize(StoreProfilePatch raw) {
        if (raw == null || raw.isEmpty()) {
            return StoreProfilePatch.NONE;
        }
        String category = null;
        if (raw.category() != null) {
            String key = raw.category().strip().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                category = "";
            } else {
                category = StoreCategory.fromKey(key).map(StoreCategory::key)
                        .orElseThrow(() -> invalid("Catégorie de boutique inconnue"));
            }
        }
        return new StoreProfilePatch(
                category,
                bounded(raw.description(), MAX_DESCRIPTION,
                        "La description ne doit pas dépasser " + MAX_DESCRIPTION + " caractères"),
                bounded(raw.niu(), MAX_NIU, "Le NIU ne doit pas dépasser " + MAX_NIU + " caractères"),
                bounded(raw.rccm(), MAX_RCCM, "Le RCCM ne doit pas dépasser " + MAX_RCCM + " caractères"),
                country(raw.country()),
                raw.shareClients());
    }

    /** The store with a raw patch applied (validated here). An empty patch returns the same store. */
    public static Store apply(Store store, StoreProfilePatch raw) {
        StoreProfilePatch p = normalize(raw);
        if (p.isEmpty()) {
            return store;
        }
        Store profiled = store.withProfile(
                p.category() == null ? store.category() : StoreCategory.fromKey(p.category()).orElse(null),
                pick(p.description(), store.description()),
                pick(p.niu(), store.niu()),
                pick(p.rccm(), store.rccm()),
                p.country() == null ? store.country() : p.country());
        return p.shareClients() == null ? profiled : profiled.withShareClients(p.shareClients());
    }

    /** What changed between two versions of a store, as audit values (category as its French label, never a key). */
    public record Changes(Map<String, Object> before, Map<String, Object> after) {
        public boolean isEmpty() {
            return before.isEmpty() && after.isEmpty();
        }
    }

    /** The fields that differ between two versions of a store: name, address, phone and the Bref fields. */
    public static Changes changes(Store before, Store after) {
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> a = new LinkedHashMap<>();
        diff("name", before.name(), after.name(), b, a);
        diff("address", before.address(), after.address(), b, a);
        diff("phone", before.phone(), after.phone(), b, a);
        diff("category", label(before.category()), label(after.category()), b, a);
        diff("description", before.description(), after.description(), b, a);
        diff("niu", before.niu(), after.niu(), b, a);
        diff("rccm", before.rccm(), after.rccm(), b, a);
        diff("country", before.country(), after.country(), b, a);
        diff("shareClients", before.shareClients(), after.shareClients(), b, a); // Story 20.2
        return new Changes(b, a);
    }

    private static void diff(String key, Object before, Object after, Map<String, Object> b, Map<String, Object> a) {
        if (!Objects.equals(before, after)) {
            if (before != null) b.put(key, before);
            if (after != null) a.put(key, after);
        }
    }

    private static String label(StoreCategory category) {
        return category == null ? null : category.label();
    }

    private static String pick(String patched, String current) {
        if (patched == null) return current;
        return patched.isEmpty() ? null : patched;
    }

    private static String bounded(String value, int max, String message) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.length() > max) throw invalid(message);
        return trimmed;
    }

    private static String country(String value) {
        if (value == null) return null;
        String code = value.strip().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) throw invalid("Le pays de la boutique est obligatoire");
        if (!COUNTRIES.contains(code)) {
            throw invalid("Pays inconnu : indiquez le code du pays en deux lettres (par exemple CM)");
        }
        return code;
    }

    private static DomainException invalid(String message) {
        return DomainException.userFacing(ErrorCode.VALIDATION_ERROR, message);
    }
}
