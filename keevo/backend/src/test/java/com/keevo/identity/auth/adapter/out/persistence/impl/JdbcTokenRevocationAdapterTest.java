package com.keevo.identity.auth.adapter.out.persistence.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

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

    // ── revokeAllSessions ────────────────────────────────────────────────

    @Test
    @DisplayName("revokeAllSessions calls jdbcTemplate.update with userId and tenantSchema")
    void shouldCallUpdateOnRevoke() {
        UUID userId = UUID.randomUUID();

        adapter.revokeAllSessions(userId, "kv_abc123");

        verify(jdbcTemplate).update(anyString(), eq(userId), eq("kv_abc123"));
    }

    // ── v1s-15-7 item 19: real SQL (reads moved to AccessStateLoader, Story 16.11) ──

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
