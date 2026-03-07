package com.keevo.subscription.plan.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.subscription.plan.adapter.out.persistence.entity.SubscriptionJpaEntity;
import com.keevo.subscription.plan.adapter.out.persistence.jpa.SubscriptionSpringRepository;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.model.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SubscriptionRepositoryAdapterTest — Unit tests for {@link SubscriptionRepositoryAdapter}.
 *
 * <p>Mocks {@link SubscriptionSpringRepository} to isolate the mapping logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionRepositoryAdapter")
class SubscriptionRepositoryAdapterTest {

    @Mock
    private SubscriptionSpringRepository springRepository;

    @InjectMocks
    private SubscriptionRepositoryAdapter adapter;

    // ── findActivePlan ────────────────────────────────────────────────────────

    @Test
    @DisplayName("findActivePlan() maps PREMIUM_TRIAL entity to domain model correctly")
    void findActivePlan_returnsSubscription_whenEntityExists() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant expires = Instant.now().plus(180, ChronoUnit.DAYS);

        SubscriptionJpaEntity entity = new SubscriptionJpaEntity(
                id, "PREMIUM_TRIAL",
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                "ACTIVE", created, expires);

        when(springRepository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(entity));

        Optional<Subscription> result = adapter.findActivePlan();

        assertThat(result).isPresent();
        Subscription sub = result.get();
        assertThat(sub.getId()).isEqualTo(id);
        assertThat(sub.getPlanType()).isEqualTo(PlanType.PREMIUM_TRIAL);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.isActive()).isTrue();
        assertThat(sub.isPremiumTrial()).isTrue();
        assertThat(sub.getExpiresAt()).isEqualTo(expires);
    }

    @Test
    @DisplayName("findActivePlan() returns empty Optional when no row exists")
    void findActivePlan_returnsEmpty_whenNoRow() {
        when(springRepository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        Optional<Subscription> result = adapter.findActivePlan();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findActivePlan() maps FREE plan without expiresAt")
    void findActivePlan_mapsFreeSubscription() {
        UUID id = UUID.randomUUID();
        SubscriptionJpaEntity entity = new SubscriptionJpaEntity(
                id, "FREE", 1, 500, 3, "ACTIVE", Instant.now(), null);

        when(springRepository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(entity));

        Optional<Subscription> result = adapter.findActivePlan();

        assertThat(result).isPresent();
        assertThat(result.get().getPlanType()).isEqualTo(PlanType.FREE);
        assertThat(result.get().getExpiresAt()).isNull();
        assertThat(result.get().isFree()).isTrue();
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save() persists SUSPENDED status and returns domain model")
    void save_persistsSuspendedStatus() {
        UUID id = UUID.randomUUID();
        Subscription domain = new Subscription(id, PlanType.FREE, SubscriptionStatus.SUSPENDED,
                1, 500, 3, Instant.now(), null);

        SubscriptionJpaEntity savedEntity = new SubscriptionJpaEntity(
                id, "FREE", 1, 500, 3, "SUSPENDED", Instant.now(), null);
        when(springRepository.save(any())).thenReturn(savedEntity);

        Subscription saved = adapter.save(domain);

        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.SUSPENDED);
        assertThat(saved.isSuspended()).isTrue();
        verify(springRepository).save(any(SubscriptionJpaEntity.class));
    }
}
