package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncOperationsLogJpaEntity;
import com.keevo.sync.sync.adapter.out.persistence.jpa.SyncOperationsLogSpringRepository;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import com.keevo.sync.sync.domain.model.SyncOperationsLogEntry;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SyncOperationsLogRepositoryAdapter implements SyncOperationsLogRepository {

    private final SyncOperationsLogSpringRepository jpa;

    public SyncOperationsLogRepositoryAdapter(SyncOperationsLogSpringRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existsById(String operationId) {
        return jpa.existsById(operationId);
    }

    @Override
    public void save(SyncOperationsLogEntry entry) {
        jpa.save(new SyncOperationsLogJpaEntity(
                entry.id(),
                entry.operationType(),
                entry.entityId(),
                entry.status().name(),
                entry.errorReason(),
                entry.processedAt(),
                entry.clientTimestamp()));
    }

    @Override
    public Optional<SyncOperationsLogEntry> findPreviousAppliedByEntityId(String entityId, String excludeOperationId) {
        return jpa.findPreviousAppliedByEntityId(entityId, excludeOperationId)
                .map(e -> new SyncOperationsLogEntry(
                        e.getId(),
                        e.getOperationType(),
                        e.getEntityId(),
                        SyncOperationStatus.valueOf(e.getStatus()),
                        e.getErrorReason(),
                        e.getProcessedAt(),
                        e.getClientTimestamp()));
    }
}
