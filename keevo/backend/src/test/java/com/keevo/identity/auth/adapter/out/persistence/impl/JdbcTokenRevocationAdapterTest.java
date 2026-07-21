package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
}