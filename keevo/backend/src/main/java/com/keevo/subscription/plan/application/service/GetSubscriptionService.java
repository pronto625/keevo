package com.keevo.subscription.plan.application.service;

import com.keevo.shared.infrastructure.persistence.TenantContext;

import com.keevo.identity.api.PlanKind;
import com.keevo.shared.capability.entitlement.PlanDefinition;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.capability.entitlement.UsageStatus;
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
import java.util.Optional;

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
 *
 * <p>Story 19.8 — additive {@code quotas} and {@code credits} ({@link UsageQuotaPort#status}: two small indexed
 * queries).
 */
@Service
public class GetSubscriptionService implements GetSubscriptionUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetSubscriptionService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final StoreCountPort storeCountPort;
    private final ProductCountPort productCountPort;
    private final UserCountPort userCountPort;
    private final PlanLimitGuard planLimitGuard;
    private final UsageQuotaPort usage;

    /** Without usage quotas (tests): {@link UsageQuotaPort#NONE}, {@code quotas} and {@code credits} empty. */
    public GetSubscriptionService(SubscriptionRepository subscriptionRepository,
                                   StoreCountPort storeCountPort,
                                   ProductCountPort productCountPort,
                                   UserCountPort userCountPort,
                                   PlanLimitGuard planLimitGuard) {
        this(subscriptionRepository, storeCountPort, productCountPort, userCountPort, planLimitGuard,
                UsageQuotaPort.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GetSubscriptionService(SubscriptionRepository subscriptionRepository,
                                   StoreCountPort storeCountPort,
                                   ProductCountPort productCountPort,
                                   UserCountPort userCountPort,
                                   PlanLimitGuard planLimitGuard,
                                   UsageQuotaPort usage) {
        this.usage = usage;
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
        String code = sub.getPlanType().code();
        // 19.7b: one catalogue lookup for kind and label; an unknown code is taken as free (as PlanLimitGuard.kind)
        Optional<PlanDefinition> plan = planLimitGuard.definition(sub.getPlanType());
        PlanKind kind = plan.map(PlanDefinition::kind).orElse(PlanKind.FREE);
        UsageStatus quotas = usage(); // 19.8: never fails the V1 subscription screen

        return new SubscriptionResponse(
                kind.v1Plan().name(), // 19.7a: V1 code, derived from the kind
                sub.getStatus().name(),
                sub.getExpiresAt() != null ? sub.getExpiresAt().toString() : null,
                profile.maxStores(),
                profile.maxProducts(),
                profile.maxEmployees(),
                currentStores,
                currentProducts,
                currentEmployees,
                code, // 19.7b: additive fields
                plan.map(PlanDefinition::label).orElse(code),
                kind.name(),
                profile.maxEmployeesPerStore(),
                SubscriptionResponse.quotas(quotas), // 19.8: additive fields
                SubscriptionResponse.credits(quotas),
                SubscriptionResponse.packsUsedThisMonth(quotas) // 21.3: additive field
        );
    }

    /** Story 19.8 — usage display only: an unreadable usage gives empty quotas, the V1 fields still answer. */
    private UsageStatus usage() {
        String tenant = TenantContext.getCurrentTenant();
        try {
            return usage.statusForSchema(tenant);
        } catch (RuntimeException e) {
            log.warn("Subscription usage: quotas of tenant {} could not be read ({}: {}) — shown empty", tenant,
                    e.getClass().getSimpleName(), e.getMessage());
            return UsageStatus.empty();
        }
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
