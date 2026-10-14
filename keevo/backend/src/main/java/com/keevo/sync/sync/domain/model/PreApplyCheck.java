package com.keevo.sync.sync.domain.model;

/**
 * PreApplyCheck — what a conflict strategy decided about an operation BEFORE its handler runs.
 *
 * @param superseded non-null: the entity already holds a newer state, the operation must not be
 *                   applied (acknowledged as APPLIED so the device drops it) and this conflict is logged
 * @param overwrite  non-null: applying the operation overwrites a concurrent change (another device or
 *                   the server); logged only if the handler really applies it
 */
public record PreApplyCheck(ConflictResult superseded, ConflictResult overwrite) {

    public static final PreApplyCheck NONE = new PreApplyCheck(null, null);

    public static PreApplyCheck superseded(ConflictResult conflict) {
        return new PreApplyCheck(conflict, null);
    }

    public static PreApplyCheck overwrite(ConflictResult conflict) {
        return new PreApplyCheck(null, conflict);
    }

    public boolean isSuperseded() {
        return superseded != null;
    }
}
