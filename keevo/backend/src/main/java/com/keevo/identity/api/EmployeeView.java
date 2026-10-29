package com.keevo.identity.api;

import java.util.UUID;

/**
 * An employee record of a tenant as seen by other modules (Story 12.2d).
 *
 * @param id     employee record id ({@code employees.id})
 * @param userId platform user id ({@code public.users.id}, the id carried by sales / JWT)
 * @param active true for an ACTIVE employee
 */
public record EmployeeView(
        UUID id,
        UUID userId,
        UUID storeId,
        String firstName,
        String lastName,
        boolean active
) {}
