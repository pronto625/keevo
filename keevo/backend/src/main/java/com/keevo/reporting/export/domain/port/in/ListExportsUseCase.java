package com.keevo.reporting.export.domain.port.in;

import com.keevo.reporting.export.domain.model.ExportJob;

import java.util.List;
import java.util.UUID;

/** « Mes exports » — background jobs of the current tenant (Story v1s-18-1). */
public interface ListExportsUseCase {

    /** Newest first (at most 50). */
    List<ExportJob> list();

    /** @throws com.keevo.shared.domain.exception.DomainException EXPORT_NOT_FOUND */
    ExportJob get(UUID id);
}
