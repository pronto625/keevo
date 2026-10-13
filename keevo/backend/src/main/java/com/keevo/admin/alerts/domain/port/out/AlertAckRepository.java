package com.keevo.admin.alerts.domain.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** AlertAckRepository — {@code public.admin_alert_acks} (Story 9-5). */
public interface AlertAckRepository {

    /** Handled time of each of {@code keys} that was marked handled. */
    Map<String, Instant> handledAt(Collection<String> keys);

    /** Stores the ack; an existing ack is kept (first handler wins). */
    void markHandled(String key, Instant handledAt, UUID handledBy);

    /** Deletes the acks of {@code keys} (condition cleared: a future occurrence alerts again). */
    int delete(Collection<String> keys);

    /** @return number of acks handled before {@code cutoff} deleted */
    int purgeBefore(Instant cutoff);
}
