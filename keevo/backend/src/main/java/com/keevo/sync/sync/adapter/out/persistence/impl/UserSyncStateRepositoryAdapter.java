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
 * DDL is handled by {@link com.keevo.sync.sync.adapter.out.persistence.UserSyncStateDdlInitializer}.
 */
@Repository
public class UserSyncStateRepositoryAdapter implements UserSyncStateRepository {

    private static final String FIND_BY_DEVICE =
            "SELECT device_id, user_id, tenant_id, last_push_at, last_pull_at, updated_at "
                    + "FROM public.user_sync_state WHERE device_id = ?";

    private static final String UPSERT =
            "INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_push_at, updated_at) "
                    + "VALUES (?, ?, ?, NOW(), NOW()) "
                    + "ON CONFLICT (device_id) DO UPDATE SET "
                    + "  last_push_at = NOW(), "
                    + "  updated_at   = NOW()";

    private static final String UPSERT_ON_PULL =
            "INSERT INTO public.user_sync_state (device_id, user_id, tenant_id, last_pull_at, updated_at) "
                    + "VALUES (?, ?, ?, NOW(), NOW()) "
                    + "ON CONFLICT (device_id) DO UPDATE SET "
                    + "  last_pull_at = NOW(), "
                    + "  updated_at   = NOW()";

    private static final String FIND_ALL_BY_TENANT =
            "SELECT device_id, user_id, tenant_id, last_push_at, last_pull_at, updated_at "
                    + "FROM public.user_sync_state WHERE tenant_id = ? ORDER BY updated_at DESC";

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
    public void upsert(UserSyncState state) {
        jdbcTemplate.update(UPSERT, state.deviceId(), state.userId(), state.tenantId());
    }

    @Override
    public void upsertOnPull(UserSyncState state) {
        jdbcTemplate.update(UPSERT_ON_PULL, state.deviceId(), state.userId(), state.tenantId());
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
                toInstant(rs, "updated_at"));
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }
}
