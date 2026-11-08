package com.keevo.admin.user.adapter.out.persistence;

import com.keevo.admin.crosstenant.CrossTenantSql;
import com.keevo.admin.crosstenant.CursorPage;
import com.keevo.admin.crosstenant.KeysetCursor;
import com.keevo.admin.crosstenant.KeysetSort;
import com.keevo.admin.crosstenant.AdminParams;
import com.keevo.admin.user.domain.model.AdminUserViews;
import com.keevo.admin.user.domain.model.AdminUserViews.DeviceSession;
import com.keevo.admin.user.domain.model.AdminUserViews.Membership;
import com.keevo.admin.user.domain.model.AdminUserViews.RoleCount;
import com.keevo.admin.user.domain.model.AdminUserViews.UserItem;
import com.keevo.admin.user.domain.port.out.AdminUserReadPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Story 20.13 — platform users read from the public tables (no tenant schema, no per-tenant query).
 * <ul>
 *   <li>list: keyset on {@code (created_at DESC, id DESC)} — index {@code idx_users_created_at_id} (V59); device
 *       count and last use per row on {@code idx_refresh_tokens_live_by_user}; memberships of the page in one
 *       {@code user_id = ANY(?)} query;</li>
 *   <li>stats: one aggregate over {@code users} + one grouped count over memberships;</li>
 *   <li>devices: the live refresh tokens of the user grouped by device session (16.11).</li>
 * </ul>
 * Deleted tenants and KV-ADMIN never appear as memberships; platform figures exclude super-admins.
 */
@Component
public class JdbcAdminUserReader implements AdminUserReadPort {

    static final String SUPER_ADMIN = "SUPER_ADMIN";

    private static final String USER_COLUMNS =
            "SELECT CAST(u.id AS text) AS id, u.first_name, u.last_name, u.phone_number, u.email, u.is_active, "
            + "(u.role = 'SUPER_ADMIN') AS super_admin, u.created_at, "
            + "(SELECT COUNT(DISTINCT rt.session_id) FROM public.refresh_tokens rt WHERE rt.user_id = u.id "
            + "AND rt.revoked = false AND rt.expires_at > now()) AS device_count, "
            + "(SELECT MAX(rt.last_used_at) FROM public.refresh_tokens rt WHERE rt.user_id = u.id "
            + "AND rt.revoked = false AND rt.expires_at > now()) AS last_seen_at "
            + "FROM public.users u ";

    /** List order: newest account first — index {@code idx_users_created_at_id} (V59). */
    static final KeysetSort USER_SORT = new KeysetSort(List.of(
            new KeysetSort.Key("u.created_at", "timestamptz"), new KeysetSort.Key("u.id", "uuid")), true);

    private static final String LIVE_TENANT = CrossTenantSql.LIVE_TENANT;

    private final JdbcTemplate jdbc;

    public JdbcAdminUserReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CursorPage<UserItem> users(UserCriteria c) {
        KeysetCursor cursor = KeysetCursor.decode(c.cursor(), c.filters(), USER_SORT);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(USER_COLUMNS).append("WHERE ")
                .append(filters(c.role(), c.active(), c.tenantId(), c.search(), params));
        if (cursor != null) {
            sql.append(" AND (u.created_at, u.id) < (CAST(? AS timestamptz), CAST(? AS uuid))");
            params.add(cursor.keys().get(0));
            params.add(cursor.keys().get(1));
        }
        sql.append(" ORDER BY u.created_at DESC, u.id DESC LIMIT ").append(c.limit() + 1);

        List<Row> rows = jdbc.query(sql.toString(), (rs, n) -> row(rs), params.toArray());
        boolean more = rows.size() > c.limit();
        List<Row> page = more ? rows.subList(0, c.limit()) : rows;
        Map<String, List<Membership>> memberships = memberships(page.stream().map(Row::id).toList());
        List<UserItem> items = page.stream().map(r -> r.item(memberships.getOrDefault(r.id(), List.of()))).toList();
        String next = null;
        if (more) {
            Row last = page.get(page.size() - 1);
            next = KeysetCursor.encode(c.filters(), KeysetCursor.PLATFORM_SLOT,
                    List.of(last.createdAt().toString(), last.id()));
        }
        return new CursorPage<>(items, next, more);
    }

