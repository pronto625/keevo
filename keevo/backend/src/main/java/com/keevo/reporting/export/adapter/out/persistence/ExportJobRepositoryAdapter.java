package com.keevo.reporting.export.adapter.out.persistence;

import com.keevo.reporting.export.domain.model.ExportFormat;
import com.keevo.reporting.export.domain.model.ExportJob;
import com.keevo.reporting.export.domain.model.ExportJobStatus;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.shared.application.port.export.ExportFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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

    private static final Logger log = LoggerFactory.getLogger(ExportJobRepositoryAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> CRITERIA_TYPE = new TypeReference<>() {
    };

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
                String.join(",", j.getDatasets()), f.storeId(), f.from(), f.to(), criteriaJson(f.criteria()),
                j.getRowCount(),
                j.getFileName(), j.getFileSize(), j.getContentType(), j.getStoragePath(), j.getErrorCode(),
                j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt(), j.getExpiresAt());
    }

    /** Story v1s-18-2 — criteria persisted as a JSON object of strings (null when none). */
    static String criteriaJson(Map<String, String> criteria) {
        if (criteria == null || criteria.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(new TreeMap<>(criteria));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise export criteria", e);
        }
    }

    /** A corrupt / non-object value never breaks job listing: logged, read as no criteria. */
    static Map<String, String> criteria(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> parsed = JSON.readValue(json, CRITERIA_TYPE);
            return parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException e) {
            log.warn("EXPORT_JOB_CRITERIA_CORRUPT — ignored: {}", e.getOriginalMessage());
            return Map.of();
        }
    }

    static ExportJob toDomain(ExportJobJpaEntity e) {
        List<String> datasets = e.getDatasets() == null || e.getDatasets().isBlank()
                ? List.of() : Arrays.asList(e.getDatasets().split(","));
        return new ExportJob(e.getId(), e.getRequestedBy(), ExportJobStatus.valueOf(e.getStatus()),
                ExportFormat.valueOf(e.getFormat()), datasets,
                new ExportFilter(e.getStoreId(), e.getPeriodFrom(), e.getPeriodTo(), criteria(e.getCriteria())),
                e.getRowCount(), e.getFileName(), e.getFileSize(), e.getContentType(), e.getStoragePath(),
                e.getErrorCode(), e.getCreatedAt(), e.getStartedAt(), e.getCompletedAt(), e.getExpiresAt());
    }
}
