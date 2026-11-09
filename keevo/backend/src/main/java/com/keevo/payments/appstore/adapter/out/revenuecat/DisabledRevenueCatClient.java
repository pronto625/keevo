package com.keevo.payments.appstore.adapter.out.revenuecat;

import com.keevo.payments.appstore.domain.port.out.RevenueCatClient;

import java.util.List;

/** Story 21.2 — no RevenueCat keys: the owner sync answers 503 and TRANSFER events are redelivered later. */
public class DisabledRevenueCatClient implements RevenueCatClient {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public List<StoreSubscription> subscriptions(String appUserId) {
        throw new RevenueCatUnavailableException("RevenueCat client disabled", true);
    }
}
