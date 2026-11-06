package com.keevo.crm.contact.domain.entity;

import java.util.Locale;
import java.util.Optional;

/** Story 20.2 — Bref customer type ({@code person} / {@code company}): a private person or a company. */
public enum ClientType {
    PERSON,
    COMPANY;

    /** Case-insensitive ({@code person} as Bref writes it, {@code PERSON} as Keevo does); empty when unknown. */
    public static Optional<ClientType> parse(String raw) {
        if (raw == null) return Optional.empty();
        String key = raw.strip().toUpperCase(Locale.ROOT);
        for (ClientType t : values()) {
            if (t.name().equals(key)) return Optional.of(t);
        }
        return Optional.empty();
    }
}
