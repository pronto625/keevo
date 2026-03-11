package com.keevo.catalog.contact.domain.port.out;

import java.util.UUID;

/**
 * ClientSalesStatsPort — output port for querying sales statistics linked to a client.
 *
 * <p>Isolates the contact domain from the commerce domain (sales table).
 * Implementation accesses the {@code sales} table without polluting the Client aggregate.
 */
public interface ClientSalesStatsPort {

    long countSalesByClient(UUID clientId);

    long totalSpentByClient(UUID clientId);
}
