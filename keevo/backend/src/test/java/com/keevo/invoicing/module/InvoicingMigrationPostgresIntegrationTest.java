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
    private static final List<String> TABLES = List.of("credit_note_counters", "credit_note_items",
            "credit_notes", "invoice_counters", "invoice_items", "invoices", "invoicing_settings",
            "sale_invoice_states");

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
    @DisplayName("trial tenant without row: tables + history V1..V7 (15.5b), no tenant_modules row; re-provision is a no-op")
    void trialTenantNoRow() {
        assertThat(tables(A)).isEmpty();
        assertThat(migrations.currentVersion(A, "invoicing")).isNull();
        assertThat(migrations.latestVersion("invoicing")).isEqualTo("7");

        ProvisioningResult result = provisioner.provision(A, "invoicing");

        assertThat(result.migrationsApplied()).isEqualTo(7);
        assertThat(result.toVersion()).isEqualTo("7");
        assertThat(tables(A)).containsExactlyElementsOf(TABLES);
        assertThat(migrations.currentVersion(A, "invoicing")).isEqualTo("7");
        // Story 15.5b — the keyset index of the invoices sync pull, as SyncKeysetIndexJob expects it
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                String.class, A, "idx_invoices_sync_keyset"))
                .endsWith("invoices USING btree (updated_at, ((id)::text))");
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
                + "AND module_id = 'invoicing'", String.class, IDS.get(A))).isEqualTo("7");
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

    @Test
    @DisplayName("15.5b — sync delta keyset: two pages (limit 2) disjoint, complete, in (updated_at, id) order; "
            + "a deleted draft is a tombstone, still in the delta")
    void deltaKeysetPages() {
        provisioner.provision(A, "invoicing");
        JdbcInvoiceRepository repository = new JdbcInvoiceRepository(new JdbcTemplate(dataSource));
        TenantContext.setCurrentTenant(A);
        java.time.Instant t0 = java.time.Instant.parse("2026-10-30T10:00:00Z");
        // two rows share the same updated_at: the id breaks the tie
        List<java.time.Instant> times = List.of(t0, t0, t0.plusSeconds(60), t0.plusSeconds(120));
        List<java.util.UUID> ids = new java.util.ArrayList<>();
        for (java.time.Instant at : times) {
            java.util.UUID id = java.util.UUID.randomUUID();
            ids.add(id);
            jdbc.update("INSERT INTO \"" + A + "\".invoices (id, client_name, vat_registered, vat_rate, "
                    + "prices_include_vat, created_at, updated_at) VALUES (?, 'Client', false, 0, true, ?, ?)",
                    id, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at));
        }
        jdbc.update("UPDATE \"" + A + "\".invoices SET deleted_at = updated_at WHERE id = ?", ids.get(3));
        List<String> expected = new java.util.ArrayList<>(ids.subList(0, 2).stream().map(Object::toString).sorted()
                .toList());
        expected.add(ids.get(2).toString());
        expected.add(ids.get(3).toString());

        var first = repository.delta(java.time.Instant.EPOCH, null, null, 2);
        var lastOfFirst = first.get(1).invoice();
        var second = repository.delta(java.time.Instant.EPOCH, lastOfFirst.updatedAt(), lastOfFirst.id().toString(), 2);
        var third = repository.delta(java.time.Instant.EPOCH, second.get(1).invoice().updatedAt(),
                second.get(1).invoice().id().toString(), 2);

        List<String> seen = new java.util.ArrayList<>();
        first.forEach(d -> seen.add(d.invoice().id().toString()));
        second.forEach(d -> seen.add(d.invoice().id().toString()));
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(third).isEmpty();
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(second.get(1).deleted()).isTrue();
        assertThat(first.get(0).deleted()).isFalse();
        // and the API reads never see it
        assertThat(repository.findById(ids.get(3))).isEmpty();
        assertThat(repository.findAnyById(ids.get(3))).isPresent();
        TenantContext.clear();
    }
}
