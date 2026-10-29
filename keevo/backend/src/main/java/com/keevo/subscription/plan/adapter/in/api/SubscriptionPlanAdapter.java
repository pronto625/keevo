package com.keevo.subscription.plan.adapter.in.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.api.PlanLimitPort;
import com.keevo.subscription.api.PlanView;
import com.keevo.subscription.api.SubscriptionQueryPort;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implements {@link SubscriptionQueryPort} and {@link PlanLimitPort} (Story 12.2d) over the
 * tenant-bound {@link SubscriptionRepository} and {@link PlanLimitGuard}. No transaction of its own:
 * the check runs in the caller's transaction, before its guarded write.
 */
@Component
class SubscriptionPlanAdapter implements SubscriptionQueryPort, PlanLimitPort {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanLimitGuard planLimitGuard;

    SubscriptionPlanAdapter(SubscriptionRepository subscriptionRepository, PlanLimitGuard planLimitGuard) {
        this.subscriptionRepository = subscriptionRepository;
        this.planLimitGuard = planLimitGuard;
    }

    @Override
    public Optional<PlanView> findCurrentPlan(String tenantSchema) {
        TenantContext.requireCurrent(tenantSchema);
        return subscriptionRepository.findActivePlan().map(SubscriptionPlanAdapter::view);
    }

    @Override
    public void checkStoreLimit(PlanView plan, int currentStores) {
        planLimitGuard.checkStoreLimit(requirePlan(plan).planType(), currentStores);
    }

    @Override
    public void checkProductLimit(PlanView plan, int currentProducts) {
        planLimitGuard.checkProductLimit(requirePlan(plan).planType(), currentProducts);
    }

    @Override
    public void checkEmployeeLimit(PlanView plan, int currentEmployees) {
        planLimitGuard.checkEmployeeLimit(requirePlan(plan).planType(), currentEmployees);
    }

    private static PlanView requirePlan(PlanView plan) {
        if (plan == null) {
            throw new DomainException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Aucun abonnement actif");
        }
        return plan;
    }

    static PlanView view(Subscription s) {
        return new PlanView(s.getPlanType(), s.isActive(), s.getExpiresAt());
    }
}
