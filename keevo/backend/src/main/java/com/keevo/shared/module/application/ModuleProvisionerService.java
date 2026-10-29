package com.keevo.shared.module.application;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import com.keevo.shared.module.ModuleManifest;
import com.keevo.shared.module.ModuleNotActiveException;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ModuleProvisioningException;
import com.keevo.shared.module.ModuleRegistry;
import com.keevo.shared.module.ProvisioningReport;
import com.keevo.shared.module.ProvisioningResult;
import com.keevo.shared.module.TenantModuleStatus;
import com.keevo.shared.module.TenantNotFoundException;
import com.keevo.shared.module.application.port.ModuleMigrationPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort;
import com.keevo.shared.module.application.port.TenantModuleProvisioningPort.LiveTenant;
import com.keevo.shared.module.application.port.TenantModuleStatePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

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

    public ModuleProvisionerService(ModuleCatalog catalog, ModuleRegistry registry, TenantModuleStatePort states,
                                    TenantModuleProvisioningPort rows, ModuleMigrationPort migrations) {
        this.catalog = catalog;
        this.registry = registry;
        this.states = states;
        this.rows = rows;
        this.migrations = migrations;
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
        Map<String, TenantModuleStatus> tenantRows = states.findStatuses(schema)
                .orElseThrow(TenantNotFoundException::new);
        boolean rowExists = tenantRows.containsKey(id);
        if (!rowExists && !module.isCore() && !registry.isActive(schema, id)) {
            throw new ModuleNotActiveException(id);
        }

        ModuleMigrationPort.Outcome outcome;
        try {
            outcome = migrations.migrate(schema, id);
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
