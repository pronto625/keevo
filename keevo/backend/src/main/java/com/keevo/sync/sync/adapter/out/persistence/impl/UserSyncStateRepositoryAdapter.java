package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.domain.model.UserSyncState;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * UserSyncStateRepositoryAdapter — JdbcTemplate implementation of {@link UserSyncStateRepository}.
 *
 * <p>Operates on the PUBLIC schema ({@code public.user_sync_state}).
 * DDL is managed by Flyway (see {@code V1__baseline_public.sql}).
 */
@Repository
public class UserSyncStateRepositoryAdapter implements UserSyncStateRepository {

    private static final String FIND_BY_DEVICE =
            "SELECT device_id, user_id, tenant_id, last_push_at, last_pull_at, updated_at, "
                    + "last_scope_restricted, last_scope_store_id, last_scope_known, last_scope_client_stores "
                    + "FROM public.user_sync_state WHERE device_id = ?";

    /**
     * Story 12.12 — closes the TOCTOU between the controller's ownership check and the write:
     * a row owned by another user is never re-assigned (0 rows written instead).
     */
    private static final String OWNER_GUARD =
            "WHERE public.user_sync_state.user_id = EXCLUDED.user_id";

    private static final String UPSERT =
            "INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_push_at, updated_at) "
                    + "VALUES (?, ?, ?, NOW(), NOW()) "
                    + "ON CONFLICT (device_id) DO UPDATE SET "
                    + "  last_push_at = NOW(), "
                    + "  updated_at   = NOW() "
                    + OWNER_GUARD;

    private static final String UPSERT_ON_PULL =
            "INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_pull_at, updated_at) "
                    + "VALUES (?, ?, ?, NOW(), NOW()) "
                    + "ON CONFLICT (device_id) DO UPDATE SET "
                    + "  last_pull_at = NOW(), "
                    + "  updated_at   = NOW() "
                    + OWNER_GUARD;

    private static final String FIND_ALL_BY_TENANT =
            "SELECT device_id, user_id, tenant_id, last_push_at, last_pull_at, updated_at, "
                    + "last_scope_restricted, last_scope_store_id, last_scope_known, last_scope_client_stores "
                    + "FROM public.user_sync_state WHERE tenant_id = ? ORDER BY updated_at DESC";

    private static final String REMEMBER_PULL_SCOPE =
            "UPDATE public.user_sync_state SET last_scope_restricted = ?, last_scope_store_id = ?, "
                    + "last_scope_known = TRUE, last_scope_client_stores = ? "
                    + "WHERE device_id = ? AND user_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public UserSyncStateRepositoryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserSyncState> findByDeviceId(String deviceId) {
        List<UserSyncState> results = jdbcTemplate.query(FIND_BY_DEVICE, rowMapper(), deviceId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean upsert(UserSyncState state) {
        return jdbcTemplate.update(UPSERT, state.deviceId(), state.userId(), state.tenantId()) > 0;
    }

    @Override
    public boolean upsertOnPull(UserSyncState state) {
        return jdbcTemplate.update(UPSERT_ON_PULL, state.deviceId(), state.userId(), state.tenantId()) > 0;
    }

    @Override
    public boolean rememberPullScope(String deviceId, UUID userId, boolean restricted, UUID storeId) {
        return rememberPullScope(deviceId, userId, restricted, storeId, null);
    }

    @Override
    public boolean rememberPullScope(String deviceId, UUID userId, boolean restricted, UUID storeId,
                                     String clientStores) {
        return jdbcTemplate.update(REMEMBER_PULL_SCOPE, restricted, storeId, clientStores, deviceId, userId) > 0;
    }

    @Override
    public List<UserSyncState> findAllByTenantId(String tenantId) {
        return jdbcTemplate.query(FIND_ALL_BY_TENANT, rowMapper(), tenantId);
    }

    private RowMapper<UserSyncState> rowMapper() {
        return (rs, rowNum) -> new UserSyncState(
                rs.getString("device_id"),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("tenant_id"),
                toInstant(rs, "last_push_at"),
                toInstant(rs, "last_pull_at"),
                toInstant(rs, "updated_at"),
                rs.getBoolean("last_scope_restricted"),
                rs.getString("last_scope_store_id") != null
                        ? UUID.fromString(rs.getString("last_scope_store_id")) : null,
                rs.getBoolean("last_scope_known"),
                rs.getString("last_scope_client_stores"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }
}
