package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.model.SyncPullResult;
import com.keevo.shared.application.port.sync.DeltaEntityProvider;
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
    @Mock private com.keevo.sync.sync.domain.port.out.UserSyncStateRepository userSyncStateRepository;

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
                conflictStrategyRegistry, conflictsLogRepository, syncErrorLogRepository, moduleRegistry,
                userSyncStateRepository);
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
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(
                page(List.of(Map.of("id", "p1"))));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(
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
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        verify(productProvider).queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any());
        verify(clientProvider).queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any());
    }

    // A row whose transaction commits just after the providers ran may carry an updated_at a
    // little before "now": the returned cursor keeps a margin so the next pull still sees it.
    @Test
    void pull_serverTimestampKeepsASafetyMarginBeforeTheQueries() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(page(List.of()));

        var before = Instant.now();
        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID,
                Instant.parse("2026-03-20T10:00:00Z")));

        var after = Instant.now();
        assertThat(result.serverTimestamp()).isAfterOrEqualTo(before.minus(SyncPushService.PULL_CURSOR_MARGIN));
        assertThat(result.serverTimestamp()).isBeforeOrEqualTo(after.minus(SyncPushService.PULL_CURSOR_MARGIN));
    }

    @Test
    void pull_aggregatesCountsFromAllProviders() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(
                page(List.of(Map.of("id", "p1"), Map.of("id", "p2"))));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(
                page(List.of(Map.of("id", "c1"))));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null));

        assertThat(result.counts()).containsEntry("products", 2);
        assertThat(result.counts()).containsEntry("clients", 1);
    }

    @Test
    void pull_emptyDelta_returnsEmptyArraysNotNull() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID,
                Instant.parse("2099-01-01T00:00:00Z")));

        assertThat(result.entities().get("products")).isEmpty();
        assertThat(result.entities().get("clients")).isEmpty();
        assertThat(result.counts()).containsEntry("products", 0);
        assertThat(result.counts()).containsEntry("clients", 0);
    }

    @Test
    void pull_oneProviderFails_doesNotAbortOthers() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenThrow(new RuntimeException("DB error"));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(
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
        when(productProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenThrow(new RuntimeException("DB error"));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));

        assertThat(result.serverTimestamp()).isEqualTo(since);
    }

    @Test
    void pull_truncatedProvider_returnsHasMoreAndResumesFromCursor() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var position = new DeltaEntityProvider.Position(Instant.parse("2026-03-21T08:00:00Z"), "p1000");
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any()))
                .thenReturn(page(List.of(Map.of("id", "p1")), position));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any()))
                .thenReturn(page(List.of(Map.of("id", "c1"))));

        var first = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));
        var afterFirstPage = Instant.now();

        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        // Older apps ignore hasMore and save serverTimestamp: it must not skip the unsent rows.
        assertThat(first.serverTimestamp()).isEqualTo(position.cursor());

        when(productProvider.queryDeltaPage(eq(since), eq(position), anyInt(), any()))
                .thenReturn(page(List.of(Map.of("id", "p1001"))));

        var second = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, first.nextCursor()));

        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.entities().get("products")).hasSize(1);
        // The finished provider is not queried again.
        assertThat(second.entities().get("clients")).isEmpty();
        verify(clientProvider, times(1)).queryDeltaPage(any(), any(), anyInt(), any());
        // Final cursor: the first page's start (minus margin), not the last page's —
        // clients changed while paging must still be pulled next time.
        assertThat(second.serverTimestamp()).isBeforeOrEqualTo(afterFirstPage.minus(SyncPushService.PULL_CURSOR_MARGIN));
    }

    @Test
    void pull_failureOnALaterPage_keepsTheCursorAtSince() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var position = new DeltaEntityProvider.Position(Instant.parse("2026-03-21T08:00:00Z"), "p1000");
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any()))
                .thenReturn(page(List.of(Map.of("id", "p1")), position));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        var first = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));

        when(productProvider.queryDeltaPage(eq(since), eq(position), anyInt(), any()))
                .thenThrow(new RuntimeException("DB error"));
        var second = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, first.nextCursor()));

        assertThat(second.hasMore()).isFalse();
        assertThat(second.serverTimestamp()).isEqualTo(since);
    }

    @Test
    void pull_unreadableCursor_restartsFromSince() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, "not-a-cursor"));

        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void story1310a_disabledModuleProviderSkipped_readOnlyStillPulled() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(productProvider.moduleId()).thenReturn("catalog");
        when(clientProvider.moduleId()).thenReturn("counting");
        when(moduleRegistry.moduleStates(TENANT_ID)).thenReturn(Map.of(
                "catalog", com.keevo.shared.module.ModuleState.READ_ONLY,
                "counting", com.keevo.shared.module.ModuleState.DISABLED));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(
                page(List.of(Map.of("id", "p1"))));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));

        assertThat(result.entities().get("products")).hasSize(1);
        assertThat(result.entities().get("clients")).isEmpty();
        assertThat(result.hasMore()).isFalse();
        verify(clientProvider, never()).queryDeltaPage(any(), any(), anyInt(), any());
    }

    @Test
    void story1310a_moduleStatesUnreadable_everythingPulled() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(clientProvider.moduleId()).thenReturn("counting");
        when(moduleRegistry.moduleStates(TENANT_ID)).thenThrow(new IllegalStateException("db"));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(
                page(List.of(Map.of("id", "c1"))));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since));

        assertThat(result.entities().get("clients")).hasSize(1);
    }

    // ── Story 16.13 — scope through the command, re-seed on a scope change ────────────

    private static final String DEVICE = "device-1";
    private static final UUID STORE_A = UUID.randomUUID();
    private static final UUID STORE_B = UUID.randomUUID();

    private static com.keevo.sync.sync.domain.model.UserSyncState stored(UUID storeId, boolean known) {
        return stored(true, storeId, known);
    }

    /** Story 16.13 (reprise) — the visibility marker of an employee pull without permissions (this test's pulls). */
    private static final String CURRENT_VISIBILITY = SyncPushService.ScopeKey.visibilityMarker(
            com.keevo.shared.application.port.sync.PullScope.employee(STORE_A, ACTOR_ID));

    private static com.keevo.sync.sync.domain.model.UserSyncState stored(boolean restricted, UUID storeId,
                                                                         boolean known) {
        return new com.keevo.sync.sync.domain.model.UserSyncState(DEVICE, ACTOR_ID, TENANT_ID, null, null,
                Instant.now(), restricted, storeId, known, null, restricted ? CURRENT_VISIBILITY : null);
    }

    private static SyncUseCase.PullCommand ownerPull(Instant since, String cursor) {
        return new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, cursor, true,
                com.keevo.shared.application.port.sync.PullScope.unrestricted(ACTOR_ID), DEVICE);
    }

    private static SyncUseCase.PullCommand employeePull(UUID store, Instant since, String cursor) {
        return new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, cursor, false,
                com.keevo.shared.application.port.sync.PullScope.employee(store, ACTOR_ID), DEVICE);
    }

    @Test
    void story1613_scopeOfTheCommandReachesEveryProvider_andTheResult() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var scope = com.keevo.shared.application.port.sync.PullScope.employee(STORE_A, ACTOR_ID);
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), eq(scope))).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), eq(scope))).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_A, since, null));

        assertThat(result.scope()).isEqualTo(scope);
        assertThat(result.reseed()).isFalse();
        verify(userSyncStateRepository).rememberPullScope(DEVICE, ACTOR_ID, true, STORE_A);
    }

    @Test
    void story1613_legacyCommandFailsClosed() {
        when(productProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, null, null, true));

        assertThat(result.scope()).isEqualTo(com.keevo.shared.application.port.sync.PullScope.NONE);
        verify(productProvider).queryDeltaPage(any(), any(), anyInt(),
                eq(com.keevo.shared.application.port.sync.PullScope.NONE));
        verifyNoInteractions(userSyncStateRepository); // no device: nothing read nor remembered
    }

    @Test
    void story1613_sameScope_incrementalPull_noReseed() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(STORE_A, true)));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_A, since, null));

        assertThat(result.reseed()).isFalse();
        // known and unchanged: nothing written
        verify(userSyncStateRepository, never()).rememberPullScope(any(), any(), anyBoolean(), any());
    }

    @Test
    void story1613_firstPullOfADevice_isNotAScopeChange() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(null, false)));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_B, since, null));

        assertThat(result.reseed()).isFalse();
        verify(userSyncStateRepository).rememberPullScope(DEVICE, ACTOR_ID, true, STORE_B);
    }

    @Test
    void story1613_scopeChanged_pullRestartsFromEpoch_onEveryPage_scopeSavedAfterTheLastPage() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var position = new DeltaEntityProvider.Position(Instant.parse("2026-01-01T00:00:00Z"), "p1");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(STORE_A, true)));
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any()))
                .thenReturn(page(List.of(Map.of("id", "p1")), position));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var first = service.pull(employeePull(STORE_B, since, null));

        assertThat(first.reseed()).isTrue();
        assertThat(first.hasMore()).isTrue();
        verify(userSyncStateRepository, never()).rememberPullScope(any(), any(), anyBoolean(), any());

        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), eq(position), anyInt(), any()))
                .thenReturn(page(List.of(Map.of("id", "p2"))));
        var second = service.pull(employeePull(STORE_B, since, first.nextCursor()));

        assertThat(second.reseed()).isTrue();
        assertThat(second.hasMore()).isFalse();
        assertThat(second.entities().get("products")).extracting(r -> r.get("id")).containsExactly("p2");
        verify(userSyncStateRepository, times(1)).findByDeviceId(DEVICE); // decided once, carried by the cursor
        verify(userSyncStateRepository).rememberPullScope(DEVICE, ACTOR_ID, true, STORE_B);
    }

    @Test
    void story1613_failedReseedPull_keepsTheOldScope() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(STORE_A, true)));
        when(productProvider.queryDeltaPage(any(), any(), anyInt(), any())).thenThrow(new RuntimeException("db"));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_B, since, null));

        assertThat(result.reseed()).isTrue();
        assertThat(result.serverTimestamp()).isEqualTo(Instant.EPOCH);
        verify(userSyncStateRepository, never()).rememberPullScope(any(), any(), anyBoolean(), any());
    }

    @Test
    void story1613_anotherUsersDeviceRow_isNotAScopeChange() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var foreign = new com.keevo.sync.sync.domain.model.UserSyncState(DEVICE, UUID.randomUUID(), TENANT_ID,
                null, null, Instant.now(), true, STORE_A, true);
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(foreign));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        assertThat(service.pull(employeePull(STORE_B, since, null)).reseed()).isFalse();
    }

    @Test
    void story1613_cursorFromBeforeTheReseedFlag_stillDecodes() {
        String legacy = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "2026-03-20T10:00:00Z\n0\nproducts=2026-01-01T00:00:00Z|p1\nclients=-"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var decoded = SyncPushService.PullCursor.decode(legacy);

        assertThat(decoded).isNotNull();
        assertThat(decoded.failed()).isFalse();
        assertThat(decoded.reseed()).isFalse();
        assertThat(decoded.remember()).isTrue();
        assertThat(decoded.scope()).isNull(); // no pinned scope: the pull goes on without a scope check
        assertThat(decoded.isDone("clients")).isTrue();
    }

    // ── Story 16.13 review — restricted flag, mid-pull scope change ────────────

    @Test
    void story1613_ownerToStorelessEmployee_isAScopeChange() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(false, null, true)));
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(null, since, null));

        assertThat(result.reseed()).isTrue();
        verify(userSyncStateRepository).rememberPullScope(DEVICE, ACTOR_ID, true, null);
    }

    @Test
    void story1613_storelessEmployeePromotedToOwner_isAScopeChange() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(true, null, true)));
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(ownerPull(since, null));

        assertThat(result.reseed()).isTrue();
        verify(userSyncStateRepository).rememberPullScope(DEVICE, ACTOR_ID, false, null);
    }

    @Test
    void story1613_scopeChangedBetweenTwoPages_restartsThePullAsAReseed() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var position = new DeltaEntityProvider.Position(Instant.parse("2026-03-21T00:00:00Z"), "p1");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(STORE_A, true)));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any()))
                .thenReturn(page(List.of(Map.of("id", "p1")), position));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var first = service.pull(employeePull(STORE_A, since, null));
        assertThat(first.reseed()).isFalse();
        assertThat(first.hasMore()).isTrue();

        // reassigned to B before the next page: A's keyset position must not be reused
        var scopeB = com.keevo.shared.application.port.sync.PullScope.employee(STORE_B, ACTOR_ID);
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), eq(scopeB)))
                .thenReturn(page(List.of(Map.of("id", "b1"))));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), eq(scopeB))).thenReturn(page(List.of()));

        var second = service.pull(employeePull(STORE_B, since, first.nextCursor()));

        assertThat(second.reseed()).isTrue();
        assertThat(second.hasMore()).isFalse();
        assertThat(second.entities().get("products")).extracting(r -> r.get("id")).containsExactly("b1");
        verify(productProvider, never()).queryDeltaPage(any(), eq(position), anyInt(), any());
        verify(userSyncStateRepository).rememberPullScope(DEVICE, ACTOR_ID, true, STORE_B);
    }

    @Test
    void story1613_cursorPinsTheScope_roundTrip() {
        var cursor = new SyncPushService.PullCursor(Instant.parse("2026-03-20T10:00:00Z"), false, true, false,
                new SyncPushService.ScopeKey(true, STORE_A), new java.util.HashMap<>(Map.of("clients",
                SyncPushService.PullCursor.DONE)));

        var decoded = SyncPushService.PullCursor.decode(cursor.encode());

        assertThat(decoded.reseed()).isTrue();
        assertThat(decoded.remember()).isFalse();
        assertThat(decoded.scope()).isEqualTo(new SyncPushService.ScopeKey(true, STORE_A));
        assertThat(decoded.isDone("clients")).isTrue();
        var owner = new SyncPushService.PullCursor(Instant.EPOCH, true, false, true,
                new SyncPushService.ScopeKey(false, null), new java.util.HashMap<>());
        assertThat(SyncPushService.PullCursor.decode(owner.encode()).scope())
                .isEqualTo(new SyncPushService.ScopeKey(false, null));
    }

    // ── Story 20.2 — client visible stores fingerprint ───────────────────────────────────────────────

    private com.keevo.store.api.StoreQueryPort sharing(java.util.Set<UUID> visible) {
        var storeQuery = mock(com.keevo.store.api.StoreQueryPort.class);
        when(storeQuery.clientVisibleStoreIds(TENANT_ID, STORE_A)).thenReturn(visible);
        service.setStoreQuery(storeQuery);
        return storeQuery;
    }

    private static com.keevo.sync.sync.domain.model.UserSyncState storedWithClients(java.util.Set<UUID> visible) {
        return new com.keevo.sync.sync.domain.model.UserSyncState(DEVICE, ACTOR_ID, TENANT_ID, null, null,
                Instant.now(), true, STORE_A, true, SyncPushService.ScopeKey.fingerprint(visible), CURRENT_VISIBILITY);
    }

    @Test
    void story202_sharingTurnedOn_nextPullReseedsWithTheOtherStoresClients() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var visible = java.util.Set.of(STORE_A, STORE_B);
        var storeQuery = sharing(visible);
        when(userSyncStateRepository.findByDeviceId(DEVICE))
                .thenReturn(java.util.Optional.of(storedWithClients(java.util.Set.of(STORE_A))));
        var scopeCaptor = org.mockito.ArgumentCaptor.forClass(com.keevo.shared.application.port.sync.PullScope.class);
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), scopeCaptor.capture()))
                .thenReturn(page(List.of(Map.of("id", "client-of-B"))));

        var result = service.pull(employeePull(STORE_A, since, null));

        assertThat(result.reseed()).isTrue();
        assertThat(result.entities().get("clients")).hasSize(1);
        // resolved once, carried to the clients provider
        assertThat(scopeCaptor.getValue().clientStoreIds()).isEqualTo(visible);
        verify(storeQuery, times(1)).clientVisibleStoreIds(TENANT_ID, STORE_A);
        verify(userSyncStateRepository).rememberPullScope(DEVICE, ACTOR_ID, true, STORE_A,
                SyncPushService.ScopeKey.fingerprint(visible));
    }

    @Test
    void story202_sharingUnchanged_incrementalPull_nothingWritten() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        sharing(java.util.Set.of(STORE_A, STORE_B));
        when(userSyncStateRepository.findByDeviceId(DEVICE))
                .thenReturn(java.util.Optional.of(storedWithClients(java.util.Set.of(STORE_B, STORE_A))));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_A, since, null));

        assertThat(result.reseed()).isFalse();
        verify(userSyncStateRepository, never()).rememberPullScope(any(), any(), anyBoolean(), any(), any());
        verify(userSyncStateRepository, never()).rememberPullScope(any(), any(), anyBoolean(), any());
    }

    @Test
    void story202_fingerprintNotKnownYet_isNotAChange_butIsRemembered() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        var visible = java.util.Set.of(STORE_A);
        sharing(visible);
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(STORE_A, true)));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_A, since, null));

        assertThat(result.reseed()).isFalse();
        verify(userSyncStateRepository).rememberPullScope(DEVICE, ACTOR_ID, true, STORE_A,
                SyncPushService.ScopeKey.fingerprint(visible));
    }

    @Test
    void story202_cursorCarriesTheFingerprint_ownerHasNone() {
        var key = new SyncPushService.ScopeKey(true, STORE_A, SyncPushService.ScopeKey.fingerprint(java.util.Set.of(STORE_A)));
        var cursor = new SyncPushService.PullCursor(Instant.parse("2026-03-20T10:00:00Z"), false, false, true, key,
                new java.util.HashMap<>());
        assertThat(SyncPushService.PullCursor.decode(cursor.encode()).scope()).isEqualTo(key);
        assertThat(SyncPushService.ScopeKey.of(com.keevo.shared.application.port.sync.PullScope.unrestricted(ACTOR_ID)
                .withClientStoreIds(java.util.Set.of(STORE_A))).clientStores()).isNull();
        // a cursor from before 20.2 (no fingerprint) is the same scope
        assertThat(new SyncPushService.ScopeKey(true, STORE_A).sameAs(key)).isTrue();
        assertThat(key.sameAs(new SyncPushService.ScopeKey(true, STORE_A,
                SyncPushService.ScopeKey.fingerprint(java.util.Set.of(STORE_A, STORE_B))))).isFalse();
    }

    // ── Story 16.13 (reprise 10/11/2026) — visibility rules (store-scoped stock, purchase prices) ─────────────

    private static com.keevo.sync.sync.domain.model.UserSyncState storedVisibility(String visibility) {
        return new com.keevo.sync.sync.domain.model.UserSyncState(DEVICE, ACTOR_ID, TENANT_ID, null, null,
                Instant.now(), true, STORE_A, true, null, visibility);
    }

    @Test
    void story1613r_restrictedDeviceFromBeforeTheRules_reseedsOnce_andRemembersTheMarker() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(storedVisibility(null)));
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_A, since, null));

        assertThat(result.reseed()).isTrue();
        verify(userSyncStateRepository).rememberPullVisibility(DEVICE, ACTOR_ID, CURRENT_VISIBILITY);
    }

    @Test
    void story1613r_purchasePricePermissionGranted_reseeds() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE))
                .thenReturn(java.util.Optional.of(storedVisibility(CURRENT_VISIBILITY))); // pulled as an employee
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        var manager = com.keevo.shared.application.port.sync.PullScope.employee(STORE_A, ACTOR_ID)
                .withPermissions(java.util.Set.of("PURCHASE_PRICE_VIEW"));

        var result = service.pull(new SyncUseCase.PullCommand(ACTOR_ID, TENANT_ID, since, null, false, manager, DEVICE));

        assertThat(result.reseed()).isTrue();
        verify(userSyncStateRepository).rememberPullVisibility(DEVICE, ACTOR_ID,
                SyncPushService.ScopeKey.visibilityMarker(manager));
    }

    @Test
    void story1613r_sameRules_incremental_nothingWritten() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE))
                .thenReturn(java.util.Optional.of(storedVisibility(CURRENT_VISIBILITY)));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_A, since, null));

        assertThat(result.reseed()).isFalse();
        verify(userSyncStateRepository, never()).rememberPullVisibility(any(), any(), any());
    }

    @Test
    void story1613r_fullPullWithoutSince_isNotFlaggedReseed_butRemembersTheMarker() {
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(storedVisibility(null)));
        when(productProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(Instant.EPOCH), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(employeePull(STORE_A, null, null));

        assertThat(result.reseed()).isFalse();
        verify(userSyncStateRepository).rememberPullVisibility(DEVICE, ACTOR_ID, CURRENT_VISIBILITY);
    }

    @Test
    void story1613r_ownerPull_carriesNoMarker_neverReseedsForIt() {
        var since = Instant.parse("2026-03-20T10:00:00Z");
        when(userSyncStateRepository.findByDeviceId(DEVICE)).thenReturn(java.util.Optional.of(stored(false, null, true)));
        when(productProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));
        when(clientProvider.queryDeltaPage(eq(since), isNull(), anyInt(), any())).thenReturn(page(List.of()));

        var result = service.pull(ownerPull(since, null));

        assertThat(result.reseed()).isFalse();
    }

    @Test
    void story1613r_cursorKeepsTheMarker_evenWithoutClientStores() {
        var key = new SyncPushService.ScopeKey(true, STORE_A, null, CURRENT_VISIBILITY);
        var cursor = new SyncPushService.PullCursor(Instant.parse("2026-03-20T10:00:00Z"), false, false, true, key,
                new java.util.HashMap<>());
        assertThat(SyncPushService.PullCursor.decode(cursor.encode()).scope()).isEqualTo(key);
        var other = new SyncPushService.ScopeKey(true, STORE_A, null, "v1613r;pp=1");
        assertThat(key.sameAs(other)).isFalse();
        assertThat(key.sameAs(new SyncPushService.ScopeKey(true, STORE_A))).isTrue(); // unknown marker: same
    }
}
