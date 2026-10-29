package com.keevo.identity.auth.adapter.in.api;

import com.keevo.identity.api.TenantDeletionPort;
import com.keevo.identity.auth.application.service.TenantDeletionService;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Implements {@link TenantDeletionPort} over {@link TenantDeletionService#deleteNow} (Story 12.2f):
 * the exact routine of the daily run — tenant row lock and re-check, schema DROP, purge of
 * memberships / orphaned users / tokens, {@code DELETED} last, file purge and final WhatsApp after
 * commit. Not {@code @SchedulerLock}ed: the row lock serialises it with a concurrent daily run.
 */
@Component
class TenantDeletionAdapter implements TenantDeletionPort {

    private final TenantDeletionService tenantDeletionService;

    TenantDeletionAdapter(TenantDeletionService tenantDeletionService) {
        this.tenantDeletionService = tenantDeletionService;
    }

    @Override
    public boolean deleteNow(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return tenantDeletionService.deleteNow(tenantId);
    }
}
