package com.keevo.catalog.product.adapter.out.persistence;

import com.keevo.subscription.api.ProductCountPort;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProductCountAdapter — JDBC implementation of {@link ProductCountPort}.
 *
 * <p>Counts active products in the given tenant schema. Story 16.8: <b>fails closed</b> — an invalid schema or a
 * failing query throws instead of answering 0, so the plan-limit guard refuses the creation
 * ({@code PRODUCT_QUOTA_UNAVAILABLE}) rather than letting a Free tenant past its quota. The guard only counts when the
 * plan's product limit is finite.
 *
 * <p>Story 12.2d: moved from subscription to catalog, the owner of the {@code products} table;
 * the port is declared by subscription ({@code subscription :: api}).
 */
@Component
public class ProductCountAdapter implements ProductCountPort {

    private final JdbcTemplate jdbcTemplate;

    public ProductCountAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs with {@code NOT_SUPPORTED} propagation: the count runs outside the caller's transaction, so a failing
     * query never leaves that PostgreSQL transaction aborted — the failure is thrown to the caller, which decides.
     *
     * @throws IllegalStateException the schema is invalid
     * @throws org.springframework.dao.DataAccessException the count could not be read
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int countActiveProducts(String tenantSchema) {
        if (!TenantSchema.isValid(tenantSchema)) {
            throw new IllegalStateException("Product count: invalid tenant schema");
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"" + tenantSchema + "\".products WHERE status = 'ACTIVE'",
                Integer.class);
        return count != null ? count : 0;
    }
}
