/**
 * Public API of the {@code inventory} module (Story 12.2b): stock, transfers and counting.
 *
 * <p>Only query/command ports, immutable records and API enums live here. Implementations are
 * internal adapters ({@code inventory.*.adapter.in.api}) delegating to the existing services and
 * use cases; they carry no transaction of their own, so a port call joins the caller's
 * transaction (a sale and its stock movements stay atomic). Other modules depend on
 * {@code "inventory :: api"} (and {@code "inventory :: events"}), never on inventory internals.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.inventory.api;
