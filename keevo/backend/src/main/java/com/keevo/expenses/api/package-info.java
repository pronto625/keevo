/**
 * Public API of the {@code expenses} module (Story 20.11): what other modules may read of the expenses — totals, never
 * the table. Consumer today: {@code finance} (the result = revenue excl. VAT − expenses).
 *
 * <p><b>Tenant convention:</b> tenant-scoped methods take {@code String tenantSchema} first and require it to be the
 * schema of the current tenant context.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.expenses.api;
