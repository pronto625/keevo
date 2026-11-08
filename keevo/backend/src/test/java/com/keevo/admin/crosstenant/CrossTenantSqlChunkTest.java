package com.keevo.admin.crosstenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("20.13 — chunked cross-tenant union: chunk size, failure isolation per tenant, resource failures rethrown")
class CrossTenantSqlChunkTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final List<String> statements = new ArrayList<>();

    @Test
    @DisplayName("5 tenants, chunks of 2: 3 statements, each holding at most 2 branches")
    void chunks() {
        answerWith(null);

        List<String> rows = new CrossTenantSql(jdbc, 2).query(tenants(5), (t, p) -> "SELECT '" + t.schemaName() + "'",
                u -> u, (rs, n) -> null);

        assertThat(statements).hasSize(3);
        assertThat(statements).allSatisfy(s -> assertThat(s.split("UNION ALL", -1).length).isLessThanOrEqualTo(2));
        assertThat(rows).hasSize(5);
    }

    @Test
    @DisplayName("a chunk failing on one schema is retried tenant by tenant: the bad tenant is skipped, the others kept")
    void failureIsolated() {
        answerWith("kv_aaaaa1");

        List<String> rows = new CrossTenantSql(jdbc, 2).query(tenants(4), (t, p) -> "SELECT '" + t.schemaName() + "'",
                u -> u, (rs, n) -> null);

        assertThat(rows).containsExactlyInAnyOrder("kv_aaaaa0", "kv_aaaaa2", "kv_aaaaa3");
    }

    @Test
    @DisplayName("a connection / resource failure is rethrown (no partial 200)")
    void resourceFailureRethrown() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> new CrossTenantSql(jdbc, 2).query(tenants(3), (t, p) -> "SELECT 1", u -> u,
                (rs, n) -> null)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    /** Each branch yields its schema name; a statement mentioning {@code broken} fails with a schema error. */
    @SuppressWarnings("unchecked")
    private void answerWith(String broken) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            statements.add(sql);
            if (broken != null && sql.contains(broken)) {
                throw new BadSqlGrammarException("q", sql, new SQLException("relation does not exist"));
            }
            List<String> out = new ArrayList<>();
            for (String part : sql.split("UNION ALL")) {
                out.add(part.replaceAll(".*'(kv_\\w+)'.*", "$1").trim());
            }
            return out;
        });
    }

    private static List<TenantBranch> tenants(int n) {
        return IntStream.range(0, n).mapToObj(i -> new TenantBranch(
                String.format("00000000-0000-0000-0000-%012d", i + 1), "T" + i, "kv_aaaaa" + i, "ACTIVE", null))
                .toList();
    }
}
