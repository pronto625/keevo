package com.keevo.reporting.export.integration;

import com.keevo.identity.auth.application.service.TenantFactory;
import com.keevo.identity.auth.domain.model.Tenant;
import com.keevo.reporting.export.application.service.ExportJobRunner;
import com.keevo.reporting.export.application.service.ExportPurgeScheduler;
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
import com.keevo.shared.infrastructure.persistence.FullContextIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchemaSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story v1s-18-1 — export chain on a real PostgreSQL 16 with two provisioned tenants: the
 * {@code stores} dataset read through the tenant-routed EntityManager (keyset pages), direct and
 * background exports, V15 + tenant DDL, purge. Every tenant schema is unique to the test run.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Export module on PostgreSQL (v1s-18-1)")
class ExportPostgresIntegrationTest extends FullContextIntegrationTest {

    @Autowired TenantFactory tenantFactory;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired RequestExportUseCase requestExport;
    @Autowired DownloadExportUseCase downloadExport;
    @Autowired ExportJobRepository jobRepository;
    @Autowired ExportJobRunner jobRunner;
    @Autowired ExportPurgeScheduler purgeScheduler;
    @Autowired ExportFileStorage storage;
    @Autowired TenantSchemaSyncService syncService;

    private final List<String> filesToDelete = new ArrayList<>();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        filesToDelete.forEach(storage::delete);
    }

    private String newTenant() {
        Tenant t = tx.execute(status -> tenantFactory.create());
        return t.getSchemaName();
    }

    /** Inserts {@code n} stores named {@code prefix-i}; the first {@code sameInstant} share one created_at. */
    private void insertStores(String schema, String prefix, int n, int sameInstant) {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Instant created = i < sameInstant ? base : base.plusSeconds(i);
            args.add(new Object[]{UUID.randomUUID(), prefix + "-" + i, Timestamp.from(created), Timestamp.from(created)});
        }
        jdbc.batchUpdate("INSERT INTO \"" + schema + "\".stores (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                args);
    }

    private String directCsv(String schema, ExportFilter filter) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TenantContext.setCurrentTenant(schema);
        try {
            var outcome = requestExport.request(schema, UUID.randomUUID(),
                    new ExportRequest(List.of("stores"), ExportFormat.CSV, filter), (ct, name) -> out);
            assertThat(outcome).isInstanceOf(RequestExportUseCase.Direct.class);
        } finally {
            TenantContext.clear();
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static List<String> dataLines(String csv) {
        List<String> lines = new ArrayList<>(List.of(csv.split("\r\n")));
        lines.remove(0); // header
        return lines;
    }

    @Test
    @DisplayName("V15 creates public.export_jobs; provisioning creates the tenant table and its indexes")
    void schema() {
        String a = newTenant();
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.export_jobs')::text", String.class))
                .isEqualTo("export_jobs");
        assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, "\"" + a + "\".export_jobs"))
                .isNotNull();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'export_jobs'",
                String.class, a)).contains("idx_export_jobs_created", "idx_export_jobs_status_expires");
    }

    @Test
    @DisplayName("boot / request sync re-creates a missing tenant export_jobs table")
    void syncCreatesMissingTable() throws Exception {
        String b = newTenant();
        jdbc.execute("DROP TABLE \"" + b + "\".export_jobs");
        syncService.syncNow(b);
        assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, "\"" + b + "\".export_jobs"))
                .isNotNull();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'export_jobs'",
                String.class, b)).contains("idx_export_jobs_created", "idx_export_jobs_status_expires");
    }

    @Test
    @DisplayName("repository: list newest first, countActive = PENDING + RUNNING, stale RUNNING measured from startedAt")
    void repositoryQueries() {
        String a = newTenant();
        Instant now = Instant.now();
        TenantContext.setCurrentTenant(a);
        try {
            ExportJob oldPending = job(now.minusSeconds(4 * 3600));                // PENDING, created 4 h ago
            ExportJob lateStart = job(now.minusSeconds(3 * 3600));                 // RUNNING, started 10 min ago
            lateStart.start(now.minusSeconds(600));
            ExportJob longRun = job(now.minusSeconds(2 * 3600 + 1800));            // RUNNING, started 2.5 h ago
            longRun.start(now.minusSeconds(2 * 3600 + 1800));
            ExportJob failed = job(now.minusSeconds(60));
            failed.fail("EXPORT_FAILED", now);
            ExportJob newest = job(now);
            for (ExportJob j : List.of(oldPending, lateStart, longRun, failed, newest)) jobRepository.save(j);

            assertThat(jobRepository.findRecent(50)).extracting(ExportJob::getId)
                    .containsExactly(newest.getId(), failed.getId(), longRun.getId(), lateStart.getId(),
                            oldPending.getId());
            assertThat(jobRepository.countActive()).isEqualTo(4);
            assertThat(jobRepository.findActive()).hasSize(4);
            assertThat(jobRepository.findStale(now.minusSeconds(2 * 3600))).extracting(ExportJob::getId)
                    .containsExactlyInAnyOrder(oldPending.getId(), longRun.getId());
        } finally {
            TenantContext.clear();
        }
    }

    private static ExportJob job(Instant createdAt) {
        return ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), ExportFormat.CSV, List.of("stores"),
                ExportFilter.none(), "f.csv", "text/csv; charset=UTF-8", createdAt);
    }

    @Test
    @DisplayName("direct export of tenant A contains only A's stores, every keyset page (ties on created_at included), and is audited DATA_EXPORTED")
    void directExportIsTenantIsolated() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String tagA = "A" + UUID.randomUUID().toString().substring(0, 8);
        String tagB = "B" + UUID.randomUUID().toString().substring(0, 8);
        insertStores(a, tagA, 2300, 1200); // 3 keyset pages of 1000, 1200 rows tie on created_at
        insertStores(b, tagB, 5, 0);

        String csv = directCsv(a, ExportFilter.none());

        List<String> rows = dataLines(csv);
        assertThat(rows).hasSize(2301); // + the seeded « Ma Boutique »
        assertThat(csv).doesNotContain(tagB);
        assertThat(rows.stream().filter(l -> l.contains(tagA + "-")).count()).isEqualTo(2300);
        assertThat(rows.stream().map(l -> l.substring(0, 36)).distinct().count()).as("no duplicate across pages")
                .isEqualTo(2301);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + a + "\".audit_log WHERE action = 'DATA_EXPORTED' "
                + "AND entity_type = 'Export'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + b + "\".audit_log WHERE action = 'DATA_EXPORTED'",
                Integer.class)).isZero();

        String csvB = directCsv(b, ExportFilter.none());
        assertThat(dataLines(csvB)).hasSize(6);
        assertThat(csvB).doesNotContain(tagA);
    }

    @Test
    @DisplayName("store filter applies to the stores dataset; the period is ignored (reference dataset, v1s-18-2)")
    void filters() throws Exception {
        String a = newTenant();
        String tag = "F" + UUID.randomUUID().toString().substring(0, 8);
        insertStores(a, tag, 10, 0); // created 2026-01-01T00:00:00Z + i seconds
        UUID third = jdbc.queryForObject("SELECT id FROM \"" + a + "\".stores WHERE name = ?", UUID.class, tag + "-3");

        assertThat(dataLines(directCsv(a, new ExportFilter(third, null, null))))
                .singleElement().satisfies(l -> assertThat(l).startsWith(third.toString()).contains(tag + "-3"));
        assertThat(dataLines(directCsv(a, new ExportFilter(null,
                Instant.parse("2026-01-01T00:00:02Z"), Instant.parse("2026-01-01T00:00:05Z")))))
                .hasSize(11); // the 10 stores + the seeded « Ma Boutique », whatever their creation date
    }

    @Test
    @DisplayName("background job of tenant A: READY file with only A's rows; purge marks it EXPIRED, deletes it, download → 410")
    void backgroundJobAndPurge() throws Exception {
        String a = newTenant();
        String b = newTenant();
        String tagA = "J" + UUID.randomUUID().toString().substring(0, 8);
        String tagB = "K" + UUID.randomUUID().toString().substring(0, 8);
        insertStores(a, tagA, 1500, 0);
        insertStores(b, tagB, 3, 0);

        TenantContext.setCurrentTenant(a);
        ExportJob job = jobRepository.save(ExportJob.pending(UUID.randomUUID(), UUID.randomUUID(), ExportFormat.CSV,
                List.of("stores"), ExportFilter.none(), "keevo-stores.csv", "text/csv; charset=UTF-8", Instant.now()));
        TenantContext.clear();

        jobRunner.run(a, job.getId());
        assertThat(TenantContext.getCurrentTenant()).isNull();

        TenantContext.setCurrentTenant(a);
        ExportJob ready = jobRepository.findById(job.getId()).orElseThrow();
        TenantContext.clear();
        filesToDelete.add(ready.getStoragePath());
        assertThat(ready.getStatus()).isEqualTo(ExportJobStatus.READY);
        assertThat(ready.getRowCount()).isEqualTo(1501);
        assertThat(ready.getExpiresAt()).isEqualTo(ready.getCompletedAt().plusSeconds(24 * 3600));

        String csv;
        try (InputStream in = storage.read(ready.getStoragePath())) {
            csv = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(dataLines(csv)).hasSize(1501);
        assertThat(csv).contains(tagA + "-1499").doesNotContain(tagB);

        // tenant B cannot see A's job
        TenantContext.setCurrentTenant(b);
        try {
            assertThat(jobRepository.findById(job.getId())).isEmpty();
        } finally {
            TenantContext.clear();
        }

        // expire it, purge, download → 410
        jdbc.update("UPDATE \"" + a + "\".export_jobs SET expires_at = now() - interval '1 minute' WHERE id = ?",
                job.getId());
        purgeScheduler.purgeTenant(a);

        assertThat(jdbc.queryForObject("SELECT status FROM \"" + a + "\".export_jobs WHERE id = ?", String.class,
                job.getId())).isEqualTo("EXPIRED");
        assertThat(storage.exists(ready.getStoragePath())).isFalse();
        TenantContext.setCurrentTenant(a);
        try {
            assertThatThrownBy(() -> downloadExport.open(job.getId()))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).getDomainCode()).isEqualTo("EXPORT_EXPIRED");
        } finally {
            TenantContext.clear();
        }
    }
}
