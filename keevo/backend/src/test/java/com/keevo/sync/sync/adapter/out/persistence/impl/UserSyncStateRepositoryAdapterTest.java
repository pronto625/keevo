package com.keevo.sync.sync.adapter.out.persistence.impl;

import com.keevo.sync.sync.domain.model.UserSyncState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserSyncStateRepositoryAdapterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private UserSyncStateRepositoryAdapter adapter;

    private static final String DEVICE_ID = "device-abc123";
    private static final UUID USER_ID   = UUID.randomUUID();
    private static final String TENANT_ID = "kv_test";

    @BeforeEach
    void setUp() {
        adapter = new UserSyncStateRepositoryAdapter(jdbcTemplate);
    }

    // ── findByDeviceId ───────────────────────────────────────────────────────

    @Test
    void findByDeviceId_notFound_returnsEmpty() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(DEVICE_ID)))
                .thenReturn(List.of());

        assertThat(adapter.findByDeviceId(DEVICE_ID)).isEmpty();
    }

    @Test
    void findByDeviceId_found_returnsDomainObject() {
        Instant lastPushAt = Instant.now().minusSeconds(3600);
        UserSyncState expected = new UserSyncState(
                DEVICE_ID, USER_ID, TENANT_ID, lastPushAt, null, Instant.now());

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(DEVICE_ID)))
                .thenReturn(List.of(expected));

        Optional<UserSyncState> result = adapter.findByDeviceId(DEVICE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().deviceId()).isEqualTo(DEVICE_ID);
        assertThat(result.get().userId()).isEqualTo(USER_ID);
        assertThat(result.get().lastPushAt()).isEqualTo(lastPushAt);
    }

    // ── upsert ───────────────────────────────────────────────────────────────

    @Test
    void upsert_callsJdbcWithUpsertSql() {
        UserSyncState state = new UserSyncState(
                DEVICE_ID, USER_ID, TENANT_ID, Instant.now(), null, Instant.now());

        adapter.upsert(state);

        verify(jdbcTemplate).update(
                contains("INSERT INTO public.user_sync_state"),
                eq(state.deviceId()),
                eq(state.userId()),
                eq(state.tenantId()));
    }

    @Test
    void upsert_sqlContainsOnConflict() {
        UserSyncState state = new UserSyncState(
                DEVICE_ID, USER_ID, TENANT_ID, Instant.now(), null, Instant.now());

        adapter.upsert(state);

        verify(jdbcTemplate).update(contains("ON CONFLICT"), any(), any(), any());
    }

    // ── findAllByTenantId ────────────────────────────────────────────────────

    @Test
    void findAllByTenantId_returnsMatchingDevices() {
        UserSyncState device1 = new UserSyncState(
                "device-1", USER_ID, TENANT_ID, Instant.now(), null, Instant.now());
        UserSyncState device2 = new UserSyncState(
                "device-2", UUID.randomUUID(), TENANT_ID, Instant.now(), null, Instant.now());

        when(jdbcTemplate.query(contains("WHERE tenant_id"), any(RowMapper.class), eq(TENANT_ID)))
                .thenReturn(List.of(device1, device2));

        List<UserSyncState> result = adapter.findAllByTenantId(TENANT_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).deviceId()).isEqualTo("device-1");
        assertThat(result.get(1).deviceId()).isEqualTo("device-2");
    }

    @Test
    void findAllByTenantId_emptyWhenNoDevices() {
        when(jdbcTemplate.query(contains("WHERE tenant_id"), any(RowMapper.class), eq("kv_empty")))
                .thenReturn(List.of());

        List<UserSyncState> result = adapter.findAllByTenantId("kv_empty");

        assertThat(result).isEmpty();
    }
}
