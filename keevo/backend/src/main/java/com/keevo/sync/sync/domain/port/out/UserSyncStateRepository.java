package com.keevo.sync.sync.domain.port.out;

import com.keevo.sync.sync.domain.model.UserSyncState;

import java.util.Optional;
import java.util.UUID;

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
     * On conflict (device_id PK), updates last_push_at and updated_at — only when the existing
     * row belongs to the same user (Story 12.12 guarded ON CONFLICT).
     *
     * @return {@code true} if a row was inserted/updated, {@code false} if the device is owned
     *         by another user (nothing written)
     */
    boolean upsert(UserSyncState state);

    /**
     * Inserts or updates the device row on pull.
     * On conflict (device_id PK), updates last_pull_at and updated_at (does not touch last_push_at)
     * — only when the existing row belongs to the same user (Story 12.12).
     *
     * @return {@code true} if a row was written, {@code false} if owned by another user
     */
    boolean upsertOnPull(UserSyncState state);

    /**
     * Story 16.13 — remembers the scope of the device's last completed pull ({@code restricted} = employee;
     * {@code storeId} null = unrestricted or employee without a store) and marks it known. Only on the device's own row
     * ({@code device_id} and {@code user_id} both match); nothing written otherwise.
     *
     * @return {@code true} if the row was updated
     */
    boolean rememberPullScope(String deviceId, UUID userId, boolean restricted, UUID storeId);

    /** Story 20.2 — same, with the fingerprint of the pull's client visible stores ({@code null} = none). */
    boolean rememberPullScope(String deviceId, UUID userId, boolean restricted, UUID storeId, String clientStores);

    /**
     * Returns all device sync states for the given tenant.
     * Story 5.5 — AC4.
     */
    java.util.List<UserSyncState> findAllByTenantId(String tenantId);
}
