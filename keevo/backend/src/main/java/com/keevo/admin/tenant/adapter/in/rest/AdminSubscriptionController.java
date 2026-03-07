package com.keevo.admin.tenant.adapter.in.rest;

import com.keevo.identity.auth.domain.model.PlanType;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.subscription.plan.adapter.in.rest.dto.ActivatePlanRequest;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanCommand;
import com.keevo.subscription.plan.domain.port.in.ActivatePlanUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
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

    private final ActivatePlanUseCase activatePlanUseCase;

    public AdminSubscriptionController(ActivatePlanUseCase activatePlanUseCase) {
        this.activatePlanUseCase = activatePlanUseCase;
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

        requireSuperAdmin();

        UUID actorId = (UUID) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        activatePlanUseCase.execute(new ActivatePlanCommand(
                actorId.toString(),
                tenantId,
                PlanType.valueOf(request.planType()),
                request.expiresAt()
        ));

        return ResponseEntity.ok(ApiResponseWrapper.ok(null));
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private void requireSuperAdmin() {
        Collection<? extends GrantedAuthority> authorities =
                SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        boolean isSuperAdmin = authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        if (!isSuperAdmin) {
            // H2 fix: authenticated but wrong role → 403 FORBIDDEN, not 401 UNAUTHORIZED
            throw new DomainException(ErrorCode.FORBIDDEN, "SUPER_ADMIN role required");
        }
    }
}
