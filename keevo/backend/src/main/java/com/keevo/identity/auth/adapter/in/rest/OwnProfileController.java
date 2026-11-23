package com.keevo.identity.auth.adapter.in.rest;

import com.keevo.identity.auth.adapter.in.rest.dto.UpdateOwnProfileRequest;
import com.keevo.identity.auth.adapter.in.rest.dto.UserProfileResponse;
import com.keevo.identity.auth.domain.port.in.UpdateOwnProfileUseCase;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 22.19 (D17) — {@code PUT /api/v1/auth/profile} {@code {firstName, lastName}}: the authenticated user changes
 * his own name (owner included, any tenant role). Answers the same body as {@code GET /api/v1/auth/profile}
 * (unwrapped, like the GET). Audited {@code PROFILE_UPDATED}.
 */
@Tag(name = "Authentication", description = "User registration and login endpoints")
@EntitlementExempt("authentification: mon profil")
@RestController
public class OwnProfileController {

    private final UpdateOwnProfileUseCase updateOwnProfile;

    public OwnProfileController(UpdateOwnProfileUseCase updateOwnProfile) {
        this.updateOwnProfile = updateOwnProfile;
    }

    @Operation(summary = "Modifier mon nom (prénom et nom)")
    @PutMapping("/api/v1/auth/profile")
    public ResponseEntity<UserProfileResponse> update(@RequestBody(required = false) UpdateOwnProfileRequest request) {
        UpdateOwnProfileRequest body = request == null ? new UpdateOwnProfileRequest(null, null) : request;
        return ResponseEntity.ok(AuthController.toProfileResponse(updateOwnProfile.update(CurrentActor.actorId(),
                TenantContext.getCurrentTenant(), body.firstName(), body.lastName())));
    }
}
