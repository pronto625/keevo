package com.keevo.subscription.plan.application.service;

import com.keevo.shared.infrastructure.persistence.TenantContext;

import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.plan.adapter.in.rest.dto.SubscriptionResponse;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.in.GetSubscriptionQuery;
import com.keevo.subscription.plan.domain.port.in.GetSubscriptionUseCase;
import com.keevo.subscription.api.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.StoreCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import com.keevo.subscription.plan.domain.port.out.UserCountPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GetSubscriptionService — Returns current subscription info and usage counters.
 *
 * <p>Story 16.8: the limits are those of the plan profile ({@link PlanLimitGuard#profile}), the same the limit guard
 * enforces — {@code null} = unlimited (whatever the plan type). The legacy {@code subscriptions.max_*} columns are no
 * longer read.
 */
@Service
public class GetSubscriptionService implements GetSubscriptionUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetSubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final StoreCountPort storeCountPort;
    private final ProductCountPort productCountPort;
    private final UserCountPort userCountPort;
    private final PlanLimitGuard planLimitGuard;

    public GetSubscriptionService(SubscriptionRepository subscriptionRepository,
                                   StoreCountPort storeCountPort,
                                   ProductCountPort productCountPort,
                                   UserCountPort userCountPort,
                                   PlanLimitGuard planLimitGuard) {
        this.subscriptionRepository = subscriptionRepository;
        this.storeCountPort = storeCountPort;
        this.productCountPort = productCountPort;
        this.userCountPort = userCountPort;
        this.planLimitGuard = planLimitGuard;
    }

    @Override
    @Transactional(readOnly = true)  // L3 fix: read-only transaction optimises JDBC hints and avoids dirty-check overhead
    public SubscriptionResponse execute(GetSubscriptionQuery query) {
        Subscription sub = subscriptionRepository.findActivePlan()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
                        "No subscription found for tenant"));

        int currentStores    = storeCountPort.countActiveStores();
        Integer currentProducts = currentProducts();
        int currentEmployees = userCountPort.countEmployees();

        PlanProfile profile = planLimitGuard.profile(sub.getPlanType());

        return new SubscriptionResponse(
                sub.getPlanType().name(),
                sub.getStatus().name(),
                sub.getExpiresAt() != null ? sub.getExpiresAt().toString() : null,
                profile.maxStores(),
                profile.maxProducts(),
                profile.maxEmployees(),
                currentStores,
                currentProducts,
                currentEmployees
        );
    }

    /** Usage display only: an unreadable count is unknown ({@code null}); creations stay guarded, fail closed. */
    private Integer currentProducts() {
        try {
            return productCountPort.countActiveProducts(TenantContext.getCurrentTenant());
        } catch (RuntimeException e) {
            log.warn("Subscription usage: active products could not be counted ({}) — shown as unknown",
                    e.getClass().getSimpleName());
            return null;
        }
    }
}
