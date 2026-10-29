/**
 * Events published by the reporting module — named interface {@code "reporting :: events"}
 * (Story 12.2e).
 *
 * <p>No business module consumes them yet (only {@code shared}'s audit listener reads
 * {@code DataExportedEvent}); the interface exists so a future consumer depends on
 * {@code "reporting :: events"} rather than on reporting internals.
 */
@org.springframework.modulith.NamedInterface("events")
package com.keevo.reporting.export.domain.event;
