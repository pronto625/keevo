package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.module.FoundationUpgrade;
import com.keevo.shared.module.ModuleProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * TenantSchemaMigrationRunner — brings the tenant tables of every existing tenant to the latest foundation module
 * versions at startup (Story 16.12).
 *
 * <p>The tenant tables are owned by the foundation module migrations ({@code db/modules/<id>/V*.sql}, idempotent
 * forever). This runner asks {@link ModuleProvisioner#upgradeFoundation(int)} to find, in one set-based pass, the live
 * tenants whose foundation versions are missing or older than the shipped ones, and to provision only those,
 * {@code keevo.tenant-schema.upgrade-parallelism} (default {@value #DEFAULT_PARALLELISM}) at a time. An up-to-date
 * database starts without any Flyway run. One tenant's failure is logged and counted, never aborts startup (the
 * tenant is retried at the next start, or on its first request through {@code ModuleProvisioner.ensureFoundation}).
 * One summary line is logged: tenants checked, provisioned, failed, duration.
 *
 * <p>Runs as a {@link SmartLifecycle} with phase {@value #PHASE}, i.e. before the embedded web server starts
 * accepting requests ({@code WebServerStartStopLifecycle} runs at {@code DEFAULT_PHASE - 1024}) and before
 * {@code @Scheduled} tasks are registered (the scheduling post-processor registers them on
 * {@code ContextRefreshedEvent}, which is published after all lifecycle beans have started).
 */
@Component
public class TenantSchemaMigrationRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaMigrationRunner.class);

    /** Low phase: before the web server (DEFAULT_PHASE - 1024) and before scheduler registration. */
    static final int PHASE = 0;

    static final int DEFAULT_PARALLELISM = 4;

    private final ModuleProvisioner moduleProvisioner;
    private final int parallelism;
    private volatile boolean running;

    public TenantSchemaMigrationRunner(ModuleProvisioner moduleProvisioner,
                                       @Value("${keevo.tenant-schema.upgrade-parallelism:" + DEFAULT_PARALLELISM + "}")
                                       int parallelism) {
        this.moduleProvisioner = moduleProvisioner;
        this.parallelism = Math.max(1, parallelism);
    }

    // ── SmartLifecycle ───────────────────────────────────────────────────────

    @Override
    public void start() {
        if (running) return;
        running = true;
        runAll();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    // ── Work ─────────────────────────────────────────────────────────────────

    /** Upgrades the tenants whose foundation tables are behind; never throws. */
    public FoundationUpgrade runAll() {
        try {
            FoundationUpgrade upgrade = moduleProvisioner.upgradeFoundation(parallelism);
            if (upgrade.failed() > 0) {
                log.error("Tenant tables: {} tenant(s) checked, {} provisioned, {} FAILED (retried at the next start "
                                + "or on first request), {} ms", upgrade.checked(), upgrade.provisioned(),
                        upgrade.failed(), upgrade.durationMs());
            } else {
                log.info("Tenant tables: {} tenant(s) checked, {} provisioned, 0 failed, {} ms", upgrade.checked(),
                        upgrade.provisioned(), upgrade.durationMs());
            }
            return upgrade;
        } catch (RuntimeException e) {
            log.error("Tenant tables: the boot upgrade could not run ({}) — tenants are provisioned on first request",
                    e.getClass().getSimpleName(), e);
            return new FoundationUpgrade(0, 0, 0, 0);
        }
    }
}
