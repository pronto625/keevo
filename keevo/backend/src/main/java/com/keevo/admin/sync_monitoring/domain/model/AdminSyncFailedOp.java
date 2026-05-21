package com.keevo.admin.sync_monitoring.domain.model;

import java.time.Instant;

public record AdminSyncFailedOp(
        String operationType,
        String errorReason,
        Instant processedAt
) {}
