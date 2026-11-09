package com.keevo.payments.appstore.adapter.out.revenuecat;

import com.keevo.payments.appstore.domain.port.out.RevenueCatClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Story 21.2 — simulated RevenueCat for the {@code dev} and {@code test} profiles only (refused at startup elsewhere):
 * the subscriptions of a customer are whatever was {@link #put}; {@link #failNext()} makes the next call fail. Never
 * reaches RevenueCat.
 */
public class InMemoryRevenueCatClient implements RevenueCatClient {

    private final Map<String, List<StoreSubscription>> customers = new ConcurrentHashMap<>();
    private final AtomicBoolean failNext = new AtomicBoolean();

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<StoreSubscription> subscriptions(String appUserId) {
        if (failNext.getAndSet(false)) {
            throw new RevenueCatUnavailableException("simulated RevenueCat failure", false);
        }
        return customers.getOrDefault(appUserId, List.of());
    }

    public void put(String appUserId, List<StoreSubscription> subscriptions) {
        customers.put(appUserId, List.copyOf(subscriptions));
    }

    public void failNext() {
        failNext.set(true);
    }

    public void clear() {
        customers.clear();
        failNext.set(false);
    }
}
