package com.keevo.identity.auth.adapter.out.persistence.impl;

import com.keevo.identity.auth.domain.port.out.TokenRevocationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * JdbcTokenRevocationAdapter — Raw JDBC adapter for token revocation (Story 12.2, NFR12).
 *
 * <p>Reads/writes the {@code tokens_valid_after} column on {@code public.user_tenant_memberships}
 * using fully-qualified table names and parameterized queries (ARCH18: no search_path dependency).
 * Resolves JWT claim {@code tenantId} (schema name {@code kv_xxxxxx}) to tenant UUID via
 * JOIN on {@code public.tenants}.
 *
 * <p>NOT the bean injected into consumers — {@link CachedTokenRevocationAdapter} is {@code @Primary}.
 */
@Component
public class JdbcTokenRevocationAdapter implements TokenRevocationPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcTokenRevocationAdapter.class);

    private static final String SELECT_TVA =
            "SELECT m.tokens_valid_after " +
            "FROM public.user_tenant_memberships m " +
            "JOIN public.tenants t ON t.id = m.tenant_id " +
            "WHERE m.user_id = ? AND t.schema_name = ?";

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
    public Instant getTokensValidAfter(UUID userId, String tenantSchema) {
        try {
            return jdbcTemplate.queryForObject(SELECT_TVA,
                    (rs, rowNum) -> {
                        java.sql.Timestamp ts = rs.getTimestamp("tokens_valid_after");
                        return ts != null ? ts.toInstant() : null;
                    }, userId, tenantSchema);
        } catch (EmptyResultDataAccessException e) {
            // No membership row for (userId, tenant) → treat as never-revoked (allow).
            // The EMPLOYEE branch will separately reject via ACCOUNT_INACTIVE/STORE_REASSIGNED.
            return null;
        } catch (DataAccessException e) {
            // Fail-open (code-review decision 2026-07-21): on DB unavailability (conn refused,
            // timeout, IncorrectResultSize, ...) we cannot determine revocation state — treat
            // as not-revoked (allow) rather than 500-ing every request for ALL roles. The filter
            // runs before @RestControllerAdvice, so an uncaught exception here would leak a
            // stack trace and bypass the JSON contract. Security cost: a revoked token may pass
            // during the outage (<5min cross-instance propagation is already contractual per
            // NFR12; the CachedTokenRevocationAdapter masks most blips via stale cache).
            log.warn("Token revocation lookup failed for user {} tenant {}: {} — fail-open (allow)",
                    userId, tenantSchema, e.getMessage());
            return null;
        }
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