package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.api.TokenRevocationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * JdbcTokenRevocationAdapterTest — TDD tests for the raw-JDBC adapter (Story 12.2, AC7).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JdbcTokenRevocationAdapter")
class JdbcTokenRevocationAdapterTest {

    @Mock JdbcTemplate jdbcTemplate;

    private JdbcTokenRevocationAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JdbcTokenRevocationAdapter(jdbcTemplate);
    }

    // ── getTokensValidAfter ──────────────────────────────────────────────

    @Test
    @DisplayName("queryForObject returns non-null Timestamp → Instant")
    void shouldReturnInstantWhenTimestampNonNull() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(userId), eq("kv_abc123")))
                .thenReturn(now);

        Instant result = adapter.getTokensValidAfter(userId, "kv_abc123");

        assertThat(result).isEqualTo(now);
    }

    @Test
    @DisplayName("queryForObject returns null → null (never revoked)")
    void shouldReturnNullWhenTimestampNull() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(userId), eq("kv_abc123")))
                .thenReturn(null);

        Instant result = adapter.getTokensValidAfter(userId, "kv_abc123");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("EmptyResultDataAccessException → null (no membership, allow)")
    void shouldReturnNullOnEmptyResult() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(userId), eq("kv_abc123")))
                .thenThrow(new EmptyResultDataAccessException(1));

        Instant result = adapter.getTokensValidAfter(userId, "kv_abc123");

        assertThat(result).isNull();
    }

    // ── revokeAllSessions ────────────────────────────────────────────────

    @Test
    @DisplayName("revokeAllSessions calls jdbcTemplate.update with userId and tenantSchema")
    void shouldCallUpdateOnRevoke() {
        UUID userId = UUID.randomUUID();

        adapter.revokeAllSessions(userId, "kv_abc123");

        verify(jdbcTemplate).update(anyString(), eq(userId), eq("kv_abc123"));
    }

    // ── v1s-15-7 item 19: real SQL + RowMapper ─────────────────────────────

    @Test
    @DisplayName("getTokensValidAfter SQL joins public.tenants on schema_name and RowMapper converts Timestamp/null")
    @SuppressWarnings("unchecked")
    void shouldQueryMembershipJoinedOnSchemaNameAndMapTimestamp() throws Exception {
        UUID userId = UUID.randomUUID();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper<Instant>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.queryForObject(sqlCaptor.capture(), mapperCaptor.capture(), eq(userId), eq("kv_abc123")))
                .thenReturn(null);

        adapter.getTokensValidAfter(userId, "kv_abc123");

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("SELECT m.tokens_valid_after")
                .contains("FROM public.user_tenant_memberships m")
                .contains("JOIN public.tenants t ON t.id = m.tenant_id")
                .contains("m.user_id = ?")
                .contains("t.schema_name = ?");

        RowMapper<Instant> mapper = mapperCaptor.getValue();
        Instant cutoff = Instant.parse("2026-10-11T10:00:00.123456Z");
        ResultSet withTva = mock(ResultSet.class);
        when(withTva.getTimestamp("tokens_valid_after")).thenReturn(Timestamp.from(cutoff));
        assertThat(mapper.mapRow(withTva, 0)).isEqualTo(cutoff);

        ResultSet withoutTva = mock(ResultSet.class);
        when(withoutTva.getTimestamp("tokens_valid_after")).thenReturn(null);
        assertThat(mapper.mapRow(withoutTva, 0)).isNull();
    }

    @Test
    @DisplayName("revokeAllSessions SQL sets tokens_valid_after = now() scoped to the tenant resolved by schema_name")
    void revokeAllSessionsSqlScopesToTenantBySchemaName() {
        UUID userId = UUID.randomUUID();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        adapter.revokeAllSessions(userId, "kv_abc123");

        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(userId), eq("kv_abc123"));
        assertThat(sqlCaptor.getValue())
                .contains("UPDATE public.user_tenant_memberships")
                .contains("tokens_valid_after = now()")
                .contains("WHERE user_id = ?")
                .contains("tenant_id = (SELECT id FROM public.tenants WHERE schema_name = ?)");
    }

    @Test
    @DisplayName("revokeAllSessionsEverywhere SQL updates every membership of the user, no tenant filter")
    void revokeAllSessionsEverywhereSqlHasNoTenantFilter() {
        UUID userId = UUID.randomUUID();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        adapter.revokeAllSessionsEverywhere(userId);

        verify(jdbcTemplate).update(sqlCaptor.capture(), eq(userId));
        assertThat(sqlCaptor.getValue())
                .contains("UPDATE public.user_tenant_memberships")
                .contains("tokens_valid_after = now()")
                .contains("WHERE user_id = ?")
                .doesNotContain("schema_name")
                .doesNotContain("tenant_id");
    }
}
