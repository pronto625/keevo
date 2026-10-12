package com.keevo.reporting.export.adapter.out.persistence;

import com.keevo.reporting.export.domain.model.ExportJob;

/** Test helper: deep copy of a job through the persistence mapping (round-trip). */
public final class ExportJobJpaEntityTestAccess {

    private ExportJobJpaEntityTestAccess() {
    }

    public static ExportJob copy(ExportJob job) {
        return ExportJobRepositoryAdapter.toDomain(ExportJobRepositoryAdapter.toEntity(job));
    }
}
