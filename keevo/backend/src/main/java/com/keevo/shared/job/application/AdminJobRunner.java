package com.keevo.shared.job.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.job.AdminJobs;
import com.keevo.shared.job.JobFailedException;
import com.keevo.shared.job.ProgressListener;
import com.keevo.shared.job.application.port.AdminJobStore;
import com.keevo.shared.job.application.port.AdminJobStore.StoredJob;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AdminJobs} of the core (Story 16.4): jobs persisted in {@code public.admin_jobs} ({@link AdminJobStore}), run
 * one after the other on a single-thread executor of this node (queue bounded to {@value #QUEUE_CAPACITY}). Progress
 * is written as the work reports it (at most every {@value #PROGRESS_EVERY_MS} ms, and always at the end); the report
 * is stored as JSON. A failure ends the job {@code FAILED} with the {@link JobFailedException} message, or a generic
 * French message for any other exception (logged with its class only). At boot, every job still queued or running
 * (cut by the restart) is marked {@code FAILED} « Interrompu par un redémarrage ».
 */
@Service
public class AdminJobRunner implements AdminJobs {

    private static final Logger log = LoggerFactory.getLogger(AdminJobRunner.class);

    static final int QUEUE_CAPACITY = 8;
    static final long PROGRESS_EVERY_MS = 500;
    static final String UNEXPECTED = "La tâche s'est arrêtée sur une erreur inattendue. Vous pouvez la relancer ; "
            + "si le problème persiste, contactez l'équipe technique.";
    static final String QUEUE_FULL = "Trop de tâches en attente : réessayez dans quelques minutes.";
    private static final int MAX_LIMIT = 100;

    private final AdminJobStore store;
    private final ObjectMapper json;
    private final Executor executor;
    private final ExecutorService ownExecutor;
    /** When this instance started: only older unfinished jobs are cut by a restart. */
    private final java.time.Instant instanceStart = java.time.Instant.now();

    @Autowired
    public AdminJobRunner(AdminJobStore store, ObjectMapper json) {
        this(store, json, null);
    }

    /** @param executor where the jobs run; {@code null}: the bounded single-thread executor of this node */
    public AdminJobRunner(AdminJobStore store, ObjectMapper json, Executor executor) {
        this.store = store;
        this.json = json;
        if (executor != null) {
            this.executor = executor;
            this.ownExecutor = null;
        } else {
            AtomicInteger threads = new AtomicInteger();
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
                        Thread t = new Thread(r, "admin-job-" + threads.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
            this.executor = pool;
            this.ownExecutor = pool;
        }
    }

    /** Jobs cut by the previous shutdown are never resumed: they are marked failed. */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedJobs() {
        try {
            int count = store.failUnfinished(INTERRUPTED, instanceStart);
            if (count > 0) {
                log.warn("Admin jobs: {} job(s) interrupted by the restart marked FAILED", count);
            }
        } catch (RuntimeException e) {
            log.error("Admin jobs: interrupted jobs could not be marked ({})", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownExecutor != null) {
            ownExecutor.shutdownNow(); // a running job stays RUNNING: marked interrupted at the next boot
        }
    }

    @Override
    public Reservation reserve(Kind kind, Map<String, Object> params, UUID actor) {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
        UUID id = UUID.randomUUID();
        if (!store.insertQueued(id, kind, write(params != null ? params : Map.of()), actor)) {
            throw new DomainException(ErrorCode.ADMIN_JOB_RUNNING,
                    "« " + kind.label() + " » est déjà en cours. Attendez la fin de la tâche avant d'en relancer une.",
                    Map.of("label", kind.label()));
        }
        log.info("[ADMIN AUDIT] Job queued: actor={} kind={} params={}", actor, kind, params);
        return new Ticket(id, kind);
    }

    private final class Ticket implements Reservation {

        private final UUID id;
        private final Kind kind;

        Ticket(UUID id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public void submit(Work work) {
            try {
                executor.execute(() -> execute(id, kind, work));
            } catch (RejectedExecutionException e) {
                release(id, QUEUE_FULL);
                throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, QUEUE_FULL);
            }
        }

        @Override
        public void cancel() {
            store.delete(id);
        }
    }

    private void execute(UUID id, Kind kind, Work work) {
        try {
            if (!store.markRunning(id)) {
                log.warn("Admin job {} ({}) not started: no longer queued", id, kind);
                return;
            }
        } catch (RuntimeException e) {
            log.error("Admin job {} ({}) could not start ({})", id, kind, e.getClass().getSimpleName());
            release(id, UNEXPECTED);
            return;
        }
        long started = System.currentTimeMillis();
        try {
            Object report = work.run(new ThrottledProgress(id));
            store.markSucceeded(id, write(report));
            log.info("Admin job {} ({}) succeeded in {} ms", id, kind, System.currentTimeMillis() - started);
        } catch (JobFailedException e) {
            log.warn("Admin job {} ({}) failed: {}", id, kind, e.getMessage());
            fail(id, e.getMessage());
        } catch (RuntimeException | Error e) {
            log.error("Admin job {} ({}) failed ({})", id, kind, e.getClass().getSimpleName(), e);
            fail(id, UNEXPECTED);
        }
    }

    /**
     * Frees the kind's slot of a job that will never run: best-effort {@code FAILED}, else the row is removed (a
     * queued row left behind would block the kind until the next restart).
     */
    private void release(UUID id, String message) {
        try {
            store.markFailed(id, message);
            return;
        } catch (RuntimeException e) {
            log.error("Admin job {}: could not be marked failed ({}) — removing it", id, e.getClass().getSimpleName());
        }
        try {
            store.delete(id);
        } catch (RuntimeException e) {
            log.error("Admin job {}: could not be removed ({}) — its kind stays blocked until the next restart", id,
                    e.getClass().getSimpleName());
        }
    }

    private void fail(UUID id, String message) {
        try {
            store.markFailed(id, message);
        } catch (RuntimeException e) {
            log.error("Admin job {}: failure could not be recorded ({})", id, e.getClass().getSimpleName());
        }
    }

    /** Writes the progress at most every {@link #PROGRESS_EVERY_MS} ms, and always the last item. */
    private final class ThrottledProgress implements ProgressListener {

        private final UUID id;
        private long lastWrite;

        ThrottledProgress(UUID id) {
            this.id = id;
        }

        @Override
        public void progress(int processed, int total) {
            long now = System.currentTimeMillis();
            if (processed != 0 && processed < total && now - lastWrite < PROGRESS_EVERY_MS) {
                return;
            }
            lastWrite = now;
            try {
                store.progress(id, Math.max(0, processed), Math.max(0, total));
            } catch (RuntimeException e) {
                log.warn("Admin job {}: progress not recorded ({})", id, e.getClass().getSimpleName());
            }
        }
    }

    @Override
    public Optional<JobView> find(UUID id) {
        return id == null ? Optional.empty() : store.find(id).map(this::view);
    }

    @Override
    public List<JobView> latest(Kind kind, int limit) {
        return store.latest(kind, Math.max(1, Math.min(limit, MAX_LIMIT))).stream().map(this::view).toList();
    }

    private JobView view(StoredJob job) {
        return new JobView(job.id(), job.kind(), readMap(job.paramsJson()), job.status(), job.processed(),
                job.total(), readTree(job.reportJson()), job.actor(), job.createdAt(), job.startedAt(),
                job.finishedAt(), job.error());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Job payload not serialisable", e);
        }
    }

    private Map<String, Object> readMap(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = json.readValue(text, new TypeReference<java.util.LinkedHashMap<String, Object>>() { });
            map.values().removeIf(java.util.Objects::isNull);
            return map;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private JsonNode readTree(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
