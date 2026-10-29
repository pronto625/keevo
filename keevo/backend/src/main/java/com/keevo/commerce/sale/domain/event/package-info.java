/**
 * Sale and day-closure domain events of the {@code commerce} module, published to other modules
 * as the {@code "commerce :: events"} named interface (Story 12.2c). The module's only event
 * package: a new commerce event goes here. Payload types are JDK types or {@code commerce :: api}
 * records ({@code DayClosureSummary}, {@code ClosureWindow}). Relocation to the shared event
 * catalogue is Story 12.3.
 */
@org.springframework.modulith.NamedInterface("events")
package com.keevo.commerce.sale.domain.event;
