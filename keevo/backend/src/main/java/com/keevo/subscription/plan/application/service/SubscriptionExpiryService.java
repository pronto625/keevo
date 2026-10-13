package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.subscription.plan.domain.model.PlanChangeCause;
import com.keevo.subscription.plan.domain.model.event.PlanExpiredEvent;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsCommand;
import com.keevo.subscription.plan.domain.port.in.DowngradeExpiredTrialsUseCase;
import com.keevo.subscription.plan.domain.port.out.PlanChangeRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SubscriptionExpiryService — Auto-downgrades expired PREMIUM_TRIAL and PREMIUM
 * subscriptions to FREE.
 *
 * <p>Runs across ALL tenant schemas by:
 * <ol>
 *   <li>Reading non-deleted tenants (id + schema name) from {@code public.tenants}</li>
 *   <li>Per tenant, in its own transaction: downgrade the expired subscription row
 *       ({@code UPDATE … RETURNING} the previous plan), sync {@code public.tenants.plan_type},
 *       append a {@code tenant_plan_events} row (cause EXPIRY) and publish a
 *       {@link PlanExpiredEvent} (owner WhatsApp sent after commit) — Story 9-2.</li>
 * </ol>
 *
 * <p>GoF Pattern: <b>State</b> — PREMIUM_TRIAL → FREE, PREMIUM → FREE (expiry transition).
 * <b>Observer</b>: {@link PlanExpiredEvent}.
 *
 * <p>Triggered daily at 02:00 by {@code SubscriptionExpiryScheduler}.
 *
 * <p>NOTE: Uses direct JDBC (not the per-tenant JPA layer) to operate cross-schema
 * without managing TenantContext per tenant. Table names are schema-qualified
 * (e.g., {@code "kv_abc123".subscriptions}) to avoid search_path ambiguity.
 *
 * <p><b>M4 fix — single source of truth for FREE limits:</b> FREE plan limit values are read from
 * {@link PlanType#FREE} at runtime via JDBC parameters instead of being hardcoded in the SQL.
 */
@Service
public class SubscriptionExpiryService implements DowngradeExpiredTrialsUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryService.class);

    static final String FETCH_TENANTS =
            "SELECT id, schema_name FROM public.tenants WHERE status != 'DELETED'";

    // M4 fix: limit values are JDBC ? params — populated from PlanType.FREE at runtime.
    // Schema name injected via String.format (%s) as it cannot be a JDBC param in PostgreSQL.
    // Story 9-2: RETURNING the plan BEFORE the update (CTE) to publish one event per downgrade.
    static final String DOWNGRADE_SQL =
            "WITH expired AS ("
            + " SELECT id, plan_type FROM \"%1$s\".subscriptions"
            + " WHERE plan_type IN ('PREMIUM_TRIAL', 'PREMIUM')"
            + " AND status = 'ACTIVE'"
            + " AND expires_at IS NOT NULL"
            + " AND expires_at < NOW()"
            + " FOR UPDATE) "
            + "UPDATE \"%1$s\".subscriptions s "
            + "SET plan_type = 'FREE', max_stores = ?, max_products = ?, max_employees = ?, "
            + "expires_at = NULL "
            + "FROM expired WHERE s.id = expired.id "
            + "RETURNING expired.plan_type";

    private final JdbcTemplate jdbcTemplate;
    private final PlanChangeRecorder planChangeRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public SubscriptionExpiryService(JdbcTemplate jdbcTemplate,
                                     PlanChangeRecorder planChangeRecorder,
                                     ApplicationEventPublisher eventPublisher,
                                     PlatformTransactionManager transactionManager,
                                     Clock clock) {
        this(jdbcTemplate, planChangeRecorder, eventPublisher, new TransactionTemplate(transactionManager), clock);
    }

    /** Test constructor — pre-built transaction template. */
    SubscriptionExpiryService(JdbcTemplate jdbcTemplate,
                              PlanChangeRecorder planChangeRecorder,
                              ApplicationEventPublisher eventPublisher,
                              TransactionTemplate transactionTemplate,
                              Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.planChangeRecorder = planChangeRecorder;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public int execute(DowngradeExpiredTrialsCommand command) {
        List<Map<String, Object>> tenants = jdbcTemplate.queryForList(FETCH_TENANTS);

        int totalDowngraded = 0;
        for (Map<String, Object> tenant : tenants) {
            String schema = (String) tenant.get("schema_name");
            if (!TenantSchema.isValid(schema)) continue; // safety guard
            UUID tenantId = toUuid(tenant.get("id"));

            try {
                Integer updated = transactionTemplate.execute(status -> downgrade(tenantId, schema));
                if (updated != null && updated > 0) {
                    log.info("Trial/premium expired — downgraded {} subscription(s) of tenant {} to FREE",
                            updated, tenantId);
                    totalDowngraded += updated;
                }
            } catch (Exception e) {
                log.warn("Failed to process expiry for tenant {}: {}", tenantId, e.getMessage());
            }
        }

        log.info("SubscriptionExpiryService completed — {} tenant(s) downgraded (triggeredBy={})",
                totalDowngraded, command.triggeredBy());
        return totalDowngraded;
    }

    /** One tenant, inside its own transaction: downgrade + public plan + history + event. */
    private int downgrade(UUID tenantId, String schema) {
        if (!TenantSchema.isValid(schema)) { // run loop filters; re-checked at use
            log.warn("Subscription expiry: tenant {} skipped (invalid schema name)", tenantId);
            return 0;
        }
        @SuppressWarnings("SqlSourceToSinkFlow")
        List<String> previousPlans = jdbcTemplate.queryForList(
                String.format(DOWNGRADE_SQL, schema), String.class,
                PlanType.FREE.getMaxStores(),
                PlanType.FREE.getMaxProducts(),
                PlanType.FREE.getMaxEmployees());
        if (previousPlans.isEmpty()) return 0;

        PlanType previous = PlanType.valueOf(previousPlans.get(0));
        Instant now = clock.instant();
        planChangeRecorder.syncPublicPlanType(tenantId, PlanType.FREE);
        planChangeRecorder.recordEvent(tenantId, previous, PlanType.FREE, PlanChangeCause.EXPIRY, null, null);
        eventPublisher.publishEvent(new PlanExpiredEvent(tenantId, schema, previous, now));
        return previousPlans.size();
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(String.valueOf(value));
    }
}
