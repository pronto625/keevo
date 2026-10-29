package com.keevo.identity.auth.application.service;

import com.keevo.admin.tenant.application.service.AdminTenantService;
import com.keevo.identity.auth.adapter.in.api.IdentityAuthApiTestSupport;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.api.WhatsAppPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story v1s-15-9 — the three account-deletion races routed from v1s-15-11, proven on a real
 * PostgreSQL 16 with the production {@link AccountDeletionService} and {@link AdminTenantService}
 * beans against a hand-built {@link TenantDeletionService} (the bean sits behind the ShedLock
 * proxy and the no-op WhatsApp adapter).
 *
 * <p>Every test provisions its own tenant(s) and asserts only on those rows: the database is
 * shared with the rest of the suite.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Account deletion concurrency on PostgreSQL (Story v1s-15-9)")
class AccountDeletionConcurrencyPostgresIntegrationTest extends FullContextIntegrationTest {

    private static final int TIMEOUT_SECONDS = 30;
    private static final String ADMIN_TENANT_ID = "00000000-0000-0000-0000-000000000000";

    @Autowired TenantFactory tenantFactory;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired TenantSchemaProvisioner schemaProvisioner;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountDeletionService accountDeletionService;
    @Autowired AdminTenantService adminTenantService;

    private final WhatsAppPort whatsAppPort = mock(WhatsAppPort.class);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── Seeding (mirrors TenantDeletionServicePostgresIntegrationTest) ─────────────────────────

    private record Seed(UUID tenantId, String schema, UUID ownerId, String phone) {}

