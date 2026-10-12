package com.keevo.reporting.export.application.service;

import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Purge and recovery of background exports (Story v1s-18-1), for every tenant not DELETED
 * (files of SUSPENDED / DELETION_PENDING tenants expire too).
 *
 * <ul>
 *   <li><b>Hourly purge</b>: READY jobs past {@code expiresAt} → file deleted, EXPIRED; stale jobs
 *       (PENDING created, or RUNNING started, more than {@code stale-job-after} ago) → FAILED
 *       {@code EXPORT_INTERRUPTED}, leftover part file deleted.</li>
 *   <li><b>Startup recovery</b> ({@link ApplicationReadyEvent}): the executor queue is in memory, so
 *       every job still PENDING / RUNNING at boot was lost with the previous process → FAILED
 *       {@code EXPORT_INTERRUPTED}, part files deleted (frees {@code max-active-jobs}). This relies
 *       on V1 being a single instance with local-disk storage: with several instances, a boot would
 *       fail jobs still running on another one.</li>
 * </ul>
 * Errors are logged per tenant / per job and the loop continues.
 */
@Component
public class ExportPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExportPurgeScheduler.class);
    static final String INTERRUPTED = "EXPORT_INTERRUPTED";

    private final TenantRepository tenantRepository;
    private final ExportJobRepository jobRepository;
    private final ExportFileStorage storage;
    private final Duration staleJobAfter;
    private final Clock clock;

    public ExportPurgeScheduler(TenantRepository tenantRepository, ExportJobRepository jobRepository,
                                ExportFileStorage storage, ExportProperties properties, Clock clock) {
        this.tenantRepository = tenantRepository;
        this.jobRepository = jobRepository;
        this.storage = storage;
        this.staleJobAfter = properties.getStaleJobAfter();
        this.clock = clock;
    }

    @Scheduled(cron = "0 20 * * * *")
    @SchedulerLock(name = "exportPurge", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    public void purge() {
        forEachTenant("EXPORT_PURGE_FAILED", this::purgeTenant);
    }

    /** Fail every job left PENDING / RUNNING by the previous process (single-instance V1). */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        forEachTenant("EXPORT_RECOVERY_FAILED", this::recoverTenant);
    }

    private void forEachTenant(String errorTag, Consumer<String> action) {
        List<Tenant> tenants;
        try {
            tenants = tenantRepository.findAll();
        } catch (RuntimeException e) {
            log.error("{} — cannot list tenants: {}", errorTag, e.getMessage(), e);
            return;
        }
        for (Tenant tenant : tenants) {
            if (tenant.getStatus() == TenantStatus.DELETED) continue;
            try {
                action.accept(tenant.getSchemaName());
            } catch (Exception e) {
                log.error("{} tenant={} error={}", errorTag, tenant.getSchemaName(), e.getMessage(), e);
            }
        }
    }

    /** Purge one tenant; sets and clears {@link TenantContext} itself. */
    public void purgeTenant(String tenantSchema) {
        TenantContext.setCurrentTenant(tenantSchema);
        try {
            Instant now = Instant.now(clock);
            for (ExportJob job : jobRepository.findReadyExpiredAt(now)) {
                try {
                    storage.delete(job.getStoragePath());
                    job.expire();
                    jobRepository.save(job);
                } catch (RuntimeException e) {
                    log.error("EXPORT_EXPIRE_FAILED jobId={} tenant={} error={}",
                            job.getId(), tenantSchema, e.getMessage(), e);
                }
            }
            interrupt(tenantSchema, jobRepository.findStale(now.minus(staleJobAfter)), now);
        } finally {
            TenantContext.clear();
        }
    }

    /** Startup recovery of one tenant; sets and clears {@link TenantContext} itself. */
    public void recoverTenant(String tenantSchema) {
        TenantContext.setCurrentTenant(tenantSchema);
        try {
            interrupt(tenantSchema, jobRepository.findActive(), Instant.now(clock));
        } finally {
            TenantContext.clear();
        }
    }

    private void interrupt(String tenantSchema, List<ExportJob> jobs, Instant now) {
        for (ExportJob job : jobs) {
            try {
                job.fail(INTERRUPTED, now);
                jobRepository.save(job);
                storage.deletePartials(tenantSchema, job.getId());
                log.warn("EXPORT_INTERRUPTED jobId={} tenant={}", job.getId(), tenantSchema);
            } catch (RuntimeException e) {
                log.error("EXPORT_INTERRUPT_FAILED jobId={} tenant={} error={}",
                        job.getId(), tenantSchema, e.getMessage(), e);
            }
        }
    }
}
