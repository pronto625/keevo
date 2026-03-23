package com.keevo.sync.sync.domain.port.out;

import com.keevo.sync.sync.domain.model.UserSyncState;

import java.util.Optional;

/**
 * UserSyncStateRepository — port-out interface for per-device sync state persistence.
 *
 * <p>Implemented by {@code UserSyncStateRepositoryAdapter} which uses JdbcTemplate
 * against the PUBLIC schema. MUST NOT use EntityManager (which resolves via TenantContext
 * to the per-tenant schema).
 *
 * <p>Story 5.4 — AC8/AC9/AC10.
 */
public interface UserSyncStateRepository {

    /**
     * Returns the sync state for the given device, or empty if the device has never pushed.
     */
    Optional<UserSyncState> findByDeviceId(String deviceId);

    /**
     * Inserts or updates the device row.
     * On conflict (device_id PK), updates last_push_at and updated_at.
     */
    void upsert(UserSyncState state);
}
