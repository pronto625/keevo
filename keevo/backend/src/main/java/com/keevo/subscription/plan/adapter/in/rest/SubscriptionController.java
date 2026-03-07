package com.keevo.subscription.plan.adapter.in.rest;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.subscription.plan.adapter.in.rest.dto.SubscriptionResponse;
import com.keevo.subscription.plan.domain.port.in.GetSubscriptionQuery;
import com.keevo.subscription.plan.domain.port.in.GetSubscriptionUseCase;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
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
 * SubscriptionController — REST adapter for subscription info endpoint.
 *
 * <p>Architecture rules:
 * - ZERO business logic in this class
 * - actorId extracted from SecurityContext principal (set by JwtAuthFilter as UUID)
 * - MCP Port Purity: actorId passed explicitly to query record
 * - OWNER / EMPLOYEE only — SUPER_ADMIN has no tenant schema, so tenant-specific
 *   endpoints must reject it explicitly (see {@link #requireTenantRole()}).
 */
@Tag(name = "Subscription", description = "Plan limits and account lifecycle")
@RestController
@RequestMapping("/api/v1/subscription")
public class SubscriptionController {

    private final GetSubscriptionUseCase getSubscriptionUseCase;

    public SubscriptionController(GetSubscriptionUseCase getSubscriptionUseCase) {
        this.getSubscriptionUseCase = getSubscriptionUseCase;
    }

    @Operation(
        summary = "Get current subscription info and usage counts",
        description = """
            Returns the current tenant's subscription plan, status, expiry date,
            plan limits and current usage counts for stores, products and employees.
            Returns null for limits on PREMIUM_TRIAL and PREMIUM plans (= unlimited).
            Requires OWNER or EMPLOYEE role — SUPER_ADMIN is not a tenant and cannot
            call this endpoint.
            """,
        security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping("/me")
    public ResponseEntity<ApiResponseWrapper<SubscriptionResponse>> getMySubscription() {
        requireTenantRole();

        UUID actorId = (UUID) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();

        SubscriptionResponse response = getSubscriptionUseCase.execute(
                new GetSubscriptionQuery(actorId.toString()));

        return ResponseEntity.ok(ApiResponseWrapper.ok(response));
    }

    // ── Private helper ────────────────────────────────────────────────────────

    /**
     * Enforces that the caller is a tenant user (OWNER or EMPLOYEE).
     * SUPER_ADMIN operates on the public schema and has no tenant data.
     *
     * @throws DomainException FORBIDDEN (403) if called by SUPER_ADMIN
     */
    private void requireTenantRole() {
        Collection<? extends GrantedAuthority> authorities =
                SecurityContextHolder.getContext().getAuthentication().getAuthorities();
        boolean isTenantRole = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_OWNER") || a.equals("ROLE_EMPLOYEE"));
        if (!isTenantRole) {
            // H2 fix: authenticated but wrong role → 403 FORBIDDEN, not 401 UNAUTHORIZED
            throw new DomainException(ErrorCode.FORBIDDEN,
                    "This endpoint requires OWNER or EMPLOYEE role");
        }
    }
}

