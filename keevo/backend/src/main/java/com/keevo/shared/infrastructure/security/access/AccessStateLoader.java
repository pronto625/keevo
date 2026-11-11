package com.keevo.shared.infrastructure.security.access;

import com.keevo.shared.infrastructure.persistence.TenantSchema;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Story 16.11 — reads the {@link AccessState} of (user, account) from the database: membership role
 * and status, account status, revocation cutoff and open device sessions in one statement on the
 * public tables (Story 19.14: with the membership's custom role, joined by primary key), plus the employee row (store, status, password flag, first name) when there is
 * one: the EMPLOYEE guards, and the actor's first name (an owner has a name only through an
 * employee row — {@code public.users} stores none).
 *
 * <p>Fully-qualified tables and bind parameters only; the account schema is validated before it is
 * concatenated (ARCH18). A {@link org.springframework.dao.DataAccessException} propagates: the
 * caller never caches a failure.
 */
@Component
public class AccessStateLoader {

    private static final String SELECT_MEMBERSHIP =
            "SELECT m.role, m.is_active, m.tokens_valid_after, t.status, "
            + "ARRAY(SELECT DISTINCT r.session_id FROM public.refresh_tokens r "
            + "      WHERE r.user_id = m.user_id AND r.tenant_id = t.schema_name "
            + "        AND r.revoked = false AND r.expires_at > now() AND r.session_id IS NOT NULL) AS sessions, "
            // Story 19.14 — the owner-defined role of the membership (primary-key join, same statement)
            + "cr.id AS cr_id, cr.name AS cr_name, cr.base_role AS cr_base, cr.permissions AS cr_permissions, "
            + "cr.store_ids AS cr_stores "
            + "FROM public.user_tenant_memberships m "
            + "JOIN public.tenants t ON t.id = m.tenant_id "
            + "LEFT JOIN public.tenant_roles cr ON cr.id = m.custom_role_id "
            + "WHERE m.user_id = ? AND t.schema_name = ? "
            + "ORDER BY m.is_active DESC LIMIT 1";

    private final JdbcTemplate jdbcTemplate;

    public AccessStateLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return the access state, or {@code null} when the user has no membership in that account */
    public AccessState load(UUID userId, String schema) {
        List<AccessState> rows = jdbcTemplate.query(SELECT_MEMBERSHIP, (rs, n) -> {
            Timestamp tva = rs.getTimestamp("tokens_valid_after");
            return new AccessState(
                    rs.getString("role"),
                    rs.getBoolean("is_active"),
                    rs.getString("status"),
                    tva != null ? tva.toInstant() : null,
                    null,
                    uuids(rs.getArray("sessions")),
                    customRole(rs));
        }, userId, schema);
        if (rows.isEmpty()) {
            return null;
        }
        AccessState state = rows.get(0);
        // Employee row: guards for an EMPLOYEE; first name for an owner who has one (promoted).
        if ("DELETED".equals(state.tenantStatus()) || TenantSchema.PLATFORM_ADMIN_SCHEMA.equals(schema)) {
            return state;
        }
        List<AccessState.EmployeeState> employees = jdbcTemplate.query(
                "SELECT store_id, status, password_change_required, first_name FROM \""
                + TenantSchema.validate(schema) + "\".employees WHERE user_id = ? LIMIT 1",
                (rs, n) -> new AccessState.EmployeeState(
                        rs.getObject("store_id", UUID.class),
                        rs.getString("status"),
                        rs.getBoolean("password_change_required"),
                        rs.getString("first_name")),
                userId);
        return new AccessState(state.role(), state.membershipActive(), state.tenantStatus(),
                state.tokensValidAfter(), employees.isEmpty() ? null : employees.get(0),
                state.openSessionIds(), state.customRole());
    }

    /** Story 19.14 — the custom role columns of the row, {@code null} when the membership holds none. */
    private static AccessState.CustomRoleState customRole(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("cr_id", UUID.class);
        if (id == null) {
            return null;
        }
        Set<String> permissions = new HashSet<>();
        Array granted = rs.getArray("cr_permissions");
        if (granted != null) {
            for (Object value : (Object[]) granted.getArray()) {
                if (value != null) permissions.add(value.toString());
            }
        }
        Array stores = rs.getArray("cr_stores");
        List<UUID> storeIds = stores == null ? null : new ArrayList<>(uuids(stores));
        return new AccessState.CustomRoleState(id, rs.getString("cr_name"), rs.getString("cr_base"), permissions,
                storeIds);
    }

    private static Set<UUID> uuids(Array array) throws java.sql.SQLException {
        Set<UUID> ids = new HashSet<>();
        if (array == null) return ids;
        Object[] values = (Object[]) array.getArray();
        for (Object v : values) {
            if (v instanceof UUID u) ids.add(u);
            else if (v != null) ids.add(UUID.fromString(v.toString()));
        }
        return ids;
    }
}
