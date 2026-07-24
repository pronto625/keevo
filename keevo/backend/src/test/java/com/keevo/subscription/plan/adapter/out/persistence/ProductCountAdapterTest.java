package com.keevo.subscription.plan.adapter.out.persistence;

import com.keevo.subscription.plan.adapter.out.persistence.impl.ProductCountAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * ProductCountAdapterTest — Unit tests for {@link ProductCountAdapter}.
 *
 * <p>RED phase: Verifies:
 * <ul>
 *   <li>Returns 0 when {@code products} table does not exist (pre-Epic 2 state).</li>
 *   <li>Returns actual count when table exists.</li>
 *   <li>{@link Transactional#propagation()} is {@link Propagation#NOT_SUPPORTED} —
 *       CRITICAL: without this, a caught SQL exception inside a shared
 *       {@code @Transactional(readOnly=true)} context from GetSubscriptionService
 *       leaves the PostgreSQL transaction in "aborted" state and poisons all
 *       subsequent queries in the same transaction block (500 error).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCountAdapter")
class ProductCountAdapterTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    ProductCountAdapter adapter;

    @BeforeEach
    void setUp() {
        // Use real constructor — TenantContext is not Spring-managed, safe in unit tests
        adapter = new ProductCountAdapter(jdbcTemplate);
        // TenantContext is a ThreadLocal — set a dummy schema so SQL is deterministic
        com.keevo.shared.infrastructure.persistence.TenantContext.setCurrentTenant("kv_test");
    }

    // ── Behaviour when products table is absent (pre-Epic 2) ──────────────────

    @Test
    @DisplayName("countActiveProducts() returns 0 when products table does not exist")
    void countActiveProducts_tableAbsent_returns0() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM \"kv_test\".products WHERE status = 'ACTIVE'"),
                eq(Integer.class)))
                .thenThrow(new org.springframework.dao.DataAccessException("relation products does not exist") {});

        int count = adapter.countActiveProducts();

        assertThat(count).isZero();
    }

    @Test
    @DisplayName("countActiveProducts() returns actual count when table exists")
    void countActiveProducts_tableExists_returnsCount() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM \"kv_test\".products WHERE status = 'ACTIVE'"),
                eq(Integer.class)))
                .thenReturn(42);

        int count = adapter.countActiveProducts();

        assertThat(count).isEqualTo(42);
    }

    @Test
    @DisplayName("countActiveProducts() returns 0 when jdbcTemplate returns null")
    void countActiveProducts_nullResult_returns0() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM \"kv_test\".products WHERE status = 'ACTIVE'"),
                eq(Integer.class)))
                .thenReturn(null);

        int count = adapter.countActiveProducts();

        assertThat(count).isZero();
    }

    // ── Transaction propagation guard ─────────────────────────────────────────

    /**
     * RED TEST — MUST FAIL until {@code @Transactional(propagation=NOT_SUPPORTED)}
     * is added to {@link ProductCountAdapter#countActiveProducts()}.
     *
     * <p>Without NOT_SUPPORTED propagation: when invoked inside a
     * {@code @Transactional(readOnly=true)} context (GetSubscriptionService), a
     * {@code products} table-not-found SQL error aborts the outer PostgreSQL
     * transaction and causes the subsequent {@code users} count query to fail with
     * "current transaction is aborted" → HTTP 500.
     *
     * <p>With NOT_SUPPORTED: the adapter suspends the outer transaction, runs on
     * its own connection, catches the exception, returns 0, and the outer
     * transaction remains healthy.
     */
    @Test
    @DisplayName("countActiveProducts() must declare @Transactional(propagation=NOT_SUPPORTED) to isolate from outer transactions")
    void countActiveProducts_declaresNotSupportedPropagation() throws NoSuchMethodException {
        Method method = ProductCountAdapter.class.getMethod("countActiveProducts");
        Transactional annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation)
                .as("countActiveProducts() must be annotated with @Transactional to declare NOT_SUPPORTED propagation")
                .isNotNull();
        assertThat(annotation.propagation())
                .as("Propagation must be NOT_SUPPORTED to prevent outer @Transactional contexts from being poisoned by missing-table SQL errors")
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }
}
