package com.keevo.sync.sync.application.provider;

import com.keevo.shared.application.port.sync.DeltaEntityProvider;
import com.keevo.shared.application.port.sync.PullScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 16.13 — every store-specific delta provider restricts an EMPLOYEE pull to its store through a bound
 * parameter (never an inlined value), sends the OWNER every row, and returns an empty page without querying for an
 * employee without a store. Reports are personal: restricted to the pulling user's id.
 */
@DisplayName("Store-specific delta providers honour the pull scope (Story 16.13)")
class StoreScopedDeltaProvidersTest {

    private static final Instant SINCE = Instant.parse("2026-10-01T00:00:00Z");

    record Case(String name, Function<EntityManager, DeltaEntityProvider> factory, String filter) {
        @Override public String toString() { return name; }
    }

    static Stream<Case> storeScoped() {
        return Stream.of(
                new Case("stockMovements", StockMovementDeltaProvider::new, "store_id = :scopeStoreId"),
                new Case("sales", SaleDeltaProvider::new, "store_id = :scopeStoreId"),
                new Case("dayClosures", DayClosureDeltaProvider::new, "store_id = :scopeStoreId"),
                new Case("inventorySessions", InventorySessionDeltaProvider::new, "store_id = :scopeStoreId"),
                new Case("inventoryCounts", InventoryCountDeltaProvider::new,
                        "session_id IN (SELECT s.id FROM inventory_sessions s WHERE s.store_id = :scopeStoreId)"),
                new Case("employees", EmployeeDeltaProvider::new, "store_id = :scopeStoreId"),
                new Case("stockTransfers", StockTransferDeltaProvider::new,
                        "source_store_id = :scopeStoreId OR destination_store_id = :scopeStoreId"));
    }

    private record Captured(EntityManager em, Query query) {}

    private static Captured mocks() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        return new Captured(em, query);
    }

    private static String sql(EntityManager em) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    @ParameterizedTest(name = "{0}: owner pull is unscoped")
    @MethodSource("storeScoped")
    void ownerIsUnscoped(Case c) {
        Captured m = mocks();

        c.factory().apply(m.em()).queryDeltaPage(SINCE, null, 50, PullScope.unrestricted(UUID.randomUUID()));

        assertThat(sql(m.em())).doesNotContain(":scopeStoreId");
        verify(m.query(), never()).setParameter(eq("scopeStoreId"), any());
    }

    @ParameterizedTest(name = "{0}: employee pull is restricted to its store, bound parameter")
    @MethodSource("storeScoped")
    void employeeIsScopedWithABoundParameter(Case c) {
        Captured m = mocks();
        UUID store = UUID.randomUUID();

        c.factory().apply(m.em()).queryDeltaPage(SINCE, null, 50, PullScope.employee(store, UUID.randomUUID()));

        assertThat(sql(m.em())).contains("AND (" + c.filter() + ")").doesNotContain(store.toString());
        verify(m.query()).setParameter("scopeStoreId", store);
    }

    @ParameterizedTest(name = "{0}: employee without a store gets an empty page, no query")
    @MethodSource("storeScoped")
    void employeeWithoutStoreGetsNothing(Case c) {
        Captured m = mocks();
        DeltaEntityProvider provider = c.factory().apply(m.em());

        var page = provider.queryDeltaPage(SINCE, null, 50, PullScope.employee(null, UUID.randomUUID()));
        var failClosed = provider.queryDeltaPage(SINCE, null, 50, PullScope.NONE);
        var unscopedCall = provider.queryDeltaPage(SINCE, null, 50); // no scope given: fail closed

        assertThat(page.rows()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(failClosed.rows()).isEmpty();
        assertThat(unscopedCall.rows()).isEmpty();
        verify(m.em(), never()).createNativeQuery(anyString());
    }

    // ── Reports: personal (REST parity with ReportController) ─────────────────────────

    @Test
    void reports_owner_unscoped() {
        Captured m = mocks();

        new ReportsDeltaProvider(m.em()).queryDeltaPage(SINCE, null, 50, PullScope.unrestricted(UUID.randomUUID()));

        assertThat(sql(m.em())).doesNotContain(":scopeActorId");
    }

    @Test
    void reports_employee_onlyOwnReports_evenWithoutAStore() {
        Captured m = mocks();
        UUID user = UUID.randomUUID();

        new ReportsDeltaProvider(m.em()).queryDeltaPage(SINCE, null, 50, PullScope.employee(null, user));

        assertThat(sql(m.em())).contains("AND (actor_id = :scopeActorId)").doesNotContain(user.toString());
        verify(m.query()).setParameter("scopeActorId", user);
    }

    @Test
    void reports_unknownActor_empty() {
        Captured m = mocks();

        assertThat(new ReportsDeltaProvider(m.em()).queryDeltaPage(SINCE, null, 50, PullScope.NONE).rows()).isEmpty();
        verify(m.em(), never()).createNativeQuery(anyString());
    }

    // ── Tenant-wide datasets ignore the scope ──────────────────────────────

    static Stream<Case> tenantWide() {
        return Stream.of(
                new Case("stores", StoreDeltaProvider::new, null),
                new Case("stockLevels", StockLevelDeltaProvider::new, null),
                new Case("products", ProductDeltaProvider::new, null),
                new Case("categories", CategoryDeltaProvider::new, null),
                // clients: store-scoped since Story 20.2 (ClientDeltaProviderTest)
                new Case("suppliers", SupplierDeltaProvider::new, null));
    }

    @ParameterizedTest(name = "{0}: an employee still pulls every store")
    @MethodSource("tenantWide")
    void tenantWideDatasetsAreNotScoped(Case c) {
        Captured m = mocks();

        c.factory().apply(m.em()).queryDeltaPage(SINCE, null, 50, PullScope.employee(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(sql(m.em())).doesNotContain(":scopeStoreId").doesNotContain(":scopeActorId");
    }
}
