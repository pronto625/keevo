package com.keevo.catalog.product.application.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ImportResult — Immutable summary of a CSV import operation.
 *
 * <p>GoF Pattern: <b>Builder</b> — accumulates imported count, skipped count, error list,
 * and limitReached flag progressively during the import loop. Prevents mutable parameter
 * explosion and keeps the use case loop clean.
 *
 * <p>Usage:
 * <pre>{@code
 *   var builder = new ImportResult.Builder();
 *   // ... in loop:
 *   builder.incrementImported();
 *   builder.addError(new CsvRowError(12, "prix_vente", "Prix de vente manquant"));
 *   builder.markLimitReached("Limite atteinte : 50 produits importés ...");
 *   // at end:
 *   ImportResult result = builder.build();
 * }</pre>
 */
public final class ImportResult {

    private final int imported;
    private final int skipped;
    private final List<CsvRowError> errors;
    private final boolean limitReached;
    private final String limitMessage;  // null unless limitReached

    private ImportResult(int imported, int skipped, List<CsvRowError> errors,
                         boolean limitReached, String limitMessage) {
        this.imported     = imported;
        this.skipped      = skipped;
        this.errors       = Collections.unmodifiableList(new ArrayList<>(errors));
        this.limitReached = limitReached;
        this.limitMessage = limitMessage;
    }

    public int getImported()        { return imported; }
    public int getSkipped()         { return skipped; }
    public List<CsvRowError> getErrors() { return errors; }
    public boolean isLimitReached() { return limitReached; }
    public String getLimitMessage() { return limitMessage; }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private int imported     = 0;
        private int skipped      = 0;
        private final List<CsvRowError> errors = new ArrayList<>();
        private boolean limitReached = false;
        private String  limitMessage = null;

        public void incrementImported()          { imported++; }
        public void incrementSkipped()           { skipped++; }
        public void addError(CsvRowError error)  { errors.add(error); skipped++; }

        public void markLimitReached(String message) {
            this.limitReached = true;
            this.limitMessage = message;
        }

        public int getImported() { return imported; }
        public int getSkipped()  { return skipped;  }

        public ImportResult build() {
            return new ImportResult(imported, skipped, errors, limitReached, limitMessage);
        }
    }
}
