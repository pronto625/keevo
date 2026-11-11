package com.keevo.admin.subscription.application.service;

import com.keevo.subscription.plan.adapter.in.api.SubscriptionApiTestSupport;
import com.keevo.admin.subscription.application.service.TargetTenantLookup.TargetTenant;
import com.keevo.admin.subscription.domain.model.PaymentMethod;
import com.keevo.admin.subscription.domain.model.PlanAlert;
import com.keevo.admin.subscription.domain.model.TenantPayment;
import com.keevo.admin.subscription.domain.port.in.ActivatePremiumCommand;
import com.keevo.admin.subscription.domain.port.in.DowngradeToFreeCommand;
import com.keevo.admin.subscription.domain.port.out.TenantPaymentRepository;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.event.catalog.plan.PlanActivatedEvent;
import com.keevo.shared.event.catalog.plan.PlanDowngradedEvent;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanCommand;
import com.keevo.subscription.plan.domain.port.in.ChangeTenantPlanUseCase;
import com.keevo.subscription.api.CurrentPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.SQLException;
import java.sql.Timestamp;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPlanService (Story 9-2)")
class AdminPlanServiceTest {

    /** 2026-10-13 10:00 WAT. */
    private static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final String SCHEMA = "kv_abc123";

    @Mock TargetTenantLookup lookup;
    @Mock ChangeTenantPlanUseCase changePlan;
    @Mock TenantPaymentRepository payments;
    @Mock ApplicationEventPublisher publisher;
    @Mock JdbcTemplate jdbc;

    AdminPlanService service;

