package com.keevo.shared.job;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 16.4 — super-admin background jobs ({@code public.admin_jobs}): the long bulk operations of the « Modules »
 * console (module backfill, provisioning of one module for every tenant, plan-profile re-sync) run on one bounded
 * single-thread executor of this node, with their progress and final report stored, so the HTTP request that starts
 * one answers at once ({@code 202 {jobId}}) and the console polls the job.
 *
 * <p>One job of a kind at a time (database check): starting a second one while the first is queued or running is
 * refused with {@code ADMIN_JOB_RUNNING} (409, French). A job left queued or running by a restart is marked
 * {@code FAILED} « Interrompu par un redémarrage » at boot and never resumed. Reports keep the JSON shape of the
 * operation's own report (tenants named by code only).
 */
public interface AdminJobs {

    /** Error message of a job cut by a restart. */
    String INTERRUPTED = "Interrompu par un redémarrage";

    /** What a job does. */
    enum Kind {
        MODULE_BACKFILL("Migration des modules"),
        MODULE_PROVISION_ALL("Provisionnement d'un module"),
        PROFILE_RESYNC("Mise à jour des comptes d'une offre");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** French name of the job kind. */
        public String label() {
            return label;
        }
    }

    /** Where a job is. */
    enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED }

    /** The work of a job: returns its report (serialised as JSON), reports its progress. */
    @FunctionalInterface
    interface Work {
        Object run(ProgressListener progress);
    }

    /**
     * Queues a job and returns its id at once.
     *
     * @param params what the job was started with (shown on the console: no technical identifier beyond module ids)
     * @throws com.keevo.shared.domain.exception.DomainException {@code ADMIN_JOB_RUNNING} (409) when a job of this kind
     *         is already queued or running
     */
    default UUID start(Kind kind, Map<String, Object> params, UUID actor, Work work) {
        Reservation reservation = reserve(kind, params, actor);
        reservation.submit(work);
        return reservation.id();
    }

    /**
     * Takes the slot of the kind (a {@code QUEUED} row) without starting anything yet: for callers that must be sure
     * the job can run before they change anything (e.g. the plan profile is saved only once its re-sync is
     * reserved). Then {@link Reservation#submit} or {@link Reservation#cancel}.
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code ADMIN_JOB_RUNNING} (409)
     */
    Reservation reserve(Kind kind, Map<String, Object> params, UUID actor);

    /** The job with this id. */
    Optional<JobView> find(UUID id);

    /**
     * The latest jobs, newest first.
     *
     * @param kind  {@code null}: every kind
     * @param limit at most this many (1 … 100)
     */
    List<JobView> latest(Kind kind, int limit);

    /** A reserved job slot. */
    interface Reservation {

        UUID id();

        /** Starts the work on the job executor. */
        void submit(Work work);

        /** Frees the slot without running anything (the row is removed). */
        void cancel();
    }

    /**
     * A job.
     *
     * @param params      what it was started with
     * @param processed   items done so far
     * @param total       items to do ({@code null}: not known yet)
     * @param report      the final report ({@code null} until {@code SUCCEEDED})
     * @param error       French reason of a failure ({@code null} otherwise)
     */
    record JobView(UUID id, Kind kind, Map<String, Object> params, Status status, int processed, Integer total,
                   JsonNode report, UUID actor, Instant createdAt, Instant startedAt, Instant finishedAt,
                   String error) {

        public JobView {
            params = params != null ? Map.copyOf(params) : Map.of();
        }
    }
}
