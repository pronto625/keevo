package com.keevo.messaging.notification.domain.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Story 20.14 — hourly activity of the current tenant's stores, read from the single revenue source (sales and
 * invoices of each store; credit notes are not transactions). Feeds the sales-trend alert (Story 8.1).
 */
public interface StoreActivityRepository {

    /**
     * Per store with any activity in the four windows: the transactions of the hour ending at {@code hourEnd}, and
     * the average of the same hour over the three previous days. A store absent from the map had none.
     *
     * @param hourEnd the end of the hour compared (a full hour, exclusive)
     */
    Map<UUID, HourlyActivity> lastHour(Instant hourEnd);

    /** @param current transactions of the hour; @param previousDaysAverage average of the same hour, 3 days before */
    record HourlyActivity(int current, double previousDaysAverage) {
        public static final HourlyActivity NONE = new HourlyActivity(0, 0.0);
    }
}
