package com.keevo.messaging.notification.domain.port.out;

import com.keevo.messaging.notification.domain.model.ExpoPushTicket;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Story 19.9b — {@code public.expo_push_tickets}: Expo tickets waiting for their receipt. */
public interface ExpoPushTicketRepository {

    /**
     * Stores the accepted tickets of one send ({@code ticketId → token}) in one batch insert; a ticket id already
     * stored is ignored. A schema that is not a tenant schema stores nothing.
     */
    void saveAll(String tenantSchema, Map<String, String> tokenByTicketId);

    /**
     * At most {@code limit} tickets created before {@code before}, oldest first, strictly after the keyset cursor
     * ({@code afterCreatedAt}, {@code afterTicketId}); both null = from the start.
     */
    List<ExpoPushTicket> findCreatedBefore(Instant before, Instant afterCreatedAt, String afterTicketId, int limit);

    /** Deletes these tickets in one statement; the number deleted. */
    int deleteByIds(Collection<String> ticketIds);

    /** Deletes every ticket created before {@code before} in one statement; the number deleted. */
    int purgeCreatedBefore(Instant before);
}
