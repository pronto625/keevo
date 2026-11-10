package com.keevo.payments.appstore.application.service;

import com.keevo.identity.api.TenantQueryPort;
import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantView;
import com.keevo.identity.api.PlanType;
import com.keevo.identity.api.UserQueryPort;
import com.keevo.payments.appstore.adapter.out.revenuecat.DisabledRevenueCatClient;
import com.keevo.payments.appstore.domain.model.AppStoreEvent;
import com.keevo.payments.appstore.domain.model.EventOutcome;
import com.keevo.payments.appstore.domain.model.ExternalSubscription;
import com.keevo.payments.appstore.domain.model.SubscriptionStatus;
import com.keevo.payments.appstore.domain.port.out.ExternalSubscriptionStore;
import com.keevo.payments.checkout.domain.model.PaidPlanPeriod;
import com.keevo.payments.checkout.domain.port.out.PaidPlanPeriods;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.api.ChangePlanInput;
import com.keevo.subscription.api.CurrentPlan;
import com.keevo.subscription.api.PlanAdministrationPort;
import com.keevo.subscription.api.StoreProducts;
import com.keevo.subscription.api.StoreProducts.StoreProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AppStoreSubscriptionService (Story 21.2)")
class AppStoreSubscriptionServiceTest {

    @Test
    @DisplayName("client disabled → 503 PAYMENT_UNAVAILABLE in French, nothing written")
    void syncDisabled() {
        ExternalSubscriptionStore store = mock(ExternalSubscriptionStore.class);
        TenantQueryPort tenants = mock(TenantQueryPort.class);
        when(tenants.findBySchemaName("kv_abc123")).thenReturn(Optional.of(new TenantView(UUID.randomUUID(),
                "KV-ABC123", "kv_abc123", "Boutique", TenantStatus.ACTIVE, PlanType.FREE, Instant.now(), null)));
        PlanAdministrationPort plans = mock(PlanAdministrationPort.class);
        AppStoreSubscriptionService service = new AppStoreSubscriptionService(store, mock(StoreProducts.class), plans,
                mock(PlanCatalog.class), mock(PaidPlanPeriods.class), tenants, mock(UserQueryPort.class),
                new DisabledRevenueCatClient(), new AppStoreSettings("", false, null),
                mock(ApplicationEventPublisher.class), mock(PlatformTransactionManager.class), Clock.systemUTC());

        assertThatThrownBy(() -> service.sync("kv_abc123", UUID.randomUUID()))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_UNAVAILABLE);
                    assertThat(e.getUserMessage()).isEqualTo("Les achats App Store sont momentanément indisponibles");
                });
        verifyNoInteractions(store, plans);
    }

    /**
     * Correctif post-test appareil (10/11/2026): a revoked App Store subscription gives the company back to its running
     * Mobile Money period instead of the free plan.
     */
    @Nested
    @DisplayName("revocation and Mobile Money period (correctif post-test appareil)")
    class MobileMoneyPeriod {

        /** 2026-11-10 10:00 WAT. */
        private final Instant now = Instant.parse("2026-11-10T09:00:00Z");
        private final Instant appleEnd = Instant.parse("2026-11-20T09:00:00Z");
        private final UUID tenantId = UUID.randomUUID();
        private final String product = "brefpoint.pro.month";
        private final ExternalSubscriptionStore store = mock(ExternalSubscriptionStore.class);
        private final StoreProducts products = mock(StoreProducts.class);
        private final PlanAdministrationPort plans = mock(PlanAdministrationPort.class);
        private final PlanCatalog catalog = mock(PlanCatalog.class);
        private final PaidPlanPeriods paidPeriods = mock(PaidPlanPeriods.class);
        private AppStoreSubscriptionService service;
        private long eventMs = 1_000L;

        @BeforeEach
        void setUp() {
            when(catalog.freePlan()).thenReturn(PlanType.FREE);
            when(store.claimEvent(any())).thenReturn(true);
            when(products.find(StoreProducts.APP_STORE, product)).thenReturn(Optional.of(new StoreProduct(
                    StoreProducts.APP_STORE, product, "PRO", "Pro", "MONTHLY", "Mensuel", 1, true, null, null)));
            service = new AppStoreSubscriptionService(store, products, plans, catalog, paidPeriods,
                    mock(TenantQueryPort.class), mock(UserQueryPort.class), new DisabledRevenueCatClient(),
                    new AppStoreSettings("", true, null), mock(ApplicationEventPublisher.class),
                    mock(PlatformTransactionManager.class), Clock.fixed(now, ZoneOffset.UTC));
        }

        private ExternalSubscription bound(Instant granted) {
            return new ExternalSubscription(UUID.randomUUID(), tenantId, "kv_abc123", UUID.randomUUID(), "OT-1",
                    product, "PRO", "MONTHLY", SubscriptionStatus.ACTIVE, true, false, appleEnd, null, granted, 0L);
        }

        private EventOutcome send(String type, ExternalSubscription existing, Instant expires) {
            when(store.lock("OT-1")).thenReturn(Optional.ofNullable(existing));
            return service.process(new AppStoreEvent("E-" + eventMs, type, null, "OT-1", null, product,
                    AppStoreEvent.APP_STORE, "PRODUCTION", eventMs++, null, expires, null, null, null, null, null,
                    List.of(), List.of(), null), Map.of());
        }

        private void current(String plan, Instant expires) {
            when(plans.currentPlan("kv_abc123")).thenReturn(Optional.of(new CurrentPlan(PlanType.of(plan), expires)));
        }

        private ChangePlanInput changed() {
            ArgumentCaptor<ChangePlanInput> in = ArgumentCaptor.forClass(ChangePlanInput.class);
            verify(plans).changePlan(in.capture());
            return in.getValue();
        }

        @Test
        @DisplayName("Mobile Money TEAM running until 31/12, Apple PRO expires → TEAM until 31/12 23:59:59 WAT, "
                + "cause paiement en ligne, French reason")
        void runningPeriodRestored() {
            current("PRO", appleEnd);
            when(paidPeriods.latestConfirmed(tenantId))
                    .thenReturn(Optional.of(new PaidPlanPeriod("TEAM", LocalDate.of(2026, 12, 31))));

            assertThat(send("EXPIRATION", bound(appleEnd), appleEnd)).isEqualTo(EventOutcome.APPLIED);

            ChangePlanInput in = changed();
            assertThat(in.newPlan().code()).isEqualTo("TEAM");
            assertThat(in.expiresAt()).isEqualTo(Instant.parse("2026-12-31T22:59:59Z"));
            assertThat(in.cause()).isEqualTo(PlanChangeCause.SELF_SERVICE_PAYMENT);
            assertThat(in.actorId()).isNull();
            assertThat(in.reason()).isEqualTo(
                    "Fin de l'abonnement App Store : retour à la période payée par Mobile Money");
        }

        @Test
        @DisplayName("Mobile Money period ending today (WAT) still runs → restored until tonight 23:59:59 WAT")
        void periodEndingTodayRestored() {
            current("PRO", appleEnd);
            when(paidPeriods.latestConfirmed(tenantId))
                    .thenReturn(Optional.of(new PaidPlanPeriod("PRO", LocalDate.of(2026, 11, 10))));

            send("EXPIRATION", bound(appleEnd), appleEnd);

            ChangePlanInput in = changed();
            assertThat(in.newPlan().code()).isEqualTo("PRO");
            assertThat(in.expiresAt()).isEqualTo(Instant.parse("2026-11-10T22:59:59Z"));
        }

        @Test
        @DisplayName("refund while a Mobile Money period runs → that period, not the free plan")
        void refundRestores() {
            current("PRO", appleEnd);
            when(paidPeriods.latestConfirmed(tenantId))
                    .thenReturn(Optional.of(new PaidPlanPeriod("PRO", LocalDate.of(2026, 12, 5))));
            when(store.lock("OT-1")).thenReturn(Optional.of(bound(appleEnd)));

            service.process(new AppStoreEvent("E-refund", "CANCELLATION", null, "OT-1", "T-1", product,
                    AppStoreEvent.APP_STORE, "PRODUCTION", eventMs++, null, appleEnd, null, "CUSTOMER_SUPPORT", null,
                    null, null, List.of(), List.of(), null), Map.of());

            ChangePlanInput in = changed();
            assertThat(in.newPlan().code()).isEqualTo("PRO");
            assertThat(in.expiresAt()).isEqualTo(Instant.parse("2026-12-05T22:59:59Z"));
        }

        @Test
        @DisplayName("Mobile Money period ended yesterday → free plan (cause EXPIRY)")
        void endedPeriodFree() {
            current("PRO", appleEnd);
            when(paidPeriods.latestConfirmed(tenantId))
                    .thenReturn(Optional.of(new PaidPlanPeriod("TEAM", LocalDate.of(2026, 11, 9))));

            send("EXPIRATION", bound(appleEnd), appleEnd);

            ChangePlanInput in = changed();
            assertThat(in.newPlan()).isEqualTo(PlanType.FREE);
            assertThat(in.expiresAt()).isNull();
            assertThat(in.cause()).isEqualTo(PlanChangeCause.EXPIRY);
            assertThat(in.reason()).isEqualTo("Abonnement App Store terminé");
        }

        @Test
        @DisplayName("never paid by Mobile Money → free plan")
        void noMobileMoneyFree() {
            current("PRO", appleEnd);
            when(paidPeriods.latestConfirmed(tenantId)).thenReturn(Optional.empty());

            send("EXPIRATION", bound(appleEnd), appleEnd);

            assertThat(changed().newPlan()).isEqualTo(PlanType.FREE);
        }

        @Test
        @DisplayName("another plan took over → untouched, the payments are not even read")
        void anotherPlanUntouched() {
            current("TEAM", Instant.parse("2027-01-31T22:59:59Z"));

            send("EXPIRATION", bound(appleEnd), appleEnd);

            verify(plans, never()).changePlan(any());
            verifyNoInteractions(paidPeriods);
        }

        @Test
        @DisplayName("a longer payment of the same plan took over → untouched")
        void longerPaymentUntouched() {
            current("PRO", Instant.parse("2026-12-31T22:59:59Z"));

            send("EXPIRATION", bound(appleEnd), appleEnd);

            verify(plans, never()).changePlan(any());
            verifyNoInteractions(paidPeriods);
        }

        @Test
        @DisplayName("already exactly on the Mobile Money period (same plan, same end) → no plan change, no event")
        void alreadyOnPeriod() {
            Instant momo = Instant.parse("2026-11-20T22:59:59Z");
            current("PRO", momo);
            when(paidPeriods.latestConfirmed(tenantId))
                    .thenReturn(Optional.of(new PaidPlanPeriod("PRO", LocalDate.of(2026, 11, 20))));

            send("EXPIRATION", bound(momo), appleEnd);

            verify(plans, never()).changePlan(any());
        }

        @Test
        @DisplayName("grant shorter than a running Mobile Money period of the same plan → plan untouched (covered); "
                + "its expiration then leaves the Mobile Money period as is")
        void shorterGrantOfSamePlanCovered() {
            current("PRO", Instant.parse("2026-12-31T22:59:59Z"));

            send("INITIAL_PURCHASE", bound(null), appleEnd);
            verify(plans, never()).changePlan(any());

            send("EXPIRATION", bound(appleEnd), appleEnd);
            verify(plans, never()).changePlan(any());
            verifyNoInteractions(paidPeriods);
        }

        @Test
        @DisplayName("grant of another plan during a Mobile Money period → Apple's plan until Apple's expiry")
        void grantOfAnotherPlan() {
            current("TEAM", Instant.parse("2026-12-31T22:59:59Z"));

            send("INITIAL_PURCHASE", bound(null), appleEnd);

            ChangePlanInput in = changed();
            assertThat(in.newPlan().code()).isEqualTo("PRO");
            assertThat(in.expiresAt()).isEqualTo(appleEnd);
            assertThat(in.cause()).isEqualTo(PlanChangeCause.SELF_SERVICE_PAYMENT);
        }
    }
}
