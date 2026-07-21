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
     * Returns {@code true} if this device's last activity (push or pull) is strictly older than 7 days.
     *
     * <p>Uses {@code GREATEST(lastPushAt, lastPullAt)} so a device that pulls
     * regularly but never pushes is not permanently locked out (B-CRIT-3).
     *
     * <ul>
     *   <li>Device not found → {@code false} (first push, always allowed)</li>
     *   <li>Both lastPushAt and lastPullAt null → {@code false} (allow)</li>
     *   <li>lastActivity &lt;= 7 days ago → {@code false} (within threshold)</li>
     *   <li>lastActivity &gt; 7 days ago → {@code true} (gate fires)</li>
     * </ul>
     */
    public boolean isStalePush(String deviceId) {
        return userSyncStateRepository.findByDeviceId(deviceId)
                .map(s -> {
                    Instant lastActivity = s.lastPushAt();
                    if (s.lastPullAt() != null &&
                        (lastActivity == null || s.lastPullAt().isAfter(lastActivity))) {
                        lastActivity = s.lastPullAt();
                    }
                    return lastActivity != null &&
                           lastActivity.isBefore(Instant.now().minus(GATE_THRESHOLD_DAYS, ChronoUnit.DAYS));
                })
                .orElse(false);
    }
}
