package com.keevo.reporting.export.application.service;

import com.keevo.reporting.export.adapter.out.persistence.ExportJobJpaEntityTestAccess;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link ExportJobRepository}: stores copies, like a database would. */
class InMemoryExportJobRepository implements ExportJobRepository {

    final Map<UUID, ExportJob> rows = new ConcurrentHashMap<>();

    @Override
    public ExportJob save(ExportJob job) {
        rows.put(job.getId(), ExportJobJpaEntityTestAccess.copy(job));
        return ExportJobJpaEntityTestAccess.copy(job);
    }

    @Override
    public Optional<ExportJob> findById(UUID id) {
        return Optional.ofNullable(rows.get(id)).map(ExportJobJpaEntityTestAccess::copy);
    }

    @Override
    public List<ExportJob> findRecent(int limit) {
        return rows.values().stream().sorted(Comparator.comparing(ExportJob::getCreatedAt).reversed())
                .limit(limit).map(ExportJobJpaEntityTestAccess::copy).toList();
    }

    @Override
    public long countActive() {
        return rows.values().stream().filter(j -> j.getStatus().isActive()).count();
    }

    @Override
    public List<ExportJob> findReadyExpiredAt(Instant now) {
        return rows.values().stream()
                .filter(j -> j.getStatus() == ExportJobStatus.READY && !j.getExpiresAt().isAfter(now))
                .map(ExportJobJpaEntityTestAccess::copy).toList();
    }

    @Override
    public List<ExportJob> findStale(Instant cutoff) {
        return rows.values().stream()
                .filter(j -> (j.getStatus() == ExportJobStatus.PENDING && j.getCreatedAt().isBefore(cutoff))
                        || (j.getStatus() == ExportJobStatus.RUNNING && j.getStartedAt().isBefore(cutoff)))
                .map(ExportJobJpaEntityTestAccess::copy).toList();
    }

    @Override
    public List<ExportJob> findActive() {
        return rows.values().stream().filter(j -> j.getStatus().isActive())
                .map(ExportJobJpaEntityTestAccess::copy).toList();
    }
}
