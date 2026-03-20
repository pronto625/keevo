package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncOperationsLogJpaEntity;
import com.keevo.sync.sync.adapter.out.persistence.jpa.SyncOperationsLogSpringRepository;
import com.keevo.sync.sync.domain.model.SyncOperationsLogEntry;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.springframework.stereotype.Component;

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
}
