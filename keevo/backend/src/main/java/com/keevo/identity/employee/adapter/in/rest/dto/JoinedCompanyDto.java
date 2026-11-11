package com.keevo.identity.employee.adapter.in.rest.dto;

import com.keevo.identity.api.Role;
import com.keevo.identity.employee.domain.port.in.MembershipInvitationUseCase.JoinedCompany;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Story 19.6b — the company joined by accepting an invitation, shaped like a login membership: the app then opens it
 * with {@code POST /auth/select-tenant} (same loginToken) or after signing in again.
 */
public record JoinedCompanyDto(
        String tenantCode,
        String tenantName,
        @Schema(description = "Role in V1 vocabulary (MANAGER and VIEWER show as EMPLOYEE)") String role,
        @Schema(description = "The real membership role: MANAGER, EMPLOYEE or VIEWER") String memberRole
) {
    public static JoinedCompanyDto from(JoinedCompany c) {
        return new JoinedCompanyDto(c.tenantCode(), c.tenantName(), Role.v1Label(c.role()), c.role());
    }
}
