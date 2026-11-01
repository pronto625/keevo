package com.keevo.shared.module.application.port;

import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.application.port.PlanModuleRowStore.Origin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Story 16.1 — persistence of the nightly licence materialisation on {@code public.tenant_modules}. Every call runs
 * on the caller's transaction. Implemented by {@code shared.module.adapter.out.persistence.JdbcModuleExpiryStore}.
 */
public interface ModuleExpiryStore {

    /**
     * The tenant's {@code PLAN} / {@code ADMIN} rows whose stored status is behind their dates at {@code now}
     * (exclusive bounds, like the real-time rules): {@code TRIAL} / {@code ACTIVE} with {@code expires_at <= now},
     * {@code EXPIRED} with {@code grace_until} null or {@code <= now}. Locked {@code FOR UPDATE}, module id order.
     */
    List<DueRow> lockDueRows(UUID tenantId, Instant now);

    /** {@code TRIAL} / {@code ACTIVE} → {@code EXPIRED}, dates kept. @return whether the row changed */
    boolean markExpired(UUID tenantId, String moduleId);

    /** {@code TRIAL} / {@code ACTIVE} / {@code EXPIRED} → {@code DISABLED}, {@code disabled_at = now}, dates kept. */
    boolean markDisabled(UUID tenantId, String moduleId, Instant now);

    /**
     * @param origin     {@code PLAN} or {@code ADMIN}
     * @param status     the stored status
     * @param expiresAt  stored end of the subscription / trial
     * @param graceUntil stored end of the read-only grace
     */
    record DueRow(String moduleId, Origin origin, TenantModuleStatus status, Instant expiresAt, Instant graceUntil) {
    }
}
