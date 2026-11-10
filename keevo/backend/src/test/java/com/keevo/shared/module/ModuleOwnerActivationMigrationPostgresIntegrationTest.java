package com.keevo.shared.module;

import com.keevo.shared.infrastructure.persistence.SharedPostgresContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 19.13 — V50 on PostgreSQL around pre-existing tenants (migrated up to V49 first): the go-live backfill turns
 * invoicing off for a tenant without a row, FREE is granted invoicing, the default rule sets are seeded; re-running the
 * script keeps an owner choice made since (invoicing back on) and an edited DOCUMENT default set (not reseeded).
 */
@DisplayName("V50 — owner module activation: go-live backfill and idempotent re-run (19.13)")
class ModuleOwnerActivationMigrationPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_owner_activation_v50";
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000502");

    private static String url;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAroundV50() {
        url = SharedPostgresContainer.freshDatabase(TEST_DB);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password()));
        flyway().target("49").load().migrate();
        int i = 0;
        for (UUID id : List.of(FIRST, SECOND)) {
            i++;
            jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                    + "max_stores, plan_type, schema_name, status, name) "
                    + "VALUES (?, now(), now(), ?, 1, 500, 1, 'FREE', ?, 'ACTIVE', 'Test')", id, "KV-V50-" + i,
                    "kv_v50ab" + i);
        }
        flyway().load().migrate();
    }

    private static FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(url, SharedPostgresContainer.username(), SharedPostgresContainer.password())
                .baselineOnMigrate(true).baselineVersion("0")
                .schemas("public").defaultSchema("public")
                .locations("classpath:db/migration");
    }

    @AfterAll
    static void dropTestDatabase() {
        SharedPostgresContainer.dropDatabase(TEST_DB);
    }

    private static void rerunV50() throws Exception {
        String sql;
        try (var in = ModuleOwnerActivationMigrationPostgresIntegrationTest.class
                .getResourceAsStream("/db/migration/V50__module_owner_activation.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbc.execute(sql);
    }

    private static Map<String, Boolean> rows(UUID tenant) {
        Map<String, Boolean> rows = new LinkedHashMap<>();
        jdbc.query("SELECT module_id, activated FROM public.tenant_module_activation WHERE tenant_id = ? "
                + "ORDER BY module_id", rs -> {
            rows.put(rs.getString(1), rs.getBoolean(2));
        }, tenant);
        return rows;
    }

    private static List<String> ruleModules(String flow) {
        return jdbc.queryForList("SELECT r.module_id FROM public.module_activation_rule_modules r "
                + "JOIN public.module_activation_rule_sets s ON s.id = r.rule_set_id "
                + "WHERE s.sale_flow = ? AND s.sector_type IS NULL ORDER BY r.module_id", String.class, flow);
    }

    @Test
    @DisplayName("pre-existing tenants: invoicing off, nothing else; FREE granted invoicing; default sets seeded; "
            + "re-run keeps an owner choice and an edited DOCUMENT default set")
    void backfillAndRerun() throws Exception {
        // Story 20.10 — V56 owner-disables the new expenses module the same way; 20.11 — V58 the finance module;
        // 21.4 — V65 the ai-assistant module
        assertThat(rows(FIRST)).containsExactly(Map.entry("ai-assistant", false), Map.entry("expenses", false), Map.entry("finance", false), Map.entry("invoicing", false));
        assertThat(rows(SECOND)).containsExactly(Map.entry("ai-assistant", false), Map.entry("expenses", false), Map.entry("finance", false), Map.entry("invoicing", false));
        assertThat(jdbc.queryForObject("SELECT granted FROM public.profile_modules WHERE plan_type = 'FREE' "
                + "AND module_id = 'invoicing'", Boolean.class)).isTrue();
        // Story 21.4 — V65 grants the assistant to every plan, FREE included (guided topics are free)
        assertThat(jdbc.queryForList("SELECT p.plan_type FROM public.plan_profiles p LEFT JOIN public.profile_modules m "
                + "ON m.plan_type = p.plan_type AND m.module_id = 'ai-assistant' AND m.granted WHERE m.plan_type IS NULL",
                String.class)).as("plans without ai-assistant").isEmpty();
        assertThat(ruleModules("POS")).containsExactly("invoicing");
        assertThat(ruleModules("DOCUMENT")).containsExactly("counting", "inventory", "multi-store", "pos");

        // since go-live: the first owner enabled invoicing; the super-admin edited the DOCUMENT default set
        jdbc.update("UPDATE public.tenant_module_activation SET activated = true WHERE tenant_id = ? "
                + "AND module_id = 'invoicing'", FIRST);
        jdbc.update("DELETE FROM public.module_activation_rule_modules WHERE module_id = 'multi-store' AND rule_set_id = "
                + "(SELECT id FROM public.module_activation_rule_sets WHERE sale_flow = 'DOCUMENT' "
                + "AND sector_type IS NULL)");

        rerunV50();

        assertThat(rows(FIRST)).containsExactly(Map.entry("ai-assistant", false), Map.entry("expenses", false), Map.entry("finance", false), Map.entry("invoicing", true));
        assertThat(rows(SECOND)).containsExactly(Map.entry("ai-assistant", false), Map.entry("expenses", false), Map.entry("finance", false), Map.entry("invoicing", false));
        assertThat(ruleModules("DOCUMENT")).containsExactly("counting", "inventory", "pos");
        assertThat(ruleModules("POS")).containsExactly("invoicing");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.module_activation_rule_sets", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.profile_modules WHERE plan_type = 'FREE' "
                + "AND module_id = 'invoicing' AND granted", Integer.class)).isEqualTo(1);
    }
}
