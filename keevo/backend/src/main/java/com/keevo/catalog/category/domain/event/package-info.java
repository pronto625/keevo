/**
 * Category domain events of the {@code catalog} module, part of the {@code "catalog :: events"}
 * named interface (Story 12.2a).
 *
 * <p>Each event type carries {@code @NamedInterface("events")} itself instead of this package:
 * Spring Modulith 1.4 does not merge two <em>package-level</em> named interfaces sharing a name
 * (only the first is resolved by {@code "catalog :: events"}), whereas type-level declarations are
 * merged into the package-level one of {@code catalog.product.domain.event}. A new event added here
 * must carry the annotation too. Relocation to the shared event catalogue is Story 12.3.
 */
package com.keevo.catalog.category.domain.event;
