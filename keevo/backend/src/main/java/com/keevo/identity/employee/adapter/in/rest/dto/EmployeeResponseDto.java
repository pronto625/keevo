package com.keevo.identity.employee.adapter.in.rest.dto;

import com.keevo.identity.api.Role;
import com.keevo.identity.employee.domain.model.Employee;

import java.time.Instant;
import java.util.UUID;

public record EmployeeResponseDto(
        UUID id,
        UUID userId,
        String firstName,
        String lastName,
        UUID storeId,
        String status,
        boolean passwordChangeRequired,
        Instant createdAt,
        String role,
        String phoneNumber,
        String username,
        String memberRole,
        UUID customRoleId,      // Story 19.14 — the owner-defined role of the member, null when none (additive)
        String customRoleName   // Story 19.14 — its name (« Caissier »), null when none (additive)
) {
    /** @deprecated use {@link #fromDomain(Employee, String, String)} to include role and phone */
    @Deprecated
    public static EmployeeResponseDto fromDomain(Employee e) {
        return fromDomain(e, null, null);
    }

    /** Story 14.11 — factory with role resolved from membership. */
    public static EmployeeResponseDto fromDomain(Employee e, String role) {
        return fromDomain(e, role, null);
    }

    /**
     * Story 14.11 (AC6) — factory with role and the phone number of the
     * underlying {@code public.users} row, so the Flutter edit form can be
     * pre-filled.
     */
    public static EmployeeResponseDto fromDomain(Employee e, String role, String phoneNumber) {
        return fromDomain(e, role, phoneNumber, null);
    }

    /**
     * Story 19.4 — with the member's username (additive field; {@code phoneNumber} is {@code null} for a member
     * identified by a username only). Story 19.5 — {@code role} in the V1 vocabulary (MANAGER and VIEWER show as
     * EMPLOYEE), the real role in the additive {@code memberRole}.
     */
    public static EmployeeResponseDto fromDomain(Employee e, String role, String phoneNumber, String username) {
        return fromDomain(e, role, phoneNumber, username, null, null);
    }

    /** Story 19.14 — with the member's owner-defined role ({@code null} id and name when none). */
    public static EmployeeResponseDto fromDomain(Employee e, String role, String phoneNumber, String username,
                                                 UUID customRoleId, String customRoleName) {
        return new EmployeeResponseDto(
                e.getId(), e.getUserId(), e.getFirstName(), e.getLastName(),
                e.getStoreId(), e.getStatus().name(),
                e.isPasswordChangeRequired(), e.getCreatedAt(), Role.v1Label(role), phoneNumber, username, role,
                customRoleId, customRoleName);
    }
}
