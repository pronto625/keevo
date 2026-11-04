package com.keevo.messaging.notification.domain.port.out;

import java.util.Collection;
import java.util.Map;

/** Story 19.9b — Expo's {@code POST /push/getReceipts}. */
public interface ExpoReceiptClient {

    /** Receipts ready for these ticket ids (at most 1000). */
    int MAX_IDS = 1000;

    /**
     * Error code of each receipt Expo returned: {@code ""} for an {@code ok} receipt, the {@code details.error} code
     * (e.g. {@code DeviceNotRegistered}) or {@code "unknown"} for an error. Ids without a receipt yet are absent.
     *
     * @throws RuntimeException when the call fails (the tickets stay for the next run)
     */
    Map<String, String> fetchReceipts(Collection<String> ticketIds);
}
