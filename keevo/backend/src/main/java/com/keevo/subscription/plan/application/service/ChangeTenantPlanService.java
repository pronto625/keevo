package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanCommand;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanUseCase;
import com.keevo.subscription.plan.domain.port.in.CurrentPlan;
import com.keevo.subscription.plan.domain.port.out.PlanChangeRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ChangeTenantPlanService — schema-qualified JDBC plan change used by the super-admin plan flow
 * (Story 9-2). Same row and limits rule as {@link ActivatePlanService} (first subscription row by
 * {@code created_at}, limits from {@link PlanType}, status back to ACTIVE), but it joins the caller's
 * transaction and keeps {@code public.tenants.plan_type} + {@code tenant_plan_events} in step.
 *
 * <p>Schema names are re-validated with {@link TenantSchema} before any interpolation.
 */
@Service
public class ChangeTenantPlanService implements ChangeTenantPlanUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChangeTenantPlanService.class);

    static final String SELECT_CURRENT_SQL =
            "SELECT id, plan_type, expires_at FROM \"%s\".subscriptions "
            + "ORDER BY created_at ASC LIMIT 1 FOR UPDATE";

    static final String UPDATE_PLAN_SQL =
            "UPDATE \"%s\".subscriptions SET plan_type = ?, status = 'ACTIVE', "
            + "max_stores = ?, max_products = ?, max_employees = ?, expires_at = ? WHERE id = ?";

    private record Row(UUID id, CurrentPlan plan) {}

    private final JdbcTemplate jdbc;
    private final PlanChangeRecorder recorder;

    public ChangeTenantPlanService(JdbcTemplate jdbc, PlanChangeRecorder recorder) {
        this.jdbc = jdbc;
        this.recorder = recorder;
    }

    @Override
    @Transactional
    public Optional<CurrentPlan> currentPlan(String schemaName) {
        return readRow(schemaName).map(Row::plan);
    }

    @Override
    @Transactional
    public CurrentPlan changePlan(ChangeTenantPlanCommand cmd) {
        Row row = readRow(cmd.schemaName())
                .orElseThrow(() -> new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND,
                        "No subscription for tenant " + cmd.tenantId()));
        PlanType plan = cmd.newPlan();
        jdbc.update(String.format(UPDATE_PLAN_SQL, cmd.schemaName()),
                plan.name(),
                plan.getMaxStores(), plan.getMaxProducts(), plan.getMaxEmployees(),
                cmd.expiresAt() != null ? Timestamp.from(cmd.expiresAt()) : null,
                row.id());
        recorder.syncPublicPlanType(cmd.tenantId(), plan);
        recorder.recordEvent(cmd.tenantId(), row.plan().planType(), plan, cmd.cause(),
                cmd.actorId(), cmd.reason());
        log.info("Plan changed: tenant={} {} -> {} cause={} actor={}",
                cmd.tenantId(), row.plan().planType(), plan, cmd.cause(), cmd.actorId());
        return row.plan();
    }

    private Optional<Row> readRow(String schemaName) {
        String schema = TenantSchema.validate(schemaName);
        List<Row> rows = jdbc.query(String.format(SELECT_CURRENT_SQL, schema), (rs, i) -> {
            Timestamp exp = rs.getTimestamp("expires_at");
            return new Row(
                    UUID.fromString(rs.getString("id")),
                    new CurrentPlan(PlanType.valueOf(rs.getString("plan_type")),
                            exp != null ? exp.toInstant() : null));
        });
        return rows.stream().findFirst();
    }
}
