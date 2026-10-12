package com.keevo.reporting.export.application.service;

import com.keevo.messaging.notification.domain.model.NotificationPayload;
import com.keevo.messaging.notification.domain.port.out.NotificationPort;
import com.keevo.reporting.export.SyntheticExportProvider;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.adapter.out.render.CsvExportRenderer;
import com.keevo.reporting.export.adapter.out.render.XlsxExportRenderer;
import com.keevo.reporting.export.adapter.out.storage.LocalDiskExportFileStorage;
import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.shared.application.port.export.ExportFilter;
import com.keevo.shared.application.port.export.ExportPage;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("ExportJobRunner (v1s-18-1)")
class ExportJobRunnerTest {

    private static final String TENANT = "kv_abc123";
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @TempDir Path root;

    private final InMemoryExportJobRepository repository = new InMemoryExportJobRepository();
    private final NotificationPort notificationPort = mock(NotificationPort.class);
    private final List<String> tenantsSeenByProvider = new ArrayList<>();
    private LocalDiskExportFileStorage storage;

    @BeforeEach
    void setUp() {
        ExportProperties props = new ExportProperties();
        props.setStorageDir(root.toString());
        storage = new LocalDiskExportFileStorage(props);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ExportJobRunner runner(SyntheticExportProvider... providers) {
        return new ExportJobRunner(repository, storage, new ExportStreamingWriter(1000),
                new ExportDatasetRegistry(List.of(providers)),
                new ExportRendererRegistry(List.of(new CsvExportRenderer(), new XlsxExportRenderer())),
                notificationPort, new ExportProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ExportJob pending(ExportFormat format, String... datasets) {
        ExportJob job = ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), format, List.of(datasets),
                ExportFilter.none(), "keevo-x." + format.name().toLowerCase(), "text/csv", NOW.minusSeconds(5));
        return repository.save(job);
    }

    private SyntheticExportProvider recordingProvider(String key, long rows) {
        return new SyntheticExportProvider(key, key, rows) {
            @Override
            public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
                tenantsSeenByProvider.add(TenantContext.getCurrentTenant());
                return super.readPage(filter, cursor, limit);
            }
        };
    }

    @Test
    @DisplayName("READY: file stored under <root>/<tenant>/<id>.csv, rows / size / expiresAt = completedAt + 24 h, EXPORT_READY pushed, context cleared")
    void readyAndPushed() throws Exception {
        ExportJob job = pending(ExportFormat.CSV, "sales");

        runner(recordingProvider("sales", 2500)).run(TENANT, job.getId());

        ExportJob done = repository.findById(job.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ExportJobStatus.READY);
        assertThat(done.getRowCount()).isEqualTo(2500);
        assertThat(done.getStartedAt()).isEqualTo(NOW);
        assertThat(done.getCompletedAt()).isEqualTo(NOW);
        assertThat(done.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(done.getStoragePath()).isEqualTo(TENANT + "/" + job.getId() + ".csv");
        Path file = root.resolve(TENANT).resolve(job.getId() + ".csv");
        assertThat(file).exists();
        assertThat(done.getFileSize()).isEqualTo(Files.size(file));
        try (InputStream in = storage.read(done.getStoragePath())) {
            String csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(csv.split("\r\n")).hasSize(2501);
        }
        assertThat(tenantsSeenByProvider).isNotEmpty().allMatch(TENANT::equals);
        assertThat(partFiles()).isEmpty();

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("EXPORT_READY");
        assertThat(payload.getValue().deepLink()).isEqualTo("/exports/" + job.getId());
        assertThat(payload.getValue().metadata()).containsEntry("jobId", job.getId().toString());
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("provider failure mid-file → FAILED EXPORT_FAILED, partial file deleted, EXPORT_FAILED pushed, context cleared")
    void failureCleansUp() throws Exception {
        ExportJob job = pending(ExportFormat.XLSX, "sales");
        SyntheticExportProvider failing = new SyntheticExportProvider("sales", "Ventes", 5000) {
            @Override
            public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
                if (cursor != null) throw new IllegalStateException("connection reset");
                return super.readPage(filter, cursor, limit);
            }
        };

        runner(failing).run(TENANT, job.getId());

        ExportJob failed = repository.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ExportJobStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("EXPORT_FAILED");
        assertThat(failed.getCompletedAt()).isEqualTo(NOW);
        assertThat(allFiles()).isEmpty();
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notificationPort).notifyOwners(eq(TENANT), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("EXPORT_FAILED");
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("a domain failure keeps its code (dataset removed since the request → EXPORT_DATASET_UNKNOWN)")
    void domainCodeKept() {
        ExportJob job = pending(ExportFormat.CSV, "gone");
        runner(new SyntheticExportProvider("sales", "Ventes", 1)).run(TENANT, job.getId());
        ExportJob failed = repository.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ExportJobStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("EXPORT_DATASET_UNKNOWN");
    }

    @Test
    @DisplayName("job not found in the tenant → nothing pushed, context cleared")
    void missingJob() {
        runner(new SyntheticExportProvider("sales", "Ventes", 1)).run(TENANT, UUID.randomUUID());
        verifyNoInteractions(notificationPort);
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("a job no longer PENDING (already picked up) is skipped: not re-run, not failed, nothing pushed")
    void alreadyRunning() {
        ExportJob job = pending(ExportFormat.CSV, "sales");
        job.start(NOW);
        repository.save(job);
        runner(new SyntheticExportProvider("sales", "Ventes", 1)).run(TENANT, job.getId());
        assertThat(repository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ExportJobStatus.RUNNING);
        assertThat(allFiles()).isEmpty();
        verifyNoInteractions(notificationPort);
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    @Test
    @DisplayName("job failed by the purge while running → file deleted, FAILED kept (no READY overwrite), nothing pushed")
    void supersededByPurge() {
        ExportJob job = pending(ExportFormat.CSV, "sales");
        SyntheticExportProvider purgedMeanwhile = new SyntheticExportProvider("sales", "Ventes", 10) {
            @Override
            public ExportPage readPage(ExportFilter filter, String cursor, int limit) {
                ExportJob current = repository.findById(job.getId()).orElseThrow();
                if (current.getStatus() == ExportJobStatus.RUNNING) {
                    current.fail("EXPORT_INTERRUPTED", NOW);
                    repository.save(current);
                }
                return super.readPage(filter, cursor, limit);
            }
        };

        runner(purgedMeanwhile).run(TENANT, job.getId());

        ExportJob after = repository.findById(job.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ExportJobStatus.FAILED);
        assertThat(after.getErrorCode()).isEqualTo("EXPORT_INTERRUPTED");
        assertThat(allFiles()).isEmpty();
        verifyNoInteractions(notificationPort);
        assertThat(TenantContext.getCurrentTenant()).isNull();
    }

    private List<Path> partFiles() throws Exception {
        return allFiles().stream().filter(p -> p.toString().endsWith(".part")).toList();
    }

    private List<Path> allFiles() {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
