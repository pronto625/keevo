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
        Instant createdAt
) {
    public static EmployeeResponseDto fromDomain(Employee e) {
        return new EmployeeResponseDto(
                e.getId(), e.getUserId(), e.getFirstName(), e.getLastName(),
                e.getStoreId(), e.getStatus().name(),
                e.isPasswordChangeRequired(), e.getCreatedAt());
    }
}
