package com.keevo.subscription.plan.domain.port.in;

import com.keevo.subscription.plan.adapter.in.rest.dto.SubscriptionResponse;

/**
 * GetSubscriptionUseCase — Input port: retrieve current subscription info with usage counts.
 */
public interface GetSubscriptionUseCase {
    SubscriptionResponse execute(GetSubscriptionQuery query);
}
