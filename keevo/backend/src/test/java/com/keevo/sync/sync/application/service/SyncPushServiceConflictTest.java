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
    @Mock private com.keevo.shared.module.ModuleRegistry moduleRegistry;

    private SyncPushService service;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        // As the interface defaults: beforeApply → NONE, evaluate(…, preApply) → the 4-arg evaluate.
        lenient().when(conflictStrategy.beforeApply(any(), any(), any())).thenReturn(PreApplyCheck.NONE);
        lenient().when(conflictStrategy.evaluate(any(), any(), any(), any(), any())).thenAnswer(inv ->
                ((ConflictResolutionStrategy) inv.getMock()).evaluate(inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3)));
        // Story 12.7 — every module active (today's state): the guard lets everything through.
        lenient().when(moduleRegistry.isKnownTenant(any())).thenReturn(true);
        lenient().when(moduleRegistry.activeModules(any())).thenReturn(java.util.Set.of("admin", "catalog", "core", "crm", "inventory", "messaging", "pos", "reporting", "store", "sync"));
        lenient().when(saleHandler.moduleId()).thenReturn("pos");
        service = new SyncPushService(handlerRegistry, logRepository, eventPublisher,
                transactionManager, List.of(), conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository, moduleRegistry);
    }

    @Test
    void pushBatch_handlerApplied_evaluatesConflict() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1",
                Map.of("storeId", "s1", "items", List.of()), Instant.now());

        when(logRepository.isSettled("op-1")).thenReturn(false);
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

        when(logRepository.isSettled("op-1")).thenReturn(false);
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

        when(logRepository.isSettled("op-1")).thenReturn(false);
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

        when(logRepository.isSettled("op-1")).thenReturn(false);
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

        when(logRepository.isSettled("op-1")).thenReturn(false);
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

        when(logRepository.isSettled("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.REJECTED, null, "ERROR"));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        service.pushBatch(command);

        // Resolved once for the pre-apply check; nothing evaluated nor logged after the rejection.
        verify(conflictStrategyRegistry).resolve("CREATE_SALE");
        verifyNoInteractions(conflictsLogRepository);
    }

    @Test
    void pushBatch_handlerDuplicate_skipsConflictEvaluation() {
        var op = new SyncOperation("op-dup", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.isSettled("op-dup")).thenReturn(true);

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));
        service.pushBatch(command);

        verifyNoInteractions(conflictStrategyRegistry);
    }

    @Test
    void pushBatch_lwwConflict_statusStaysApplied() {
        var op = new SyncOperation("op-1", "UPDATE_PRODUCT", "p1", Map.of(), Instant.now());

        when(logRepository.isSettled("op-1")).thenReturn(false);
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

        when(logRepository.isSettled("op-1")).thenReturn(false);
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

    // ── Last-write-wins: pre-apply decision ──────────────────────────────────

    @Test
    void pushBatch_supersededOperation_notAppliedButAcknowledgedAndLogged() {
        var op = new SyncOperation("op-old", "UPDATE_PRODUCT", "p1",
                Map.of("productId", "p1", "price", 3600), Instant.parse("2026-10-14T02:44:00Z"));
        var data = Map.<String, Object>of("conflictType", "LAST_WRITE_SUPERSEDED", "entityType", "PRODUCT",
                "entityId", "p1");
        when(logRepository.isSettled("op-old")).thenReturn(false);
        when(conflictStrategyRegistry.resolve("UPDATE_PRODUCT")).thenReturn(conflictStrategy);
        doReturn(PreApplyCheck.superseded(new ConflictResult("LAST_WRITE_SUPERSEDED", "LAST_WRITE_WINS", data)))
                .when(conflictStrategy).beforeApply(op, "device-1", TENANT_ID);

        SyncBatchResult result = service.pushBatch(new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op)));

        var r = result.results().get(0);
        assertThat(r.status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(r.reason()).isEqualTo("LWW_SUPERSEDED");
        assertThat(r.serverEntityId()).isEqualTo("p1");
        assertThat(r.conflictData()).containsEntry("conflictType", "LAST_WRITE_SUPERSEDED");
        // Story 12.7: the registry is consulted once (module guard); no handler ever runs.
        verify(handlerRegistry, times(1)).resolve("UPDATE_PRODUCT");
        verifyNoMoreInteractions(handlerRegistry);
        var conflict = ArgumentCaptor.forClass(SyncConflictsLogEntry.class);
        verify(conflictsLogRepository).save(conflict.capture());
        assertThat(conflict.getValue().conflictType()).isEqualTo("LAST_WRITE_SUPERSEDED");
        assertThat(conflict.getValue().strategy()).isEqualTo("LAST_WRITE_WINS");
        var logged = ArgumentCaptor.forClass(SyncOperationsLogEntry.class);
        verify(logRepository).save(logged.capture());
        assertThat(logged.getValue().status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(logged.getValue().errorReason()).isEqualTo("LWW_SUPERSEDED");
    }

    @Test
    void pushBatch_logEntryCarriesPushingDevice() {
        var op = new SyncOperation("op-1", "UPDATE_PRODUCT", "p1", Map.of("productId", "p1"), Instant.now());
        when(logRepository.isSettled("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("UPDATE_PRODUCT")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "p1", null));

        service.pushBatch(new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-7", List.of(op)));

        var logged = ArgumentCaptor.forClass(SyncOperationsLogEntry.class);
        verify(logRepository).save(logged.capture());
        assertThat(logged.getValue().deviceId()).isEqualTo("device-7");
    }

    @Test
    void pushBatch_overwriteDecidedBeforeApply_loggedOnlyOnceApplied() {
        var op = new SyncOperation("op-1", "UPDATE_PRODUCT", "p1", Map.of("productId", "p1"), Instant.now());
        var overwrite = new ConflictResult("LAST_WRITE_WINS", "LAST_WRITE_WINS", Map.of("entityType", "PRODUCT"));
        var pre = PreApplyCheck.overwrite(overwrite);
        when(logRepository.isSettled("op-1")).thenReturn(false);
        when(conflictStrategyRegistry.resolve("UPDATE_PRODUCT")).thenReturn(conflictStrategy);
        doReturn(pre).when(conflictStrategy).beforeApply(any(), any(), any());
        when(handlerRegistry.resolve("UPDATE_PRODUCT")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "p1", null));
        doReturn(overwrite).when(conflictStrategy).evaluate(any(), any(), any(), any(), eq(pre));

        SyncBatchResult result = service.pushBatch(new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op)));

        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(result.results().get(0).conflictData()).containsEntry("entityType", "PRODUCT");
        verify(conflictsLogRepository).save(any(SyncConflictsLogEntry.class));
    }
}
