package com.keevo.sync.sync.domain.model;

/**
 * SyncOperationStatus — Result status for each sync operation.
 */
public enum SyncOperationStatus {
    APPLIED,
    CONFLICT,
    REJECTED,
    DUPLICATE
}
