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
 * Story 12.7 — module-aware push: operations of a module inactive for the tenant are REJECTED
 * {@code MODULE_INACTIVE} without affecting the rest of the batch (every row of the I/O matrix but the boot and
 * full-context ones, covered by {@code SyncOperationHandlerRegistryTest} and {@code SyncModuleGuardPostgresIntegrationTest}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncPushService — module guard (Story 12.7)")
class SyncPushServiceModuleGuardTest {

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
    @DisplayName("all modules active: a batch of 3 mixed operations is applied as today, registry read once")
    void allActive_allApplied() {
        when(moduleRegistry.activeModules(TENANT)).thenReturn(ALL);

        SyncBatchResult batch = push(op("c1", "CREATE_CLIENT"), op("s1", "CREATE_SALE"), op("p1", "UPDATE_PRODUCT"));

        assertThat(batch.results()).extracting(SyncOperationResult::status)
                .containsOnly(SyncOperationStatus.APPLIED);
        verify(moduleRegistry, times(1)).activeModules(TENANT);
        verify(saleHandler).handle(any(), any(), any());
        verify(clientHandler).handle(any(), any(), any());
        verify(productHandler).handle(any(), any(), any());
        verify(syncErrorLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("crm off: the client is REJECTED MODULE_INACTIVE (logged, payload kept), the sale is APPLIED")
    void crmOff_clientRejected_saleApplied() {
        when(moduleRegistry.activeModules(TENANT)).thenReturn(Set.of("core", "pos", "catalog", "inventory", "store"));

        SyncOperation client = op("c1", "CREATE_CLIENT");
        SyncBatchResult batch = push(client, op("s1", "CREATE_SALE"));

        assertThat(resultOf(batch, "c1").status()).isEqualTo(SyncOperationStatus.REJECTED);
        assertThat(resultOf(batch, "c1").reason()).isEqualTo("MODULE_INACTIVE");
        assertThat(resultOf(batch, "s1").status()).isEqualTo(SyncOperationStatus.APPLIED);
        verify(clientHandler, never()).handle(any(), any(), any());
        verify(saleHandler).handle(any(), any(), any());

        ArgumentCaptor<SyncOperationsLogEntry> logged = ArgumentCaptor.forClass(SyncOperationsLogEntry.class);
        verify(logRepository, times(2)).save(logged.capture());
        assertThat(logged.getAllValues()).anySatisfy(e -> {
            assertThat(e.id()).isEqualTo("c1");
            assertThat(e.status()).isEqualTo(SyncOperationStatus.REJECTED);
            assertThat(e.errorReason()).isEqualTo("MODULE_INACTIVE");
        });
        ArgumentCaptor<SyncErrorLogEntry> errors = ArgumentCaptor.forClass(SyncErrorLogEntry.class);
        verify(syncErrorLogRepository, times(1)).save(errors.capture());
        assertThat(errors.getValue().operationId()).isEqualTo("c1");
        assertThat(errors.getValue().payload()).isEqualTo(client.payload());
        assertThat(errors.getValue().errorReason()).isEqualTo("MODULE_INACTIVE");
    }

    @Test
    @DisplayName("pos off: CREATE_SALE is REJECTED MODULE_INACTIVE before any conflict check or handler")
    void posOff_saleRejected() {
        when(moduleRegistry.activeModules(TENANT)).thenReturn(Set.of("core", "crm", "catalog"));

        SyncBatchResult batch = push(op("s1", "CREATE_SALE"));

        assertThat(batch.results()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(SyncOperationStatus.REJECTED);
            assertThat(r.reason()).isEqualTo("MODULE_INACTIVE");
        });
        verify(saleHandler, never()).handle(any(), any(), any());
        verify(conflictStrategyRegistry, never()).resolve(anyString());
    }

    @Test
    @DisplayName("replay of an applied operation whose module is now off: still DUPLICATE, nothing re-logged")
    void replayOfAppliedOp_moduleOff_duplicate() {
        when(moduleRegistry.activeModules(TENANT)).thenReturn(Set.of("core"));
        when(logRepository.isSettled("s1")).thenReturn(true);

        SyncBatchResult batch = push(op("s1", "CREATE_SALE"));

        assertThat(batch.results()).singleElement()
                .extracting(SyncOperationResult::status).isEqualTo(SyncOperationStatus.DUPLICATE);
        verify(logRepository, never()).save(any());
        verify(syncErrorLogRepository, never()).save(any());
        verify(saleHandler, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("registry throws: the whole push fails SERVICE_UNAVAILABLE (503), nothing processed or recorded")
    void registryThrows_batchUnavailable() {
        when(moduleRegistry.activeModules(TENANT)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> push(op("c1", "CREATE_CLIENT"), op("s1", "CREATE_SALE")))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        verify(moduleRegistry, times(1)).activeModules(TENANT);
        verifyNoInteractions(logRepository, syncErrorLogRepository, eventPublisher);
        verify(saleHandler, never()).handle(any(), any(), any());
        verify(clientHandler, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("unknown or deleted tenant: every operation REJECTED TENANT_NOT_FOUND (not MODULE_INACTIVE)")
    void unknownTenant_tenantNotFound() {
        when(moduleRegistry.isKnownTenant(TENANT)).thenReturn(false);

        SyncBatchResult batch = push(op("c1", "CREATE_CLIENT"), op("s1", "CREATE_SALE"));

        assertThat(batch.results()).hasSize(2).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(SyncOperationStatus.REJECTED);
            assertThat(r.reason()).isEqualTo("TENANT_NOT_FOUND");
        });
        verify(moduleRegistry, never()).activeModules(any());
        verify(saleHandler, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("known tenant with every business module off: MODULE_INACTIVE, not TENANT_NOT_FOUND")
    void knownTenantAllOff_moduleInactive() {
        when(moduleRegistry.activeModules(TENANT)).thenReturn(Set.of("core"));

        SyncBatchResult batch = push(op("s1", "CREATE_SALE"));

        assertThat(batch.results()).singleElement()
                .extracting(SyncOperationResult::reason).isEqualTo("MODULE_INACTIVE");
    }

    @Test
    @DisplayName("unknown operation type keeps UNKNOWN_OPERATION_TYPE")
    void unknownType_keepsReason() {
        when(moduleRegistry.activeModules(TENANT)).thenReturn(ALL);

        SyncBatchResult batch = push(op("x1", "CREATE_UNICORN"));

        assertThat(batch.results()).singleElement()
                .extracting(SyncOperationResult::reason).isEqualTo("UNKNOWN_OPERATION_TYPE");
    }

    @Test
    @DisplayName("empty batch: the registry is not read")
    void emptyBatch_noRegistryRead() {
        assertThat(push().results()).isEmpty();
        verifyNoInteractions(moduleRegistry);
    }

    @Test
    @DisplayName("an inactive operation is never superseded by last-write-wins (refused before the conflict checks)")
    void inactiveOp_notSuperseded() {
        ConflictResolutionStrategy strategy = mock(ConflictResolutionStrategy.class);
        lenient().when(conflictStrategyRegistry.resolve("UPDATE_PRODUCT")).thenReturn(strategy);
        when(moduleRegistry.activeModules(TENANT)).thenReturn(Set.of("core", "pos"));

        SyncBatchResult batch = push(op("p1", "UPDATE_PRODUCT"));

        assertThat(batch.results()).singleElement()
                .extracting(SyncOperationResult::reason).isEqualTo("MODULE_INACTIVE");
        verifyNoInteractions(strategy);
    }
}
