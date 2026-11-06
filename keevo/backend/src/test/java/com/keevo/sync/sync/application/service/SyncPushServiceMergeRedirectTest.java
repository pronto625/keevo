package com.keevo.sync.sync.application.service;

import com.keevo.catalog.api.ProductQueryPort;
import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationHandler;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import com.keevo.shared.module.ModuleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story 20.1 — pushed operations on a merged product land on its merge target: one {@code merged_into_id} read per
 * batch (set-based), archive / restore never redirected, a redirected product edit drops the merged name and SKU.
 */
@DisplayName("SyncPushService — redirection des produits fusionnés (20.1)")
class SyncPushServiceMergeRedirectTest {

    private static final String TENANT = "kv_merge2";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private final SyncOperationHandlerRegistry registry = mock(SyncOperationHandlerRegistry.class);
    private final SyncOperationsLogRepository logRepository = mock(SyncOperationsLogRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ModuleRegistry moduleRegistry = mock(ModuleRegistry.class);
    private final ProductQueryPort productQuery = mock(ProductQueryPort.class);
    private final List<SyncOperation> handled = new ArrayList<>();
    private SyncPushService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(moduleRegistry.isKnownTenant(any())).thenReturn(true);
        when(moduleRegistry.moduleStates(any())).thenReturn(
                ModuleStatesFixture.enabled(Set.of("catalog", "pos", "inventory", "core", "store", "sync")));
        SyncOperationHandler handler = mock(SyncOperationHandler.class);
        when(handler.moduleId()).thenReturn("pos");
        when(handler.handle(any(), eq(ACTOR), eq(TENANT))).thenAnswer(invocation -> {
            SyncOperation op = invocation.getArgument(0);
            handled.add(op);
            return new SyncOperationResult(op.operationId(), SyncOperationStatus.APPLIED, null, null);
        });
        when(registry.resolve(any())).thenReturn(Optional.of(handler));
        when(logRepository.findMergedProductTargets(anyCollection())).thenReturn(Map.of());
        when(productQuery.resolveMergedTargets(eq(TENANT), anyCollection())).thenReturn(Map.of(A, B));
        service = new SyncPushService(registry, logRepository, mock(ApplicationEventPublisher.class),
                transactionManager, List.of(), mock(ConflictStrategyRegistry.class),
                mock(SyncConflictsLogRepository.class), mock(SyncErrorLogRepository.class), moduleRegistry,
                mock(UserSyncStateRepository.class), productQuery);
    }

    private static SyncOperation op(String id, String type, Map<String, Object> payload, int second) {
        return new SyncOperation(id, type, id, payload, Instant.parse("2026-11-06T10:00:00Z").plusSeconds(second));
    }

    private static Map<String, Object> sale(UUID productId) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", productId.toString());
        item.put("variantId", UUID.randomUUID().toString());
        item.put("quantity", 1);
        return Map.of("items", List.of(item));
    }

    private SyncOperation handled(String id) {
        return handled.stream().filter(o -> o.operationId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("ventes hors ligne sur A → appliquées sur B, une seule lecture des fusions pour le lot")
    void offline_sales_on_a_merged_product_land_on_the_target() {
        var result = service.pushBatch(new SyncUseCase.PushBatchCommand(ACTOR, TENANT, "device-1", List.of(
                op("s1", "CREATE_SALE", sale(A), 1),
                op("s2", "CREATE_SALE", sale(A), 2),
                op("s3", "CREATE_SALE", sale(UUID.randomUUID()), 3))));

        assertThat(result.results()).allMatch(r -> r.status() == SyncOperationStatus.APPLIED);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) handled("s1").payload().get("items");
        assertThat(items.get(0).get("productId")).isEqualTo(B.toString());
        assertThat(items.get(0)).as("a variant of A is never one of B").doesNotContainKey("variantId");
        @SuppressWarnings("unchecked")
        var untouched = (List<Map<String, Object>>) handled("s3").payload().get("items");
        assertThat(untouched.get(0)).containsKey("variantId");
        verify(productQuery, times(1)).resolveMergedTargets(eq(TENANT), anyCollection());
    }

    @Test
    @DisplayName("UPDATE_PRODUCT sur A → B, sans le nom ni la référence de A ; archivage / restauration non redirigés")
    void edits_redirected_archive_and_restore_not() {
        Map<String, Object> update = new HashMap<>();
        update.put("productId", A.toString());
        update.put("name", "Savon");
        update.put("sku", "KEV-SAVON1");
        update.put("price", 700);
        update.put("barcode", "6111234567890");
        update.put("trackStock", false);

        service.pushBatch(new SyncUseCase.PushBatchCommand(ACTOR, TENANT, "device-1", List.of(
                op("u1", "UPDATE_PRODUCT", update, 1),
                op("x1", "UNARCHIVE_PRODUCT", Map.of("productId", A.toString()), 2),
                op("x2", "ARCHIVE_PRODUCT", Map.of("productId", A.toString()), 3))));

        assertThat(handled("u1").payload()).containsEntry("productId", B.toString()).containsEntry("price", 700)
                .doesNotContainKeys("name", "sku", "barcode", "trackStock");
        assertThat(handled("x1").payload()).containsEntry("productId", A.toString());
        assertThat(handled("x2").payload()).containsEntry("productId", A.toString());
    }

    @Test
    @DisplayName("un alias de nom vers A suit aussi la fusion (A → B)")
    void name_alias_then_merge() {
        UUID local = UUID.randomUUID();
        when(logRepository.findMergedProductTargets(anyCollection()))
                .thenReturn(Map.of(local.toString(), A.toString()));

        service.pushBatch(new SyncUseCase.PushBatchCommand(ACTOR, TENANT, "device-1", List.of(
                op("s1", "CREATE_SALE", sale(local), 1))));

        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) handled("s1").payload().get("items");
        assertThat(items.get(0).get("productId")).isEqualTo(B.toString());
    }

    @Test
    @DisplayName("lecture des fusions en échec → le lot passe sans redirection")
    void failed_read_never_blocks_the_push() {
        when(productQuery.resolveMergedTargets(eq(TENANT), anyCollection())).thenThrow(new IllegalStateException("db"));

        var result = service.pushBatch(new SyncUseCase.PushBatchCommand(ACTOR, TENANT, "device-1", List.of(
                op("s1", "CREATE_SALE", sale(A), 1))));

        assertThat(result.results().get(0).status()).isEqualTo(SyncOperationStatus.APPLIED);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) handled("s1").payload().get("items");
        assertThat(items.get(0).get("productId")).isEqualTo(A.toString());
    }
}
