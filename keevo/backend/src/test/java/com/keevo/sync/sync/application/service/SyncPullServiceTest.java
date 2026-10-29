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
import static org.mockito.ArgumentMatchers.*;
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
    @Mock private com.keevo.shared.module.ModuleRegistry moduleRegistry;
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
                conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository, moduleRegistry);
    }

    private static DeltaEntityProvider.Page page(List<Map<String, Object>> rows) {
        return new DeltaEntityProvider.Page(rows, null);
    }

    private static DeltaEntityProvider.Page page(List<Map<String, Object>> rows, DeltaEntityProvider.Position next) {
        return new DeltaEntityProvider.Page(rows, next);
    }

    @Test
    void pull_withSince_queriesAllProvidersWithTimestamp() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt())).thenReturn(
                page(List.of(Map.of("id", "p1"))));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt())).thenReturn(
                page(List.of(Map.of("id", "c1"), Map.of("id", "c2"))));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));

        assertThat(result.entities()).containsKeys("products", "clients");
        assertThat(result.entities().get("products")).hasSize(1);
        assertThat(result.entities().get("clients")).hasSize(2);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void pull_withNullSince_queriesAllProvidersWithEpoch() {
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt())).thenReturn(page(List.of()));

        service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        verify(productProvider).queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt());
        verify(clientProvider).queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt());
    }

    // A row whose transaction commits just after the providers ran may carry an updated_at a
    // little before "now": the returned cursor keeps a margin so the next pull still sees it.
    @Test
    void pull_serverTimestampKeepsASafetyMarginBeforeTheQueries() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt())).thenReturn(page(List.of()));

        var before = Instant.now();
        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID,
                Instant.parse("2026-03-20T10:00:00Z")));

        var after = Instant.now();
        assertThat(result.serverTimestamp()).isAfterOrEqualTo(before.minus(SyncPushService.PULL_CURSOR_MARGIN));
        assertThat(result.serverTimestamp()).isBeforeOrEqualTo(after.minus(SyncPushService.PULL_CURSOR_MARGIN));
    }

    @Test
    void pull_aggregatesCountsFromAllProviders() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt())).thenReturn(
                page(List.of(Map.of("id", "p1"), Map.of("id", "p2"))));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt())).thenReturn(
                page(List.of(Map.of("id", "c1"))));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        assertThat(result.counts()).containsEntry("products", 2);
        assertThat(result.counts()).containsEntry("clients", 1);
    }

    @Test
    void pull_emptyDelta_returnsEmptyArraysNotNull() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt())).thenReturn(page(List.of()));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID,
                Instant.parse("2099-01-01T00:00:00Z")));

        assertThat(result.entities().get("products")).isEmpty();
        assertThat(result.entities().get("clients")).isEmpty();
        assertThat(result.counts()).containsEntry("products", 0);
        assertThat(result.counts()).containsEntry("clients", 0);
    }

    @Test
    void pull_oneProviderFails_doesNotAbortOthers() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt())).thenThrow(new RuntimeException("DB error"));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt())).thenReturn(
                page(List.of(Map.of("id", "c1"))));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        assertThat(result.entities().get("products")).isEmpty();
        assertThat(result.counts()).containsEntry("products", 0);
        assertThat(result.entities().get("clients")).hasSize(1);
        assertThat(result.counts()).containsEntry("clients", 1);
    }

    // The failing provider's rows were never sent: advancing the cursor would skip them forever.
    @Test
    void pull_oneProviderFails_cursorDoesNotAdvance() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(productProvider.queryDeltaPage(any(), any(), anyInt())).thenThrow(new RuntimeException("DB error"));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt())).thenReturn(page(List.of()));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));

        assertThat(result.serverTimestamp()).isEqualTo(since);
    }

    @Test
    void pull_truncatedProvider_returnsHasMoreAndResumesFromCursor() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var position = new DeltaEntityProvider.Position(Instant.parse("2026-03-21T08:00:00Z"), "p1000");
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt()))
                .thenReturn(page(List.of(Map.of("id", "p1")), position));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt()))
                .thenReturn(page(List.of(Map.of("id", "c1"))));

        var first = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));
        var afterFirstPage = Instant.now();

        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        // Older apps ignore hasMore and save serverTimestamp: it must not skip the unsent rows.
        assertThat(first.serverTimestamp()).isEqualTo(position.cursor());

        when(productProvider.queryDeltaPage(eq(since), eq(position), anyInt()))
                .thenReturn(page(List.of(Map.of("id", "p1001"))));

        var second = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, first.nextCursor()));

        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.entities().get("products")).hasSize(1);
        // The finished provider is not queried again.
        assertThat(second.entities().get("clients")).isEmpty();
        verify(clientProvider, times(1)).queryDeltaPage(any(), any(), anyInt());
        // Final cursor: the first page's start (minus margin), not the last page's —
        // clients changed while paging must still be pulled next time.
        assertThat(second.serverTimestamp()).isBeforeOrEqualTo(afterFirstPage.minus(SyncPushService.PULL_CURSOR_MARGIN));
    }

    @Test
    void pull_failureOnALaterPage_keepsTheCursorAtSince() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var position = new DeltaEntityProvider.Position(Instant.parse("2026-03-21T08:00:00Z"), "p1000");
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt()))
                .thenReturn(page(List.of(Map.of("id", "p1")), position));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt())).thenReturn(page(List.of()));
        var first = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));

        when(productProvider.queryDeltaPage(eq(since), eq(position), anyInt()))
                .thenThrow(new RuntimeException("DB error"));
        var second = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, first.nextCursor()));

        assertThat(second.hasMore()).isFalse();
        assertThat(second.serverTimestamp()).isEqualTo(since);
    }

    @Test
    void pull_unreadableCursor_restartsFromSince() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt())).thenReturn(page(List.of()));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, "not-a-cursor"));

        assertThat(result.hasMore()).isFalse();
    }
}