    @BeforeEach
    void setUp() {
        service = new AdminPlanService(lookup, SubscriptionApiTestSupport.administrationPort(null, changePlan), payments, publisher, jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void tenant(String status) {
        when(lookup.load(TENANT)).thenReturn(new TargetTenant(TENANT, "Boutique Awa", SCHEMA, status));
    }

    private ActivatePremiumCommand activation(LocalDate expiresAt, long amount, PaymentMethod method) {
        return new ActivatePremiumCommand(ACTOR, TENANT, expiresAt, amount, method, null, "Orange Money");
    }

    // ── Activate ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("activate from FREE: payment today→expiresAt, the default paid plan (19.7b: TEAM) until end of day "
            + "WAT, event published with the granted plan")
    void activate_fromFree() {
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.FREE, null)));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.FREE, null));
        LocalDate expires = TODAY.plusDays(30);

        CurrentPlan result = service.activatePremium(activation(expires, 10_000, PaymentMethod.MOBILE_MONEY));

        Instant endOfDay = Instant.parse("2026-11-12T22:59:59Z"); // 23:59:59 WAT
        assertThat(result).isEqualTo(new CurrentPlan(PlanType.of("TEAM"), endOfDay));

        ArgumentCaptor<TenantPayment> payment = ArgumentCaptor.forClass(TenantPayment.class);
        verify(payments).insert(payment.capture());
        assertThat(payment.getValue().periodStart()).isEqualTo(TODAY);
        assertThat(payment.getValue().periodEnd()).isEqualTo(expires);
        assertThat(payment.getValue().amountXaf()).isEqualTo(10_000);
        assertThat(payment.getValue().method()).isEqualTo(PaymentMethod.MOBILE_MONEY);
        assertThat(payment.getValue().paidAt()).isEqualTo(NOW);
        assertThat(payment.getValue().recordedBy()).isEqualTo(ACTOR);

        ArgumentCaptor<ChangeTenantPlanCommand> change = ArgumentCaptor.forClass(ChangeTenantPlanCommand.class);
        verify(changePlan).changePlan(change.capture());
        assertThat(change.getValue().newPlan()).isEqualTo(PlanType.of("TEAM"));
        assertThat(change.getValue().expiresAt()).isEqualTo(endOfDay);
        assertThat(change.getValue().cause()).isEqualTo(PlanChangeCause.ADMIN_ACTIVATION);
        assertThat(change.getValue().schemaName()).isEqualTo(SCHEMA);

        verify(publisher).publishEvent(new PlanActivatedEvent(ACTOR, TENANT, SCHEMA, PlanType.FREE,
                endOfDay, 10_000, NOW, PlanType.of("TEAM")));
    }

    @Test
    @DisplayName("renewal before expiry: period starts the day after the current Premium expiry")
    void activate_renewal_extendsFromCurrentExpiry() {
        tenant("ACTIVE");
        Instant currentExpiry = AdminPlanService.endOfDayWat(TODAY.plusDays(5));
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.PREMIUM, currentExpiry)));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.PREMIUM, currentExpiry));

        service.activatePremium(activation(TODAY.plusDays(35), 10_000, PaymentMethod.CASH));

        ArgumentCaptor<TenantPayment> payment = ArgumentCaptor.forClass(TenantPayment.class);
        verify(payments).insert(payment.capture());
        assertThat(payment.getValue().periodStart()).isEqualTo(TODAY.plusDays(6));
        assertThat(payment.getValue().periodEnd()).isEqualTo(TODAY.plusDays(35));
    }

    @Test
    @DisplayName("a trial does not shift the period start (only a paid Premium is extended)")
    void activate_fromTrial_startsToday() {
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(
                new CurrentPlan(PlanType.PREMIUM_TRIAL, AdminPlanService.endOfDayWat(TODAY.plusDays(20)))));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.PREMIUM_TRIAL, null));

        service.activatePremium(activation(TODAY.plusDays(30), 5_000, PaymentMethod.OTHER));

        ArgumentCaptor<TenantPayment> payment = ArgumentCaptor.forClass(TenantPayment.class);
        verify(payments).insert(payment.capture());
        assertThat(payment.getValue().periodStart()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("renewal with an expiry before the current one → 422 VALIDATION_ERROR, nothing written")
    void activate_expiryBeforeCurrent_rejected() {
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(
                new CurrentPlan(PlanType.PREMIUM, AdminPlanService.endOfDayWat(TODAY.plusDays(40)))));

        assertValidationError(() -> service.activatePremium(activation(TODAY.plusDays(35), 10_000, PaymentMethod.CASH)),
                "expiresAt");
        // same day as the current expiry: a retry would record a payment covering zero days
        assertValidationError(() -> service.activatePremium(activation(TODAY.plusDays(40), 10_000, PaymentMethod.CASH)),
                "expiresAt");
        verify(payments, never()).insert(any());
        verifyNoInteractions(publisher);
        verify(changePlan, never()).changePlan(any());
    }

    @Test
    @DisplayName("renewal: an expiry granted by a since-voided payment no longer blocks a shorter, corrected date")
    void activate_renewal_ignoresVoidedPaymentEnd() {
        tenant("ACTIVE");
        LocalDate typoEnd = TODAY.plusYears(2);
        LocalDate countedEnd = TODAY.plusDays(5);
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(
                new CurrentPlan(PlanType.PREMIUM, AdminPlanService.endOfDayWat(typoEnd))));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.PREMIUM, null));
        when(payments.findByTenant(TENANT)).thenReturn(List.of(
                payment(TODAY.minusDays(20), typoEnd, Instant.parse("2026-10-12T09:00:00Z")),
                payment(TODAY.minusDays(25), countedEnd, null)));

        service.activatePremium(activation(TODAY.plusDays(35), 10_000, PaymentMethod.CASH));

        ArgumentCaptor<TenantPayment> payment = ArgumentCaptor.forClass(TenantPayment.class);
        verify(payments).insert(payment.capture());
        assertThat(payment.getValue().periodStart()).isEqualTo(countedEnd.plusDays(1));
    }

    @Test
    @DisplayName("expiresAt more than 3 years after today → « Date d'expiration trop lointaine »; exactly 3 years accepted")
    void activate_expiryTooFar() {
        assertThatThrownBy(() -> service.activatePremium(activation(TODAY.plusYears(3).plusDays(1), 10, PaymentMethod.CASH)))
                .satisfies(e -> assertThat(((DomainException) e).getDetails())
                        .containsEntry("expiresAt", "Date d'expiration trop lointaine"));
        verifyNoInteractions(lookup, changePlan, payments, publisher);

        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.FREE, null)));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.FREE, null));
        service.activatePremium(activation(TODAY.plusYears(3), 10, PaymentMethod.CASH));
        verify(payments).insert(any());
    }

    @Test
    @DisplayName("invalid input (expiresAt ≤ today, amount ≤ 0, no method, future paidAt, long note) → 422, nothing written")
    void activate_invalidInput() {
        assertValidationError(() -> service.activatePremium(activation(TODAY, 10_000, PaymentMethod.CASH)), "expiresAt");
        assertValidationError(() -> service.activatePremium(activation(TODAY.minusDays(1), 10_000, PaymentMethod.CASH)), "expiresAt");
        assertValidationError(() -> service.activatePremium(activation(TODAY.plusDays(1), 0, PaymentMethod.CASH)), "amountXaf");
        assertValidationError(() -> service.activatePremium(activation(TODAY.plusDays(1), -5, PaymentMethod.CASH)), "amountXaf");
        assertValidationError(() -> service.activatePremium(activation(TODAY.plusDays(1), 10, null)), "method");
        assertValidationError(() -> service.activatePremium(new ActivatePremiumCommand(ACTOR, TENANT,
                TODAY.plusDays(1), 10, PaymentMethod.CASH, NOW.plusSeconds(3600), null)), "paidAt");
        assertValidationError(() -> service.activatePremium(new ActivatePremiumCommand(ACTOR, TENANT,
                TODAY.plusDays(1), 10, PaymentMethod.CASH, null, "x".repeat(501))), "note");
        verifyNoInteractions(lookup, changePlan, payments, publisher);
    }

    @Test
    @DisplayName("amount above the integer range → « Montant trop élevé »; paidAt older than 366 days → « Date de paiement trop ancienne »")
    void activate_amountTooHigh_paidAtTooOld() {
        assertThatThrownBy(() -> service.activatePremium(
                activation(TODAY.plusDays(1), Integer.MAX_VALUE + 1L, PaymentMethod.CASH)))
                .satisfies(e -> assertThat(((DomainException) e).getDetails()).containsEntry("amountXaf", "Montant trop élevé"));
        assertThatThrownBy(() -> service.activatePremium(new ActivatePremiumCommand(ACTOR, TENANT,
                TODAY.plusDays(1), 10, PaymentMethod.CASH, NOW.minus(java.time.Duration.ofDays(367)), null)))
                .satisfies(e -> assertThat(((DomainException) e).getDetails())
                        .containsEntry("paidAt", "Date de paiement trop ancienne"));
        verifyNoInteractions(lookup, changePlan, payments, publisher);
    }

    @Test
    @DisplayName("DELETION_PENDING tenant → INVALID_STATE for activation and downgrade")
    void deletionPending_refused() {
        when(lookup.load(TENANT)).thenReturn(new TargetTenant(TENANT, "Boutique Awa", SCHEMA, "DELETION_PENDING"));

        assertThatThrownBy(() -> service.activatePremium(activation(TODAY.plusDays(30), 10_000, PaymentMethod.CASH)))
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("INVALID_STATE");
        assertThatThrownBy(() -> service.downgradeToFree(new DowngradeToFreeCommand(ACTOR, TENANT, null)))
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("INVALID_STATE");
        verifyNoInteractions(changePlan, payments, publisher);
    }

    @Test
    @DisplayName("unknown tenant → TENANT_NOT_FOUND, nothing written")
    void activate_unknownTenant() {
        when(lookup.load(TENANT)).thenThrow(new DomainException(ErrorCode.TENANT_NOT_FOUND, "x"));

        assertThatThrownBy(() -> service.activatePremium(activation(TODAY.plusDays(30), 10_000, PaymentMethod.CASH)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("TENANT_NOT_FOUND");
        verifyNoInteractions(changePlan, payments, publisher);
    }

    @Test
    @DisplayName("deleted tenant → INVALID_STATE")
    void activate_deletedTenant() {
        tenant("DELETED");

        assertThatThrownBy(() -> service.activatePremium(activation(TODAY.plusDays(30), 10_000, PaymentMethod.CASH)))
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("INVALID_STATE");
        verifyNoInteractions(changePlan, payments, publisher);
    }

    // ── Downgrade ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("downgrade PREMIUM → FREE: change with no expiry, event published")
    void downgrade_ok() {
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.PREMIUM, NOW)));

        service.downgradeToFree(new DowngradeToFreeCommand(ACTOR, TENANT, "  non renouvelé "));

        ArgumentCaptor<ChangeTenantPlanCommand> change = ArgumentCaptor.forClass(ChangeTenantPlanCommand.class);
        verify(changePlan).changePlan(change.capture());
        assertThat(change.getValue().newPlan()).isEqualTo(PlanType.FREE);
        assertThat(change.getValue().expiresAt()).isNull();
        assertThat(change.getValue().cause()).isEqualTo(PlanChangeCause.ADMIN_DOWNGRADE);
        assertThat(change.getValue().reason()).isEqualTo("non renouvelé");
        verify(publisher).publishEvent(new PlanDowngradedEvent(ACTOR, TENANT, SCHEMA, PlanType.PREMIUM,
                "non renouvelé", NOW));
    }

    @Test
    @DisplayName("downgrade a tenant already FREE → INVALID_STATE, nothing written")
    void downgrade_alreadyFree() {
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.FREE, null)));

        assertThatThrownBy(() -> service.downgradeToFree(new DowngradeToFreeCommand(ACTOR, TENANT, null)))
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("INVALID_STATE");
        verify(changePlan, never()).changePlan(any());
        verifyNoInteractions(publisher);
    }

    // ── Story 19.7a — by kind, plans created as data ─────────────────────────

    private static final PlanType TEST_PAID = PlanType.of("TEST_PAID");
    private static final PlanType TEST_FREE = PlanType.of("TEST_FREE");

    /** The seed plus TEST_PAID (kind PAID) and TEST_FREE (kind FREE, the free plan of the settings). */
    private AdminPlanService dataPlanService() {
        java.util.List<com.keevo.shared.capability.entitlement.PlanDefinition> plans =
                new java.util.ArrayList<>(com.keevo.shared.capability.entitlement.PlanCatalog.SEED.all());
        plans.add(new com.keevo.shared.capability.entitlement.PlanDefinition(TEST_PAID, "Test payant", null, 9, true,
                com.keevo.identity.api.PlanKind.PAID, null));
        plans.add(new com.keevo.shared.capability.entitlement.PlanDefinition(TEST_FREE, "Test gratuit", null, 10,
                true, com.keevo.identity.api.PlanKind.FREE, null));
        com.keevo.shared.capability.entitlement.PlanCatalog catalog = com.keevo.shared.capability.entitlement
                .PlanCatalog.of(plans, new com.keevo.shared.capability.entitlement.PlanSettings(TEST_FREE,
                        PlanType.PREMIUM_TRIAL, 6, PlanType.PREMIUM));
        return new AdminPlanService(lookup, SubscriptionApiTestSupport.administrationPort(null, changePlan), payments,
                publisher, jdbc, Clock.fixed(NOW, ZoneOffset.UTC), catalog);
    }

    @Test
    @DisplayName("19.7a/19.7b: a running data plan of kind PAID, granted again by code, is renewed from its end; a "
            + "non-later expiry is refused")
    void activate_renewalOfADataPaidPlan() {
        AdminPlanService dataService = dataPlanService();
        tenant("ACTIVE");
        Instant currentExpiry = AdminPlanService.endOfDayWat(TODAY.plusDays(5));
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(TEST_PAID, currentExpiry)));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(TEST_PAID, currentExpiry));

        assertValidationError(() -> dataService.activatePremium(activation(TODAY.plusDays(5), "test_paid")),
                "expiresAt");
        dataService.activatePremium(activation(TODAY.plusDays(35), "test_paid"));

        ArgumentCaptor<TenantPayment> payment = ArgumentCaptor.forClass(TenantPayment.class);
        verify(payments).insert(payment.capture());
        assertThat(payment.getValue().periodStart()).isEqualTo(TODAY.plusDays(6));
        ArgumentCaptor<ChangeTenantPlanCommand> change = ArgumentCaptor.forClass(ChangeTenantPlanCommand.class);
        verify(changePlan).changePlan(change.capture());
        assertThat(change.getValue().newPlan()).isEqualTo(TEST_PAID);
    }

    private ActivatePremiumCommand activation(LocalDate expiresAt, String planCode) {
        return new ActivatePremiumCommand(ACTOR, TENANT, expiresAt, 10_000, PaymentMethod.CASH, null, null, planCode);
    }

    @Test
    @DisplayName("19.7b: moving from a running paid plan to another paid plan keeps the paid days: the period starts "
            + "the day after the current end (ADR-06 no loss)")
    void activate_otherPaidPlan_renewsFromCurrentEnd() {
        AdminPlanService dataService = dataPlanService();
        tenant("ACTIVE");
        Instant currentExpiry = AdminPlanService.endOfDayWat(TODAY.plusDays(5));
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.PREMIUM, currentExpiry)));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.PREMIUM, currentExpiry));

        CurrentPlan result = dataService.activatePremium(activation(TODAY.plusDays(30), "TEST_PAID"));

        assertThat(result.planType()).isEqualTo(TEST_PAID);
        ArgumentCaptor<TenantPayment> payment = ArgumentCaptor.forClass(TenantPayment.class);
        verify(payments).insert(payment.capture());
        assertThat(payment.getValue().periodStart()).isEqualTo(TODAY.plusDays(6));
    }

    @Test
    @DisplayName("19.7b: a plan code that is not a live paid plan (free, trial, archived, unknown) → 422 « Ce plan ne "
            + "peut pas être attribué », nothing written")
    void activate_invalidPlanCode() {
        java.util.List<com.keevo.shared.capability.entitlement.PlanDefinition> plans =
                new java.util.ArrayList<>(com.keevo.shared.capability.entitlement.PlanCatalog.SEED.all());
        plans.add(new com.keevo.shared.capability.entitlement.PlanDefinition(PlanType.of("TEST_OLD"), "Ancien", null,
                9, false, com.keevo.identity.api.PlanKind.PAID, NOW));
        AdminPlanService archivedService = new AdminPlanService(lookup,
                SubscriptionApiTestSupport.administrationPort(null, changePlan), payments, publisher, jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC), com.keevo.shared.capability.entitlement.PlanCatalog.of(plans,
                        com.keevo.shared.capability.entitlement.PlanCatalog.SEED.settings()));
        for (String code : List.of("FREE", "PREMIUM_TRIAL", "TEST_OLD", "NOPE")) {
            assertThatThrownBy(() -> archivedService.activatePremium(activation(TODAY.plusDays(30), code)))
                    .isInstanceOfSatisfying(DomainException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(e.getUserMessage()).isEqualTo("Ce plan ne peut pas être attribué");
                    });
        }
        verifyNoInteractions(lookup, payments, publisher);
        verify(changePlan, never()).changePlan(any());
    }

    /** Story 21.10 — the service with the offer port (billing cycles). */
    private AdminPlanService withOffers(com.keevo.subscription.api.OfferAdministration offers) {
        return new AdminPlanService(lookup, SubscriptionApiTestSupport.administrationPort(null, changePlan), payments,
                publisher, jdbc, Clock.fixed(NOW, ZoneOffset.UTC),
                com.keevo.shared.capability.entitlement.PlanCatalog.SEED, offers);
    }

    private static com.keevo.subscription.api.OfferAdministration.CycleItem cycle(String code, boolean archived) {
        return new com.keevo.subscription.api.OfferAdministration.CycleItem(code, code, 3, 20, archived,
                archived ? NOW : null);
    }

    @Test
    @DisplayName("21.10: the payment stores the plan granted and the billing cycle paid for (code normalised)")
    void activate_storesPlanAndCycle() {
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.FREE, null)));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.FREE, null));
        com.keevo.subscription.api.OfferAdministration offers =
                mock(com.keevo.subscription.api.OfferAdministration.class);
        when(offers.cycle("QUARTERLY")).thenReturn(Optional.of(cycle("QUARTERLY", false)));

        withOffers(offers).activatePremium(new ActivatePremiumCommand(ACTOR, TENANT, TODAY.plusDays(90), 15_000,
                PaymentMethod.CASH, null, null, "team", " quarterly "));

        ArgumentCaptor<TenantPayment> payment = ArgumentCaptor.forClass(TenantPayment.class);
        verify(payments).insert(payment.capture());
        assertThat(payment.getValue().planCode()).isEqualTo("TEAM");
        assertThat(payment.getValue().cycleCode()).isEqualTo("QUARTERLY");
    }

    @Test
    @DisplayName("21.10: without a cycle the payment keeps a free-form period (cycle null), the default plan stored")
    void activate_withoutCycle_storesDefaultPlan() {
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.FREE, null)));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.FREE, null));

        service.activatePremium(activation(TODAY.plusDays(30), 10_000, PaymentMethod.CASH));

        ArgumentCaptor<TenantPayment> payment = ArgumentCaptor.forClass(TenantPayment.class);
        verify(payments).insert(payment.capture());
        assertThat(payment.getValue().planCode()).isEqualTo("TEAM");
        assertThat(payment.getValue().cycleCode()).isNull();
    }

    @Test
    @DisplayName("21.10: an unknown or archived cycle → 422 « Cette durée ne peut pas être choisie », nothing written")
    void activate_badCycle() {
        com.keevo.subscription.api.OfferAdministration offers =
                mock(com.keevo.subscription.api.OfferAdministration.class);
        when(offers.cycle("WEEKLY")).thenReturn(Optional.empty());
        when(offers.cycle("OLD")).thenReturn(Optional.of(cycle("OLD", true)));
        AdminPlanService withCycles = withOffers(offers);

        for (String code : List.of("weekly", "old")) {
            assertThatThrownBy(() -> withCycles.activatePremium(new ActivatePremiumCommand(ACTOR, TENANT,
                    TODAY.plusDays(30), 10_000, PaymentMethod.CASH, null, null, null, code)))
                    .isInstanceOfSatisfying(DomainException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                        assertThat(e.getUserMessage()).isEqualTo("Cette durée ne peut pas être choisie");
                    });
        }
        verifyNoInteractions(lookup, payments, publisher);
    }

    @Test
    @DisplayName("21.10 (pins 19.7b): the archived PREMIUM of the seeded catalogue cannot be activated → 422")
    void activate_archivedPremium_refused() {
        assertThatThrownBy(() -> service.activatePremium(activation(TODAY.plusDays(30), "PREMIUM")))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(e.getUserMessage()).isEqualTo("Ce plan ne peut pas être attribué");
                });
        verifyNoInteractions(lookup, payments, publisher);
    }

    @Test
    @DisplayName("19.7a: downgrading a tenant already on a plan of kind FREE (data) → INVALID_STATE, nothing written")
    void downgrade_alreadyOnADataFreePlan() {
        AdminPlanService dataService = dataPlanService();
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(TEST_FREE, null)));

        assertThatThrownBy(() -> dataService.downgradeToFree(new DowngradeToFreeCommand(ACTOR, TENANT, null)))
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("INVALID_STATE");
        verify(changePlan, never()).changePlan(any());
    }

    // ── Payments ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("payments: unknown tenant → TENANT_NOT_FOUND; known → repository list")
    void payments() {
        tenant("ACTIVE");
        TenantPayment p = new TenantPayment(UUID.randomUUID(), TENANT, 1000, PaymentMethod.CASH, NOW,
                TODAY, TODAY.plusDays(30), null, ACTOR);
        when(payments.findByTenant(TENANT)).thenReturn(List.of(p));

        assertThat(service.listPayments(TENANT)).containsExactly(p);
    }

    @Test
    @DisplayName("void: counted payment → voided with the trimmed reason and the clock time")
    void void_ok() {
        tenant("ACTIVE");
        UUID id = UUID.randomUUID();
        when(payments.findById(TENANT, id)).thenReturn(Optional.of(new TenantPayment(id, TENANT, 10_000,
                PaymentMethod.CASH, NOW, TODAY, TODAY.plusDays(30), null, ACTOR)));
        when(payments.voidPayment(TENANT, id, ACTOR, "Erreur de saisie", NOW)).thenReturn(true);

        service.voidPayment(new com.keevo.admin.subscription.domain.port.in.VoidTenantPaymentCommand(
                ACTOR, TENANT, id, "  Erreur de saisie "));

        verify(payments).voidPayment(TENANT, id, ACTOR, "Erreur de saisie", NOW);
    }

    @Test
    @DisplayName("void: reason < 3 / > 500 → 422; unknown payment → 404 NOT_FOUND; already voided → 409 INVALID_STATE")
    void void_errors() {
        UUID id = UUID.randomUUID();
        for (String reason : new String[]{null, " ab ", "x".repeat(501)}) {
            assertValidationError(() -> service.voidPayment(
                    new com.keevo.admin.subscription.domain.port.in.VoidTenantPaymentCommand(ACTOR, TENANT, id, reason)),
                    "reason");
        }
        verifyNoInteractions(lookup, payments);

        tenant("ACTIVE");
        var cmd = new com.keevo.admin.subscription.domain.port.in.VoidTenantPaymentCommand(ACTOR, TENANT, id, "Erreur");
        when(payments.findById(TENANT, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.voidPayment(cmd))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);

        when(payments.findById(TENANT, id)).thenReturn(Optional.of(payment(TODAY, TODAY.plusDays(30), NOW)));
        assertThatThrownBy(() -> service.voidPayment(cmd))
                .extracting(e -> ((DomainException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE);
        verify(payments, never()).voidPayment(any(), any(), any(), any(), any());
    }

    private static TenantPayment payment(LocalDate start, LocalDate end, Instant voidedAt) {
        return new TenantPayment(UUID.randomUUID(), TENANT, 10_000, PaymentMethod.CASH, NOW.minusSeconds(86_400),
                start, end, null, ACTOR, voidedAt, voidedAt != null ? "Erreur de saisie" : null);
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("alerts: expiring within 7 days with daysLeft (WAT), expired in the last 7 days; broken schema skipped")
    void alerts() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(jdbc.queryForList(AdminPlanService.ALERT_TENANTS_SQL, "TRIAL", "PAID")).thenReturn(List.of(
                Map.of("id", a, "name", "Boutique A", "schema_name", "kv_aaaaa1"),
                Map.of("id", b, "name", "Boutique B", "schema_name", "kv_bbbbb2"),
                Map.of("id", UUID.randomUUID(), "name", "Bad", "schema_name", "public")));
        Instant aExpiry = AdminPlanService.endOfDayWat(TODAY.plusDays(3));
        // Story 19.11c: one union for every tenant; kv_bbbbb2 fails and is skipped by CrossTenantSql (no row)
        com.keevo.admin.crosstenant.CrossTenantSql crossTenant = crossTenantAnswering(Map.of(
                "kv_aaaaa1", new Object[]{"PREMIUM", Timestamp.from(aExpiry)}));
        List<Object> params = new java.util.ArrayList<>();
        service = withCrossTenant(crossTenant, params);
        PlanAlert expired = new PlanAlert(UUID.randomUUID(), "Boutique C", PlanAlert.Kind.EXPIRED, "PREMIUM",
                null, null, NOW.minusSeconds(3600));
        when(jdbc.query(eq(AdminPlanService.EXPIRED_SQL), any(RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of(expired));

        List<PlanAlert> alerts = service.listAlerts();

        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0)).isEqualTo(new PlanAlert(a, "Boutique A", PlanAlert.Kind.EXPIRING, "PREMIUM",
                3, aExpiry, null));
        assertThat(alerts.get(1)).isEqualTo(expired);
        // lower bound bound from the app clock, not the DB NOW(); the invalid schema is never a branch
        assertThat(params).hasSize(4).startsWith("TRIAL", "PAID", Timestamp.from(NOW));
        @SuppressWarnings("rawtypes")
        org.mockito.ArgumentCaptor<List> branches = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(crossTenant).query(branches.capture(), any(), any(), any());
        assertThat(branches.getValue()).hasSize(2);
    }

    /** A cross-tenant reader answering, per schema, {plan_type, expires_at} through the service's row mapper. */
    @SuppressWarnings("unchecked")
    private static com.keevo.admin.crosstenant.CrossTenantSql crossTenantAnswering(Map<String, Object[]> rows) {
        var crossTenant = mock(com.keevo.admin.crosstenant.CrossTenantSql.class);
        when(crossTenant.withPresence(anyList())).thenAnswer(inv ->
                ((List<com.keevo.admin.crosstenant.TenantBranch>) inv.getArgument(0)).stream()
                        .map(t -> t.withTables(java.util.EnumSet.allOf(com.keevo.admin.crosstenant.TenantTable.class)))
                        .toList());
        when(crossTenant.query(anyList(), any(), any(), any())).thenAnswer(inv -> {
            List<com.keevo.admin.crosstenant.TenantBranch> tenants = inv.getArgument(0);
            com.keevo.admin.crosstenant.CrossTenantSql.BranchSql branch = inv.getArgument(1);
            RowMapper<Object> mapper = inv.getArgument(3);
            List<Object> params = PARAMS.get();
            List<Object> out = new java.util.ArrayList<>();
            for (var t : tenants) {
                params.clear();
                branch.sql(t, params);
                Object[] row = rows.get(t.schemaName());
                if (row == null) continue;
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getString("tenant_id")).thenReturn(t.id());
                when(rs.getString("plan_type")).thenReturn((String) row[0]);
                when(rs.getTimestamp("expires_at")).thenReturn((Timestamp) row[1]);
                out.add(mapper.mapRow(rs, out.size()));
            }
            return out;
        });
        return crossTenant;
    }

    /** Bound values of the last branch built by {@link #crossTenantAnswering}. */
    private static final ThreadLocal<List<Object>> PARAMS = ThreadLocal.withInitial(java.util.ArrayList::new);

    private AdminPlanService withCrossTenant(com.keevo.admin.crosstenant.CrossTenantSql crossTenant,
                                             List<Object> params) {
        PARAMS.set(params);
        return new AdminPlanService(lookup, SubscriptionApiTestSupport.administrationPort(null, changePlan), payments,
                publisher, jdbc, Clock.fixed(NOW, ZoneOffset.UTC), com.keevo.shared.capability.entitlement.PlanCatalog.SEED,
                null, crossTenant);
    }

    @Test
    @DisplayName("alerts SQL: non-FREE live tenants only; EXPIRED excludes deletion states and later plan changes")
    void alerts_sqlShape() {
        // Story 19.7a: plans that expire by kind (TRIAL, PAID — joined from the catalogue), no plan code
        assertThat(AdminPlanService.ALERT_TENANTS_SQL).contains("p.kind IN (?, ?)").contains("'DELETION_PENDING'")
                .doesNotContain("FREE");
        assertThat(AdminPlanService.EXPIRING_SQL).contains("JOIN public.plans p").doesNotContain("PREMIUM");
        assertThat(AdminPlanService.EXPIRING_SQL).doesNotContain("NOW()");
        assertThat(AdminPlanService.EXPIRED_SQL).contains("'DELETION_PENDING'").contains("NOT EXISTS")
                .contains("later.occurred_at > e.occurred_at");
    }

    @Test
    @DisplayName("alerts: a connection failure is rethrown, never answered as an empty list")
    void alerts_connectionFailureRethrown() {
        when(jdbc.queryForList(AdminPlanService.ALERT_TENANTS_SQL, "TRIAL", "PAID")).thenReturn(List.of(
                Map.of("id", UUID.randomUUID(), "name", "A", "schema_name", "kv_aaaaa1")));
        var crossTenant = crossTenantAnswering(Map.of());
        when(crossTenant.query(anyList(), any(), any(), any())).thenThrow(new CannotGetJdbcConnectionException("down"));
        service = withCrossTenant(crossTenant, new java.util.ArrayList<>());

        assertThatThrownBy(() -> service.listAlerts()).isInstanceOf(CannotGetJdbcConnectionException.class);
    }

    private static void assertValidationError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException de = (DomainException) e;
                    assertThat(de.getDomainCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(de.getDetails()).containsKey(field);
                });
    }
}
