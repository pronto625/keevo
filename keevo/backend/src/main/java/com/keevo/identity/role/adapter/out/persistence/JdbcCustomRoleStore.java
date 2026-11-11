package com.keevo.identity.role.adapter.out.persistence;

import com.keevo.identity.role.domain.model.CustomRole;
import com.keevo.identity.role.domain.port.out.CustomRoleStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Story 19.14 — JDBC adapter of {@link CustomRoleStore} on {@code public.tenant_roles} and
 * {@code public.user_tenant_memberships.custom_role_id} (public schema only; the JPA membership entity does not map
 * the column, so its saves never touch it). Runs on the caller's transaction. Every statement is keyed by the
 * company id (index {@code ux_tenant_roles_tenant_name}, primary keys, {@code uk_user_tenant_memberships_user_tenant},
 * {@code ix_utm_custom_role}); lists are one statement whatever their size.
 */
@Component
public class JdbcCustomRoleStore implements CustomRoleStore {

    static final String COLUMNS = "r.id, r.tenant_id, r.name, r.base_role, r.permissions, r.store_ids, r.created_at, "
            + "r.updated_at";

    static final String FIND_ALL_SQL = "SELECT " + COLUMNS + ", "
            + "(SELECT count(*) FROM public.user_tenant_memberships m WHERE m.custom_role_id = r.id) AS members "
            + "FROM public.tenant_roles r WHERE r.tenant_id = ? ORDER BY lower(r.name), r.id";

    static final String FIND_SQL = "SELECT " + COLUMNS + " FROM public.tenant_roles r WHERE r.tenant_id = ? AND r.id = ?";

    static final String NAME_TAKEN_SQL = "SELECT EXISTS (SELECT 1 FROM public.tenant_roles WHERE tenant_id = ? "
            + "AND lower(name) = lower(?) AND (CAST(? AS uuid) IS NULL OR id <> CAST(? AS uuid)))";

    static final String COUNT_SQL = "SELECT count(*) FROM public.tenant_roles WHERE tenant_id = ?";

    static final String INSERT_SQL = "INSERT INTO public.tenant_roles (id, tenant_id, name, base_role, permissions, "
            + "store_ids, created_at, updated_at, created_by) VALUES (?, ?, ?, ?, ?, CAST(? AS uuid[]), ?, ?, ?)";

    static final String UPDATE_SQL = "UPDATE public.tenant_roles SET name = ?, permissions = ?, store_ids = CAST(? AS uuid[]), "
            + "updated_at = ? WHERE tenant_id = ? AND id = ?";

    static final String DELETE_SQL = "DELETE FROM public.tenant_roles WHERE tenant_id = ? AND id = ?";

    static final String MEMBER_COUNT_SQL = "SELECT count(*) FROM public.user_tenant_memberships "
            + "WHERE tenant_id = ? AND custom_role_id = ?";

    static final String ASSIGN_SQL = "UPDATE public.user_tenant_memberships SET custom_role_id = ?, updated_at = now() "
            + "WHERE tenant_id = ? AND user_id = ? AND custom_role_id IS DISTINCT FROM ?";

    static final String ASSIGNED_SQL = "SELECT m.user_id, r.id, r.name, r.permissions "
            + "FROM public.user_tenant_memberships m JOIN public.tenant_roles r ON r.id = m.custom_role_id "
            + "WHERE m.tenant_id = ? AND m.user_id = ANY(?)";

    private static final RowMapper<CustomRole> ROW = (rs, n) -> new CustomRole(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("name"),
            rs.getString("base_role"),
            strings(rs.getArray("permissions")),
            uuids(rs.getArray("store_ids")),
            instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public JdbcCustomRoleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CustomRole.WithMembers> findAll(UUID tenantId) {
        return jdbc.query(FIND_ALL_SQL, (rs, n) -> new CustomRole.WithMembers(ROW.mapRow(rs, n), rs.getInt("members")),
                tenantId);
    }

    @Override
    public Optional<CustomRole> findById(UUID tenantId, UUID roleId) {
        return jdbc.query(FIND_SQL, ROW, tenantId, roleId).stream().findFirst();
    }

    @Override
    public boolean nameTaken(UUID tenantId, String name, UUID excludeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(NAME_TAKEN_SQL, Boolean.class, tenantId, name,
                excludeId, excludeId));
    }

    @Override
    public int count(UUID tenantId) {
        Integer count = jdbc.queryForObject(COUNT_SQL, Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    @Override
    public void insert(CustomRole role, UUID createdBy) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_SQL);
            ps.setObject(1, role.id());
            ps.setObject(2, role.tenantId());
            ps.setString(3, role.name());
            ps.setString(4, role.baseRole());
            ps.setArray(5, textArray(con, role.permissions()));
            setUuids(ps, 6, con, role.storeIds());
            ps.setTimestamp(7, Timestamp.from(role.createdAt()));
            ps.setTimestamp(8, Timestamp.from(role.updatedAt()));
            ps.setObject(9, createdBy);
            return ps;
        });
    }

    @Override
    public void update(CustomRole role) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(UPDATE_SQL);
            ps.setString(1, role.name());
            ps.setArray(2, textArray(con, role.permissions()));
            setUuids(ps, 3, con, role.storeIds());
            ps.setTimestamp(4, Timestamp.from(role.updatedAt()));
            ps.setObject(5, role.tenantId());
            ps.setObject(6, role.id());
            return ps;
        });
    }

    @Override
    public void delete(UUID tenantId, UUID roleId) {
        jdbc.update(DELETE_SQL, tenantId, roleId);
    }

    @Override
    public int memberCount(UUID tenantId, UUID roleId) {
        Integer count = jdbc.queryForObject(MEMBER_COUNT_SQL, Integer.class, tenantId, roleId);
        return count == null ? 0 : count;
    }

    @Override
    public void assign(UUID tenantId, UUID userId, UUID roleId) {
        jdbc.update(ASSIGN_SQL, roleId, tenantId, userId, roleId);
    }

    @Override
    public Optional<CustomRole.Assigned> findAssigned(UUID tenantId, UUID userId) {
        return Optional.ofNullable(findAssigned(tenantId, List.of(userId)).get(userId));
    }

    @Override
    public Map<UUID, CustomRole.Assigned> findAssigned(UUID tenantId, Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CustomRole.Assigned> result = new HashMap<>();
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(ASSIGNED_SQL);
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("uuid", userIds.toArray()));
            return ps;
        }, (ResultSet rs) -> {
            result.put(rs.getObject("user_id", UUID.class), new CustomRole.Assigned(
                    rs.getObject("id", UUID.class), rs.getString("name"), strings(rs.getArray("permissions"))));
        });
        return result;
    }

    private static Array textArray(Connection con, Set<String> values) throws SQLException {
        return con.createArrayOf("text", values.stream().sorted().toArray());
    }

    /** {@code null} (every store) is bound as a typed NULL the {@code CAST(? AS uuid[])} accepts. */
    private static void setUuids(PreparedStatement ps, int index, Connection con, List<UUID> values)
            throws SQLException {
        if (values == null) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setArray(index, con.createArrayOf("uuid", values.toArray()));
        }
    }

    private static Set<String> strings(Array array) throws SQLException {
        Set<String> values = new HashSet<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                if (value != null) values.add(value.toString());
            }
        }
        return values;
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        List<UUID> values = new ArrayList<>();
        for (Object value : (Object[]) array.getArray()) {
            if (value instanceof UUID id) values.add(id);
            else if (value != null) values.add(UUID.fromString(value.toString()));
        }
        return values;
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
