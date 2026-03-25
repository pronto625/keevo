package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncPushServiceConflictTest {

    @Mock private SyncOperationHandlerRegistry handlerRegistry;
    @Mock private SyncOperationsLogRepository logRepository;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;
    @Mock private ConflictStrategyRegistry conflictStrategyRegistry;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SyncOperationHandler saleHandler;
    @Mock private ConflictResolutionStrategy conflictStrategy;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository syncErrorLogRepository;

    private SyncPushService service;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        service = new SyncPushService(handlerRegistry, logRepository, eventPublisher,
                transactionManager, List.of(), conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository);
    }

    @Test
    void pushBatch_handlerApplied_evaluatesConflict() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1",
                Map.of("storeId", "s1", "items", List.of()), Instant.now());

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null));
        when(conflictStrategyRegistry.resolve("CREATE_SALE")).thenReturn(conflictStrategy);
        when(conflictStrategy.evaluate(any(), any(), any(), any())).thenReturn(null);

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        service.pushBatch(command);

        verify(conflictStrategyRegistry).resolve("CREATE_SALE");
        verify(conflictStrategy).evaluate(eq(op), any(), eq(ACTOR_ID), eq(TENANT_ID));
    }

    @Test
    void pushBatch_conflictDetected_enrichesResultWithConflictData() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1",
                Map.of("storeId", "s1"), Instant.now());

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null));
        var conflictData = Map.<String, Object>of("conflictType", "STOCK_NEGATIVE", "resultingStock", -1);
        when(conflictStrategyRegistry.resolve("CREATE_SALE")).thenReturn(conflictStrategy);
        when(conflictStrategy.evaluate(any(), any(), any(), any()))
                .thenReturn(new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", conflictData));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results().get(0).conflictData()).containsEntry("resultingStock", -1);
    }

    @Test
    void pushBatch_conflictDetected_statusChangedToConflict() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null));
        var conflictData = Map.<String, Object>of("conflictType", "STOCK_NEGATIVE");
        when(conflictStrategyRegistry.resolve("CREATE_SALE")).thenReturn(conflictStrategy);
        when(conflictStrategy.evaluate(any(), any(), any(), any()))
                .thenReturn(new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", conflictData));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.CONFLICT);
    }

    @Test
    void pushBatch_conflictDetected_logsToSyncConflictsLog() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null));
        var conflictData = Map.<String, Object>of("conflictType", "STOCK_NEGATIVE");
        when(conflictStrategyRegistry.resolve("CREATE_SALE")).thenReturn(conflictStrategy);
        when(conflictStrategy.evaluate(any(), any(), any(), any()))
                .thenReturn(new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", conflictData));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        service.pushBatch(command);

        verify(conflictsLogRepository).save(any(SyncConflictsLogEntry.class));
    }

    @Test
    void pushBatch_noConflict_resultUnchanged() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null));
        when(conflictStrategyRegistry.resolve("CREATE_SALE")).thenReturn(conflictStrategy);
        when(conflictStrategy.evaluate(any(), any(), any(), any())).thenReturn(null);

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.results().get(0).conflictData()).isNull();
        verify(conflictsLogRepository, never()).save(any());
    }

    @Test
    void pushBatch_handlerRejected_skipsConflictEvaluation() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.REJECTED, null, "ERROR"));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        service.pushBatch(command);

        verifyNoInteractions(conflictStrategyRegistry);
    }

    @Test
    void pushBatch_handlerDuplicate_skipsConflictEvaluation() {
        var op = new SyncOperation("op-dup", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.existsById("op-dup")).thenReturn(true);

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        service.pushBatch(command);

        verifyNoInteractions(conflictStrategyRegistry);
    }

    @Test
    void pushBatch_lwwConflict_statusStaysApplied() {
        var op = new SyncOperation("op-1", "UPDATE_PRODUCT", "p1", Map.of(), Instant.now());

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("UPDATE_PRODUCT")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "p1", null));
        var conflictData = Map.<String, Object>of("entityType", "PRODUCT");
        when(conflictStrategyRegistry.resolve("UPDATE_PRODUCT")).thenReturn(conflictStrategy);
        when(conflictStrategy.evaluate(any(), any(), any(), any()))
                .thenReturn(new ConflictResult("LAST_WRITE_WINS", "LAST_WRITE_WINS", conflictData));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        SyncBatchResult result = service.pushBatch(command);

        // LWW keeps APPLIED status
        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.APPLIED);
        // But conflictData is still populated for audit
        assertThat(result.results().get(0).conflictData()).containsEntry("entityType", "PRODUCT");
    }

    @Test
    void pushBatch_stockNegative_statusChangesToConflict() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null));
        var conflictData = Map.<String, Object>of("conflictType", "STOCK_NEGATIVE", "resultingStock", -2);
        when(conflictStrategyRegistry.resolve("CREATE_SALE")).thenReturn(conflictStrategy);
        when(conflictStrategy.evaluate(any(), any(), any(), any()))
                .thenReturn(new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", conflictData));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.CONFLICT);
    }
}
