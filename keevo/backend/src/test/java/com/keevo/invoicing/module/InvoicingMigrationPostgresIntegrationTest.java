package com.keevo.invoicing.module;

import com.keevo.invoicing.invoice.adapter.out.persistence.JdbcInvoiceRepository;
import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.module.ModuleNotActiveException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 14.1 — {@code db/modules/invoicing/V1__invoicing.sql} on PostgreSQL through the real {@link ModuleProvisioner}
 * and the shipped manifests: tenant schemas built by the real {@link TenantSchemaProvisioner} (registration trial).
 * A trial tenant gets the four module tables and its history at V1 but <b>no</b> {@code tenant_modules} row (paid
 * module); a row is only updated; a Free tenant is refused before any DDL; the counter is gapless and a rolled-back
 * increment is given back.
 */
class InvoicingMigrationPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_invoicing_migration";
    private static final String A = "kv_inva01";
    private static final String B = "kv_invb02";
    private static final Map<String, UUID> IDS = Map.of(A, UUID.randomUUID(), B, UUID.randomUUID());
    private static final List<String> TABLES = List.of("invoice_counters", "invoice_items", "invoices",
            "invoicing_settings");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private ModuleProvisioner provisioner;
    private FlywayModuleMigrations migrations;

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
        for (String schema : List.of(A, B)) {
            jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                            + "max_stores, plan_type, schema_name, status, name) "
                            + "VALUES (?, now(), now(), ?, 3, 500, 1, 'PREMIUM_TRIAL', ?, 'ACTIVE', 'Test')",
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
        for (String schema : List.of(A, B)) {
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); // test database only
            schemas.provision(IDS.get(schema).toString(), schema); // seeds a PREMIUM_TRIAL subscription
        }
        ModuleCatalog catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        JdbcTenantModuleStateStore store = new JdbcTenantModuleStateStore(dataSource);
        ModuleRegistryService registry = new ModuleRegistryService(catalog, store,
                new RequestScopedActiveModulesCache(), java.time.Clock.systemUTC());
        migrations = new FlywayModuleMigrations(dataSource);
        provisioner = new ModuleProvisionerService(catalog, registry, store, store, migrations,
                KeevoModuleDirectory.of(catalog, List.of(new InvoicingModule())),
                TransactionOperations.withoutTransaction());
    }

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    private static List<String> tables(String schema) {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name = ANY (?) ORDER BY 1", String.class, schema, TABLES.toArray(new String[0]));
    }

    private static int rows(String schema) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND module_id = 'invoicing'", Integer.class, IDS.get(schema));
    }

    @Test
    @DisplayName("trial tenant without row: tables + history V1..V3 (14.4), no tenant_modules row; re-provision is a no-op")
    void trialTenantNoRow() {
        assertThat(tables(A)).isEmpty();
        assertThat(migrations.currentVersion(A, "invoicing")).isNull();
        assertThat(migrations.latestVersion("invoicing")).isEqualTo("3");

        ProvisioningResult result = provisioner.provision(A, "invoicing");

        assertThat(result.migrationsApplied()).isEqualTo(3);
        assertThat(result.toVersion()).isEqualTo("3");
        assertThat(tables(A)).containsExactlyElementsOf(TABLES);
        assertThat(migrations.currentVersion(A, "invoicing")).isEqualTo("3");
        assertThat(rows(A)).isZero();
        assertThat(tables(B)).as("other tenants untouched").isEmpty();

        assertThat(provisioner.provision(A, "invoicing").migrationsApplied()).isZero();
        assertThat(rows(A)).isZero();
    }

    @Test
    @DisplayName("tenant with an ACTIVE row: provisioned_version recorded on the row")
    void rowUpdated() {
        jdbc.update("INSERT INTO public.tenant_modules (tenant_id, module_id, status, tier) "
                + "VALUES (?, 'invoicing', 'ACTIVE', 'PREMIUM')", IDS.get(A));
        provisioner.provision(A, "invoicing");
        assertThat(jdbc.queryForObject("SELECT provisioned_version FROM public.tenant_modules WHERE tenant_id = ? "
                + "AND module_id = 'invoicing'", String.class, IDS.get(A))).isEqualTo("3");
    }

    @Test
    @DisplayName("Free tenant: refused before any DDL (no table, no history, no row)")
    void freeTenantRefused() {
        jdbc.update("UPDATE \"" + B + "\".subscriptions SET plan_type = 'FREE', expires_at = NULL");
        assertThatThrownBy(() -> provisioner.provision(B, "invoicing")).isInstanceOf(ModuleNotActiveException.class);
        assertThat(tables(B)).isEmpty();
        assertThat(migrations.currentVersion(B, "invoicing")).isNull();
        assertThat(rows(B)).isZero();
    }

    @Test
    @DisplayName("ensureProvisioned on an un-provisioned trial tenant creates the tables once")
    void lazyProvisioning() {
        provisioner.ensureProvisioned(A, "invoicing");
        assertThat(tables(A)).containsExactlyElementsOf(TABLES);
        provisioner.ensureProvisioned(A, "invoicing");
        assertThat(rows(A)).isZero();
    }

    @Test
    @DisplayName("counter: 1, 2, 3 per year; a rolled-back increment is given back (no gap)")
    void gaplessCounter() {
        provisioner.provision(A, "invoicing");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcInvoiceRepository repository = new JdbcInvoiceRepository(new JdbcTemplate(dataSource));
        TenantContext.setCurrentTenant(A);

        Long first = tx.execute(st -> repository.nextNumber(2026));
        assertThat(first).isEqualTo(1L);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            assertThat(repository.nextNumber(2026)).isEqualTo(2L);
            throw new IllegalStateException("failure after the increment");
        })).isInstanceOf(IllegalStateException.class);
        Long again = tx.execute(st -> repository.nextNumber(2026));
        Long nextYear = tx.execute(st -> repository.nextNumber(2027));
        assertThat(again).as("rolled-back number reused").isEqualTo(2L);
        assertThat(nextYear).as("new year restarts").isEqualTo(1L);
    }
}
