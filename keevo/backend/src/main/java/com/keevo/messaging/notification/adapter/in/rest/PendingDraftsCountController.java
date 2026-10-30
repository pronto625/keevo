package com.keevo.messaging.notification.adapter.in.rest;

import com.keevo.messaging.notification.application.service.PendingDraftsQueryService;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.RequiresEntitlement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pending-drafts badge counter (Story 2.4 AC6/AC10). Same endpoint, response and RBAC as before; moved from
 * the catalog's {@code ProductImportController} to messaging, which owns the pending-draft records
 * (Story 12.6a: the catalog no longer reads messaging).
 */
@Tag(name = "Messaging", description = "Pending draft products awaiting owner validation")
@RestController
public class PendingDraftsCountController {

    private final PendingDraftsQueryService pendingDrafts;

    public PendingDraftsCountController(PendingDraftsQueryService pendingDrafts) {
        this.pendingDrafts = pendingDrafts;
    }

    /**
     * GET /api/v1/products/drafts/count
     * Returns the number of unacknowledged DRAFT products for the current tenant.
     * Used by the owner's UI badge counter.
     */
    @Operation(summary = "Count the draft products awaiting owner validation (owner only)")
    @RequiresEntitlement(value = "messaging", access = RequiresEntitlement.Access.READ)
    @GetMapping("/api/v1/products/drafts/count")
    public ResponseEntity<ApiResponseWrapper<Long>> getPendingDraftsCount() {
        String role = CurrentActor.role();
        if (role == null) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Rôle introuvable dans le jeton d'authentification");
        }
        // OWNER-only (Story 2.4 AC10): employees receive HTTP 403.
        if (!"OWNER".equals(role)) {
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "Seul le propriétaire peut consulter le compteur de brouillons");
        }
        return ResponseEntity.ok(ApiResponseWrapper.ok(pendingDrafts.countPending(TenantContext.getCurrentTenant())));
    }
}
