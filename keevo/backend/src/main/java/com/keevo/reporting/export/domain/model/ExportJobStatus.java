package com.keevo.reporting.export.domain.model;

/** Lifecycle of a background export job (Story v1s-18-1). */
public enum ExportJobStatus {
    PENDING,
    RUNNING,
    READY,
    FAILED,
    EXPIRED;

    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }
}
