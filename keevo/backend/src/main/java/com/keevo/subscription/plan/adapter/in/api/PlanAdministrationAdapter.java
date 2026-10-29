package com.keevo.subscription.plan.adapter.in.api;

import com.keevo.subscription.api.ActivatePlanInput;
import com.keevo.subscription.api.ChangePlanInput;
import com.keevo.subscription.api.CurrentPlan;
import com.keevo.subscription.api.PlanAdministrationPort;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanCommand;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanUseCase;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanCommand;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanUseCase;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Implements {@link PlanAdministrationPort} by delegating to {@link ActivatePlanUseCase} and
 * {@link ChangeTenantPlanUseCase} (Story 12.2d). The activation keeps its REQUIRES_NEW transaction;
 * the plan read (FOR UPDATE) and change are MANDATORY: they must run inside the caller's
 * transaction, otherwise the lock would be released at once.
 */
@Component
class PlanAdministrationAdapter implements PlanAdministrationPort {

    private final ActivatePlanUseCase activatePlanUseCase;
    private final ChangeTenantPlanUseCase changeTenantPlanUseCase;

    PlanAdministrationAdapter(ActivatePlanUseCase activatePlanUseCase,
                              ChangeTenantPlanUseCase changeTenantPlanUseCase) {
        this.activatePlanUseCase = activatePlanUseCase;
        this.changeTenantPlanUseCase = changeTenantPlanUseCase;
    }

    @Override
    public void activatePlan(ActivatePlanInput in) {
        activatePlanUseCase.execute(new ActivatePlanCommand(
                in.actorId() != null ? in.actorId().toString() : null, in.tenantSchema(), in.newPlan(),
                in.expiresAt()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CurrentPlan> currentPlan(String tenantSchema) {
        return changeTenantPlanUseCase.currentPlan(tenantSchema);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CurrentPlan changePlan(ChangePlanInput in) {
        return changeTenantPlanUseCase.changePlan(new ChangeTenantPlanCommand(
                in.tenantId(), in.tenantSchema(), in.newPlan(), in.expiresAt(), in.cause(),
                in.actorId(), in.reason()));
    }
}
