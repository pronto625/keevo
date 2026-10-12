package com.keevo.reporting.export.application.service;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.identity.auth.domain.model.TenantStatus;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.shared.application.port.export.ExportPage;
import com.keevo.reporting.export.SyntheticExportProvider;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.adapter.out.render.CsvExportRenderer;
import com.keevo.reporting.export.adapter.out.render.XlsxExportRenderer;
import com.keevo.reporting.export.domain.event.DataExportedEvent;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.model.ExportRequest;
import com.keevo.reporting.export.domain.port.in.DownloadExportUseCase;
import com.keevo.reporting.export.domain.port.in.RequestExportUseCase;
import com.keevo.reporting.export.domain.port.out.ExportFileStorage;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.subscription.plan.domain.model.Subscription;
import com.keevo.subscription.plan.domain.model.SubscriptionStatus;
import com.keevo.subscription.plan.domain.port.out.SubscriptionRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ExportService (v1s-18-1)")
class ExportServiceTest {

    private static final String TENANT = "kv_abc123";
    private static final Instant NOW = Instant.parse("2026-10-12T12:30:00Z"); // 13:30 WAT
    private static final UUID ACTOR = UUID.randomUUID();

    @Mock ExportJobRepository jobRepository;
    @Mock ExportFileStorage storage;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ExportJobRunner jobRunner;
    @Mock TenantRepository tenantRepository;

    private final List<Runnable> submitted = new ArrayList<>();
    private Executor executor = submitted::add;
    private final SyntheticExportProvider stores = new SyntheticExportProvider("stores", "Boutiques", 3);
    private final SyntheticExportProvider big = new SyntheticExportProvider("big", "Gros", 6000);
    private final ExportLinkSigner signer = new ExportLinkSigner("export-link-secret-for-tests-0123456789");
    private int maxRequestsPerHour = 30;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final List<String> opened = new ArrayList<>();
    private final RequestExportUseCase.DirectOutput direct = (contentType, fileName) -> {
        opened.add(contentType + "|" + fileName);
        return out;
    };

