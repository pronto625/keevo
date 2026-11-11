package com.keevo.identity.employee.adapter.in.rest.dto;

import com.keevo.identity.employee.domain.port.in.MembershipInvitationUseCase.PendingInvitation;
import com.keevo.identity.employee.domain.model.MembershipInvitation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Story 19.6b — an invitation as the invited person sees it: the company and the role offered. */
public record PendingInvitationDto(
        UUID id,
        @Schema(description = "Name of the inviting company") String companyName,
        @Schema(description = "MANAGER, EMPLOYEE or VIEWER") String role,
        @Schema(description = "French label of the role") String roleLabel,
        Instant invitedAt,
        Instant expiresAt
) {
    public static PendingInvitationDto from(PendingInvitation p) {
        return new PendingInvitationDto(p.id(), p.companyName(), p.role(),
                MembershipInvitation.roleLabel(p.role()), p.invitedAt(), p.expiresAt());
    }
}
