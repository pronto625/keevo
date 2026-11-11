package com.keevo.identity.employee.adapter.in.rest.dto;

import com.keevo.identity.employee.domain.model.MembershipInvitation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 19.6b — a pending invitation as the inviting company sees it: only what it typed (names, role, store, the
 * identifier and its kind), never anything read from the invited account.
 */
public record InvitationResponseDto(
        UUID id,
        String firstName,
        String lastName,
        @Schema(description = "MANAGER, EMPLOYEE or VIEWER") String role,
        @Schema(description = "French label of the role (« Gérant », « Employé », « Lecteur »)") String roleLabel,
        UUID storeId,
        @Schema(description = "PHONE or USERNAME — what the company typed") String identifierKind,
        @Schema(description = "The phone or username the company typed") String identifier,
        Instant invitedAt,
        Instant expiresAt
) {
    public static InvitationResponseDto fromDomain(MembershipInvitation i) {
        return new InvitationResponseDto(i.id(), i.firstName(), i.lastName(), i.role(),
                MembershipInvitation.roleLabel(i.role()), i.storeId(), i.identifierKind().name(),
                i.identifier(), i.invitedAt(), i.expiresAt());
    }
}
