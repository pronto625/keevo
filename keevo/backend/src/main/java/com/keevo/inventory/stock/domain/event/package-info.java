/**
 * Stock domain events of the {@code inventory} module, published to other modules as the
 * {@code "inventory :: events"} named interface (Story 12.2b).
 *
 * <p>The transfer ({@code inventory.transfer.domain.event}) and counting
 * ({@code inventory.counting.domain.event}) events, and {@code MovementType} (carried by
 * {@link com.keevo.inventory.stock.domain.event.StockAdjustedEvent}), join this interface through a
 * type-level {@code @NamedInterface("events")}: Spring Modulith 1.4 does not merge two
 * <em>package-level</em> named interfaces sharing a name (see {@code catalog.category.domain.event}).
 * A new event added in those packages must carry the annotation too. Relocation to the shared
 * event catalogue is Story 12.3.
 */
@org.springframework.modulith.NamedInterface("events")
package com.keevo.inventory.stock.domain.event;
