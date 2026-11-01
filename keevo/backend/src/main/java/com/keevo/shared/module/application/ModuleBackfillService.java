package com.keevo.shared.module.application;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleBackfill;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ModuleState;
import com.keevo.shared.module.ProvisioningResult;
import com.keevo.shared.module.application.port.ModuleMigrationPort;
import com.keevo.shared.module.application.port.PlanModuleRowStore;
import com.keevo.shared.module.application.port.PlanModuleRowStore.LiveTenant;
import com.keevo.shared.module.application.port.PlanModuleRowStore.StoredRow;
import com.keevo.shared.module.application.port.PlanModuleRowSync;
import com.keevo.shared.module.application.port.PlanModuleRowSync.SyncResult;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ModuleBackfill} of the core (Story 16.5). For each live tenant, in code order:
 * <ol>
 *   <li>the platform tenant and tenants without a schema are skipped;</li>
 *   <li>{@link PlanModuleRowSync#sync} (its own transaction) — or {@link PlanModuleRowSync#preview} in a dry run; an
 *       unreadable plan or any other fault is reported and the tenant is left untouched (no provisioning);</li>
 *   <li>every module that ships migrations and is {@link ModuleState#ENABLED} for the tenant is provisioned through
 *       {@link ModuleProvisioner#provision} unless it is already up to date (Flyway history at the latest shipped
 *       version and {@code provisioned_version} recorded) — counted only in a dry run.</li>
 * </ol>
 * One run at a time across instances (ShedLock lock {@value #LOCK_NAME}); a second call while it is held answers
 * {@code MODULE_BACKFILL_RUNNING}.
 */
@Service
public class ModuleBackfillService implements ModuleBackfill {

    private static final Logger log = LoggerFactory.getLogger(ModuleBackfillService.class);

    static final String LOCK_NAME = "moduleBackfill";
    static final Duration LOCK_AT_MOST_FOR = Duration.ofHours(2);

    private final ModuleCatalog catalog;
    private final ModuleRegistry registry;
    private final PlanModuleRowSync rowSync;
    private final PlanModuleRowStore rows;
    private final ModuleProvisioner provisioner;
    private final ModuleMigrationPort migrations;
    private final LockProvider lockProvider;
    private final Clock clock;

    public ModuleBackfillService(ModuleCatalog catalog, ModuleRegistry registry, PlanModuleRowSync rowSync,
                                 PlanModuleRowStore rows, ModuleProvisioner provisioner,
                                 ModuleMigrationPort migrations, LockProvider lockProvider, Clock clock) {
        this.catalog = catalog;
        this.registry = registry;
        this.rowSync = rowSync;
        this.rows = rows;
        this.provisioner = provisioner;
        this.migrations = migrations;
        this.lockProvider = lockProvider;
        this.clock = clock;
    }

    @Override
    public BackfillReport run(boolean dryRun) {
        Optional<SimpleLock> lock = lockProvider.lock(
                new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO));
        if (lock.isEmpty()) {
            throw new DomainException(ErrorCode.MODULE_BACKFILL_RUNNING, "A module backfill is already running");
        }
        try {
            return backfill(dryRun);
        } finally {
            try {
                lock.get().unlock();
            } catch (RuntimeException e) {
                log.warn("Module backfill: unlock failed — the lock expires by itself ({})",
                        e.getClass().getSimpleName());
            }
        }
    }

    private BackfillReport backfill(boolean dryRun) {
        List<ModuleManifest> migrating = migratingModules();
        int processed = 0;
        int skipped = 0;
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int preserved = 0;
        int provisionedCount = 0;
        List<TenantChange> changes = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();

        for (LiveTenant tenant : rows.liveTenants()) {
            if (!TenantSchema.isValid(tenant.tenantSchema()) || !tenant.schemaPresent()) {
                skipped++; // the platform tenant ("public"), or a schema not created yet
                continue;
            }
            String schema = tenant.tenantSchema();
            SyncResult result;
            try {
                result = dryRun ? rowSync.preview(tenant.id(), schema) : rowSync.sync(tenant.id(), schema);
            } catch (PlanModuleRowSync.PlanUnreadableException e) {
                failures.add(new Failure(tenant.code(), "Abonnement illisible : modules laissés inchangés"));
                log.warn("Module backfill: tenant {} skipped (plan unreadable)", tenant.code());
                continue;
            } catch (RuntimeException e) {
                failures.add(new Failure(tenant.code(), "Mise à jour des modules impossible : réessayez plus tard"));
                log.warn("Module backfill: rows of tenant {} not synced ({})", tenant.code(),
                        e.getClass().getSimpleName());
                continue;
            }
            processed++;
            inserted += result.inserted();
            updated += result.updated();
            unchanged += result.unchanged();
            preserved += result.preserved();

            List<String> provisioned = provision(tenant, migrating, dryRun, failures);
            provisionedCount += provisioned.size();
            if (result.written() > 0 || !provisioned.isEmpty()) {
                changes.add(new TenantChange(tenant.code(), result.inserted(), result.updated(), provisioned));
            }
        }
        log.info("Module backfill{}: {} tenant(s) processed, {} skipped, {} failed — rows {} inserted, {} updated, "
                        + "{} unchanged, {} preserved; {} module provisioning(s)", dryRun ? " (dry run)" : "",
                processed, skipped, failures.size(), inserted, updated, unchanged, preserved, provisionedCount);
        return new BackfillReport(dryRun, processed, skipped, inserted, updated, unchanged, preserved,
                provisionedCount, changes, failures);
    }

    /** The modules of the tenant to provision (display names): enabled, shipping migrations, not up to date. */
    private List<String> provision(LiveTenant tenant, List<ModuleManifest> migrating, boolean dryRun,
                                   List<Failure> failures) {
        String schema = tenant.tenantSchema();
        List<String> provisioned = new ArrayList<>();
        Map<String, ModuleState> states;
        Map<String, StoredRow> stored;
        try {
            registry.evict(schema);
            states = registry.moduleStates(schema);
            stored = rows.findRows(tenant.id());
        } catch (RuntimeException e) {
            failures.add(new Failure(tenant.code(), "Lecture des modules impossible : tables non vérifiées"));
            log.warn("Module backfill: modules of tenant {} unreadable ({})", tenant.code(),
                    e.getClass().getSimpleName());
            return provisioned;
        }
        for (ModuleManifest module : migrating) {
            String id = module.id();
            if (states.get(id) != ModuleState.ENABLED) {
                continue;
            }
            try {
                if (upToDate(schema, id, stored.get(id))) {
                    continue;
                }
                if (!dryRun) {
                    ProvisioningResult result = provisioner.provision(schema, id);
                    log.debug("Module backfill: '{}' provisioned for tenant {} ({} → {})", id, tenant.code(),
                            result.fromVersion(), result.toVersion());
                }
                provisioned.add(module.displayName());
            } catch (RuntimeException e) {
                failures.add(new Failure(tenant.code(),
                        "Création des tables du module « " + module.displayName() + " » échouée : réessayez"));
                log.warn("Module backfill: '{}' provisioning failed for tenant {} ({})", id, tenant.code(),
                        e.getClass().getSimpleName());
            }
        }
        return provisioned;
    }

    /** Flyway history at the latest shipped version and that version recorded on the module's row. */
    private boolean upToDate(String schema, String moduleId, StoredRow row) {
        String latest = migrations.latestVersion(moduleId);
        if (latest == null || row == null || row.provisionedVersion() == null) {
            return false;
        }
        String current = migrations.currentVersion(schema, moduleId);
        return current != null && sameVersion(latest, current) && sameVersion(latest, row.provisionedVersion());
    }

    private static boolean sameVersion(String a, String b) {
        return MigrationVersion.fromVersion(a).equals(MigrationVersion.fromVersion(b));
    }

    /** Installed tenant modules shipping migrations, each after its {@code requires} (id order otherwise). */
    private List<ModuleManifest> migratingModules() {
        List<ModuleManifest> sorted = new ArrayList<>(catalog.manifests());
        sorted.sort(Comparator.comparing(ModuleManifest::id));
        List<ModuleManifest> order = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ModuleManifest module : sorted) {
            visit(module, seen, order);
        }
        return order.stream()
                .filter(m -> m.category() != ModuleCategory.PLATFORM && migrations.hasMigrations(m.id()))
                .toList();
    }

    private void visit(ModuleManifest module, java.util.Set<String> seen, List<ModuleManifest> order) {
        if (!seen.add(module.id())) {
            return;
        }
        for (String dependency : module.requires()) {
            catalog.find(dependency).ifPresent(d -> visit(d, seen, order));
        }
        order.add(module);
    }
}
