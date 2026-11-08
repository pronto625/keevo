/**
 * Public API of the {@code expenses} module (Story 20.11): what other modules may read of the expenses — totals, never
 * the table. Consumer today: {@code finance} (the result = revenue excl. VAT − expenses). Story 20.13 — the French
 * labels of the expense codes ({@link com.keevo.expenses.api.ExpenseLabels}), read by the super-admin cross-account
 * expense list.
 *
 * <p><b>Tenant convention:</b> tenant-scoped methods take {@code String tenantSchema} first and require it to be the
 * schema of the current tenant context.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.expenses.api;
