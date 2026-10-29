package com.keevo.reporting.export.application.service;

import com.keevo.shared.infrastructure.persistence.TenantContext;

import com.keevo.identity.api.TenantStatus;
import com.keevo.identity.api.TenantQueryPort;
import com.keevo.reporting.export.adapter.out.config.ExportExecutorConfig;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.shared.event.catalog.export.DataExportedEvent;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.model.ExportRequest;
import com.keevo.reporting.export.domain.port.in.DownloadExportUseCase;
import com.keevo.reporting.export.domain.port.in.ListExportsUseCase;
import com.keevo.reporting.export.domain.port.in.RequestExportUseCase;
import com.keevo.reporting.export.domain.port.out.DocumentExportRenderer;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.reporting.export.domain.port.out.ExportRenderer;
import com.keevo.shared.application.port.export.ExportDatasetProvider;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.infrastructure.export.ExportValues;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.capability.entitlement.PlanView;
import com.keevo.shared.capability.entitlement.TenantPlanQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ExportService — export use cases (Story v1s-18-1, FR55/FR94/FR96).
 *
 * <p>{@code request}: validate → plan gate → (document renderer: datasets + size check, v1s-18-3)
 * → rate limit → count (Premium only) → then
 * <ul>
 *   <li><b>DIRECT</b> (rows ≤ {@code sync-row-threshold}, or any non-Premium tenant — CSV stays
 *       free, background is Premium): streams to the caller's output on the request thread;</li>
 *   <li><b>BACKGROUND</b>: saves a PENDING {@link ExportJob} and submits {@link ExportJobRunner}
 *       to {@code exportExecutor} (rejected ⇒ job FAILED {@code EXPORT_QUEUE_FULL}, 429).</li>
 * </ul>
 * Each accepted export publishes {@link DataExportedEvent} on the request thread (audit
 * {@code DATA_EXPORTED}, source ONLINE) — a direct export that fails mid-stream too, as
 * {@code DIRECT_INCOMPLETE}. Signed links only open files of ACTIVE tenants. Not transactional: providers read page by page, each
 * page in its own read-only transaction.
 */
