package com.keevo.sync.sync.domain.port.out;

import com.keevo.sync.sync.domain.model.SyncErrorLogEntry;

import java.time.Instant;
import java.util.Optional;

/**
 * SyncErrorLogRepository — port-out interface for rejected operation payload persistence.
 *
 * <p>Implemented by {@code SyncErrorLogRepositoryAdapter} which uses JdbcTemplate
 * against the tenant schema (NOT public). TenantContext must be set before use.
 *
 * <p>Story 5.5 — AC5.
 */
public interface SyncErrorLogRepository {

    void save(SyncErrorLogEntry entry);

    Optional<SyncErrorLogEntry> findByOperationId(String operationId);

    int deleteOlderThan(Instant cutoff);
}
