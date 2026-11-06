package com.keevo.catalog.product.adapter.in.bootstrap;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Story 20.1 AC — after the backfill every existing product has its {@code product_key} and {@code price_manual = true};
 * a second run changes nothing; keyset batches cover more than one page; the done-marker stops later runs.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("ProductKeyBackfillJob — clé produit et prix manuel des produits existants (20.1, PostgreSQL)")
class ProductKeyBackfillJobPostgresTest extends FullContextIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private String schema;
    private ProductKeyBackfillJob job;

    @BeforeEach
    void setUp() {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        schema = tenant.getSchemaName();
        job = new ProductKeyBackfillJob(jdbc, transactionManager, mock(LockProvider.class), false);
    }

    /** A pre-20.1 row: no key, price_manual at its column default (false). */
    private UUID legacy(String name, String barcode, int index) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, price, barcode) VALUES (?, ?, ?, 100, ?)",
                id, name, String.format("KEV-%06d", index), barcode);
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT product_key, price_manual FROM \"" + schema + "\".products WHERE id = ?", id);
    }

    @Test
    @DisplayName("chaque produit reçoit sa clé et price_manual = true ; une seconde passe ne change rien")
    void backfill_is_complete_and_idempotent() {
        int count = ProductKeyBackfillJob.BATCH + 7; // more than one keyset page
        for (int i = 0; i < count; i++) legacy("Produit " + i, null, i);
        UUID creme = legacy("CRÈME Brûlée", null, 900001);
        UUID coded = legacy("Savon", "6111234567890", 900002);
        UUID free = legacy("Chemise", "REF-001", 900003);
        assertThat(row(creme).get("product_key")).isNull();
        assertThat(row(creme).get("price_manual")).isEqualTo(false);

        // created after the deploy without a price: already keyed, price_manual = false — never touched
        UUID automatic = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, price, product_key, price_manual) "
                + "VALUES (?, 'Service auto', 'KEV-AUTO01', 0, 'nm:service auto', false)", automatic);

        int changed = job.backfillTenant(schema);

        assertThat(changed).isGreaterThanOrEqualTo(count + 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".products "
                + "WHERE id <> ? AND (product_key IS NULL OR price_manual = false)", Integer.class, automatic)).isZero();
        assertThat(row(automatic).get("price_manual")).isEqualTo(false);
        assertThat(row(automatic).get("product_key")).isEqualTo("nm:service auto");
        assertThat(row(creme).get("product_key")).isEqualTo("nm:creme brulee");
        assertThat(row(coded).get("product_key")).isEqualTo("bc:6111234567890");
        assertThat(row(free).get("product_key")).isEqualTo("nm:chemise");
        assertThat(row(creme).get("price_manual")).isEqualTo(true);

        assertThat(job.backfillTenant(schema)).isZero();
        assertThat(row(automatic).get("price_manual")).isEqualTo(false);
    }

    @Test
    @DisplayName("passe complète : marqueur posé, la passe suivante ne lit plus aucun compte")
    void marker_stops_later_runs() {
        UUID id = legacy("Thé VERT", null, 900010);
        jdbc.update("DELETE FROM public.data_migrations WHERE name = ?", ProductKeyBackfillJob.MARKER);

        job.backfillAll();

        assertThat(row(id).get("product_key")).isEqualTo("nm:the vert");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.data_migrations WHERE name = ?", Integer.class,
                ProductKeyBackfillJob.MARKER)).isEqualTo(1);
        UUID later = legacy("Bissap", null, 900011);
        assertThat(job.backfillAll()).isZero();
        assertThat(row(later).get("product_key")).isNull();
    }

    @Test
    @DisplayName("verrou détenu ailleurs → rien ne tourne")
    void lock_held_elsewhere() {
        LockProvider locks = mock(LockProvider.class);
        when(locks.lock(any(LockConfiguration.class))).thenReturn(Optional.empty());
        JdbcTemplate spyJdbc = spy(jdbc);
        new ProductKeyBackfillJob(spyJdbc, transactionManager, locks, true).runAtStartup();
        verifyNoInteractions(spyJdbc);
    }
}
