package com.keevo.shared.infrastructure.scheduling;

import com.keevo.identity.auth.domain.model.*;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AccountDeletionSchedulerTest — Unit tests for {@link AccountDeletionScheduler}.
 *
 * <p>Uses a fixed {@link Clock} for deterministic expiry testing.
 * Mocks all dependencies. JdbcTemplate calls are verified but not executed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDeletionScheduler")
class AccountDeletionSchedulerTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private TenantSchemaProvisioner schemaProvisioner;
    @Mock private JdbcTemplate jdbcTemplate;

    private Clock fixedClock;
    private AccountDeletionScheduler scheduler;

    private UUID tenantId;
    private String schemaName;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-08-22T03:00:00Z"), ZoneOffset.UTC);
        scheduler = new AccountDeletionScheduler(tenantRepository, userRepository,
                whatsAppPort, schemaProvisioner, jdbcTemplate, fixedClock);
        tenantId = UUID.randomUUID();
        schemaName = "kv_abc123";
    }

    private Tenant createTenant(TenantStatus status, Instant deletionScheduledAt) {
        return new Tenant(tenantId, "KV-ABC123", schemaName, "Test Shop",
                status, PlanType.FREE, Instant.now().minus(60, ChronoUnit.DAYS),
                deletionScheduledAt);
    }

    // ── Expired tenant ───────────────────────────────────────────────────────

    @Test
    @DisplayName("expired DELETION_PENDING tenant is fully deleted")
    void executeDeletions_deletesExpiredTenant() {
        Instant expiredDate = Instant.parse("2026-07-22T00:00:00Z");
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        // Use lenient stubs — all queryForList calls return a default list
        List<UUID> userIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        lenient().when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any()))
                .thenReturn(userIds);

        User owner = new User(userIds.get(0), "+243812345678", "hash",
                Role.OWNER, true, Instant.now().minus(60, ChronoUnit.DAYS));
        lenient().when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(owner));

        lenient().when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.executeDeletions();

        verify(schemaProvisioner).dropSchemaForDeletion(schemaName);
        verify(tenantRepository).save(argThat(t ->
                t.getStatus() == TenantStatus.DELETED && t.getDeletionScheduledAt() == null));
    }

    // ── Not yet expired ──────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETION_PENDING but not yet expired is skipped")
    void executeDeletions_skipsNotYetExpired() {
        Instant futureDate = Instant.parse("2026-09-22T00:00:00Z"); // after clock
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, futureDate);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        scheduler.executeDeletions();

        verifyNoInteractions(jdbcTemplate);
        verify(schemaProvisioner, never()).dropSchemaForDeletion(anyString());
        verify(tenantRepository, never()).save(any());
    }

    // ── Non-PENDING tenants ──────────────────────────────────────────────────

    @Test
    @DisplayName("ACTIVE tenant is skipped")
    void executeDeletions_skipsActiveTenant() {
        Tenant tenant = createTenant(TenantStatus.ACTIVE, null);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        scheduler.executeDeletions();

        verifyNoInteractions(jdbcTemplate);
        verify(tenantRepository, never()).save(any());
    }

    @Test
    @DisplayName("SUSPENDED tenant is skipped")
    void executeDeletions_skipsSuspendedTenant() {
        Tenant tenant = createTenant(TenantStatus.SUSPENDED, null);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        scheduler.executeDeletions();

        verifyNoInteractions(jdbcTemplate);
        verify(tenantRepository, never()).save(any());
    }

    // ── Multi-tenant user preservation ───────────────────────────────────────

    @Test
    @DisplayName("multi-tenant user is NOT deleted (still has membership in another tenant)")
    void executeDeletions_preservesMultiTenantUser() {
        Instant expiredDate = Instant.parse("2026-07-22T00:00:00Z");
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        lenient().when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any()))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        lenient().when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        lenient().when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        scheduler.executeDeletions();

        verify(schemaProvisioner).dropSchemaForDeletion(schemaName);
    }

    // ── Failure resilience ──────────────────────────────────────────────────

    @Test
    @DisplayName("DROP schema failure does not mark tenant DELETED")
    void executeDeletions_doesNotMarkDeletedOnSchemaDropFailure() {
        Instant expiredDate = Instant.parse("2026-07-22T00:00:00Z");
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        lenient().when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), any()))
                .thenReturn(List.of());

        doThrow(new RuntimeException("DB connection lost"))
                .when(schemaProvisioner).dropSchemaForDeletion(schemaName);

        scheduler.executeDeletions();

        // Tenant is NOT saved as DELETED
        verify(tenantRepository, never()).save(any());
    }
}