@Service
public class ExportService implements RequestExportUseCase, ListExportsUseCase, DownloadExportUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    static final ZoneId WAT = ZoneId.of("Africa/Douala");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    static final String DOWNLOAD_PATH = "/api/v1/exports/download?token=";
    static final int LIST_LIMIT = 50;

    private final ExportDatasetRegistry datasetRegistry;
    private final ExportRendererRegistry rendererRegistry;
    private final ExportStreamingWriter writer;
    private final ExportJobRepository jobRepository;
    private final ExportFileStorage storage;
    private final ExportRateLimiter rateLimiter;
    private final ExportLinkSigner linkSigner;
    private final TenantPlanQuery tenantPlanQuery;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantQueryPort tenantQuery;
    private final Executor exportExecutor;
    private final ExportJobRunner jobRunner;
    private final long syncRowThreshold;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ExportService(ExportDatasetRegistry datasetRegistry,
                         ExportRendererRegistry rendererRegistry,
                         ExportStreamingWriter writer,
                         ExportJobRepository jobRepository,
                         ExportFileStorage storage,
                         ExportRateLimiter rateLimiter,
                         ExportLinkSigner linkSigner,
                         TenantPlanQuery tenantPlanQuery,
                         ApplicationEventPublisher eventPublisher,
                         TenantQueryPort tenantQuery,
                         @Qualifier(ExportExecutorConfig.EXPORT_EXECUTOR) Executor exportExecutor,
                         ExportJobRunner jobRunner,
                         ExportProperties properties,
                         Clock clock) {
        this.datasetRegistry = datasetRegistry;
        this.rendererRegistry = rendererRegistry;
        this.writer = writer;
        this.jobRepository = jobRepository;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.linkSigner = linkSigner;
        this.tenantPlanQuery = tenantPlanQuery;
        this.eventPublisher = eventPublisher;
        this.tenantQuery = tenantQuery;
        this.exportExecutor = exportExecutor;
        this.jobRunner = jobRunner;
        this.syncRowThreshold = properties.getSyncRowThreshold();
        this.clock = clock;
    }

    // ── RequestExportUseCase ────────────────────────────────────────────────────

    @Override
    public ExportOutcome request(String tenantSchema, UUID actorId, ExportRequest request, DirectOutput direct)
            throws IOException {
        List<ExportDatasetProvider> providers = validate(request);
        request = withNormalizedCriteria(request);

        boolean premium = isPremium();
        String feature = request.format().premiumFeature();
        if (feature != null && !premium) {
            throw new DomainException(ErrorCode.PLAN_FEATURE_REQUIRED, "Premium plan required",
                    Map.of("feature", feature));
        }
        ExportRenderer renderer = rendererRegistry.require(request.format());
        // Story v1s-18-3 — a document (PDF) checks its datasets and size before the quota too.
        Long documentCount = null;
        if (renderer instanceof DocumentExportRenderer document) {
            document.validate(providers);
            documentCount = count(providers, request.filter());
            document.checkSize(providers, request.filter(), documentCount);
        }
        // After the gates: a refused request (400 / 403 / 422) does not burn the hourly quota.
        rateLimiter.acquireRequest(tenantSchema);

        Instant now = Instant.now(clock);
        String fileName = fileName(providers, renderer, now);
        String contentType = renderer.contentType(providers.size());

        if (!premium) { // background is Premium: the count would be unused
            return streamDirect(tenantSchema, actorId, request, providers, renderer, contentType, fileName, direct);
        }
        long counted = documentCount != null ? documentCount : count(providers, request.filter());
        if (counted <= syncRowThreshold) {
            return streamDirect(tenantSchema, actorId, request, providers, renderer, contentType, fileName, direct);
        }

        rateLimiter.checkActiveJobs();
        ExportJob job = jobRepository.save(ExportJob.pending(UUID.randomUUID(), actorId, request.format(),
                request.datasets(), request.filter(), fileName, contentType, now));
        UUID jobId = job.getId();
        try {
            exportExecutor.execute(() -> jobRunner.run(tenantSchema, jobId));
        } catch (RejectedExecutionException e) {
            job.fail(ErrorCode.EXPORT_QUEUE_FULL.name(), Instant.now(clock));
            jobRepository.save(job);
            log.warn("EXPORT_QUEUE_FULL jobId={} tenant={}", jobId, tenantSchema);
            throw new DomainException(ErrorCode.EXPORT_QUEUE_FULL, "Export queue is full");
        }
        publish(jobId, actorId, tenantSchema, request, providers, counted, DataExportedEvent.MODE_BACKGROUND);
        log.info("EXPORT_QUEUED jobId={} tenant={} format={} datasets={} countedRows={}",
                jobId, tenantSchema, request.format(), request.datasets(), counted);
        return new Background(job);
    }

    /**
     * Stream on the request thread. Audited in every case: DIRECT with the rows written, or
     * DIRECT_INCOMPLETE with the rows written so far when the stream failed or was interrupted.
     */
    @SuppressWarnings("java:S107")
    private Direct streamDirect(String tenantSchema, UUID actorId, ExportRequest request,
                                List<ExportDatasetProvider> providers, ExportRenderer renderer,
                                String contentType, String fileName, DirectOutput direct) throws IOException {
        UUID exportId = UUID.randomUUID();
        AtomicLong progress = new AtomicLong();
        try {
            OutputStream out = direct.open(contentType, fileName);
            writer.write(out, renderer, providers, request.filter(), progress);
        } catch (IOException | RuntimeException e) {
            try {
                publish(exportId, actorId, tenantSchema, request, providers, progress.get(),
                        DataExportedEvent.MODE_DIRECT_INCOMPLETE);
            } catch (RuntimeException auditFailure) {
                e.addSuppressed(auditFailure);
            }
            log.warn("EXPORT_DIRECT_INCOMPLETE exportId={} tenant={} rowsWritten={} error={}",
                    exportId, tenantSchema, progress.get(), e.getMessage());
            throw e;
        }
        long rows = progress.get();
        publish(exportId, actorId, tenantSchema, request, providers, rows, DataExportedEvent.MODE_DIRECT);
        log.info("EXPORT_DIRECT exportId={} tenant={} format={} datasets={} rows={}",
                exportId, tenantSchema, request.format(), request.datasets(), rows);
        return new Direct(exportId, fileName, rows);
    }

    private static long count(List<ExportDatasetProvider> providers, ExportFilter filter) {
        long counted = 0;
        for (ExportDatasetProvider p : providers) {
            counted += p.count(filter);
        }
        return counted;
    }

    /** Defensive re-validation (the controller already answers 400 on a malformed body). */
    private List<ExportDatasetProvider> validate(ExportRequest request) {
        if (request.format() == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "format is required");
        }
        if (request.datasets().isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "datasets must not be empty");
        }
        Set<String> seen = new HashSet<>();
        for (String key : request.datasets()) {
            if (!seen.add(key)) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR, "duplicate dataset");
            }
        }
        ExportFilter f = request.filter();
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "from must not be after to");
        }
        List<ExportDatasetProvider> providers = request.datasets().stream().map(datasetRegistry::require).toList();
        // Story v1s-18-2 — every criterion must be accepted by every requested dataset (never
        // silently ignored), and its value well-formed; all before any rate-limit slot is used.
        try {
            ExportCriteria.checkApplicable(ExportCriteria.normalize(f.criteria()), providers);
        } catch (ExportCriteria.InvalidCriterionException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, e.getMessage(),
                    Map.of("field", e.getField()));
        }
        return providers;
    }

    private static ExportRequest withNormalizedCriteria(ExportRequest request) {
        ExportFilter f = request.filter();
        return new ExportRequest(request.datasets(), request.format(),
                new ExportFilter(f.storeId(), f.from(), f.to(), ExportCriteria.normalize(f.criteria())));
    }

    private boolean isPremium() {
        return tenantPlanQuery.findCurrentPlan(TenantContext.getCurrentTenant())
                .map(ExportService::isActivePremium)
                .orElse(false); // no subscription row ⇒ FREE
    }

    static boolean isActivePremium(PlanView s) {
        return (s.isPremium() || s.isPremiumTrial()) && s.active();
    }

    /**
     * {@code keevo-<french label slug>-<stamp>.<ext>} for one dataset ({@code keevo-ventes-…}),
     * {@code keevo-export-…} for several (v1s-16-20: no dataset key in a name the user sees).
     */
    static String fileName(List<ExportDatasetProvider> providers, ExportRenderer renderer, Instant now) {
        int n = providers.size();
        String slug = n == 1 ? ExportValues.slug(providers.get(0).label()) : null;
        String name = slug != null ? slug : "export";
        return "keevo-" + name + "-" + FILE_STAMP.format(now.atZone(WAT)) + "." + renderer.extension(n);
    }

    private void publish(UUID exportId, UUID actorId, String tenantSchema, ExportRequest request,
                         List<ExportDatasetProvider> providers, long rows, String mode) {
        ExportFilter f = request.filter();
        eventPublisher.publishEvent(new DataExportedEvent(exportId, actorId, tenantSchema,
                request.format().name(), request.datasets(),
                providers.stream().map(ExportDatasetProvider::label).toList(), f.storeId(), f.from(), f.to(),
                f.criteria(), rows, mode, Instant.now(clock)));
    }

    // ── ListExportsUseCase ──────────────────────────────────────────────────────

    @Override
    public List<ExportJob> list() {
        return jobRepository.findRecent(LIST_LIMIT);
    }

    @Override
    public ExportJob get(UUID id) {
        return jobRepository.findById(id).orElseThrow(ExportService::notFound);
    }

    // ── DownloadExportUseCase ───────────────────────────────────────────────────

    @Override
    public ExportFile open(UUID jobId) throws IOException {
        ExportJob job = get(jobId);
        if (job.isExpiredAt(Instant.now(clock))) {
            throw expired();
        }
        if (job.getStatus() != ExportJobStatus.READY) {
            throw new DomainException(ErrorCode.EXPORT_NOT_READY, "Export not ready",
                    Map.of("status", job.getStatus().name()));
        }
        if (!storage.exists(job.getStoragePath())) {
            log.warn("EXPORT_FILE_MISSING jobId={} — treated as expired", jobId);
            throw expired();
        }
        long size = job.getFileSize() != null ? job.getFileSize() : -1;
        try {
            return new ExportFile(job.getFileName(), job.getContentType(), size, storage.read(job.getStoragePath()));
        } catch (NoSuchFileException e) { // purged between exists() and read()
            throw expired();
        }
    }

    @Override
    public LinkTarget verifyLink(String token) {
        ExportLinkSigner.Claims claims = linkSigner.verify(token);
        // A link must not outlive its tenant's access: suspended / deleted tenants → invalid.
        boolean tenantActive = tenantQuery.findBySchemaName(claims.tenantSchema())
                .map(t -> t.status() == TenantStatus.ACTIVE)
                .orElse(false);
        if (!tenantActive) {
            throw new DomainException(ErrorCode.EXPORT_LINK_INVALID, "Tenant of the export link is not active");
        }
        if (!Instant.now(clock).isBefore(claims.expiresAt())) {
            throw expired();
        }
        return new LinkTarget(claims.tenantSchema(), claims.jobId());
    }

    @Override
    public String signedDownloadPath(String tenantSchema, UUID jobId, Instant expiresAt) {
        if (expiresAt == null || !Instant.now(clock).isBefore(expiresAt)) return null;
        return DOWNLOAD_PATH + linkSigner.sign(tenantSchema, jobId, expiresAt);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.EXPORT_NOT_FOUND, "Export not found");
    }

    private static DomainException expired() {
        return new DomainException(ErrorCode.EXPORT_EXPIRED, "Export expired");
    }
}
