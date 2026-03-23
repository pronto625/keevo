package com.keevo.sync.sync.application.strategy;

import com.keevo.sync.sync.domain.model.*;
import com.keevo.sync.sync.domain.port.out.SyncOperationsLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LastWriteWinsStrategyTest {

    @Mock private SyncOperationsLogRepository logRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private LastWriteWinsStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new LastWriteWinsStrategy(logRepository, eventPublisher);
    }

    @Test
    void evaluate_firstUpdate_returnsNoConflict() {
        var op = new SyncOperation("op-1", "UPDATE_PRODUCT", "p1",
                Map.of("productId", "p1", "name", "NewName"), Instant.parse("2026-03-22T10:00:00Z"));
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "p1", null);

        when(logRepository.findPreviousAppliedByEntityId("p1", "op-1")).thenReturn(Optional.empty());

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNull();
    }

    @Test
    void evaluate_overwriteDetected_returnsLwwConflict() {
        var op = new SyncOperation("op-2", "UPDATE_PRODUCT", "p1",
                Map.of("productId", "p1", "name", "FromDeviceB"), Instant.parse("2026-03-22T11:00:00Z"));
        var handlerResult = new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, "p1", null);

        var previousEntry = new SyncOperationsLogEntry(
                "op-1", "UPDATE_PRODUCT", "p1", SyncOperationStatus.APPLIED, null,
                Instant.parse("2026-03-22T10:30:00Z"), Instant.parse("2026-03-22T10:00:00Z"));
        when(logRepository.findPreviousAppliedByEntityId("p1", "op-2")).thenReturn(Optional.of(previousEntry));

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNotNull();
        assertThat(result.conflictType()).isEqualTo("LAST_WRITE_WINS");
        assertThat(result.strategy()).isEqualTo("LAST_WRITE_WINS");
        verify(eventPublisher).publishEvent(any(SyncOverwrittenEvent.class));
    }

    @Test
    void evaluate_overwriteDetected_conflictDataHasTimestamps() {
        var op = new SyncOperation("op-2", "UPDATE_PRODUCT", "p1",
                Map.of("productId", "p1"), Instant.parse("2026-03-22T11:00:00Z"));
        var handlerResult = new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, "p1", null);

        var previousEntry = new SyncOperationsLogEntry(
                "op-1", "UPDATE_PRODUCT", "p1", SyncOperationStatus.APPLIED, null,
                Instant.parse("2026-03-22T10:30:00Z"), Instant.parse("2026-03-22T10:00:00Z"));
        when(logRepository.findPreviousAppliedByEntityId("p1", "op-2")).thenReturn(Optional.of(previousEntry));

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result.conflictData()).containsKey("overwrittenTimestamp");
        assertThat(result.conflictData()).containsKey("winnerTimestamp");
        assertThat(result.conflictData()).containsEntry("entityType", "PRODUCT");
        assertThat(result.conflictData()).containsEntry("entityId", "p1");
    }

    @Test
    void evaluate_sameTimestamp_returnsNoConflict() {
        var ts = Instant.parse("2026-03-22T10:00:00Z");
        var op = new SyncOperation("op-2", "UPDATE_PRODUCT", "p1",
                Map.of("productId", "p1"), ts);
        var handlerResult = new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, "p1", null);

        // No previous — means this is the only one
        when(logRepository.findPreviousAppliedByEntityId("p1", "op-2")).thenReturn(Optional.empty());

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNull();
    }

    @Test
    void evaluate_noEntityId_returnsNoConflict() {
        var op = new SyncOperation("op-1", "UPDATE_PRODUCT", null,
                Map.of("name", "test"), Instant.now());
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, null, null);

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNull();
        verifyNoInteractions(logRepository);
    }

    @Test
    void evaluate_handlerStatusRejected_skipsEvaluation() {
        var op = new SyncOperation("op-1", "UPDATE_PRODUCT", "p1",
                Map.of(), Instant.now());
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.REJECTED, null, "ERROR");

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNull();
        verifyNoInteractions(logRepository);
    }
}
