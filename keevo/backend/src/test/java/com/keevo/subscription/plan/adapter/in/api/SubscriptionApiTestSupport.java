package com.keevo.subscription.plan.adapter.in.api;

import com.keevo.subscription.api.PlanAdministrationPort;
import com.keevo.shared.capability.entitlement.PlanLimitCheck;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanUseCase;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanUseCase;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;

/**
 * Test-only access to the package-private {@code subscription :: api} adapters (Story 12.2d), for
 * tests of other packages that drive the real adapters over a mocked repository / guard.
 */
public final class SubscriptionApiTestSupport {

    private SubscriptionApiTestSupport() {}

    /** The real query adapter over the given repository. */
    public static TenantPlanQuery queryPort(SubscriptionRepository subscriptionRepository) {
        return new SubscriptionPlanAdapter(subscriptionRepository, new PlanLimitGuard(com.keevo.shared.capability.entitlement.TestPlanProfiles.DEFAULTS));
    }

    /** The real limit adapter over the given guard (real or mock). */
    public static PlanLimitCheck limitPort(PlanLimitGuard planLimitGuard) {
        return new SubscriptionPlanAdapter(null, planLimitGuard);
    }

    public static PlanAdministrationPort administrationPort(ActivatePlanUseCase activate,
                                                            ChangeTenantPlanUseCase change) {
        return new PlanAdministrationAdapter(activate, change);
    }

    /** Matcher of a {@link PlanView} of the given plan type (16.8: the guard receives the plan view). */
    public static PlanView plan(com.keevo.identity.api.PlanType type) {
        return org.mockito.ArgumentMatchers.argThat(p -> p != null && type.equals(p.planType()));
    }

    /** The published view of a subscription, mapped exactly as the adapter does. */
    public static PlanView view(Subscription subscription) {
        return SubscriptionPlanAdapter.view(subscription,
                com.keevo.shared.capability.entitlement.TestPlanProfiles.kind(subscription.getPlanType()),
                com.keevo.shared.capability.entitlement.TestPlanProfiles.profile(subscription.getPlanType()));
    }
}
