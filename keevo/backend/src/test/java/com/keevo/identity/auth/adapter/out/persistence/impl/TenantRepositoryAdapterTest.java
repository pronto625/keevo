package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.adapter.out.persistence.entity.TenantJpaEntity;
import com.keevo.identity.auth.adapter.out.persistence.jpa.TenantSpringRepository;
import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TenantRepositoryAdapterTest — Unit tests for {@link TenantRepositoryAdapter}.
 *
 * <p>Mocks {@link TenantSpringRepository} to isolate the mapping logic,
 * including the bidirectional {@code deletionScheduledAt} mapping (Story 14.5).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantRepositoryAdapter")
class TenantRepositoryAdapterTest {

    @Mock
    private TenantSpringRepository springRepository;

    @InjectMocks
    private TenantRepositoryAdapter adapter;

    // ── deletionScheduledAt round-trip ─────────────────────────────────────

    @Test
    @DisplayName("save() round-trips deletionScheduledAt = null correctly (non-regression)")
    void save_roundTripsNullDeletionScheduledAt() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        Tenant tenant = new Tenant(id, "KV-TEST01", "kv_test01", "Test Shop",
                TenantStatus.ACTIVE, PlanType.FREE, created);

        when(springRepository.save(any(TenantJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = adapter.save(tenant);

        assertThat(result.getDeletionScheduledAt()).isNull();
    }

    @Test
    @DisplayName("save() round-trips deletionScheduledAt = non-null correctly")
    void save_roundTripsNonNullDeletionScheduledAt() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant scheduledDeletion = Instant.now().plus(30, ChronoUnit.DAYS);
        Tenant tenant = new Tenant(id, "KV-TEST02", "kv_test02", "Deleting Shop",
                TenantStatus.DELETION_PENDING, PlanType.FREE, created, scheduledDeletion);

        when(springRepository.save(any(TenantJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = adapter.save(tenant);

        assertThat(result.getDeletionScheduledAt()).isEqualTo(scheduledDeletion);
    }

    @Test
    @DisplayName("withStatus() returns new instance with updated status and deletionScheduledAt")
    void withStatus_createsNewInstanceWithUpdatedFields() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        Tenant active = new Tenant(id, "KV-TEST03", "kv_test03", "Shop",
                TenantStatus.ACTIVE, PlanType.PREMIUM_TRIAL, created);

        Instant deletionDate = Instant.now().plus(30, ChronoUnit.DAYS);
        Tenant pending = active.withStatus(TenantStatus.DELETION_PENDING, deletionDate);

        // New instance, old unchanged
        assertThat(active.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(active.getDeletionScheduledAt()).isNull();

        // New instance carries new values
        assertThat(pending.getStatus()).isEqualTo(TenantStatus.DELETION_PENDING);
        assertThat(pending.getDeletionScheduledAt()).isEqualTo(deletionDate);
        assertThat(pending.getId()).isEqualTo(id);
        assertThat(pending.getCode()).isEqualTo("KV-TEST03");
    }

    @Test
    @DisplayName("withStatus(TenantStatus.ACTIVE, null) clears deletionScheduledAt")
    void withStatus_activeNull_clearsDeletionScheduledAt() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.now().minus(1, ChronoUnit.HOURS);
        Tenant pending = new Tenant(id, "KV-TEST04", "kv_test04", "Shop",
                TenantStatus.DELETION_PENDING, PlanType.FREE, created,
                Instant.now().plus(30, ChronoUnit.DAYS));

        Tenant active = pending.withStatus(TenantStatus.ACTIVE, null);

        assertThat(active.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(active.getDeletionScheduledAt()).isNull();
    }
}
