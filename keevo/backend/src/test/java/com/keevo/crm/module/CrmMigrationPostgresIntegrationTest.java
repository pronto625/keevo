package com.keevo.crm.module;

import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.module.ModuleProvisioner;
import com.keevo.shared.module.ProvisioningResult;
import com.keevo.shared.module.adapter.out.persistence.FlywayModuleMigrations;
import com.keevo.shared.module.adapter.out.persistence.JdbcTenantModuleStateStore;
import com.keevo.shared.module.application.KeevoModuleDirectory;
import com.keevo.shared.module.application.ModuleCatalog;
import com.keevo.shared.module.application.ModuleProvisionerService;
import com.keevo.shared.module.application.ModuleRegistryService;
import com.keevo.shared.module.infrastructure.RequestScopedActiveModulesCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.9 — the crm module's {@code db/modules/crm/V1__crm_baseline.sql} on PostgreSQL, through the real
 * {@link ModuleProvisioner} and the shipped manifests: a fresh database of the shared container migrated with the
 * public Flyway, tenant schemas built by the real {@link TenantSchemaProvisioner} (the shape of every existing
 * tenant). With the crm existing-schema baseline (probe {@code clients}, version 1): an existing-shape tenant and a
 * drifted one are baselined at 1 without running V1 — only {@code flyway_history_crm} appears, every other table,
 * column, default, index and constraint unchanged, data untouched; a schema without the crm tables runs V1, which
 * builds the provisioner's shape; re-provisioning is a no-op.
 */
class CrmMigrationPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_crm_migration";
    private static final String A = "kv_crma01";
    private static final String B = "kv_crmb02";
    private static final String C = "kv_crmc03";
    private static final Map<String, UUID> IDS = Map.of(A, UUID.randomUUID(), B, UUID.randomUUID(),
            C, UUID.randomUUID());
    private static final List<String> CRM_TABLES = List.of("clients", "product_suppliers", "suppliers");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private ModuleProvisioner provisioner;

    @BeforeAll
    static void migratePublic() {
        String url = SharedPostgresContainer.freshDatabase(TEST_DB);
        Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration")
                .load().migrate();
        dataSource = new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password());
        jdbc = new JdbcTemplate(dataSource);
        for (String schema : List.of(A, B, C)) {
            jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                            + "max_stores, plan_type, schema_name, status, name) "
                            + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')",
                    IDS.get(schema), "KV-" + schema.substring(3).toUpperCase(), schema);
        }
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void freshTenants() {
        jdbc.update("DELETE FROM public.tenant_modules"); // test database only
        TenantSchemaProvisioner schemas = new TenantSchemaProvisioner(dataSource);
        for (String schema : List.of(A, B, C)) {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); // test database only
            schemas.provision(IDS.get(schema).toString(), schema);
        }
        ModuleCatalog catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        JdbcTenantModuleStateStore store = new JdbcTenantModuleStateStore(dataSource);
        ModuleRegistryService registry = new ModuleRegistryService(catalog, store,
                new RequestScopedActiveModulesCache(), Clock.systemUTC());
        provisioner = new ModuleProvisionerService(catalog, registry, store, store,
                new FlywayModuleMigrations(dataSource),
                KeevoModuleDirectory.of(catalog, List.of(new CrmModule(null))),
                TransactionOperations.withoutTransaction());
    }

    // ── catalogue snapshots (schema name stripped) ───────────────────────────────

    private static List<String> snapshot(String schema, List<String> onlyTables) {
        String filter = onlyTables == null ? "" : " AND %s = ANY (?)";
        List<String> out = new ArrayList<>();
        Object[] args = onlyTables == null ? new Object[]{schema}
                : new Object[]{schema, onlyTables.toArray(new String[0])};
        out.addAll(jdbc.queryForList("SELECT 'table ' || table_name FROM information_schema.tables "
                + "WHERE table_schema = ?" + filter.formatted("table_name") + " ORDER BY 1", String.class, args));
        out.addAll(jdbc.queryForList("SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || "
                + "COALESCE('(' || character_maximum_length || ')', '') || ' null=' || is_nullable || ' default=' || "
                + "COALESCE(column_default, '-') FROM information_schema.columns WHERE table_schema = ?"
                + filter.formatted("table_name") + " ORDER BY 1", String.class, args));
        out.addAll(jdbc.queryForList("SELECT 'index ' || replace(indexdef, ' ' || schemaname || '.', ' ') "
                + "FROM pg_indexes WHERE schemaname = ?" + filter.formatted("tablename") + " ORDER BY 1",
                String.class, args));
        out.addAll(jdbc.queryForList("SELECT 'constraint ' || c.relname || '.' || con.conname || ' ' || "
                + "replace(pg_get_constraintdef(con.oid), n.nspname || '.', '') FROM pg_constraint con "
                + "JOIN pg_class c ON c.oid = con.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ?" + filter.formatted("c.relname") + " ORDER BY 1", String.class, args));
        return out;
    }

    private static List<String> history(String schema) {
        return jdbc.queryForList("SELECT COALESCE(version, '-') || ':' || type || ':' || success FROM \"" + schema
                + "\".flyway_history_crm ORDER BY installed_rank", String.class);
    }

    private static List<String> withoutHistory(List<String> snapshot) {
        return snapshot.stream().filter(line -> !line.contains("flyway_history_crm")).toList();
    }

    // ── matrix ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("existing-shape tenant: baselined at 1 without running V1 — only flyway_history_crm appears")
    void existingTenantBaselined() {
        jdbc.update("INSERT INTO \"" + A + "\".clients (name, phone) VALUES ('Awa', '+237690000001')");
        List<String> before = snapshot(A, null);
        List<Map<String, Object>> clientsBefore = jdbc.queryForList("SELECT * FROM \"" + A + "\".clients");

        ProvisioningResult result = provisioner.provision(A, "crm");

        assertThat(result.migrationsApplied()).isZero();
        assertThat(result.toVersion()).isEqualTo("1");
        assertThat(history(A)).containsExactly("1:BASELINE:true");
        List<String> after = snapshot(A, null);
        assertThat(withoutHistory(after)).isEqualTo(before);
        assertThat(after).contains("table flyway_history_crm");
        assertThat(jdbc.queryForList("SELECT * FROM \"" + A + "\".clients")).isEqualTo(clientsBefore);
        assertThat(jdbc.queryForObject("SELECT provisioned_version FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND module_id = 'crm'", String.class, IDS.get(A))).isEqualTo("1");
        assertThat(snapshot(B, null)).noneMatch(line -> line.contains("flyway_history_crm")); // others untouched

        ProvisioningResult again = provisioner.provision(A, "crm");
        assertThat(again.migrationsApplied()).isZero();
        assertThat(snapshot(A, null)).isEqualTo(after);
    }

    @Test
    @DisplayName("drifted tenant (indexes, defaults, FK missing): baselined as is — nothing repaired, nothing stamped")
    void driftedTenantUntouched() {
        jdbc.update("INSERT INTO \"" + C + "\".clients (id, name, phone, archived, created_at, updated_at) "
                + "VALUES (?, 'Awa', '+237690000001', true, now() - interval '3 days', now() - interval '3 days')",
                UUID.randomUUID());
        for (String index : List.of("idx_clients_name", "idx_clients_archived", "idx_suppliers_name",
                "idx_product_suppliers_supplier")) {
            jdbc.execute("DROP INDEX \"" + C + "\"." + index); // test database only: emulate the drift
        }
        for (String table : List.of("clients", "suppliers")) {
            for (String column : List.of("id", "archived", "created_at", "updated_at")) {
                jdbc.execute("ALTER TABLE \"" + C + "\"." + table + " ALTER COLUMN " + column + " DROP DEFAULT");
            }
        }
        jdbc.execute("ALTER TABLE \"" + C + "\".product_suppliers DROP CONSTRAINT product_suppliers_product_id_fkey");
        List<String> before = snapshot(C, null);
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM \"" + C + "\".clients");

        provisioner.provision(C, "crm");

        assertThat(withoutHistory(snapshot(C, null))).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM \"" + C + "\".clients")).isEqualTo(rowsBefore);
        assertThat(history(C)).containsExactly("1:BASELINE:true");
    }

    @Test
    @DisplayName("schema without crm tables: V1 executes and builds the TenantSchemaProvisioner shape")
    void v1RunsWithoutCrmTables() {
        List<String> reference = snapshot(A, CRM_TABLES);
        // test database only: remove the crm tables (and the sales → clients FK with them)
        jdbc.execute("DROP TABLE \"" + B + "\".product_suppliers, \"" + B + "\".suppliers, \"" + B
                + "\".clients CASCADE");

        ProvisioningResult result = provisioner.provision(B, "crm");

        assertThat(result.migrationsApplied()).isEqualTo(1);
        assertThat(history(B)).containsExactly("0:BASELINE:true", "1:SQL:true");
        List<String> expected = reference.stream()
                .filter(line -> !line.contains("_sync_keyset")).toList(); // owned by the sync keyset index job
        assertThat(snapshot(B, CRM_TABLES)).isEqualTo(expected);
    }

    @Test
    @DisplayName("without the module's existing-schema baseline (no KeevoModule bean): V1 would run — the bean decides")
    void baselineComesFromTheModule() {
        assertThat(new CrmModule(null).existingSchemaBaseline()).contains(CrmModule.EXISTING_SCHEMA);
        assertThat(CrmModule.EXISTING_SCHEMA.probeTable()).isEqualTo("clients");
        assertThat(CrmModule.EXISTING_SCHEMA.version()).isEqualTo("1");
    }
}
