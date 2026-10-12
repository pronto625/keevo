package com.keevo.reporting.export.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Data repository of {@code export_jobs} (Story v1s-18-1). */
public interface ExportJobSpringRepository extends JpaRepository<ExportJobJpaEntity, UUID> {

    List<ExportJobJpaEntity> findTop50ByOrderByCreatedAtDesc();

    long countByStatusIn(Collection<String> statuses);

    List<ExportJobJpaEntity> findByStatusAndExpiresAtLessThanEqual(String status, Instant now);

    @Query("SELECT j FROM ExportJobJpaEntity j WHERE (j.status = 'PENDING' AND j.createdAt < :cutoff) "
            + "OR (j.status = 'RUNNING' AND j.startedAt < :cutoff)")
    List<ExportJobJpaEntity> findStale(@Param("cutoff") Instant cutoff);

    List<ExportJobJpaEntity> findByStatusIn(Collection<String> statuses);
}
