package com.keevo.catalog.stock.domain.port.in;

import java.util.UUID;

/**
 * GetCrossStoreAvailabilityQuery — input record for the cross-store availability use case.
 *
 * <p>GoF: Query Object. The use case is driven purely by this immutable record,
 * making it MCP-ready (plain Java, no framework dependency).
 *
 * Story 3.4.
 */
public record GetCrossStoreAvailabilityQuery(UUID productId) {}
