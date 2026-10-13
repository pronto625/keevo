package com.keevo.identity.employee.domain.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * EmployeeActivityRepository — driven port of the FR67 activity read model (v1s-14-6).
 *
 * <p>Reads existing storage only (tenant {@code audit_log} and {@code sales},
 * {@code public.refresh_tokens}); no table of its own.
 */
public interface EmployeeActivityRepository {

    /**
     * Raw per-user aggregates. Every requested user id is present in the result (zeros / nulls
     * when the user has no activity).
     *
     * @param userIds      users to aggregate (employee {@code user_id}s)
     * @param todayStart   start of the current WAT day (inclusive)
     * @param weekStart    start of the current WAT ISO week (inclusive)
     * @param now          end of both windows (inclusive) and expiry reference of sessions
     * @param tenantSchema current tenant schema (refresh tokens of other tenants are ignored)
     */
    Map<UUID, RawActivity> findRawActivity(Collection<UUID> userIds, Instant todayStart,
                                           Instant weekStart, Instant now, String tenantSchema);

    record RawActivity(
            UUID userId,
            Instant lastLoginAt,
            Instant lastAuditAt,
            Instant lastSaleAt,
            int todayCount,
            long todayAmount,
            int weekCount,
            long weekAmount,
            boolean activeSession
    ) {
        public static RawActivity empty(UUID userId) {
            return new RawActivity(userId, null, null, null, 0, 0L, 0, 0L, false);
        }
    }
}
