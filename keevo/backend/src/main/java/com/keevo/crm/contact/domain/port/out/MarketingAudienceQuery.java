package com.keevo.crm.contact.domain.port.out;

import com.keevo.crm.api.MarketingRecipient;
import com.keevo.crm.api.PurchaseFilter;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Story 21.11 — the campaign audience restricted by purchase behaviour, for the current tenant: the eligible clients
 * (consent given, no STOP, not archived; one per phone; of {@code storeIds}, empty = every client) whose number's
 * purchases match a non-empty {@link PurchaseFilter}. Each method is one SQL statement.
 */
public interface MarketingAudienceQuery {

    /** At most {@code limit} recipients, ordered by phone. */
    List<MarketingRecipient> audience(Collection<UUID> storeIds, PurchaseFilter filter, int limit);

    /** The number of matching phones, counted up to {@code cap} (rows never loaded). */
    int count(Collection<UUID> storeIds, PurchaseFilter filter, int cap);
}
