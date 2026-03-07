package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsCommand;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SubscriptionExpiryService — Auto-downgrades expired PREMIUM_TRIAL and PREMIUM
 * subscriptions to FREE.
 *
 * <p>Runs across ALL tenant schemas by:
 * <ol>
 *   <li>Reading active tenant schema names from {@code public.tenants}</li>
 *   <li>Per-schema: UPDATE subscriptions SET plan_type = 'FREE' WHERE expired</li>
 * </ol>
 *
 * <p>GoF Pattern: <b>State</b> — PREMIUM_TRIAL → FREE, PREMIUM → FREE (expiry transition).
 * <b>Observer</b> hook: emits downgrade event (future WhatsApp notification).
 *
 * <p>Triggered daily at 02:00 by {@code SubscriptionExpiryScheduler}.
 *
 * <p>NOTE: Uses direct JDBC (not the per-tenant JPA layer) to operate cross-schema
 * without managing TenantContext per tenant. Table names are schema-qualified
 * (e.g., {@code "kv_abc123".subscriptions}) to avoid search_path ambiguity.
 *
 * <p><b>M4 fix — single source of truth for FREE limits:</b> FREE plan limit values
 * ({@code max_stores=1, max_products=500, max_employees=3}) are read from
 * {@link PlanType#FREE} at runtime via JDBC parameters instead of being
 * hardcoded in the SQL constant. This ensures a single source of truth: any
 * change to {@link PlanType} is automatically reflected in the downgrade SQL.
 */
@Service
public class SubscriptionExpiryService implements DowngradeExpiredTrialsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryService.class);

    private static final String FETCH_TENANT_SCHEMAS =
            "SELECT schema_name FROM public.tenants WHERE status != 'DELETED'";

    // M4 fix: limit values replaced with JDBC ? params — populated from PlanType.FREE at runtime
    // Schema name still injected via String.format (%s) as it cannot be a JDBC param in PostgreSQL.
    private static final String DOWNGRADE_SQL =
            "UPDATE \"%s\".subscriptions " +
            "SET plan_type = 'FREE', max_stores = ?, max_products = ?, max_employees = ?, " +
            "expires_at = NULL " +
            "WHERE plan_type IN ('PREMIUM_TRIAL', 'PREMIUM') " +
            "AND status = 'ACTIVE' " +
            "AND expires_at IS NOT NULL " +
            "AND expires_at < NOW()";

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionExpiryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int execute(DowngradeExpiredTrialsCommand command) {
        List<String> schemas = jdbcTemplate.queryForList(FETCH_TENANT_SCHEMAS, String.class);

        int totalDowngraded = 0;
        for (String schema : schemas) {
            if (!schema.matches("^kv_[a-z0-9]{6}$")) continue; // safety guard

            try {
                // Schema name validated against regex above — safe to interpolate via format.
                // FREE plan limit values come from PlanType.FREE (single source of truth).
                @SuppressWarnings("SqlSourceToSinkFlow")
                int updated = jdbcTemplate.update(
                        String.format(DOWNGRADE_SQL, schema),
                        PlanType.FREE.getMaxStores(),
                        PlanType.FREE.getMaxProducts(),
                        PlanType.FREE.getMaxEmployees()
                );
                if (updated > 0) {
                    log.info("Trial/premium expired — downgraded {} subscription(s) in schema {} to FREE",
                            updated, schema);
                    // TODO(Story 1.6+): emit SubscriptionDowngradedEvent for WhatsApp notification
                    totalDowngraded += updated;
                }
            } catch (Exception e) {
                log.warn("Failed to process expiry for schema {}: {}", schema, e.getMessage());
            }
        }

        log.info("SubscriptionExpiryService completed — {} tenant(s) downgraded (triggeredBy={})",
                totalDowngraded, command.triggeredBy());
        return totalDowngraded;
    }
}
