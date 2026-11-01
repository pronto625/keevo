package com.keevo.shared.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.job.AdminJobs;
import com.keevo.shared.job.AdminJobs.Kind;
import com.keevo.shared.job.AdminJobs.Status;
import com.keevo.shared.job.JobFailedException;
import com.keevo.shared.job.application.port.AdminJobStore;
import com.keevo.shared.module.ModuleBackfill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Story 16.4 — {@link AdminJobRunner} on an in-memory {@code admin_jobs} with a manual executor: start answers at once
 * (job QUEUED), the work runs on the executor (RUNNING → progress → SUCCEEDED with its JSON report), one job of a kind
 * at a time (409, French), failures end FAILED with a French message (JobFailedException's own, generic otherwise),
 * boot marks unfinished jobs « Interrompu par un redémarrage ».
 */
@DisplayName("AdminJobRunner (Story 16.4)")
public class AdminJobRunnerTest {

    private final InMemoryStore store = new InMemoryStore();
    private final List<Runnable> queued = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AdminJobRunner runner = new AdminJobRunner(store, json, queued::add);
    private static final UUID ACTOR = UUID.randomUUID();

    /** {@code public.admin_jobs} in memory, with the "one QUEUED/RUNNING job per kind" index. */
    public static final class InMemoryStore implements AdminJobStore {
        public final Map<UUID, StoredJob> jobs = new LinkedHashMap<>();
        final List<String> progress = new ArrayList<>();
        long clock = 1_000;

        private Instant now() {
            return Instant.ofEpochMilli(clock++);
        }

        @Override
        public boolean insertQueued(UUID id, Kind kind, String paramsJson, UUID actor) {
            boolean busy = jobs.values().stream().anyMatch(j -> j.kind() == kind
                    && (j.status() == Status.QUEUED || j.status() == Status.RUNNING));
            if (busy) {
                return false;
            }
            jobs.put(id, new StoredJob(id, kind, paramsJson, Status.QUEUED, 0, null, null, actor, now(), null, null,
                    null));
            return true;
        }

        private void update(UUID id, java.util.function.UnaryOperator<StoredJob> change) {
            jobs.computeIfPresent(id, (k, j) -> change.apply(j));
        }

        RuntimeException failMarkRunning;
        RuntimeException failMarkFailed;

        @Override
        public boolean markRunning(UUID id) {
            if (failMarkRunning != null) {
                throw failMarkRunning;
            }
            StoredJob job = jobs.get(id);
            if (job == null || job.status() != Status.QUEUED) {
                return false;
            }
            update(id, j -> new StoredJob(j.id(), j.kind(), j.paramsJson(), Status.RUNNING, 0, null, null, j.actor(),
                    j.createdAt(), now(), null, null));
            return true;
        }

        private boolean is(UUID id, Status... statuses) {
            StoredJob job = jobs.get(id);
            return job != null && java.util.Arrays.asList(statuses).contains(job.status());
        }

        @Override
        public void progress(UUID id, int processed, int total) {
            progress.add(processed + "/" + total);
            if (!is(id, Status.RUNNING)) {
                return;
            }
            update(id, j -> new StoredJob(j.id(), j.kind(), j.paramsJson(), j.status(), processed, total, null,
                    j.actor(), j.createdAt(), j.startedAt(), null, null));
        }

        @Override
        public void markSucceeded(UUID id, String reportJson) {
            if (!is(id, Status.RUNNING)) {
                return;
            }
            update(id, j -> new StoredJob(j.id(), j.kind(), j.paramsJson(), Status.SUCCEEDED,
                    j.total() != null ? j.total() : j.processed(), j.total(), reportJson, j.actor(), j.createdAt(),
                    j.startedAt(), now(), null));
        }

        @Override
        public void markFailed(UUID id, String error) {
            if (failMarkFailed != null) {
                throw failMarkFailed;
            }
            if (!is(id, Status.QUEUED, Status.RUNNING)) {
                return;
            }
            update(id, j -> new StoredJob(j.id(), j.kind(), j.paramsJson(), Status.FAILED, j.processed(), j.total(),
                    null, j.actor(), j.createdAt(), j.startedAt(), now(), error));
        }

        @Override
        public void delete(UUID id) {
            if (is(id, Status.QUEUED)) {
                jobs.remove(id);
            }
        }

        @Override
        public int failUnfinished(String error, Instant createdBefore) {
            List<UUID> ids = jobs.values().stream()
                    .filter(j -> j.status() == Status.QUEUED || j.status() == Status.RUNNING)
                    .filter(j -> j.createdAt().isBefore(createdBefore)).map(StoredJob::id)
                    .toList();
            ids.forEach(id -> markFailed(id, error));
            return ids.size();
        }

        @Override
        public Optional<StoredJob> find(UUID id) {
            return Optional.ofNullable(jobs.get(id));
        }

        @Override
        public List<StoredJob> latest(Kind kind, int limit) {
            return jobs.values().stream().filter(j -> kind == null || j.kind() == kind)
                    .sorted(Comparator.comparing(StoredJob::createdAt).reversed()).limit(limit).toList();
        }
    }

    private void runQueued() {
        List<Runnable> now = new ArrayList<>(queued);
        queued.clear();
        now.forEach(Runnable::run);
    }

    private AdminJobs.JobView job(UUID id) {
        return runner.find(id).orElseThrow();
    }

    @Test
    @DisplayName("start answers at once (QUEUED); the work runs on the executor: RUNNING, progress, SUCCEEDED + report")
    void startRunsInBackground() {
        ModuleBackfill.BackfillReport report = new ModuleBackfill.BackfillReport(true, 2, 1, 3, 0, 0, 0, 0,
                List.of(new ModuleBackfill.TenantChange("KV-AAA111", 3, 0, List.of())), List.of());
        UUID id = runner.start(Kind.MODULE_BACKFILL, Map.of("dryRun", true), ACTOR, progress -> {
            assertThat(job(store.jobs.keySet().iterator().next()).status()).isEqualTo(Status.RUNNING);
            progress.progress(0, 3);
            progress.progress(3, 3);
            return report;
        });

        assertThat(job(id).status()).isEqualTo(Status.QUEUED);
        assertThat(job(id).params()).containsEntry("dryRun", true);
        runQueued();

        AdminJobs.JobView done = job(id);
        assertThat(done.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(done.processed()).isEqualTo(3);
        assertThat(done.total()).isEqualTo(3);
        assertThat(done.report().get("tenants").get(0).get("tenantCode").asText()).isEqualTo("KV-AAA111");
        assertThat(done.report().get("rowsInserted").asInt()).isEqualTo(3);
        assertThat(store.progress).containsExactly("0/3", "3/3");
        assertThat(done.error()).isNull();
    }

    @Test
    @DisplayName("a second job of the same kind while one is queued or running → 409 ADMIN_JOB_RUNNING (French); "
            + "another kind is accepted; once finished the kind is free again")
    void oneJobPerKind() {
        UUID first = runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of());

        DomainException refused = catchThrowableOfType(DomainException.class,
                () -> runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of()));
        assertThat(refused.getDomainCode()).isEqualTo("ADMIN_JOB_RUNNING");
        assertThat(refused.getMessage()).contains("Migration des modules").contains("déjà en cours");
        assertThat(refused.getDetails()).containsEntry("label", "Migration des modules");

        UUID other = runner.start(Kind.PROFILE_RESYNC, Map.of(), ACTOR, progress -> Map.of());
        assertThat(other).isNotEqualTo(first);
        runQueued();
        assertThat(runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of())).isNotNull();
    }

    @Test
    @DisplayName("failures: JobFailedException → its French message; any other exception → generic French message")
    void failures() {
        UUID known = runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> {
            throw new JobFailedException("Une migration des modules est déjà en cours.");
        });
        UUID unexpected = runner.start(Kind.MODULE_PROVISION_ALL, Map.of(), ACTOR, progress -> {
            throw new IllegalStateException("SQL kv_abc123 boom");
        });
        runQueued();

        assertThat(job(known).status()).isEqualTo(Status.FAILED);
        assertThat(job(known).error()).isEqualTo("Une migration des modules est déjà en cours.");
        assertThat(job(unexpected).status()).isEqualTo(Status.FAILED);
        assertThat(job(unexpected).error()).isEqualTo(AdminJobRunner.UNEXPECTED).doesNotContain("kv_", "boom");
        assertThat(job(unexpected).finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("reservation: the slot is taken before the work exists; cancel frees it without any row left")
    void reservation() {
        AdminJobs.Reservation reservation = runner.reserve(Kind.PROFILE_RESYNC, Map.of("planType", "FREE"), ACTOR);
        assertThat(catchThrowableOfType(DomainException.class,
                () -> runner.reserve(Kind.PROFILE_RESYNC, Map.of(), ACTOR)).getDomainCode())
                .isEqualTo("ADMIN_JOB_RUNNING");
        reservation.cancel();
        assertThat(store.jobs).isEmpty();
        assertThat(runner.reserve(Kind.PROFILE_RESYNC, Map.of(), ACTOR).id()).isNotNull();
    }

    @Test
    @DisplayName("boot: queued / running jobs cut by the restart → FAILED « Interrompu par un redémarrage », never "
            + "resumed; finished jobs untouched")
    void restartMarksInterrupted() {
        UUID done = runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of());
        runQueued();
        UUID running = runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of());
        store.markRunning(running);
        UUID waiting = runner.start(Kind.PROFILE_RESYNC, Map.of(), ACTOR, progress -> Map.of());
        queued.clear(); // the restart loses the executor's queue

        runner.failInterruptedJobs();

        assertThat(job(running).status()).isEqualTo(Status.FAILED);
        assertThat(job(running).error()).isEqualTo("Interrompu par un redémarrage");
        assertThat(job(waiting).status()).isEqualTo(Status.FAILED);
        assertThat(job(done).status()).isEqualTo(Status.SUCCEEDED);
    }

    @Test
    @DisplayName("latest: newest first, filtered by kind, bounded")
    void latest() {
        runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of());
        runQueued();
        UUID last = runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of());
        runner.start(Kind.PROFILE_RESYNC, Map.of(), ACTOR, progress -> Map.of());

        assertThat(runner.latest(Kind.MODULE_BACKFILL, 20)).extracting(AdminJobs.JobView::id).first().isEqualTo(last);
        assertThat(runner.latest(Kind.MODULE_BACKFILL, 20)).hasSize(2);
        assertThat(runner.latest(null, 1)).hasSize(1);
    }

    @Test
    @DisplayName("markRunning fails → the job is marked FAILED (the kind is free again), the work never runs")
    void markRunningFails() {
        UUID id = runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> {
            throw new AssertionError("must not run");
        });
        store.failMarkRunning = new IllegalStateException("db down");
        runQueued();
        store.failMarkRunning = null;

        assertThat(job(id).status()).isEqualTo(Status.FAILED);
        assertThat(runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of())).isNotNull();
    }

    @Test
    @DisplayName("executor full and markFailed fails → the queued row is removed (503), the kind is free again")
    void rejectedAndMarkFailedFails() {
        AdminJobRunner full = new AdminJobRunner(store, json, r -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        store.failMarkFailed = new IllegalStateException("db down");

        DomainException refused = catchThrowableOfType(DomainException.class,
                () -> full.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of()));
        assertThat(refused.getDomainCode()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(store.jobs).isEmpty();
        store.failMarkFailed = null;

        AdminJobRunner rejecting = new AdminJobRunner(store, json, r -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        catchThrowableOfType(DomainException.class,
                () -> rejecting.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of()));
        assertThat(store.jobs.values()).extracting(AdminJobStore.StoredJob::status).containsExactly(Status.FAILED);
    }

    @Test
    @DisplayName("restart marking leaves jobs created since this instance started; a job failed by it is never "
            + "turned SUCCEEDED afterwards")
    void restartMarkingScopedToOlderJobs() {
        store.clock = java.time.Instant.now().plusSeconds(3600).toEpochMilli(); // created after the start
        UUID recent = runner.start(Kind.MODULE_BACKFILL, Map.of(), ACTOR, progress -> Map.of("ok", true));
        runner.failInterruptedJobs();
        assertThat(job(recent).status()).isEqualTo(Status.QUEUED);

        store.markFailed(recent, AdminJobs.INTERRUPTED); // e.g. marked by a restart meanwhile
        runQueued();
        assertThat(job(recent).status()).isEqualTo(Status.FAILED);
        assertThat(job(recent).error()).isEqualTo("Interrompu par un redémarrage");
    }
}
