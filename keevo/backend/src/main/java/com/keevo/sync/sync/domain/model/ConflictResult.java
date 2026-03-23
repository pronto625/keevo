package com.keevo.sync.sync.domain.model;

import java.util.Map;

/**
 * ConflictResult — Outcome of conflict resolution strategy evaluation.
 * Null conflictType means no conflict was detected.
 */
public record ConflictResult(
        String conflictType,
        String strategy,
        Map<String, Object> conflictData
) {}
