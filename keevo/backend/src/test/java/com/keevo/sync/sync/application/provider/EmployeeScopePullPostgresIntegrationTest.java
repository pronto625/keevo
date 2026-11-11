package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.PullScope;
import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.sync.sync.adapter.out.persistence.impl.UserSyncStateRepositoryAdapter;
import com.keevo.sync.sync.application.service.SyncOperationHandlerRegistry;
import com.keevo.sync.sync.application.service.SyncPushService;
import com.keevo.sync.sync.application.strategy.ConflictStrategyRegistry;
import com.keevo.sync.sync.domain.model.SyncPullResult;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Story 16.13 — a full sync pull on a real PostgreSQL over two stores: an EMPLOYEE of A receives only A's
 * movements, sales (+ items), closures, inventory sessions/counts and colleagues, and only its own reports, while
 * stores, stock levels and the catalogue cover every store; the OWNER receives everything; the keyset paging
 * continues over the filtered rows (> 1000 movements, interleaved stores). After a reassignment A → B the device's
 * next pull (with its previous {@code since}) is answered from the beginning ({@code reseed}), carries only B's
 * history, and the scope is remembered only once the pull completed.
 *
 * <p>Commits a tenant schema and {@code public.user_sync_state} rows, removed in {@link #cleanUp()}.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({TenantSchemaProvisioner.class, com.keevo.shared.module.adapter.out.persistence.JdbcPlanCatalog.class, UserSyncStateRepositoryAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Employee sync pull is scoped to its store, re-seeded on reassignment (Story 16.13)")
class EmployeeScopePullPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "kv_esp001";
    private static final String T = "\"" + SCHEMA + "\".";
    private static final int MOVEMENTS_PER_STORE = 1200; // > PAGE_SIZE: the employee pull needs two pages

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired TenantSchemaProvisioner provisioner;
    @Autowired UserSyncStateRepositoryAdapter userSyncStates;

    private final UUID storeA = UUID.randomUUID();
    private final UUID storeB = UUID.randomUUID();
    private final UUID ownerUser = UUID.randomUUID();
    private final UUID employeeUser = UUID.randomUUID();
    private final UUID colleagueBUser = UUID.randomUUID();
    private final String device = UUID.randomUUID().toString();
    private UUID product;

    /** Ids per dataset and store, filled by {@link #seed()}. */
    private final Map<String, Map<UUID, Set<String>>> seeded = new LinkedHashMap<>();
    private String employeeReport;
    private String ownerReport;

    @BeforeEach
    void seed() {
        provisioner.provision("tenant-" + SCHEMA, SCHEMA);
        Instant at = Instant.now().minus(2, ChronoUnit.HOURS);
        product = UUID.randomUUID();
        jdbc.update("INSERT INTO " + T + "products (id, name, price, sku) VALUES (?, 'Riz', 100, 'SKU-ESP-1')", product);
        int day = 0;
        for (UUID store : List.of(storeA, storeB)) {
            jdbc.update("INSERT INTO " + T + "stores (id, name) VALUES (?, ?)", store, store == storeA ? "A" : "B");
            jdbc.update("INSERT INTO " + T + "stock_levels (product_id, store_id, quantity) VALUES (?, ?, 5)", product, store);

            UUID sale = UUID.randomUUID();
            jdbc.update("INSERT INTO " + T + "sales (id, store_id, employee_id, total_amount, updated_at) "
                    + "VALUES (?, ?, ?, 100, ?)", sale, store, UUID.randomUUID(), Timestamp.from(at));
            jdbc.update("INSERT INTO " + T + "sale_items (id, sale_id, product_id, product_name, applied_unit_price, "
                    + "quantity, subtotal) VALUES (?, ?, ?, 'Riz', 100, 1, 100)", UUID.randomUUID(), sale, product);
            add("sales", store, sale);

            UUID closure = UUID.randomUUID();
            jdbc.update("INSERT INTO " + T + "day_closures (id, store_id, closure_date, received_at) VALUES (?, ?, ?, ?)",
                    closure, store, LocalDate.now().minusDays(++day), Timestamp.from(at));
            add("dayClosures", store, closure);

            UUID session = UUID.randomUUID();
            jdbc.update("INSERT INTO " + T + "inventory_sessions (id, store_id, scope, started_by, updated_at) "
                    + "VALUES (?, ?, 'FULL', ?, ?)", session, store, UUID.randomUUID(), Timestamp.from(at));
            add("inventorySessions", store, session);
            UUID count = UUID.randomUUID();
            jdbc.update("INSERT INTO " + T + "inventory_counts (id, session_id, product_id, product_name, updated_at) "
                    + "VALUES (?, ?, ?, 'Riz', ?)", count, session, product, Timestamp.from(at));
            add("inventoryCounts", store, count);

            UUID employee = UUID.randomUUID();
            jdbc.update("INSERT INTO " + T + "employees (id, user_id, store_id, first_name, last_name, updated_at) "
                    + "VALUES (?, ?, ?, 'Awa', 'Ngo', ?)", employee, store == storeA ? employeeUser : colleagueBUser,
                    store, Timestamp.from(at));
            add("employees", store, employee);
        }
        // Interleaved movements of A and B (same instants, ids mixed): the filter must not break the keyset.
        for (UUID store : List.of(storeA, storeB)) {
            List<String> ids = jdbc.queryForList("INSERT INTO " + T + "stock_movements (id, product_id, store_id, "
                    + "movement_type, quantity_before, quantity_change, quantity_after, actor_id, occurred_at) "
                    + "SELECT gen_random_uuid(), ?, ?, 'ADJUSTMENT', 0, 1, 1, ?, CAST(? AS timestamptz) - (g % 50) * interval '1 second' "
                    + "FROM generate_series(1, ?) g RETURNING CAST(id AS text)", String.class,
                    product, store, UUID.randomUUID(), Timestamp.from(at), MOVEMENTS_PER_STORE);
            seeded.computeIfAbsent("stockMovements", k -> new LinkedHashMap<>())
                    .computeIfAbsent(store, k -> new HashSet<>()).addAll(ids);
        }
        employeeReport = insertReport(storeA, employeeUser, at);
        ownerReport = insertReport(storeA, ownerUser, at);
    }

    private String insertReport(UUID store, UUID actor, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + T + "reports (id, tenant_id, store_id, actor_id, report_type, report_date, content, "
                + "updated_at) VALUES (?, ?, ?, ?, 'PERSONAL', ?, 'x', ?)", id, SCHEMA, store, actor,
                LocalDate.now(), Timestamp.from(at));
        return id.toString();
    }

    private void add(String key, UUID store, UUID id) {
        seeded.computeIfAbsent(key, k -> new LinkedHashMap<>()).computeIfAbsent(store, k -> new HashSet<>())
                .add(id.toString());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM public.user_sync_state WHERE device_id = ?", device);
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
    }

    /**
     * A store member holding « Voir les ventes de toute la boutique » (Story 19.14): the store scope of every dataset,
     * sales of every seller included. Without it a member pulls his own sales only ({@code SaleDeltaProviderTest},
     * {@code CustomRolesFullContextTest}).
     */
    private PullScope storeWide(UUID store) {
        return PullScope.employee(store, employeeUser).withPermissions(Set.of("STORE_SALES_VIEW"));
    }

    // ── Scope ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("employee of A: only A's store data (stock levels included) and its own reports; stores, catalogue for all")
    void employeeReceivesOnlyItsStore() {
        Pulled pulled = pullAll(storeWide(storeA), false, null, null);

        for (String key : List.of("stockMovements", "sales", "dayClosures", "inventorySessions", "inventoryCounts",
                "employees")) {
            assertThat(pulled.ids(key)).as(key).containsExactlyInAnyOrderElementsOf(seeded.get(key).get(storeA));
        }
        assertThat(pulled.ids("reports")).containsExactly(employeeReport);
        assertThat(pulled.ids("stores")).contains(storeA.toString(), storeB.toString());
        // Story 16.13 (reprise 10/11/2026, D1): stock levels of the member's store only
        assertThat(pulled.rows("stockLevels")).extracting(r -> r.get("storeId"))
                .containsOnly(storeA.toString());
        assertThat(pulled.ids("products")).contains(product.toString());
        assertThat(pulled.pages()).as("movements of A need two pages").isEqualTo(2);
        assertThat(pulled.last().scope()).isEqualTo(storeWide(storeA));
        assertThat(pulled.last().reseed()).isFalse();
        // sale items follow their (in-scope) sales
        assertThat(pulled.rows("sales")).allSatisfy(s -> assertThat((List<?>) s.get("items")).hasSize(1));
    }

    @Test
    @DisplayName("owner: every row of every store, every report")
    void ownerReceivesEverything() {
        Pulled pulled = pullAll(PullScope.unrestricted(ownerUser), true, null, null);

        for (String key : List.of("stockMovements", "sales", "dayClosures", "inventorySessions", "inventoryCounts",
                "employees")) {
            Set<String> all = new HashSet<>(seeded.get(key).get(storeA));
            all.addAll(seeded.get(key).get(storeB));
            assertThat(pulled.ids(key)).as(key).containsExactlyInAnyOrderElementsOf(all);
        }
        assertThat(pulled.ids("reports")).containsExactlyInAnyOrder(employeeReport, ownerReport);
    }

    @Test
    @DisplayName("employee without a store: store data empty, own reports only, pull not failed")
    void employeeWithoutStore() {
        Instant before = Instant.now().minus(1, ChronoUnit.DAYS);
        Pulled pulled = pullAll(PullScope.employee(null, employeeUser), false, null, null);

        for (String key : List.of("stockMovements", "sales", "dayClosures", "inventorySessions", "inventoryCounts",
                "employees", "stockTransfers")) {
            assertThat(pulled.ids(key)).as(key).isEmpty();
        }
        assertThat(pulled.ids("reports")).containsExactly(employeeReport);
        assertThat(pulled.ids("products")).contains(product.toString());
        assertThat(pulled.last().serverTimestamp()).as("cursor advances").isAfter(before);
        assertThat(pulled.last().scope().restricted()).isTrue();
        assertThat(pulled.last().scope().storeId()).isNull();
    }

    // ── Re-seed ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("first pull of a device is not a change; reassignment A → B re-seeds from the beginning, then incremental")
    void reassignmentReseeds() {
        registerDevice();
        Pulled first = pullAll(storeWide(storeA), false, null, device);
        assertThat(first.last().reseed()).isFalse();
        assertThat(userSyncStates.findByDeviceId(device).orElseThrow().lastScopeStoreId()).isEqualTo(storeA);
        assertThat(userSyncStates.findByDeviceId(device).orElseThrow().lastScopeRestricted()).isTrue();

        Instant since = first.last().serverTimestamp(); // after every seeded row: an incremental pull is empty
        Pulled same = pullAll(storeWide(storeA), false, since, device);
        assertThat(same.last().reseed()).isFalse();
        assertThat(same.ids("sales")).isEmpty();

        Pulled reseeded = pullAll(storeWide(storeB), false, since, device);
        assertThat(reseeded.pages()).isEqualTo(2);
        assertThat(reseeded.all()).allSatisfy(p -> assertThat(p.reseed()).isTrue());
        assertThat(reseeded.last().scope().storeId()).isEqualTo(storeB);
        for (String key : List.of("stockMovements", "sales", "dayClosures", "inventorySessions", "inventoryCounts",
                "employees")) {
            assertThat(reseeded.ids(key)).as(key).containsExactlyInAnyOrderElementsOf(seeded.get(key).get(storeB));
        }
        var state = userSyncStates.findByDeviceId(device).orElseThrow();
        assertThat(state.lastScopeKnown()).isTrue();
        assertThat(state.lastScopeRestricted()).isTrue();
        assertThat(state.lastScopeStoreId()).isEqualTo(storeB);
        // the tenant listing maps the same V37 columns
        assertThat(userSyncStates.findAllByTenantId(SCHEMA)).filteredOn(s -> s.deviceId().equals(device))
                .singleElement().satisfies(s -> {
                    assertThat(s.lastScopeKnown()).isTrue();
                    assertThat(s.lastScopeRestricted()).isTrue();
                    assertThat(s.lastScopeStoreId()).isEqualTo(storeB);
                });

        Pulled next = pullAll(storeWide(storeB), false, reseeded.last().serverTimestamp(), device);
        assertThat(next.last().reseed()).isFalse();
        assertThat(next.ids("sales")).isEmpty();
    }

    @Test
    @DisplayName("an interrupted re-seed (first page only) keeps the old scope: the next pull re-seeds again")
    void interruptedReseedKeepsTheOldScope() {
        registerDevice();
        pullAll(storeWide(storeA), false, null, device);
        Instant since = Instant.now();

        SyncPullResult firstPage = pullPage(storeWide(storeB), false, since, null, device);
        assertThat(firstPage.reseed()).isTrue();
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(userSyncStates.findByDeviceId(device).orElseThrow().lastScopeStoreId()).isEqualTo(storeA);

        Pulled again = pullAll(storeWide(storeB), false, since, device);
        assertThat(again.last().reseed()).isTrue();
        assertThat(userSyncStates.findByDeviceId(device).orElseThrow().lastScopeStoreId()).isEqualTo(storeB);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void registerDevice() {
        // what SyncController.pull does before the use case
        userSyncStates.upsertOnPull(new com.keevo.sync.sync.domain.model.UserSyncState(
                device, employeeUser, SCHEMA, null, Instant.now(), Instant.now()));
    }

    private record Pulled(List<SyncPullResult> all) {
        int pages() { return all.size(); }
        SyncPullResult last() { return all.get(all.size() - 1); }
        List<Map<String, Object>> rows(String key) {
            List<Map<String, Object>> rows = new ArrayList<>();
            all.forEach(p -> rows.addAll(p.entities().getOrDefault(key, List.of())));
            return rows;
        }
        List<String> ids(String key) {
            return rows(key).stream().map(r -> (String) r.get("id")).toList();
        }
    }

    private Pulled pullAll(PullScope scope, boolean owner, Instant since, String deviceId) {
        List<SyncPullResult> pages = new ArrayList<>();
        String cursor = null;
        do {
            SyncPullResult page = pullPage(scope, owner, since, cursor, deviceId);
            pages.add(page);
            cursor = page.hasMore() ? page.nextCursor() : null;
        } while (cursor != null && pages.size() < 10);
        // no duplicate across pages
        Pulled pulled = new Pulled(pages);
        assertThat(pulled.ids("stockMovements")).doesNotHaveDuplicates();
        return pulled;
    }

    private SyncPullResult pullPage(PullScope scope, boolean owner, Instant since, String cursor, String deviceId) {
        List<DeltaEntityProvider> providers = List.of(
                new StoreDeltaProvider(em), new ProductDeltaProvider(em), new StockLevelDeltaProvider(em),
                new StockMovementDeltaProvider(em), new SaleDeltaProvider(em), new DayClosureDeltaProvider(em),
                new InventorySessionDeltaProvider(em), new InventoryCountDeltaProvider(em),
                new EmployeeDeltaProvider(em), new ReportsDeltaProvider(em), new StockTransferDeltaProvider(em));
        var service = new SyncPushService(
                mock(SyncOperationHandlerRegistry.class), mock(SyncOperationsLogRepository.class),
                mock(ApplicationEventPublisher.class), txManager, providers,
                mock(ConflictStrategyRegistry.class), mock(SyncConflictsLogRepository.class),
                mock(SyncErrorLogRepository.class), mock(ModuleRegistry.class), userSyncStates);
        var command = new SyncUseCase.PullCommand(owner ? ownerUser : employeeUser, SCHEMA, since, cursor, owner,
                scope, deviceId);
        // The pull's per-provider read-only transactions join this one, which carries the tenant search_path.
        return new TransactionTemplate(txManager).execute(s -> {
            em.createNativeQuery("SELECT set_config('search_path', :path, true)")
                    .setParameter("path", "\"" + SCHEMA + "\"")
                    .getSingleResult();
            return service.pull(command);
        });
    }
}
