package com.keevo.reporting.export.application.service;

import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage.StoredFile;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage.WriteHandle;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.reporting.export.domain.port.out.ExportRenderer;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs one background export job on {@code exportExecutor} (Story v1s-18-1).
 *
 * <p>Sets {@link TenantContext} to the job's tenant, moves the job RUNNING, streams the file to a
 * temporary part file, publishes it, marks the job READY ({@code expiresAt = completedAt +
 * retention}) and pushes {@code EXPORT_READY} to the owners. Any failure marks the job FAILED,
 * deletes the partial file and pushes {@code EXPORT_FAILED}. If the job is no longer RUNNING when
 * the file is ready (failed by the purge meanwhile), the file is deleted and nothing is pushed. The context is always cleared; the
 * push is sent last ({@code FcmNotificationAdapter} clears the context itself).
 */
@Component
public class ExportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportJobRunner.class);
    static final String TYPE_READY = "EXPORT_READY";
    static final String TYPE_FAILED = "EXPORT_FAILED";
    static final String FAILURE_CODE = "EXPORT_FAILED";

    private final ExportJobRepository jobRepository;
    private final ExportFileStorage storage;
    private final ExportStreamingWriter writer;
    private final ExportDatasetRegistry datasetRegistry;
    private final ExportRendererRegistry rendererRegistry;
    private final NotificationPort notificationPort;
    private final Duration retention;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ExportJobRunner(ExportJobRepository jobRepository,
                           ExportFileStorage storage,
                           ExportStreamingWriter writer,
                           ExportDatasetRegistry datasetRegistry,
                           ExportRendererRegistry rendererRegistry,
                           NotificationPort notificationPort,
                           ExportProperties properties,
                           Clock clock) {
        this.jobRepository = jobRepository;
        this.storage = storage;
        this.writer = writer;
        this.datasetRegistry = datasetRegistry;
        this.rendererRegistry = rendererRegistry;
        this.notificationPort = notificationPort;
        this.retention = properties.getRetention();
        this.clock = clock;
    }

    public void run(String tenantSchema, UUID jobId) {
        ExportJob job = null;
        boolean ready = false;
        TenantContext.setCurrentTenant(tenantSchema);
        try {
            job = jobRepository.findById(jobId).orElse(null);
            if (job == null) {
                log.warn("EXPORT_JOB_MISSING jobId={} tenant={}", jobId, tenantSchema);
                return;
            }
            if (job.getStatus() != ExportJobStatus.PENDING) {
                log.warn("EXPORT_JOB_NOT_PENDING jobId={} tenant={} status={} — skipped", jobId, tenantSchema,
                        job.getStatus());
                job = null; // not ours to run nor to notify about
                return;
            }
            job.start(Instant.now(clock));
            job = jobRepository.save(job);
            job = produce(tenantSchema, job);
            if (job == null) {
                log.warn("EXPORT_JOB_SUPERSEDED jobId={} tenant={} — no longer RUNNING (purged?), file discarded",
                        jobId, tenantSchema);
                return; // no READY, no push
            }
            ready = true;
            log.info("EXPORT_READY jobId={} tenant={} rows={} size={}",
                    jobId, tenantSchema, job.getRowCount(), job.getFileSize());
        } catch (Exception e) {
            log.error("EXPORT_JOB_FAILED jobId={} tenant={} error={}", jobId, tenantSchema, e.getMessage(), e);
            job = markFailed(jobId, errorCode(e), job);
        } finally {
            TenantContext.clear();
        }
        if (job != null) {
            notifyOwners(tenantSchema, job, ready);
        }
    }

    private ExportJob produce(String tenantSchema, ExportJob job) throws Exception {
        List<ExportDatasetProvider> providers = job.getDatasets().stream().map(datasetRegistry::require).toList();
        ExportRenderer renderer = rendererRegistry.require(job.getFormat());
        WriteHandle handle = storage.openWrite(tenantSchema, job.getId(), renderer.extension(providers.size()));
        StoredFile file;
        long rows;
        try {
            rows = writer.write(handle.stream(), renderer, providers, job.getFilter());
            file = handle.commit();
        } catch (Exception e) {
            handle.abort();
            throw e;
        }
        try {
            // The purge may have failed the job meanwhile: never overwrite FAILED with READY.
            ExportJob current = jobRepository.findById(job.getId()).orElse(null);
            if (current == null || current.getStatus() != ExportJobStatus.RUNNING) {
                storage.delete(file.storagePath());
                return null;
            }
            Instant done = Instant.now(clock);
            current.complete(rows, file.size(), file.storagePath(), done, done.plus(retention));
            return jobRepository.save(current);
        } catch (RuntimeException e) {
            storage.delete(file.storagePath());
            throw e;
        }
    }

    /** Re-read the job (the in-memory one may be ahead of the database) and fail it if still active. */
    private ExportJob markFailed(UUID jobId, String code, ExportJob fallback) {
        try {
            ExportJob current = jobRepository.findById(jobId).orElse(null);
            if (current == null) return fallback;
            if (current.getStatus().isActive()) {
                current.fail(code, Instant.now(clock));
                current = jobRepository.save(current);
            }
            return current;
        } catch (RuntimeException e) {
            log.error("EXPORT_JOB_FAIL_UPDATE_FAILED jobId={} error={}", jobId, e.getMessage(), e);
            return fallback;
        }
    }

    private void notifyOwners(String tenantSchema, ExportJob job, boolean ready) {
        Map<String, String> metadata = Map.of("jobId", job.getId().toString());
        String deepLink = "/exports/" + job.getId();
        NotificationPayload payload = ready
                ? NotificationPayload.of(TYPE_READY, "Votre export est prêt",
                        "Votre export " + job.getFormat() + " est prêt. Il reste disponible "
                                + retention.toHours() + " h.", deepLink, metadata)
                : NotificationPayload.of(TYPE_FAILED, "Échec de l'export",
                        "Votre export n'a pas pu être généré. Veuillez réessayer.", deepLink, metadata);
        try {
            notificationPort.notifyOwners(tenantSchema, payload);
        } catch (RuntimeException e) {
            log.warn("EXPORT_NOTIFY_FAILED jobId={} tenant={} error={}", job.getId(), tenantSchema, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private static String errorCode(Exception e) {
        return e instanceof DomainException de && de.getDomainCode() != null ? de.getDomainCode() : FAILURE_CODE;
    }
}
