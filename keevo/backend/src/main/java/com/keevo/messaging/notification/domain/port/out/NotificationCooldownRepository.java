package com.keevo.messaging.notification.domain.port.out;

import java.time.Duration;
import java.util.UUID;

/**
 * NotificationCooldownRepository — Port for managing notification cooldowns.
 *
 * <p>Prevents notification spam by tracking when the last notification of a given
 * type was sent for a specific product/store combination.
 *
 * <p>Story 8.1 — Stock Alert & Trend Notifications.
 */
public interface NotificationCooldownRepository {

    /**
     * Check if an active cooldown exists for the given type/product/store combination.
     *
     * @param cooldownType e.g. "STOCK_ALERT", "TREND_UP", "TREND_DOWN"
     * @param productId    product UUID (nullable for trend cooldowns)
     * @param storeId      store UUID
     * @param window       cooldown duration window
     * @return true if a cooldown was sent within the window
     */
    boolean existsActiveCooldown(String cooldownType, UUID productId, UUID storeId, Duration window);

    /**
     * Insert or update (upsert) a cooldown entry, setting last_sent_at to now.
     *
     * @param cooldownType e.g. "STOCK_ALERT", "TREND_UP", "TREND_DOWN"
     * @param productId    product UUID (nullable for trend cooldowns)
     * @param storeId      store UUID
     */
    void upsertCooldown(String cooldownType, UUID productId, UUID storeId);
}
