package com.keevo.sync.sync.application.service;

import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationHandler;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.domain.model.SyncBatchResult;
import com.keevo.sync.sync.domain.port.in.SyncUseCase.PushBatchCommand;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 19.5 — a VIEWER never writes through sync either: every operation of its push is {@code REJECTED} with reason
 * {@code FORBIDDEN}, decided once per batch from the command's {@code actorRole} (no security context read), without
 * calling a handler, reading the module gate or writing a log row.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncPushService — VIEWER push rejected (Story 19.5)")
class SyncPushServiceViewerTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Mock private SyncOperationsLogRepository logRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ConflictStrategyRegistry conflictStrategyRegistry;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;
    @Mock private SyncErrorLogRepository syncErrorLogRepository;
    @Mock private ModuleRegistry moduleRegistry;

    private SyncOperationHandler saleHandler;
    private SyncPushService service;

    @BeforeEach
    void setUp() {
        saleHandler = mock(SyncOperationHandler.class);
        when(saleHandler.supportedTypes()).thenReturn(Set.of("CREATE_SALE", "CREATE_CLIENT", "UPDATE_PRODUCT"));
        when(saleHandler.moduleId()).thenReturn("pos");
        SyncOperationHandlerRegistry registry = new SyncOperationHandlerRegistry(List.of(saleHandler),
                Set.of("core", "pos"));
        service = new SyncPushService(registry, logRepository, eventPublisher, transactionManager, List.of(),
                conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository, moduleRegistry,
                mock(UserSyncStateRepository.class));
    }

    private static SyncOperation op(String id, String type) {
        return new SyncOperation(id, type, "entity-" + id, Map.of("name", "Valeur " + id), Instant.now());
    }

    @Test
    @DisplayName("3 operations → each REJECTED / FORBIDDEN, no handler, no module gate, no log row")
    void everyOperationRejected() {
        SyncBatchResult batch = service.pushBatch(new PushBatchCommand(ACTOR_ID, "kv_viewer1", "device-1",
                List.of(op("s1", "CREATE_SALE"), op("c1", "CREATE_CLIENT"), op("p1", "UPDATE_PRODUCT")), "VIEWER"));

        assertThat(batch.results()).hasSize(3);
        assertThat(batch.results()).extracting(SyncOperationResult::operationId).containsExactly("s1", "c1", "p1");
        assertThat(batch.results()).extracting(SyncOperationResult::status).containsOnly(SyncOperationStatus.REJECTED);
        assertThat(batch.results()).extracting(SyncOperationResult::reason).containsOnly("FORBIDDEN");
        verifyNoInteractions(moduleRegistry, logRepository, transactionManager, conflictStrategyRegistry);
        org.mockito.Mockito.verify(saleHandler, org.mockito.Mockito.never())
                .handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}
