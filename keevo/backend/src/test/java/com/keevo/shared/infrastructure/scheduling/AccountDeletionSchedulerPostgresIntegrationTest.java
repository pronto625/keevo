package com.keevo.shared.infrastructure.scheduling;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserRepository;
import com.keevo.messaging.whatsapp.domain.port.out.WhatsAppPort;
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Story v1s-15-11 — {@link AccountDeletionScheduler} on a real PostgreSQL 16: per-tenant
 * transaction, {@code FOR UPDATE} row lock, DROP first, DELETED last.
 *
 * <p>The scheduler is instantiated from the context's production beans rather than called through
 * the Spring bean: the bean is wrapped by the ShedLock proxy ({@code lockAtLeastFor = PT1M}), which
 * would silently skip the second call and make "a second run is a no-op" vacuous. The
 * {@link WhatsAppPort} is a Mockito mock so the "sent once, after commit" contract is observable.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("AccountDeletionScheduler on PostgreSQL (Story v1s-15-11)")
class AccountDeletionSchedulerPostgresIntegrationTest extends FullContextIntegrationTest {

    @Autowired TenantFactory tenantFactory;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired TenantSchemaProvisioner schemaProvisioner;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final int TIMEOUT_SECONDS = 30;

    private final WhatsAppPort whatsAppPort = mock(WhatsAppPort.class);

    private AccountDeletionScheduler scheduler(JdbcTemplate jdbcTemplate) {
        return new AccountDeletionScheduler(tenantRepository, userRepository, whatsAppPort,
                schemaProvisioner, jdbcTemplate, transactionManager, Clock.systemUTC());
    }

