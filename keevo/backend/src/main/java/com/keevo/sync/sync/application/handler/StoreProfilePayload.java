package com.keevo.sync.sync.application.handler;

import com.keevo.store.api.StoreProfilePatch;
import com.keevo.store.api.StoreView;

import java.util.Map;
import java.util.Objects;

/**
 * The optional Bref fields of a CREATE_STORE / UPDATE_STORE payload (Story 19.12): {@code category},
 * {@code description}, {@code niu}, {@code rccm}, {@code country}; {@code shareClients} (Story 20.2, boolean). Absent or null ⇒ unchanged (a V1 app sends none).
 * A non-string value → {@link IllegalArgumentException} (REJECTED); the values themselves are validated by the store
 * module ({@code StoreCommandPort#normalizeProfile}, the REST rule) → {@code VALIDATION_ERROR} (REJECTED).
 */
final class StoreProfilePayload {

    private StoreProfilePayload() {
    }

    static StoreProfilePatch read(Map<String, Object> payload) {
        return new StoreProfilePatch(
                string(payload, "category"),
                string(payload, "description"),
                string(payload, "niu"),
                string(payload, "rccm"),
                string(payload, "country"),
                bool(payload, "shareClients"));
    }

    /** Whether a normalized patch ({@code null} = unchanged, {@code ""} = clear) changes nothing on {@code current}. */
    static boolean matches(StoreProfilePatch normalized, StoreView current) {
        return same(normalized.category(), current.category() == null ? null : current.category().key())
                && same(normalized.description(), current.description())
                && same(normalized.niu(), current.niu())
                && same(normalized.rccm(), current.rccm())
                && same(normalized.country(), current.country())
                && (normalized.shareClients() == null || normalized.shareClients() == current.shareClients());
    }

    private static boolean same(String patched, String current) {
        return patched == null || Objects.equals(patched.isEmpty() ? null : patched, current);
    }

    /** Story 20.2 — {@code shareClients}: a JSON boolean or absent. */
    private static Boolean bool(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new IllegalArgumentException("Invalid field: " + key);
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("Invalid field: " + key);
    }
}
