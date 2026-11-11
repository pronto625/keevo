package com.keevo.shared.module.application;

import com.keevo.shared.capability.entitlement.PlanProfile;
import com.keevo.shared.capability.entitlement.PlanProfiles;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.Origin;
import com.keevo.shared.module.application.port.PlanModuleRowStore.StoredRow;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.application.port.TenantModuleStatePort.Plan;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent;
import com.keevo.shared.event.catalog.module.ModuleLicencesChangedEvent.Transition;
import com.keevo.shared.module.TenantModuleStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final ApplicationEventPublisher events;
    private final PlanProfiles profiles;

    /** Without an event publisher (tests that do not observe the licence notifications), built-in plan profiles. */
    public PlanModuleRowSyncService(ModuleCatalog catalog, PlanModuleRowStore store, ModuleRegistry registry,
                                    Clock clock) {
        this(catalog, store, registry, clock, event -> { }, null);
    }

    /** Built-in plan profiles (tests). */
    public PlanModuleRowSyncService(ModuleCatalog catalog, PlanModuleRowStore store, ModuleRegistry registry,
                                    Clock clock, ApplicationEventPublisher events) {
        this(catalog, store, registry, clock, events, null);
    }

    /**
     * @param profiles the plan profiles (Story 16.8: which paid modules the plan type grants); {@code null} → built-in
     *                 defaults (premium grants every paid module, {@code FREE} none)
     */
    @Autowired
    public PlanModuleRowSyncService(ModuleCatalog catalog, PlanModuleRowStore store, ModuleRegistry registry,
                                    Clock clock, ApplicationEventPublisher events, PlanProfiles profiles) {
        this.catalog = catalog;
        this.store = store;
        this.registry = registry;
        this.clock = clock;
        this.events = events;
        this.profiles = profiles;
    }

    @Override
    @Transactional
    public SyncResult sync(UUID tenantId, String tenantSchema) {
        SyncResult result = run(tenantId, tenantSchema, true, null);
        evictAfterCommit(tenantSchema);
        return result;
    }

    @Override
    @Transactional
    public SyncResult syncSilently(UUID tenantId, String tenantSchema) {
        SyncResult result = run(tenantId, tenantSchema, true, null, false);
        evictAfterCommit(tenantSchema);
        return result;
    }

    @Override
    @Transactional
    public Optional<SyncResult> syncIfPlanType(UUID tenantId, String tenantSchema, String planType) {
        Objects.requireNonNull(planType, "planType");
        SyncResult result = run(tenantId, tenantSchema, true, planType);
        if (result == null) {
            return Optional.empty();
        }
        evictAfterCommit(tenantSchema);
        return Optional.of(result);
    }

    @Override
    @Transactional // not read-only: the plan read locks the subscription row (FOR UPDATE); nothing is written
    public SyncResult preview(UUID tenantId, String tenantSchema) {
        return run(tenantId, tenantSchema, false, null);
    }

    /** @param onlyPlanType when set, nothing is done ({@code null} returned) unless the plan has this type */
    private SyncResult run(UUID tenantId, String tenantSchema, boolean write, String onlyPlanType) {
        return run(tenantId, tenantSchema, write, onlyPlanType, true);
    }

    /** @param notify whether modules switched off are told to the owner ({@link ModuleLicencesChangedEvent}) */
    private SyncResult run(UUID tenantId, String tenantSchema, boolean write, String onlyPlanType, boolean notify) {
        Objects.requireNonNull(tenantId, "tenantId");
        String schema = TenantSchema.validate(tenantSchema);
        Instant now = clock.instant();
        Plan plan = store.readPlan(tenantId, schema);
        if (plan.unreadable()) {
            throw new PlanUnreadableException();
        }
        if (onlyPlanType != null && !onlyPlanType.equals(plan.planType())) {
            return null;
        }
        // Story 16.8 — which paid modules the plan type grants: its profile, read live (built-in defaults if absent)
        PlanProfile profile = profiles != null && plan.planType() != null ? profiles.profile(plan.planType()) : null;
        Map<String, PlanModuleRows.Desired> desired = PlanModuleRows.compute(plan, now, catalog.manifests(), profile);
        Map<String, StoredRow> stored = store.findRows(tenantId);
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int preserved = 0;
        List<Transition> disabled = new ArrayList<>();
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
                if (write && row.status() == TenantModuleStatus.DISABLED
                        && current.status() != TenantModuleStatus.DISABLED) {
                    disabled.add(new Transition(moduleId, catalog.find(moduleId).map(m -> m.displayName())
                            .orElse(moduleId), ModuleLicencesChangedEvent.Origin.PLAN,
                            ModuleLicencesChangedEvent.NewStatus.DISABLED, row.graceUntil()));
                }
            }
        }
        if (notify && !disabled.isEmpty()) {
            // Story 16.1: an existing row switched off by a plan write (grace over, downgrade) is told to the owner
            // like the nightly job's transitions — the job will find nothing left to change
            events.publishEvent(new ModuleLicencesChangedEvent(schema, disabled, null, now));
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
