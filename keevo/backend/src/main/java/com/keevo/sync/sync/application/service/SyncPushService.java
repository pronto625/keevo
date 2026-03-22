package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
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

    public SyncPushService(SyncOperationHandlerRegistry handlerRegistry,
                           SyncOperationsLogRepository logRepository,
                           ApplicationEventPublisher eventPublisher,
                           PlatformTransactionManager transactionManager,
                           List<DeltaEntityProvider> deltaProviders) {
        this.handlerRegistry = handlerRegistry;
        this.logRepository = logRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.deltaProviders = deltaProviders;
    }

    @Override
    public SyncBatchResult pushBatch(PushBatchCommand command) {
        List<SyncOperation> sorted = command.operations().stream()
                .sorted(Comparator.comparing(
                        SyncOperation::clientTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<SyncOperationResult> results = new ArrayList<>();

        for (SyncOperation operation : sorted) {
            // Each operation runs in its own transaction — one failure does NOT roll back others (AC3)
            SyncOperationResult result = transactionTemplate.execute(status -> {
                SyncOperationResult opResult = processOperation(operation, command);

                logRepository.save(new SyncOperationsLogEntry(
                        operation.operationId(),
                        operation.operationType(),
                        operation.entityId(),
                        opResult.status(),
                        opResult.reason(),
                        Instant.now(),
                        operation.clientTimestamp()));

                return opResult;
            });

            results.add(result);

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

    private SyncOperationResult processOperation(SyncOperation operation, PushBatchCommand command) {
        if (logRepository.existsById(operation.operationId())) {
            return new SyncOperationResult(
                    operation.operationId(), SyncOperationStatus.DUPLICATE, null, null);
        }

        return handlerRegistry.resolve(operation.operationType())
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
    }

    @Override
    @Transactional(readOnly = true)
    public SyncPullResult pull(PullCommand command) {
        Instant serverTimestamp = Instant.now();
        Instant since = command.since() != null ? command.since() : Instant.EPOCH;

        Map<String, List<Map<String, Object>>> entities = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (DeltaEntityProvider provider : deltaProviders) {
            try {
                List<Map<String, Object>> delta = provider.queryDelta(since);
                entities.put(provider.entityKey(), delta);
                counts.put(provider.entityKey(), delta.size());
            } catch (Exception e) {
                entities.put(provider.entityKey(), List.of());
                counts.put(provider.entityKey(), 0);
                log.error("Delta provider {} failed: {}", provider.entityKey(), e.getMessage());
            }
        }

        return new SyncPullResult(serverTimestamp, entities, counts);
    }
}
