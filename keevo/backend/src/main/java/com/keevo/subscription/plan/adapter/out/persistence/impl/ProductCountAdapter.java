package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.plan.domain.port.out.ProductCountPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProductCountAdapter — JDBC implementation of {@link ProductCountPort}.
 *
 * <p>Counts active products in the current tenant schema. Returns 0 if the
 * {@code products} table does not exist yet (pre-Epic 2 state).
 *
 * <p>NOTE: Temporary count-only adapter. Epic 2 will introduce a full ProductRepository
 * — this adapter will be superseded at that point.
 */
@Component
public class ProductCountAdapter implements ProductCountPort {

    private static final Logger log = LoggerFactory.getLogger(ProductCountAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public ProductCountAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs with {@code NOT_SUPPORTED} propagation to prevent an outer
     * {@code @Transactional} context (GetSubscriptionService) from being poisoned
     * when the {@code products} table does not yet exist (pre-Epic 2 state).
     * A caught SQL error inside a shared PostgreSQL transaction leaves it in
     * "aborted" state — subsequent queries fail with «transaction is aborted».
     * NOT_SUPPORTED suspends the outer transaction, isolates the failure, and
     * allows the catch to return 0 cleanly without tainting anything.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int countActiveProducts() {
        String schema = TenantContext.getCurrentTenant();
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM \"" + schema + "\".products WHERE status = 'ACTIVE'",
                    Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            // products table doesn't exist yet (created in Epic 2) — return 0
            log.debug("products table not yet available in schema {}: {}", schema, e.getMessage());
            return 0;
        }
    }
}
