package com.keevo.identity.auth.adapter.in.api;

import com.keevo.identity.api.TenantDeletionPort;
import com.keevo.identity.auth.application.service.TenantDeletionService;
import com.keevo.identity.auth.domain.port.out.AccountDeletionLogRepository;
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
    private final AccountDeletionLogRepository deletionLog;

    TenantDeletionAdapter(TenantDeletionService tenantDeletionService,
                          AccountDeletionLogRepository deletionLog) {
        this.tenantDeletionService = tenantDeletionService;
        this.deletionLog = deletionLog;
    }

    @Override
    public boolean deleteNow(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return tenantDeletionService.deleteNow(tenantId);
    }

    @Override
    public boolean forgetImportedNow(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return tenantDeletionService.forgetImportedNow(tenantId);
    }

    @Override
    public void recordAdminDeletionRequest(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        // null → scheduled_for copied from the tenant row: exactly the marker forceDelete just wrote
        deletionLog.record(tenantId, AccountDeletionLogRepository.Event.REQUESTED,
                AccountDeletionLogRepository.Source.ADMIN, null);
    }

    @Override
    public void recordAdminDeletionCancel(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        deletionLog.record(tenantId, AccountDeletionLogRepository.Event.CANCELLED,
                AccountDeletionLogRepository.Source.ADMIN, null);
    }
}
