package com.keevo.sync.sync.application.service;

import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.identity.api.Role;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.application.strategy.LastWriteWinsStrategy;
import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.PullScope;
import com.keevo.shared.application.port.sync.SyncOperationHandler;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import com.keevo.sync.sync.domain.model.UserSyncState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.*;

@Service
public class SyncPushService implements SyncUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncPushService.class);

    private final SyncOperationHandlerRegistry handlerRegistry;
    private final SyncOperationsLogRepository logRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final List<DeltaEntityProvider> deltaProviders;
    private final ConflictStrategyRegistry conflictStrategyRegistry;
    private final SyncConflictsLogRepository conflictsLogRepository;
    private final SyncErrorLogRepository syncErrorLogRepository;
    private final ModuleRegistry moduleRegistry;
    private final UserSyncStateRepository userSyncStateRepository;
    /** Story 20.1 — merge redirection of pushed product ids ({@code null}: none, unit tests). */
    private final ProductQueryPort productQuery;

    /** Without the merge redirection (Story 20.1) — unit tests. */
    public SyncPushService(SyncOperationHandlerRegistry handlerRegistry,
                           SyncOperationsLogRepository logRepository,
                           ApplicationEventPublisher eventPublisher,
                           PlatformTransactionManager transactionManager,
                           List<DeltaEntityProvider> deltaProviders,
                           ConflictStrategyRegistry conflictStrategyRegistry,
                           SyncConflictsLogRepository conflictsLogRepository,
                           SyncErrorLogRepository syncErrorLogRepository,
                           ModuleRegistry moduleRegistry,
                           UserSyncStateRepository userSyncStateRepository) {
        this(handlerRegistry, logRepository, eventPublisher, transactionManager, deltaProviders,
                conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository, moduleRegistry,
                userSyncStateRepository, null);
    }

    @Autowired
    public SyncPushService(SyncOperationHandlerRegistry handlerRegistry,
                           SyncOperationsLogRepository logRepository,
                           ApplicationEventPublisher eventPublisher,
                           PlatformTransactionManager transactionManager,
                           List<DeltaEntityProvider> deltaProviders,
                           ConflictStrategyRegistry conflictStrategyRegistry,
                           SyncConflictsLogRepository conflictsLogRepository,
                           SyncErrorLogRepository syncErrorLogRepository,
                           ModuleRegistry moduleRegistry,
                           UserSyncStateRepository userSyncStateRepository,
                           ProductQueryPort productQuery) {
        this.productQuery = productQuery;
        this.handlerRegistry = handlerRegistry;
        this.logRepository = logRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.deltaProviders = deltaProviders;
        this.conflictStrategyRegistry = conflictStrategyRegistry;
        this.conflictsLogRepository = conflictsLogRepository;
        this.syncErrorLogRepository = syncErrorLogRepository;
        this.moduleRegistry = moduleRegistry;
        this.userSyncStateRepository = userSyncStateRepository;
    }

    /**
     * Story 12.7 / 13.5 — the tenant's module state, read once per push batch (before the loop): the effective state
     * of every installed module, or, for an unknown / deleted tenant, the reason every (non-replayed) operation is
     * rejected with ({@code TENANT_NOT_FOUND}, transient on the device).
     */
    private record ModuleGate(Map<String, ModuleState> states, String tenantRejection) {
        static final ModuleGate NONE = new ModuleGate(Map.of(), null);

        ModuleState stateOf(String moduleId) {
            ModuleState state = moduleId != null ? states.get(moduleId) : null;
            return state != null ? state : ModuleState.DISABLED;
        }
    }

    /**
     * @throws DomainException {@code SERVICE_UNAVAILABLE} (HTTP 503) when the module state cannot be read: the
     *         whole batch is answered 503, nothing is processed or recorded, the device keeps its queue and retries
     */
    private ModuleGate readModuleGate(PushBatchCommand command) {
        try {
            if (!moduleRegistry.isKnownTenant(command.tenantId())) {
                log.warn("[Sync] Push for a tenant that is not registered — every operation is rejected as "
                        + "TENANT_NOT_FOUND");
                return new ModuleGate(Map.of(), ErrorCode.TENANT_NOT_FOUND.name());
            }
            Map<String, ModuleState> states = moduleRegistry.moduleStates(command.tenantId());
            return new ModuleGate(states != null ? states : Map.of(), null);
        } catch (Exception e) {
            log.error("[Sync] Could not read the tenant's active modules — push answered 503", e);
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Service momentanément indisponible, réessayez dans un instant");
        }
    }

    /**
     * Story 12.7 — refuses an operation whose module is not active for the tenant ({@code MODULE_INACTIVE}),
     * or every operation of an unknown tenant ({@code TENANT_NOT_FOUND}). Story 13.5 — a module in its read-only
     * grace (subscription expired) refuses the operation with {@code MODULE_READ_ONLY}: every sync operation is a
     * write. Both reasons are held on the device (data kept, retried automatically). Runs after the
     * duplicate/replay detection and before anything else touches the operation. {@code null} = proceed
     * (an unknown operation type keeps {@code UNKNOWN_OPERATION_TYPE}, decided further down).
     */
    private SyncOperationResult refuseByModule(SyncOperation operation, Optional<SyncOperationHandler> handler,
                                               ModuleGate gate) {
        if (gate.tenantRejection() != null) {
            return new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED, null,
                    gate.tenantRejection());
        }
        if (handler.isEmpty()) {
            return null;
        }
        String moduleId = handler.get().moduleId(); // never null for a registered handler (boot check)
        ModuleState state = gate.stateOf(moduleId);
        if (state == ModuleState.ENABLED) {
            return null;
        }
        ErrorCode reason = state == ModuleState.READ_ONLY ? ErrorCode.MODULE_READ_ONLY : ErrorCode.MODULE_INACTIVE;
        log.info("[Sync] Operation {} ({}) refused: module {} is {} for the tenant",
                operation.operationId(), operation.operationType(), moduleId,
                state == ModuleState.READ_ONLY ? "read-only" : "not active");
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED, null, reason.name());
    }

    /**
     * Creations other operations of the same batch may reference, hoisted ahead of everything
     * else (tier = index). Categories/stores/contacts first, then products and employees (which
     * reference categories/stores), then inventory sessions (which reference both).
     * Every other operation keeps clientTimestamp order: e.g. STOCK_ADJUST sets an absolute
     * quantity and must not jump ahead of an earlier sale on the same product.
     */
    private static final List<Set<String>> HOISTED_CREATION_TIERS = List.of(
            Set.of("CREATE_CATEGORY", "CREATE_STORE", "CREATE_CLIENT", "CREATE_SUPPLIER"),
            Set.of("CREATE_PRODUCT", "CREATE_DRAFT_PRODUCT", "CREATE_EMPLOYEE"),
            Set.of("CREATE_INVENTORY_SESSION"));

    private int hoistTier(SyncOperation op) {
        for (int i = 0; i < HOISTED_CREATION_TIERS.size(); i++) {
            if (HOISTED_CREATION_TIERS.get(i).contains(op.operationType())) return i;
        }
        return HOISTED_CREATION_TIERS.size();
    }

    /** Hoisted creations first (by tier), then chronological order. The sort is stable. */
    private List<SyncOperation> dependencyOrder(List<SyncOperation> operations) {
        return operations.stream()
                .sorted(Comparator.comparingInt(this::hoistTier)
                        .thenComparing(SyncOperation::clientTimestamp,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public SyncBatchResult pushBatch(PushBatchCommand command) {
        // Story 19.5 — a VIEWER never writes: one role check per batch (role resolved by the push endpoint), every
        // operation REJECTED/FORBIDDEN, no handler called, no log row written.
        if (Role.VIEWER.name().equals(command.actorRole())) {
            log.info("[Sync] Push of {} operation(s) by a VIEWER refused (read-only role)",
                    command.operations().size());
            return new SyncBatchResult(Instant.now(), command.operations().stream()
                    .map(op -> new SyncOperationResult(op.operationId(), SyncOperationStatus.REJECTED, null,
                            ErrorCode.FORBIDDEN.name()))
                    .toList());
        }
        List<SyncOperation> sorted = dependencyOrder(command.operations());

        List<SyncOperationResult> results = new ArrayList<>();
        // Story 12.7 — one registry read per batch (request-cached as well), not one per operation.
        ModuleGate moduleGate = sorted.isEmpty() ? ModuleGate.NONE : readModuleGate(command);
        // Story 20.1 — one read per batch: the merge targets of every product id the batch references
        Map<String, String> productMerges = readProductMerges(command, sorted);

        for (SyncOperation operation : sorted) {
            // Each operation runs in its own transaction — one failure does NOT roll back others (AC3)
            SyncOperationResult result;
            // Keeps what the handler decided even if the commit later fails, so the real
            // rejection reason (e.g. PRODUCT_NOT_FOUND) is not lost behind a generic one.
            var handlerOutcome = new java.util.concurrent.atomic.AtomicReference<SyncOperationResult>();
            try {
                result = transactionTemplate.execute(status -> {
                    SyncOperationResult opResult = processOperation(operation, command, moduleGate, productMerges);
                    handlerOutcome.set(opResult);

                    // Skip saving the log entry for DUPLICATE operations — the entry already exists
                    // and attempting to save it again would cause a primary key constraint violation.
                    if (opResult.status() != SyncOperationStatus.DUPLICATE) {
                        logRepository.save(new SyncOperationsLogEntry(
                                operation.operationId(),
                                operation.operationType(),
                                operation.entityId(),
                                opResult.status(),
                                opResult.reason(),
                                Instant.now(),
                                operation.clientTimestamp(),
                                command.deviceId()));
                    }

                    return opResult;
                });
            } catch (UnexpectedRollbackException e) {
                // A @Transactional use case (e.g. CloseDayService) threw a RuntimeException,
                // which caused Spring AOP to mark the transaction rollback-only before
                // AbstractSyncOperationHandler could map it to REJECTED.
                // The data operation was rolled back — record REJECTED in a fresh transaction.
                log.warn("[Sync] TX rolled back for operation {} ({}): {}",
                        operation.operationId(), operation.operationType(), e.getMessage());
                // A @Transactional use case that throws a DomainException (PRODUCT_NOT_FOUND, ...)
                // marks the shared transaction rollback-only even though the handler already mapped
                // it to REJECTED with the precise reason — keep that reason.
                var decided = handlerOutcome.get();
                boolean keepDecided = decided != null && decided.status() == SyncOperationStatus.REJECTED
                        && decided.reason() != null;
                String reason = keepDecided ? decided.reason() : "TRANSACTION_ROLLED_BACK";
                // 16.14 — keep the rejection details too (PLAN_LIMIT_EXCEEDED: limit / current usage)
                result = rejectAfterRollback(operation, reason, keepDecided ? decided.conflictData() : null);
            } catch (DataIntegrityViolationException e) {
                // A DB constraint (e.g. uq_products_name) fires at flush/commit time, i.e. AFTER the
                // handler returned, so AbstractSyncOperationHandler never sees it. Without this catch
                // it escapes pushBatch → HTTP 500 for the WHOLE batch, the op is never logged, and
                // the device replays the same poisoned batch forever. Reject only this operation.
                String reason = isProductNameViolation(e)
                        ? "PRODUCT_NAME_ALREADY_EXISTS" : "DATA_INTEGRITY_VIOLATION";
                log.warn("[Sync] Constraint violation for operation {} ({}): {}",
                        operation.operationId(), operation.operationType(), reason);
                result = rejectAfterRollback(operation, reason);
            } catch (RuntimeException e) {
                // Story 12.7 (live test) — anything else escaping the per-operation transaction, e.g. a
                // JpaSystemException "current transaction is aborted" when the handler already hit a constraint
                // (the PostgreSQL transaction is dead, so saving the log row fails too). Rejecting only this
                // operation keeps the batch answering 200; otherwise the device replays it forever.
                var decided = handlerOutcome.get();
                String reason;
                Map<String, Object> details = null;
                if (decided != null && decided.status() == SyncOperationStatus.REJECTED && decided.reason() != null
                        && !ErrorCode.INTERNAL_ERROR.name().equals(decided.reason())) {
                    reason = decided.reason();
                    details = decided.conflictData();
                } else if (e instanceof DataAccessException) {
                    reason = "DATA_INTEGRITY_VIOLATION";
                } else {
                    reason = ErrorCode.INTERNAL_ERROR.name();
                }
                log.error("[Sync] Operation {} ({}) failed in its transaction — rejected as {}",
                        operation.operationId(), operation.operationType(), reason, e);
                result = rejectAfterRollback(operation, reason, details);
            }

            results.add(result);

            // Story 5.5 — AC5: persist REJECTED payloads to sync_error_log for zero data loss (FR73).
            // CRITICAL: outside the per-operation transaction — error log save failure must NOT affect push processing.
            if (result != null && result.status() == SyncOperationStatus.REJECTED) {
                try {
                    syncErrorLogRepository.save(new SyncErrorLogEntry(
                            UUID.randomUUID(),
                            operation.operationId(),
                            operation.operationType(),
                            operation.entityId(),
                            operation.payload(),
                            result.reason(),
                            operation.clientTimestamp(),
                            Instant.now()));
                } catch (Exception e) {
                    log.error("Failed to save sync error log for operation {}: {}",
                            operation.operationId(), e.getMessage());
                }
            }

            // Event published outside the transaction — non-transactional side effect
            eventPublisher.publishEvent(new SyncOperationProcessedEvent(
                    operation.operationId(),
                    operation.operationType(),
                    operation.entityId(),
                    result.status(),
                    command.tenantId(),
                    Instant.now()));
        }

        return new SyncBatchResult(Instant.now(), results);
    }

    /**
     * Operation types whose {@code productId} is never redirected to a merge target (Story 20.1): archiving or
     * restoring a merged product must act on that product (archive = no-op, restore = {@code PRODUCT_MERGED}),
     * never on the product it was merged into.
     */
    private static final Set<String> NO_MERGE_REDIRECT = Set.of("ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT");

    /** Product edits whose identity fields belong to the merged product, not to its target. */
    private static final Set<String> PRODUCT_EDITS = Set.of("UPDATE_PRODUCT", "PROMOTE_PRODUCT");

    /** Fields of a redirected product edit that describe the merged product itself: never applied to the target. */
    private static final List<String> MERGED_IDENTITY_FIELDS = List.of("name", "sku", "barcode", "trackStock");

    /**
     * Story 20.1 — merged product id → merge target id for every product id referenced by the batch (and the targets
     * of their name-merge aliases known when the batch starts), read once per batch: one alias query plus one
     * {@code products.merged_into_id} query, whatever the number of operations. A failed read never blocks the push
     * (the operations then land on the merged product itself, as before 20.1).
     */
    private Map<String, String> readProductMerges(PushBatchCommand command, List<SyncOperation> operations) {
        if (productQuery == null || operations.isEmpty()) return Map.of();
        Set<String> candidates = new HashSet<>();
        operations.forEach(op -> collectProductIds(op.payload(), candidates));
        if (candidates.isEmpty()) return Map.of();
        try {
            Map<String, String> result = transactionTemplate.execute(status -> {
                Set<String> ids = new HashSet<>(candidates);
                ids.addAll(logRepository.findMergedProductTargets(candidates).values());
                List<UUID> uuids = ids.stream().map(SyncPushService::uuidOrNull).filter(Objects::nonNull).toList();
                if (uuids.isEmpty()) return Map.<String, String>of();
                Map<String, String> merges = new HashMap<>();
                productQuery.resolveMergedTargets(command.tenantId(), uuids)
                        .forEach((from, to) -> merges.put(from.toString(), to.toString()));
                return merges;
            });
            return result != null ? result : Map.of();
        } catch (RuntimeException e) {
            log.warn("[Sync] Merged products of the batch could not be read — no merge redirection ({})",
                    e.getMessage());
            return Map.of();
        }
    }

    private static UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Rewrites every {@code productId} of the payload that no longer stands for itself:
     * <ul>
     *   <li>a CREATE_PRODUCT whose name already existed was merged into the existing product (see
     *   ProductSyncHandler): operations queued offline against the device's local product id (stock entries, sales,
     *   counts...) target that existing product (alias read per operation, so an alias recorded earlier in the same
     *   batch is followed);</li>
     *   <li>Story 20.1 — a product merged by its owner ({@code products.merged_into_id}, read once per batch): the
     *   operation lands on the merge target, except archive / restore ({@link #NO_MERGE_REDIRECT}). A redirected
     *   product edit drops the merged product's name, SKU, barcode and stock-tracking setting
     *   ({@link #MERGED_IDENTITY_FIELDS}: they still describe it, applied to the target they would collide with it or
     *   overwrite the target's own); a redirected sale line drops its {@code variantId} (a variant of the merged
     *   product, never of the target).</li>
     * </ul>
     */
    private SyncOperation redirectMergedProductIds(SyncOperation operation, Map<String, String> productMerges) {
        Set<String> candidates = new HashSet<>();
        collectProductIds(operation.payload(), candidates);
        if (candidates.isEmpty()) return operation;
        Map<String, String> aliases = logRepository.findMergedProductTargets(candidates);
        boolean followMerges = !productMerges.isEmpty() && !NO_MERGE_REDIRECT.contains(operation.operationType());
        Map<String, String> targets = new HashMap<>();
        Set<String> mergeRedirected = new HashSet<>();
        for (String id : candidates) {
            String target = aliases.getOrDefault(id, id);
            if (followMerges && productMerges.containsKey(target)) {
                target = productMerges.get(target);
                mergeRedirected.add(id);
            }
            if (!target.equals(id)) targets.put(id, target);
        }
        if (targets.isEmpty()) return operation;
        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) rewriteProductIds(operation.payload(), targets, mergeRedirected);
        if (PRODUCT_EDITS.contains(operation.operationType())
                && operation.payload().get("productId") instanceof String edited && mergeRedirected.contains(edited)) {
            MERGED_IDENTITY_FIELDS.forEach(rewritten::remove);
        }
        return new SyncOperation(operation.operationId(), operation.operationType(),
                operation.entityId(), rewritten, operation.clientTimestamp());
    }

    private void collectProductIds(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if ("productId".equals(k) && v instanceof String s) out.add(s);
                else collectProductIds(v, out);
            });
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectProductIds(v, out));
        }
    }

    private Object rewriteProductIds(Object node, Map<String, String> targets, Set<String> mergeRedirected) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                if ("productId".equals(key) && v instanceof String s && targets.containsKey(s)) {
                    copy.put(key, targets.get(s));
                } else {
                    copy.put(key, rewriteProductIds(v, targets, mergeRedirected));
                }
            });
            // Story 20.1 — a variant of the merged product is never a variant of the target
            if (map.get("productId") instanceof String s && mergeRedirected.contains(s)) {
                copy.remove("variantId");
            }
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(v -> rewriteProductIds(v, targets, mergeRedirected)).toList();
        }
        return node;
    }

    private static boolean isProductNameViolation(DataIntegrityViolationException e) {
        String msg = e.getMostSpecificCause().getMessage();
        return msg != null && msg.contains("uq_products_name");
    }

    /** Builds a REJECTED result and records it in a fresh transaction (the original one rolled back). */
    private SyncOperationResult rejectAfterRollback(SyncOperation operation, String reason) {
        return rejectAfterRollback(operation, reason, null);
    }

    /** Same, with the rejection details of the handler's decision ({@code conflictData}, Story 16.14). */
    private SyncOperationResult rejectAfterRollback(SyncOperation operation, String reason,
                                                    Map<String, Object> details) {
        try {
            transactionTemplate.execute(status -> {
                if (!logRepository.existsById(operation.operationId())) {
                    logRepository.save(new SyncOperationsLogEntry(
                            operation.operationId(),
                            operation.operationType(),
                            operation.entityId(),
                            SyncOperationStatus.REJECTED,
                            reason,
                            Instant.now(),
                            operation.clientTimestamp()));
                }
                return null;
            });
        } catch (Exception logEx) {
            log.error("[Sync] Failed to save REJECTED log for operation {}: {}",
                    operation.operationId(), logEx.getMessage());
        }
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED, null, reason, details);
    }

    private SyncOperationResult processOperation(SyncOperation originalOperation, PushBatchCommand command,
                                                 ModuleGate moduleGate, Map<String, String> productMerges) {
        // A previously REJECTED op is processed again; saving its new outcome
        // below overwrites the log row (assigned id → merge).
        if (logRepository.isSettled(originalOperation.operationId())
                // Claiming the log row before the handler runs makes a concurrent push of the
                // same operation wait on our insert, then see it settled instead of applying it twice.
                || logRepository.isSettledElseClaim(originalOperation.operationId(),
                        originalOperation.operationType(), originalOperation.entityId(),
                        originalOperation.clientTimestamp())) {
            return new SyncOperationResult(
                    originalOperation.operationId(), SyncOperationStatus.DUPLICATE, null, null);
        }
        // Story 12.7 — after the replay detection (an APPLIED operation keeps answering DUPLICATE even if its
        // module is now off), before the conflict checks and the handler.
        Optional<SyncOperationHandler> handler = handlerRegistry.resolve(originalOperation.operationType());
        SyncOperationResult refused = refuseByModule(originalOperation, handler, moduleGate);
        if (refused != null) {
            return refused;
        }
        SyncOperation operation = redirectMergedProductIds(originalOperation, productMerges);

        // Last-write-wins: an operation older than the entity's current state is not applied.
        ConflictResolutionStrategy strategy = conflictStrategyRegistry.resolve(operation.operationType());
        PreApplyCheck preApply = beforeApply(strategy, operation, command);
        if (preApply.isSuperseded()) {
            return supersede(operation, preApply.superseded(), command.actorId());
        }

        SyncOperationResult handlerResult = handler
                .map(h -> {
                    try {
                        return h.handle(operation, command.actorId(), command.tenantId());
                    } catch (Exception e) {
                        return new SyncOperationResult(
                                operation.operationId(), SyncOperationStatus.REJECTED, null, "INTERNAL_ERROR");
                    }
                })
                .orElse(new SyncOperationResult(
                        operation.operationId(), SyncOperationStatus.REJECTED, null, "UNKNOWN_OPERATION_TYPE"));

        if (handlerResult.status() != SyncOperationStatus.APPLIED) {
            return handlerResult;
        }

        try {
            return evaluateConflict(strategy, operation, handlerResult,
                    command.actorId(), command.tenantId(), preApply);
        } catch (Exception e) {
            log.error("Conflict evaluation failed for operation {}: {}", operation.operationId(), e.getMessage(), e);
            return handlerResult;
        }
    }

    /** The strategy's pre-apply decision; a failing or absent one never blocks the operation. */
    private PreApplyCheck beforeApply(ConflictResolutionStrategy strategy, SyncOperation operation,
                                      PushBatchCommand command) {
        if (strategy == null) return PreApplyCheck.NONE;
        try {
            PreApplyCheck check = strategy.beforeApply(operation, command.deviceId(), command.tenantId());
            return check != null ? check : PreApplyCheck.NONE;
        } catch (Exception e) {
            log.error("Pre-apply conflict check failed for operation {}: {}",
                    operation.operationId(), e.getMessage(), e);
            return PreApplyCheck.NONE;
        }
    }

    /**
     * The entity already holds a newer state: the operation is acknowledged (APPLIED, so the device
     * drops it instead of retrying forever) without being applied, and the conflict is logged. The
     * device gets the kept version back through its next pull.
     */
    private SyncOperationResult supersede(SyncOperation operation, ConflictResult conflict, UUID actorId) {
        logConflict(operation, conflict, actorId);
        Object entityId = conflict.conflictData() != null ? conflict.conflictData().get("entityId") : null;
        return new SyncOperationResult(
                operation.operationId(),
                SyncOperationStatus.APPLIED,
                entityId != null ? entityId.toString() : operation.entityId(),
                LastWriteWinsStrategy.SUPERSEDED_REASON,
                conflict.conflictData());
    }

    private void logConflict(SyncOperation operation, ConflictResult conflict, UUID actorId) {
        conflictsLogRepository.save(new SyncConflictsLogEntry(
                UUID.randomUUID().toString(),
                operation.operationId(),
                operation.operationType(),
                operation.entityId(),
                deriveEntityType(conflict, operation.operationType()),
                conflict.conflictType(),
                conflict.strategy(),
                conflict.conflictData(),
                Instant.now(),
                actorId.toString()));
    }

    private SyncOperationResult evaluateConflict(ConflictResolutionStrategy strategy, SyncOperation operation,
                                                 SyncOperationResult handlerResult, UUID actorId,
                                                 String tenantId, PreApplyCheck preApply) {
        if (strategy == null) {
            return handlerResult;
        }

        ConflictResult conflict = strategy.evaluate(operation, handlerResult, actorId, tenantId, preApply);
        if (conflict == null || conflict.conflictType() == null) {
            return handlerResult;
        }

        logConflict(operation, conflict, actorId);

        if ("STOCK_NEGATIVE".equals(conflict.conflictType())) {
            return new SyncOperationResult(
                    handlerResult.operationId(),
                    SyncOperationStatus.CONFLICT,
                    handlerResult.serverEntityId(),
                    conflict.conflictType(),
                    conflict.conflictData());
        }

        return new SyncOperationResult(
                handlerResult.operationId(),
                handlerResult.status(),
                handlerResult.serverEntityId(),
                handlerResult.reason(),
                conflict.conflictData());
    }

    private String deriveEntityType(ConflictResult conflict, String operationType) {
        if (conflict.conflictData() != null && conflict.conflictData().containsKey("entityType")) {
            return String.valueOf(conflict.conflictData().get("entityType"));
        }
        if ("STOCK_NEGATIVE".equals(conflict.conflictType())) {
            return "stock_level";
        }
        return operationType;
    }

    /**
     * Subtracted from the returned pull cursor. A row is stamped when its transaction writes it
     * but only becomes visible at commit: one committing just after the providers ran can carry
     * an updated_at before "now". The overlap is re-sent and upserted idempotently by the client.
     */
    static final java.time.Duration PULL_CURSOR_MARGIN = java.time.Duration.ofSeconds(30);

    /** Story 20.2 — optional: resolves a restricted pull's client visible stores once per pull. */
    private com.keevo.store.api.StoreQueryPort storeQuery;

    @Autowired(required = false)
    void setStoreQuery(com.keevo.store.api.StoreQueryPort storeQuery) {
        this.storeQuery = storeQuery;
    }

    /**
     * Story 20.2 — a restricted pull's client visible stores (ADR-08), read once per pull and carried by the scope to
     * the clients provider; {@code null} for an unrestricted pull, or when unresolvable (the provider then resolves it
     * itself and the scope carries no fingerprint).
     */
    private PullScope withClientStores(PullScope scope, String tenantId) {
        if (scope == null || !scope.restricted() || storeQuery == null) return scope;
        if (scope.storeId() == null) return scope.withClientStoreIds(java.util.Set.of());
        try {
            return scope.withClientStoreIds(storeQuery.clientVisibleStoreIds(tenantId, scope.storeId()));
        } catch (RuntimeException e) {
            log.warn("[Sync] Client visible stores unreadable ({}) — no sharing fingerprint for this pull",
                    e.getClass().getSimpleName());
            return scope;
        }
    }

    @Override
    public SyncPullResult pull(PullCommand command) {
        PullScope pullScope = withClientStores(command.scope(), command.tenantId());
        ScopeKey scope = ScopeKey.of(pullScope);
        PullCursor state = PullCursor.decode(command.cursor());
        boolean restart = false;
        if (state != null && state.scope() != null && !state.scope().sameAs(scope)) {
            // Story 16.13 — the actor's scope changed between two pages of this pull (employee reassigned): the
            // keyset positions belong to the old scope, so the pull restarts from the beginning as a re-seed.
            log.info("[Sync] Pull scope changed during a paged pull — pull restarted from the beginning (reseed)");
            state = null;
            restart = true;
        }
        if (state == null) {
            // Story 16.13 — first page: decided once, then carried (with the scope) by the cursor.
            Optional<UserSyncState> stored = readDeviceState(command);
            boolean reseed = restart || scopeChanged(stored, scope, command);
            state = new PullCursor(Instant.now(), false, reseed, mustRemember(stored, scope, command), scope,
                    new HashMap<>());
        }
        // A re-seeded pull ignores the client's since: the new scope's whole history must reach the device.
        Instant since = state.reseed() || command.since() == null ? Instant.EPOCH : command.since();

        Map<String, List<Map<String, Object>>> entities = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        // Each provider runs in its own read-only transaction.
        // If one provider fails, other providers are not affected
        // (avoids UnexpectedRollbackException from rollback-only propagation).
        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnlyTx.setReadOnly(true);
        Map<String, ModuleState> moduleStates = pullModuleStates(command.tenantId());

        for (DeltaEntityProvider provider : deltaProviders) {
            String key = provider.entityKey();
            List<Map<String, Object>> rows = List.of();
            if (provider.ownerOnly() && !command.owner()) {
                // Story v1s-18-7 — owner-only delta: never queried for other roles (FR98).
                state.positions().put(key, PullCursor.DONE);
            } else if (provider.requiredPermission() != null && !pullScope.holds(provider.requiredPermission())) {
                // Story 20.10 — permission-guarded delta (e.g. expenses: OWNER and MANAGER): never queried without it
                state.positions().put(key, PullCursor.DONE);
            } else if (provider.moduleId() != null
                    && moduleStates.get(provider.moduleId()) == ModuleState.DISABLED) {
                // Story 13.10a review — the entity's module is DISABLED for the tenant: nothing sent (premium data
                // never leaves through the free sync path); rows changed meanwhile are not replayed on reactivation
                state.positions().put(key, PullCursor.DONE);
            } else if (!state.isDone(key)) {
                DeltaEntityProvider.Position after = state.positions().get(key);
                try {
                    DeltaEntityProvider.Page page = readOnlyTx.execute(status ->
                            provider.queryDeltaPage(since, after, DeltaEntityProvider.PAGE_SIZE, pullScope));
                    if (page != null && page.rows() != null) rows = page.rows();
                    if (page != null && page.hasMore()) state.positions().put(key, page.next());
                    else state.positions().put(key, PullCursor.DONE);
                } catch (Exception e) {
                    // Its rows were not sent: the cursor must not move past them (see below).
                    state = state.withFailure();
                    state.positions().put(key, PullCursor.DONE);
                    log.error("Delta provider {} failed: {}", key, e.getMessage());
                }
            }
            entities.put(key, rows);
            counts.put(key, rows.size());
        }

        Optional<Instant> oldestPending = state.positions().values().stream()
                .filter(p -> p != PullCursor.DONE)
                .map(DeltaEntityProvider.Position::cursor)
                .min(Comparator.naturalOrder());
        boolean hasMore = oldestPending.isPresent();

        Instant serverTimestamp;
        if (state.failed()) {
            serverTimestamp = since;
        } else {
            serverTimestamp = state.startedAt().minus(PULL_CURSOR_MARGIN);
            // Clients that ignore hasMore save this: never past rows not sent yet.
            if (hasMore && oldestPending.get().isBefore(serverTimestamp)) {
                serverTimestamp = oldestPending.get();
            }
        }

        if (!hasMore && !state.failed() && state.remember()) {
            // Only a completed pull commits the scope: an interrupted or failed one re-seeds again next time.
            rememberScope(command, scope);
        }

        return new SyncPullResult(serverTimestamp, entities, counts,
                hasMore, hasMore ? state.encode() : null, command.scope(), state.reseed());
    }

    /**
     * Story 16.13 — the part of a pull scope that decides what the device holds: restricted + store. Story 20.2 —
     * plus {@code clientStores}: a fingerprint of a restricted pull's client visible stores (sorted ids, SHA-256 hex),
     * {@code null} when unrestricted or unknown; a known-and-different fingerprint re-seeds like a scope change (a
     * store started / stopped sharing its clients, a sharing store was (de)activated).
     */
    record ScopeKey(boolean restricted, UUID storeId, String clientStores) {
        ScopeKey(boolean restricted, UUID storeId) {
            this(restricted, storeId, null);
        }

        static ScopeKey of(PullScope scope) {
            return new ScopeKey(scope.restricted(), scope.storeId(),
                    scope.restricted() ? fingerprint(scope.clientStoreIds()) : null);
        }

        /** Same restricted + store, and same fingerprint when both are known. */
        boolean sameAs(ScopeKey other) {
            return other != null && restricted == other.restricted && Objects.equals(storeId, other.storeId)
                    && (clientStores == null || other.clientStores == null
                        || clientStores.equals(other.clientStores));
        }

        static String fingerprint(java.util.Set<UUID> ids) {
            if (ids == null) return null;
            String joined = ids.stream().map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(","));
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(hash);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Story 16.13 — the device's sync state (empty without a device id, or when unreadable). */
    private Optional<UserSyncState> readDeviceState(PullCommand command) {
        if (command.deviceId() == null || command.deviceId().isBlank()) {
            return Optional.empty();
        }
        try {
            return userSyncStateRepository.findByDeviceId(command.deviceId());
        } catch (RuntimeException e) {
            log.warn("[Sync] Device pull scope unreadable ({}) — normal pull", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static boolean ownRow(Optional<UserSyncState> stored, PullCommand command) {
        return stored.isPresent() && Objects.equals(stored.get().userId(), command.actorId());
    }

    private static ScopeKey storedScope(UserSyncState stored) {
        return new ScopeKey(stored.lastScopeRestricted(), stored.lastScopeStoreId(), stored.lastScopeClientStores());
    }

    /**
     * Story 16.13 — true when the device's last completed pull had a known scope (restricted + store) different
     * from this pull's (employee reassigned, promoted...). Unknown device, first pull of a device, another user's
     * row, or unreadable state: not a change.
     */
    private static boolean scopeChanged(Optional<UserSyncState> stored, ScopeKey scope, PullCommand command) {
        if (!ownRow(stored, command) || !stored.get().lastScopeKnown()) {
            return false;
        }
        boolean changed = !storedScope(stored.get()).sameAs(scope);
        if (changed) {
            log.info("[Sync] Pull scope of the device changed since its last pull — pull answered from the "
                    + "beginning (reseed)");
        }
        return changed;
    }

    /** Story 16.13 — the scope is written at the end of the pull only when unknown or different. */
    private static boolean mustRemember(Optional<UserSyncState> stored, ScopeKey scope, PullCommand command) {
        if (command.deviceId() == null || command.deviceId().isBlank() || command.actorId() == null) {
            return false;
        }
        if (stored.isEmpty()) return true; // unreadable or not registered yet: the guarded update decides
        return ownRow(stored, command)
                && (!stored.get().lastScopeKnown() || !storedScope(stored.get()).equals(scope));
        // Story 20.2: equals (not sameAs) — a newly known or changed client-stores fingerprint is written too
    }

    /** Story 16.13 — remembers the scope of the device's completed pull; never fails the pull. */
    private void rememberScope(PullCommand command, ScopeKey scope) {
        try {
            if (scope.clientStores() == null) {
                userSyncStateRepository.rememberPullScope(command.deviceId(), command.actorId(),
                        scope.restricted(), scope.storeId());
            } else {
                userSyncStateRepository.rememberPullScope(command.deviceId(), command.actorId(),
                        scope.restricted(), scope.storeId(), scope.clientStores());
            }
        } catch (RuntimeException e) {
            log.warn("[Sync] Device pull scope not saved ({}) — next pull compares with the previous one",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Story 13.10a review — the tenant's module states for the pull (one read). Unreadable → empty map: every
     * provider is pulled (a pull never fails on the gate; the REST and push gates still apply).
     */
    private Map<String, ModuleState> pullModuleStates(String tenantId) {
        try {
            Map<String, ModuleState> states = moduleRegistry.moduleStates(tenantId);
            return states != null ? states : Map.of();
        } catch (RuntimeException e) {
            log.warn("[Sync] Module states unreadable for the pull ({}) — every entity pulled", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * State of a paged pull, round-tripped to the client as an opaque cursor: when the pull
     * started (its final cursor), whether a provider failed, whether the pull is a re-seed from the
     * beginning and whether its scope must be remembered at the end (Story 16.13), the scope the pull
     * was started with ({@code null} for a cursor from before 16.13), and each provider's keyset
     * position (or DONE).
     */
    record PullCursor(Instant startedAt, boolean failed, boolean reseed, boolean remember, ScopeKey scope,
                      Map<String, DeltaEntityProvider.Position> positions) {

        static final DeltaEntityProvider.Position DONE = new DeltaEntityProvider.Position(Instant.EPOCH, "");

        boolean isDone(String key) {
            return positions.get(key) == DONE;
        }

        PullCursor withFailure() {
            return new PullCursor(startedAt, true, reseed, remember, scope, positions);
        }

        String encode() {
            StringBuilder sb = new StringBuilder();
            // Flags line (Story 16.13): failed, reseed, remember, then |restricted|store of the pinned scope.
            // A one-char line from before reads as reseed = false, remember = true, no pinned scope.
            sb.append(startedAt).append('\n').append(failed ? '1' : '0').append(reseed ? '1' : '0')
                    .append(remember ? '1' : '0');
            if (scope != null) {
                sb.append('|').append(scope.restricted() ? '1' : '0').append('|')
                        .append(scope.storeId() != null ? scope.storeId().toString() : "-");
                if (scope.clientStores() != null) sb.append('|').append(scope.clientStores()); // Story 20.2
            }
            positions.forEach((key, pos) -> {
                sb.append('\n').append(key).append('=');
                if (pos == DONE) sb.append('-');
                else sb.append(pos.cursor()).append('|').append(pos.id());
            });
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        /** Null when absent or unreadable — the pull then restarts from {@code since}. */
        static PullCursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) return null;
            try {
                String[] lines = new String(Base64.getUrlDecoder().decode(cursor),
                        java.nio.charset.StandardCharsets.UTF_8).split("\n");
                Instant startedAt = Instant.parse(lines[0]);
                String[] header = lines[1].split("\\|");
                String flags = header[0];
                boolean failed = flags.startsWith("1");
                boolean reseed = flags.length() > 1 && flags.charAt(1) == '1';
                boolean remember = flags.length() <= 2 || flags.charAt(2) == '1';
                ScopeKey scope = header.length >= 3
                        ? new ScopeKey("1".equals(header[1]), "-".equals(header[2]) ? null : UUID.fromString(header[2]),
                                header.length >= 4 ? header[3] : null)
                        : null;
                Map<String, DeltaEntityProvider.Position> positions = new HashMap<>();
                for (int i = 2; i < lines.length; i++) {
                    int eq = lines[i].indexOf('=');
                    String key = lines[i].substring(0, eq);
                    String value = lines[i].substring(eq + 1);
                    if ("-".equals(value)) {
                        positions.put(key, DONE);
                    } else {
                        int bar = value.indexOf('|');
                        positions.put(key, new DeltaEntityProvider.Position(
                                Instant.parse(value.substring(0, bar)), value.substring(bar + 1)));
                    }
                }
                return new PullCursor(startedAt, failed, reseed, remember, scope, positions);
            } catch (RuntimeException e) {
                log.warn("[Sync] Ignoring unreadable pull cursor: {}", e.getMessage());
                return null;
            }
        }
    }
}
