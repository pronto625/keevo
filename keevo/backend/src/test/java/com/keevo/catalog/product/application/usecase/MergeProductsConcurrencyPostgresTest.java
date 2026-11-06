package com.keevo.catalog.product.application.usecase;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 20.1 — two crossed merges (A → B and B → A) at the same time on PostgreSQL: both rows are locked in id order,
 * so exactly one succeeds, the other is refused with {@code PRODUCT_MERGE_INVALID}, and no deadlock occurs.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("MergeProductsUseCase — fusions croisées concurrentes (20.1, PostgreSQL)")
class MergeProductsConcurrencyPostgresTest extends FullContextIntegrationTest {

    @Autowired MergeProductsUseCase mergeProducts;
    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private UUID product(String schema, String name, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO \"" + schema + "\".products (id, name, sku, price) VALUES (?, ?, ?, 100)",
                id, name, sku);
        return id;
    }

    @RepeatedTest(3)
    @DisplayName("A → B et B → A en même temps : une seule fusion réussit, l'autre est refusée, pas d'interblocage")
    void crossed_merges() throws Exception {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        String schema = tenant.getSchemaName();
        UUID a = product(schema, "Savon A", "KEV-CRSA01");
        UUID b = product(schema, "Savon B", "KEV-CRSB01");
        UUID actor = UUID.randomUUID();

        CyclicBarrier start = new CyclicBarrier(2);
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = List.of(
                    pool.submit(() -> merge(schema, a, b, actor, start, outcomes)),
                    pool.submit(() -> merge(schema, b, a, actor, start, outcomes)));
            for (Future<?> run : runs) run.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).filteredOn(o -> o instanceof UUID).as("exactly one success").hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof DomainException d
                && d.getErrorCode() == ErrorCode.PRODUCT_MERGE_INVALID).as("the other one refused").hasSize(1);
        int merged = jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".products "
                + "WHERE merged_into_id IS NOT NULL", Integer.class);
        assertThat(merged).isEqualTo(1);
    }

    private void merge(String schema, UUID source, UUID target, UUID actor, CyclicBarrier start,
                       List<Object> outcomes) {
        TenantContext.setCurrentTenant(schema);
        try {
            start.await(10, TimeUnit.SECONDS);
            outcomes.add(mergeProducts.execute(source, target, actor).getId());
        } catch (DomainException e) {
            outcomes.add(e);
        } catch (Exception e) {
            outcomes.add(e); // a deadlock or anything else fails the assertions above
        } finally {
            TenantContext.clear();
        }
    }
}
