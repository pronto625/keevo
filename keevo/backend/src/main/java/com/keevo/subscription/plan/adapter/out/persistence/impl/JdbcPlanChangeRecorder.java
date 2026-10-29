package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.identity.api.PlanType;
import com.keevo.subscription.api.PlanChangeCause;
import com.keevo.subscription.api.PlanChangeRecorder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JdbcPlanChangeRecorder — {@link PlanChangeRecorder} on {@code public.tenant_plan_events} and
 * {@code public.tenants} (Story 9-2). Plain JDBC on schema-qualified tables: it joins the
 * caller's transaction whatever the current tenant context.
 */
@Component
public class JdbcPlanChangeRecorder implements PlanChangeRecorder {

    static final String INSERT_EVENT_SQL =
            "INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause, actor_id, reason) "
            + "VALUES (?, ?, ?, ?, ?, ?)";

    static final String SYNC_PLAN_SQL =
            "UPDATE public.tenants SET plan_type = ?, updated_at = NOW() "
            + "WHERE id = ? AND plan_type IS DISTINCT FROM ?";

    private final JdbcTemplate jdbc;

    public JdbcPlanChangeRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordEvent(UUID tenantId, PlanType fromPlan, PlanType toPlan, PlanChangeCause cause,
                            UUID actorId, String reason) {
        jdbc.update(INSERT_EVENT_SQL,
                tenantId,
                fromPlan != null ? fromPlan.name() : null,
                toPlan.name(),
                cause.name(),
                actorId,
                reason);
    }

    @Override
    public void syncPublicPlanType(UUID tenantId, PlanType plan) {
        jdbc.update(SYNC_PLAN_SQL, plan.name(), tenantId, plan.name());
    }
}
