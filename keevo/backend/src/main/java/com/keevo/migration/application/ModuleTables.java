package com.keevo.migration.application;

import com.keevo.shared.module.application.port.ModuleMigrationPort;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Story 24.4 — the tables of a paid module ({@code invoicing}, {@code expenses}) in an imported tenant's schema,
 * whatever its plan (D9): the Bref data exists, the plan only decides who may use it (24.5). The module's own Flyway
 * scripts are applied ({@link ModuleMigrationPort#migrate}, idempotent, isolated history table), only when the schema
 * is behind the shipped version (one history read otherwise). Never inside a transaction: Flyway uses its own
 * connection and commits each script.
 */
@Component
public class ModuleTables {

    private final ModuleMigrationPort migrations;

    public ModuleTables(ModuleMigrationPort migrations) {
        this.migrations = migrations;
    }

    public void ensure(String tenantSchema, String moduleId) {
        String latest = migrations.latestVersion(moduleId);
        if (latest != null && Objects.equals(latest, migrations.currentVersion(tenantSchema, moduleId))) {
            return;
        }
        migrations.migrate(tenantSchema, moduleId);
    }
}