    @BeforeEach
    void setUp() {
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantRepository.findBySchemaName(TENANT)).thenReturn(Optional.of(tenant(TenantStatus.ACTIVE)));
    }

    private static Tenant tenant(TenantStatus status) {
        return new Tenant(UUID.randomUUID(), "KV-ABC123", TENANT, status, PlanType.FREE, NOW);
    }

    private ExportService service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ExportProperties props = new ExportProperties();
        return new ExportService(
                new ExportDatasetRegistry(List.of(stores, big)),
                new ExportRendererRegistry(List.of(new CsvExportRenderer(), new XlsxExportRenderer())),
                new ExportStreamingWriter(1000), jobRepository, storage,
                new ExportRateLimiter(maxRequestsPerHour, 3, jobRepository, clock), signer,
                subscriptionRepository, eventPublisher, tenantRepository, executor, jobRunner, props, clock);
    }

    private void plan(PlanType type, SubscriptionStatus status) {
        when(subscriptionRepository.findActivePlan()).thenReturn(Optional.of(
                new Subscription(UUID.randomUUID(), type, status, 1, 1, 1, NOW, null)));
    }

    private static ExportRequest req(ExportFormat format, String... datasets) {
        return new ExportRequest(List.of(datasets), format, ExportFilter.none());
    }

    private RequestExportUseCase.ExportOutcome run(ExportRequest r) throws Exception {
        return service().request(TENANT, ACTOR, r, direct);
    }

    private DataExportedEvent capturedEvent() {
        ArgumentCaptor<DataExportedEvent> c = ArgumentCaptor.forClass(DataExportedEvent.class);
        verify(eventPublisher).publishEvent(c.capture());
        return c.getValue();
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo(code);
    }

    @Nested
    @DisplayName("gating and mode")
    class Gating {

        @Test
        @DisplayName("FREE small CSV → DIRECT stream, named keevo-stores-<WAT stamp>.csv, audited with the written row count")
        void freeSmallCsvIsDirect() throws Exception {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            UUID storeFilter = UUID.randomUUID();
            ExportRequest r = new ExportRequest(List.of("stores"), ExportFormat.CSV,
                    new ExportFilter(storeFilter, NOW.minus(Duration.ofDays(7)), NOW));

            var outcome = run(r);

            assertThat(outcome).isInstanceOf(RequestExportUseCase.Direct.class);
            var d = (RequestExportUseCase.Direct) outcome;
            assertThat(d.fileName()).isEqualTo("keevo-stores-20261012-1330.csv");
            assertThat(d.rowCount()).isEqualTo(3);
            assertThat(opened).containsExactly("text/csv; charset=UTF-8|keevo-stores-20261012-1330.csv");
            assertThat(new String(out.toByteArray(), StandardCharsets.UTF_8)).startsWith("﻿N°;Nom;");
            DataExportedEvent e = capturedEvent();
            assertThat(e.exportId()).isEqualTo(d.exportId());
            assertThat(e.actorId()).isEqualTo(ACTOR);
            assertThat(e.tenantId()).isEqualTo(TENANT);
            assertThat(e.format()).isEqualTo("CSV");
            assertThat(e.datasets()).containsExactly("stores");
            assertThat(e.datasetLabels()).containsExactly("Boutiques");
            assertThat(e.storeId()).isEqualTo(storeFilter);
            assertThat(e.from()).isEqualTo(NOW.minus(Duration.ofDays(7)));
            assertThat(e.to()).isEqualTo(NOW);
            assertThat(e.rowCount()).isEqualTo(3);
            assertThat(e.mode()).isEqualTo("DIRECT");
            verify(jobRepository, never()).save(any());
            assertThat(submitted).isEmpty();
        }

        @Test
        @DisplayName("several datasets → one zip named keevo-export-<stamp>.zip")
        void severalDatasetsZip() throws Exception {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            var d = (RequestExportUseCase.Direct) run(req(ExportFormat.CSV, "stores", "big"));
            assertThat(d.fileName()).isEqualTo("keevo-export-20261012-1330.zip");
            assertThat(opened).containsExactly("application/zip|keevo-export-20261012-1330.zip");
            assertThat(d.rowCount()).isEqualTo(6003);
        }

        @Test
        @DisplayName("FREE XLSX → 403 PLAN_FEATURE_REQUIRED feature=EXPORT_XLSX, nothing written, no audit")
        void freeXlsxRefused() {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            assertThatThrownBy(() -> run(req(ExportFormat.XLSX, "stores")))
                    .isInstanceOf(DomainException.class)
                    .satisfies(e -> {
                        DomainException de = (DomainException) e;
                        assertThat(de.getDomainCode()).isEqualTo("PLAN_FEATURE_REQUIRED");
                        assertThat(de.getDetails()).containsEntry("feature", "EXPORT_XLSX");
                    });
            assertThat(opened).isEmpty();
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("FREE PDF → PLAN_FEATURE_REQUIRED feature=EXPORT_PDF; Premium PDF → EXPORT_FORMAT_UNSUPPORTED")
        void pdf() {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            assertThatThrownBy(() -> run(req(ExportFormat.PDF, "stores")))
                    .satisfies(e -> assertThat(((DomainException) e).getDetails()).containsEntry("feature", "EXPORT_PDF"));
            plan(PlanType.PREMIUM, SubscriptionStatus.ACTIVE);
            assertCode(() -> run(req(ExportFormat.PDF, "stores")), "EXPORT_FORMAT_UNSUPPORTED");
        }

        @Test
        @DisplayName("no subscription row, or a suspended / expired Premium → treated as FREE")
        void inactivePremiumIsFree() {
            when(subscriptionRepository.findActivePlan()).thenReturn(Optional.empty());
            assertCode(() -> run(req(ExportFormat.XLSX, "stores")), "PLAN_FEATURE_REQUIRED");
            plan(PlanType.PREMIUM, SubscriptionStatus.SUSPENDED);
            assertCode(() -> run(req(ExportFormat.XLSX, "stores")), "PLAN_FEATURE_REQUIRED");
            plan(PlanType.PREMIUM_TRIAL, SubscriptionStatus.EXPIRED);
            assertCode(() -> run(req(ExportFormat.XLSX, "stores")), "PLAN_FEATURE_REQUIRED");
        }

        @Test
        @DisplayName("PREMIUM_TRIAL small XLSX → DIRECT xlsx")
        void premiumTrialSmallXlsxDirect() throws Exception {
            plan(PlanType.PREMIUM_TRIAL, SubscriptionStatus.ACTIVE);
            var d = (RequestExportUseCase.Direct) run(req(ExportFormat.XLSX, "stores"));
            assertThat(d.fileName()).endsWith(".xlsx");
            assertThat(opened.get(0)).startsWith(XlsxExportRendererContentType.VALUE);
            assertThat(out.size()).isPositive();
        }

        @Test
        @DisplayName("FREE large CSV (> threshold) → still DIRECT, no job (CSV is free, background is Premium)")
        void freeLargeCsvStaysDirect() throws Exception {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            var d = (RequestExportUseCase.Direct) run(req(ExportFormat.CSV, "big"));
            assertThat(d.rowCount()).isEqualTo(6000);
            assertThat(submitted).isEmpty();
            verify(jobRepository, never()).save(any());
        }

        @Test
        @DisplayName("PREMIUM large export → PENDING job saved, runner submitted, audit BACKGROUND with the counted rows, nothing streamed")
        void premiumLargeIsBackground() throws Exception {
            plan(PlanType.PREMIUM, SubscriptionStatus.ACTIVE);

            var outcome = run(req(ExportFormat.XLSX, "big"));

            assertThat(outcome).isInstanceOf(RequestExportUseCase.Background.class);
            ExportJob job = ((RequestExportUseCase.Background) outcome).job();
            assertThat(job.getStatus()).isEqualTo(ExportJobStatus.PENDING);
            assertThat(job.getRequestedBy()).isEqualTo(ACTOR);
            assertThat(job.getFileName()).isEqualTo("keevo-big-20261012-1330.xlsx");
            assertThat(job.getCreatedAt()).isEqualTo(NOW);
            assertThat(submitted).hasSize(1);
            assertThat(opened).isEmpty();
            DataExportedEvent e = capturedEvent();
            assertThat(e.mode()).isEqualTo("BACKGROUND");
            assertThat(e.rowCount()).isEqualTo(6000);
            assertThat(e.exportId()).isEqualTo(job.getId());

            submitted.get(0).run();
            verify(jobRunner).run(TENANT, job.getId());
        }

        @Test
        @DisplayName("Premium threshold: exactly 5000 counted rows → DIRECT, 5001 → BACKGROUND")
        void thresholdBoundary() throws Exception {
            plan(PlanType.PREMIUM, SubscriptionStatus.ACTIVE);
            SyntheticExportProvider five = new SyntheticExportProvider("five", "Cinq", 5000);
            SyntheticExportProvider fivePlus = new SyntheticExportProvider("fiveplus", "Cinq+", 5001);
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            ExportService s = new ExportService(new ExportDatasetRegistry(List.of(five, fivePlus)),
                    new ExportRendererRegistry(List.of(new CsvExportRenderer())), new ExportStreamingWriter(1000),
                    jobRepository, storage, new ExportRateLimiter(30, 3, jobRepository, clock), signer,
                    subscriptionRepository, eventPublisher, tenantRepository, executor, jobRunner,
                    new ExportProperties(), clock);

            assertThat(s.request(TENANT, ACTOR, req(ExportFormat.CSV, "five"), direct))
                    .isInstanceOf(RequestExportUseCase.Direct.class);
            assertThat(s.request(TENANT, ACTOR, req(ExportFormat.CSV, "fiveplus"), direct))
                    .isInstanceOf(RequestExportUseCase.Background.class);
        }

        @Test
        @DisplayName("non-Premium: providers are never counted (the count would be unused)")
        void freeDoesNotCount() throws Exception {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            SyntheticExportProvider noCount = new SyntheticExportProvider("stores", "Boutiques", 3) {
                @Override
                public long count(ExportFilter filter) {
                    throw new AssertionError("count() must not be called for a non-Premium tenant");
                }
            };
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            ExportService s = new ExportService(new ExportDatasetRegistry(List.of(noCount)),
                    new ExportRendererRegistry(List.of(new CsvExportRenderer())), new ExportStreamingWriter(1000),
                    jobRepository, storage, new ExportRateLimiter(30, 3, jobRepository, clock), signer,
                    subscriptionRepository, eventPublisher, tenantRepository, executor, jobRunner,
                    new ExportProperties(), clock);
            assertThat(s.request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"), direct))
                    .isInstanceOf(RequestExportUseCase.Direct.class);
        }

        @Test
        @DisplayName("direct stream failing mid-way → DATA_EXPORTED DIRECT_INCOMPLETE with the rows written so far, error rethrown")
        void directFailureAuditedIncomplete() {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            SyntheticExportProvider failing = new SyntheticExportProvider("stores", "Boutiques", 5000) {
                @Override
                public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
                    if (cursor != null) throw new IllegalStateException("db down");
                    return super.readPage(filter, cursor, limit);
                }
            };
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            ExportService s = new ExportService(new ExportDatasetRegistry(List.of(failing)),
                    new ExportRendererRegistry(List.of(new CsvExportRenderer())), new ExportStreamingWriter(1000),
                    jobRepository, storage, new ExportRateLimiter(30, 3, jobRepository, clock), signer,
                    subscriptionRepository, eventPublisher, tenantRepository, executor, jobRunner,
                    new ExportProperties(), clock);

            assertThatThrownBy(() -> s.request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"), direct))
                    .hasMessage("db down");
            DataExportedEvent e = capturedEvent();
            assertThat(e.mode()).isEqualTo("DIRECT_INCOMPLETE");
            assertThat(e.rowCount()).isEqualTo(1000);
        }

        @Test
        @DisplayName("client disconnect (IOException on open) → DIRECT_INCOMPLETE with 0 rows")
        void clientAbortAudited() {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            assertThatThrownBy(() -> service().request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"),
                    (ct, name) -> {
                        throw new IOException("Broken pipe");
                    })).isInstanceOf(IOException.class);
            DataExportedEvent e = capturedEvent();
            assertThat(e.mode()).isEqualTo("DIRECT_INCOMPLETE");
            assertThat(e.rowCount()).isZero();
        }

        @Test
        @DisplayName("executor saturated → job FAILED EXPORT_QUEUE_FULL and 429 EXPORT_QUEUE_FULL, no audit")
        void queueFull() {
            plan(PlanType.PREMIUM, SubscriptionStatus.ACTIVE);
            executor = task -> {
                throw new RejectedExecutionException("full");
            };
            List<ExportJob> saved = new ArrayList<>();
            when(jobRepository.save(any())).thenAnswer(inv -> {
                ExportJob j = inv.getArgument(0);
                saved.add(j);
                return j;
            });

            assertCode(() -> run(req(ExportFormat.CSV, "big")), "EXPORT_QUEUE_FULL");
            assertThat(saved).isNotEmpty();
            assertThat(saved.get(saved.size() - 1).getStatus()).isEqualTo(ExportJobStatus.FAILED);
            assertThat(saved.get(saved.size() - 1).getErrorCode()).isEqualTo("EXPORT_QUEUE_FULL");
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("rate limiting")
    class RateLimit {

        @Test
        @DisplayName("more than max-requests-per-hour requests → 429 RATE_LIMIT_EXCEEDED")
        void perHourWindow() throws Exception {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            maxRequestsPerHour = 2;
            ExportService s = service();
            s.request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"), direct);
            s.request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"), direct);
            assertCode(() -> s.request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"), direct), "RATE_LIMIT_EXCEEDED");
            // another tenant has its own window
            s.request("kv_zzz999", ACTOR, req(ExportFormat.CSV, "stores"), direct);
        }

        @Test
        @DisplayName("a 403 PLAN_FEATURE_REQUIRED does not consume the hourly quota")
        void planGateDoesNotConsumeQuota() throws Exception {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            maxRequestsPerHour = 1;
            ExportService s = service();
            assertCode(() -> s.request(TENANT, ACTOR, req(ExportFormat.XLSX, "stores"), direct), "PLAN_FEATURE_REQUIRED");
            assertCode(() -> s.request(TENANT, ACTOR, req(ExportFormat.PDF, "stores"), direct), "PLAN_FEATURE_REQUIRED");
            s.request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"), direct);
            assertCode(() -> s.request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"), direct), "RATE_LIMIT_EXCEEDED");
        }

        @Test
        @DisplayName("max-active-jobs PENDING/RUNNING already → background refused with 429, nothing saved")
        void activeJobs() {
            plan(PlanType.PREMIUM, SubscriptionStatus.ACTIVE);
            when(jobRepository.countActive()).thenReturn(3L);
            assertCode(() -> run(req(ExportFormat.CSV, "big")), "RATE_LIMIT_EXCEEDED");
            verify(jobRepository, never()).save(any());
            assertThat(submitted).isEmpty();
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("unknown dataset → EXPORT_DATASET_UNKNOWN; empty / duplicate / from > to / no format → VALIDATION_ERROR")
        void invalidRequests() {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            assertCode(() -> run(req(ExportFormat.CSV, "nope")), "EXPORT_DATASET_UNKNOWN");
            assertCode(() -> run(req(ExportFormat.CSV)), "VALIDATION_ERROR");
            assertCode(() -> run(req(ExportFormat.CSV, "stores", "stores")), "VALIDATION_ERROR");
            assertCode(() -> run(new ExportRequest(List.of("stores"), null, null)), "VALIDATION_ERROR");
            assertCode(() -> run(new ExportRequest(List.of("stores"), ExportFormat.CSV,
                    new ExportFilter(null, NOW, NOW.minusSeconds(1)))), "VALIDATION_ERROR");
            assertThat(opened).isEmpty();
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("an invalid request does not consume a rate-limit slot")
        void invalidDoesNotConsumeSlot() throws Exception {
            plan(PlanType.FREE, SubscriptionStatus.ACTIVE);
            maxRequestsPerHour = 1;
            ExportService s = service();
            assertCode(() -> s.request(TENANT, ACTOR, req(ExportFormat.CSV, "nope"), direct), "EXPORT_DATASET_UNKNOWN");
            s.request(TENANT, ACTOR, req(ExportFormat.CSV, "stores"), direct);
        }
    }

    @Nested
    @DisplayName("download")
    class Download {

        private ExportJob readyJob(Instant expiresAt) {
            ExportJob j = ExportJob.pending(UUID.randomUUID(), ACTOR, ExportFormat.CSV, List.of("stores"),
                    ExportFilter.none(), "keevo-stores.csv", "text/csv; charset=UTF-8", NOW.minus(Duration.ofHours(1)));
            j.start(NOW.minus(Duration.ofHours(1)));
            j.complete(3, 42, TENANT + "/" + j.getId() + ".csv", NOW.minus(Duration.ofMinutes(50)), expiresAt);
            return j;
        }

        @Test
        @DisplayName("READY → file with stored name, type and size")
        void ready() throws Exception {
            ExportJob j = readyJob(NOW.plus(Duration.ofHours(23)));
            when(jobRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(storage.exists(j.getStoragePath())).thenReturn(true);
            when(storage.read(j.getStoragePath())).thenReturn(new ByteArrayInputStream(new byte[42]));

            DownloadExportUseCase.ExportFile f = service().open(j.getId());

            assertThat(f.fileName()).isEqualTo("keevo-stores.csv");
            assertThat(f.contentType()).isEqualTo("text/csv; charset=UTF-8");
            assertThat(f.size()).isEqualTo(42);
        }

        @Test
        @DisplayName("expiresAt passed or EXPIRED → EXPORT_EXPIRED; file missing → EXPORT_EXPIRED")
        void expired() {
            ExportJob past = readyJob(NOW.minusSeconds(1));
            when(jobRepository.findById(past.getId())).thenReturn(Optional.of(past));
            assertCode(() -> service().open(past.getId()), "EXPORT_EXPIRED");

            ExportJob gone = readyJob(NOW.minusSeconds(1));
            gone.expire();
            when(jobRepository.findById(gone.getId())).thenReturn(Optional.of(gone));
            assertCode(() -> service().open(gone.getId()), "EXPORT_EXPIRED");

            ExportJob missing = readyJob(NOW.plusSeconds(60));
            when(jobRepository.findById(missing.getId())).thenReturn(Optional.of(missing));
            when(storage.exists(missing.getStoragePath())).thenReturn(false);
            assertCode(() -> service().open(missing.getId()), "EXPORT_EXPIRED");
        }

        @Test
        @DisplayName("PENDING / RUNNING / FAILED → EXPORT_NOT_READY; unknown id → EXPORT_NOT_FOUND")
        void notReadyAndNotFound() {
            ExportJob pending = ExportJob.pending(UUID.randomUUID(), ACTOR, ExportFormat.CSV, List.of("stores"),
                    ExportFilter.none(), "f.csv", "text/csv", NOW);
            when(jobRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            assertCode(() -> service().open(pending.getId()), "EXPORT_NOT_READY");
            pending.start(NOW);
            assertCode(() -> service().open(pending.getId()), "EXPORT_NOT_READY");
            pending.fail("EXPORT_FAILED", NOW);
            assertCode(() -> service().open(pending.getId()), "EXPORT_NOT_READY");

            UUID unknown = UUID.randomUUID();
            when(jobRepository.findById(unknown)).thenReturn(Optional.empty());
            assertCode(() -> service().open(unknown), "EXPORT_NOT_FOUND");
            assertCode(() -> service().get(unknown), "EXPORT_NOT_FOUND");
        }

        @Test
        @DisplayName("file purged between exists() and read() → EXPORT_EXPIRED, not 500")
        void purgedDuringOpen() throws Exception {
            ExportJob j = readyJob(NOW.plus(Duration.ofHours(1)));
            when(jobRepository.findById(j.getId())).thenReturn(Optional.of(j));
            when(storage.exists(j.getStoragePath())).thenReturn(true);
            when(storage.read(j.getStoragePath())).thenThrow(new NoSuchFileException(j.getStoragePath()));
            assertCode(() -> service().open(j.getId()), "EXPORT_EXPIRED");
        }

        @Test
        @DisplayName("signed link of a suspended, deletion-pending, deleted or unknown tenant → EXPORT_LINK_INVALID")
        void inactiveTenantLink() {
            String token = signer.sign(TENANT, UUID.randomUUID(), NOW.plus(Duration.ofHours(2)));
            for (TenantStatus status : List.of(TenantStatus.SUSPENDED, TenantStatus.DELETION_PENDING,
                    TenantStatus.DELETED)) {
                when(tenantRepository.findBySchemaName(TENANT)).thenReturn(Optional.of(tenant(status)));
                assertCode(() -> service().verifyLink(token), "EXPORT_LINK_INVALID");
            }
            when(tenantRepository.findBySchemaName(TENANT)).thenReturn(Optional.empty());
            assertCode(() -> service().verifyLink(token), "EXPORT_LINK_INVALID");
        }

        @Test
        @DisplayName("signed link: valid → target; expired → EXPORT_EXPIRED; no link for an expired job")
        void signedLinks() {
            UUID job = UUID.randomUUID();
            String path = service().signedDownloadPath(TENANT, job, NOW.plus(Duration.ofHours(2)));
            assertThat(path).startsWith("/api/v1/exports/download?token=");
            String token = path.substring(path.indexOf('=') + 1);
            DownloadExportUseCase.LinkTarget t = service().verifyLink(token);
            assertThat(t.tenantSchema()).isEqualTo(TENANT);
            assertThat(t.jobId()).isEqualTo(job);

            String old = signer.sign(TENANT, job, NOW.minusSeconds(5));
            assertCode(() -> service().verifyLink(old), "EXPORT_EXPIRED");
            assertCode(() -> service().verifyLink(old + "x"), "EXPORT_LINK_INVALID");
            assertThat(service().signedDownloadPath(TENANT, job, NOW.minusSeconds(5))).isNull();
        }
    }

    /** Keeps the XLSX content type in one place for the assertions above. */
    private static final class XlsxExportRendererContentType {
        static final String VALUE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}
