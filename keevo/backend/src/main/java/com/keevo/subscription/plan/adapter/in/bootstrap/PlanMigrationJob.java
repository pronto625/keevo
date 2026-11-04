package com.keevo.subscription.plan.adapter.in.bootstrap;

import com.keevo.identity.api.PlanKind;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanChangeCause;
import com.keevo.shared.capability.entitlement.PlanChangeRecorder;
import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PlanMigrationJob — one-shot startup data migration of the merged offer (Story 19.7b, ADR-06): every Keevo
 * {@code PREMIUM} subscription moves to {@code TEAM}, keeping its end date and status (same price until the paid
 * period ends; no loss). Module scripts may not update data (db/modules/README), hence a boot job; same template as
 * {@link PlanTypeRealignJob}: {@link ApplicationReadyEvent}, own daemon thread, one instance (ShedLock
 * {@value #LOCK_NAME}), gated by {@code keevo.scheduling.enabled}.
 *
 * <p>Per tenant (non-deleted, system tenant excluded), one transaction: one {@code UPDATE} of the tenant's
 * subscriptions (no per-row loop); when a row moved, {@code public.tenants.plan_type} is synced, a
 * {@code PLAN_MIGRATION} event PREMIUM → TEAM is recorded (no actor) and the paid-module rows are re-synced on the new
 * plan ({@link PlanModuleRowSync}). Idempotent: a rerun finds no PREMIUM row and writes nothing. A tenant that fails
 * is logged and skipped; a connection/resource failure stops the run (the next boot retries).
 *
 * <p>One shot (FUS-NFR11): a run with no failure records the done-marker {@value #MARKER} in
 * {@code public.data_migrations}; once it exists, the job reads that one row and touches no tenant. A run with a
 * failed tenant leaves no marker, so the next boot retries (moved tenants are not moved again).
 *
 * <p>The mapping PREMIUM → TEAM is a declared one-shot data migration constant (allow-listed in the plan-code gate),
 * not behaviour: running PREMIUM_TRIAL trials are left to term.
 */
@Component
public class PlanMigrationJob {

    private static final Logger log = LoggerFactory.getLogger(PlanMigrationJob.class);

    static final String LOCK_NAME = "planMigrationJob";

    /** ADR-06: the Keevo paid plan and its merged-offer successor (one-shot data migration). */
    static final PlanType FROM = PlanType.PREMIUM;
    static final PlanType TO = PlanType.of("TEAM");
    static final String REASON = "Fusion : Premium devient Team";
    static final String MARKER = "plan-migration-premium-team";

    static final String MARKER_DONE_SQL = "SELECT count(*) FROM public.data_migrations WHERE name = ?";
    static final String MARKER_SQL =
            "INSERT INTO public.data_migrations (name, done_at) VALUES (?, now()) ON CONFLICT (name) DO NOTHING";

    static final String FETCH_TENANTS =
            "SELECT id, schema_name FROM public.tenants WHERE status <> 'DELETED' "
            + "AND id <> '00000000-0000-0000-0000-000000000000'::uuid";

    /** Keeps {@code expires_at} and {@code status}; legacy limit columns from the new plan's profile. */
    static final String MIGRATE_SQL =
            "UPDATE \"%s\".subscriptions SET plan_type = ?, max_stores = ?, max_products = ?, max_employees = ? "
            + "WHERE plan_type = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LockProvider lockProvider;
    private final PlanChangeRecorder recorder;
    private final PlanModuleRowSync planModuleRows;
    private final PlanCatalog catalog;
    private final PlanProfiles profiles;
    private final boolean enabled;

    public PlanMigrationJob(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            LockProvider lockProvider, PlanChangeRecorder recorder,
                            PlanModuleRowSync planModuleRows, PlanCatalog catalog, PlanProfiles profiles,
                            @Value("${keevo.scheduling.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.lockProvider = lockProvider;
        this.recorder = recorder;
        this.planModuleRows = planModuleRows;
        this.catalog = catalog;
        this.profiles = profiles;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) return;
        Thread.ofPlatform().name("plan-migration-job").daemon(true).start(this::runAtStartup);
    }

    void runAtStartup() {
        Optional<SimpleLock> lock;
        try {
            lock = lockProvider.lock(new LockConfiguration(
                    Instant.now(), LOCK_NAME, Duration.ofMinutes(30), Duration.ofSeconds(10)));
        } catch (RuntimeException e) {
            log.error("PlanMigrationJob: cannot take the lock — run skipped", e);
            return;
        }
        if (lock.isEmpty()) {
            log.info("PlanMigrationJob: lock held by another instance — run skipped");
            return;
        }
        try {
            migrateAll();
        } catch (RuntimeException e) {
            log.error("PlanMigrationJob: run failed", e);
        } finally {
            try {
                lock.get().unlock();
            } catch (RuntimeException e) {
                log.warn("PlanMigrationJob: unlock failed — the lock expires by itself", e);
            }
        }
    }

    /** @return number of tenants whose subscription moved from PREMIUM to TEAM */
    public int migrateAll() {
        Integer marked = jdbc.queryForObject(MARKER_DONE_SQL, Integer.class, MARKER);
        if (marked != null && marked > 0) {
            log.debug("PlanMigrationJob: already done ({}) — nothing to do", MARKER);
            return 0;
        }
        if (catalog.find(TO).map(p -> p.kind() != PlanKind.PAID || p.archived()).orElse(true)) {
            log.error("PlanMigrationJob: plan {} missing from the catalogue, archived or not paid — nothing migrated",
                    TO);
            return 0;
        }
        PlanProfile target = profiles.profile(TO); // once per run, before any tenant transaction
        if (target.fallback()) {
            log.error("PlanMigrationJob: profile of plan {} unreadable or missing (fallback limits) — nothing migrated",
                    TO);
            return 0;
        }
        int moved = 0;
        int failed = 0;
        for (Map<String, Object> tenant : jdbc.queryForList(FETCH_TENANTS)) {
            String schema = (String) tenant.get("schema_name");
            if (!TenantSchema.isValid(schema)) continue;
            UUID tenantId = toUuid(tenant.get("id"));
            try {
                Boolean done = tx.execute(status -> migrate(tenantId, schema, target));
                if (Boolean.TRUE.equals(done)) moved++;
            } catch (DataAccessResourceFailureException | TransientDataAccessException
                     | RecoverableDataAccessException e) {
                throw e;
            } catch (RuntimeException e) {
                failed++;
                log.warn("PlanMigrationJob: tenant {} skipped ({}: {})", tenantId, e.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        log.info("PlanMigrationJob: {} tenant(s) moved {} -> {}, {} failed", moved, FROM, TO, failed);
        if (failed == 0) {
            jdbc.update(MARKER_SQL, MARKER);
        }
        return moved;
    }

    /** In the tenant's transaction: true when a subscription row moved. */
    private boolean migrate(UUID tenantId, String schema, PlanProfile target) {
        int rows = jdbc.update(String.format(MIGRATE_SQL, TenantSchema.validate(schema)), TO.code(),
                target.legacyMaxStores(), target.legacyMaxProducts(), target.legacyMaxEmployees(), FROM.code());
        if (rows == 0) {
            return false;
        }
        recorder.syncPublicPlanType(tenantId, TO);
        recorder.recordEvent(tenantId, FROM, TO, PlanChangeCause.PLAN_MIGRATION, null, REASON);
        planModuleRows.sync(tenantId, schema);
        log.info("PlanMigrationJob: tenant {} moved {} -> {}", tenantId, FROM, TO);
        return true;
    }

    private static UUID toUuid(Object value) {
        return value instanceof UUID u ? u : UUID.fromString(String.valueOf(value));
    }
}
