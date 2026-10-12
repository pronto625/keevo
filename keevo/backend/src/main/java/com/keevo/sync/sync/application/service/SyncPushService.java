package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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

    public SyncPushService(SyncOperationHandlerRegistry handlerRegistry,
                           SyncOperationsLogRepository logRepository,
                           ApplicationEventPublisher eventPublisher,
                           PlatformTransactionManager transactionManager,
                           List<DeltaEntityProvider> deltaProviders,
                           ConflictStrategyRegistry conflictStrategyRegistry,
                           SyncConflictsLogRepository conflictsLogRepository,
                           SyncErrorLogRepository syncErrorLogRepository) {
        this.handlerRegistry = handlerRegistry;
        this.logRepository = logRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.deltaProviders = deltaProviders;
        this.conflictStrategyRegistry = conflictStrategyRegistry;
        this.conflictsLogRepository = conflictsLogRepository;
        this.syncErrorLogRepository = syncErrorLogRepository;
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
        List<SyncOperation> sorted = dependencyOrder(command.operations());

        List<SyncOperationResult> results = new ArrayList<>();

        for (SyncOperation operation : sorted) {
            // Each operation runs in its own transaction — one failure does NOT roll back others (AC3)
            SyncOperationResult result;
            // Keeps what the handler decided even if the commit later fails, so the real
            // rejection reason (e.g. PRODUCT_NOT_FOUND) is not lost behind a generic one.
            var handlerOutcome = new java.util.concurrent.atomic.AtomicReference<SyncOperationResult>();
            try {
                result = transactionTemplate.execute(status -> {
                    SyncOperationResult opResult = processOperation(operation, command);
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
                                operation.clientTimestamp()));
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
                String reason = decided != null && decided.status() == SyncOperationStatus.REJECTED
                        && decided.reason() != null ? decided.reason() : "TRANSACTION_ROLLED_BACK";
                result = rejectAfterRollback(operation, reason);
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
     * A CREATE_PRODUCT whose name already existed was merged into the existing product (see
     * ProductSyncHandler). Operations queued offline against the device's local product id
     * (stock entries, sales, counts...) must target that existing product: rewrite every
     * {@code productId} in the payload whose id was merged.
     */
    private SyncOperation redirectMergedProductIds(SyncOperation operation) {
        Set<String> candidates = new HashSet<>();
        collectProductIds(operation.payload(), candidates);
        if (candidates.isEmpty()) return operation;
        Map<String, String> targets = logRepository.findMergedProductTargets(candidates);
        if (targets.isEmpty()) return operation;
        @SuppressWarnings("unchecked")
        Map<String, Object> rewritten = (Map<String, Object>) rewriteProductIds(operation.payload(), targets);
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

    private Object rewriteProductIds(Object node, Map<String, String> targets) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                if ("productId".equals(key) && v instanceof String s && targets.containsKey(s)) {
                    copy.put(key, targets.get(s));
                } else {
                    copy.put(key, rewriteProductIds(v, targets));
                }
            });
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(v -> rewriteProductIds(v, targets)).toList();
        }
        return node;
    }

    private static boolean isProductNameViolation(DataIntegrityViolationException e) {
        String msg = e.getMostSpecificCause().getMessage();
        return msg != null && msg.contains("uq_products_name");
    }

    /** Builds a REJECTED result and records it in a fresh transaction (the original one rolled back). */
    private SyncOperationResult rejectAfterRollback(SyncOperation operation, String reason) {
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
        return new SyncOperationResult(operation.operationId(), SyncOperationStatus.REJECTED, null, reason);
    }

    private SyncOperationResult processOperation(SyncOperation originalOperation, PushBatchCommand command) {
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
        SyncOperation operation = redirectMergedProductIds(originalOperation);

        SyncOperationResult handlerResult = handlerRegistry.resolve(operation.operationType())
                .map(handler -> {
                    try {
                        return handler.handle(operation, command.actorId(), command.tenantId());
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
            return evaluateConflict(operation, handlerResult, command.actorId(), command.tenantId());
        } catch (Exception e) {
            log.error("Conflict evaluation failed for operation {}: {}", operation.operationId(), e.getMessage(), e);
            return handlerResult;
        }
    }

    private SyncOperationResult evaluateConflict(SyncOperation operation, SyncOperationResult handlerResult,
                                                  UUID actorId, String tenantId) {
        ConflictResolutionStrategy strategy = conflictStrategyRegistry.resolve(operation.operationType());
        if (strategy == null) {
            return handlerResult;
        }

        ConflictResult conflict = strategy.evaluate(operation, handlerResult, actorId, tenantId);
        if (conflict == null || conflict.conflictType() == null) {
            return handlerResult;
        }

        String entityType = deriveEntityType(conflict, operation.operationType());

        conflictsLogRepository.save(new SyncConflictsLogEntry(
                UUID.randomUUID().toString(),
                operation.operationId(),
                operation.operationType(),
                operation.entityId(),
                entityType,
                conflict.conflictType(),
                conflict.strategy(),
                conflict.conflictData(),
                Instant.now(),
                actorId.toString()));

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

    @Override
    public SyncPullResult pull(PullCommand command) {
        Instant since = command.since() != null ? command.since() : Instant.EPOCH;
        PullCursor state = PullCursor.decode(command.cursor());
        if (state == null) state = new PullCursor(Instant.now(), false, new HashMap<>());

        Map<String, List<Map<String, Object>>> entities = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        // Each provider runs in its own read-only transaction.
        // If one provider fails, other providers are not affected
        // (avoids UnexpectedRollbackException from rollback-only propagation).
        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnlyTx.setReadOnly(true);

        for (DeltaEntityProvider provider : deltaProviders) {
            String key = provider.entityKey();
            List<Map<String, Object>> rows = List.of();
            if (provider.ownerOnly() && !command.owner()) {
                // Story v1s-18-7 — owner-only delta: never queried for other roles (FR98).
                state.positions().put(key, PullCursor.DONE);
            } else if (!state.isDone(key)) {
                DeltaEntityProvider.Position after = state.positions().get(key);
                try {
                    DeltaEntityProvider.Page page = readOnlyTx.execute(status ->
                            provider.queryDeltaPage(since, after, DeltaEntityProvider.PAGE_SIZE));
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

        return new SyncPullResult(serverTimestamp, entities, counts,
                hasMore, hasMore ? state.encode() : null);
    }

    /**
     * State of a paged pull, round-tripped to the client as an opaque cursor: when the pull
     * started (its final cursor), whether a provider failed, and each provider's keyset
     * position (or DONE).
     */
    record PullCursor(Instant startedAt, boolean failed,
                      Map<String, DeltaEntityProvider.Position> positions) {

        static final DeltaEntityProvider.Position DONE = new DeltaEntityProvider.Position(Instant.EPOCH, "");

        boolean isDone(String key) {
            return positions.get(key) == DONE;
        }

        PullCursor withFailure() {
            return new PullCursor(startedAt, true, positions);
        }

        String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(startedAt).append('\n').append(failed ? '1' : '0');
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
                boolean failed = "1".equals(lines[1]);
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
                return new PullCursor(startedAt, failed, positions);
            } catch (RuntimeException e) {
                log.warn("[Sync] Ignoring unreadable pull cursor: {}", e.getMessage());
                return null;
            }
        }
    }
}