    /** Provisions a tenant (schema included) with an OWNER, its membership, refresh and device token. */
    private Seed seedTenant(String status, Instant deletionScheduledAt) {
        Tenant tenant = tx.execute(s -> tenantFactory.create());
        UUID ownerId = UUID.randomUUID();
        String phone = "+2439" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                + "password_hash, phone_number, role) VALUES (?, now(), now(), true, 0, 'hash', ?, 'OWNER')",
                ownerId, phone);
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                + "tenant_id, user_id) VALUES (?, now(), now(), true, 'OWNER', ?, ?)",
                UUID.randomUUID(), tenant.getId(), ownerId);
        jdbc.update("INSERT INTO public.refresh_tokens (id, created_at, updated_at, expires_at, revoked, tenant_id, "
                + "token_hash, user_id) VALUES (?, now(), now(), now() + interval '30 days', false, ?, ?, ?)",
                UUID.randomUUID(), tenant.getSchemaName(), "hash-" + UUID.randomUUID(), ownerId);
        jdbc.update("INSERT INTO public.device_tokens (id, created_at, updated_at, platform, role, token, user_id) "
                + "VALUES (?, now(), now(), 'ANDROID', 'OWNER', ?, ?)",
                UUID.randomUUID(), "fcm-" + UUID.randomUUID(), ownerId);
        jdbc.update("UPDATE public.tenants SET status = ?, deletion_scheduled_at = ? WHERE id = ?",
                status, deletionScheduledAt == null ? null : Timestamp.from(deletionScheduledAt), tenant.getId());
        return new Seed(tenant.getId(), tenant.getSchemaName(), ownerId, phone);
    }

    private Seed seedPending(Duration fromNow) {
        return seedTenant("DELETION_PENDING", Instant.now().plus(fromNow));
    }

    private TenantDeletionService scheduler(TenantSchemaProvisioner provisioner, Clock clock) {
        return new TenantDeletionService(tenantRepository, userRepository, whatsAppPort,
                provisioner, jdbc, transactionManager, clock);
    }

    private boolean schemaExists(String schema) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)", Boolean.class, schema));
    }

    private String status(UUID tenantId) {
        return jdbc.queryForObject("SELECT status FROM public.tenants WHERE id = ?", String.class, tenantId);
    }

    private Timestamp scheduledAt(UUID tenantId) {
        return jdbc.queryForObject("SELECT deletion_scheduled_at FROM public.tenants WHERE id = ?",
                Timestamp.class, tenantId);
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private static String domainCode(Throwable t) {
        assertThat(t).isInstanceOf(DomainException.class);
        return ((DomainException) t).getDomainCode();
    }

    private void assertFullyDeleted(Seed seed) {
        assertThat(schemaExists(seed.schema())).isFalse();
        assertThat(status(seed.tenantId())).isEqualTo("DELETED");
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", seed.ownerId())).isZero();
        assertThat(count("SELECT count(*) FROM public.refresh_tokens WHERE tenant_id = ?", seed.schema())).isZero();
        assertThat(count("SELECT count(*) FROM public.device_tokens WHERE user_id = ?", seed.ownerId())).isZero();
    }

    // ── Row 1: normal owner cancel ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner cancel inside the grace period with the schema present → ACTIVE, date cleared")
    void ownerCancel_normal_reactivates() {
        Seed seed = seedPending(Duration.ofDays(20));

        accountDeletionService.cancelDeletion(seed.ownerId(), seed.schema());

        assertThat(status(seed.tenantId())).isEqualTo("ACTIVE");
        assertThat(scheduledAt(seed.tenantId())).isNull();
        assertThat(schemaExists(seed.schema())).isTrue();
    }

    // ── Row 2: cancel races the scheduler ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel issued while the scheduler holds the row lock waits, then matches 0 rows → stays DELETED")
    void ownerCancel_racingScheduler_waitsThenRefused() throws Exception {
        // Future date: the cancel's UPDATE sees a qualifying row and must queue on the lock.
        Seed seed = seedPending(Duration.ofDays(20));
        CountDownLatch inDrop = new CountDownLatch(1);
        CountDownLatch releaseDrop = new CountDownLatch(1);
        // Delegating spy: blocks inside dropSchemaForDeletion (row lock held), then runs the real DROP.
        TenantSchemaProvisioner blockingProvisioner = mock(TenantSchemaProvisioner.class);
        doAnswer(inv -> {
            inDrop.countDown();
            assertThat(releaseDrop.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            schemaProvisioner.dropSchemaForDeletion(inv.getArgument(0));
            return null;
        }).when(blockingProvisioner).dropSchemaForDeletion(anyString());
        // The scheduler clock is 31 days ahead, so the daily-run path (requireExpired) selects it.
        TenantDeletionService scheduler = scheduler(blockingProvisioner,
                Clock.offset(Clock.systemUTC(), Duration.ofDays(31)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> deletion = pool.submit(() -> scheduler.runTenantDeletion(seed.tenantId(), true));
            assertThat(inDrop.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("scheduler reached the DROP").isTrue();

            // The cancel already read DELETION_PENDING (unlocked); its conditional UPDATE must wait.
            Future<?> cancel = pool.submit(() -> {
                try {
                    accountDeletionService.cancelDeletion(seed.ownerId(), seed.schema());
                } finally {
                    TenantContext.clear();
                }
            });

            boolean waiting = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() "
                                + "AND wait_event_type = 'Lock' AND query ILIKE '%UPDATE public.tenants%' "
                                + "AND query ILIKE '%to_regnamespace%' AND pid <> pg_backend_pid())",
                        Boolean.class));
                if (!waiting) Thread.sleep(50);
            }
            releaseDrop.countDown();

            assertThat(waiting).as("cancel UPDATE waited on the tenant row lock").isTrue();
            assertThat(deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cancel.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(e -> assertThat(domainCode(e.getCause())).isEqualTo("DELETION_NOT_PENDING"));

            assertFullyDeleted(seed);
            verify(whatsAppPort, times(1)).sendReport(eq(seed.phone()), anyString());
        } finally {
            releaseDrop.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("admin cancel issued while the daily run holds the row lock waits, then TENANT_NOT_FOUND → stays DELETED")
    void adminCancel_racingDailyRun_waitsThenRefused() throws Exception {
        // Expired date: the real-clock daily path selects it, and the admin cancel (no grace-period
        // guard) still sees a qualifying row, so its UPDATE must queue on the lock.
        Seed seed = seedPending(Duration.ofDays(-1));
        CountDownLatch inDrop = new CountDownLatch(1);
        CountDownLatch releaseDrop = new CountDownLatch(1);
        TenantSchemaProvisioner blockingProvisioner = mock(TenantSchemaProvisioner.class);
        doAnswer(inv -> {
            inDrop.countDown();
            assertThat(releaseDrop.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            schemaProvisioner.dropSchemaForDeletion(inv.getArgument(0));
            return null;
        }).when(blockingProvisioner).dropSchemaForDeletion(anyString());
        TenantDeletionService scheduler = scheduler(blockingProvisioner, Clock.systemUTC());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> deletion = pool.submit(() -> scheduler.runTenantDeletion(seed.tenantId(), true));
            assertThat(inDrop.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("scheduler reached the DROP").isTrue();

            Future<?> cancel = pool.submit(() -> adminTenantService.cancelDeletion(seed.tenantId()));

            boolean waiting = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() "
                                + "AND wait_event_type = 'Lock' AND query ILIKE '%UPDATE public.tenants%' "
                                + "AND query ILIKE '%to_regnamespace%' AND pid <> pg_backend_pid())",
                        Boolean.class));
                if (!waiting) Thread.sleep(50);
            }
            releaseDrop.countDown();

            assertThat(waiting).as("admin cancel UPDATE waited on the tenant row lock").isTrue();
            assertThat(deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cancel.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(e -> assertThat(domainCode(e.getCause())).isEqualTo("TENANT_NOT_FOUND"));

            assertFullyDeleted(seed);
        } finally {
            releaseDrop.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ── Row 3: cancel after a post-DROP failure ───────────────────────────────────────────────────

    @Test
    @DisplayName("schema already dropped (post-DROP failure): owner and admin cancels refused; next run completes it")
    void cancel_afterSchemaDropped_refusedThenSchedulerCompletes() {
        // Future date isolates the namespace guard (the grace-period guard alone would not refuse).
        Seed seed = seedPending(Duration.ofDays(20));
        schemaProvisioner.dropSchemaForDeletion(seed.schema()); // what a failed run leaves behind
        assertThat(schemaExists(seed.schema())).isFalse();

        assertThatThrownBy(() -> accountDeletionService.cancelDeletion(seed.ownerId(), seed.schema()))
                .satisfies(e -> assertThat(domainCode(e)).isEqualTo("DELETION_NOT_PENDING"));
        assertThatThrownBy(() -> adminTenantService.cancelDeletion(seed.tenantId()))
                .satisfies(e -> assertThat(domainCode(e)).isEqualTo("TENANT_NOT_FOUND"));
        assertThat(status(seed.tenantId())).isEqualTo("DELETION_PENDING");

        // Next daily run (clock past the grace period): DROP IF EXISTS is a no-op, the rest commits.
        TenantDeletionService scheduler = scheduler(schemaProvisioner,
                Clock.offset(Clock.systemUTC(), Duration.ofDays(31)));
        assertThat(scheduler.runTenantDeletion(seed.tenantId(), true)).isTrue();

        assertFullyDeleted(seed);
        verify(whatsAppPort, times(1)).sendReport(eq(seed.phone()), anyString());
    }

    // ── Row 4: owner cancel after the grace period ────────────────────────────────────────────────

    @Test
    @DisplayName("owner cancel after the grace period is refused and changes nothing; the admin may still rescue it")
    void ownerCancel_afterGracePeriod_refused() {
        Seed seed = seedPending(Duration.ofDays(-1));
        Timestamp before = scheduledAt(seed.tenantId());

        assertThatThrownBy(() -> accountDeletionService.cancelDeletion(seed.ownerId(), seed.schema()))
                .satisfies(e -> assertThat(domainCode(e)).isEqualTo("DELETION_NOT_PENDING"));

        assertThat(status(seed.tenantId())).isEqualTo("DELETION_PENDING");
        assertThat(scheduledAt(seed.tenantId())).isEqualTo(before);
        assertThat(schemaExists(seed.schema())).isTrue();

        adminTenantService.cancelDeletion(seed.tenantId());

        assertThat(status(seed.tenantId())).isEqualTo("ACTIVE");
        assertThat(scheduledAt(seed.tenantId())).isNull();
    }

    // ── Rows 5–6: admin forceDelete ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SUSPENDED", "DELETION_PENDING"})
    @DisplayName("forceDelete drops the schema, purges memberships/users/tokens, marks DELETED, WhatsApp after commit")
    void forceDelete_runsTheDeletionRoutine(String initialStatus) {
        Seed seed = seedTenant(initialStatus,
                "DELETION_PENDING".equals(initialStatus) ? Instant.now().plus(20, ChronoUnit.DAYS) : null);
        AdminTenantService service = new AdminTenantService(jdbc,
                IdentityAuthApiTestSupport.tenantDeletionPort(scheduler(schemaProvisioner, Clock.systemUTC())));

        service.forceDelete(seed.tenantId());

        assertFullyDeleted(seed);
        assertThat(scheduledAt(seed.tenantId())).isNull();
        verify(whatsAppPort, times(1)).sendReport(eq(seed.phone()), anyString());
    }

    @Test
    @DisplayName("forceDelete whose routine fails keeps the committed expired marker: owner cancel refused, next run completes it")
    void forceDelete_routineFails_markerCommittedThenRetried() {
        Seed seed = seedTenant("ACTIVE", null);
        TenantSchemaProvisioner failingProvisioner = mock(TenantSchemaProvisioner.class);
        doThrow(new RuntimeException("simulated DROP failure"))
                .when(failingProvisioner).dropSchemaForDeletion(anyString());
        AdminTenantService service = new AdminTenantService(jdbc,
                IdentityAuthApiTestSupport.tenantDeletionPort(scheduler(failingProvisioner, Clock.systemUTC())));

        assertThatThrownBy(() -> service.forceDelete(seed.tenantId()))
                .hasMessageContaining("simulated DROP failure");

        // The marker auto-committed before the routine; the routine's own transaction rolled back.
        assertThat(status(seed.tenantId())).isEqualTo("DELETION_PENDING");
        assertThat(scheduledAt(seed.tenantId()).toInstant()).isBeforeOrEqualTo(Instant.now());
        assertThat(schemaExists(seed.schema())).isTrue();
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", seed.tenantId())).isEqualTo(1);
        verifyNoInteractions(whatsAppPort);

        // Grace period over: the owner can no longer cancel it.
        assertThatThrownBy(() -> accountDeletionService.cancelDeletion(seed.ownerId(), seed.schema()))
                .satisfies(e -> assertThat(domainCode(e)).isEqualTo("DELETION_NOT_PENDING"));

        // The next daily run (real clock) selects the expired marker and completes the deletion.
        assertThat(scheduler(schemaProvisioner, Clock.systemUTC()).runTenantDeletion(seed.tenantId(), true)).isTrue();
        assertFullyDeleted(seed);
    }

    @Test
    @DisplayName("forceDelete through the Spring bean is wired to the same routine")
    void forceDelete_bean_deletesTenant() {
        Seed seed = seedTenant("ACTIVE", null);

        adminTenantService.forceDelete(seed.tenantId());

        assertFullyDeleted(seed);
    }

    @Test
    @DisplayName("forceDelete on a DELETED, system or unknown tenant → TENANT_NOT_FOUND, nothing changes")
    void forceDelete_invalidTargets_tenantNotFound() {
        Seed seed = seedTenant("ACTIVE", null);
        adminTenantService.forceDelete(seed.tenantId());
        assertThat(status(seed.tenantId())).isEqualTo("DELETED");

        assertThatThrownBy(() -> adminTenantService.forceDelete(seed.tenantId()))
                .satisfies(e -> assertThat(domainCode(e)).isEqualTo("TENANT_NOT_FOUND"));
        assertThat(status(seed.tenantId())).isEqualTo("DELETED");
        assertThat(scheduledAt(seed.tenantId())).isNull();

        UUID adminTenant = UUID.fromString(ADMIN_TENANT_ID);
        String adminStatusBefore = jdbc.query("SELECT status FROM public.tenants WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, adminTenant);
        assertThatThrownBy(() -> adminTenantService.forceDelete(adminTenant))
                .satisfies(e -> assertThat(domainCode(e)).isEqualTo("TENANT_NOT_FOUND"));
        String adminStatusAfter = jdbc.query("SELECT status FROM public.tenants WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, adminTenant);
        assertThat(adminStatusAfter).isEqualTo(adminStatusBefore);

        assertThatThrownBy(() -> adminTenantService.forceDelete(UUID.randomUUID()))
                .satisfies(e -> assertThat(domainCode(e)).isEqualTo("TENANT_NOT_FOUND"));
    }

    // ── findDeletionsDueBy on the real schema ─────────────────────────────────────────────────────

    @Test
    @DisplayName("findDeletionsDueBy returns only DELETION_PENDING tenants whose date is due")
    void findDeletionsDueBy_selectsOnlyDuePendingTenants() {
        Seed due = seedPending(Duration.ofDays(-1));
        Seed future = seedPending(Duration.ofDays(5));
        Seed active = seedTenant("ACTIVE", null);

        try {
            var ids = tenantRepository.findDeletionsDueBy(Instant.now()).stream().map(Tenant::getId).toList();

            assertThat(ids).contains(due.tenantId()).doesNotContain(future.tenantId(), active.tenantId());
        } finally {
            // Never leave a due DELETION_PENDING tenant in the shared DB: a later executeDeletions()
            // (another class) would delete it and notify its owner.
            jdbc.update("UPDATE public.tenants SET status = 'ACTIVE', deletion_scheduled_at = NULL "
                    + "WHERE id IN (?, ?)", due.tenantId(), future.tenantId());
        }
    }
}
