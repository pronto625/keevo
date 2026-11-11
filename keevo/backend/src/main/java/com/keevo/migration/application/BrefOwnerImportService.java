package com.keevo.migration.application;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.LegacyIdentityImportPort.ConflictKind;
import com.keevo.identity.api.LegacyIdentityImportPort.LegacyAccount;
import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantStatus;
import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.BlockedCase;
import com.keevo.migration.domain.model.BrefAccount;
import com.keevo.migration.domain.model.BrefShop;
import com.keevo.migration.domain.model.BrefSnapshot;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.in.BrefOwnerImportUseCase;
import com.keevo.migration.domain.port.out.BrefSourcePort;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.migration.domain.service.BrefSnapshotValidator;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.store.api.StoreCommandPort;
import com.keevo.store.api.StoreProfilePatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.keevo.migration.domain.model.MigrationRecord.BREF_USER;
import static com.keevo.migration.domain.model.MigrationRecord.TENANTS;
import static com.keevo.migration.domain.model.MigrationRecord.USERS;

/**
 * Story 24.2 — the import of one Bref owner (analysis §10.2, steps 1–3), synchronous (an owner imports in well under a
 * minute).
 *
 * <p>Patterns: Strategy for the source ({@link BrefSourcePort} per {@link SourceKind}), validation as a pure function
 * ({@link BrefSnapshotValidator}) before any write, then a Pipeline of {@link ImportStep}s (ordered beans: 24.3–24.6
 * append theirs), each its own unit of work and idempotent. One batch per run ({@code migration_batches}), one RUNNING
 * batch per owner at a time.
 *
 * <p>Cost per run: the source read (3 queries or one streaming pass), one records read, one conflicts query, then the
 * steps' batches — never a query per row. No personal data in logs (ids and counts only; a failure logs its class).
 */
@Service
public class BrefOwnerImportService implements BrefOwnerImportUseCase {

    private static final Logger log = LoggerFactory.getLogger(BrefOwnerImportService.class);

    /** A RUNNING batch older than this was interrupted (restart, crash): closed FAILED and taken over. */
    static final Duration STALE_RUN = Duration.ofHours(1);

    private final Map<SourceKind, BrefSourcePort> sources = new EnumMap<>(SourceKind.class);
    private final MigrationRecordStore store;
    private final LegacyIdentityImportPort identity;
    private final TenantQueryPort tenants;
    private final StoreCommandPort storeRules;
    private final List<ImportStep> steps;
    /** The record tables the check and the steps read: the only records loaded per run. */
    private final List<String> targetTables;

    public BrefOwnerImportService(List<BrefSourcePort> sources, MigrationRecordStore store,
                                  LegacyIdentityImportPort identity, TenantQueryPort tenants,
                                  StoreCommandPort storeRules, List<ImportStep> steps) {
        sources.forEach(s -> this.sources.put(s.kind(), s));
        this.store = store;
        this.identity = identity;
        this.tenants = tenants;
        this.storeRules = storeRules;
        this.steps = List.copyOf(steps); // ordered by @Order
        Set<String> tables = new java.util.TreeSet<>(List.of(MigrationRecord.TENANTS, MigrationRecord.USERS));
        steps.forEach(s -> tables.addAll(s.targetTables()));
        this.targetTables = List.copyOf(tables);
    }

