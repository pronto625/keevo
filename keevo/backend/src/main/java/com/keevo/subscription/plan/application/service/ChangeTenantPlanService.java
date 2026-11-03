package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanCommand;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanUseCase;
import com.keevo.subscription.api.CurrentPlan;
import com.keevo.shared.capability.entitlement.PlanChangeRecorder;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
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
 * {@code created_at}, legacy limit columns from the plan's profile, status back to ACTIVE), but it joins the caller's
 * transaction and keeps {@code public.tenants.plan_type} + {@code tenant_plan_events} in step.
 *
 * <p>Story 16.5: after the history row, the tenant's paid-module rows are realigned on the new plan in the same
 * transaction ({@link PlanModuleRowSync}).
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
    private final PlanModuleRowSync planModuleRows;
    private final PlanProfiles planProfiles;

    public ChangeTenantPlanService(JdbcTemplate jdbc, PlanChangeRecorder recorder, PlanModuleRowSync planModuleRows,
                                   PlanProfiles planProfiles) {
        this.jdbc = jdbc;
        this.recorder = recorder;
        this.planModuleRows = planModuleRows;
        this.planProfiles = planProfiles;
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
        PlanProfile profile = planProfiles.profile(plan); // 16.8: legacy columns, no longer read for decisions
        jdbc.update(String.format(UPDATE_PLAN_SQL, TenantSchema.validate(cmd.schemaName())),
                plan.name(),
                profile.legacyMaxStores(), profile.legacyMaxProducts(), profile.legacyMaxEmployees(),
                cmd.expiresAt() != null ? Timestamp.from(cmd.expiresAt()) : null,
                row.id());
        recorder.syncPublicPlanType(cmd.tenantId(), plan);
        recorder.recordEvent(cmd.tenantId(), row.plan().planType(), plan, cmd.cause(),
                cmd.actorId(), cmd.reason());
        planModuleRows.sync(cmd.tenantId(), cmd.schemaName()); // Story 16.5 — same transaction
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
                    new CurrentPlan(PlanType.of(rs.getString("plan_type")), // 19.7a: stored code, shape only
                            exp != null ? exp.toInstant() : null));
        });
        return rows.stream().findFirst();
    }
}
