package com.keevo.identity.employee.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * EmployeeActivity — FR67 read model of one employee's activity (v1s-14-6).
 *
 * <p>Pure Java. {@code lastLoginAt} = latest {@code USER_AUTHENTICATED} audit entry;
 * {@code lastActivityAt} = latest audit entry or sale, whichever is newer (null = none);
 * sales figures are COMPLETED sales in the WAT day / ISO week (XAF); {@code activeSession} = a
 * non-revoked, unexpired refresh token exists for this tenant; {@code inactive} = ACTIVE employee
 * idle for more than 48 h.
 */
public record EmployeeActivity(
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
}
