package com.keevo.sync.sync.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * SyncErrorLogEntry — domain record for rejected sync operation payloads.
 *
 * <p>Stored in per-tenant schema ({@code sync_error_log}).
 * Retained 30 days for zero data loss recovery (FR73).
 *
 * <p>Story 5.5 — AC5.
 */
public record SyncErrorLogEntry(
        UUID id,
        String operationId,
        String operationType,
        String entityId,
        Map<String, Object> payload,
        String errorReason,
        Instant clientTimestamp,
        Instant createdAt
) {}
