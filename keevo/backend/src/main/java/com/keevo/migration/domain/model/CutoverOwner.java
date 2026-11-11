package com.keevo.migration.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 24.9 — one Bref owner of a cutover ({@code public.migration_cutover_owners}). {@code label} is what a screen
 * shows (« Compte n »), {@code companyName} the imported company's name once it exists; the ids are handles.
 *
 * @param reason French reason of the status (blocking cases, gap, error), {@code null} when none
 */
public record CutoverOwner(UUID brefOwnerId, int position, String label, String companyName,
                           CutoverOwnerStatus status, UUID batchId, UUID tenantId, String reason, Integer importMs,
                           Integer reconcileMs, Instant startedAt, Instant finishedAt) {

    /** The neutral name of an owner in reports and lists: its listing position. */
    public static String label(int position) {
        return "Compte " + position;
    }
}
