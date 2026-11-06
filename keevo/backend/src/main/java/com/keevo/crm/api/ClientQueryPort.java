package com.keevo.crm.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Read access to the crm's clients for other modules. */
public interface ClientQueryPort {

    boolean existsById(String tenantSchema, UUID clientId);

    /**
     * Story 20.2 — for campaigns (21.6): the clients among {@code clientIds} that may receive marketing messages —
     * consent given by the team, no STOP from the client, not archived. A withdrawal makes a client ineligible from
     * the moment it commits. One query; unknown ids are ignored.
     */
    Set<UUID> marketingEligible(String tenantSchema, Collection<UUID> clientIds);
}
