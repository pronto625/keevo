package com.keevo.migration.application;

import com.keevo.identity.api.LegacyIdentityImportPort;
import com.keevo.identity.api.LegacyIdentityImportPort.RemovalPreparation;
import com.keevo.identity.api.TenantDeletionPort;
import com.keevo.migration.domain.model.BatchStatus;
import com.keevo.migration.domain.model.Cutover;
import com.keevo.migration.domain.model.CutoverOwner;
import com.keevo.migration.domain.model.CutoverOwnerStatus;
import com.keevo.migration.domain.model.CutoverReport;
import com.keevo.migration.domain.model.CutoverStatus;
import com.keevo.migration.domain.model.MigrationBatch;
import com.keevo.migration.domain.model.MigrationRecord;
import com.keevo.migration.domain.model.OwnerFigures;
import com.keevo.migration.domain.model.ReconciliationReport;
import com.keevo.migration.domain.model.SourceKind;
import com.keevo.migration.domain.port.in.BrefCutoverUseCase;
import com.keevo.migration.domain.port.in.BrefReconciliationUseCase;
import com.keevo.migration.domain.port.out.BrefOwnerDirectory;
import com.keevo.migration.domain.port.out.CutoverStore;
import com.keevo.migration.domain.port.out.MigrationRecordStore;
import com.keevo.migration.domain.service.BrefMappings;
import com.keevo.migration.domain.service.CutoverFigures;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Story 24.9 — the one-shot cutover command (ADR-10): every owner of a restored Bref database is imported (the 24.2–24.7
 * pipeline, untouched) and reconciled (24.8), then the super-admin gives the « go » (global activation) or rolls back.
 *
 * <p>Patterns: asynchronous coordinator (one background thread per run) feeding a bounded worker pool through a
 * semaphore — Producer/Consumer with back-pressure: memory O(page + parallelism), never the whole owner list; per-owner
 * isolation (one failure is recorded, the others go on); the 24.8 gate as the precondition of the activation command,
 * itself one set-based unit of work; per-owner compensation for the rollback (each owner independent, relaunchable).
 *
 * <p>Cost: the listing reads one indexed page per {@value #PAGE} owners; each owner costs its own import and
 * reconciliation (constant statements per table, no per-row query) plus 3 small updates of its cutover line; the
 * report is a constant number of aggregate queries; the activation is 3 statements whatever the number of owners;
 * the rollback is one deletion routine (schema drop) per imported company. Logs carry ids and counts only.
 */
@Service
public class BrefCutoverService implements BrefCutoverUseCase {

    private static final Logger log = LoggerFactory.getLogger(BrefCutoverService.class);

    static final int PAGE = 200;
    static final int DEFAULT_PARALLELISM = 4;
    static final int MAX_PARALLELISM = 8;
    /** A RUNNING / ROLLING_BACK cutover whose coordinator stopped beating for this long may be taken over (D7). */
    static final Duration STALE = Duration.ofMinutes(30);
    static final long HEARTBEAT_EVERY_MS = 10_000;
    static final int REASON_MIN = 10;
    static final String METRIC = "keevo.migration.cutover.owner";

    static final String OPEN = "Une bascule est déjà ouverte : relancez-la, donnez le « go » ou faites son retour "
            + "arrière avant d'en ouvrir une autre";
    static final String SOURCE_ONLY_DATABASE = "La bascule lit une base Bref restaurée : choisissez la source "
            + "« Base Bref »";
    static final String PARALLELISM_RANGE = "Le nombre de comptes importés en même temps va de 1 à " + MAX_PARALLELISM;
    static final String GO_PHRASE = "Tapez « " + GO_CONFIRMATION + " » pour confirmer le « go »";
    static final String ROLLBACK_PHRASE = "Tapez « " + ROLLBACK_CONFIRMATION + " » pour confirmer le retour arrière";
    static final String REASON_REQUIRED = "Chaque compte accepté demande un motif d'au moins " + REASON_MIN
            + " caractères (écart documenté par la répétition générale)";
    static final String NOT_IN_CUTOVER = "Un compte accepté ne fait pas partie de cette bascule";
    static final String BLOCKED_GO = "Activation refusée : %d compte(s) bloquent la bascule (écart, import bloqué ou "
            + "en échec). Corrigez-les ou acceptez-les un par un avec un motif.";
    static final String RUNNING = "La bascule est en cours : attendez la fin";
    static final String AFTER_GO = "Après le « go », plus de retour arrière automatique : corrigez sur Keevo (runbook)";
    static final String AFTER_GO_RUN = "Le « go » est donné : la bascule ne peut plus être relancée (un owner "
            + "retardataire s'importe seul, runbook)";
    static final String ROLLBACK_STARTED = "Un retour arrière a commencé : terminez-le (relancez-le), puis ouvrez "
            + "une nouvelle bascule";
    static final String ROLLED_BACK = "Retour arrière effectué : ouvrez une nouvelle bascule";
    static final String INTERRUPTED = "La bascule s'est arrêtée avant la fin : relancez-la";
    static final String ROLLBACK_INCOMPLETE = "Retour arrière incomplet : %d compte(s) à reprendre, relancez-le";
    static final String ROLLBACK_OWNER_FAILED = "Retour arrière impossible pour ce compte : relancez-le";
    static final String ROLLBACK_REFUSED = "Entreprise déjà active ou suspendue pour un autre motif : non supprimée";
    /** The migration records a rollback reads to compensate an owner. */
    static final List<String> ROLLBACK_TABLES = List.of(MigrationRecord.TENANTS, MigrationRecord.USERS,
            MigrationRecord.LINKED_USERS, MigrationRecord.AUTHENTICATORS, MigrationRecord.WEB_PUSH_SUBSCRIPTIONS);
    static final String OWNER_NOT_IN_CUTOVER = "Ce compte ne fait pas partie de cette bascule";
    static final String OWNER_NOTHING_TO_REMOVE = "Ce compte n'a rien à retirer : il est en attente, non repris, "
            + "activé ou déjà retiré";
    static final String OWNER_RESET = "Compte retiré et remis en attente : relancez la bascule pour le réimporter";
    static final String OWNER_RESET_FAILED = "Retour arrière du compte impossible : voir son motif, puis relancez";
    /** Lines whose company (or traces) one owner's rollback may remove. */
    static final Set<CutoverOwnerStatus> OWNER_REMOVABLE = EnumSet.of(CutoverOwnerStatus.RECONCILED,
            CutoverOwnerStatus.GAP, CutoverOwnerStatus.BLOCKED, CutoverOwnerStatus.FAILED);

    private final BrefOwnerImportService imports;
    private final BrefReconciliationUseCase reconciliation;
    private final BrefOwnerDirectory directory;
    private final CutoverStore store;
    private final MigrationRecordStore records;
    private final LegacyIdentityImportPort identity;
    private final TenantDeletionPort deletion;
    /** Story 24.6 item — the browser subscriptions the import added (an account the import linked keeps its own). */
    private final com.keevo.messaging.api.NotificationImportPort notifications;
    private final MigrationUnitOfWork uow;
    private final ObjectProvider<MeterRegistry> meters;
    private final Clock clock;
    /** Starts the coordinator of a run or a rollback (a daemon thread; tests may run it inline). */
    Consumer<Runnable> launcher = r -> Thread.ofPlatform().name("bref-cutover").daemon(true).start(r);

    public BrefCutoverService(BrefOwnerImportService imports, BrefReconciliationUseCase reconciliation,
                              BrefOwnerDirectory directory, CutoverStore store, MigrationRecordStore records,
                              LegacyIdentityImportPort identity, TenantDeletionPort deletion,
                              com.keevo.messaging.api.NotificationImportPort notifications, MigrationUnitOfWork uow,
                              ObjectProvider<MeterRegistry> meters, Clock clock) {
        this.imports = imports;
        this.reconciliation = reconciliation;
        this.directory = directory;
        this.store = store;
        this.records = records;
        this.identity = identity;
        this.deletion = deletion;
        this.notifications = notifications;
        this.uow = uow;
        this.meters = meters;
        this.clock = clock;
    }

    // ── commands ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Cutover start(String source, Integer parallelism, UUID actorId) {
        if (SourceKind.fromCode(source).orElse(null) != SourceKind.DATABASE) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, SOURCE_ONLY_DATABASE);
        }
        int threads = parallelism == null ? DEFAULT_PARALLELISM : parallelism;
        if (threads < 1 || threads > MAX_PARALLELISM) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, PARALLELISM_RANGE);
        }
        if (!directory.available()) {
            throw new DomainException(ErrorCode.MIGRATION_SOURCE_UNAVAILABLE, "Bref database not configured");
        }
        UUID id = store.create(SourceKind.DATABASE, threads, actorId)
                .orElseThrow(() -> DomainException.userFacing(ErrorCode.INVALID_STATE, OPEN));
        log.info("[ADMIN AUDIT] Bref cutover started: actor={} cutover={} parallelism={}", actorId, id, threads);
        launcher.accept(() -> run(id, actorId, threads));
        return cutover(id);
    }

    @Override
    public Cutover resume(UUID cutoverId, UUID actorId) {
        Cutover c = cutover(cutoverId);
        if (c.progress().stream().anyMatch(p -> CutoverOwnerStatus.ROLLED_BACK.name().equals(p.status()))) {
            // a rollback has started: re-running would leave its removed owners out of the « go » (review 24.9)
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, ROLLBACK_STARTED);
        }
        if (!store.transition(cutoverId, EnumSet.of(CutoverStatus.READY, CutoverStatus.FAILED, CutoverStatus.RUNNING),
                CutoverStatus.RUNNING, STALE, true)) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, c.status() == CutoverStatus.ACTIVATED
                    ? AFTER_GO_RUN : refusal(c.status()));
        }
        log.info("[ADMIN AUDIT] Bref cutover resumed: actor={} cutover={}", actorId, cutoverId);
        launcher.accept(() -> run(cutoverId, actorId, c.parallelism()));
        return cutover(cutoverId);
    }

    @Override
    public Cutover activate(UUID cutoverId, String confirmation, List<Acceptance> accepted, UUID actorId) {
        if (confirmation == null || !GO_CONFIRMATION.equals(confirmation.strip())) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, GO_PHRASE);
        }
        Cutover c = cutover(cutoverId);
        if (c.status() != CutoverStatus.READY && c.status() != CutoverStatus.ACTIVATED) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, refusal(c.status()));
        }
        long t0 = System.nanoTime();
        List<CutoverOwner> lines = store.ownerLines(cutoverId, EnumSet.of(CutoverOwnerStatus.NOT_MIGRATED,
                CutoverOwnerStatus.ROLLED_BACK, CutoverOwnerStatus.ACTIVATED));
        Map<UUID, CutoverOwner> byId = new LinkedHashMap<>();
        lines.forEach(l -> byId.put(l.brefOwnerId(), l));
        Map<UUID, String> reasons = acceptances(accepted, byId.keySet());

        // the 24.8 gate, plus any line the run did not reconcile (an owner FAILED before a new batch keeps an
        // older reconciled batch the gate would let through — review 24.9)
        Map<UUID, String> blocking = new LinkedHashMap<>(reconciliation.blockingOwners(byId.keySet()));
        for (CutoverOwner line : lines) {
            if (line.status() != CutoverOwnerStatus.RECONCILED) {
                blocking.putIfAbsent(line.brefOwnerId(), line.reason() == null ? line.status().label()
                        : line.reason());
            }
        }
        List<Map<String, Object>> refused = new ArrayList<>();
        blocking.forEach((owner, reason) -> {
            if (!reasons.containsKey(owner)) {
                CutoverOwner line = byId.get(owner);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("ownerId", owner);
                d.put("label", line == null ? null : line.label());
                d.put("reason", reason);
                refused.add(d);
            }
        });
        if (!refused.isEmpty()) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, BLOCKED_GO.formatted(refused.size()),
                    Map.of("blockingOwners", refused));
        }
        // the decision point: one conditional update (a rollback or a run started meanwhile wins — review 24.9)
        if (!store.transition(cutoverId, EnumSet.of(CutoverStatus.READY, CutoverStatus.ACTIVATED),
                CutoverStatus.ACTIVATED, STALE, false)) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, refusal(cutover(cutoverId).status()));
        }

        // clear owners, and accepted owners whose import completed (an accepted owner without it stays out — D2)
        Map<UUID, UUID> tenantOf = new LinkedHashMap<>();
        for (CutoverOwner line : lines) {
            boolean clear = !blocking.containsKey(line.brefOwnerId());
            boolean importedAndAccepted = reasons.containsKey(line.brefOwnerId())
                    && (line.status() == CutoverOwnerStatus.RECONCILED || line.status() == CutoverOwnerStatus.GAP);
            if ((clear || importedAndAccepted) && line.tenantId() != null) {
                tenantOf.put(line.brefOwnerId(), line.tenantId());
            }
        }
        int[] redated = new int[1];
        List<UUID> activatedOwners = new ArrayList<>();
        Set<UUID> activated = uow.platform(() -> {
            Set<UUID> done = identity.activateImportedTenants(tenantOf.values(), BrefMappings.SUSPENSION_REASON);
            tenantOf.forEach((owner, tenant) -> {
                if (done.contains(tenant)) {
                    activatedOwners.add(owner);
                }
            });
            // only the invitations of the companies activated now (review 24.9)
            List<UUID> invitations = new ArrayList<>();
            store.targets(activatedOwners, List.of(MigrationRecord.INVITATIONS)).values()
                    .forEach(t -> invitations.addAll(t.getOrDefault(MigrationRecord.INVITATIONS, List.of())));
            redated[0] = identity.redateImportedInvitations(invitations);
            return done;
        });
        store.ownersStatus(cutoverId, activatedOwners, CutoverOwnerStatus.ACTIVATED, null);
        Instant now = clock.instant();
        Set<UUID> alreadySigned = new java.util.HashSet<>();
        c.acceptedOwners().forEach(a -> alreadySigned.add(a.ownerId()));
        List<Cutover.AcceptedOwner> signed = reasons.entrySet().stream()
                .filter(e -> blocking.containsKey(e.getKey()) && !alreadySigned.contains(e.getKey()))
                .map(e -> new Cutover.AcceptedOwner(e.getKey(), e.getValue(), actorId, now)).toList();
        store.activated(cutoverId, actorId, signed);
        CutoverReport.Durations previous = c.report().durations();
        store.saveReport(cutoverId, report(cutoverId, c, previous.listingMs(), previous.runWallMs(), ms(t0),
                previous.rollbackMs(), c.report().sourceIndexes(), c.acceptedOwners().size() + signed.size()));
        log.info("[ADMIN AUDIT] Bref cutover go: actor={} cutover={} companiesActivated={} notActivated={} "
                        + "invitationsRedated={} acceptedOwners={}", actorId, cutoverId, activated.size(),
                tenantOf.size() - activated.size(), redated[0], signed.size());
        return cutover(cutoverId);
    }

    @Override
    public Cutover rollback(UUID cutoverId, String confirmation, UUID actorId) {
        if (confirmation == null || !ROLLBACK_CONFIRMATION.equals(confirmation.strip())) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, ROLLBACK_PHRASE);
        }
        Cutover c = cutover(cutoverId);
        if (!store.transition(cutoverId, EnumSet.of(CutoverStatus.READY, CutoverStatus.FAILED,
                CutoverStatus.ROLLING_BACK), CutoverStatus.ROLLING_BACK, STALE, false)) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, refusal(c.status()));
        }
        log.info("[ADMIN AUDIT] Bref cutover rollback started: actor={} cutover={}", actorId, cutoverId);
        launcher.accept(() -> rollbackRun(cutoverId, actorId));
        return cutover(cutoverId);
    }

    @Override
    public Cutover rollbackOwner(UUID cutoverId, UUID ownerId, String confirmation, UUID actorId) {
        if (confirmation == null || !ROLLBACK_CONFIRMATION.equals(confirmation.strip())) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, ROLLBACK_PHRASE);
        }
        Cutover c = cutover(cutoverId);
        if (c.status() == CutoverStatus.READY || c.status() == CutoverStatus.FAILED) {
            if (c.progress().stream().anyMatch(p -> CutoverOwnerStatus.ROLLED_BACK.name().equals(p.status()))) {
                throw DomainException.userFacing(ErrorCode.INVALID_STATE, ROLLBACK_STARTED);
            }
            CutoverOwner line = ownerId == null ? null : store.owner(cutoverId, ownerId).orElse(null);
            if (line == null) {
                throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, OWNER_NOT_IN_CUTOVER);
            }
            if (!OWNER_REMOVABLE.contains(line.status())) {
                throw DomainException.userFacing(ErrorCode.INVALID_STATE, OWNER_NOTHING_TO_REMOVE);
            }
        }
        // the lock: no coordinator, global rollback or « go » can start while this owner is removed (review 24.9
        // pattern: one conditional update); RUNNING so that a crash is taken over by a relaunch after STALE
        if (!store.transition(cutoverId, EnumSet.of(CutoverStatus.READY, CutoverStatus.FAILED), CutoverStatus.RUNNING,
                STALE, false)) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, c.status() == CutoverStatus.ACTIVATED
                    ? AFTER_GO : refusal(cutover(cutoverId).status()));
        }
        long t0 = System.nanoTime();
        DomainException refused = null; // a refusal found under the lock: nothing touched, previous state restored
        String problem = null;          // a removal that failed: line FAILED with its reason, cutover FAILED
        try {
            Cutover locked = cutover(cutoverId);
            CutoverOwner line = store.owner(cutoverId, ownerId).orElse(null); // re-read under the lock
            if (locked.progress().stream().anyMatch(p -> CutoverOwnerStatus.ROLLED_BACK.name().equals(p.status()))) {
                refused = DomainException.userFacing(ErrorCode.INVALID_STATE, ROLLBACK_STARTED);
            } else if (line == null) {
                refused = DomainException.userFacing(ErrorCode.VALIDATION_FAILED, OWNER_NOT_IN_CUTOVER);
            } else if (!OWNER_REMOVABLE.contains(line.status())) {
                refused = DomainException.userFacing(ErrorCode.INVALID_STATE, OWNER_NOTHING_TO_REMOVE);
            } else {
                problem = removeOwner(cutoverId, ownerId, store.targets(List.of(ownerId), ROLLBACK_TABLES)
                        .getOrDefault(ownerId, Map.of()), false);
            }
        } catch (RuntimeException e) {
            problem = ROLLBACK_OWNER_FAILED;
            log.warn("[ADMIN AUDIT] Bref cutover owner rollback stopped: cutover={} owner={} error={}", cutoverId,
                    ownerId, e.getClass().getSimpleName());
        } finally {
            releaseAfterOwnerRollback(cutoverId, ownerId, c, refused != null, problem);
        }
        log.info("[ADMIN AUDIT] Bref cutover owner rollback: actor={} cutover={} owner={} outcome={} ms={}", actorId,
                cutoverId, ownerId, refused != null ? "refused" : problem == null ? "pending again" : "failed",
                ms(t0));
        if (refused != null) {
            throw refused;
        }
        if (problem != null) {
            throw DomainException.userFacing(ErrorCode.INVALID_STATE, problem);
        }
        return cutover(cutoverId);
    }

    /**
     * Releases the lock of {@link #rollbackOwner}: a refusal found under the lock restores the previous status and label
     * (nothing was touched); otherwise the cutover is FAILED (a line is pending again, or could not be removed: the next
     * action is a relaunch). Never throws: a failure here leaves the cutover RUNNING until {@link #STALE}, logged.
     */
    private void releaseAfterOwnerRollback(UUID cutoverId, UUID ownerId, Cutover before, boolean refused,
                                           String problem) {
        if (problem != null) {
            try {
                store.ownersStatus(cutoverId, List.of(ownerId), CutoverOwnerStatus.FAILED, problem);
            } catch (RuntimeException e) {
                log.warn("[ADMIN AUDIT] Bref cutover owner line not marked failed: cutover={} owner={} error={}",
                        cutoverId, ownerId, e.getClass().getSimpleName());
            }
        }
        try {
            Cutover now = cutover(cutoverId);
            CutoverReport.Durations previous = now.report().durations();
            CutoverReport report = report(cutoverId, now, previous.listingMs(), previous.runWallMs(),
                    previous.activationMs(), previous.rollbackMs(), now.report().sourceIndexes(),
                    now.acceptedOwners().size());
            if (refused) {
                store.finish(cutoverId, before.status(), before.errorLabel(), report);
            } else {
                store.finish(cutoverId, CutoverStatus.FAILED, problem == null ? OWNER_RESET : OWNER_RESET_FAILED,
                        report);
            }
        } catch (RuntimeException e) {
            log.error("[ADMIN AUDIT] Bref cutover {} could not be released after an owner rollback ({}): relaunch it "
                    + "after {} min", cutoverId, e.getClass().getSimpleName(), STALE.toMinutes());
        }
    }

    // ── reads ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Cutover cutover(UUID cutoverId) {
        return store.find(cutoverId).orElseThrow(() -> new DomainException(ErrorCode.MIGRATION_CUTOVER_NOT_FOUND,
                "Cutover not found"));
    }

    @Override
    public List<Cutover> cutovers(UUID after, int limit) {
        return store.list(after, clamp(limit));
    }

    @Override
    public List<CutoverOwner> owners(UUID cutoverId, CutoverOwnerStatus status, Integer afterPosition, int limit) {
        cutover(cutoverId);
        return store.owners(cutoverId, status, afterPosition == null ? 0 : afterPosition, clamp(limit));
    }

    @Override
    public List<MigrationBatch> batches(UUID after, int limit) {
        return records.listBatches(after, clamp(limit));
    }

    // ── the run ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** Lists the owners of the source (keyset), then imports and reconciles every owner to process. Never throws. */
    void run(UUID cutoverId, UUID actorId, int parallelism) {
        MDC.put("cutoverId", cutoverId.toString());
        long wall = System.nanoTime();
        Long listingMs = null;
        CutoverStatus end = CutoverStatus.READY;
        String error = null;
        CutoverReport.IndexProbe probe = probe();
        AtomicLong lastBeat = new AtomicLong(System.currentTimeMillis());
        ExecutorService pool = Executors.newFixedThreadPool(parallelism,
                Thread.ofPlatform().name("bref-cutover-owner-", 0).daemon(true).factory());
        try {
            long listing = System.nanoTime();
            UUID after = null;
            int listed = 0;
            while (true) {
                List<UUID> page = directory.owners(after, PAGE);
                listed += store.addOwners(cutoverId, page);
                if (page.size() < PAGE) {
                    break;
                }
                after = page.get(page.size() - 1);
                beat(cutoverId, lastBeat);
            }
            listingMs = ms(listing);
            log.info("[ADMIN AUDIT] Bref cutover listing: cutover={} ownersAdded={} listingMs={} helperIndexes={}",
                    cutoverId, listed, listingMs, probe == null ? "?" : probe.present() + "/" + probe.expected());
            Semaphore permits = new Semaphore(parallelism * 2); // back-pressure: at most 2 owners queued per thread
            int position = 0;
            while (true) {
                List<CutoverOwner> page = store.ownersToProcess(cutoverId, position, PAGE);
                if (page.isEmpty()) {
                    break;
                }
                for (CutoverOwner owner : page) {
                    while (!permits.tryAcquire(1, TimeUnit.MINUTES)) {
                        store.heartbeat(cutoverId); // long owners: the coordinator stays visibly alive
                    }
                    pool.execute(() -> {
                        try {
                            process(cutoverId, owner, actorId);
                            beat(cutoverId, lastBeat);
                        } catch (Error e) {
                            log.error("[ADMIN AUDIT] Bref cutover owner stopped: cutover={} owner={} error={}",
                                    cutoverId, owner.brefOwnerId(), e.getClass().getSimpleName());
                        } finally {
                            permits.release();
                        }
                    });
                }
                position = page.get(page.size() - 1).position();
            }
            pool.shutdown();
            while (!pool.awaitTermination(1, TimeUnit.MINUTES)) {
                store.heartbeat(cutoverId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            end = CutoverStatus.FAILED;
            error = INTERRUPTED;
        } catch (RuntimeException e) {
            log.warn("[ADMIN AUDIT] Bref cutover stopped: cutover={} error={}", cutoverId,
                    e.getClass().getSimpleName());
            end = CutoverStatus.FAILED;
            error = e instanceof DomainException d && d.getErrorCode() == ErrorCode.MIGRATION_SOURCE_UNAVAILABLE
                    ? "Base Bref restaurée inaccessible : relancez la bascule quand elle l'est" : INTERRUPTED;
        } finally {
            pool.shutdownNow();
            try {
                // never close the run while a worker still writes (a relaunch would race it — review 24.9)
                while (!pool.awaitTermination(1, TimeUnit.MINUTES)) {
                    store.heartbeat(cutoverId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            Cutover c = cutover(cutoverId);
            if (end == CutoverStatus.READY && c.progress().stream()
                    .anyMatch(p -> CutoverOwnerStatus.PENDING.name().equals(p.status()))) {
                end = CutoverStatus.FAILED; // an owner was never processed: not ready for the decision
                error = INTERRUPTED;
            }
            CutoverReport.Durations previous = c.report().durations();
            CutoverReport report = report(cutoverId, c, listingMs, ms(wall), previous.activationMs(),
                    previous.rollbackMs(), probe, c.acceptedOwners().size());
            store.finish(cutoverId, end, error, report);
            log.info("[ADMIN AUDIT] Bref cutover run finished: cutover={} status={} owners={} wallMs={}", cutoverId,
                    end, report.ownersTotal(), ms(wall));
        } catch (RuntimeException e) {
            log.error("[ADMIN AUDIT] Bref cutover {} could not be closed ({})", cutoverId,
                    e.getClass().getSimpleName());
        } finally {
            MDC.remove("cutoverId");
        }
    }

    /** One owner: import, then reconciliation of a completed import; the outcome is stored, nothing is thrown. */
    void process(UUID cutoverId, CutoverOwner owner, UUID actorId) {
        MDC.put("cutoverId", cutoverId.toString());
        UUID ownerId = owner.brefOwnerId();
        try {
            store.ownerStarted(cutoverId, ownerId, clock.instant());
            long t0 = System.nanoTime();
            MigrationBatch batch;
            try {
                batch = imports.importOwner(ownerId, SourceKind.DATABASE.name(), actorId);
            } catch (RuntimeException e) {
                int importMs = (int) ms(t0);
                String reason = CutoverFigures.failure(e);
                store.ownerDone(cutoverId, ownerId, CutoverOwnerStatus.FAILED, null, null, reason, importMs, null,
                        OwnerFigures.NONE);
                record("import", "failed", importMs);
                log.warn("[ADMIN AUDIT] Bref cutover owner failed before import: cutover={} owner={} error={}",
                        cutoverId, ownerId, e.getClass().getSimpleName());
                return;
            }
            int importMs = (int) ms(t0);
            MDC.put("migrationBatchId", batch.id().toString());
            record("import", batch.status().name().toLowerCase(java.util.Locale.ROOT), importMs);
            ReconciliationReport report = null;
            Integer reconcileMs = null;
            if (batch.status() == BatchStatus.COMPLETED) {
                long t1 = System.nanoTime();
                try {
                    report = reconciliation.reconcile(batch.id(), actorId);
                } catch (RuntimeException e) {
                    log.warn("[ADMIN AUDIT] Bref cutover reconciliation not stored: cutover={} migrationBatchId={} "
                            + "owner={} error={}", cutoverId, batch.id(), ownerId, e.getClass().getSimpleName());
                }
                reconcileMs = (int) ms(t1);
                record("reconcile", report == null ? "failed" : report.status().name().toLowerCase(
                        java.util.Locale.ROOT), reconcileMs);
            }
            CutoverFigures.Outcome outcome = CutoverFigures.outcome(batch, report);
            OwnerFigures figures = CutoverFigures.figures(report).withAnomalies(CutoverFigures.anomalies(batch));
            store.ownerDone(cutoverId, ownerId, outcome.status(), batch.id(), batch.tenantId(), outcome.reason(),
                    importMs, reconcileMs, figures);
            log.info("[ADMIN AUDIT] Bref cutover owner: cutover={} migrationBatchId={} owner={} tenant={} status={} "
                            + "importMs={} reconcileMs={}", cutoverId, batch.id(), ownerId, batch.tenantId(),
                    outcome.status(), importMs, reconcileMs);
        } catch (RuntimeException e) {
            log.error("[ADMIN AUDIT] Bref cutover owner not recorded: cutover={} owner={} error={}", cutoverId,
                    ownerId, e.getClass().getSimpleName());
        } finally {
            MDC.remove("migrationBatchId");
            MDC.remove("cutoverId");
        }
    }

    // ── the rollback ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Removes every company of the cutover, owner by owner (each independent and relaunchable). Never throws. */
    void rollbackRun(UUID cutoverId, UUID actorId) {
        MDC.put("cutoverId", cutoverId.toString());
        long t0 = System.nanoTime();
        int failures = 0;
        int removed = 0;
        AtomicLong lastBeat = new AtomicLong(System.currentTimeMillis());
        try {
            int position = 0;
            while (true) {
                List<CutoverOwner> page = store.owners(cutoverId, null, position, PAGE);
                if (page.isEmpty()) {
                    break;
                }
                List<UUID> ids = page.stream().filter(o -> o.status() != CutoverOwnerStatus.ROLLED_BACK)
                        .map(CutoverOwner::brefOwnerId).toList();
                Map<UUID, Map<String, List<UUID>>> targets = store.targets(ids, ROLLBACK_TABLES); // one query per page
                for (CutoverOwner owner : page) {
                    if (owner.status() == CutoverOwnerStatus.ROLLED_BACK) {
                        continue;
                    }
                    String problem = removeOwner(cutoverId, owner.brefOwnerId(),
                            targets.getOrDefault(owner.brefOwnerId(), Map.of()), true);
                    if (problem == null) {
                        removed++;
                    } else {
                        failures++;
                        store.ownersStatus(cutoverId, List.of(owner.brefOwnerId()), CutoverOwnerStatus.FAILED,
                                problem);
                    }
                    beat(cutoverId, lastBeat);
                }
                position = page.get(page.size() - 1).position();
            }
        } catch (RuntimeException e) {
            failures++;
            log.warn("[ADMIN AUDIT] Bref cutover rollback stopped: cutover={} error={}", cutoverId,
                    e.getClass().getSimpleName());
        }
        try {
            Cutover c = cutover(cutoverId);
            CutoverReport.Durations previous = c.report().durations();
            CutoverReport report = report(cutoverId, c, previous.listingMs(), previous.runWallMs(),
                    previous.activationMs(), ms(t0), c.report().sourceIndexes(), c.acceptedOwners().size());
            if (failures == 0) {
                store.finish(cutoverId, CutoverStatus.ROLLED_BACK, null, report);
                store.rolledBack(cutoverId, actorId);
            } else {
                store.finish(cutoverId, CutoverStatus.FAILED, ROLLBACK_INCOMPLETE.formatted(failures), report);
            }
            log.info("[ADMIN AUDIT] Bref cutover rollback finished: actor={} cutover={} ownersRemoved={} failures={} "
                    + "rollbackMs={}", actorId, cutoverId, removed, failures, ms(t0));
        } catch (RuntimeException e) {
            log.error("[ADMIN AUDIT] Bref cutover {} rollback could not be closed ({})", cutoverId,
                    e.getClass().getSimpleName());
        } finally {
            MDC.remove("cutoverId");
        }
    }

    /**
     * One owner's compensation: its imported company (only when still suspended by the import), the accounts the import
     * created (never the linked ones), the identities it added, then its migration records and batches.
     *
     * @param global {@code true} for the rollback of the whole cutover (line ROLLED_BACK), {@code false} for one owner
     *               (line back to PENDING, imported again by the next run — 24.13)
     * @return {@code null} when done, else the French reason it is not
     */
    private String removeOwner(UUID cutoverId, UUID ownerId, Map<String, List<UUID>> targets, boolean global) {
        try {
            List<UUID> linked = targets.getOrDefault(MigrationRecord.LINKED_USERS, List.of());
            List<UUID> authenticators = targets.getOrDefault(MigrationRecord.AUTHENTICATORS, List.of());
            for (UUID tenantId : targets.getOrDefault(MigrationRecord.TENANTS, List.of())) {
                RemovalPreparation prep = uow.platform(() -> identity.prepareImportedTenantRemoval(tenantId,
                        BrefMappings.SUSPENSION_REASON, linked, authenticators));
                if (prep == RemovalPreparation.REFUSED) {
                    return ROLLBACK_REFUSED;
                }
                if (prep == RemovalPreparation.READY && !deletion.forgetImportedNow(tenantId)) {
                    return ROLLBACK_OWNER_FAILED; // no longer DELETION_PENDING: keep its records (review 24.9)
                }
            }
            List<UUID> webPush = targets.getOrDefault(MigrationRecord.WEB_PUSH_SUBSCRIPTIONS, List.of());
            if (!webPush.isEmpty()) {
                uow.platform(() -> notifications.deleteImportedWebPushSubscriptions(webPush));
            }
            Set<UUID> created = new LinkedHashSet<>(targets.getOrDefault(MigrationRecord.USERS, List.of()));
            created.removeAll(new HashSet<>(linked)); // never an account that existed before the import
            if (!created.isEmpty()) {
                uow.platform(() -> identity.deleteOrphanImportedAccounts(created));
            }
            store.purgeOwners(List.of(ownerId));
            if (global) {
                store.ownersStatus(cutoverId, List.of(ownerId), CutoverOwnerStatus.ROLLED_BACK, null);
            } else {
                store.resetOwner(cutoverId, ownerId);
            }
            log.info("[ADMIN AUDIT] Bref cutover owner rolled back: cutover={} owner={} companies={} "
                    + "createdAccounts={}", cutoverId, ownerId, targets.getOrDefault(MigrationRecord.TENANTS,
                    List.of()).size(), created.size());
            return null;
        } catch (RuntimeException e) {
            log.warn("[ADMIN AUDIT] Bref cutover owner rollback failed: cutover={} owner={} error={}", cutoverId,
                    ownerId, e.getClass().getSimpleName());
            return ROLLBACK_OWNER_FAILED;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private CutoverReport report(UUID cutoverId, Cutover c, Long listingMs, Long runWallMs, Long activationMs,
                                 Long rollbackMs, CutoverReport.IndexProbe probe, int accepted) {
        Long total = Duration.between(c.startedAt(), clock.instant()).toMillis();
        CutoverReport.Durations stages = new CutoverReport.Durations(listingMs, runWallMs, null, null, null, null,
                null, activationMs, rollbackMs, total);
        return store.aggregate(cutoverId, stages, probe, accepted);
    }

    /** Accepted owners → their documented reason (each in the cutover, each with a reason of 10 characters or more). */
    private static Map<UUID, String> acceptances(List<Acceptance> accepted, Set<UUID> owners) {
        Map<UUID, String> reasons = new LinkedHashMap<>();
        for (Acceptance a : accepted == null ? List.<Acceptance>of() : accepted) {
            if (a == null || a.ownerId() == null) {
                throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, NOT_IN_CUTOVER);
            }
            String reason = a.reason() == null ? "" : a.reason().strip();
            if (reason.length() < REASON_MIN || reason.length() > 300) {
                throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, REASON_REQUIRED);
            }
            if (!owners.contains(a.ownerId())) {
                throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, NOT_IN_CUTOVER);
            }
            reasons.put(a.ownerId(), reason);
        }
        return reasons;
    }

    /** The French reason a command is refused in this state. */
    static String refusal(CutoverStatus status) {
        return switch (status) {
            case RUNNING, ROLLING_BACK -> RUNNING;
            case ACTIVATED -> AFTER_GO;
            case ROLLED_BACK -> ROLLED_BACK;
            case FAILED -> INTERRUPTED;
            case READY -> "Action impossible dans l'état actuel de la bascule";
        };
    }

    private CutoverReport.IndexProbe probe() {
        try {
            return directory.helperIndexes();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void beat(UUID cutoverId, AtomicLong lastBeat) {
        long now = System.currentTimeMillis();
        long last = lastBeat.get();
        if (now - last >= HEARTBEAT_EVERY_MS && lastBeat.compareAndSet(last, now)) {
            try {
                store.heartbeat(cutoverId);
            } catch (RuntimeException e) {
                log.warn("Bref cutover {} heartbeat not stored ({})", cutoverId, e.getClass().getSimpleName());
            }
        }
    }

    private void record(String stage, String outcome, long millis) {
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            Timer.builder(METRIC).tag("stage", stage).tag("outcome", outcome).register(registry)
                    .record(Duration.ofMillis(millis));
        }
    }

    private static long ms(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(PAGE, limit));
    }
}