    @Override
    public Optional<UserItem> user(String userId) {
        List<Row> rows = jdbc.query(USER_COLUMNS + "WHERE u.id = CAST(? AS uuid)", (rs, n) -> row(rs), userId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row row = rows.get(0);
        return Optional.of(row.item(memberships(List.of(row.id())).getOrDefault(row.id(), List.of())));
    }

    @Override
    public Totals totals(StatsCriteria c) {
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(c.newFrom()));
        params.add(Timestamp.from(c.newTo()));
        String where = "u.role <> '" + SUPER_ADMIN + "' AND " + filters(c.role(), c.active(), null, null, params);
        return jdbc.queryForObject("SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE u.is_active) AS active, "
                        + "COUNT(*) FILTER (WHERE u.created_at >= ? AND u.created_at < ?) AS created "
                        + "FROM public.users u WHERE " + where,
                (rs, n) -> new Totals(rs.getLong("total"), rs.getLong("active"), rs.getLong("created")),
                params.toArray());
    }

    @Override
    public List<RoleCount> byRole(StatsCriteria c) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("u.role <> '" + SUPER_ADMIN + "' AND " + LIVE_TENANT);
        if (c.role() != null) {
            where.append(" AND m.role = ?");
            params.add(c.role());
        }
        if (c.active() != null) {
            where.append(" AND u.is_active = ?");
            params.add(c.active());
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT m.role, COUNT(DISTINCT m.user_id) AS users FROM public.user_tenant_memberships m "
                        + "JOIN public.users u ON u.id = m.user_id JOIN public.tenants t ON t.id = m.tenant_id "
                        + "WHERE " + where + " GROUP BY m.role ORDER BY m.role",
                rs -> {
                    counts.put(rs.getString("role"), rs.getLong("users"));
                }, params.toArray());
        return counts.entrySet().stream()
                .map(e -> new RoleCount(e.getKey(), AdminUserViews.roleLabel(e.getKey()), e.getValue()))
                .toList();
    }

    @Override
    public List<DeviceSession> devices(String userId) {
        return jdbc.query("SELECT CAST(rt.session_id AS text) AS session_id, MAX(rt.device_label) AS device_label, "
                        + "MAX(rt.last_used_at) AS last_used_at, MAX(CAST(t.id AS text)) AS tenant_id, "
                        + "MAX(COALESCE(NULLIF(btrim(t.designation), ''), NULLIF(btrim(t.name), ''))) AS tenant_name "
                        + "FROM public.refresh_tokens rt LEFT JOIN public.tenants t ON t.schema_name = rt.tenant_id "
                        + "AND " + LIVE_TENANT + " "
                        + "WHERE rt.user_id = CAST(? AS uuid) AND rt.revoked = false AND rt.expires_at > now() "
                        + "AND (t.id IS NOT NULL OR rt.tenant_id = 'public') "
                        + "GROUP BY rt.session_id ORDER BY MAX(rt.last_used_at) DESC NULLS LAST",
                (rs, n) -> {
                    String label = rs.getString("device_label");
                    String tenantId = rs.getString("tenant_id");
                    String tenantName = rs.getString("tenant_name");
                    return new DeviceSession(rs.getString("session_id"),
                            label == null || label.isBlank() ? AdminUserViews.UNKNOWN_DEVICE : label,
                            tenantId, tenantId == null ? AdminUserViews.PLATFORM
                                    : tenantName == null ? com.keevo.admin.AdminLabels.UNNAMED_TENANT : tenantName,
                            instant(rs, "last_used_at"));
                }, userId);
    }

    @Override
    public Map<String, String> displayNames(Collection<String> userIds) {
        Map<String, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT CAST(u.id AS text) AS id, NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), '') "
                        + "AS name FROM public.users u WHERE u.id = ANY (CAST(string_to_array(?, ',') AS uuid[]))",
                rs -> {
                    String name = rs.getString("name");
                    names.put(rs.getString("id"), name == null ? AdminUserViews.UNNAMED_USER : name);
                }, String.join(",", userIds));
        return names;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The WHERE clause of the user filters (always non-empty); appends the bound values. */
    private static String filters(String role, Boolean active, String tenantId, String search, List<Object> params) {
        StringBuilder where = new StringBuilder("TRUE");
        if (SUPER_ADMIN.equals(role)) {
            where.append(" AND u.role = '" + SUPER_ADMIN + "'");
        } else if (role != null) {
            where.append(" AND EXISTS (SELECT 1 FROM public.user_tenant_memberships m JOIN public.tenants t "
                    + "ON t.id = m.tenant_id WHERE m.user_id = u.id AND m.role = ? AND " + LIVE_TENANT + ")");
            params.add(role);
        }
        if (active != null) {
            where.append(" AND u.is_active = ?");
            params.add(active);
        }
        if (tenantId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM public.user_tenant_memberships m2 JOIN public.tenants t "
                    + "ON t.id = m2.tenant_id WHERE m2.user_id = u.id AND m2.tenant_id = CAST(? AS uuid) AND "
                    + LIVE_TENANT + ")");
            params.add(tenantId);
        }
        if (search != null) {
            String like = AdminParams.likePattern(search);
            where.append(" AND (lower(concat_ws(' ', u.first_name, u.last_name)) LIKE ? OR u.phone_number LIKE ? "
                    + "OR lower(u.email) LIKE ? OR lower(u.username) LIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        return where.toString();
    }

    /** Memberships of the given users in live tenants — one query. */
    private Map<String, List<Membership>> memberships(List<String> userIds) {
        Map<String, List<Membership>> byUser = new HashMap<>();
        if (userIds.isEmpty()) {
            return byUser;
        }
        jdbc.query("SELECT CAST(m.user_id AS text) AS user_id, CAST(t.id AS text) AS tenant_id, "
                        + "COALESCE(NULLIF(btrim(t.designation), ''), NULLIF(btrim(t.name), '')) AS tenant_name, "
                        + "m.role, m.is_active FROM public.user_tenant_memberships m "
                        + "JOIN public.tenants t ON t.id = m.tenant_id "
                        + "WHERE m.user_id = ANY (CAST(string_to_array(?, ',') AS uuid[])) AND " + LIVE_TENANT
                        + " ORDER BY m.created_at",
                rs -> {
                    String tenantName = rs.getString("tenant_name");
                    byUser.computeIfAbsent(rs.getString("user_id"), k -> new ArrayList<>()).add(new Membership(
                            rs.getString("tenant_id"),
                            tenantName == null ? com.keevo.admin.AdminLabels.UNNAMED_TENANT : tenantName,
                            rs.getString("role"), AdminUserViews.roleLabel(rs.getString("role")),
                            rs.getBoolean("is_active")));
                }, String.join(",", userIds));
        return byUser;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getString("id"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("phone_number"), rs.getString("email"), rs.getBoolean("is_active"),
                rs.getBoolean("super_admin"), rs.getLong("device_count"), instant(rs, "last_seen_at"),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private record Row(String id, String firstName, String lastName, String phone, String email, boolean active,
                       boolean superAdmin, long deviceCount, Instant lastSeenAt, Instant createdAt) {
        UserItem item(List<Membership> memberships) {
            String name = String.join(" ", firstName == null ? "" : firstName.trim(),
                    lastName == null ? "" : lastName.trim()).trim();
            return new UserItem(id, name.isEmpty() ? AdminUserViews.UNNAMED_USER : name, phone, email, active,
                    superAdmin, memberships, deviceCount, lastSeenAt, createdAt);
        }
    }
}
