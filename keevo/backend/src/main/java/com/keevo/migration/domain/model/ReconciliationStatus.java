package com.keevo.migration.domain.model;

/** Story 24.8 — outcome of the reconciliation of an imported Bref owner (FUS-MIG3). */
public enum ReconciliationStatus {
    /** Every check within its tolerance (explained differences included): the owner may be activated (24.9). */
    RECONCILED("Réconcilié"),
    /** An out-of-tolerance gap, or a reconciliation that could not run: the owner blocks the activation. */
    RECONCILIATION_FAILED("Écart à corriger avant l'activation");

    private final String label;

    ReconciliationStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
