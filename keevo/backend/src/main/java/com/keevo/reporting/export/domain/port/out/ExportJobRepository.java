package com.keevo.reporting.export.domain.port.out;

import com.keevo.reporting.export.domain.model.ExportJob;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the current tenant's export jobs (Story v1s-18-1). */
public interface ExportJobRepository {

    ExportJob save(ExportJob job);

    Optional<ExportJob> findById(UUID id);

    /** Newest first, at most {@code limit}. */
    List<ExportJob> findRecent(int limit);

    /** Jobs in PENDING or RUNNING. */
    long countActive();

    /** READY jobs whose {@code expiresAt} is at or before {@code now}. */
    List<ExportJob> findReadyExpiredAt(Instant now);

    /**
     * Stale jobs: PENDING created before {@code cutoff}, RUNNING started before {@code cutoff}
     * (a long-queued job that has just started is not stale).
     */
    List<ExportJob> findStale(Instant cutoff);

    /** Every PENDING / RUNNING job. */
    List<ExportJob> findActive();
}
