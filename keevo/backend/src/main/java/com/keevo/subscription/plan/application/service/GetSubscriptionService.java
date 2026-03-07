package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.plan.adapter.in.rest.dto.SubscriptionResponse;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.port.in.GetSubscriptionQuery;
import com.keevo.subscription.plan.domain.port.in.GetSubscriptionUseCase;
import com.keevo.subscription.plan.domain.port.out.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.StoreCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import com.keevo.subscription.plan.domain.port.out.UserCountPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GetSubscriptionService — Returns current subscription info and usage counters.
 *
 * <p>GoF Pattern: <b>Strategy</b> (via PlanType) — FREE plans expose numeric limits;
 * PREMIUM_TRIAL and PREMIUM return null for limits (= unlimited in the UI).
 */
@Service
public class GetSubscriptionService implements GetSubscriptionUseCase {

    private final SubscriptionRepository subscriptionRepository;
    private final StoreCountPort storeCountPort;
    private final ProductCountPort productCountPort;
    private final UserCountPort userCountPort;

    public GetSubscriptionService(SubscriptionRepository subscriptionRepository,
                                   StoreCountPort storeCountPort,
                                   ProductCountPort productCountPort,
                                   UserCountPort userCountPort) {
        this.subscriptionRepository = subscriptionRepository;
        this.storeCountPort = storeCountPort;
        this.productCountPort = productCountPort;
        this.userCountPort = userCountPort;
    }

    @Override
    @Transactional(readOnly = true)  // L3 fix: read-only transaction optimises JDBC hints and avoids dirty-check overhead
    public SubscriptionResponse execute(GetSubscriptionQuery query) {
        Subscription sub = subscriptionRepository.findActivePlan()
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND,
                        "No subscription found for tenant"));

        int currentStores    = storeCountPort.countActiveStores();
        int currentProducts  = productCountPort.countActiveProducts();
        int currentEmployees = userCountPort.countEmployees();

        boolean unlimited = sub.getPlanType() != PlanType.FREE;

        return new SubscriptionResponse(
                sub.getPlanType().name(),
                sub.getStatus().name(),
                sub.getExpiresAt() != null ? sub.getExpiresAt().toString() : null,
                unlimited ? null : sub.getMaxStores(),
                unlimited ? null : sub.getMaxProducts(),
                unlimited ? null : sub.getMaxEmployees(),
                currentStores,
                currentProducts,
                currentEmployees
        );
    }
}
