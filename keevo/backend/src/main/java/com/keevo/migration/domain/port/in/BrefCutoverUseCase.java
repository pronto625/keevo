package com.keevo.migration.domain.port.in;

import com.keevo.migration.domain.model.Cutover;
import com.keevo.migration.domain.model.CutoverOwner;
import com.keevo.migration.domain.model.CutoverOwnerStatus;
import com.keevo.migration.domain.model.MigrationBatch;

import java.util.List;
import java.util.UUID;

/**
 * Story 24.9 — the one-shot cutover command (ADR-10): import every owner of a restored Bref database with its
 * reconciliation, then the « go » (global activation) or the rollback, and the reads of the super-admin screen.
 */
public interface BrefCutoverUseCase {

    /** The confirmation phrase of the « go ». */
    String GO_CONFIRMATION = "ACTIVER";
    /** The confirmation phrase of the rollback. */
    String ROLLBACK_CONFIRMATION = "RETOUR ARRIERE";

    /**
     * Opens a cutover and starts it in the background.
     *
     * @param source      {@code DATABASE} only
     * @param parallelism owners imported at the same time, 1–8 ({@code null} = default)
     * @throws com.keevo.shared.domain.exception.DomainException {@code VALIDATION_FAILED},
     *         {@code MIGRATION_SOURCE_UNAVAILABLE}, {@code INVALID_STATE} (another cutover is open)
     */
    Cutover start(String source, Integer parallelism, UUID actorId);

    /**
     * Runs the cutover again in the background: owners not reconciled yet and owners added to the source since.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_CUTOVER_NOT_FOUND},
     *         {@code INVALID_STATE}
     */
    Cutover resume(UUID cutoverId, UUID actorId);

    /**
     * The « go »: activates the imported companies when no owner blocks, or when every blocking owner is accepted.
     * It may be given again on an ACTIVATED cutover: it then activates the owners cleared since (the first « go »
     * keeps its actor and time).
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_CUTOVER_NOT_FOUND},
     *         {@code VALIDATION_FAILED}, {@code INVALID_STATE} (with {@code details.blockingOwners} when owners block)
     */
    Cutover activate(UUID cutoverId, String confirmation, List<Acceptance> accepted, UUID actorId);

    /**
     * Removes every company imported by the cutover (before the « go » only), in the background.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_CUTOVER_NOT_FOUND},
     *         {@code VALIDATION_FAILED}, {@code INVALID_STATE}
     */
    Cutover rollback(UUID cutoverId, String confirmation, UUID actorId);

    /** @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_CUTOVER_NOT_FOUND} */
    Cutover cutover(UUID cutoverId);

    List<Cutover> cutovers(UUID after, int limit);

    /** @throws com.keevo.shared.domain.exception.DomainException {@code MIGRATION_CUTOVER_NOT_FOUND} */
    List<CutoverOwner> owners(UUID cutoverId, CutoverOwnerStatus status, Integer afterPosition, int limit);

    List<MigrationBatch> batches(UUID after, int limit);

    /** An owner let through the « go » despite its gap or block, with the documented reason. */
    record Acceptance(UUID ownerId, String reason) {
    }
}
