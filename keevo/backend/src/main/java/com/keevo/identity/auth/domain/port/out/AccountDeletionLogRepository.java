package com.keevo.identity.auth.domain.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable, RGPD-clean trace of the account deletion lifecycle (Story 16.6, FR91):
 * {@code public.account_deletion_log}. It survives the tenant schema drop and holds no personal
 * data — tenant id and account code only (the code is read from {@code public.tenants}).
 *
 * <p>Writes join the caller's transaction, so a rolled-back request, cancel or execution leaves
 * no row behind.
 */
public interface AccountDeletionLogRepository {

    enum Event { REQUESTED, CANCELLED, EXECUTED }

    enum Source { OWNER, ADMIN, SYSTEM }

    /**
     * Appends one event for the tenant. An unknown tenant writes nothing (logged as a warning).
     *
     * @param scheduledFor the planned deletion date (REQUESTED only), {@code null} otherwise; for a
     *                     REQUESTED event, {@code null} means "the tenant row's current
     *                     {@code deletion_scheduled_at}" (the admin force-delete marker)
     */
    void record(UUID tenantId, Event event, Source source, Instant scheduledFor);
}
