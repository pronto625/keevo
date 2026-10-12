package com.keevo.reporting.export.adapter.out.persistence;

import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.shared.application.port.export.ExportFilter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter of {@link ExportJobRepository} on the tenant-routed JPA {@code export_jobs} table
 * (Story v1s-18-1). Each call is its own short transaction.
 */
@Component
public class ExportJobRepositoryAdapter implements ExportJobRepository {

    static final int RECENT_LIMIT = 50;
    private static final List<String> ACTIVE =
            List.of(ExportJobStatus.PENDING.name(), ExportJobStatus.RUNNING.name());

    private final ExportJobSpringRepository repository;

    public ExportJobRepositoryAdapter(ExportJobSpringRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public ExportJob save(ExportJob job) {
        return toDomain(repository.save(toEntity(job)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExportJob> findById(UUID id) {
        return repository.findById(id).map(ExportJobRepositoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportJob> findRecent(int limit) {
        return repository.findTop50ByOrderByCreatedAtDesc().stream()
                .limit(Math.min(limit, RECENT_LIMIT))
                .map(ExportJobRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countActive() {
        return repository.countByStatusIn(ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportJob> findReadyExpiredAt(Instant now) {
        return repository.findByStatusAndExpiresAtLessThanEqual(ExportJobStatus.READY.name(), now).stream()
                .map(ExportJobRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportJob> findStale(Instant cutoff) {
        return repository.findStale(cutoff).stream().map(ExportJobRepositoryAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportJob> findActive() {
        return repository.findByStatusIn(ACTIVE).stream().map(ExportJobRepositoryAdapter::toDomain).toList();
    }

    static ExportJobJpaEntity toEntity(ExportJob j) {
        ExportFilter f = j.getFilter();
        return new ExportJobJpaEntity(j.getId(), j.getRequestedBy(), j.getStatus().name(), j.getFormat().name(),
                String.join(",", j.getDatasets()), f.storeId(), f.from(), f.to(), j.getRowCount(),
                j.getFileName(), j.getFileSize(), j.getContentType(), j.getStoragePath(), j.getErrorCode(),
                j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt(), j.getExpiresAt());
    }

    static ExportJob toDomain(ExportJobJpaEntity e) {
        List<String> datasets = e.getDatasets() == null || e.getDatasets().isBlank()
                ? List.of() : Arrays.asList(e.getDatasets().split(","));
        return new ExportJob(e.getId(), e.getRequestedBy(), ExportJobStatus.valueOf(e.getStatus()),
                ExportFormat.valueOf(e.getFormat()), datasets,
                new ExportFilter(e.getStoreId(), e.getPeriodFrom(), e.getPeriodTo()),
                e.getRowCount(), e.getFileName(), e.getFileSize(), e.getContentType(), e.getStoragePath(),
                e.getErrorCode(), e.getCreatedAt(), e.getStartedAt(), e.getCompletedAt(), e.getExpiresAt());
    }
}
