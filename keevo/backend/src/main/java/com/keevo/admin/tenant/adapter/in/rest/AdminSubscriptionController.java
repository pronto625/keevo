package com.keevo.admin.tenant.adapter.in.rest;

import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.identity.api.PlanType;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.admin.tenant.adapter.in.rest.dto.ActivatePlanRequest;
import com.keevo.subscription.api.ActivatePlanInput;
import com.keevo.subscription.api.PlanAdministrationPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * AdminSubscriptionController — REST adapter for Super Admin plan management.
 *
 * <p>Lives in {@code admin/tenant/} domain because it's a cross-domain operation
 * initiated by the Super Admin actor (not the tenant/subscription domain itself).
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class
 * - SUPER_ADMIN role checked explicitly (method-level guard, SecurityConfig is role-agnostic)
 * - actorId extracted from SecurityContext principal (UUID)
 */
@Tag(name = "Admin — Subscriptions", description = "Super Admin subscription management")
@RestController
@RequestMapping("/api/v1/admin/subscriptions")
public class AdminSubscriptionController {

    private final PlanAdministrationPort planAdministration;

    public AdminSubscriptionController(PlanAdministrationPort planAdministration) {
        this.planAdministration = planAdministration;
    }

    @Operation(
        summary = "Activate or upgrade a tenant's plan",
        description = """
            Super Admin only. Changes tenant plan type (FREE → PREMIUM or downgrades as needed).
            Setting planType to PREMIUM also reactivates a suspended account.
            """,
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{tenantId}/activate")
    public ResponseEntity<ApiResponseWrapper<Void>> activatePlan(
            @PathVariable String tenantId,
            @RequestBody ActivatePlanRequest request) {

        CurrentActor.requireSuperAdmin();

        UUID actorId = CurrentActor.actorId();

        planAdministration.activatePlan(new ActivatePlanInput(
                actorId,
                tenantId, // path variable carries the tenant schema (kv_xxxxxx)
                PlanType.valueOf(request.planType()),
                request.expiresAt()
        ));

        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }
}
