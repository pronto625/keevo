/**
 * Counting domain events of the {@code inventory} module, part of the {@code "inventory :: events"}
 * named interface (Story 12.2b).
 *
 * <p>Each event type carries {@code @NamedInterface("events")} itself instead of this package:
 * Spring Modulith 1.4 does not merge two <em>package-level</em> named interfaces sharing a name,
 * whereas type-level declarations are merged into the package-level one of
 * {@code inventory.stock.domain.event}. A new event added here must carry the annotation too.
 * Relocation to the shared event catalogue is Story 12.3.
 */
package com.keevo.inventory.counting.domain.event;
