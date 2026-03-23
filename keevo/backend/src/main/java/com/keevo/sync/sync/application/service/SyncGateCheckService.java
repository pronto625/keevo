package com.keevo.sync.sync.application.service;

import com.keevo.sync.sync.domain.port.out.UserSyncStateRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * SyncGateCheckService — decides whether a push from a given device should be blocked.
 *
 * <p>The 7-day gate fires when {@code user_sync_state.last_push_at < NOW() - 7 days}.
 *
 * <p>Boundary: exactly 7 days ago is OK (>7 days required to block).
 * A device that has never pushed is allowed (null last_push_at → first push → open).
 *
 * <p>Story 5.4 — AC10.
 */
@Service
public class SyncGateCheckService {

    static final long GATE_THRESHOLD_DAYS = 7L;

    private final UserSyncStateRepository userSyncStateRepository;

    public SyncGateCheckService(UserSyncStateRepository userSyncStateRepository) {
        this.userSyncStateRepository = userSyncStateRepository;
    }

    /**
     * Returns {@code true} if this device's last push is strictly older than 7 days.
     *
     * <ul>
     *   <li>Device not found → {@code false} (first push, always allowed)</li>
     *   <li>lastPushAt is null → {@code false} (allow)</li>
     *   <li>lastPushAt &lt;= 7 days ago → {@code false} (within threshold)</li>
     *   <li>lastPushAt &gt; 7 days ago → {@code true} (gate fires)</li>
     * </ul>
     */
    public boolean isStalePush(String deviceId) {
        return userSyncStateRepository.findByDeviceId(deviceId)
                .map(s -> s.lastPushAt() != null &&
                          s.lastPushAt().isBefore(Instant.now().minus(GATE_THRESHOLD_DAYS, ChronoUnit.DAYS)))
                .orElse(false);
    }
}
