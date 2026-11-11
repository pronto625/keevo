package com.keevo.migration.domain.model;

import java.util.Arrays;
import java.util.Optional;

/** Where the snapshot of a Bref owner is read (Story 24.2). */
public enum SourceKind {
    /** The api-bref export of the owner (JSON file + SHA-256, contract {@code keevo/contracts/bref-export.json}). */
    EXPORT("Export Bref"),
    /** A restored Bref database, read-only (dry-run, tests). */
    DATABASE("Base Bref (lecture seule)");

    private final String label;

    SourceKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<SourceKind> fromCode(String code) {
        return code == null ? Optional.empty()
                : Arrays.stream(values()).filter(k -> k.name().equals(code.strip())).findFirst();
    }
}
