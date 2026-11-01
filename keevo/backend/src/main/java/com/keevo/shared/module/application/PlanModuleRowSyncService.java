package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.Origin;
import com.keevo.shared.module.application.port.PlanModuleRowStore.StoredRow;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Plan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link PlanModuleRowSync} of the core (Story 16.5): reads the plan and the tenant's rows on the caller's
 * transaction, computes the desired rows ({@link PlanModuleRows}) and writes only what differs, never touching an
 * {@code ADMIN} or {@code SYSTEM} row. {@code @Transactional} (REQUIRED): joins the subscription writer's
 * transaction, so the rows commit or roll back with the plan change.
 */
@Service
public class PlanModuleRowSyncService implements PlanModuleRowSync {

    private final ModuleCatalog catalog;
    private final PlanModuleRowStore store;
    private final ModuleRegistry registry;
    private final Clock clock;

    public PlanModuleRowSyncService(ModuleCatalog catalog, PlanModuleRowStore store, ModuleRegistry registry,
                                    Clock clock) {
        this.catalog = catalog;
        this.store = store;
        this.registry = registry;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SyncResult sync(UUID tenantId, String tenantSchema) {
        SyncResult result = run(tenantId, tenantSchema, true);
        evictAfterCommit(tenantSchema);
        return result;
    }

    @Override
    @Transactional // not read-only: the plan read locks the subscription row (FOR UPDATE); nothing is written
    public SyncResult preview(UUID tenantId, String tenantSchema) {
        return run(tenantId, tenantSchema, false);
    }

    private SyncResult run(UUID tenantId, String tenantSchema, boolean write) {
        Objects.requireNonNull(tenantId, "tenantId");
        String schema = TenantSchema.validate(tenantSchema);
        Instant now = clock.instant();
        Plan plan = store.readPlan(tenantId, schema);
        if (plan.unreadable()) {
            throw new PlanUnreadableException();
        }
        Map<String, PlanModuleRows.Desired> desired = PlanModuleRows.compute(plan, now, catalog.manifests());
        Map<String, StoredRow> stored = store.findRows(tenantId);
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int preserved = 0;
        for (Map.Entry<String, PlanModuleRows.Desired> entry : desired.entrySet()) {
            String moduleId = entry.getKey();
            PlanModuleRows.Desired row = entry.getValue();
            StoredRow current = stored.get(moduleId);
            if (current != null && current.origin() != Origin.PLAN) {
                preserved++;
                continue;
            }
            if (current != null && row.sameAs(current.status(), current.tier(), current.expiresAt(),
                    current.graceUntil())) {
                unchanged++;
                continue;
            }
            boolean written = !write || store.upsertPlanRow(tenantId, moduleId, row.status(), row.tier(),
                    row.expiresAt(), row.graceUntil(), now);
            if (!written) {
                preserved++; // an ADMIN / SYSTEM row appeared concurrently: left as it is
            } else if (current == null) {
                inserted++;
            } else {
                updated++;
            }
        }
        return new SyncResult(inserted, updated, unchanged, preserved);
    }

    /** Evicts the tenant's registry request cache now and again after commit (rows read before stay stale). */
    private void evictAfterCommit(String schema) {
        registry.evict(schema);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    registry.evict(schema);
                }
            });
        }
    }
}
