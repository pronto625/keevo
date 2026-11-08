package com.keevo.admin.tenantwrite.adapter.in.rest;

import com.keevo.admin.crossaccount.domain.model.CrossAccountViews.CustomerDetail;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.CustomerCreated;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.CustomerInput;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberCreated;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberInput;
import com.keevo.admin.tenantwrite.domain.model.TenantWriteViews.MemberView;
import com.keevo.admin.tenantwrite.domain.port.in.TenantWriteUseCase;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 20.13 (decisions A1–A4, 2026-11-08) — super-admin writes in one account inherited from Bref: customers
 * ({@code POST/PATCH /customers}, {@code PATCH /customers/:id/marketing-consent}) and members ({@code POST/PATCH /users},
 * {@code PATCH /users/:id/status}, {@code DELETE /users/:id}). SUPER_ADMIN checked explicitly behind the SecurityConfig
 * URL rule; the rules, events and audit are the crm's and identity's (actor = the super-admin). A member is
 * addressed by its user id (from {@code GET /admin/users}); its employee record in the account is resolved.
 */
@Tag(name = "Admin — Account writes", description = "Super Admin customers and members of one account")
@EntitlementExempt("super-admin")
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}")
public class AdminTenantWriteController {

    /** Body of the consent route. */
    public record ConsentRequest(Boolean consent) {
    }

    private final TenantWriteUseCase useCase;

    public AdminTenantWriteController(TenantWriteUseCase useCase) {
        this.useCase = useCase;
    }

    @Operation(summary = "Create a customer in an account", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/customers")
    public ResponseEntity<ApiResponseWrapper<CustomerCreated>> createCustomer(@PathVariable String tenantId,
                                                                              @RequestBody(required = false) CustomerInput body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(
                useCase.createCustomer(CurrentActor.actorId(), tenantId, body)));
    }

    @Operation(summary = "Update a customer of an account", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping("/customers/{customerId}")
    public ResponseEntity<ApiResponseWrapper<CustomerDetail>> updateCustomer(@PathVariable String tenantId,
                                                                             @PathVariable String customerId,
                                                                             @RequestBody(required = false) CustomerInput body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                useCase.updateCustomer(CurrentActor.actorId(), tenantId, customerId, body)));
    }

    @Operation(summary = "Set a customer's marketing consent", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/customers/{customerId}/marketing-consent")
    public ResponseEntity<ApiResponseWrapper<CustomerDetail>> setConsent(@PathVariable String tenantId,
                                                                         @PathVariable String customerId,
                                                                         @RequestBody(required = false) ConsentRequest body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(useCase.setMarketingConsent(CurrentActor.actorId(), tenantId,
                customerId, body == null ? null : body.consent())));
    }

    @Operation(summary = "Add a member to an account", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/members")
    public ResponseEntity<ApiResponseWrapper<MemberCreated>> createMember(@PathVariable String tenantId,
                                                                          @RequestBody(required = false) MemberInput body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.ok(
                useCase.createMember(CurrentActor.actorId(), tenantId, body)));
    }

    @Operation(summary = "Update a member of an account", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping("/members/{userId}")
    public ResponseEntity<ApiResponseWrapper<MemberView>> updateMember(@PathVariable String tenantId,
                                                                       @PathVariable String userId,
                                                                       @RequestBody(required = false) MemberInput body) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                useCase.updateMember(CurrentActor.actorId(), tenantId, userId, body)));
    }

    @Operation(summary = "Deactivate a member (sessions revoked)", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/members/{userId}/deactivate")
    public ResponseEntity<ApiResponseWrapper<MemberView>> deactivate(@PathVariable String tenantId,
                                                                     @PathVariable String userId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                useCase.setMemberActive(CurrentActor.actorId(), tenantId, userId, false)));
    }

    @Operation(summary = "Reactivate a member", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/members/{userId}/reactivate")
    public ResponseEntity<ApiResponseWrapper<MemberView>> reactivate(@PathVariable String tenantId,
                                                                     @PathVariable String userId) {
        CurrentActor.requireSuperAdmin();
        return ResponseEntity.ok(ApiResponseWrapper.ok(
                useCase.setMemberActive(CurrentActor.actorId(), tenantId, userId, true)));
    }

    @Operation(summary = "Remove a member from an account (never the owner)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> remove(@PathVariable String tenantId, @PathVariable String userId) {
        CurrentActor.requireSuperAdmin();
        useCase.removeMember(CurrentActor.actorId(), tenantId, userId);
        return ResponseEntity.noContent().build();
    }
}
