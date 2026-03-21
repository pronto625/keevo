package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class SyncPushService implements SyncUseCase {

    private final SyncOperationHandlerRegistry handlerRegistry;
    private final SyncOperationsLogRepository logRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public SyncPushService(SyncOperationHandlerRegistry handlerRegistry,
                           SyncOperationsLogRepository logRepository,
                           ApplicationEventPublisher eventPublisher,
                           PlatformTransactionManager transactionManager) {
        this.handlerRegistry = handlerRegistry;
        this.logRepository = logRepository;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
    public int pull(String since) {
        throw new UnsupportedOperationException("Pull sync not implemented — Story 5.2");
    }
}
