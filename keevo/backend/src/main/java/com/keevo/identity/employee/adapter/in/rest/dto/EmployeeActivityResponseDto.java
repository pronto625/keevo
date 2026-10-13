package com.keevo.identity.employee.adapter.in.rest.dto;

import com.keevo.identity.employee.domain.model.EmployeeActivity;

import java.time.Instant;
import java.util.UUID;

/**
 * EmployeeActivityResponseDto — FR67 activity payload (v1s-14-6). Amounts are XAF integers.
 */
public record EmployeeActivityResponseDto(
        UUID employeeId,
        UUID userId,
        Instant lastLoginAt,
        Instant lastActivityAt,
        int todaySalesCount,
        long todaySalesAmount,
        int weekSalesCount,
        long weekSalesAmount,
        boolean activeSession,
        boolean inactive
) {
    public static EmployeeActivityResponseDto fromDomain(EmployeeActivity a) {
        return new EmployeeActivityResponseDto(
                a.employeeId(), a.userId(), a.lastLoginAt(), a.lastActivityAt(),
                a.todaySalesCount(), a.todaySalesAmount(),
                a.weekSalesCount(), a.weekSalesAmount(),
                a.activeSession(), a.inactive());
    }
}
