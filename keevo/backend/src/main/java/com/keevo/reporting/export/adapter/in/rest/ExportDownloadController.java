package com.keevo.reporting.export.adapter.in.rest;

import com.keevo.reporting.export.domain.port.in.DownloadExportUseCase;
import com.keevo.reporting.export.domain.port.in.DownloadExportUseCase.LinkTarget;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Public signed download of a READY export (Story v1s-18-1): {@code GET /api/v1/exports/download?token=…}.
 *
 * <p>No JWT — the HMAC token binds tenant schema, job and expiry (403 {@code EXPORT_LINK_INVALID},
 * 410 {@code EXPORT_EXPIRED}). The controller validates the schema and sets / clears
 * {@link TenantContext} itself (the JWT filter is bypassed for this path).
 */
@Tag(name = "Exports", description = "Export de données (CSV, Excel) — propriétaire uniquement")
@EntitlementExempt("lien signé (export déjà contrôlé à la demande)")
@RestController
public class ExportDownloadController {

    private final DownloadExportUseCase downloadExportUseCase;

    public ExportDownloadController(DownloadExportUseCase downloadExportUseCase) {
        this.downloadExportUseCase = downloadExportUseCase;
    }

    @Operation(summary = "Télécharger un export via un lien signé")
    @GetMapping("/api/v1/exports/download")
    public ResponseEntity<Resource> download(@RequestParam("token") String token) throws IOException {
        LinkTarget target = downloadExportUseCase.verifyLink(token);
        TenantContext.setCurrentTenant(TenantSchema.validate(target.tenantSchema()));
        try {
            return ExportController.fileResponse(downloadExportUseCase.open(target.jobId()));
        } finally {
            TenantContext.clear();
        }
    }
}
