package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Story 16.8 — the guard enforces the limits carried by the plan view (built from the live profile when the plan is
 * read, as {@code SubscriptionPlanAdapter} does per request); null = unlimited; the product count fails closed.
 */
class PlanLimitGuardTest {

    /** Mutable in-memory profiles: a change applies to the next plan read. */
    private final Map<PlanType, PlanProfile> stored = new EnumMap<>(PlanType.class);
    private final PlanProfiles profiles = type -> stored.getOrDefault(type, PlanProfile.fromEnum(type));
    private final PlanLimitGuard guard = new PlanLimitGuard(profiles);

    {
        stored.put(PlanType.FREE, PlanProfile.of(PlanType.FREE, 1, 500, 2, Set.of()));
        stored.put(PlanType.PREMIUM_TRIAL, PlanProfile.of(PlanType.PREMIUM_TRIAL, null, null, null, Set.of()));
        stored.put(PlanType.PREMIUM, PlanProfile.of(PlanType.PREMIUM, null, null, null, Set.of()));
    }

    /** The plan as read for one request: limits from the current profile. */
    private PlanView view(PlanType type) {
        return new PlanView(type, true, null, guard.profile(type));
    }

    @Test
    void storeUnderLimit_passes() {
        assertThatCode(() -> guard.checkStoreLimit(view(PlanType.FREE), 0)).doesNotThrowAnyException();
    }

    @Test
    void storeAtLimit_throwsPlanLimitExceeded() {
        assertLimit(() -> guard.checkStoreLimit(view(PlanType.FREE), 1), "stores", 1, 1);
    }

    @Test
    void productAtLimit_throwsPlanLimitExceeded() {
        assertLimit(() -> guard.checkProductLimit(view(PlanType.FREE), () -> 500), "products", 500, 500);
    }

    @Test
    void freeEmployeeLimitIsTwo() {
        assertThatCode(() -> guard.checkEmployeeLimit(view(PlanType.FREE), 1)).doesNotThrowAnyException();
        assertLimit(() -> guard.checkEmployeeLimit(view(PlanType.FREE), 2), "employees", 2, 2);
    }

    @Test
    void lowerLimit_existingAboveLimit_nextCreationRefusedWithTheNewLimit() {
        assertLimit(() -> guard.checkEmployeeLimit(view(PlanType.FREE), 3), "employees", 2, 3);
    }

    @Test
    void profileChange_appliesToTheNextPlanRead() {
        assertLimit(() -> guard.checkStoreLimit(view(PlanType.FREE), 1), "stores", 1, 1);
        stored.put(PlanType.FREE, PlanProfile.of(PlanType.FREE, 2, 500, 2, Set.of()));
        assertThatCode(() -> guard.checkStoreLimit(view(PlanType.FREE), 1)).doesNotThrowAnyException();
        assertLimit(() -> guard.checkStoreLimit(view(PlanType.FREE), 2), "stores", 2, 2);
        stored.put(PlanType.FREE, PlanProfile.of(PlanType.FREE, null, 500, 2, Set.of()));
        assertThatCode(() -> guard.checkStoreLimit(view(PlanType.FREE), 50)).doesNotThrowAnyException();
    }

    @Test
    void enforcesTheViewItReceives_notAFreshProfileRead() {
        PlanView read = view(PlanType.FREE);
        stored.put(PlanType.FREE, PlanProfile.of(PlanType.FREE, 5, 5, 5, Set.of()));
        // same request: the view read before the change decides (message, check and quota agree)
        assertLimit(() -> guard.checkStoreLimit(read, 1), "stores", 1, 1);
        assertThat(guard.remainingProducts(read, () -> 499)).isEqualTo(1);
    }

    @Test
    void premiumProfilesUnlimited_neverBlock() {
        for (PlanType type : new PlanType[] {PlanType.PREMIUM_TRIAL, PlanType.PREMIUM}) {
            assertThatCode(() -> {
                guard.checkStoreLimit(view(type), 999);
                guard.checkProductLimit(view(type), () -> 99999);
                guard.checkEmployeeLimit(view(type), 9999);
            }).doesNotThrowAnyException();
        }
    }

    @Test
    void finitePremiumLimit_isEnforced() {
        stored.put(PlanType.PREMIUM, PlanProfile.of(PlanType.PREMIUM, 5, null, null, Set.of()));
        assertLimit(() -> guard.checkStoreLimit(view(PlanType.PREMIUM), 5), "stores", 5, 5);
    }

    @Test
    void unlimitedProducts_neverCount() {
        AtomicInteger counts = new AtomicInteger();
        guard.checkProductLimit(view(PlanType.PREMIUM), () -> {
            counts.incrementAndGet();
            return 600;
        });
        assertThat(guard.remainingProducts(view(PlanType.PREMIUM), () -> {
            counts.incrementAndGet();
            throw new IllegalStateException("db down");
        })).isEqualTo(Integer.MAX_VALUE);
        assertThat(counts).hasValue(0);
    }

    @Test
    void finiteProducts_countAndCheck() {
        assertThatCode(() -> guard.checkProductLimit(view(PlanType.FREE), () -> 499)).doesNotThrowAnyException();
        assertThat(guard.remainingProducts(view(PlanType.FREE), () -> 490)).isEqualTo(10);
        assertThat(guard.remainingProducts(view(PlanType.FREE), () -> 600)).isZero();
    }

    @Test
    void unreadableProductCount_failsClosed() {
        for (Runnable call : new Runnable[] {
                () -> guard.checkProductLimit(view(PlanType.FREE), () -> {
                    throw new IllegalStateException("db down");
                }),
                () -> guard.remainingProducts(view(PlanType.FREE), () -> {
                    throw new IllegalStateException("db down");
                })}) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PRODUCT_QUOTA_UNAVAILABLE));
        }
    }

    @Test
    void enumFallback_freeIsOneStore500Products2Employees() {
        PlanProfile free = PlanProfile.fromEnum(PlanType.FREE);
        assertThat(free.maxStores()).isEqualTo(1);
        assertThat(free.maxProducts()).isEqualTo(500);
        assertThat(free.maxEmployees()).isEqualTo(2);
        assertThat(free.fallback()).isTrue();
        assertThat(PlanProfile.fromEnum(PlanType.PREMIUM).maxStores()).isNull();
        assertThat(PlanProfile.fromEnum(PlanType.PREMIUM).grants("invoicing")).isTrue();
        assertThat(free.grants("invoicing")).isFalse();
    }

    @Test
    void composition_explicitDecisionsElsePlanDefault() {
        PlanProfile premium = PlanProfile.of(PlanType.PREMIUM, null, null, null, Set.of("exports"), Set.of("invoicing"));
        assertThat(premium.grants("exports")).isTrue();
        assertThat(premium.grants("invoicing")).isFalse();
        assertThat(premium.grants("shipped-later")).as("no decision: premium default").isTrue();
        PlanProfile free = PlanProfile.of(PlanType.FREE, 1, 500, 2, Set.of("exports"), Set.of());
        assertThat(free.grants("exports")).isTrue();
        assertThat(free.grants("shipped-later")).as("no decision: Free default").isFalse();
    }

    private static void assertLimit(Runnable call, String entity, int limit, int current) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(DomainException.class, de -> {
                    assertThat(de.getErrorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(de.getDetails().get("entity")).isEqualTo(entity);
                    assertThat(de.getDetails().get("limit")).isEqualTo(limit);
                    assertThat(de.getDetails().get("current")).isEqualTo(current);
                });
    }
}
