package com.keevo.sync.sync.application.provider;

import com.keevo.shared.infrastructure.persistence.PostgresIntegrationTest;
import com.keevo.shared.infrastructure.persistence.TenantSchemaProvisioner;
import com.keevo.shared.application.port.sync.DeltaEntityProvider.Position;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 11.5 — on a tenant schema with ~20k rows (ANALYZEd), the planner answers the real
 * {@link DeltaPageQuery} SQL for a second page (row-value start bound) with the
 * {@code idx_<table>_sync_keyset} index, without any planner setting tweak.
 *
 * <p>Commits a tenant schema, dropped in {@link #dropSchema()}.</p>
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import(TenantSchemaProvisioner.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Delta page SQL uses the keyset indexes (Story 11.5)")
class DeltaPageQueryKeysetIndexPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "kv_ksp001";
    private static final int ROWS = 20_000;
    private static final int LIMIT = 50;
    private static final Instant SINCE = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TenantSchemaProvisioner provisioner;

    @BeforeEach
    void populate() {
        provisioner.provision("tenant-" + SCHEMA, SCHEMA);
        String s = "\"" + SCHEMA + "\".";
        jdbc.update("INSERT INTO " + s + "sales (store_id, employee_id, created_at, occurred_at, updated_at) "
                + "SELECT gen_random_uuid(), gen_random_uuid(), t, t, t "
                + "FROM (SELECT now() - make_interval(secs => g) AS t FROM generate_series(1, " + ROWS + ") g) x");
        jdbc.update("INSERT INTO " + s + "products (name, price, sku) VALUES ('Produit', 100, 'SKU-KSP-1')");
        jdbc.update("INSERT INTO " + s + "stock_transfers (source_store_id, destination_store_id, product_id, "
                + "quantity, actor_id, occurred_at, updated_at) "
                + "SELECT gen_random_uuid(), gen_random_uuid(), (SELECT id FROM " + s + "products LIMIT 1), 1, "
                + "gen_random_uuid(), t, CASE WHEN g % 3 = 0 THEN t + interval '1 minute' END "
                + "FROM (SELECT g, now() - make_interval(secs => g) AS t FROM generate_series(1, " + ROWS + ") g) x");
        jdbc.execute("ANALYZE " + s + "sales");
        jdbc.execute("ANALYZE " + s + "stock_transfers");
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    @DisplayName("sales: second page → Index (Only) Scan on idx_sales_sync_keyset")
    void salesSecondPageUsesKeysetIndex() throws Exception {
        assertThat(explainSecondPage("sales", SaleDeltaProvider.CURSOR))
                .containsPattern("Index (Only )?Scan using idx_sales_sync_keyset");
    }

    @Test
    @DisplayName("stock_transfers (expression cursor): second page → Index (Only) Scan on idx_stock_transfers_sync_keyset")
    void stockTransfersSecondPageUsesKeysetIndex() throws Exception {
        assertThat(explainSecondPage("stock_transfers", StockTransferDeltaProvider.CURSOR))
                .containsPattern("Index (Only )?Scan using idx_stock_transfers_sync_keyset");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** EXPLAIN of the SQL DeltaPageQuery builds for the page after a row in the middle of the table. */
    private String explainSecondPage(String table, String cursor) throws Exception {
        Map<String, Object> middle = jdbc.queryForMap(
                "SELECT " + cursor + " AS c, CAST(id AS text) AS i FROM \"" + SCHEMA + "\"." + table
                + " ORDER BY " + cursor + ", CAST(id AS text) OFFSET " + (ROWS / 2) + " LIMIT 1");
        Position after = new Position(((Timestamp) middle.get("c")).toInstant(), (String) middle.get("i"));

        String sql = deltaSql(table, cursor, after);
        // Hibernate binds the named parameters positionally and adds the row limit (setMaxResults).
        String jdbcSql = sql.replace(":since", "?").replace(":afterCursor", "?").replace(":afterId", "?")
                + " LIMIT " + (LIMIT + 1);
        assertThat(sql.indexOf(":since")).isLessThan(sql.indexOf(":afterCursor"));
        assertThat(sql.indexOf(":afterCursor")).isLessThan(sql.indexOf(":afterId"));

        StringBuilder plan = new StringBuilder();
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SET search_path TO \"" + SCHEMA + "\"");
            }
            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN " + jdbcSql)) {
                bind(ps, after);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) plan.append(rs.getString(1)).append('\n');
                }
            }
            // and the page itself resumes strictly after the previous row, in (cursor, id) order
            List<String> expected = jdbc.queryForList(
                    "SELECT CAST(id AS text) FROM \"" + SCHEMA + "\"." + table + " ORDER BY " + cursor
                    + ", CAST(id AS text) OFFSET " + (ROWS / 2 + 1) + " LIMIT " + (LIMIT + 1), String.class);
            List<String> actual = new java.util.ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(jdbcSql)) {
                bind(ps, after);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) actual.add(rs.getString(1));
                }
                assertThat(actual).as("second page of %s", table).isEqualTo(expected);
            } finally {
                try (Statement st = conn.createStatement()) {
                    st.execute("SET search_path TO public");
                }
            }
        }
        return plan.toString();
    }

    private static void bind(PreparedStatement ps, Position after) throws java.sql.SQLException {
        ps.setTimestamp(1, Timestamp.from(SINCE));
        ps.setTimestamp(2, Timestamp.from(after.cursor()));
        ps.setString(3, after.id());
    }

    /** The native SQL DeltaPageQuery sends for this page, captured from a mocked EntityManager. */
    @SuppressWarnings("unchecked")
    private static String deltaSql(String table, String cursor, Position after) {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        DeltaPageQuery.fetch(em, "id", table, cursor, SINCE, after, LIMIT);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sql.capture());
        return sql.getValue();
    }
}
