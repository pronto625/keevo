package com.keevo.admin.platform.domain.port.out;

import java.time.Instant;

/** PlatformProbePort — live database checks of the health view (Story 9-5). */
public interface PlatformProbePort {

    /** {@code SELECT 1 FROM public.tenants LIMIT 1} succeeded (never throws). */
    boolean databaseOk();

    /**
     * Sync operations refused since {@code since}: rows of every company's {@code sync_error_log}.
     * A company whose own schema/table is unreadable is skipped; connection failures are rethrown.
     */
    long syncRefusalsSince(Instant since);
}
