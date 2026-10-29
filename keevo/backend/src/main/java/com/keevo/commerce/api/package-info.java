/**
 * Public API of the {@code commerce} module (Story 12.2c): sales and day closures.
 *
 * <p>Only query/command ports, immutable records and API enums live here (plus the
 * {@link com.keevo.commerce.api.ClosureWindow} value object reporting builds its windows with).
 * Implementations are internal adapters ({@code commerce.sale.adapter.in.api}) delegating to the
 * existing use cases and repositories; they carry no transaction of their own. The use cases are
 * {@code @Transactional} REQUIRED: a port call joins the caller's transaction when there is one
 * (the sync push's per-op transaction) and opens one otherwise, so a sale and its stock movements,
 * or a closure, commit or roll back together. Other modules
 * depend on {@code "commerce :: api"} (and {@code "commerce :: events"}), never on commerce
 * internals.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.commerce.api;
