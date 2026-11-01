package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.api.TokenRevocationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JdbcTokenRevocationAdapter — Raw JDBC adapter for token revocation (Story 12.2, NFR12).
 *
 * <p>Writes the {@code tokens_valid_after} column on {@code public.user_tenant_memberships}
 * using fully-qualified table names and parameterized queries (ARCH18: no search_path dependency).
 * Resolves JWT claim {@code tenantId} (schema name {@code kv_xxxxxx}) to tenant UUID via
 * JOIN on {@code public.tenants}.
 *
 * <p>Story 16.11 — reads of the cutoff moved to the access state ({@code AccessStateLoader}); the
 * membership trigger broadcasts each write to every instance.
 */
@Component
public class JdbcTokenRevocationAdapter implements TokenRevocationPort {

    private static final String REVOKE_TVA =
            "UPDATE public.user_tenant_memberships " +
            "SET tokens_valid_after = now(), updated_at = now() " +
            "WHERE user_id = ? " +
            "  AND tenant_id = (SELECT id FROM public.tenants WHERE schema_name = ?)";

    private static final String REVOKE_TVA_ALL =
            "UPDATE public.user_tenant_memberships " +
            "SET tokens_valid_after = now(), updated_at = now() " +
            "WHERE user_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcTokenRevocationAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void revokeAllSessions(UUID userId, String tenantSchema) {
        jdbcTemplate.update(REVOKE_TVA, userId, tenantSchema);
    }

    @Override
    public void revokeAllSessionsEverywhere(UUID userId) {
        jdbcTemplate.update(REVOKE_TVA_ALL, userId);
    }
}