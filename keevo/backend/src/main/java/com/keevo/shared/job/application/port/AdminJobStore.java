package com.keevo.shared.job.application.port;

import com.keevo.shared.job.AdminJobs.Kind;
import com.keevo.shared.job.AdminJobs.Status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 16.4 — persistence of {@code public.admin_jobs}. Each call is its own statement (auto-commit): progress is
 * visible to the console while the job runs. Implemented by {@code shared.job.adapter.out.persistence.JdbcAdminJobStore}.
 */
public interface AdminJobStore {

    /**
     * Inserts a {@code QUEUED} job.
     *
     * @return {@code false} when a job of this kind is already queued or running (nothing inserted)
     */
    boolean insertQueued(UUID id, Kind kind, String paramsJson, UUID actor);

    /** {@code QUEUED} → {@code RUNNING}, {@code started_at = now()}; {@code false} when the row is not queued any more. */
    boolean markRunning(UUID id);

    /** Progress of a running job (a job no longer {@code RUNNING} is not touched). */
    void progress(UUID id, int processed, int total);

    /** {@code RUNNING} → {@code SUCCEEDED} with its report, {@code finished_at = now()} (never over a failed job). */
    void markSucceeded(UUID id, String reportJson);

    /** {@code QUEUED}/{@code RUNNING} → {@code FAILED} with a French reason, {@code finished_at = now()}. */
    void markFailed(UUID id, String error);

    /** Removes a job that never ran (cancelled reservation). */
    void delete(UUID id);

    /**
     * Marks every {@code QUEUED}/{@code RUNNING} job created before {@code createdBefore} {@code FAILED} with this
     * reason (jobs started by this instance since its start are left alone); returns how many.
     */
    int failUnfinished(String error, java.time.Instant createdBefore);

    /** The job; empty when unknown or stored with a kind / status this version does not know. */
    Optional<StoredJob> find(UUID id);

    /** Newest first; {@code kind == null}: every kind; rows with an unknown kind / status are skipped. */
    List<StoredJob> latest(Kind kind, int limit);

    /** A stored job (JSON columns as text). */
    record StoredJob(UUID id, Kind kind, String paramsJson, Status status, int processed, Integer total,
                     String reportJson, UUID actor, Instant createdAt, Instant startedAt, Instant finishedAt,
                     String error) {
    }
}
