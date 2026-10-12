package com.keevo.shared.application.port.export;

import java.util.Objects;

/**
 * One column of an export dataset (Story v1s-18-1).
 *
 * @param key   stable technical key (documented column schema, shared with the on-device CSV)
 * @param label French header label shown in the file
 * @param type  logical type, drives cell formatting
 */
public record ExportColumn(String key, String label, ExportColumnType type) {

    public ExportColumn {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(type, "type");
    }
}
