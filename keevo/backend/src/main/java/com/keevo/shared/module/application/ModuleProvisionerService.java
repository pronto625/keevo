package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleCategory;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleNotActiveException;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleProvisioningException;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ProvisioningReport;
import com.keevo.shared.module.ProvisioningResult;
import com.keevo.shared.module.TenantNotFoundException;
import com.keevo.shared.module.application.port.ModuleMigrationPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort.LiveTenant;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import com.keevo.shared.module.KeevoModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ModuleProvisioner} of the core (Story 13.4): validates the input, checks that the module may be provisioned
 * for the tenant (row present, or active through the free rule), runs the module's migrations
 * ({@link ModuleMigrationPort}) and records {@code tenant_modules.provisioned_version}
 * ({@link TenantModuleProvisioningPort}), then evicts the registry's request cache for the tenant.
 */
@Service
public class ModuleProvisionerService implements ModuleProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ModuleProvisionerService.class);

    private final ModuleCatalog catalog;
    private final ModuleRegistry registry;
    private final TenantModuleStatePort states;
    private final TenantModuleProvisioningPort rows;
    private final ModuleMigrationPort migrations;
    private final KeevoModuleDirectory modules;
    private final TransactionOperations moduleTransaction;

    /** Without module beans (no existing-schema baseline) nor transaction manager — tests. */
    public ModuleProvisionerService(ModuleCatalog catalog, ModuleRegistry registry, TenantModuleStatePort states,
                                    TenantModuleProvisioningPort rows, ModuleMigrationPort migrations) {
        this(catalog, registry, states, rows, migrations, KeevoModuleDirectory.empty(catalog),
                TransactionOperations.withoutTransaction());
    }

    /**
     * @param moduleTransaction how {@link #provisionActiveModules} isolates each module (production: a
     *                          {@code REQUIRES_NEW} transaction per module)
     */
    public ModuleProvisionerService(ModuleCatalog catalog, ModuleRegistry registry, TenantModuleStatePort states,
                                    TenantModuleProvisioningPort rows, ModuleMigrationPort migrations,
                                    KeevoModuleDirectory modules, TransactionOperations moduleTransaction) {
        this.catalog = catalog;
        this.registry = registry;
        this.states = states;
        this.rows = rows;
        this.migrations = migrations;
        this.modules = modules;
        this.moduleTransaction = moduleTransaction;
    }

    @Autowired
    public ModuleProvisionerService(ModuleCatalog catalog, ModuleRegistry registry, TenantModuleStatePort states,
                                    TenantModuleProvisioningPort rows, ModuleMigrationPort migrations,
                                    KeevoModuleDirectory modules, PlatformTransactionManager transactionManager) {
        this(catalog, registry, states, rows, migrations, modules, requiresNew(transactionManager));
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Override
    public boolean hasMigrations(String moduleId) {
        return migrations.hasMigrations(requireModule(moduleId).id());
    }

    @Override
    public ProvisioningResult provision(String tenantSchema, String moduleId) {
        // the platform schema "public" fails the tenant format too: never provisioned
        String schema = TenantSchema.validate(tenantSchema);
        ModuleManifest module = requireModule(moduleId);
        String id = module.id();
        if (!migrations.hasMigrations(id)) {
            return new ProvisioningResult(id, null, null, 0);
        }
        Map<String, TenantModuleStatePort.Row> tenantRows = states.findRows(schema)
                .orElseThrow(TenantNotFoundException::new);
        boolean rowExists = tenantRows.containsKey(id);
        if (!rowExists && !module.isCore() && !registry.isActive(schema, id)) {
            throw new ModuleNotActiveException(id);
        }

        ModuleMigrationPort.Outcome outcome;
        try {
            outcome = migrations.migrate(schema, id,
                    modules.module(id).flatMap(KeevoModule::existingSchemaBaseline).orElse(null));
        } catch (ModuleProvisioningException e) {
            // earlier scripts of the run are committed: record what is really in the schema, then rethrow
            recordVersion(schema, module, rowExists, e.highestAppliedVersion());
            throw e;
        }
        recordVersion(schema, module, rowExists, outcome.toVersion());
        if (outcome.applied() > 0) {
            log.info("Module '{}' provisioned: {} migration(s), version {} → {}", id, outcome.applied(),
                    outcome.fromVersion(), outcome.toVersion());
        }
        return new ProvisioningResult(id, outcome.fromVersion(), outcome.toVersion(), outcome.applied());
    }

    /**
     * Writes {@code provisioned_version}: update of the existing row, else (non-core) insert of an ACTIVE/FREE row.
     * A {@code null} version (nothing versioned applied, e.g. repeatable scripts only) writes nothing — never a row
     * without a version. A write that touches no row (a higher version stored by another node, or the tenant row
     * gone) logs a WARN. A database failure becomes a {@link ModuleProvisioningException}.
     */
    private void recordVersion(String schema, ModuleManifest module, boolean rowExists, String version) {
        String id = module.id();
        if (version == null || (!rowExists && module.isCore())) {
            registry.evict(schema);
            return;
        }
        int written;
        try {
            written = rowExists ? rows.updateProvisionedVersion(schema, id, version)
                    : rows.insertActiveFree(schema, id, version);
        } catch (DataAccessException e) {
            throw new ModuleProvisioningException(id, schema, "ROW_WRITE/" + e.getClass().getSimpleName(), version);
        } finally {
            registry.evict(schema);
        }
        if (written == 0) {
            log.warn("Module '{}': provisioned_version {} not recorded (a higher version is already stored, or the "
                    + "tenant row is gone)", id, version);
        }
    }

    @Override
    public ProvisioningReport provisionAll(String moduleId) {
        String id = requireModule(moduleId).id();
        List<String> provisioned = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<ProvisioningReport.Failure> failures = new ArrayList<>();
        for (LiveTenant tenant : rows.liveTenants()) {
            if (!TenantSchema.isValid(tenant.tenantSchema())) {
                // Story 13.9: the platform tenant (schema "public") is not a tenant schema — never provisioned
                skipped.add(tenant.code());
                log.debug("Module '{}' batch: tenant {} skipped (not a tenant schema)", id, tenant.code());
                continue;
            }
            if (!tenant.schemaPresent()) {
                skipped.add(tenant.code());
                log.debug("Module '{}' batch: tenant {} skipped (schema not created yet)", id, tenant.code());
                continue;
            }
            try {
                provision(tenant.tenantSchema(), id);
                provisioned.add(tenant.code());
            } catch (ModuleNotActiveException | TenantNotFoundException e) {
                skipped.add(tenant.code());
                log.debug("Module '{}' batch: tenant {} skipped ({})", id, tenant.code(), e.getMessage());
            } catch (ModuleProvisioningException | IllegalArgumentException e) {
                // our messages carry no schema value nor SQL text
                failures.add(new ProvisioningReport.Failure(tenant.code(), e.getMessage()));
                log.warn("Module '{}' provisioning failed for tenant {} ({})", id, tenant.code(),
                        e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                failures.add(new ProvisioningReport.Failure(tenant.code(), e.getClass().getSimpleName()));
                log.warn("Module '{}' provisioning failed for tenant {} ({})", id, tenant.code(),
                        e.getClass().getSimpleName());
            }
        }
        log.info("Module '{}' batch provisioning: {} provisioned, {} skipped, {} failed", id, provisioned.size(),
                skipped.size(), failures.size());
        return new ProvisioningReport(id, provisioned, skipped, failures);
    }

    /**
     * Each module in its own transaction ({@code REQUIRES_NEW}, {@link #moduleTransaction}): it runs after the
     * registration commit, where the original transaction's resources may still be bound to the thread — the row
     * writes must commit on their own, and one module's failure must not roll back the others.
     */
    @Override
    public List<ProvisioningResult> provisionActiveModules(String tenantSchema) {
        String schema = TenantSchema.validate(tenantSchema);
        List<ProvisioningResult> results = new ArrayList<>();
        for (ModuleManifest module : dependencyOrder()) {
            String id = module.id();
            if (module.category() == ModuleCategory.PLATFORM || !migrations.hasMigrations(id)) {
                continue;
            }
            try {
                ProvisioningResult result = moduleTransaction.execute(status ->
                        registry.isActive(schema, id) ? provision(schema, id) : null);
                if (result != null) {
                    results.add(result);
                }
            } catch (ModuleNotActiveException | TenantNotFoundException e) {
                log.debug("Module '{}' not provisioned for a new tenant ({})", id, e.getMessage());
            } catch (RuntimeException e) {
                // our messages carry no schema value nor SQL text; the tenant is named by its code
                log.error("Module '{}' provisioning failed for new tenant {} ({}) — the other modules go on; "
                        + "provision it again on demand (provisionAll)", id, tenantCode(schema),
                        e.getClass().getSimpleName());
            }
        }
        return List.copyOf(results);
    }

    /** The tenant code of a live tenant schema, for logs ({@code ?} when unknown). */
    private String tenantCode(String schema) {
        try {
            return rows.liveTenants().stream().filter(t -> schema.equals(t.tenantSchema()))
                    .map(LiveTenant::code).findFirst().orElse("?");
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Installed manifests, each after its {@code requires}, id order otherwise. */
    private List<ModuleManifest> dependencyOrder() {
        List<ModuleManifest> sorted = new ArrayList<>(catalog.manifests());
        sorted.sort(java.util.Comparator.comparing(ModuleManifest::id));
        List<ModuleManifest> order = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ModuleManifest module : sorted) {
            visit(module, seen, order);
        }
        return order;
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

    /** The installed manifest; null/blank/unknown ids throw without echoing the raw value. */
    private ModuleManifest requireModule(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("A module id is required");
        }
        Optional<ModuleManifest> module = catalog.find(moduleId);
        return module.orElseThrow(
                () -> new IllegalArgumentException("Unknown module (length=" + moduleId.length() + ")"));
    }
}
