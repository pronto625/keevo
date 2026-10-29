package com.keevo.subscription.plan.adapter.in.bootstrap;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * PlanTypeRealignJob — one-shot startup job (Story 9-2) realigning {@code public.tenants.plan_type}
 * on each tenant schema's {@code subscriptions.plan_type} (the source of truth).
 *
 * <p>Before 9-2 the public column was never updated after registration; from 9-2 every plan change
 * keeps it in step, so this job only repairs the historical drift. It is idempotent (only rows whose
 * value differs are updated), runs on its own thread after {@link ApplicationReadyEvent} and on a
 * single instance (ShedLock lock {@value #LOCK_NAME}). Each correction writes a {@code REALIGNMENT}
 * row in {@code tenant_plan_events} (old public value → schema value), so the plan history of a
 * pre-9-2 tenant does not stay « still on trial ». A tenant whose schema query fails is skipped;
 * connection/resource failures stop the run (next boot retries).
 */
@Component
public class PlanTypeRealignJob {

    private static final Logger log = LoggerFactory.getLogger(PlanTypeRealignJob.class);

    static final String LOCK_NAME = "planTypeRealignJob";

    static final String FETCH_TENANTS =
            "SELECT id, schema_name FROM public.tenants WHERE status <> 'DELETED' "
            + "AND id <> '00000000-0000-0000-0000-000000000000'::uuid";

    /**
     * Realigns one tenant and records a {@code REALIGNMENT} plan event from the old public value to
     * the schema value, in one statement (all CTEs see the pre-update snapshot: {@code old} is the
     * stale value). Returns the number of events written (0 or 1).
     */
    static final String REALIGN_SQL =
            "WITH s AS (SELECT plan_type FROM \"%s\".subscriptions ORDER BY created_at ASC LIMIT 1), "
            + "old AS (SELECT t.id, t.plan_type AS old_plan FROM public.tenants t, s "
            + "  WHERE t.id = ? AND t.plan_type IS DISTINCT FROM s.plan_type), "
            + "upd AS (UPDATE public.tenants t SET plan_type = s.plan_type, updated_at = NOW() FROM s, old "
            + "  WHERE t.id = old.id RETURNING t.id, s.plan_type AS new_plan) "
            + "INSERT INTO public.tenant_plan_events (tenant_id, from_plan, to_plan, cause) "
            + "SELECT upd.id, old.old_plan, upd.new_plan, 'REALIGNMENT' FROM upd JOIN old ON old.id = upd.id";

    private final JdbcTemplate jdbc;
    private final LockProvider lockProvider;
    private final boolean enabled;

    public PlanTypeRealignJob(JdbcTemplate jdbc, LockProvider lockProvider,
                              @Value("${keevo.scheduling.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.lockProvider = lockProvider;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) return;
        Thread.ofPlatform().name("plan-type-realign-job").daemon(true).start(this::runAtStartup);
    }

    void runAtStartup() {
        Optional<SimpleLock> lock;
        try {
            lock = lockProvider.lock(new LockConfiguration(
                    Instant.now(), LOCK_NAME, Duration.ofMinutes(30), Duration.ofSeconds(10)));
        } catch (RuntimeException e) {
            log.error("PlanTypeRealignJob: cannot take the lock — run skipped", e);
            return;
        }
        if (lock.isEmpty()) {
            log.info("PlanTypeRealignJob: lock held by another instance — run skipped");
            return;
        }
        try {
            realignAll();
        } catch (RuntimeException e) {
            log.error("PlanTypeRealignJob: run failed", e);
        } finally {
            try {
                lock.get().unlock();
            } catch (RuntimeException e) {
                log.warn("PlanTypeRealignJob: unlock failed — the lock expires by itself", e);
            }
        }
    }

    /** @return number of {@code public.tenants} rows whose plan_type was corrected */
    public int realignAll() {
        int fixed = 0;
        for (Map<String, Object> tenant : jdbc.queryForList(FETCH_TENANTS)) {
            String schema = (String) tenant.get("schema_name");
            if (!TenantSchema.isValid(schema)) continue;
            try {
                fixed += jdbc.update(String.format(REALIGN_SQL, schema), tenant.get("id"));
            } catch (DataAccessResourceFailureException | TransientDataAccessException
                     | RecoverableDataAccessException e) {
                throw e;
            } catch (DataAccessException e) {
                log.warn("PlanTypeRealignJob: tenant {} skipped ({})", tenant.get("id"), e.getClass().getSimpleName());
            }
        }
        log.info("PlanTypeRealignJob: {} tenant plan(s) realigned", fixed);
        return fixed;
    }
}
