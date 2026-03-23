package com.keevo.sync.sync.application.strategy;

import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * LastWriteWinsStrategy — Detects when a non-stock entity update overwrites a previous one.
 * Both updates are applied (the later one wins by timestamp order).
 * The overwrite is logged for OWNER audit.
 */
@Component
public class LastWriteWinsStrategy implements ConflictResolutionStrategy {

    private final SyncOperationsLogRepository logRepository;
    private final ApplicationEventPublisher eventPublisher;

    public LastWriteWinsStrategy(SyncOperationsLogRepository logRepository, ApplicationEventPublisher eventPublisher) {
        this.logRepository = logRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                   UUID actorId, String tenantId) {
        if (handlerResult.status() != SyncOperationStatus.APPLIED) return null;
        if (operation.entityId() == null) return null;

        var previousOp = logRepository.findPreviousAppliedByEntityId(
                operation.entityId(), operation.operationId());

        if (previousOp.isEmpty()) return null;

        var prev = previousOp.get();
        Map<String, Object> conflictData = new LinkedHashMap<>();
        conflictData.put("entityType", extractEntityType(operation.operationType()));
        conflictData.put("entityId", operation.entityId());
        conflictData.put("overwrittenTimestamp", prev.clientTimestamp() != null ? prev.clientTimestamp().toString() : null);
        conflictData.put("winnerTimestamp", operation.clientTimestamp() != null ? operation.clientTimestamp().toString() : null);

        // Publish SyncOverwrittenEvent for audit (AC3)
        eventPublisher.publishEvent(new SyncOverwrittenEvent(
                operation.operationId(),
                extractEntityType(operation.operationType()),
                operation.entityId(),
                prev.clientTimestamp(),
                operation.clientTimestamp(),
                tenantId,
                Instant.now()));

        return new ConflictResult("LAST_WRITE_WINS", "LAST_WRITE_WINS", conflictData);
    }

    private String extractEntityType(String operationType) {
        if (operationType == null) return "UNKNOWN";
        if (operationType.contains("PRODUCT")) return "PRODUCT";
        if (operationType.contains("CLIENT")) return "CLIENT";
        if (operationType.contains("SUPPLIER")) return "SUPPLIER";
        if (operationType.contains("EMPLOYEE")) return "EMPLOYEE";
        if (operationType.contains("STORE")) return "STORE";
        return operationType;
    }
}
