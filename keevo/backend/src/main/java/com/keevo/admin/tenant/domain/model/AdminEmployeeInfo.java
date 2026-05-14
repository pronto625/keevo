package com.keevo.admin.tenant.domain.model;

import java.time.Instant;

/**
 * AdminEmployeeInfo — lightweight employee summary for the tenant detail drawer.
 */
public record AdminEmployeeInfo(
        String id,
        String name,
        String role,
        Instant lastLoginAt
) {}
