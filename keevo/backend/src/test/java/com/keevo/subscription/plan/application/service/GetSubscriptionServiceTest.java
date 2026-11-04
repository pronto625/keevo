package com.keevo.subscription.plan.application.service;

import com.keevo.identity.api.PlanType;
import com.keevo.subscription.plan.adapter.in.rest.dto.SubscriptionResponse;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.model.SubscriptionStatus;
import com.keevo.subscription.plan.domain.port.in.GetSubscriptionQuery;
import com.keevo.subscription.api.ProductCountPort;
import com.keevo.subscription.plan.domain.port.out.StoreCountPort;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import com.keevo.subscription.plan.domain.port.out.UserCountPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GetSubscriptionServiceTest — Unit tests for {@link GetSubscriptionService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetSubscriptionService")
class GetSubscriptionServiceTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock StoreCountPort storeCountPort;
    @Mock ProductCountPort productCountPort;
    @Mock UserCountPort userCountPort;

    /** Story 16.8 — the limits come from the plan profiles (mutable here: a change shows on the next call). */
    private final java.util.Map<PlanType, PlanProfile> profiles = new java.util.HashMap<>();
    GetSubscriptionService service;

    @org.junit.jupiter.api.BeforeEach
    void init() {
        profiles.put(PlanType.FREE, PlanProfile.of(PlanType.FREE, 1, 500, 2, java.util.Set.of()));
        profiles.put(PlanType.PREMIUM_TRIAL, PlanProfile.of(PlanType.PREMIUM_TRIAL, null, null, null, java.util.Set.of()));
        profiles.put(PlanType.PREMIUM, PlanProfile.of(PlanType.PREMIUM, null, null, null, java.util.Set.of()));
        PlanProfiles source = type -> profiles.get(type);
        service = new GetSubscriptionService(subscriptionRepository, storeCountPort, productCountPort, userCountPort,
                new PlanLimitGuard(source));
    }

    @Test
    @DisplayName("16.8 — limits come from the profile, not from the subscription columns; product count fault is unknown (null)")
    void execute_limitsFromProfile_countFaultShowsZero() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeActiveSub()));
        when(productCountPort.countActiveProducts(any())).thenThrow(new IllegalStateException("db down"));
        profiles.put(PlanType.FREE, PlanProfile.of(PlanType.FREE, 2, 800, 4, java.util.Set.of()));

        SubscriptionResponse response = service.execute(new GetSubscriptionQuery("user-1"));

        assertThat(response.maxStores()).isEqualTo(2);
        assertThat(response.maxProducts()).isEqualTo(800);
        assertThat(response.maxEmployees()).isEqualTo(4);
        assertThat(response.currentProducts()).isNull(); // unknown, never 0;
    }

    @Test
    @DisplayName("19.8 — the usage read fails: the V1 fields still answer, quotas and credits empty")
    void execute_usageFault_quotasEmpty() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeActiveSub()));
        when(productCountPort.countActiveProducts(any())).thenReturn(3);
        com.keevo.shared.capability.entitlement.UsageQuotaPort failing =
                mock(com.keevo.shared.capability.entitlement.UsageQuotaPort.class);
        when(failing.statusForSchema(any())).thenThrow(new IllegalStateException("db down"));
        PlanProfiles source = type -> profiles.get(type);
        GetSubscriptionService withUsage = new GetSubscriptionService(subscriptionRepository, storeCountPort,
                productCountPort, userCountPort, new PlanLimitGuard(source), failing);

        SubscriptionResponse response = withUsage.execute(new GetSubscriptionQuery("user-1"));

        assertThat(response.planType()).isEqualTo("FREE");
        assertThat(response.maxStores()).isEqualTo(1);
        assertThat(response.quotas()).isEmpty();
        assertThat(response.credits()).isEmpty();
    }

    // ── FREE plan ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FREE plan — returns numeric limits and current usage counts")
    void execute_freePlan_returnsLimitsAndCurrentCounts() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeActiveSub()));
        when(storeCountPort.countActiveStores()).thenReturn(1);
        when(productCountPort.countActiveProducts(any())).thenReturn(87);
        when(userCountPort.countEmployees()).thenReturn(2);

        SubscriptionResponse response = service.execute(new GetSubscriptionQuery("user-1"));

        assertThat(response.planType()).isEqualTo("FREE");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.currentStores()).isEqualTo(1);
        assertThat(response.currentProducts()).isEqualTo(87);
        assertThat(response.currentEmployees()).isEqualTo(2);
        assertThat(response.maxStores()).isEqualTo(1);      // FREE limit
        assertThat(response.maxProducts()).isEqualTo(500);  // FREE limit
        assertThat(response.maxEmployees()).isEqualTo(2);   // FREE limit (16.8: validated value, from the profile)
        assertThat(response.expiresAt()).isNull();           // FREE has no expiry
    }

    // ── PREMIUM_TRIAL plan ────────────────────────────────────────────────────

    @Test
    @DisplayName("PREMIUM_TRIAL plan — returns null limits (unlimited) and expiry date")
    void execute_premiumTrialPlan_returnsUnlimitedLimitsWithExpiry() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumTrialSub()));
        when(storeCountPort.countActiveStores()).thenReturn(5);
        when(productCountPort.countActiveProducts(any())).thenReturn(1200);
        when(userCountPort.countEmployees()).thenReturn(10);

        SubscriptionResponse response = service.execute(new GetSubscriptionQuery("user-2"));

        assertThat(response.planType()).isEqualTo("PREMIUM_TRIAL");
        assertThat(response.maxStores()).isNull();       // null = unlimited
        assertThat(response.maxProducts()).isNull();     // null = unlimited
        assertThat(response.maxEmployees()).isNull();    // null = unlimited
        assertThat(response.expiresAt()).isNotNull();    // trial has expiry date
        assertThat(response.currentStores()).isEqualTo(5);
        assertThat(response.currentProducts()).isEqualTo(1200);
        assertThat(response.currentEmployees()).isEqualTo(10);
    }

    // ── PREMIUM plan ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("PREMIUM plan — returns null limits (unlimited) and expiry date")
    void execute_premiumPlan_returnsUnlimitedLimits() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(premiumSub()));
        when(storeCountPort.countActiveStores()).thenReturn(3);
        when(productCountPort.countActiveProducts(any())).thenReturn(450);
        when(userCountPort.countEmployees()).thenReturn(8);

        SubscriptionResponse response = service.execute(new GetSubscriptionQuery("user-3"));

        assertThat(response.planType()).isEqualTo("PREMIUM");
        assertThat(response.maxStores()).isNull();
        assertThat(response.maxProducts()).isNull();
        assertThat(response.maxEmployees()).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Subscription freeActiveSub() {
        return new Subscription(
                UUID.randomUUID(), PlanType.FREE, SubscriptionStatus.ACTIVE,
                1, 500, 3, Instant.now(), null);
    }

    private Subscription premiumTrialSub() {
        Instant expires = Instant.now().plus(180, ChronoUnit.DAYS);
        return new Subscription(
                UUID.randomUUID(), PlanType.PREMIUM_TRIAL, SubscriptionStatus.ACTIVE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Instant.now(), expires);
    }

    private Subscription premiumSub() {
        Instant expires = Instant.now().plus(365, ChronoUnit.DAYS);
        return new Subscription(
                UUID.randomUUID(), PlanType.PREMIUM, SubscriptionStatus.ACTIVE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Instant.now(), expires);
    }
}
