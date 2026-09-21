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
import java.util.stream.Collectors;

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
     * Operation types that mutate a product entity.
     * These must be processed BEFORE stock operations that reference the same product.
     */
    private static final Set<String> PRODUCT_MUTATION_TYPES = Set.of(
            "CREATE_PRODUCT", "CREATE_DRAFT_PRODUCT", "UPDATE_PRODUCT",
            "PROMOTE_PRODUCT", "ARCHIVE_PRODUCT", "UNARCHIVE_PRODUCT");

    /**
     * Operation types that mutate stock for a product.
     * These must be processed AFTER product mutations for the same product.
     */
    private static final Set<String> STOCK_MUTATION_TYPES = Set.of(
            "RECORD_STOCK_ENTRY", "STOCK_ADJUST");

    /**
     * Extracts the productId from an operation's payload, if present.
     */
    private String productIdOf(SyncOperation op) {
        Object pid = op.payload().get("productId");
        return pid != null ? pid.toString() : null;
    }

    /**
     * Priority for topological sort: product mutations (0) before stock mutations (1).
     * All other types get priority 2 (neutral — sorted by timestamp only).
     */
    private int topologicalPriority(SyncOperation op) {
        if (PRODUCT_MUTATION_TYPES.contains(op.operationType())) return 0;
        if (STOCK_MUTATION_TYPES.contains(op.operationType())) return 1;
        return 2;
    }

    /**
     * Sorts operations with dependency awareness: for the same productId,
     * product mutations (CREATE/UPDATE/PROMOTE) always come before
     * stock mutations (RECORD_STOCK_ENTRY/STOCK_ADJUST).
     *
     * <p>Within the same priority level, chronological order is preserved.
     * Operations without a productId are sorted by timestamp only.
     */
    private List<SyncOperation> topologicalSort(List<SyncOperation> operations) {
        // Build a map of productId → earliest timestamp among product mutations
        Map<String, Instant> productMutationEarliest = operations.stream()
                .filter(op -> PRODUCT_MUTATION_TYPES.contains(op.operationType()))
                .filter(op -> productIdOf(op) != null)
                .collect(Collectors.groupingBy(
                        this::productIdOf,
                        Collectors.mapping(SyncOperation::clientTimestamp,
                                Collectors.minBy(Comparator.nullsLast(Comparator.naturalOrder())))))
                .entrySet().stream()
                .filter(e -> e.getValue().isPresent())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));

        return operations.stream()
                .sorted(Comparator
                        // 1. Topological priority (product before stock)
                        .comparingInt(this::topologicalPriority)
                        // 2. For stock ops, use the product mutation's earliest timestamp as tiebreaker
                        //    so stock ops are grouped right after their product mutation
                        .thenComparing((SyncOperation op) -> {
                            if (STOCK_MUTATION_TYPES.contains(op.operationType())) {
                                String pid = productIdOf(op);
                                if (pid != null) {
                                    Instant earliest = productMutationEarliest.get(pid);
                                    return earliest != null ? earliest : op.clientTimestamp();
                                }
                            }
                            return op.clientTimestamp();
                        }, Comparator.nullsLast(Comparator.naturalOrder()))
                        // 3. Within same priority, chronological order
                        .thenComparing(SyncOperation::clientTimestamp,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Override
    public SyncBatchResult pushBatch(PushBatchCommand command) {
        List<SyncOperation> sorted = topologicalSort(command.operations());

        List<SyncOperationResult> results = new ArrayList<>();

        for (SyncOperation operation : sorted) {
            // Each operation runs in its own transaction — one failure does NOT roll back others (AC3)
            SyncOperationResult result;
            try {
                result = transactionTemplate.execute(status -> {
                    SyncOperationResult opResult = processOperation(operation, command);

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
                result = rejectAfterRollback(operation, "TRANSACTION_ROLLED_BACK");
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
        if (logRepository.existsById(originalOperation.operationId())) {
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

    @Override
    public SyncPullResult pull(PullCommand command) {
        Instant serverTimestamp = Instant.now();
        Instant since = command.since() != null ? command.since() : Instant.EPOCH;

        Map<String, List<Map<String, Object>>> entities = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        // Each provider runs in its own read-only transaction.
        // If one provider fails, other providers are not affected
        // (avoids UnexpectedRollbackException from rollback-only propagation).
        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        readOnlyTx.setReadOnly(true);

        for (DeltaEntityProvider provider : deltaProviders) {
            try {
                List<Map<String, Object>> delta = readOnlyTx.execute(status ->
                        provider.queryDelta(since));
                entities.put(provider.entityKey(), delta != null ? delta : List.of());
                counts.put(provider.entityKey(), delta != null ? delta.size() : 0);
            } catch (Exception e) {
                entities.put(provider.entityKey(), List.of());
                counts.put(provider.entityKey(), 0);
                log.error("Delta provider {} failed: {}", provider.entityKey(), e.getMessage());
            }
        }

        return new SyncPullResult(serverTimestamp, entities, counts);
    }
}
