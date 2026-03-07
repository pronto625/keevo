package com.keevo.subscription.plan.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.model.SubscriptionStatus;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanCommand;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ActivatePlanServiceTest — Unit tests for {@link ActivatePlanService}.
 *
 * <p>Uses the package-private test constructor that accepts a {@link TransactionTemplate} mock.
 * The mock is configured to directly execute the callback (no real transaction manager needed),
 * allowing full unit-test coverage without Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivatePlanService")
class ActivatePlanServiceTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock TenantRepository tenantRepository;
    @Mock TransactionTemplate requiresNewTemplate;

    ActivatePlanService service;

    private static final UUID TENANT_UUID = UUID.randomUUID();
    private static final String SCHEMA_NAME = "kv_abc123";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Stub tenant resolution: schemaName → Tenant
        // Flutter sends LoginResponse.tenantId (schemaName), NOT the UUID.
        // lenient() because execute_throwsTenantNotFound_whenSchemaNameUnknown uses "kv_unknown"
        // and never exercises this stub — strict-stubbing would flag it as unnecessary.
        Tenant tenant = new Tenant(TENANT_UUID, "KV-ABC123", SCHEMA_NAME,
                TenantStatus.ACTIVE, PlanType.FREE, Instant.now());
        lenient().when(tenantRepository.findBySchemaName(SCHEMA_NAME)).thenReturn(Optional.of(tenant));

        // Configure TransactionTemplate mock to directly invoke the callback in-thread.
        // Bypasses real transaction management for unit tests.
        // lenient() because tests that throw before reaching the template (e.g. tenant not found)
        // would otherwise trigger UnnecessaryStubbing.
        lenient().doAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            cb.doInTransaction(new SimpleTransactionStatus());
            return null;
        }).when(requiresNewTemplate).execute(any(TransactionCallback.class));

        // Use the package-private test constructor
        service = new ActivatePlanService(subscriptionRepository, tenantRepository, requiresNewTemplate);
    }

    @Test
    @DisplayName("upgrades FREE plan to PREMIUM and sets expiresAt")
    void execute_upgradesFreeToPremium() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSub()));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Instant expiresAt = Instant.now().plus(365, ChronoUnit.DAYS);
        // Use SCHEMA_NAME — this is what Flutter sends as tenantId path variable
        ActivatePlanCommand command = new ActivatePlanCommand(
                "admin-1", SCHEMA_NAME, PlanType.PREMIUM, expiresAt);

        assertThatCode(() -> service.execute(command)).doesNotThrowAnyException();

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());

        Subscription saved = captor.getValue();
        assertThat(saved.getPlanType()).isEqualTo(PlanType.PREMIUM);
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(saved.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(saved.getMaxStores()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("restores suspended account to ACTIVE when activated")
    void execute_unblocksSuspendedTenant() {
        Subscription suspended = new Subscription(
                UUID.randomUUID(), PlanType.FREE, SubscriptionStatus.SUSPENDED,
                1, 500, 3, Instant.now(), null);
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(suspended));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new ActivatePlanCommand("admin", SCHEMA_NAME, PlanType.PREMIUM,
                Instant.now().plus(365, ChronoUnit.DAYS)));

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("throws TENANT_NOT_FOUND when schemaName not found")
    void execute_throwsTenantNotFound_whenSchemaNameUnknown() {
        when(tenantRepository.findBySchemaName("kv_unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(
                new ActivatePlanCommand("admin", "kv_unknown", PlanType.PREMIUM, null)))
                .isInstanceOf(DomainException.class);

        // findActivePlan should never be called if tenant lookup fails
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("throws NOT_FOUND when no subscription exists in target schema")
    void execute_throwsNotFound_whenNoSubscription() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(
                new ActivatePlanCommand("admin", SCHEMA_NAME, PlanType.PREMIUM, null)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("resolves targetTenantId via findBySchemaName (not UUID) — H1 fix revised")
    void execute_findsTenantstBySchemaName() {
        // Flutter sends LoginResponse.tenantId = schemaName (kv_xxxxxx), not UUID.
        // ActivatePlanService must use findBySchemaName(), never UUID.fromString().
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSub()));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new ActivatePlanCommand(
                "admin", SCHEMA_NAME, PlanType.PREMIUM,
                Instant.now().plus(365, ChronoUnit.DAYS)));

        // Verify schemaName lookup (not UUID.fromString)
        verify(tenantRepository).findBySchemaName(SCHEMA_NAME);
        verify(tenantRepository, never()).findById(any());
    }

    @Test
    @DisplayName("uses REQUIRES_NEW TransactionTemplate for JPA operations — OEMIV isolation")
    void execute_usesRequiresNewTemplate() {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(freeSub()));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.execute(new ActivatePlanCommand(
                "admin", SCHEMA_NAME, PlanType.PREMIUM,
                Instant.now().plus(365, ChronoUnit.DAYS)));

        // Verify that JPA work runs inside the REQUIRES_NEW template (OEMIV isolation fix)
        verify(requiresNewTemplate).execute(any(TransactionCallback.class));
    }

    private Subscription freeSub() {
        return new Subscription(UUID.randomUUID(), PlanType.FREE, SubscriptionStatus.ACTIVE,
                1, 500, 3, Instant.now(), null);
    }
}
