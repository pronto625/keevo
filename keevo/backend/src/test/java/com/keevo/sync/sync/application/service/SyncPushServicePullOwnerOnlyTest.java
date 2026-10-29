package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story v1s-18-7 — owner-only deltas (audit log) are never sent to non-OWNER devices (FR98).
 */
@ExtendWith(MockitoExtension.class)
class SyncPushServicePullOwnerOnlyTest {

    @Mock private SyncOperationHandlerRegistry handlerRegistry;
    @Mock private SyncOperationsLogRepository logRepository;
    @Mock private ConflictStrategyRegistry conflictStrategyRegistry;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;
    @Mock private SyncErrorLogRepository syncErrorLogRepository;
    @Mock private com.keevo.shared.module.ModuleRegistry moduleRegistry;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private DeltaEntityProvider productProvider;
    @Mock private DeltaEntityProvider auditProvider;

    private SyncPushService service;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";
    private static final Instant SINCE = Instant.parse("2026-10-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(productProvider.entityKey()).thenReturn("products");
        lenient().when(auditProvider.entityKey()).thenReturn("auditEntries");
        lenient().when(auditProvider.ownerOnly()).thenReturn(true);
        lenient().when(productProvider.queryDeltaPage(eq(SINCE), isNull(), anyInt())).thenReturn(
                new DeltaEntityProvider.Page(List.of(Map.of("id", "p1")), null));
        service = new SyncPushService(handlerRegistry, logRepository, eventPublisher,
                transactionManager, List.of(productProvider, auditProvider),
                conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository, moduleRegistry);
    }

    @Test
    void employeePull_getsEmptyAuditEntries_andProviderIsNeverQueried() {
        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, SINCE, null, false));

        assertThat(result.entities()).containsKey("auditEntries");
        assertThat(result.entities().get("auditEntries")).isEmpty();
        assertThat(result.counts()).containsEntry("auditEntries", 0);
        assertThat(result.entities().get("products")).hasSize(1);
        assertThat(result.hasMore()).isFalse();
        verify(auditProvider, never()).queryDeltaPage(any(), any(), anyInt());
    }

    @Test
    void legacyConstructor_failsClosed() {
        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, SINCE));

        assertThat(result.entities().get("auditEntries")).isEmpty();
        verify(auditProvider, never()).queryDeltaPage(any(), any(), anyInt());
    }

    @Test
    void ownerPull_getsAuditEntriesUnchanged() {
        when(auditProvider.queryDeltaPage(eq(SINCE), isNull(), anyInt())).thenReturn(
                new DeltaEntityProvider.Page(List.of(Map.of("id", "a1"), Map.of("id", "a2")), null));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, SINCE, null, true));

        assertThat(result.entities().get("auditEntries")).hasSize(2);
        assertThat(result.counts()).containsEntry("auditEntries", 2);
        assertThat(result.entities().get("products")).hasSize(1);
    }

    @Test
    void auditEntryDeltaProvider_isOwnerOnly_andDefaultIsNot() {
        var audit = new com.keevo.sync.sync.application.provider.AuditEntryDeltaProvider(null);
        assertThat(audit.ownerOnly()).isTrue();
        DeltaEntityProvider plain = new DeltaEntityProvider() {
            @Override public String entityKey() { return "x"; }
            @Override public Page queryDeltaPage(Instant since, Position after, int limit) { return null; }
        };
        assertThat(plain.ownerOnly()).isFalse();
    }
}