    @Override
    public MigrationBatch importOwner(UUID brefOwnerId, String source, UUID actorId) {
        SourceKind kind = SourceKind.fromCode(source).orElseThrow(() -> DomainException.userFacing(
                ErrorCode.VALIDATION_FAILED, "Source inconnue : choisissez l'export Bref ou la base Bref"));
        BrefSourcePort port = sources.get(kind);
        if (port == null || !port.available()) {
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref source " + kind + " unavailable");
        }
        BrefSnapshot snapshot = port.load(brefOwnerId);
        UUID batchId = store.startBatch(brefOwnerId, kind, actorId, STALE_RUN)
                .orElseThrow(() -> new DomainException(ErrorCode.MIGRATION_ALREADY_RUNNING,
                        "An import of this owner is running"));
        ImportRun run = new ImportRun(snapshot, batchId, actorId, new java.util.HashMap<>());
        String stepLabel = "vérification";
        try {
            run = new ImportRun(snapshot, batchId, actorId, store.findTargets(brefOwnerId, targetTables));
            List<BlockedCase> cases = check(run);
            if (!cases.isEmpty()) {
                finish(batchId, BatchStatus.BLOCKED, null, run.counts(), cases, List.of(), null);
                log.info("[ADMIN AUDIT] Bref import blocked: actor={} batch={} owner={} source={} cases={}", actorId,
                        batchId, brefOwnerId, kind, cases.size());
                return batch(batchId);
            }
            for (ImportStep step : steps) {
                stepLabel = step.label();
                step.run(run);
            }
            finish(batchId, BatchStatus.COMPLETED, run.tenantId(), run.counts(), List.of(), run.notes(), null);
            log.info("[ADMIN AUDIT] Bref import completed: actor={} batch={} owner={} source={} tenant={} counts={}",
                    actorId, batchId, brefOwnerId, kind, run.tenantId(), run.counts());
        } catch (RuntimeException e) {
            // the exception's message may quote source values (SQL detail): only its class is logged
            log.warn("[ADMIN AUDIT] Bref import failed: actor={} batch={} owner={} step={} error={}", actorId, batchId,
                    brefOwnerId, stepLabel, e.getClass().getSimpleName());
            finish(batchId, BatchStatus.FAILED, run.tenantId(), run.counts(), List.of(), run.notes(),
                    "L'import s'est arrêté à l'étape « " + stepLabel + " » : relancez-le ; "
                            + "les étapes déjà faites sont conservées");
        }
        return batch(batchId);
    }

    /**
     * Closes the batch; a failure to close is logged, not thrown over the import's own outcome (the stale-run
     * take-over frees the owner after {@link #STALE_RUN}).
     */
    private void finish(UUID batchId, BatchStatus status, UUID tenantId, Map<String, Integer> counts,
                        List<BlockedCase> cases, List<com.keevo.migration.domain.model.ImportNote> notes,
                        String errorLabel) {
        try {
            store.finishBatch(batchId, status, tenantId, counts, cases, notes, errorLabel);
        } catch (RuntimeException e) {
            log.error("Bref import: batch {} could not be closed {} ({})", batchId, status,
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public MigrationBatch batch(UUID batchId) {
        return store.findBatch(batchId).orElseThrow(() -> new DomainException(ErrorCode.MIGRATION_BATCH_NOT_FOUND,
                "Migration batch not found"));
    }

    /** Every blocking case of the owner, before any write (one conflicts query, one tenant read at most). */
    private List<BlockedCase> check(ImportRun run) {
        BrefSnapshot snapshot = run.snapshot();
        List<BlockedCase> cases = new ArrayList<>();
        UUID tenantId = run.target(BREF_USER, snapshot.owner().id(), TENANTS);
        if (tenantId != null && tenants.findById(tenantId).filter(t -> t.status() != TenantStatus.DELETED)
                .isEmpty()) {
            cases.add(BlockedCase.of(BlockedCase.Kind.TENANT_GONE, BREF_USER, snapshot.owner().id()));
        }
        List<BrefAccount> accounts = new ArrayList<>();
        accounts.add(snapshot.owner());
        snapshot.members().stream().filter(m -> !m.deleted()).forEach(accounts::add);
        List<LegacyAccount> legacy = accounts.stream().map(BrefMappings::toLegacy).toList();
        Map<UUID, Set<ConflictKind>> conflicts = identity.findConflicts(legacy);
        Set<UUID> imported = new HashSet<>();
        for (BrefAccount a : accounts) {
            if (run.imported(BREF_USER, a.id(), USERS)) {
                imported.add(a.id());
            }
        }
        cases.addAll(BrefSnapshotValidator.validate(snapshot, conflicts, imported, this::shopProblem));
        return cases;
    }

    /** The store module's own rules (name, category, bounded fields, country), French message on refusal. */
    private Optional<String> shopProblem(BrefShop shop) {
        try {
            storeRules.validateName(shop.name());
            storeRules.normalizeProfile(new StoreProfilePatch(shop.category(), shop.description(), shop.niu(),
                    shop.rccm(), shop.country()));
            return Optional.empty();
        } catch (DomainException e) {
            return Optional.ofNullable(e.getMessage());
        }
    }
}
