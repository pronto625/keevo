package com.keevo.admin.tenant.domain.port.in;

import java.time.Instant;

/**
 * ListTenantsQuery — query parameters for the paginated tenant list.
 *
 * <p>All filter fields are nullable — null means "no filter for this dimension".
 * plan: "FREE" | "PREMIUM_TRIAL" | "PREMIUM" | legacy "PAID" (= PREMIUM_TRIAL + PREMIUM) | "ALL" | null (=ALL);
 * any other value is rejected with 422 VALIDATION_ERROR
 * status: "ACTIVE" | "DELETION_PENDING" | "SUSPENDED" | null (=ALL)
 */
public record ListTenantsQuery(
        String search,
        String plan,
        String status,
        Instant registeredFrom,
        Instant registeredTo,
        Instant lastActivityFrom,
        Instant lastActivityTo,
        int page,
        int pageSize
) {}
