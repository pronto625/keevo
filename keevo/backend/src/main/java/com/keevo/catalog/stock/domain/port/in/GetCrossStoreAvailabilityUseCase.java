package com.keevo.catalog.stock.domain.port.in;

import com.keevo.catalog.product.domain.entity.Product;
import com.keevo.catalog.stock.domain.model.CrossStoreAvailabilityEntry;

import java.util.List;

/**
 * GetCrossStoreAvailabilityUseCase — in port for cross-store product availability.
 *
 * <p>Carries both product context and per-store entries so the caller (controller/
 * Flutter client) can build the response without an extra product lookup.
 *
 * Story 3.4.
 */
public interface GetCrossStoreAvailabilityUseCase {

    /** Result carrying the product context + per-store availability entries. */
    record Result(Product product, List<CrossStoreAvailabilityEntry> entries) {}

    Result execute(GetCrossStoreAvailabilityQuery query);
}
