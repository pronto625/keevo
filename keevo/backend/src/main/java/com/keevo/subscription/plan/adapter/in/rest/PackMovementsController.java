package com.keevo.subscription.plan.adapter.in.rest;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import com.keevo.subscription.plan.application.service.PackHistoryService;
import com.keevo.subscription.plan.application.service.PackHistoryService.PackMovementsPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 21.3 — the pack journal of the company (« Mon plan »): {@code GET /api/v1/subscription/me/pack-movements}.
 * ZERO business logic. OWNER only (store members and the super-admin: 403).
 */
@Tag(name = "Subscription", description = "Plan limits and account lifecycle")
@EntitlementExempt("core: abonnement")
@RestController
@RequestMapping("/api/v1/subscription/me")
public class PackMovementsController {

    private final PackHistoryService history;

    public PackMovementsController(PackHistoryService history) {
        this.history = history;
    }

    @Operation(summary = "Pack history: packs bought and units consumed from packs (who, when, which counter)",
            description = """
                Newest first. metric (DOCUMENTS, SMS, EMAILS, AI_CREDITS) is optional; limit 1..100 (default 20);
                cursor = nextCursor of the previous page (null on the last page). Each item: change (+ bought,
                − consumed), remainingAfter, sourceLabel, counterLabel (the quota that was used up), byLabel (who),
                at. The month total is packsUsedThisMonth in GET /subscription/me.
                """,
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/pack-movements")
    public ResponseEntity<ApiResponseWrapper<PackMovementsPage>> packMovements(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        CurrentActor.requireOwner("Seul le propriétaire peut consulter l'historique des packs");
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                history.page(TenantContext.getCurrentTenant(), metric, cursor, limit)));
    }
}
