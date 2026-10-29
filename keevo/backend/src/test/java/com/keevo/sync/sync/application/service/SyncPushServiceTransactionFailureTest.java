package com.keevo.sync.sync.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.sync.sync.domain.model.SyncErrorLogEntry;
import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import com.keevo.sync.sync.domain.model.SyncOperationsLogEntry;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import com.keevo.sync.sync.domain.port.in.SyncOperationHandler;
import com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 12.7 (live test) — an exception escaping the per-operation transaction other than the rollback /
 * constraint ones (e.g. {@code JpaSystemException} "current transaction is aborted" when saving the log row after
 * the handler hit a constraint) rejects only that operation; the batch never fails as a whole.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncPushService — failures inside the per-operation transaction (Story 12.7 live test)")
class SyncPushServiceTransactionFailureTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT = "kv_guard1";
    private static final Set<String> ALL = Set.of("admin", "catalog", "core", "crm", "inventory", "messaging",
            "pos", "reporting", "store", "sync");

    @Mock private SyncOperationsLogRepository logRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ConflictStrategyRegistry conflictStrategyRegistry;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;
    @Mock private SyncErrorLogRepository syncErrorLogRepository;
    @Mock private ModuleRegistry moduleRegistry;

    private SyncOperationHandler saleHandler;
    private SyncOperationHandler clientHandler;
    private SyncOperationHandler productHandler;
    private SyncPushService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(moduleRegistry.isKnownTenant(TENANT)).thenReturn(true);
        saleHandler = handler("pos", "CREATE_SALE");
        clientHandler = handler("crm", "CREATE_CLIENT");
        productHandler = handler("catalog", "UPDATE_PRODUCT");
        SyncOperationHandlerRegistry registry = new SyncOperationHandlerRegistry(
                List.of(saleHandler, clientHandler, productHandler), ALL);
        service = new SyncPushService(registry, logRepository, eventPublisher, transactionManager, List.of(),
                conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository, moduleRegistry);
    }

    private static SyncOperationHandler handler(String moduleId, String type) {
        SyncOperationHandler handler = mock(SyncOperationHandler.class);
        when(handler.supportedTypes()).thenReturn(Set.of(type));
        when(handler.moduleId()).thenReturn(moduleId);
        lenient().when(handler.handle(any(), any(), any())).thenAnswer(inv -> {
            SyncOperation op = inv.getArgument(0);
            return new SyncOperationResult(op.operationId(), SyncOperationStatus.APPLIED, op.entityId(), null);
        });
        return handler;
    }

    private static SyncOperation op(String id, String type) {
        return new SyncOperation(id, type, "entity-" + id, Map.of("name", "Valeur " + id), Instant.now());
    }

    private SyncBatchResult push(SyncOperation... ops) {
        return service.pushBatch(new PushBatchCommand(ACTOR_ID, TENANT, "device-1", List.of(ops)));
    }

    private static SyncOperationResult resultOf(SyncBatchResult batch, String operationId) {
        return batch.results().stream().filter(r -> r.operationId().equals(operationId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("log save fails after a REJECTED handler outcome: that reason is kept, the next op is APPLIED")
    void abortedTransaction_keepsHandlerReason_otherOpApplied() {
        when(moduleRegistry.moduleStates(TENANT)).thenReturn(ModuleStatesFixture.enabled(ALL));
        org.mockito.Mockito.doAnswer(inv -> new SyncOperationResult(
                ((SyncOperation) inv.getArgument(0)).operationId(), SyncOperationStatus.REJECTED, null,
                "CLIENT_NOT_FOUND")).when(clientHandler).handle(any(), any(), any());
        org.mockito.Mockito.doAnswer(inv -> {
            SyncOperationsLogEntry entry = inv.getArgument(0);
            if ("c1".equals(entry.id()) && entry.errorReason() != null
                    && entry.processedAt() != null && entry.deviceId() != null) {
                throw new org.springframework.orm.jpa.JpaSystemException(
                        new RuntimeException("current transaction is aborted"));
            }
            return null;
        }).when(logRepository).save(any());

        SyncBatchResult batch = push(op("c1", "CREATE_CLIENT"), op("s1", "CREATE_SALE"));

        assertThat(resultOf(batch, "c1").status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(resultOf(batch, "c1").reason()).isEqualTo("CLIENT_NOT_FOUND");
        assertThat(resultOf(batch, "s1").status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(syncErrorLogRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("handler mapped the failure to INTERNAL_ERROR and a data-access error escapes: DATA_INTEGRITY_VIOLATION")
    void dataAccessFailure_dataIntegrityViolation() {
        when(moduleRegistry.moduleStates(TENANT)).thenReturn(ModuleStatesFixture.enabled(ALL));
        org.mockito.Mockito.doAnswer(inv -> new SyncOperationResult(
                ((SyncOperation) inv.getArgument(0)).operationId(), SyncOperationStatus.REJECTED, null,
                "INTERNAL_ERROR")).when(saleHandler).handle(any(), any(), any());
        org.mockito.Mockito.doThrow(new org.springframework.orm.jpa.JpaSystemException(
                        new RuntimeException("current transaction is aborted")))
                .doNothing()
                .when(logRepository).save(any());

        SyncBatchResult batch = push(op("s1", "CREATE_SALE"));

        assertThat(batch.results()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(SyncOperationStatus.REJECTED);
            assertThat(r.reason()).isEqualTo("DATA_INTEGRITY_VIOLATION");
        });
    }

    @Test
    @DisplayName("any other runtime failure in the transaction: that operation REJECTED INTERNAL_ERROR, no exception")
    void otherRuntimeFailure_internalError() {
        when(moduleRegistry.moduleStates(TENANT)).thenReturn(ModuleStatesFixture.enabled(ALL));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(productHandler).handle(any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("log store down"))
                .doNothing()
                .when(logRepository).save(any());

        SyncBatchResult batch = push(op("p1", "UPDATE_PRODUCT"), op("s1", "CREATE_SALE"));

        assertThat(resultOf(batch, "p1").reason()).isEqualTo("INTERNAL_ERROR");
        assertThat(resultOf(batch, "s1").status()).isEqualTo(SyncOperationStatus.APPLIED);
    }
}
