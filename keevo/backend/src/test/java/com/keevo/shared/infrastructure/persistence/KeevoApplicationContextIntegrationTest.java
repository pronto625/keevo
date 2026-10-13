package com.keevo.shared.infrastructure.persistence;

import com.keevo.identity.auth.adapter.out.persistence.impl.CachedTokenRevocationAdapter;
import com.keevo.identity.auth.application.service.AuthTokenCleanupJob;
import com.keevo.identity.auth.application.service.PasswordResetRateLimiter;
import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story v1s-15-11 (AC B) — the whole application context starts on a real PostgreSQL 16 and the
 * boot-time wiring of Stories 12.12, 13.9 and 13.12 actually works: public Flyway V1..V13,
 * {@link TenantSchemaMigrationRunner} (SmartLifecycle), ShedLock ({@code SchedulerLockConfig}),
 * the shared {@link Clock} ({@code ClockConfig}) and the provisioning of a new tenant.
 */
@DisplayName("Application context on PostgreSQL (Story v1s-15-11)")
class KeevoApplicationContextIntegrationTest extends FullContextIntegrationTest {

    private static final List<String> PUBLIC_MIGRATIONS =
            List.of("1", "2", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");

    @Autowired ApplicationContext context;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName("Flyway: public migrations V1..V13 are all applied, none failed, none pending")
    void publicMigrationsApplied() {
        MigrationInfo[] applied = flyway.info().applied();
        List<String> versions = Arrays.stream(applied)
                .filter(m -> m.getVersion() != null && !"0".equals(m.getVersion().getVersion()))
                .map(m -> m.getVersion().getVersion())
                .toList();

        // "contains", not an exact list: a future V14 must not break this test
        assertThat(versions).containsAll(PUBLIC_MIGRATIONS);
        assertThat(applied).allSatisfy(m -> assertThat(m.getState().isFailed()).isFalse());
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.shedlock') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'sales' AND column_name = 'version'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("SchedulingConfig: no cron tick in the test profile (jobs are called directly by tests)")
    void schedulingDisabledInTestProfile() {
        // A real tick (hourly day closure / weekly report, 03:00 deletion) would act on other
        // tests' tenants in the shared database. Production keeps it on (matchIfMissing = true).
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LockProvider.class)).hasSize(1);
    }

    @Test
    @DisplayName("ClockConfig: one Clock bean, injected into the time-driven auth beans")
    void clockBeanWired() {
        // Exactly one Clock bean: every constructor-injected Clock (CachedTokenRevocationAdapter and
        // PasswordResetRateLimiter keep it only inside their Caffeine tickers) is necessarily this one.
        assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
        Clock clock = context.getBean(Clock.class);

        assertThat(clock.getZone()).isEqualTo(Clock.systemUTC().getZone());
        assertThat(context.getBean(CachedTokenRevocationAdapter.class)).isNotNull();
        assertThat(context.getBean(PasswordResetRateLimiter.class)).isNotNull();
        assertThat(ReflectionTestUtils.getField(context.getBean(AuthTokenCleanupJob.class), "clock")).isSameAs(clock);
    }

    @Test
    @DisplayName("SchedulerLockConfig: the JDBC lock provider locks in public.shedlock, a second holder is refused")
    void shedLockWired() {
        LockProvider provider = context.getBean(LockProvider.class);
        assertThat(provider).isInstanceOf(JdbcTemplateLockProvider.class);

        String name = "it-context-" + UUID.randomUUID();
        LockConfiguration config = new LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO);
        Optional<SimpleLock> first = provider.lock(config);
        try {
            assertThat(first).isPresent();
            assertThat(provider.lock(config)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.shedlock WHERE name = ?", Integer.class, name))
                    .isEqualTo(1);
        } finally {
            first.ifPresent(SimpleLock::unlock);
        }
        Optional<SimpleLock> again = provider.lock(config);
        assertThat(again).isPresent();
        again.get().unlock();
    }

    @Test
    @DisplayName("TenantSchemaMigrationRunner: started by the lifecycle; a newly provisioned tenant is migrated and synced")
    void tenantProvisioningAndRunner() {
        TenantSchemaMigrationRunner runner = context.getBean(TenantSchemaMigrationRunner.class);
        assertThat(runner.isRunning()).isTrue();

        Tenant tenant = tx.execute(status -> context.getBean(TenantFactory.class).create());
        String schema = tenant.getSchemaName();

        assertThat(jdbc.queryForObject("SELECT status FROM public.tenants WHERE id = ?", String.class, tenant.getId()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, schema))
                .contains("users", "stores", "products", "stock_levels", "stock_transfers", "sales", "sale_items");

        runner.runAll(); // idempotent on a fresh tenant: nothing to change, nothing fails
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'sales' AND column_name = 'version'", Integer.class, schema)).isEqualTo(1);
    }
}
