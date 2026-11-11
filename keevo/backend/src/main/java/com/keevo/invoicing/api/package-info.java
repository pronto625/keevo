/**
 * Public API of the {@code invoicing} module (Story 24.4): the import port of the Bref migration tool — documents,
 * lines, payments, seller settings and old public links written as they were in Bref, without issuing, numbering
 * counter, quota nor event. Other modules depend on {@code "invoicing :: api"} only.
 *
 * <p><b>Tenant convention:</b> every method takes the tenant schema explicitly as its first parameter; it must be the
 * schema of the current tenant context ({@code TenantContext.requireCurrent}), the caller owning the transaction.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.invoicing.api;
