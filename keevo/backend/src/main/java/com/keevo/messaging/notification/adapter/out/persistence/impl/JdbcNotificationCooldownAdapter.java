package com.keevo.messaging.notification.adapter.out.persistence.impl;

import com.keevo.messaging.notification.domain.port.out.NotificationCooldownRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * JdbcNotificationCooldownAdapter — JDBC-based implementation of
 * {@link NotificationCooldownRepository} for managing notification cooldowns.
 *
 * <p>Uses schema-qualified SQL ({@code "kv_xxxxxx".notification_cooldowns}) so that
 * it works correctly in async listeners and schedulers where Hibernate's
 * {@code SchemaAwareMultiTenantConnectionProvider} does not route JdbcTemplate connections.
 *
 * <p>Story 8.1 — Stock Alert & Trend Notifications.
 */
@Component
public class JdbcNotificationCooldownAdapter implements NotificationCooldownRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcNotificationCooldownAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcNotificationCooldownAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Sentinel used in place of NULL product_id so the UNIQUE constraint fires correctly.
     * PostgreSQL does not consider NULL = NULL in unique indexes, so trend cooldowns
     * (which have no associated product) use this well-known zeroed UUID instead.
     */
    private static final UUID NULL_PRODUCT_SENTINEL = new UUID(0L, 0L);

    @Override
    public boolean existsActiveCooldown(String cooldownType, UUID productId, UUID storeId, Duration window) {
        String table = qualifiedTable();
        UUID effectiveProductId = productId != null ? productId : NULL_PRODUCT_SENTINEL;
        String sql = "SELECT COUNT(*) FROM " + table +
                " WHERE cooldown_type = ? AND product_id = ? AND store_id = ? " +
                "AND last_sent_at > NOW() - (? * INTERVAL '1 second')";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                cooldownType, effectiveProductId, storeId, window.toSeconds());
        return count != null && count > 0;
    }

    @Override
    public void upsertCooldown(String cooldownType, UUID productId, UUID storeId) {
        String table = qualifiedTable();
        UUID effectiveProductId = productId != null ? productId : NULL_PRODUCT_SENTINEL;
        jdbcTemplate.update(
            "INSERT INTO " + table + " (id, cooldown_type, product_id, store_id, last_sent_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, NOW()) " +
            "ON CONFLICT (cooldown_type, product_id, store_id) DO UPDATE SET last_sent_at = EXCLUDED.last_sent_at",
            cooldownType, effectiveProductId, storeId
        );
    }

    /**
     * Returns the fully-qualified table reference: {@code "kv_xxxxxx".notification_cooldowns}.
     * Schema name is validated against the tenant naming convention to prevent SQL injection.
     */
    @SuppressWarnings("SqlSourceToSinkFlow")
    private String qualifiedTable() {
        String schema = TenantContext.getCurrentTenant();
        if (schema == null || !schema.matches("^kv_[a-z0-9]{6}$")) {
            throw new IllegalStateException("TenantContext not set or invalid schema: " + schema);
        }
        return "\"" + schema + "\".notification_cooldowns";
    }
}
