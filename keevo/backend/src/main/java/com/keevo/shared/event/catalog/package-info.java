/**
 * Shared event catalogue (Story 12.3, spec-socle §7.4): contracts of the events consumed outside their
 * producer module, grouped by producer domain ({@code product}, {@code sale}, {@code stock}, …).
 *
 * <p>Records only, plus the small catalogue-level value types they carry ({@code MovementKind},
 * {@code SectorKind}, {@code InventoryScopeKind}, {@code StoreKind}, {@code DayClosureSummary}), so a
 * consumer never imports a producer's internals or {@code api}. Producers map their internal types by
 * name at publication. Events used only inside their producer module stay in that module.
 *
 * <p>Lives in {@code shared} (the core-to-be); the package becomes {@code core.event.catalog} when
 * {@code core} is carved out.
 */
package com.keevo.shared.event.catalog;
