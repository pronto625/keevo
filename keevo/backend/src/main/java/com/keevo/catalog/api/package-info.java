/**
 * Public API of the {@code catalog} module (Story 12.2a).
 *
 * <p>Only query/command ports, immutable records and API enums live here. Implementations are
 * internal adapters ({@code catalog.*.adapter.in.api}) delegating to the existing use cases and
 * repositories. Other modules depend on {@code "catalog :: api"} (and {@code "catalog :: events"}),
 * never on catalog internals.
 *
 * <p>{@link com.keevo.catalog.api.InitialStockPort} works the other way round: a port
 * the catalog <em>requires</em> and the stock-owning module implements (Story 12.2b), so that the
 * CSV import seeds stock without catalog depending on inventory.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.catalog.api;
