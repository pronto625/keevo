package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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

    private SyncPushService service;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        service = new SyncPushService(handlerRegistry, logRepository, eventPublisher);
    }

    @Test
    void pushBatch_withValidOperations_processesInOrder() {
        var op1 = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.parse("2026-03-20T10:00:00Z"));
        var op2 = new SyncOperation("op-2", "CREATE_SALE", "e2", Map.of(), Instant.parse("2026-03-20T10:01:00Z"));

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(logRepository.existsById("op-2")).thenReturn(false);
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
        SyncOperationHandler productHandler = mock(SyncOperationHandler.class);
        var op1 = new SyncOperation("op-1", "CREATE_SALE", "e1", Map.of(), Instant.now());
        var op2 = new SyncOperation("op-2", "CREATE_PRODUCT", "e2", Map.of(), Instant.now());

        when(logRepository.existsById(any())).thenReturn(false);
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

        when(logRepository.existsById(any())).thenReturn(false);
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

        when(logRepository.existsById("op-dup")).thenReturn(true);

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.DUPLICATE);
        verify(saleHandler, never()).handle(any(), any(), any());
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

        when(logRepository.existsById(any())).thenReturn(false);
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

        when(logRepository.existsById("op-1")).thenReturn(false);
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

        when(logRepository.existsById("op-1")).thenReturn(false);
        when(handlerRegistry.resolve("UNKNOWN_OP")).thenReturn(Optional.empty());

        var command = new com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand(
                ACTOR_ID, TENANT_ID, "device-1", List.of(op));

        SyncBatchResult result = service.pushBatch(command);

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(result.results().get(0).reason()).isEqualTo("UNKNOWN_OPERATION_TYPE");
    }
}
