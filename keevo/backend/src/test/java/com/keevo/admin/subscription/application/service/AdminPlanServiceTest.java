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
import com.keevo.subscription.api.PlanChangeCause;
import com.keevo.subscription.plan.domain.model.event.PlanActivatedEvent;
import com.keevo.subscription.plan.domain.model.event.PlanDowngradedEvent;
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
    @DisplayName("activate from FREE: payment today→expiresAt, PREMIUM until end of day WAT, event published")
    void activate_fromFree() {
        tenant("ACTIVE");
        when(changePlan.currentPlan(SCHEMA)).thenReturn(Optional.of(new CurrentPlan(PlanType.FREE, null)));
        when(changePlan.changePlan(any())).thenReturn(new CurrentPlan(PlanType.FREE, null));
        LocalDate expires = TODAY.plusDays(30);

        CurrentPlan result = service.activatePremium(activation(expires, 10_000, PaymentMethod.MOBILE_MONEY));

        Instant endOfDay = Instant.parse("2026-11-12T22:59:59Z"); // 23:59:59 WAT
        assertThat(result).isEqualTo(new CurrentPlan(PlanType.PREMIUM, endOfDay));

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
        assertThat(change.getValue().newPlan()).isEqualTo(PlanType.PREMIUM);
        assertThat(change.getValue().expiresAt()).isEqualTo(endOfDay);
        assertThat(change.getValue().cause()).isEqualTo(PlanChangeCause.ADMIN_ACTIVATION);
        assertThat(change.getValue().schemaName()).isEqualTo(SCHEMA);

        verify(publisher).publishEvent(new PlanActivatedEvent(ACTOR, TENANT, SCHEMA, PlanType.FREE,
                endOfDay, 10_000, NOW));
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
        when(jdbc.queryForList(AdminPlanService.ALERT_TENANTS_SQL)).thenReturn(List.of(
                Map.of("id", a, "name", "Boutique A", "schema_name", "kv_aaaaa1"),
                Map.of("id", b, "name", "Boutique B", "schema_name", "kv_bbbbb2"),
                Map.of("id", UUID.randomUUID(), "name", "Bad", "schema_name", "public")));
        Instant aExpiry = AdminPlanService.endOfDayWat(TODAY.plusDays(3));
        when(jdbc.queryForList(contains("\"kv_aaaaa1\""), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of(Map.of("plan_type", "PREMIUM", "expires_at", Timestamp.from(aExpiry))));
        when(jdbc.queryForList(contains("\"kv_bbbbb2\""), any(Timestamp.class), any(Timestamp.class)))
                .thenThrow(new BadSqlGrammarException("q", "sql", new SQLException("no table")));
        PlanAlert expired = new PlanAlert(UUID.randomUUID(), "Boutique C", PlanAlert.Kind.EXPIRED, "PREMIUM",
                null, null, NOW.minusSeconds(3600));
        when(jdbc.query(eq(AdminPlanService.EXPIRED_SQL), any(RowMapper.class), any(Timestamp.class)))
                .thenReturn(List.of(expired));

        List<PlanAlert> alerts = service.listAlerts();

        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(0)).isEqualTo(new PlanAlert(a, "Boutique A", PlanAlert.Kind.EXPIRING, "PREMIUM",
                3, aExpiry, null));
        assertThat(alerts.get(1)).isEqualTo(expired);
        // lower bound bound from the app clock, not the DB NOW()
        verify(jdbc).queryForList(contains("\"kv_aaaaa1\""), eq(Timestamp.from(NOW)), any(Timestamp.class));
    }

    @Test
    @DisplayName("alerts SQL: non-FREE live tenants only; EXPIRED excludes deletion states and later plan changes")
    void alerts_sqlShape() {
        assertThat(AdminPlanService.ALERT_TENANTS_SQL).contains("plan_type <> 'FREE'").contains("'DELETION_PENDING'");
        assertThat(AdminPlanService.EXPIRING_SQL).doesNotContain("NOW()");
        assertThat(AdminPlanService.EXPIRED_SQL).contains("'DELETION_PENDING'").contains("NOT EXISTS")
                .contains("later.occurred_at > e.occurred_at");
    }

    @Test
    @DisplayName("alerts: a connection failure is rethrown, never answered as an empty list")
    void alerts_connectionFailureRethrown() {
        when(jdbc.queryForList(AdminPlanService.ALERT_TENANTS_SQL)).thenReturn(List.of(
                Map.of("id", UUID.randomUUID(), "name", "A", "schema_name", "kv_aaaaa1")));
        when(jdbc.queryForList(anyString(), any(Timestamp.class), any(Timestamp.class)))
                .thenThrow(new CannotGetJdbcConnectionException("down"));

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
