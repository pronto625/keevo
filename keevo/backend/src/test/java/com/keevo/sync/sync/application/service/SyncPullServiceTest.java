package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.model.SyncPullResult;
import com.keevo.sync.sync.domain.port.in.DeltaEntityProvider;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
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
import static org.mockito.Mockito.*;

import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;

@ExtendWith(MockitoExtension.class)
class SyncPullServiceTest {

    @Mock private SyncOperationHandlerRegistry handlerRegistry;
    @Mock private SyncOperationsLogRepository logRepository;
    @Mock private ConflictStrategyRegistry conflictStrategyRegistry;
    @Mock private SyncConflictsLogRepository conflictsLogRepository;
    @Mock private com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository syncErrorLogRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private DeltaEntityProvider productProvider;
    @Mock private DeltaEntityProvider clientProvider;

    private SyncPushService service;

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String TENANT_ID = "kv_abc123";

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        lenient().when(productProvider.entityKey()).thenReturn("products");
        lenient().when(clientProvider.entityKey()).thenReturn("clients");
        service = new SyncPushService(handlerRegistry, logRepository, eventPublisher,
                transactionManager, List.of(productProvider, clientProvider),
                conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository);
    }

    @Test
    void pull_withSince_queriesAllProvidersWithTimestamp() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(productProvider.queryDelta(since)).thenReturn(
                List.of(Map.of("id", "p1")));
        when(clientProvider.queryDelta(since)).thenReturn(
                List.of(Map.of("id", "c1"), Map.of("id", "c2")));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));

        assertThat(result.entities()).containsKeys("products", "clients");
        assertThat(result.entities().get("products")).hasSize(1);
        assertThat(result.entities().get("clients")).hasSize(2);
        verify(productProvider).queryDelta(since);
        verify(clientProvider).queryDelta(since);
    }

    @Test
    void pull_withNullSince_queriesAllProvidersWithEpoch() {
        when(productProvider.queryDelta(Instant.EPOCH)).thenReturn(List.of());
        when(clientProvider.queryDelta(Instant.EPOCH)).thenReturn(List.of());

        service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        verify(productProvider).queryDelta(Instant.EPOCH);
        verify(clientProvider).queryDelta(Instant.EPOCH);
    }

    @Test
    void pull_assemblesServerTimestampBeforeQueries() {
        when(productProvider.queryDelta(any())).thenReturn(List.of());
        when(clientProvider.queryDelta(any())).thenReturn(List.of());

        var before = Instant.now();
        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        assertThat(result.serverTimestamp()).isAfterOrEqualTo(before);
        assertThat(result.serverTimestamp()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void pull_aggregatesCountsFromAllProviders() {
        when(productProvider.queryDelta(any())).thenReturn(
                List.of(Map.of("id", "p1"), Map.of("id", "p2")));
        when(clientProvider.queryDelta(any())).thenReturn(
                List.of(Map.of("id", "c1")));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        assertThat(result.counts()).containsEntry("products", 2);
        assertThat(result.counts()).containsEntry("clients", 1);
    }

    @Test
    void pull_emptyDelta_returnsEmptyArraysNotNull() {
        when(productProvider.queryDelta(any())).thenReturn(List.of());
        when(clientProvider.queryDelta(any())).thenReturn(List.of());

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID,
                Instant.parse("2099-01-01T00:00:00Z")));

        assertThat(result.entities().get("products")).isEmpty();
        assertThat(result.entities().get("clients")).isEmpty();
        assertThat(result.counts()).containsEntry("products", 0);
        assertThat(result.counts()).containsEntry("clients", 0);
    }

    @Test
    void pull_oneProviderFails_doesNotAbortOthers() {
        when(productProvider.queryDelta(any())).thenThrow(new RuntimeException("DB error"));
        when(clientProvider.queryDelta(any())).thenReturn(
                List.of(Map.of("id", "c1")));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        assertThat(result.entities().get("products")).isEmpty();
        assertThat(result.counts()).containsEntry("products", 0);
        assertThat(result.entities().get("clients")).hasSize(1);
        assertThat(result.counts()).containsEntry("clients", 1);
    }
}