    /** Provisions a tenant with an OWNER (membership, refresh token, device token), expired DELETION_PENDING. */
    private Seed seedExpiredTenant() {
        Tenant tenant = tx.execute(status -> tenantFactory.create());
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.now();
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
        jdbc.update("UPDATE public.tenants SET status = 'DELETION_PENDING', deletion_scheduled_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minus(1, ChronoUnit.DAYS)), tenant.getId());
        return new Seed(tenant.getId(), tenant.getSchemaName(), ownerId, phone);
    }

    private record Seed(UUID tenantId, String schema, UUID ownerId, String phone) {}

    private boolean schemaExists(String schema) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)", Boolean.class, schema));
    }

    private String status(UUID tenantId) {
        return jdbc.queryForObject("SELECT status FROM public.tenants WHERE id = ?", String.class, tenantId);
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    @Test
    @DisplayName("expired tenant: schema dropped, DELETED, membership/user/tokens gone; a second run is a no-op")
    void executeDeletionsDeletesExpiredTenant() {
        Seed seed = seedExpiredTenant();
        assertThat(schemaExists(seed.schema())).isTrue();
        AccountDeletionScheduler scheduler = scheduler(jdbc);

        scheduler.executeDeletions();

        assertThat(schemaExists(seed.schema())).isFalse();
        assertThat(status(seed.tenantId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT deletion_scheduled_at FROM public.tenants WHERE id = ?",
                java.sql.Timestamp.class, seed.tenantId())).isNull();
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.refresh_tokens WHERE tenant_id = ?", seed.schema())).isZero();
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", seed.ownerId())).isZero();
        assertThat(count("SELECT count(*) FROM public.device_tokens WHERE user_id = ?", seed.ownerId())).isZero();

        verify(whatsAppPort, times(1)).sendReport(eq(seed.phone()), anyString());

        scheduler.executeDeletions(); // DELETED is never re-selected

        assertThat(status(seed.tenantId())).isEqualTo("DELETED");
        assertThat(schemaExists(seed.schema())).isFalse();
        verify(whatsAppPort, times(1)).sendReport(eq(seed.phone()), anyString()); // not re-sent
    }

    @Test
    @DisplayName("a member of another tenant keeps the account, its device token and the other membership")
    void multiTenantUserIsPreserved() {
        Seed seed = seedExpiredTenant();
        Tenant other = tx.execute(status -> tenantFactory.create());
        jdbc.update("INSERT INTO public.user_tenant_memberships (id, created_at, updated_at, is_active, role, "
                + "tenant_id, user_id) VALUES (?, now(), now(), true, 'MANAGER', ?, ?)",
                UUID.randomUUID(), other.getId(), seed.ownerId());

        scheduler(jdbc).executeDeletions();

        assertThat(status(seed.tenantId())).isEqualTo("DELETED");
        assertThat(schemaExists(seed.schema())).isFalse();
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.refresh_tokens WHERE tenant_id = ?", seed.schema())).isZero();
        // Still a member elsewhere: the user, its device token and the other membership survive
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", seed.ownerId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.device_tokens WHERE user_id = ?", seed.ownerId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", other.getId())).isEqualTo(1);
        assertThat(status(other.getId())).isEqualTo("ACTIVE");
        assertThat(schemaExists(other.getSchemaName())).isTrue();
    }

    @Test
    @DisplayName("failure after the DROP rolls back the public deletes (still DELETION_PENDING); the next run completes it")
    void postDropFailureRollsBackAndIsRetried() {
        Seed seed = seedExpiredTenant();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        JdbcTemplate failingOnce = new JdbcTemplate(dataSource) {
            @Override
            public int update(String sql, Object... args) {
                // Only for the seeded tenant: a leftover expired tenant in the shared DB must not absorb it
                if (sql.startsWith("DELETE FROM public.refresh_tokens") && args.length == 1
                        && seed.schema().equals(args[0]) && failOnce.getAndSet(false)) {
                    throw new IllegalStateException("simulated failure after the DROP");
                }
                return super.update(sql, args);
            }
        };
        AccountDeletionScheduler scheduler = scheduler(failingOnce);

        scheduler.executeDeletions();

        // DROP ran on its own auto-commit connection; the membership delete (before the failure) rolled back
        assertThat(schemaExists(seed.schema())).isFalse();
        assertThat(status(seed.tenantId())).isEqualTo("DELETION_PENDING");
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", seed.tenantId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", seed.ownerId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.refresh_tokens WHERE tenant_id = ?", seed.schema())).isEqualTo(1);
        verify(whatsAppPort, never()).sendReport(eq(seed.phone()), anyString()); // rolled back: no final message

        scheduler.executeDeletions(); // DROP IF EXISTS is a no-op, the rest now commits

        assertThat(status(seed.tenantId())).isEqualTo("DELETED");
        assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", seed.tenantId())).isZero();
        assertThat(count("SELECT count(*) FROM public.users WHERE id = ?", seed.ownerId())).isZero();
        assertThat(count("SELECT count(*) FROM public.refresh_tokens WHERE tenant_id = ?", seed.schema())).isZero();
        verify(whatsAppPort, times(1)).sendReport(eq(seed.phone()), anyString());
    }

    @Test
    @DisplayName("FOR UPDATE: a cancel UPDATE in flight blocks the scheduler, which then skips the tenant")
    void inFlightCancelBlocksSchedulerWhichThenSkips() throws Exception {
        Seed seed = seedExpiredTenant();
        AccountDeletionScheduler scheduler = scheduler(jdbc);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch cancelUpdated = new CountDownLatch(1);
        CountDownLatch commitCancel = new CountDownLatch(1);
        try {
            // Thread A: the cancel's UPDATE, left uncommitted until B is seen waiting on a lock
            Future<Integer> cancelPid = pool.submit(() -> {
                try (Connection conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    int pid;
                    try (Statement st = conn.createStatement();
                         ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
                        rs.next();
                        pid = rs.getInt(1);
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE public.tenants SET status = 'ACTIVE', deletion_scheduled_at = NULL WHERE id = ?")) {
                        ps.setObject(1, seed.tenantId());
                        ps.executeUpdate();
                    }
                    cancelUpdated.countDown();
                    assertThat(commitCancel.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                    conn.commit();
                    return pid;
                }
            });
            assertThat(cancelUpdated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // Thread B: the scheduler run
            Future<?> run = pool.submit(scheduler::executeDeletions);

            boolean blocked = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (!blocked && System.nanoTime() < deadline) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() "
                                + "AND wait_event_type = 'Lock' AND query LIKE '%FOR UPDATE%' AND pid <> pg_backend_pid())",
                        Boolean.class));
                if (!blocked) Thread.sleep(50);
            }
            commitCancel.countDown();
            cancelPid.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            run.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(blocked).as("scheduler waited on the tenant row lock").isTrue();
            assertThat(schemaExists(seed.schema())).isTrue();
            assertThat(status(seed.tenantId())).isEqualTo("ACTIVE");
            assertThat(count("SELECT count(*) FROM public.user_tenant_memberships WHERE tenant_id = ?", seed.tenantId())).isEqualTo(1);
            // Scoped to the seeded tenant: a leftover expired tenant in the shared DB may be processed too
            verify(whatsAppPort, never()).sendReport(eq(seed.phone()), anyString());
        } finally {
            commitCancel.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }
}
