package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.adapter.out.persistence.entity.SyncOperationsLogJpaEntity;
import com.keevo.sync.sync.adapter.out.persistence.jpa.SyncOperationsLogSpringRepository;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.sync.sync.domain.model.SyncOperationsLogEntry;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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
    public boolean isSettled(String operationId) {
        return jpa.findById(operationId)
                .map(e -> !SyncOperationStatus.REJECTED.name().equals(e.getStatus()))
                .orElse(false);
    }

    @Override
    public boolean isSettledElseClaim(String operationId, String operationType, String entityId,
                                      Instant clientTimestamp) {
        Instant now = Instant.now();
        // Placeholder only; the final save overwrites it. Never null: a null Instant
        // in a native insert is bound without a type and rejected by PostgreSQL.
        return jpa.claim(operationId, operationType, entityId, now,
                clientTimestamp != null ? clientTimestamp : now) == 0;
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
                entry.clientTimestamp(),
                deviceIdColumn(entry.deviceId())));
    }

    /** The column holds 100 characters; a longer client-supplied id is cut, never rejected. */
    private static String deviceIdColumn(String deviceId) {
        return deviceId != null && deviceId.length() > 100 ? deviceId.substring(0, 100) : deviceId;
    }

    @Override
    public Map<String, String> findMergedProductTargets(Collection<String> clientIds) {
        Map<String, String> targets = new HashMap<>();
        if (clientIds.isEmpty()) return targets;
        for (var e : jpa.findMergedInto(clientIds)) {
            targets.put(e.getEntityId(), e.getErrorReason().substring("MERGED_INTO:".length()));
        }
        return targets;
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
                        e.getClientTimestamp(),
                        e.getDeviceId()));
    }
}
