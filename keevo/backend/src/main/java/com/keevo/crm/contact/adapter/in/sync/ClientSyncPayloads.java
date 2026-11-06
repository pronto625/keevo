package com.keevo.crm.contact.adapter.in.sync;

import com.keevo.crm.api.ClientProfileInput;

import java.util.Map;
import java.util.UUID;

/**
 * Story 20.2 — the optional fields of a CREATE_CLIENT / UPDATE_CLIENT payload: Bref fields ({@code clientType},
 * {@code niu}, {@code rccm}, {@code address}, {@code contactName}, {@code website}, {@code birthDate}) and
 * {@code storeId}. Absent or null ⇒ none / unchanged (a V1 app sends none). A value of the wrong JSON type →
 * {@link IllegalArgumentException} (REJECTED); the values themselves are validated by the crm rule set
 * ({@code VALIDATION_ERROR}).
 */
final class ClientSyncPayloads {

    private ClientSyncPayloads() {
    }

    static ClientProfileInput profile(Map<String, Object> p) {
        return new ClientProfileInput(string(p, "clientType"), string(p, "niu"), string(p, "rccm"),
                string(p, "address"), string(p, "contactName"), string(p, "website"), string(p, "birthDate"));
    }

    static UUID storeId(Map<String, Object> p) {
        String raw = string(p, "storeId");
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid field: storeId");
        }
    }

    static String string(Map<String, Object> p, String key) {
        Object value = p.get(key);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("Invalid field: " + key);
    }
}
