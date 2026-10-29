/**
 * Public API of the {@code crm} module — clients and suppliers (Story 12.2a).
 *
 * <p>Only query/command ports and immutable records live here. Implementations are internal
 * adapters ({@code crm.contact.adapter.in.api}) delegating to the existing use cases and
 * repositories. Other modules depend on {@code "crm :: api"} (and {@code "crm :: events"}).
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.crm.api;
