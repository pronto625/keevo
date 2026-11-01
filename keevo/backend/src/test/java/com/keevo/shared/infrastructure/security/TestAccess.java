package com.keevo.shared.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Story 16.11 — test helper for full-context tests: the access token proves identity only, so the
 * role the test wants must exist in the database. {@link #grant} upserts an active membership of the
 * user in the account (no row in {@code public.users} is needed); {@link #token} grants and returns a
 * session-less access token (the indicative legacy claims are ignored by the server).
 */
public final class TestAccess {

    private TestAccess() {
    }

    /** Upserts an active {@code role} membership of {@code userId} in the account {@code schema}. */
    public static void grant(JdbcTemplate jdbc, UUID userId, String schema, String role) {
        jdbc.update("INSERT INTO public.user_tenant_memberships "
                + "(id, created_at, updated_at, is_active, role, tenant_id, user_id) "
                + "SELECT gen_random_uuid(), now(), now(), true, ?, t.id, ? FROM public.tenants t "
                + "WHERE t.schema_name = ? "
                + "ON CONFLICT (user_id, tenant_id) DO UPDATE SET role = EXCLUDED.role, is_active = true, "
                + "updated_at = now()", role, userId, schema);
    }

    public static String token(JdbcTemplate jdbc, JwtTokenProvider jwt, UUID userId, String schema, String role) {
        grant(jdbc, userId, schema, role);
        return jwt.generateAccessToken(userId, schema, role);
    }

    public static String token(JdbcTemplate jdbc, JwtTokenProvider jwt, UUID userId, String schema, String role,
                               String tenantStatus, UUID storeId, boolean passwordChangeRequired) {
        grant(jdbc, userId, schema, role);
        return jwt.generateAccessToken(userId, schema, role, tenantStatus, storeId, passwordChangeRequired);
    }
}
