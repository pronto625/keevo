package com.keevo.sync.sync.application.service;

import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.sync.sync.domain.model.*;
import com.keevo.shared.application.port.sync.SyncOperationHandler;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
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
class SyncPushServiceTest {

    @Mock private SyncOperationHandlerRegistry handlerRegistry;
    @Mock private SyncOperationsLogRepository logRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SyncOperationHandler saleHandler;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ConflictStrategyRegistry conflictStrategyRegistry;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;
    @Mock private com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository syncErrorLogRepository;
    @Mock private com.keevo.shared.module.ModuleRegistry moduleRegistry;

    private SyncPushService service;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        // Simulate a pass-through transaction manager for unit tests
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        // Story 12.7 — every module active (today's state): the guard lets everything through.
        lenient().when(moduleRegistry.isKnownTenant(any())).thenReturn(true);
        lenient().when(moduleRegistry.moduleStates(any())).thenReturn(ModuleStatesFixture.enabled(java.util.Set.of("admin", "catalog", "core", "crm", "inventory", "messaging", "pos", "reporting", "store", "sync")));
        lenient().when(saleHandler.moduleId()).thenReturn("pos");
        service = new SyncPushService(handlerRegistry, logRepository, eventPublisher, transactionManager, List.of(),
                conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository, moduleRegistry);
    }

    @Test
    void pushBatch_withValidOperations_processesInOrder() {
        var op1 = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.parse("2026-03-20T10:00:00Z"));
        var op2 = new SyncOperation("op-2", "CREATE_SALE", "e2", Map.of(), Instant.parse("2026-03-20T10:01:00Z"));

        when(logRepository.isSettled("op-1")).thenReturn(false);
        when(logRepository.isSettled("op-2")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null))
                .thenReturn(new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, "srv-2", null));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op1, op2));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).hasSize(2);
        assertThat(result.appliedCount()).isEqualTo(2);
    }

    @Test
    void pushBatch_withMixedTypes_routesToCorrectHandlers() {
        SyncOperationHandler productHandler = handlerIn("catalog");
        var op1 = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());
        var op2 = new SyncOperation("op-2", "CREATE_PRODUCT", "e2", Map.of(), Instant.now());

        when(logRepository.isSettled(any())).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(handlerRegistry.resolve("CREATE_PRODUCT")).thenReturn(Optional.of(productHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, null, null));
        when(productHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, null, null));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op1, op2));

        SyncBatchResult result = service.pushBatch(command);

        verify(handlerRegistry).resolve("CREATE_SALE");
        verify(handlerRegistry).resolve("CREATE_PRODUCT");
        assertThat(result.results()).hasSize(2);
    }

    @Test
    void pushBatch_oneFailure_doesNotAbortOthers() {
        var op1 = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());
        var op2 = new SyncOperation("op-2", "CREATE_SALE", "e2", Map.of(), Instant.now());

        when(logRepository.isSettled(any())).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(eq(op1), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenThrow(new RuntimeException("unexpected error"));
        when(saleHandler.handle(eq(op2), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, null, null));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op1, op2));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.results().get(1).status()).isEqualTo(SyncOperationStatus.APPLIED);
    }

    @Test
    void pushBatch_duplicateOperationId_returnsDuplicate() {
        var op = new SyncOperation("op-dup", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.isSettled("op-dup")).thenReturn(true);

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.DUPLICATE);
        verify(saleHandler, never()).handle(any(), any(), any());
    }

    // A REJECTED log entry must not answer DUPLICATE on replay: the client
    // deletes DUPLICATE ops as synced, which would silently drop the sale.
    @Test
    void pushBatch_replayOfPreviouslyRejectedOperation_isProcessedAgain() {
        var op = new SyncOperation("op-retry", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.isSettled("op-retry")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-retry", SyncOperationStatus.APPLIED, "srv-1", null));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(saleHandler).handle(any(), eq(ACTOR_ID), eq(TENANT_ID));
    }

    @Test
    void pushBatch_emptyOperations_returnsEmptyResults() {
        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of());

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).isEmpty();
    }

    @Test
    void pushBatch_orderedByClientTimestamp() {
        var later = new SyncOperation("op-2", "CREATE_SALE", "e2", Map.of(), Instant.parse("2026-03-20T12:00:00Z"));
        var earlier = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.parse("2026-03-20T10:00:00Z"));

        when(logRepository.isSettled(any())).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenAnswer(inv -> {
                    SyncOperation op = inv.getArgument(0);
                    return new SyncOperationResult(op.operationId(), SyncOperationStatus.APPLIED, null, null);
                });

        // Pass in reverse order — service should sort by clientTimestamp ASC
        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(later, earlier));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).operationId()).isEqualTo("op-1");
        assertThat(result.results().get(1).operationId()).isEqualTo("op-2");
    }

    @Test
    void pushBatch_publishesSyncOperationProcessedEvent() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());

        when(logRepository.isSettled("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null));

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));

        service.pushBatch(command);

        ArgumentCaptor<SyncOperationProcessedEvent> captor = ArgumentCaptor.forClass(SyncOperationProcessedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().operationId()).isEqualTo("op-1");
        assertThat(captor.getValue().status()).isEqualTo(SyncOperationStatus.APPLIED);
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    void pushBatch_unknownOperationType_returnsRejected() {
        var op = new SyncOperation("op-1", "UNKNOWN_OP", "e1", Map.of(), Instant.now());

        when(logRepository.isSettled("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("UNKNOWN_OP")).thenReturn(Optional.empty());

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.results().get(0).reason()).isEqualTo("UNKNOWN_OPERATION_TYPE");
    }

    @Test
    void pushBatch_constraintViolationAtCommit_rejectsOnlyThatOperationAndContinues() {
        var op1 = new SyncOperation("op-1", "UPDATE_PRODUCT", "p1", Map.of(), Instant.parse("2026-03-20T10:00:00Z"));
        var op2 = new SyncOperation("op-2", "CREATE_SALE", "e2", Map.of(), Instant.parse("2026-03-20T10:01:00Z"));
        SyncOperationHandler productHandler = handlerIn("catalog");

        when(logRepository.isSettled(any())).thenReturn(false);
        when(handlerRegistry.resolve("UPDATE_PRODUCT")).thenReturn(Optional.of(productHandler));
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(productHandler.handle(eq(op1), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "p1", null));
        when(saleHandler.handle(eq(op2), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, null, null));
        // The unique-constraint violation surfaces at flush/commit, i.e. inside the tx callback
        // (here: when the first log entry is saved), not inside the handler.
        doThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "could not execute statement",
                        new RuntimeException("ERROR: duplicate key value violates unique constraint \"uq_products_name\"")))
                .doNothing()
                .when(logRepository).save(any());

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op1, op2));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.results().get(0).reason()).isEqualTo("PRODUCT_NAME_ALREADY_EXISTS");
        assertThat(result.results().get(1).status()).isEqualTo(SyncOperationStatus.APPLIED);
    }

    @Test
    void pushBatch_stockEntryOnMergedLocalProductId_isRedirectedToExistingProduct() {
        var op = new SyncOperation("op-1", "RECORD_STOCK_ENTRY", "e1",
                Map.of("productId", "local-id", "quantity", 5), Instant.now());
        SyncOperationHandler stockHandler = handlerIn("inventory");

        when(logRepository.isSettled(any())).thenReturn(false);
        when(logRepository.findMergedProductTargets(any())).thenReturn(Map.of("local-id", "existing-id"));
        when(handlerRegistry.resolve("RECORD_STOCK_ENTRY")).thenReturn(Optional.of(stockHandler));
        when(stockHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, null, null));

        service.pushBatch(new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op)));

        var captor = ArgumentCaptor.forClass(SyncOperation.class);
        verify(stockHandler).handle(captor.capture(), eq(ACTOR_ID), eq(TENANT_ID));
        assertThat(captor.getValue().payload()).containsEntry("productId", "existing-id")
                .containsEntry("quantity", 5);
    }

    @Test
    void pushBatch_rollbackOnlyAfterHandlerRejection_keepsTheRealReason() {
        var op = new SyncOperation("op-1", "STOCK_ADJUST", "e1", Map.of(), Instant.now());
        SyncOperationHandler stockHandler = handlerIn("inventory");

        when(logRepository.isSettled(any())).thenReturn(false);
        when(handlerRegistry.resolve("STOCK_ADJUST")).thenReturn(Optional.of(stockHandler));
        when(stockHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.REJECTED, null, "PRODUCT_NOT_FOUND"));
        // The @Transactional use case already flagged the shared tx rollback-only, so the commit
        // (here: the first log save) fails; the second save is the fresh-transaction fallback.
        doThrow(new org.springframework.transaction.UnexpectedRollbackException("marked as rollback-only"))
                .doNothing()
                .when(logRepository).save(any());

        SyncBatchResult result = service.pushBatch(new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op)));

        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.results().get(0).reason()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    private List<String> processedOrder(List<SyncOperation> ops) {
        SyncOperationHandler handler = handlerIn("catalog");
        List<String> order = new java.util.ArrayList<>();
        lenient().when(logRepository.isSettled(any())).thenReturn(false);
        lenient().when(handlerRegistry.resolve(any())).thenReturn(Optional.of(handler));
        lenient().when(handler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID))).thenAnswer(inv -> {
            SyncOperation op = inv.getArgument(0);
            order.add(op.operationId());
            return new SyncOperationResult(op.operationId(), SyncOperationStatus.APPLIED, null, null);
        });
        service.pushBatch(new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", ops));
        return order;
    }

    // STOCK_ADJUST sets an absolute quantity: replaying it before an earlier sale
    // would leave the stock 2 units below what the user counted.
    @Test
    void pushBatch_saleThenLaterStockAdjust_keepsClientOrder() {
        var sale = new SyncOperation("op-sale", "CREATE_SALE", "s1",
                Map.of("items", List.of(Map.of("productId", "p1", "quantity", 2))),
                Instant.parse("2026-03-20T09:00:00Z"));
        var adjust = new SyncOperation("op-adjust", "STOCK_ADJUST", "a1",
                Map.of("productId", "p1", "newQuantity", 5), Instant.parse("2026-03-20T10:00:00Z"));

        assertThat(processedOrder(List.of(adjust, sale))).containsExactly("op-sale", "op-adjust");
    }

    @Test
    void pushBatch_productUpdateIsNotHoistedAboveEarlierStockEntry() {
        var entry = new SyncOperation("op-entry", "RECORD_STOCK_ENTRY", "m1",
                Map.of("productId", "p1", "quantity", 3), Instant.parse("2026-03-20T09:00:00Z"));
        var archive = new SyncOperation("op-archive", "ARCHIVE_PRODUCT", "p1",
                Map.of("productId", "p1"), Instant.parse("2026-03-20T10:00:00Z"));

        assertThat(processedOrder(List.of(archive, entry))).containsExactly("op-entry", "op-archive");
    }

    @Test
    void pushBatch_creationsOthersDependOn_areStillProcessedFirst() {
        var entry = new SyncOperation("op-entry", "RECORD_STOCK_ENTRY", "m1",
                Map.of("productId", "p1", "quantity", 3), Instant.parse("2026-03-20T09:00:00Z"));
        var product = new SyncOperation("op-product", "CREATE_PRODUCT", "p1",
                Map.of("productId", "p1", "categoryId", "c1"), Instant.parse("2026-03-20T09:30:00Z"));
        var category = new SyncOperation("op-category", "CREATE_CATEGORY", "c1",
                Map.of(), Instant.parse("2026-03-20T09:45:00Z"));

        assertThat(processedOrder(List.of(entry, product, category)))
                .containsExactly("op-category", "op-product", "op-entry");
    }

    // Two pushes of the same batch can run concurrently (reconnect + new sale):
    // only the push that inserts the log row may run the handler.
    @Test
    void pushBatch_operationClaimedByConcurrentPush_isDuplicateAndHandlerNotRun() {
        var op = new SyncOperation("op-race", "RECORD_STOCK_ENTRY", "m1",
                Map.of("productId", "p1", "quantity", 3), Instant.now());
        SyncOperationHandler stockHandler = handlerIn("inventory");
        when(logRepository.isSettled("op-race")).thenReturn(false);
        when(logRepository.isSettledElseClaim(eq("op-race"), eq("RECORD_STOCK_ENTRY"), eq("m1"), any()))
                .thenReturn(true);

        SyncBatchResult result = service.pushBatch(new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op)));

        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.DUPLICATE);
        verify(stockHandler, never()).handle(any(), any(), any());
        verify(logRepository, never()).save(any());
    }

    @Test
    void pushBatch_claimsLogRowBeforeRunningHandler() {
        var op = new SyncOperation("op-1", "CREATE_SALE", "s1", Map.of(), Instant.now());
        when(logRepository.isSettled("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("CREATE_SALE")).thenReturn(Optional.of(saleHandler));
        when(saleHandler.handle(any(), eq(ACTOR_ID), eq(TENANT_ID)))
                .thenReturn(new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, null, null));

        service.pushBatch(new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op)));

        var inOrder = inOrder(logRepository, saleHandler);
        inOrder.verify(logRepository).isSettledElseClaim(eq("op-1"), eq("CREATE_SALE"), eq("s1"), any());
        inOrder.verify(saleHandler).handle(any(), eq(ACTOR_ID), eq(TENANT_ID));
        inOrder.verify(logRepository).save(any());
    }

    /** A mocked handler owned by {@code moduleId} (Story 12.7 — the push guard reads it). */
    private static SyncOperationHandler handlerIn(String moduleId) {
        SyncOperationHandler handler = mock(SyncOperationHandler.class);
        lenient().when(handler.moduleId()).thenReturn(moduleId);
        return handler;
    }
}
