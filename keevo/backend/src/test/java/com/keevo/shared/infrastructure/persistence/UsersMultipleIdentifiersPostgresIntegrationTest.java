package com.keevo.shared.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 19.1 — V41 on PostgreSQL: rows written before it are untouched (count + md5 of the pre-V41 columns), the
 * new columns are NULL, phone and password become optional, at least one identifier is required, email and
 * username are unique case-insensitively, and re-running the migration's DDL is a no-op (idempotent).
 */
@DisplayName("V41 — public.users multiple identifiers (19.1)")
class UsersMultipleIdentifiersPostgresIntegrationTest {

    private static final String TEST_DB = "keevo_users_identifiers";
    private static final String PRE_V41_MD5 = "SELECT count(*) || ':' || md5(string_agg(row(id, created_at, updated_at, "
            + "is_active, failed_attempts, locked_until, password_hash, phone_number, role)::text, ',' ORDER BY id)) FROM public.users";

    private static String url;
    private static JdbcTemplate jdbc;
    private static String before;
    private static String after;

    @BeforeAll
    static void migrateAroundV41() {
        url = SharedPostgresContainer.freshDatabase(TEST_DB);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, SharedPostgresContainer.username(),
                SharedPostgresContainer.password()));
        flyway().target("40").load().migrate();
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, "
                    + "locked_until, password_hash, phone_number, role) VALUES (?, now(), now(), true, ?, "
                    + (i == 1 ? "now()" : "NULL") + ", '$2a$12$hash" + i + "', ?, 'OWNER')",
                    UUID.randomUUID(), i, "+23769000000" + i);
        }
        before = jdbc.queryForObject(PRE_V41_MD5, String.class);
        flyway().load().migrate();
        after = jdbc.queryForObject(PRE_V41_MD5, String.class);
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

    @Test
    @DisplayName("existing rows unchanged (count + md5), new columns NULL")
    void existingRowsUnchanged() {
        assertThat(before).startsWith("3:");
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.users WHERE phone_number LIKE '+23769000000%' "
                + "AND (email IS NOT NULL OR username IS NOT NULL OR first_name IS NOT NULL OR last_name IS NOT NULL OR email_verified_at IS NOT NULL "
                + "OR phone_verified_at IS NOT NULL)", Integer.class)).isZero();
    }

    @Test
    @DisplayName("constraints and indexes present; CHECK validated; uk_users_phone_number kept")
    void schemaObjects() {
        assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname = 'users_identifier_present' "
                + "AND conrelid = 'public.users'::regclass", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'uk_users_phone_number'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname = 'users_username_format' "
                + "AND conrelid = 'public.users'::regclass", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname = 'ix_utm_tenant_role'", String.class)).contains("(tenant_id, role)");
        List<String> indexes = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname IN ('uq_users_email_lower', 'uq_users_username_lower') ORDER BY indexname", String.class);
        assertThat(indexes).hasSize(2);
        assertThat(indexes.get(0)).contains("UNIQUE", "lower((email)::text)");
        assertThat(indexes.get(1)).contains("UNIQUE", "lower((username)::text)");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'users' AND is_nullable = 'NO'", String.class))
                .doesNotContain("phone_number", "password_hash", "email", "username");
    }

    @Test
    @DisplayName("insert without any identifier is refused")
    void noIdentifier_refused() {
        assertThatThrownBy(() -> insert(null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("users_identifier_present");
    }

    @Test
    @DisplayName("email-only and username-only accounts (no password) are accepted")
    void emailOnly_usernameOnly_accepted() {
        insert(null, "only." + UUID.randomUUID() + "@x.cm", null);
        insert(null, null, "only_" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    @DisplayName("email unique case-insensitively")
    void email_caseInsensitiveUnique() {
        String local = "dup" + UUID.randomUUID().toString().substring(0, 8);
        insert(null, local.toUpperCase() + "@X.cm", null);
        assertThatThrownBy(() -> insert(null, local + "@x.cm", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_email_lower");
    }

    @Test
    @DisplayName("username unique case-insensitively")
    void username_caseInsensitiveUnique() {
        String name = "Awa" + UUID.randomUUID().toString().substring(0, 8);
        insert(null, null, name);
        assertThatThrownBy(() -> insert(null, null, name.toLowerCase()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_username_lower");
    }

    @Test
    @DisplayName("email lookup uses the lower() index (expression matches)")
    void emailLookup_usesIndex() {
        // one connection: DriverManagerDataSource opens a new one per statement, the SET must apply to the EXPLAIN
        String plan = jdbc.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("SET enable_seqscan = off");
                StringBuilder sb = new StringBuilder();
                try (var rs = st.executeQuery(
                        "EXPLAIN SELECT id FROM public.users WHERE lower(email) = lower('A@x.cm')")) {
                    while (rs.next()) sb.append(rs.getString(1)).append('\n');
                }
                return sb.toString();
            }
        });
        assertThat(plan).contains("uq_users_email_lower");
    }

    @Test
    @DisplayName("V41 run twice inside one transaction: no error, objects unchanged")
    void idempotent_twiceInOneTransaction() throws Exception {
        String sql = new String(getClass().getResourceAsStream("/db/migration/V41__users_multiple_identifiers.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute((java.sql.Connection c) -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute(sql);
                st.execute(sql);
                try (var rs = st.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND "
                        + "indexname IN ('uq_users_email_lower', 'uq_users_username_lower', 'ix_utm_tenant_role') "
                        + "ORDER BY indexname")) {
                    List<String> names = new java.util.ArrayList<>();
                    while (rs.next()) names.add(rs.getString(1));
                    assertThat(names).containsExactly("ix_utm_tenant_role", "uq_users_email_lower",
                            "uq_users_username_lower");
                }
                try (var rs = st.executeQuery("SELECT count(*) FROM pg_constraint WHERE conrelid = "
                        + "'public.users'::regclass AND conname IN ('users_identifier_present', 'users_username_format')")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
                try (var rs = st.executeQuery("SELECT column_name, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'users' AND column_name IN ('phone_number', "
                        + "'password_hash', 'email', 'username', 'first_name', 'last_name', 'email_verified_at', "
                        + "'phone_verified_at')")) {
                    int n = 0;
                    while (rs.next()) {
                        n++;
                        assertThat(rs.getString(2)).as(rs.getString(1)).isEqualTo("YES");
                    }
                    assertThat(n).isEqualTo(8);
                }
                c.commit();
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    @Test
    @DisplayName("blank-only identifiers are refused (users_identifier_present)")
    void blankOnlyIdentifiers_refused() {
        assertThatThrownBy(() -> insert("", null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("users_identifier_present");
        assertThatThrownBy(() -> insert("   ", " ", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("users_identifier_present");
        assertThatThrownBy(() -> insert(null, "", "  "))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("usernames outside Bref's rule are refused (users_username_format)")
    void usernameFormat_enforced() {
        for (String bad : new String[] {"a@b.cm", "+237690000001", "awa n", "", "é"}) {
            assertThatThrownBy(() -> insert(null, "fmt." + UUID.randomUUID() + "@x.cm", bad))
                    .as(bad)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("users_username_format");
        }
        // over 50 characters: refused by the column length before the CHECK
        assertThatThrownBy(() -> insert(null, null, "x".repeat(51))).isInstanceOf(DataIntegrityViolationException.class);
        insert(null, null, "Awa.N_" + UUID.randomUUID().toString().substring(0, 8) + "-x");
    }

    private static void insert(String phone, String email, String username) {
        jdbc.update("INSERT INTO public.users (id, created_at, updated_at, is_active, failed_attempts, role, "
                + "phone_number, email, username) VALUES (?, now(), now(), true, 0, 'OWNER', ?, ?, ?)",
                UUID.randomUUID(), phone, email, username);
    }
}
