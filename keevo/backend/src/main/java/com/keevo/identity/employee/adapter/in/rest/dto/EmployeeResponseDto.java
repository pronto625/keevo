package com.keevo.identity.employee.adapter.in.rest.dto;

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
        String phoneNumber
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
        return new EmployeeResponseDto(
                e.getId(), e.getUserId(), e.getFirstName(), e.getLastName(),
                e.getStoreId(), e.getStatus().name(),
                e.isPasswordChangeRequired(), e.getCreatedAt(), role, phoneNumber);
    }
}
