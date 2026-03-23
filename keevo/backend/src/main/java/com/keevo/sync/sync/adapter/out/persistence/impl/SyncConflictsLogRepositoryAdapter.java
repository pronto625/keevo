package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncConflictsLogJpaEntity;
import com.keevo.sync.sync.adapter.out.persistence.jpa.SyncConflictsLogSpringRepository;
import com.keevo.sync.sync.domain.model.SyncConflictsLogEntry;
import com.keevo.sync.sync.domain.port.out.SyncConflictsLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SyncConflictsLogRepositoryAdapter implements SyncConflictsLogRepository {

    private final SyncConflictsLogSpringRepository jpa;

    public SyncConflictsLogRepositoryAdapter(SyncConflictsLogSpringRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(SyncConflictsLogEntry entry) {
        jpa.save(new SyncConflictsLogJpaEntity(
                entry.id(),
                entry.operationId(),
                entry.operationType(),
                entry.entityId(),
                entry.entityType(),
                entry.conflictType(),
                entry.strategy(),
                entry.conflictData(),
                entry.resolvedAt(),
                entry.actorId()));
    }

    @Override
    public List<SyncConflictsLogEntry> findAll(int limit, int offset) {
        return jpa.findAllByOrderByResolvedAtDesc(PageRequest.of(offset / Math.max(limit, 1), limit))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<SyncConflictsLogEntry> findByEntityId(String entityId) {
        return jpa.findByEntityId(entityId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private SyncConflictsLogEntry toDomain(SyncConflictsLogJpaEntity e) {
        return new SyncConflictsLogEntry(
                e.getId(),
                e.getOperationId(),
                e.getOperationType(),
                e.getEntityId(),
                e.getEntityType(),
                e.getConflictType(),
                e.getStrategy(),
                e.getConflictData(),
                e.getResolvedAt(),
                e.getActorId());
    }
}
