package com.keevo.shared.infrastructure.scheduling;

import com.keevo.identity.auth.domain.model.*;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.domain.port.out.TenantFilesPurger;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.keevo.shared.infrastructure.scheduling.AccountDeletionScheduler.LOCK_TENANT_SQL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AccountDeletionSchedulerTest — Unit tests for {@link AccountDeletionScheduler}.
 *
 * <p>Uses a fixed {@link Clock} for deterministic expiry testing. The per-tenant
 * {@code TransactionTemplate} runs over a mocked {@link PlatformTransactionManager}, so commit /
 * rollback are observable. JdbcTemplate calls are verified but not executed (the real-database
 * behaviour is covered by {@code AccountDeletionSchedulerPostgresIntegrationTest}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccountDeletionScheduler")
class AccountDeletionSchedulerTest {

    private static final String MEMBERSHIP_DELETE = "DELETE FROM public.user_tenant_memberships";

    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private WhatsAppPort whatsAppPort;
    @Mock private TenantSchemaProvisioner schemaProvisioner;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private PlatformTransactionManager transactionManager;

    private AccountDeletionScheduler scheduler;

    private UUID tenantId;
    private String schemaName;
    private final Instant expiredDate = Instant.parse("2026-07-22T00:00:00Z");

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-22T03:00:00Z"), ZoneOffset.UTC);
        scheduler = new AccountDeletionScheduler(tenantRepository, userRepository,
                whatsAppPort, schemaProvisioner, jdbcTemplate, transactionManager, fixedClock);
        tenantId = UUID.randomUUID();
        schemaName = "kv_abc123";
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Tenant tenant(UUID id, String schema, TenantStatus status, Instant deletionScheduledAt) {
        return new Tenant(id, "KV-" + schema.substring(3).toUpperCase(), schema, "Test Shop",
                status, PlanType.FREE, Instant.now().minus(60, ChronoUnit.DAYS), deletionScheduledAt);
    }

    private Tenant createTenant(TenantStatus status, Instant deletionScheduledAt) {
        return tenant(tenantId, schemaName, status, deletionScheduledAt);
    }

    private void stubLock(UUID id, TenantStatus status, Instant deletionScheduledAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", status.name());
        row.put("deletion_scheduled_at", deletionScheduledAt == null ? null : Timestamp.from(deletionScheduledAt));
        when(jdbcTemplate.queryForList(eq(LOCK_TENANT_SQL), eq(id))).thenReturn(List.of(row));
    }

    /** Lock + re-fetch stubs for an expired DELETION_PENDING tenant, plus one OWNER. */
    private void stubPending(Tenant t, String ownerPhone) {
        stubLock(t.getId(), TenantStatus.DELETION_PENDING, t.getDeletionScheduledAt());
        when(tenantRepository.findById(t.getId())).thenReturn(Optional.of(t));
        UUID ownerId = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(t.getId())))
                .thenReturn(List.of(ownerId));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(new User(ownerId, ownerPhone, "hash",
                Role.OWNER, true, Instant.now().minus(60, ChronoUnit.DAYS))));
    }

    // ── Expired tenant ───────────────────────────────────────────────────────

    @Test
    @DisplayName("expired tenant: lock, DROP, deletes, DELETED save, commit, then WhatsApp")
    void executeDeletions_deletesExpiredTenant() {
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findDeletionsDueBy(any())).thenReturn(List.of(tenant));
        stubPending(tenant, "+243812345678");

        scheduler.executeDeletions();

        InOrder order = inOrder(jdbcTemplate, schemaProvisioner, tenantRepository, transactionManager, whatsAppPort);
        order.verify(jdbcTemplate).queryForList(LOCK_TENANT_SQL, tenantId);
        order.verify(schemaProvisioner).dropSchemaForDeletion(schemaName);
        order.verify(jdbcTemplate).update(startsWith(MEMBERSHIP_DELETE), any(Object.class));
        order.verify(tenantRepository).save(argThat(t ->
                t.getStatus() == TenantStatus.DELETED && t.getDeletionScheduledAt() == null));
        order.verify(transactionManager).commit(any());
        order.verify(whatsAppPort).sendReport(eq("+243812345678"), anyString());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    @DisplayName("final WhatsApp failure after commit: deletion still succeeds, the other owners are still notified")
    void deleteNow_whatsAppFailureAfterCommit_isNonBlocking() {
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        stubLock(tenantId, TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class), eq(tenantId)))
                .thenReturn(List.of(ownerA, ownerB));
        when(userRepository.findById(ownerA)).thenReturn(Optional.of(new User(ownerA, "+243811111111", "hash",
                Role.OWNER, true, Instant.now().minus(60, ChronoUnit.DAYS))));
        when(userRepository.findById(ownerB)).thenReturn(Optional.of(new User(ownerB, "+243822222222", "hash",
                Role.OWNER, true, Instant.now().minus(60, ChronoUnit.DAYS))));
        doThrow(new RuntimeException("provider down"))
                .when(whatsAppPort).sendReport(eq("+243811111111"), anyString());

        assertThat(scheduler.deleteNow(tenantId)).isTrue();

        verify(tenantRepository).save(argThat(t -> t.getStatus() == TenantStatus.DELETED));
        verify(transactionManager, times(1)).commit(any());
        verify(transactionManager, never()).rollback(any());
        verify(whatsAppPort).sendReport(eq("+243811111111"), anyString());
        verify(whatsAppPort).sendReport(eq("+243822222222"), anyString());
    }

    // ── Due-tenant query (v1s-15-9) ───────────────────────────────────────────

    @Test
    @DisplayName("queries only the tenants due by the scheduler clock; nothing due → no work")
    void executeDeletions_queriesDueTenantsOnly() {
        when(tenantRepository.findDeletionsDueBy(any())).thenReturn(List.of());

        scheduler.executeDeletions();

        verify(tenantRepository).findDeletionsDueBy(Instant.parse("2026-08-22T03:00:00Z"));
        verify(tenantRepository, never()).findAll();
        verifyNoInteractions(jdbcTemplate, transactionManager);
        verify(tenantRepository, never()).save(any());
    }

    // ── deleteNow (admin force-delete, v1s-15-9) ──────────────────────────────

    @Test
    @DisplayName("deleteNow: DELETION_PENDING with a future date is still deleted (no expiry requirement)")
    void deleteNow_deletesPendingTenantRegardlessOfDate() {
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, Instant.parse("2026-09-22T00:00:00Z"));
        stubPending(tenant, "+243812345678");

        assertThat(scheduler.deleteNow(tenantId)).isTrue();

        InOrder order = inOrder(schemaProvisioner, tenantRepository, transactionManager, whatsAppPort);
        order.verify(schemaProvisioner).dropSchemaForDeletion(schemaName);
        order.verify(tenantRepository).save(argThat(t -> t.getStatus() == TenantStatus.DELETED));
        order.verify(transactionManager).commit(any());
        order.verify(whatsAppPort).sendReport(eq("+243812345678"), anyString());
    }

    @Test
    @DisplayName("deleteNow: tenant no longer DELETION_PENDING under the lock → false, nothing dropped")
    void deleteNow_skipsWhenNoLongerPending() {
        stubLock(tenantId, TenantStatus.ACTIVE, null);

        assertThat(scheduler.deleteNow(tenantId)).isFalse();

        verify(schemaProvisioner, never()).dropSchemaForDeletion(anyString());
        verify(tenantRepository, never()).save(any());
        verifyNoInteractions(whatsAppPort);
    }

    @Test
    @DisplayName("deleteNow: a DROP failure propagates to the caller and rolls back")
    void deleteNow_propagatesFailure() {
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        stubPending(tenant, "+243812345678");
        doThrow(new RuntimeException("drop failed")).when(schemaProvisioner).dropSchemaForDeletion(schemaName);

        assertThatThrownBy(() -> scheduler.deleteNow(tenantId)).hasMessageContaining("drop failed");

        verify(transactionManager).rollback(any());
        verify(tenantRepository, never()).save(any());
        verifyNoInteractions(whatsAppPort);
    }

    // ── Multi-tenant user preservation ───────────────────────────────────────

    @Test
    @DisplayName("orphan-only user delete: the DELETE … NOT EXISTS guard is issued, the device-token delete is not when none is orphaned")
    void executeDeletions_preservesMultiTenantUser() {
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findDeletionsDueBy(any())).thenReturn(List.of(tenant));
        stubPending(tenant, "+243812345678");
        // The DELETE … RETURNING deletes nobody: the member still belongs to another tenant
        when(jdbcTemplate.queryForList(startsWith("DELETE FROM public.users"), eq(UUID.class), any(Object.class)))
                .thenReturn(List.of());

        scheduler.executeDeletions();

        verify(jdbcTemplate).queryForList(contains("NOT EXISTS"), eq(UUID.class), any(Object.class));
        verify(jdbcTemplate, never()).update(startsWith("DELETE FROM public.device_tokens"), any(Object.class));
        verify(tenantRepository).save(argThat(t -> t.getStatus() == TenantStatus.DELETED));
    }

    // ── Failure resilience ──────────────────────────────────────────────────

    @Test
    @DisplayName("DROP failure: rollback, no delete, no save, no WhatsApp (retried next day)")
    void executeDeletions_doesNotMarkDeletedOnSchemaDropFailure() {
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findDeletionsDueBy(any())).thenReturn(List.of(tenant));
        stubPending(tenant, "+243812345678");
        doThrow(new RuntimeException("DB connection lost"))
                .when(schemaProvisioner).dropSchemaForDeletion(schemaName);

        scheduler.executeDeletions();

        verify(schemaProvisioner).dropSchemaForDeletion(schemaName);
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
        verify(tenantRepository, never()).save(any());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(whatsAppPort);
    }

    @Test
    @DisplayName("DROP OK but memberships delete fails: rollback, no save/WhatsApp; the next run completes it once")
    void executeDeletions_retriesAfterPostDropFailure() {
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findDeletionsDueBy(any())).thenReturn(List.of(tenant));
        stubPending(tenant, "+243812345678");
        when(jdbcTemplate.update(startsWith(MEMBERSHIP_DELETE), any(Object.class)))
                .thenThrow(new RuntimeException("deadlock detected"))
                .thenReturn(1);

        scheduler.executeDeletions();

        verify(schemaProvisioner).dropSchemaForDeletion(schemaName);
        verify(tenantRepository, never()).save(any());
        verify(transactionManager).rollback(any());
        verifyNoInteractions(whatsAppPort);

        // Next daily run: still DELETION_PENDING (rolled back) → completes
        scheduler.executeDeletions();

        verify(schemaProvisioner, times(2)).dropSchemaForDeletion(schemaName); // DROP IF EXISTS: no-op
        verify(tenantRepository, times(1)).save(argThat(t -> t.getStatus() == TenantStatus.DELETED));
        verify(transactionManager, times(1)).commit(any());
        verify(whatsAppPort, times(1)).sendReport(eq("+243812345678"), anyString());
    }

    @Test
    @DisplayName("tenant A fails, tenant B is still deleted in the same run")
    void executeDeletions_oneTenantFailureDoesNotAffectAnother() {
        Tenant a = tenant(UUID.randomUUID(), "kv_aaaaaa", TenantStatus.DELETION_PENDING, expiredDate);
        Tenant b = tenant(UUID.randomUUID(), "kv_bbbbbb", TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findDeletionsDueBy(any())).thenReturn(List.of(a, b));
        stubPending(a, "+243800000001");
        stubPending(b, "+243800000002");
        doThrow(new RuntimeException("boom")).when(schemaProvisioner).dropSchemaForDeletion("kv_aaaaaa");

        scheduler.executeDeletions();

        verify(schemaProvisioner).dropSchemaForDeletion("kv_bbbbbb");
        verify(tenantRepository).save(argThat(t -> t.getId().equals(b.getId()) && t.getStatus() == TenantStatus.DELETED));
        verify(tenantRepository, never()).save(argThat(t -> t.getId().equals(a.getId())));
        verify(transactionManager, times(1)).rollback(any());
        verify(transactionManager, times(1)).commit(any());
        verify(whatsAppPort).sendReport(eq("+243800000002"), anyString());
        verify(whatsAppPort, never()).sendReport(eq("+243800000001"), anyString());
    }

    @Test
    @DisplayName("tenant cancelled before the row lock is skipped")
    void executeDeletions_skipsTenantCancelledDuringRun() {
        Tenant pending = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findDeletionsDueBy(any())).thenReturn(List.of(pending));
        stubLock(tenantId, TenantStatus.ACTIVE, null);

        scheduler.executeDeletions();

        verify(schemaProvisioner, never()).dropSchemaForDeletion(anyString());
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
        verify(tenantRepository, never()).save(any());
        verifyNoInteractions(whatsAppPort);
    }

    @Test
    @DisplayName("tenant cancelled then re-requested (future date) before the row lock is skipped")
    void executeDeletions_skipsTenantReRequestedWithFutureDate() {
        Tenant pending = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        when(tenantRepository.findDeletionsDueBy(any())).thenReturn(List.of(pending));
        stubLock(tenantId, TenantStatus.DELETION_PENDING, Instant.parse("2026-09-21T00:00:00Z"));

        scheduler.executeDeletions();

        verify(schemaProvisioner, never()).dropSchemaForDeletion(anyString());
        verify(jdbcTemplate, never()).update(anyString(), any(Object.class));
        verify(tenantRepository, never()).save(any());
        verifyNoInteractions(whatsAppPort);
    }

    // ── Stored files (epic 8: « uploaded photos ») ───────────────────────────

    private AccountDeletionScheduler schedulerWithPurgers(TenantFilesPurger... purgers) {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-22T03:00:00Z"), ZoneOffset.UTC);
        return new AccountDeletionScheduler(tenantRepository, userRepository, whatsAppPort,
                schemaProvisioner, jdbcTemplate, transactionManager, fixedClock, List.of(purgers));
    }

    @Test
    @DisplayName("deleted tenant: its stored files (product photos) are purged after commit")
    void deleteNow_purgesTenantFilesAfterCommit() {
        TenantFilesPurger purger = mock(TenantFilesPurger.class);
        AccountDeletionScheduler withPurger = schedulerWithPurgers(purger);
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        stubPending(tenant, "+243812345678");

        assertThat(withPurger.deleteNow(tenantId)).isTrue();

        InOrder order = inOrder(schemaProvisioner, transactionManager, purger);
        order.verify(schemaProvisioner).dropSchemaForDeletion(schemaName);
        order.verify(transactionManager).commit(any());
        order.verify(purger).purgeTenant(schemaName);
    }

    @Test
    @DisplayName("a failing file purge never fails the deletion; the other purgers still run")
    void deleteNow_filePurgeFailureIsNonBlocking() {
        TenantFilesPurger failing = mock(TenantFilesPurger.class);
        TenantFilesPurger other = mock(TenantFilesPurger.class);
        doThrow(new RuntimeException("disk")).when(failing).purgeTenant(anyString());
        AccountDeletionScheduler withPurgers = schedulerWithPurgers(failing, other);
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        stubPending(tenant, "+243812345678");

        assertThat(withPurgers.deleteNow(tenantId)).isTrue();

        verify(other).purgeTenant(schemaName);
        verify(whatsAppPort).sendReport(eq("+243812345678"), anyString());
    }

    @Test
    @DisplayName("rolled-back deletion (DROP failed): files are kept")
    void deleteNow_dropFailure_keepsFiles() {
        TenantFilesPurger purger = mock(TenantFilesPurger.class);
        AccountDeletionScheduler withPurger = schedulerWithPurgers(purger);
        Tenant tenant = createTenant(TenantStatus.DELETION_PENDING, expiredDate);
        stubPending(tenant, "+243812345678");
        doThrow(new RuntimeException("drop failed")).when(schemaProvisioner).dropSchemaForDeletion(schemaName);

        assertThatThrownBy(() -> withPurger.deleteNow(tenantId)).isInstanceOf(RuntimeException.class);

        verify(purger, never()).purgeTenant(anyString());
    }

    @Test
    @DisplayName("skipped tenant (no longer pending): files are kept")
    void deleteNow_skipped_keepsFiles() {
        TenantFilesPurger purger = mock(TenantFilesPurger.class);
        AccountDeletionScheduler withPurger = schedulerWithPurgers(purger);
        stubLock(tenantId, TenantStatus.ACTIVE, null);

        assertThat(withPurger.deleteNow(tenantId)).isFalse();

        verify(purger, never()).purgeTenant(anyString());
    }
}
