package com.keevo.shared.infrastructure.persistence;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.module.FoundationScripts;
import com.keevo.shared.module.FoundationUpgrade;
import com.keevo.shared.module.ModuleProvisioner;
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
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 16.12 — the foundation module migrations are the single author of the tenant tables, on PostgreSQL with the
 * shipped manifests and scripts, the real {@link TenantSchemaProvisioner}, {@link ModuleProvisionerService} and
 * Flyway: registration builds exactly the reference shape and records every foundation history; the scripts are
 * idempotent; a drifted tenant (old LIKE copy) gets its indexes, defaults and primary keys but no foreign key; a
 * tenant built before 16.12 with data is upgraded at boot without any row changed, and a second boot provisions
 * nothing; a failure after the scripts leaves no schema.
 */
@DisplayName("Foundation module migrations on PostgreSQL (Story 16.12)")
class FoundationMigrationsPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_foundation_migrations";

    /** Indexes of the reference shape not created by the scripts: keyset (job-owned) and unique-constraint backed. */
    private static final Set<String> CONSTRAINT_INDEXES = Set.of("roles_name_key", "users_phone_number_key",
            "user_roles_user_id_role_id_key", "uq_stock_level", "uq_day_closure_store_date", "uq_device_tokens_token",
            "uq_cooldown_product_store_type");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private TenantSchemaProvisioner tenants;
    private ModuleProvisioner modules;
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
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    @BeforeEach
    void services() {
        // every test starts with no live tenant: the boot upgrade only sees the tenants of the test
        jdbc.update("UPDATE public.tenants SET status = 'DELETED'");
        tenants = new TenantSchemaProvisioner(dataSource);
        ModuleCatalog catalog = new ModuleCatalog(new PathMatchingResourcePatternResolver());
        JdbcTenantModuleStateStore store = new JdbcTenantModuleStateStore(dataSource);
        ModuleRegistryService registry = new ModuleRegistryService(catalog, store,
                new RequestScopedActiveModulesCache(), Clock.systemUTC());
        migrations = new FlywayModuleMigrations(dataSource);
        modules = new ModuleProvisionerService(catalog, registry, store, store, migrations,
                KeevoModuleDirectory.empty(catalog), TransactionOperations.withoutTransaction());
    }

    // ── registration ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("registration: the reference shape, every foundation history at its latest version, seeds present")
    void registration() throws Exception {
        String schema = register("kv_fmreg1", "KV-FMREG1");

        assertShapeIsReference(schema);
        Map<String, String> latest = FoundationScripts.fromClasspath().latestVersions();
        assertThat(latest.keySet()).containsExactlyInAnyOrder("core", "store", "catalog", "inventory", "crm", "pos",
                "sync", "reporting", "messaging");
        latest.forEach((module, version) ->
                assertThat(migrations.currentVersion(schema, module)).as(module).isEqualTo(version));
        // core never has a row; every other foundation module is provisioned at its latest version (19.11: messaging 3)
        Map<String, String> provisioned = new LinkedHashMap<>(latest);
        provisioned.remove("core");
        assertThat(jdbc.queryForList("SELECT module_id || ':' || tm.provisioned_version FROM public.tenant_modules tm "
                + "JOIN public.tenants t ON t.id = tm.tenant_id WHERE t.schema_name = ?", String.class, schema))
                .containsExactlyInAnyOrderElementsOf(provisioned.entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue()).toList());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".roles", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".subscriptions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT name FROM \"" + schema + "\".stores", String.class))
                .containsExactly("Ma Boutique");
    }

    @Test
    @DisplayName("registration failure after the scripts: nothing committed, no schema left")
    void registrationFailureLeavesNoSchema() {
        List<FoundationScripts.Script> scripts = new ArrayList<>(FoundationScripts.fromClasspath().scripts());
        scripts.add(new FoundationScripts.Script("broken", "1", "V1__broken.sql", "SELECT * FROM missing_table"));
        TenantSchemaProvisioner failing = new TenantSchemaProvisioner(dataSource, new FoundationScripts(scripts));

        assertThatThrownBy(() -> failing.provision(UUID.randomUUID().toString(), "kv_fmfai1"))
                .isInstanceOf(DomainException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class, "kv_fmfai1")).isZero();
    }

    // ── idempotency ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("idempotency: every foundation script run twice more on a registered tenant — no error, no diff")
    void scriptsAreIdempotent() throws Exception {
        String schema = register("kv_fmidm1", "KV-FMIDM1");
        Map<String, List<String>> before = shape(schema);

        TenantFoundation.upgrade(dataSource, schema);
        TenantFoundation.upgrade(dataSource, schema);

        assertThat(shape(schema)).isEqualTo(before);
        // Flyway again: nothing pending
        for (String module : FoundationScripts.fromClasspath().moduleIds()) {
            assertThat(modules.provision(schema, module).migrationsApplied()).as(module).isZero();
        }
    }

    // ── existing tenants (boot upgrade) ──────────────────────────────────────

    @Test
    @DisplayName("tenant built before 16.12, with data: the boot upgrade provisions it, every row unchanged; a second boot provisions nothing")
    void oldTenantWithDataUpgradedAtBoot() throws Exception {
        String schema = "kv_fmold1";
        registerTenantRow(schema, "KV-FMOLD1");
        tenants.provision(UUID.randomUUID().toString(), schema); // no Flyway history: like every tenant before 16.12
        insertOneRowPerTable(schema);
        Map<String, String> before = checksums(schema);
        assertThat(before).hasSize(30).allSatisfy((table, sum) -> assertThat(sum).as(table).doesNotStartWith("0:"));
        Map<String, List<String>> shapeBefore = shape(schema);

        FoundationUpgrade first = modules.upgradeFoundation(2);

        assertThat(first.checked()).isEqualTo(1);
        assertThat(first.provisioned()).isEqualTo(1);
        assertThat(first.failed()).isZero();
        assertThat(checksums(schema)).isEqualTo(before);
        assertThat(shape(schema)).isEqualTo(shapeBefore);
        FoundationScripts.fromClasspath().latestVersions().forEach((module, version) ->
                assertThat(migrations.currentVersion(schema, module)).as(module).isEqualTo(version));

        List<String> historyBefore = histories(schema);
        FoundationUpgrade second = modules.upgradeFoundation(2);

        assertThat(second.provisioned()).isZero();
        assertThat(second.failed()).isZero();
        assertThat(histories(schema)).isEqualTo(historyBefore);
    }

    @Test
    @DisplayName("drifted tenant (LIKE copies: no FK, index, default, primary key): indexes, defaults and primary keys added, no FK, rows unchanged")
    void driftedTenant() throws Exception {
        String template = register("kv_fmtpl1", "KV-FMTPL1");
        jdbc.update("UPDATE public.tenants SET status = 'DELETED' WHERE schema_name = ?", template);
        String schema = "kv_fmdrf1";
        registerTenantRow(schema, "KV-FMDRF1");
        jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
        Map<String, List<String>> reference = TenantSchemaShape.reference();
        for (String table : TenantSchemaShape.tables(reference)) {
            // columns and NOT NULL only, like the old public → tenant copy of a Hibernate-era public table
            jdbc.execute("CREATE TABLE \"" + schema + "\"." + table + " (LIKE \"" + template + "\"." + table + ")");
        }
        jdbc.update("INSERT INTO \"" + schema + "\".clients (id, name, phone, archived, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), 'Awa', '+237690000001', false, now(), now())");
        jdbc.update("INSERT INTO \"" + schema + "\".sales (id, store_id, employee_id, total_amount, payment_mode, "
                + "created_at, status, discount_amount, updated_at, version) VALUES (gen_random_uuid(), "
                + "gen_random_uuid(), gen_random_uuid(), 500, 'CASH', now(), 'COMPLETED', 0, now(), 0)");
        Map<String, String> before = checksums(schema);

        FoundationUpgrade upgrade = modules.upgradeFoundation(2);

        assertThat(upgrade.provisioned()).isEqualTo(1);
        assertThat(upgrade.failed()).isZero();
        assertThat(checksums(schema)).isEqualTo(before);
        // restricted to the reference tables: tables of later module versions (19.9 notifications) are created whole
        Set<String> referenceTables = Set.copyOf(TenantSchemaShape.tables(reference));
        Map<String, List<String>> shape = new LinkedHashMap<>();
        shape(schema).forEach((kind, lines) -> shape.put(kind, lines.stream()
                .filter(line -> kind.equals("functions") || referenceTables.contains(owner(line))).toList()));
        assertThat(shape.get("columns")).as("types, defaults, nullability")
                .containsExactlyElementsOf(reference.get("columns"));
        Predicate<String> scripted = line -> !line.contains("_sync_keyset")
                && CONSTRAINT_INDEXES.stream().noneMatch(name -> line.contains(" INDEX " + name + " "));
        assertThat(shape.get("indexes")).containsAll(reference.get("indexes").stream().filter(scripted).toList());
        assertThat(shape.get("constraints")).as("no foreign key added to an existing table")
                .noneMatch(line -> line.contains(" [f] "));
        assertThat(shape.get("constraints")).filteredOn(line -> line.contains(" [p] "))
                .containsExactlyElementsOf(reference.get("constraints").stream()
                        .filter(line -> line.contains(" [p] ")).toList());
        assertThat(shape.get("triggers")).containsExactlyElementsOf(reference.get("triggers"));
    }

    // ── request path (real history reads) ────────────────────────────────────

    @Test
    @DisplayName("ensureFoundation: a tenant without history gets every foundation module at its latest version; a second check adds no history row")
    void ensureFoundationProvisionsOldTenant() {
        String schema = "kv_fmens1";
        registerTenantRow(schema, "KV-FMENS1");
        tenants.provision(UUID.randomUUID().toString(), schema);

        modules.ensureFoundation(schema);

        FoundationScripts.fromClasspath().latestVersions().forEach((module, version) ->
                assertThat(migrations.currentVersion(schema, module)).as(module).isEqualTo(version));
        List<String> history = histories(schema);
        services(); // a new JVM-like cache: the check runs again on the database
        modules.ensureFoundation(schema);
        assertThat(histories(schema)).isEqualTo(history);
    }

    @Test
    @DisplayName("19.7a core V2: a new tenant has no CHECK on subscriptions.plan_type (VARCHAR(30)); an existing tenant "
            + "built on core V1 (CHECK under any name, VARCHAR(20)) loses it at its foundation upgrade, default and row kept")
    void coreV2DropsThePlanCodeCheck() {
        String fresh = register("kv_fmpl1n", "KV-FMPL1N");
        assertThat(planTypeChecks(fresh)).isEmpty();
        assertThat(planTypeLength(fresh)).isEqualTo(30);
        jdbc.update("UPDATE \"" + fresh + "\".subscriptions SET plan_type = 'TEST_PAID_PLAN'"); // any code now

        // an existing tenant: built by the core V1 script only (the CHECK of V1), plus a legacy-named copy of it
        String schema = "kv_fmpl1o";
        registerTenantRow(schema, "KV-FMPL1O");
        List<FoundationScripts.Script> v1Only = FoundationScripts.fromClasspath().scripts().stream()
                .filter(sc -> !("core".equals(sc.moduleId()) && !"1".equals(sc.version()))).toList();
        // seeded as such a tenant was (19.7b: today's seed trial BUSINESS_TRIAL postdates the core V1 CHECK)
        new TenantSchemaProvisioner(dataSource, new FoundationScripts(v1Only))
                .provision(UUID.randomUUID().toString(), schema, com.keevo.identity.api.PlanType.PREMIUM_TRIAL, 6);
        jdbc.execute("ALTER TABLE \"" + schema + "\".subscriptions ADD CONSTRAINT legacy_plan_check "
                + "CHECK (plan_type IN ('FREE', 'PREMIUM_TRIAL', 'PREMIUM'))");
        assertThat(planTypeChecks(schema)).hasSize(2);
        assertThat(planTypeLength(schema)).isEqualTo(20);
        String before = jdbc.queryForObject("SELECT plan_type || '/' || expires_at FROM \"" + schema
                + "\".subscriptions", String.class);

        modules.ensureFoundation(schema); // boot upgrade / first request of an existing tenant

        assertThat(migrations.currentVersion(schema, "core")).isEqualTo("3"); // 19.12: core V3 (sale flow) on top
        assertThat(planTypeChecks(schema)).isEmpty();
        assertThat(planTypeLength(schema)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT plan_type || '/' || expires_at FROM \"" + schema
                + "\".subscriptions", String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'subscriptions' AND column_name = 'plan_type'", String.class, schema))
                .contains("PREMIUM_TRIAL");
        assertThat(jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'c'",
                String.class, "\"" + schema + "\".subscriptions")).containsExactly("subscriptions_status_check");
    }

    @Test
    @DisplayName("19.9 messaging V2: a new tenant has notifications and its two indexes; a tenant on messaging V1 gets "
            + "it at its foundation upgrade without any other table touched; a second upgrade is a no-op")
    void messagingV2CreatesNotifications() throws Exception {
        String fresh = register("kv_fmnot1", "KV-FMNOT1");
        assertThat(notificationColumns(fresh)).containsExactly("id", "user_id", "type", "title", "body", "data",
                "read_at", "created_at");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = "
                + "'notifications'", String.class, fresh)).containsExactlyInAnyOrder("notifications_pkey",
                "idx_notifications_user_created", "idx_notifications_user_unread",
                "idx_notifications_read_at"); // 19.11: messaging V3

        // an existing tenant built before 19.9: messaging at V1 only
        String schema = "kv_fmnot2";
        registerTenantRow(schema, "KV-FMNOT2");
        List<FoundationScripts.Script> v1Only = FoundationScripts.fromClasspath().scripts().stream()
                .filter(sc -> !("messaging".equals(sc.moduleId()) && !"1".equals(sc.version()))).toList();
        new TenantSchemaProvisioner(dataSource, new FoundationScripts(v1Only))
                .provision(UUID.randomUUID().toString(), schema);
        assertThat(notificationColumns(schema)).isEmpty();
        insertOneRowPerTable(schema);
        Map<String, String> before = checksums(schema);
        Map<String, List<String>> shapeBefore = shape(schema);

        modules.ensureFoundation(schema);

        assertThat(migrations.currentVersion(schema, "messaging"))
                .isEqualTo(FoundationScripts.fromClasspath().latestVersions().get("messaging")); // 19.11: V3
        assertThat(notificationColumns(schema)).hasSize(8);
        Map<String, String> after = new LinkedHashMap<>(checksums(schema));
        assertThat(after.remove("notifications")).as("created empty").startsWith("0:");
        assertThat(after).isEqualTo(before);
        Map<String, List<String>> shapeAfter = shape(schema);
        shapeBefore.forEach((kind, lines) -> assertThat(shapeAfter.get(kind).stream()
                .filter(line -> !owner(line).equals("notifications")).toList()).as(kind)
                .containsExactlyElementsOf(lines));

        List<String> history = histories(schema);
        TenantFoundation.upgrade(dataSource, schema); // the scripts again: idempotent
        services();
        modules.ensureFoundation(schema);
        assertThat(histories(schema)).isEqualTo(history);
        assertThat(shape(schema)).isEqualTo(shapeAfter);
    }

    @Test
    @DisplayName("19.12 store V2 + core V3: a tenant built before 19.12 (store V1, core V2), with rows, gets the Bref "
            + "store columns (country CM) and default_sale_flow POS with its named CHECK; rows kept; scripts re-run "
            + "twice are no-ops")
    void storeV2AndCoreV3() throws Exception {
        String fresh = register("kv_fmsf1n", "KV-FMSF1N");
        assertThat(storeBrefColumns(fresh)).containsExactly("category", "country", "description", "logo_path", "niu",
                "rccm");
        assertThat(saleFlowCheck(fresh)).containsExactly("tenant_preferences_default_sale_flow_check");

        String schema = "kv_fmsf1o";
        registerTenantRow(schema, "KV-FMSF1O");
        List<FoundationScripts.Script> before1912 = FoundationScripts.fromClasspath().scripts().stream()
                .filter(sc -> !("store".equals(sc.moduleId()) && !"1".equals(sc.version())))
                .filter(sc -> !("core".equals(sc.moduleId()) && "3".equals(sc.version()))).toList();
        new TenantSchemaProvisioner(dataSource, new FoundationScripts(before1912))
                .provision(UUID.randomUUID().toString(), schema);
        assertThat(storeBrefColumns(schema)).isEmpty();
        insertOneRowPerTable(schema);
        Map<String, String> checksumsBefore = checksums(schema);

        modules.ensureFoundation(schema);

        assertThat(migrations.currentVersion(schema, "store")).isEqualTo("2");
        assertThat(migrations.currentVersion(schema, "core")).isEqualTo("3");
        assertThat(storeBrefColumns(schema)).hasSize(6);
        assertThat(jdbc.queryForList("SELECT country FROM \"" + schema + "\".stores", String.class))
                .isNotEmpty().containsOnly("CM");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM \"" + schema + "\".stores WHERE category IS NOT NULL "
                + "OR description IS NOT NULL OR logo_path IS NOT NULL OR niu IS NOT NULL OR rccm IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT default_sale_flow FROM \"" + schema + "\".tenant_preferences",
                String.class)).isNotEmpty().containsOnly("POS");
        assertThat(saleFlowCheck(schema)).containsExactly("tenant_preferences_default_sale_flow_check");
        assertThat(checksums(schema).keySet()).isEqualTo(checksumsBefore.keySet());

        Map<String, List<String>> shapeAfter = shape(schema);
        List<String> history = histories(schema);
        TenantFoundation.upgrade(dataSource, schema);
        TenantFoundation.upgrade(dataSource, schema);
        services();
        modules.ensureFoundation(schema);
        assertThat(histories(schema)).isEqualTo(history);
        assertThat(shape(schema)).isEqualTo(shapeAfter);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE \"" + schema
                + "\".tenant_preferences SET default_sale_flow = 'OTHER'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private List<String> storeBrefColumns(String schema) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'stores' AND column_name IN ('category', 'description', 'logo_path', 'niu', "
                + "'rccm', 'country') ORDER BY column_name", String.class, schema);
    }

    private List<String> saleFlowCheck(String schema) {
        return jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'c' "
                + "AND conname = 'tenant_preferences_default_sale_flow_check' AND convalidated", String.class,
                "\"" + schema + "\".tenant_preferences");
    }

    private List<String> notificationColumns(String schema) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? "
                + "AND table_name = 'notifications' ORDER BY ordinal_position", String.class, schema);
    }

    private List<String> planTypeChecks(String schema) {
        return jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'c' "
                + "AND pg_get_constraintdef(oid) LIKE '%plan_type%'", String.class, "\"" + schema + "\".subscriptions");
    }

    private Integer planTypeLength(String schema) {
        return jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'subscriptions' AND column_name = 'plan_type'",
                Integer.class, schema);
    }

    @Test
    @DisplayName("ensureFoundation on an up-to-date tenant: no Flyway run (history unchanged)")
    void ensureFoundationUpToDate() {
        String schema = register("kv_fmupd1", "KV-FMUPD1");
        List<String> history = histories(schema);
        services();

        modules.ensureFoundation(schema);

        assertThat(histories(schema)).isEqualTo(history);
    }

    @Test
    @DisplayName("schemaVersions: a schema holding only a 13.9 crm baseline at 1 → crm=1 only")
    void schemaVersionsOfABaselinedSchema() {
        String schema = "kv_fmbsl1";
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        jdbc.execute("CREATE SCHEMA \"" + schema + "\"");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).table("flyway_history_crm")
                .locations("classpath:db/modules/crm").baselineVersion("1").createSchemas(false).load().baseline();

        assertThat(migrations.schemaVersions(schema, FoundationScripts.fromClasspath().moduleIds()))
                .containsExactly(Map.entry("crm", "1"));
        assertThat(migrations.historyVersions("crm", List.of(schema, "kv_fmnone"))).containsExactly(Map.entry(schema, "1"));
    }

    // ── legacy CHECKs and keys of old tenants ────────────────────────────────

    @Test
    @DisplayName("old tenant: IN_TRANSIT accepted after the upgrade; legacy sales.status / audit_log.source rows and duplicate keys kept — constraints NOT VALID or left out, upgrade committed")
    void legacyConstraintsNeverAbortTheUpgrade() throws Exception {
        String schema = "kv_fmleg1";
        registerTenantRow(schema, "KV-FMLEG1");
        tenants.provision(UUID.randomUUID().toString(), schema);
        String q = "\"" + schema + "\".";
        // stock_transfers: the pre-3.3 status list
        jdbc.execute("ALTER TABLE " + q + "stock_transfers DROP CONSTRAINT stock_transfers_status_check");
        jdbc.execute("ALTER TABLE " + q + "stock_transfers ADD CONSTRAINT stock_transfers_status_check "
                + "CHECK (status IN ('COMPLETED','PENDING_SYNC','CONFLICT'))");
        // sales: a legacy status under the pre-4.3 CHECK
        jdbc.execute("ALTER TABLE " + q + "sales DROP CONSTRAINT sales_status_check");
        jdbc.update("INSERT INTO " + q + "sales (store_id, employee_id, status) VALUES (gen_random_uuid(), "
                + "gen_random_uuid(), 'LEGACY')");
        jdbc.execute("ALTER TABLE " + q + "sales ADD CONSTRAINT sales_status_check "
                + "CHECK (status IN ('COMPLETED','CANCELLED')) NOT VALID");
        // audit_log: source added by the old column sync without its CHECK, a legacy value already written
        jdbc.execute("ALTER TABLE " + q + "audit_log DROP CONSTRAINT audit_log_source_check");
        jdbc.update("INSERT INTO " + q + "audit_log (user_id, entity_type, entity_id, action, source) VALUES "
                + "(gen_random_uuid(), 'Product', gen_random_uuid(), 'X', 'LEGACY')");
        // notification_cooldowns: an old copy without primary key, duplicate ids
        jdbc.execute("ALTER TABLE " + q + "notification_cooldowns DROP CONSTRAINT notification_cooldowns_pkey");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO " + q + "notification_cooldowns (id, cooldown_type, last_sent_at) VALUES "
                + "(?, 'A', now()), (?, 'B', now())", id, id);
        Map<String, String> before = checksums(schema);

        FoundationUpgrade upgrade = modules.upgradeFoundation(2);

        assertThat(upgrade.failed()).isZero();
        assertThat(upgrade.provisioned()).isEqualTo(1);
        assertThat(checksums(schema)).isEqualTo(before);
        assertThat(constraint(schema, "stock_transfers", "stock_transfers_status_check")).contains("IN_TRANSIT")
                .doesNotContain("NOT VALID");
        assertThat(constraint(schema, "sales", "sales_status_check")).contains("PENDING_VALIDATION")
                .endsWith("NOT VALID");
        assertThat(constraint(schema, "audit_log", "audit_log_source_check")).contains("SYSTEM").endsWith("NOT VALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'p'",
                Integer.class, q + "notification_cooldowns")).as("primary key left out").isZero();
        FoundationScripts.fromClasspath().latestVersions().forEach((module, version) ->
                assertThat(migrations.currentVersion(schema, module)).as(module).isEqualTo(version));
    }

    private static String constraint(String schema, String table, String name) {
        return jdbc.queryForObject("SELECT pg_get_constraintdef(oid) || CASE WHEN convalidated THEN '' "
                + "ELSE ' NOT VALID' END FROM pg_constraint WHERE conrelid = ?::regclass AND conname = ?",
                String.class, "\"" + schema + "\"." + table, name);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Registration as TenantFactory does it: tenant row, schema + scripts + seeds, then Flyway after commit. */
    private String register(String schema, String code) {
        registerTenantRow(schema, code);
        tenants.provision(UUID.randomUUID().toString(), schema);
        modules.provisionActiveModules(schema);
        return schema;
    }

    private void registerTenantRow(String schema, String code) {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE"); // test database only
        jdbc.update("DELETE FROM public.tenants WHERE schema_name = ?", schema);
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now(), now(), ?, 3, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')", UUID.randomUUID(), code, schema);
    }

    private static Map<String, List<String>> shape(String schema) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            return TenantSchemaShape.capture(conn, schema);
        }
    }

    /** The shape restricted to the 30 tenant tables equals the committed reference. */
    private static void assertShapeIsReference(String schema) throws Exception {
        Map<String, List<String>> reference = TenantSchemaShape.reference();
        Set<String> tables = Set.copyOf(TenantSchemaShape.tables(reference));
        Map<String, List<String>> shape = shape(schema);
        assertThat(TenantSchemaShape.tables(shape)).containsAll(tables);
        reference.forEach((kind, lines) -> assertThat(shape.get(kind).stream()
                .filter(line -> kind.equals("functions") || tables.contains(owner(line))).toList())
                .as(kind).containsExactlyElementsOf(lines));
    }

    /** The table a shape line belongs to: its text up to the first {@code .} or {@code :}. */
    private static String owner(String line) {
        int dot = line.indexOf('.');
        int colon = line.indexOf(':');
        int cut = dot < 0 ? colon : colon < 0 ? dot : Math.min(dot, colon);
        return cut < 0 ? line : line.substring(0, cut);
    }

    /**
     * Table → {@code count:md5} of its rows (text form, sorted), for the reference tables present in the schema (a
     * tenant on an older module version lacks the later tables, e.g. 19.9 notifications).
     */
    private static Map<String, String> checksums(String schema) {
        Map<String, String> sums = new LinkedHashMap<>();
        Set<String> present = Set.copyOf(jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ?", String.class, schema));
        for (String table : TenantSchemaShape.tables(TenantSchemaShape.reference())) {
            if (!present.contains(table)) continue;
            sums.put(table, jdbc.queryForObject("SELECT count(*) || ':' || md5(COALESCE(string_agg(t::text, '|' "
                    + "ORDER BY t::text), '')) FROM \"" + schema + "\"." + table + " t", String.class));
        }
        return sums;
    }

    private static List<String> histories(String schema) {
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                + "AND table_name LIKE 'flyway\\_history\\_%' ORDER BY 1", String.class, schema).stream()
                .flatMap(table -> jdbc.queryForList("SELECT ? || ':' || COALESCE(version, '-') || ':' || type "
                        + "|| ':' || checksum FROM \"" + schema + "\"." + table + " ORDER BY installed_rank",
                        String.class, table).stream())
                .collect(Collectors.toList());
    }

    /** One row in every tenant table (the seeds already fill roles, subscriptions and stores). */
    private static void insertOneRowPerTable(String schema) {
        String s = "\"" + schema + "\".";
        jdbc.execute("DELETE FROM " + s + "stores WHERE name <> 'Ma Boutique'");
        String[] statements = {
                "INSERT INTO " + s + "users (phone_number, password_hash, role) VALUES ('+237690000001', 'h', 'OWNER')",
                "INSERT INTO " + s + "user_roles (user_id, role_id) SELECT u.id, r.id FROM " + s + "users u, " + s
                        + "roles r WHERE r.name = 'OWNER'",
                "INSERT INTO " + s + "tenant_preferences (sector_type) VALUES ('BOUTIQUE')",
                "INSERT INTO " + s + "audit_log (user_id, entity_type, entity_id, action) VALUES (gen_random_uuid(), "
                        + "'Product', gen_random_uuid(), 'PRODUCT_CREATED')",
                "INSERT INTO " + s + "employees (user_id, store_id, first_name, last_name) SELECT gen_random_uuid(), "
                        + "id, 'Awa', 'Ngo' FROM " + s + "stores",
                "INSERT INTO " + s + "categories (name) VALUES ('Boissons')",
                "INSERT INTO " + s + "products (name, sku, category_id) SELECT 'Savon', 'SAV-1', id FROM " + s
                        + "categories",
                "INSERT INTO " + s + "stock_levels (product_id, store_id, quantity) SELECT p.id, st.id, 5 FROM " + s
                        + "products p, " + s + "stores st",
                "INSERT INTO " + s + "stock_movements (product_id, store_id, movement_type, quantity_before, "
                        + "quantity_change, quantity_after, actor_id) SELECT p.id, st.id, 'STOCK_ENTRY', 0, 5, 5, "
                        + "gen_random_uuid() FROM " + s + "products p, " + s + "stores st",
                "INSERT INTO " + s + "stock_transfers (source_store_id, destination_store_id, product_id, quantity, "
                        + "actor_id, updated_at) SELECT st.id, gen_random_uuid(), p.id, 1, gen_random_uuid(), now() FROM "
                        + s + "products p, " + s + "stores st",
                "INSERT INTO " + s + "inventory_sessions (store_id, scope, started_by) SELECT id, 'FULL', "
                        + "gen_random_uuid() FROM " + s + "stores",
                "INSERT INTO " + s + "inventory_counts (session_id, product_id, product_name) SELECT i.id, p.id, "
                        + "p.name FROM " + s + "inventory_sessions i, " + s + "products p",
                "INSERT INTO " + s + "clients (name, phone) VALUES ('Client', '+237690000002')",
                "INSERT INTO " + s + "suppliers (name, phone) VALUES ('Fournisseur', '+237690000003')",
                "INSERT INTO " + s + "product_suppliers (product_id, supplier_id) SELECT p.id, f.id FROM " + s
                        + "products p, " + s + "suppliers f",
                "INSERT INTO " + s + "sales (store_id, employee_id, client_id, total_amount, occurred_at) SELECT "
                        + "st.id, gen_random_uuid(), c.id, 1000, now() FROM " + s + "stores st, " + s + "clients c",
                "INSERT INTO " + s + "sale_items (sale_id, product_id, product_name, applied_unit_price, quantity, "
                        + "subtotal) SELECT sa.id, p.id, p.name, 500, 2, 1000 FROM " + s + "sales sa, " + s + "products p",
                "INSERT INTO " + s + "day_closures (store_id, closure_date, received_at) SELECT id, current_date, "
                        + "now() FROM " + s + "stores",
                "INSERT INTO " + s + "sync_operations_log (id, operation_type, status, device_id) VALUES ('op-1', "
                        + "'CREATE_SALE', 'APPLIED', 'device-1')",
                "INSERT INTO " + s + "sync_conflicts_log (id, operation_id, operation_type, conflict_type, strategy) "
                        + "VALUES ('cf-1', 'op-1', 'CREATE_SALE', 'STALE', 'LWW')",
                "INSERT INTO " + s + "sync_error_log (operation_id, operation_type, payload) VALUES ('op-2', "
                        + "'CREATE_SALE', '{}'::jsonb)",
                "INSERT INTO " + s + "reports (id, tenant_id, store_id, report_type, report_date, content, updated_at) "
                        + "SELECT gen_random_uuid(), 'KV-X', id, 'DAILY', current_date, 'x', now() FROM " + s + "stores",
                "INSERT INTO " + s + "export_jobs (id, requested_by, status, format, datasets, file_name, content_type) "
                        + "VALUES (gen_random_uuid(), gen_random_uuid(), 'READY', 'CSV', 'stores', 'f.csv', 'text/csv')",
                "INSERT INTO " + s + "draft_notifications (product_id, product_name, actor_id, tenant_id) VALUES "
                        + "(gen_random_uuid(), 'Savon', gen_random_uuid(), gen_random_uuid())",
                "INSERT INTO " + s + "device_tokens (id, user_id, token, platform, role) VALUES (gen_random_uuid(), "
                        + "gen_random_uuid(), 'tok-1', 'ANDROID', 'OWNER')",
                "INSERT INTO " + s + "notification_cooldowns (cooldown_type, product_id, store_id, last_sent_at) "
                        + "VALUES ('STOCK_ALERT', gen_random_uuid(), gen_random_uuid(), now())"};
        for (String sql : statements) {
            jdbc.update(sql);
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                s + "notifications"))) { // messaging V2 (19.9)
            jdbc.update("INSERT INTO " + s + "notifications (user_id, type, title, body, data) VALUES "
                    + "(gen_random_uuid(), 'STOCK_ALERT', 'Stock bas', 'Savon', '{\"deepLink\":\"/stock\"}'::jsonb)");
        }
    }
}
