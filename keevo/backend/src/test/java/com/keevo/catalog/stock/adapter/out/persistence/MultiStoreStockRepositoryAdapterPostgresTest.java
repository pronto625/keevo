package com.keevo.catalog.stock.adapter.out.persistence;

import com.keevo.catalog.stock.domain.model.StockStatus;
import com.keevo.catalog.stock.domain.model.StoreProductStockEntry;
import com.keevo.catalog.stock.domain.model.StoreStockSummary;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-SQL check of the multi-store stock queries on PostgreSQL 16 (shared Testcontainer).
 *
 * <p>A product never stocked in a store has no {@code stock_levels} row there: it must still be
 * listed (quantity 0, CRITIQUE) and counted, and archived products stay hidden. Negative stock
 * is CRITIQUE too.
 */
@DisplayName("MultiStoreStockRepositoryAdapter — SQL on PostgreSQL (rowless & negative stock)")
class MultiStoreStockRepositoryAdapterPostgresTest {

    private static final String DATABASE = "keevo_multistore_stock";
    private static final String SCHEMA = "kv_mss001";

    private static JdbcTemplate jdbc;
    private MultiStoreStockRepositoryAdapter adapter;

    private final UUID store = UUID.randomUUID();
    private final UUID otherStore = UUID.randomUUID();
    private final UUID stocked = UUID.randomUUID();   // 10 in store
    private final UUID rowless = UUID.randomUUID();   // stocked only in otherStore
    private final UUID negative = UUID.randomUUID();  // -1 in store
    private final UUID archived = UUID.randomUUID();  // archived, 3 in store

    @BeforeAll
    static void database() {
        String url = SharedPostgresContainer.freshDatabase(DATABASE);
        DriverManagerDataSource ds = new DriverManagerDataSource(
                url, SharedPostgresContainer.username(), SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        jdbc.execute("CREATE TABLE " + SCHEMA + ".stores (id uuid PRIMARY KEY, name varchar(100) NOT NULL,"
                + " type varchar(20) NOT NULL, is_active boolean NOT NULL, created_at timestamptz NOT NULL)");
        jdbc.execute("CREATE TABLE " + SCHEMA + ".products (id uuid PRIMARY KEY, name varchar(200) NOT NULL,"
                + " price integer NOT NULL, archived boolean NOT NULL,"
                + " minimum_threshold integer NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE " + SCHEMA + ".stock_levels (id uuid PRIMARY KEY, product_id uuid NOT NULL,"
                + " variant_id uuid, store_id uuid NOT NULL, quantity integer NOT NULL,"
                + " minimum_threshold integer NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL)");
    }

    @BeforeEach
    void seed() {
        TenantContext.setCurrentTenant(SCHEMA);
        adapter = new MultiStoreStockRepositoryAdapter(jdbc);
        jdbc.execute("TRUNCATE " + SCHEMA + ".stores, " + SCHEMA + ".products, " + SCHEMA + ".stock_levels");
        jdbc.update("INSERT INTO " + SCHEMA + ".stores VALUES (?, 'Boutique', 'STORE', true, now()),"
                + " (?, 'Entrepôt', 'WAREHOUSE', true, now())", store, otherStore);
        jdbc.update("INSERT INTO " + SCHEMA + ".products VALUES"
                + " (?, 'Ananas', 100, false, 0), (?, 'Bissap', 200, false, 0),"
                + " (?, 'Sardines Boîte', 300, false, 0), (?, 'Zzz archivé', 50, true, 0)",
                stocked, rowless, negative, archived);
        level(stocked, store, 10);
        level(negative, store, -1);
        level(archived, store, 3);
        level(rowless, otherStore, 7);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void level(UUID product, UUID storeId, int qty) {
        jdbc.update("INSERT INTO " + SCHEMA + ".stock_levels (id, product_id, store_id, quantity, updated_at)"
                + " VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), product, storeId, qty);
    }

    private static StoreProductStockEntry find(Page<StoreProductStockEntry> page, UUID productId) {
        return page.getContent().stream().filter(e -> e.productId().equals(productId)).findFirst().orElse(null);
    }

    @Test
    void detail_listsProductNeverStockedInStore_withZeroAndCritique_hidesArchived() {
        Page<StoreProductStockEntry> page = adapter.getStoreStockDetail(store, false, false, PageRequest.of(0, 25));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(StoreProductStockEntry::productId)
                .containsExactly(stocked, rowless, negative); // sorted by name
        StoreProductStockEntry r = find(page, rowless);
        assertThat(r.quantity()).isZero();
        assertThat(r.storeId()).isEqualTo(store);
        assertThat(r.status()).isEqualTo(StockStatus.CRITIQUE);
        assertThat(find(page, archived)).isNull();
    }

    @Test
    void detail_negativeStock_isCritique() {
        Page<StoreProductStockEntry> page = adapter.getStoreStockDetail(store, false, false, PageRequest.of(0, 25));

        StoreProductStockEntry n = find(page, negative);
        assertThat(n.quantity()).isEqualTo(-1);
        assertThat(n.isCritical()).isTrue();
        assertThat(n.status()).isEqualTo(StockStatus.CRITIQUE);
        assertThat(find(page, stocked).status()).isEqualTo(StockStatus.NORMAL);
    }

    @Test
    void detail_lowOnly_includesRowlessAndNegative_countMatches() {
        Page<StoreProductStockEntry> page = adapter.getStoreStockDetail(store, true, true, PageRequest.of(0, 25));

        assertThat(page.getContent()).extracting(StoreProductStockEntry::productId)
                .containsExactlyInAnyOrder(rowless, negative);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void detail_pagination_countsRowlessProducts() {
        Page<StoreProductStockEntry> page = adapter.getStoreStockDetail(store, false, false, PageRequest.of(1, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(StoreProductStockEntry::productId).containsExactly(negative);
    }

    @Test
    void overview_lowStockCount_includesRowlessAndNegative_excludesArchived() {
        List<StoreStockSummary> overviews = adapter.getStoreOverviews();

        StoreStockSummary boutique = overviews.stream().filter(o -> o.storeId().equals(store)).findFirst().orElseThrow();
        assertThat(boutique.productCount()).isEqualTo(2);     // stocked + negative (archived excluded)
        assertThat(boutique.lowStockCount()).isEqualTo(2);    // rowless (0) + negative (-1)
        StoreStockSummary entrepot = overviews.stream().filter(o -> o.storeId().equals(otherStore)).findFirst().orElseThrow();
        assertThat(entrepot.productCount()).isEqualTo(1);
        assertThat(entrepot.lowStockCount()).isEqualTo(2);    // stocked + negative never stocked there
    }
}
