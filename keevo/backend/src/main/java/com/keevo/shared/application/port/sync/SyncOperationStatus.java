package com.keevo.shared.application.port.sync;

/**
 * SyncOperationStatus — Result status for each sync operation.
 */
public enum SyncOperationStatus {
    APPLIED,
    CONFLICT,
    REJECTED,
    DUPLICATE
}
