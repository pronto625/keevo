package com.keevo.admin.platform.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JdbcPlatformProbeAdapter (Story 9-5)")
class JdbcPlatformProbeAdapterTest {

    JdbcTemplate jdbc;
    DataSource dataSource;
    JdbcPlatformProbeAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        dataSource = mock(DataSource.class);
        adapter = new JdbcPlatformProbeAdapter(jdbc, dataSource);
        when(jdbc.queryForList(JdbcPlatformProbeAdapter.LIVE_SCHEMAS_SQL, String.class))
                .thenReturn(List.of("kv_aaa111", "kv_bbb222", "public", "bad schema"));
    }

    @Test
    @DisplayName("databaseOk() is false when the check throws (here: no connection)")
    void databaseDown() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("refused"));
        assertThat(adapter.databaseOk()).isFalse();
    }

    @Test
    @DisplayName("a company's own SQL error is skipped; invalid schema names never queried")
    void skipsBrokenCompany() {
        when(jdbc.queryForObject(contains("\"kv_aaa111\""), eq(Long.class), any(Object[].class)))
                .thenThrow(new BadSqlGrammarException("count", "sql", new SQLException("no table")));
        when(jdbc.queryForObject(contains("\"kv_bbb222\""), eq(Long.class), any(Object[].class))).thenReturn(4L);

        assertThat(adapter.syncRefusalsSince(Instant.now())).isEqualTo(4);
    }

    @Test
    @DisplayName("a connection failure propagates (never a falsely low count)")
    void connectionFailurePropagates() {
        when(jdbc.queryForObject(contains("\"kv_aaa111\""), eq(Long.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> adapter.syncRefusalsSince(Instant.now()))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
