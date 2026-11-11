package com.keevo.clientportal;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 21.7, reprise 11/11/2026 — V84 on PostgreSQL: the client portal behaves like Bref's for the companies that
 * existed when V70 owner-disabled it. Migrated up to V69, two companies exist; V70 disables the portal for both; the
 * first owner then switches it off himself (his choice carries his id), a third company is created afterwards with an
 * initial rule that disables it; V84 re-enables only the second company (V70's row), keeps the two choices, adds the
 * account columns, cuts live sessions to 24 h, and changes nothing when run again.
 */
@DisplayName("V84 — client portal aligned on Bref: re-enabled for the existing companies, account columns (21.7 reprise)")
class ClientPortalBrefAlignmentMigrationPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_client_portal_v84";
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000841");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000842");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000843");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000008a1");
    private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-0000000008c1");

    private static String url;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAroundV84() {
        url = SharedPostgresContainer.freshDatabase(TEST_DB);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password()));
        flyway().target("69").load().migrate();
        tenant(FIRST, 1);
        tenant(SECOND, 2);
        flyway().target(lastVersionBefore(84)).load().migrate();
        // since V70: the first owner switched the portal off himself; a company created later starts without it
        jdbc.update("UPDATE public.tenant_module_activation SET updated_by = ?, updated_at = now() "
                + "WHERE tenant_id = ? AND module_id = 'client-portal'", OWNER, FIRST);
        tenant(THIRD, 3);
        jdbc.update("INSERT INTO public.tenant_module_activation (tenant_id, module_id, activated, updated_at) "
                + "VALUES (?, 'client-portal', false, now())", THIRD);
        // a 30-day portal session opened before V84
        jdbc.update("INSERT INTO public.portal_clients (id, phone) VALUES (?, '+237677000841')", CLIENT);
        jdbc.update("INSERT INTO public.portal_sessions (token_hash, client_id, created_at, expires_at) VALUES "
                + "(decode(repeat('ab', 32), 'hex'), ?, now() - interval '2 hours', now() + interval '29 days')", CLIENT);
        flyway().load().migrate();
    }

    /** A company; the first two are created an hour before V70 runs, the third after it. */
    private static void tenant(UUID id, int i) {
        jdbc.update("INSERT INTO public.tenants (id, created_at, updated_at, code, max_employees, max_products, "
                + "max_stores, plan_type, schema_name, status, name) "
                + "VALUES (?, now() - " + (i < 3 ? "interval '1 hour'" : "interval '0 hour'") + ", now(), ?, 1, 500, "
                + "1, 'FREE', ?, 'ACTIVE', 'Test')", id, "KV-V84-" + i, "kv_v84ab" + i);
    }

    /** The highest public migration below {@code version} on the classpath (other stories ship V80–V83). */
    private static String lastVersionBefore(int version) {
        try {
            int best = 0;
            for (var r : new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:db/migration/V*__*.sql")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^V(\\d+)__")
                        .matcher(java.util.Objects.requireNonNull(r.getFilename()));
                if (m.find()) {
                    int v = Integer.parseInt(m.group(1));
                    if (v < version && v > best) {
                        best = v;
                    }
                }
            }
            return Integer.toString(best);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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

    private static List<Boolean> portal(UUID tenant) {
        return jdbc.queryForList("SELECT activated FROM public.tenant_module_activation WHERE tenant_id = ? "
                + "AND module_id = 'client-portal'", Boolean.class, tenant);
    }

    private static void rerunV84() throws Exception {
        try (var in = ClientPortalBrefAlignmentMigrationPostgresIntegrationTest.class
                .getResourceAsStream("/db/migration/V84__client_portal_bref_alignment.sql")) {
            jdbc.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("V70's row removed (portal on, like Bref); an owner's switch and a later company's rule kept; sessions 24 h; re-run idempotent")
    void reenablesOnlyV70Rows() throws Exception {
        assertThat(portal(SECOND)).as("V70's row: re-enabled").isEmpty();
        assertThat(portal(FIRST)).as("owner's own choice").containsExactly(false);
        assertThat(portal(THIRD)).as("created after V70").containsExactly(false);

        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'portal_clients' ORDER BY column_name", String.class))
                .contains("name", "email", "password_hash");
        assertThat(jdbc.queryForObject("SELECT expires_at - created_at <= interval '1 day' FROM public.portal_sessions "
                + "WHERE client_id = ?", Boolean.class, CLIENT)).isTrue();

        rerunV84();
        assertThat(portal(SECOND)).isEmpty();
        assertThat(portal(FIRST)).containsExactly(false);
        assertThat(portal(THIRD)).containsExactly(false);
    }
}
