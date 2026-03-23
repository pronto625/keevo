package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.model.UserSyncState;
import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncGateCheckServiceTest {

    @Mock
    private UserSyncStateRepository userSyncStateRepository;

    private SyncGateCheckService service;

    private static final String DEVICE_ID = "test-device-abc123";
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SyncGateCheckService(userSyncStateRepository);
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private UserSyncState stateWithPushAt(Instant lastPushAt) {
        return new UserSyncState(DEVICE_ID, USER_ID, "kv_test", lastPushAt, null, Instant.now());
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    void isStale_deviceNotFound_returnsFalse() {
        when(userSyncStateRepository.findByDeviceId(DEVICE_ID)).thenReturn(Optional.empty());

        assertThat(service.isStalePush(DEVICE_ID)).isFalse();
    }

    @Test
    void isStale_lastPushAtNull_returnsFalse() {
        when(userSyncStateRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.of(stateWithPushAt(null)));

        assertThat(service.isStalePush(DEVICE_ID)).isFalse();
    }

    @Test
    void isStale_deviceLastPushWithin7Days_returnsFalse() {
        Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
        when(userSyncStateRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.of(stateWithPushAt(threeDaysAgo)));

        assertThat(service.isStalePush(DEVICE_ID)).isFalse();
    }

    @Test
    void isStale_deviceLastPushAt7DayBoundary_returnsFalse() {
        // 7 days minus 1 minute → still within limit — gate must NOT fire
        // (strict rule: ONLY fires when lastPushAt is STRICTLY before now - 7 days)
        Instant justUnder7Days = Instant.now().minus(7, ChronoUnit.DAYS).plusSeconds(60);
        when(userSyncStateRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.of(stateWithPushAt(justUnder7Days)));

        assertThat(service.isStalePush(DEVICE_ID)).isFalse();
    }

    @Test
    void isStale_deviceLastPushOver7DaysAgo_returnsTrue() {
        Instant eightDaysAgo = Instant.now().minus(8, ChronoUnit.DAYS);
        when(userSyncStateRepository.findByDeviceId(DEVICE_ID))
                .thenReturn(Optional.of(stateWithPushAt(eightDaysAgo)));

        assertThat(service.isStalePush(DEVICE_ID)).isTrue();
    }
}
