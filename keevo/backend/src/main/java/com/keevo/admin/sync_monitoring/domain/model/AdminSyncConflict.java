package com.keevo.admin.sync_monitoring.domain.model;

import java.time.Instant;

public record AdminSyncConflict(
        String entityType,
        String conflictType,
        String strategy,
        Instant resolvedAt
) {}
