package com.keevo.sync.sync.application.strategy;

import com.keevo.shared.application.port.sync.SyncOperation;
import com.keevo.shared.application.port.sync.SyncOperationResult;
import com.keevo.shared.application.port.sync.SyncOperationStatus;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.in.ConflictResolutionStrategy;
import com.keevo.sync.sync.domain.port.out.EntityLastModifiedRepository;
import com.keevo.sync.sync.domain.port.out.EntityLastModifiedRepository.LwwEntity;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * LastWriteWinsStrategy — non-stock entity updates (architecture: « last-write-wins par timestamp »).
 *
 * <p>Before the handler runs ({@link #beforeApply}), the operation's client timestamp is compared
 * with the moment the entity's current state was written:
 * <ul>
 *   <li>written by a sync operation (the latest APPLIED one on this entity was processed at or after
 *       the entity's {@code updated_at}): that operation's client timestamp — so a device's own
 *       sequential edits, pushed together, compare device time with device time;</li>
 *   <li>otherwise (REST / server-side change): the entity's {@code updated_at}.</li>
 * </ul>
 * An operation strictly older than that is <b>superseded</b>: not applied, acknowledged (the device
 * must not retry it forever) and logged ({@link #SUPERSEDED}). A null client timestamp, an equal or
 * newer one is applied as before.
 *
 * <p>An applied operation is logged as an overwrite ({@link #LAST_WRITE_WINS}) only for a real
 * concurrent change: the overwritten state came from another device or from the server, and was
 * written after the pushing device's last pull (so it had not seen it). Sequential edits from the
 * same device are not conflicts.
 *
 * <p>Device clocks are trusted as they are (no skew compensation).
 */
@Component
public class LastWriteWinsStrategy implements ConflictResolutionStrategy {

    public static final String LAST_WRITE_WINS = "LAST_WRITE_WINS";
    /** Conflict type of an outdated operation that was not applied. */
    public static final String SUPERSEDED = "LAST_WRITE_SUPERSEDED";
    /**
     * Reason of the APPLIED result (and its sync_operations_log row) of a superseded operation:
     * the row did not write the entity, so it must not count as its last write.
     */
    public static final String SUPERSEDED_REASON = "LWW_SUPERSEDED";

    private record Target(LwwEntity entity, String payloadKey) {}

    private static final Map<String, Target> TARGETS = Map.ofEntries(
            Map.entry("UPDATE_PRODUCT", new Target(LwwEntity.PRODUCT, "productId")),
            Map.entry("PRODUCT_UPDATE", new Target(LwwEntity.PRODUCT, "productId")),
            Map.entry("ARCHIVE_PRODUCT", new Target(LwwEntity.PRODUCT, "productId")),
            Map.entry("UNARCHIVE_PRODUCT", new Target(LwwEntity.PRODUCT, "productId")),
            Map.entry("UPDATE_CLIENT", new Target(LwwEntity.CLIENT, "clientId")),
            Map.entry("ARCHIVE_CLIENT", new Target(LwwEntity.CLIENT, "clientId")),
            Map.entry("UNARCHIVE_CLIENT", new Target(LwwEntity.CLIENT, "clientId")),
            Map.entry("UPDATE_SUPPLIER", new Target(LwwEntity.SUPPLIER, "supplierId")),
            Map.entry("ARCHIVE_SUPPLIER", new Target(LwwEntity.SUPPLIER, "supplierId")),
            Map.entry("UNARCHIVE_SUPPLIER", new Target(LwwEntity.SUPPLIER, "supplierId")),
            Map.entry("UPDATE_STORE", new Target(LwwEntity.STORE, "storeId")),
            Map.entry("DEACTIVATE_STORE", new Target(LwwEntity.STORE, "storeId")),
            Map.entry("RENAME_CATEGORY", new Target(LwwEntity.CATEGORY, "categoryId")),
            Map.entry("TOGGLE_CATEGORY", new Target(LwwEntity.CATEGORY, "categoryId")));

    /**
     * OWNER-only operations: for anyone else the handler must still run and reject them
     * (FORBIDDEN), never answer « superseded ».
     */
    private static final java.util.Set<String> OWNER_ONLY =
            java.util.Set.of("UPDATE_STORE", "DEACTIVATE_STORE", "TOGGLE_CATEGORY");

    private final SyncOperationsLogRepository logRepository;
    private final EntityLastModifiedRepository entityLastModified;
    private final UserSyncStateRepository userSyncStateRepository;
    private final ApplicationEventPublisher eventPublisher;

    public LastWriteWinsStrategy(SyncOperationsLogRepository logRepository,
                                 EntityLastModifiedRepository entityLastModified,
                                 UserSyncStateRepository userSyncStateRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.logRepository = logRepository;
        this.entityLastModified = entityLastModified;
        this.userSyncStateRepository = userSyncStateRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Who wrote the entity's current state, and when (by the writer's clock). */
    private record LastWrite(Instant timestamp, Instant serverInstant, boolean bySync, String deviceId) {}

    @Override
    public PreApplyCheck beforeApply(SyncOperation operation, String deviceId, String tenantId) {
        Target target = TARGETS.get(operation.operationType());
        if (target == null) return PreApplyCheck.NONE;
        if (OWNER_ONLY.contains(operation.operationType()) && !CurrentActor.isOwner()) return PreApplyCheck.NONE;
        UUID id = entityId(operation, target);
        if (id == null) return PreApplyCheck.NONE;
        Optional<Instant> updatedAt = entityLastModified.findUpdatedAt(target.entity(), id);
        // Unknown entity: the handler reports it (not found / idempotent no-op).
        if (updatedAt.isEmpty()) return PreApplyCheck.NONE;

        LastWrite last = lastWrite(id.toString(), operation.operationId(), updatedAt.get());
        Instant incoming = operation.clientTimestamp();

        if (incoming != null && last.timestamp() != null && incoming.isBefore(last.timestamp())) {
            return PreApplyCheck.superseded(new ConflictResult(SUPERSEDED, LAST_WRITE_WINS,
                    supersededData(target.entity(), id, incoming, last.timestamp())));
        }
        if (isConcurrentOverwrite(last, deviceId)) {
            return PreApplyCheck.overwrite(new ConflictResult(LAST_WRITE_WINS, LAST_WRITE_WINS,
                    overwriteData(target.entity(), id, last.timestamp(), incoming)));
        }
        return PreApplyCheck.NONE;
    }

    /** Without the pre-apply decision nothing can be told: no conflict. */
    @Override
    public ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                   UUID actorId, String tenantId) {
        return null;
    }

    @Override
    public ConflictResult evaluate(SyncOperation operation, SyncOperationResult handlerResult,
                                   UUID actorId, String tenantId, PreApplyCheck preApply) {
        if (handlerResult.status() != SyncOperationStatus.APPLIED) return null;
        if (preApply == null || preApply.overwrite() == null) return null;
        ConflictResult overwrite = preApply.overwrite();
        Map<String, Object> data = overwrite.conflictData();
        eventPublisher.publishEvent(new SyncOverwrittenEvent(
                operation.operationId(),
                String.valueOf(data.get("entityType")),
                String.valueOf(data.get("entityId")),
                parse(data.get("overwrittenTimestamp")),
                operation.clientTimestamp(),
                tenantId,
                Instant.now()));
        return overwrite;
    }

    private LastWrite lastWrite(String entityId, String operationId, Instant updatedAt) {
        var previous = logRepository.findPreviousAppliedByEntityId(entityId, operationId);
        if (previous.isPresent() && previous.get().processedAt() != null
                && !previous.get().processedAt().isBefore(updatedAt)) {
            var prev = previous.get();
            return new LastWrite(prev.clientTimestamp(), prev.processedAt(), true, prev.deviceId());
        }
        return new LastWrite(updatedAt, updatedAt, false, null);
    }

    /**
     * Another device's (or the server's) change the pushing device had not pulled yet.
     * A previous sync write whose device is unknown (logged before devices were recorded) is not
     * reported: it cannot be told apart from the device's own edit. Neither is any change when the
     * device's last pull is unknown.
     */
    private boolean isConcurrentOverwrite(LastWrite last, String deviceId) {
        if (last.bySync()) {
            if (last.deviceId() == null || deviceId == null) return false;
            if (Objects.equals(last.deviceId(), deviceId)) return false;
        }
        // Unknown base (the device never pulled): nothing proves it missed that change.
        Instant base = lastPullOf(deviceId);
        return base != null && !last.serverInstant().isBefore(base);
    }

    private Instant lastPullOf(String deviceId) {
        if (deviceId == null) return null;
        try {
            return userSyncStateRepository.findByDeviceId(deviceId)
                    .map(UserSyncState::lastPullAt)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static UUID entityId(SyncOperation operation, Target target) {
        Object raw = operation.payload().get(target.payloadKey());
        String value = raw instanceof String s && !s.isBlank() ? s : operation.entityId();
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> supersededData(LwwEntity entity, UUID id,
                                                      Instant incoming, Instant kept) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conflictType", SUPERSEDED);
        data.put("entityType", entity.name());
        data.put("entityId", id.toString());
        data.put("incomingTimestamp", incoming.toString());
        data.put("keptTimestamp", kept.toString());
        return data;
    }

    private static Map<String, Object> overwriteData(LwwEntity entity, UUID id,
                                                     Instant overwritten, Instant winner) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityType", entity.name());
        data.put("entityId", id.toString());
        data.put("overwrittenTimestamp", overwritten != null ? overwritten.toString() : null);
        data.put("winnerTimestamp", winner != null ? winner.toString() : null);
        return data;
    }

    private static Instant parse(Object iso) {
        return iso instanceof String s ? Instant.parse(s) : null;
    }
}
