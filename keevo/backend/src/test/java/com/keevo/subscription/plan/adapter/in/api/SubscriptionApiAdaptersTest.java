package com.keevo.subscription.plan.adapter.in.api;

import com.keevo.identity.api.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.subscription.api.ActivatePlanInput;
import com.keevo.subscription.api.ChangePlanInput;
import com.keevo.subscription.api.CurrentPlan;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.subscription.plan.application.service.PlanLimitGuard;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.model.SubscriptionStatus;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanCommand;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanUseCase;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanCommand;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanUseCase;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Story 12.2d — {@code subscription :: api} adapters: mapping, limit delegation, tenant guard, admin plan changes. */
@ExtendWith(MockitoExtension.class)
class SubscriptionApiAdaptersTest {

    private static final String TENANT = "kv_abc123";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private ActivatePlanUseCase activatePlanUseCase;
    @Mock private ChangeTenantPlanUseCase changeTenantPlanUseCase;

    private final PlanLimitGuard guard = new PlanLimitGuard();

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Subscription subscription(PlanType plan, SubscriptionStatus status, Instant expiresAt) {
        return new Subscription(UUID.randomUUID(), plan, status, 1, 500, 3, Instant.now(), expiresAt);
    }

    @Test
    void findCurrentPlan_mapsPlanStatusAndExpiry() {
        Instant exp = Instant.parse("2026-12-31T22:59:59Z");
        when(subscriptionRepository.findActivePlan())
                .thenReturn(Optional.of(subscription(PlanType.PREMIUM, SubscriptionStatus.SUSPENDED, exp)));
        var adapter = new SubscriptionPlanAdapter(subscriptionRepository, guard);

        PlanView plan = adapter.findCurrentPlan(TENANT).orElseThrow();

        assertThat(plan).isEqualTo(new PlanView(PlanType.PREMIUM, false, exp));
        assertThat(plan.isPremium()).isTrue();
        assertThat(plan.isPremiumTrial()).isFalse();
        assertThat(plan.isFree()).isFalse();
    }

    @Test
    void findCurrentPlan_noRow_empty() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.empty());
        assertThat(new SubscriptionPlanAdapter(subscriptionRepository, guard).findCurrentPlan(TENANT)).isEmpty();
    }

    @Test
    void findCurrentPlan_otherTenantThanContext_failsFast() {
        var adapter = new SubscriptionPlanAdapter(subscriptionRepository, guard);
        assertThatThrownBy(() -> adapter.findCurrentPlan("kv_other1")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    void freePlanLimits_unchanged_oneStore_500Products_3EmployeeSlots() {
        var adapter = new SubscriptionPlanAdapter(subscriptionRepository, guard);
        PlanView free = new PlanView(PlanType.FREE, true, null);

        adapter.checkStoreLimit(free, 0);
        adapter.checkProductLimit(free, 499);
        assertThat(free.maxStores()).isEqualTo(1);
        assertThat(free.maxProducts()).isEqualTo(500);
        assertThat(free.maxEmployees()).isEqualTo(PlanType.FREE.getMaxEmployees());
        assertLimitReached(() -> adapter.checkStoreLimit(free, 1));
        assertLimitReached(() -> adapter.checkProductLimit(free, 500));
        assertLimitReached(() -> adapter.checkEmployeeLimit(free, PlanType.FREE.getMaxEmployees()));
        adapter.checkProductLimit(new PlanView(PlanType.PREMIUM_TRIAL, true, null), 100_000);
    }

    private static void assertLimitReached(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));
    }

    @Test
    void planAdministration_delegatesWithTheSameValues() {
        var adapter = new PlanAdministrationAdapter(activatePlanUseCase, changeTenantPlanUseCase);
        UUID tenantId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant exp = Instant.parse("2027-01-31T22:59:59Z");
        CurrentPlan before = new CurrentPlan(PlanType.FREE, null);
        when(changeTenantPlanUseCase.currentPlan(TENANT)).thenReturn(Optional.of(before));
        when(changeTenantPlanUseCase.changePlan(new ChangeTenantPlanCommand(tenantId, TENANT, PlanType.PREMIUM,
                exp, PlanChangeCause.ADMIN_ACTIVATION, actor, "note"))).thenReturn(before);

        adapter.activatePlan(new ActivatePlanInput(actor, TENANT, PlanType.PREMIUM, exp));
        assertThat(adapter.currentPlan(TENANT)).contains(before);
        assertThat(adapter.changePlan(new ChangePlanInput(tenantId, TENANT, PlanType.PREMIUM, exp,
                PlanChangeCause.ADMIN_ACTIVATION, actor, "note"))).isEqualTo(before);

        verify(activatePlanUseCase).execute(new ActivatePlanCommand(actor.toString(), TENANT, PlanType.PREMIUM, exp));
    }

    @Test
    void planView_requiresAPlanType_and_limitChecks_requireAPlan() {
        var adapter = new SubscriptionPlanAdapter(subscriptionRepository, guard);
        assertThatThrownBy(() -> new PlanView(null, true, null)).isInstanceOf(IllegalArgumentException.class);
        for (Runnable call : new Runnable[] {
                () -> adapter.checkStoreLimit(null, 0),
                () -> adapter.checkProductLimit(null, 0),
                () -> adapter.checkEmployeeLimit(null, 0)}) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        }
    }

    @Test
    void currentPlanAndChangePlan_requireTheCallersTransaction() throws NoSuchMethodException {
        for (var m : new java.lang.reflect.Method[] {
                PlanAdministrationAdapter.class.getMethod("currentPlan", String.class),
                PlanAdministrationAdapter.class.getMethod("changePlan", ChangePlanInput.class)}) {
            var tx = m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertThat(tx).as(m.getName()).isNotNull();
            assertThat(tx.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
        }
    }

    @Test
    void inputsWithoutRequiredValues_validationError() {
        assertThatThrownBy(() -> new ActivatePlanInput(UUID.randomUUID(), null, PlanType.FREE, null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> new ChangePlanInput(UUID.randomUUID(), TENANT, PlanType.FREE, null, null, null, null))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
