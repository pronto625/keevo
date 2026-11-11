package com.keevo.crm.api;

import java.util.Collection;
import java.util.List;
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

    /**
     * Story 21.6 — the audience of a marketing campaign: the eligible clients (same rule as {@link #marketingEligible})
     * of the given stores ({@code storeIds} empty = every client, legacy ones without a store included), one per phone
     * number (the most recently updated record wins), at most {@code limit}, ordered by phone. One query on the partial
     * index {@code idx_clients_marketing_audience}.
     */
    List<MarketingRecipient> marketingAudience(String tenantSchema, Collection<UUID> storeIds, int limit);

    /**
     * Story 21.6 — the size of {@link #marketingAudience} (distinct phones), counted up to {@code cap} without loading
     * the rows. One query on {@code idx_clients_marketing_audience}.
     */
    int countMarketingAudience(String tenantSchema, Collection<UUID> storeIds, int cap);
}
