package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.application.port.sync.PullScope;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.sync.sync.application.service.SyncOperationHandlerRegistry;
import com.keevo.sync.sync.application.service.SyncPushService;
import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.domain.port.in.SyncUseCase;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncErrorLogRepository;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story 16.10 — the transfers delta pull of an EMPLOYEE of store A only carries transfers A sent
 * or received (Story 16.13: scope carried by the pull command), on a real PostgreSQL, keyset-paged one row at a time: out-of-scope rows sit between
 * in-scope cursors and inside a same-cursor group (id tie-break), and are never sent, without gap
 * or duplicate. The OWNER pulls every row. Also checked end to end through {@link SyncPushService#pull}.
 *
 * <p>Commits a tenant schema, dropped in {@link #dropSchema()}.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({TenantSchemaProvisioner.class, com.keevo.shared.module.adapter.out.persistence.JdbcPlanCatalog.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Transfers delta pull is scoped to the employee's store (Story 16.10)")
class StockTransferStoreScopePostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "kv_tss001";
    private static final String T = "\"" + SCHEMA + "\".";
    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired TenantSchemaProvisioner provisioner;

    private final UUID storeA = UUID.randomUUID();
    private final UUID storeB = UUID.randomUUID();
    private final UUID storeC = UUID.randomUUID();
    // Same-cursor group (T0+90 s), ids ordered as text: in-scope, out-of-scope, in-scope.
    private static final UUID TIE_IN_1 = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TIE_OUT = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final UUID TIE_IN_2 = UUID.fromString("90000000-0000-0000-0000-000000000009");

    private UUID aToB;
    private UUID bToC;
    private UUID bToA;
    private UUID cToA;

    @BeforeEach
    void seed() {
        provisioner.provision("tenant-" + SCHEMA, SCHEMA);
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO " + T + "products (id, name, price, sku) VALUES (?, 'Produit', 100, 'SKU-TSS-1')", product);
        aToB = insertTransfer(UUID.randomUUID(), product, storeA, storeB, T0);
        bToC = insertTransfer(UUID.randomUUID(), product, storeB, storeC, T0.plusSeconds(30)); // between in-scope rows
        bToA = insertTransfer(UUID.randomUUID(), product, storeB, storeA, T0.plusSeconds(60));
        insertTransfer(TIE_IN_1, product, storeA, storeC, T0.plusSeconds(90));
        insertTransfer(TIE_OUT, product, storeC, storeB, T0.plusSeconds(90));
        insertTransfer(TIE_IN_2, product, storeC, storeA, T0.plusSeconds(90));
        cToA = insertTransfer(UUID.randomUUID(), product, storeC, storeA, T0.plusSeconds(120));
    }

    private List<String> inScope() {
        return ids(aToB, bToA, TIE_IN_1, TIE_IN_2, cToA);
    }

    private List<String> everyRow() {
        return ids(aToB, bToC, bToA, TIE_IN_1, TIE_OUT, TIE_IN_2, cToA);
    }

    private static List<String> ids(UUID... ids) {
        return java.util.Arrays.stream(ids).map(UUID::toString).toList();
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    @DisplayName("employee of A: exactly the in-scope rows, in order, across one-row pages")
    void employeePullsOnlyOwnStoreTransfers() {
        PullScope scope = PullScope.employee(storeA, UUID.randomUUID());

        assertThat(pullAll(1, scope)).containsExactlyElementsOf(inScope());
        assertThat(pullAll(2, scope)).containsExactlyElementsOf(inScope());
        assertThat(pullAll(DeltaEntityProvider.PAGE_SIZE, scope)).containsExactlyElementsOf(inScope());
    }

    @Test
    @DisplayName("owner: every row, in order, across one-row pages")
    void ownerPullsEveryTransfer() {
        PullScope scope = PullScope.unrestricted(UUID.randomUUID());

        assertThat(pullAll(1, scope)).containsExactlyElementsOf(everyRow());
        assertThat(pullAll(DeltaEntityProvider.PAGE_SIZE, scope)).containsExactlyElementsOf(everyRow());
    }

    @Test
    @DisplayName("employee: a page holding the last in-scope row has no next page")
    void employeeLastPageHasNoNext() {
        var provider = new StockTransferDeltaProvider(em);

        var page = inTenant(() -> provider.queryDeltaPage(Instant.EPOCH, null, inScope().size(),
                PullScope.employee(storeA, UUID.randomUUID())));

        assertThat(page.rows()).extracting(r -> (String) r.get("id")).containsExactlyElementsOf(inScope());
        assertThat(page.hasMore()).isFalse();
    }

    // ── End to end through SyncPushService.pull ─────────────────────────

    @Test
    @DisplayName("pull of an employee of A: only A's transfers, counts consistent, pull not failed")
    void syncPullEmployee() {
        var result = syncPull(false, PullScope.employee(storeA, UUID.randomUUID()));

        assertThat(result.entities().get("stockTransfers")).extracting(r -> (String) r.get("id"))
                .containsExactlyElementsOf(inScope());
        assertThat(result.counts()).containsEntry("stockTransfers", inScope().size());
        assertThat(result.hasMore()).isFalse();
        // A failed provider returns serverTimestamp = since (EPOCH): the cursor must advance.
        assertThat(result.serverTimestamp()).isAfter(T0);
    }

    @Test
    @DisplayName("pull of the owner: every transfer")
    void syncPullOwner() {
        var result = syncPull(true, PullScope.unrestricted(UUID.randomUUID()));

        assertThat(result.entities().get("stockTransfers")).extracting(r -> (String) r.get("id"))
                .containsExactlyElementsOf(everyRow());
        assertThat(result.counts()).containsEntry("stockTransfers", everyRow().size());
        assertThat(result.hasMore()).isFalse();
        assertThat(result.serverTimestamp()).isAfter(T0);
    }

    @Test
    @DisplayName("pull of an employee without a store: no transfers, but the pull is not failed")
    void syncPullEmployeeWithoutStore() {
        var result = syncPull(false, PullScope.employee(null, UUID.randomUUID()));

        assertThat(result.entities().get("stockTransfers")).isEmpty();
        assertThat(result.counts()).containsEntry("stockTransfers", 0);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.serverTimestamp()).isAfter(T0);
    }

    private com.keevo.sync.sync.domain.model.SyncPullResult syncPull(boolean owner, PullScope scope) {
        var service = new SyncPushService(
                mock(SyncOperationHandlerRegistry.class), mock(SyncOperationsLogRepository.class),
                mock(ApplicationEventPublisher.class), txManager, List.of(new StockTransferDeltaProvider(em)),
                mock(ConflictStrategyRegistry.class), mock(SyncConflictsLogRepository.class),
                mock(SyncErrorLogRepository.class), mock(ModuleRegistry.class), // no module state: all pulled
                mock(com.keevo.sync.sync.domain.port.out.UserSyncStateRepository.class));
        // The pull's per-provider read-only transactions join this one, which carries the tenant search_path.
        return inTenant(() -> service.pull(new SyncUseCase.PullCommand(UUID.randomUUID(), SCHEMA, Instant.EPOCH, null, owner,
                scope, null)));
    }

    private List<String> pullAll(int limit, PullScope scope) {
        var provider = new StockTransferDeltaProvider(em);
        List<String> ids = new ArrayList<>();
        DeltaEntityProvider.Position after = null;
        int guard = 0;
        do {
            final DeltaEntityProvider.Position from = after;
            DeltaEntityProvider.Page page = inTenant(() -> provider.queryDeltaPage(Instant.EPOCH, from, limit, scope));
            page.rows().forEach(r -> ids.add((String) r.get("id")));
            after = page.hasMore() ? page.next() : null;
        } while (after != null && ++guard < 10);
        return ids;
    }

    private <R> R inTenant(java.util.function.Supplier<R> work) {
        return new TransactionTemplate(txManager).execute(s -> {
            em.createNativeQuery("SELECT set_config('search_path', :path, true)")
                    .setParameter("path", "\"" + SCHEMA + "\"")
                    .getSingleResult();
            return work.get();
        });
    }

    private UUID insertTransfer(UUID id, UUID product, UUID source, UUID destination, Instant at) {
        jdbc.update("INSERT INTO " + T + "stock_transfers (id, source_store_id, destination_store_id, product_id, "
                        + "quantity, actor_id, occurred_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?, ?)",
                id, source, destination, product, UUID.randomUUID(), Timestamp.from(at), Timestamp.from(at));
        return id;
    }
}
