package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.auth.domain.port.in.LoginTokenVerifier;
import com.keevo.identity.employee.adapter.in.rest.dto.JoinedCompanyDto;
import com.keevo.identity.employee.adapter.in.rest.dto.LoginTokenRequestDto;
import com.keevo.identity.employee.adapter.in.rest.dto.PendingInvitationDto;
import com.keevo.identity.employee.domain.port.in.MembershipInvitationUseCase;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.shared.infrastructure.web.ApiResponseWrapper;
import com.keevo.shared.module.EntitlementExempt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Story 19.6b — the invited person answers her invitations, from two places:
 * <ul>
 *   <li>the login step, before choosing a company ({@code /api/v1/auth/invitations/**}, public, the
 *       {@code loginToken} of step 1 in the body — also for an account without any company yet);</li>
 *   <li>an open session in any company ({@code /api/v1/account/invitations/**}, bearer token; the caller's own
 *       invitations whatever the company of the session).</li>
 * </ul>
 * Nothing here is scoped to the session's company: the invitation belongs to the person.
 */
@Tag(name = "Invitations", description = "Invitations to join a company (existing accounts)")
@EntitlementExempt("core: invitations d'un compte existant")
@RestController
public class InvitationController {

    private final MembershipInvitationUseCase useCase;
    private final LoginTokenVerifier loginTokenVerifier;

    public InvitationController(MembershipInvitationUseCase useCase, LoginTokenVerifier loginTokenVerifier) {
        this.useCase = useCase;
        this.loginTokenVerifier = loginTokenVerifier;
    }

    // ── login step (loginToken) ────────────────────────────────────────────────

    @Operation(summary = "My pending invitations (login step)",
            description = "Story 19.6b — with the loginToken of /auth/login, /auth/otp/verify or /auth/social/*: the "
                    + "invitations not answered yet (company name, role, expiry), newest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pending invitations (possibly empty)"),
            @ApiResponse(responseCode = "401", description = "TOKEN_EXPIRED or TOKEN_INVALID")
    })
    @SecurityRequirements
    @PostMapping("/api/v1/auth/invitations/pending")
    public ResponseEntity<ApiResponseWrapper<List<PendingInvitationDto>>> pendingAtLogin(
            @Valid @RequestBody LoginTokenRequestDto request) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(list(loginTokenVerifier.userIdOf(request.loginToken()))));
    }

    @Operation(summary = "Accept an invitation (login step)",
            description = "Story 19.6b — becomes a member of the inviting company with the role offered; the answer is "
                    + "the company as /auth/login lists it, to open with /auth/select-tenant and the same loginToken. "
                    + "Needs a phone or a verified email on the account (ACCOUNT_RECOVERY_REQUIRED).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Joined"),
            @ApiResponse(responseCode = "401", description = "TOKEN_EXPIRED or TOKEN_INVALID"),
            @ApiResponse(responseCode = "403", description = "PLAN_LIMIT_EXCEEDED — the company's plan is full "
                    + "(the invitation stays pending)"),
            @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND — answered, expired or cancelled"),
            @ApiResponse(responseCode = "409", description = "ACCOUNT_RECOVERY_REQUIRED or MEMBER_ALREADY_IN_COMPANY")
    })
    @SecurityRequirements
    @PostMapping("/api/v1/auth/invitations/{invitationId}/accept")
    public ResponseEntity<ApiResponseWrapper<JoinedCompanyDto>> acceptAtLogin(
            @PathVariable UUID invitationId, @Valid @RequestBody LoginTokenRequestDto request) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(JoinedCompanyDto.from(
                useCase.accept(invitationId, loginTokenVerifier.userIdOf(request.loginToken())))));
    }

    @Operation(summary = "Decline an invitation (login step)",
            description = "Story 19.6b — nobody is notified; after a second refusal the same company cannot invite again for "
                    + "30 days (reprise 11/11/2026).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Declined"),
            @ApiResponse(responseCode = "401", description = "TOKEN_EXPIRED or TOKEN_INVALID"),
            @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND")
    })
    @SecurityRequirements
    @PostMapping("/api/v1/auth/invitations/{invitationId}/decline")
    public ResponseEntity<Void> declineAtLogin(@PathVariable UUID invitationId,
                                               @Valid @RequestBody LoginTokenRequestDto request) {
        useCase.decline(invitationId, loginTokenVerifier.userIdOf(request.loginToken()));
        return ResponseEntity.noContent().build();
    }

    // ── open session (bearer) ──────────────────────────────────────────────────

    @Operation(summary = "My pending invitations",
            description = "Story 19.6b — the caller's invitations, whatever the company of the session.")
    @ApiResponse(responseCode = "200", description = "Pending invitations (possibly empty)")
    @GetMapping("/api/v1/account/invitations")
    public ResponseEntity<ApiResponseWrapper<List<PendingInvitationDto>>> pending() {
        return ResponseEntity.ok(ApiResponseWrapper.ok(list(CurrentActor.actorId())));
    }

    @Operation(summary = "Accept an invitation",
            description = "Story 19.6b — becomes a member of the inviting company; the current session is unchanged "
                    + "(the app opens the new company by signing in again and choosing it).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Joined"),
            @ApiResponse(responseCode = "403", description = "PLAN_LIMIT_EXCEEDED (the invitation stays pending)"),
            @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "ACCOUNT_RECOVERY_REQUIRED or MEMBER_ALREADY_IN_COMPANY")
    })
    @PostMapping("/api/v1/account/invitations/{invitationId}/accept")
    public ResponseEntity<ApiResponseWrapper<JoinedCompanyDto>> accept(@PathVariable UUID invitationId) {
        return ResponseEntity.ok(ApiResponseWrapper.ok(JoinedCompanyDto.from(
                useCase.accept(invitationId, CurrentActor.actorId()))));
    }

    @Operation(summary = "Decline an invitation", description = "Story 19.6b — nobody is notified.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Declined"),
            @ApiResponse(responseCode = "404", description = "INVITATION_NOT_FOUND")
    })
    @PostMapping("/api/v1/account/invitations/{invitationId}/decline")
    public ResponseEntity<Void> decline(@PathVariable UUID invitationId) {
        useCase.decline(invitationId, CurrentActor.actorId());
        return ResponseEntity.noContent().build();
    }

    private List<PendingInvitationDto> list(UUID userId) {
        return useCase.listForUser(userId).stream().map(PendingInvitationDto::from).toList();
    }
}
