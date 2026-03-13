package com.keevo.store.store.domain.model;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * StoreFactory — Encapsulates creation rules for Store domain objects.
 *
 * <p>GoF Pattern: <b>Factory Method</b> — centralizes all creation validation
 * (name length, type defaulting). Adding new store types or validation rules
 * only requires updating this factory, not its callers.
 *
 * <p>Story 3.1 — AC1 (create store), AC3 (warehouse type).
 */
public class StoreFactory {

    private StoreFactory() { /* utility class */ }

    /**
     * Create a new Store with full validation.
     *
     * @param name    store name (2–100 chars, required)
     * @param type    store type (defaults to STORE if null)
     * @param address optional address
     * @param phone   optional phone in E.164
     * @return a new Store with generated UUID and current timestamps
     * @throws DomainException VALIDATION_ERROR if name is invalid
     */
    public static Store create(String name, StoreType type, String address, String phone) {
        if (name == null || name.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Le nom de la boutique est obligatoire");
        }
        String trimmed = name.trim();
        if (trimmed.length() < 2 || trimmed.length() > 100) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Le nom doit contenir entre 2 et 100 caractères");
        }
        StoreType resolvedType = type != null ? type : StoreType.STORE;
        Instant now = Instant.now();
        return new Store(UUID.randomUUID(), trimmed, resolvedType, address, phone, true, now, now);
    }
}
