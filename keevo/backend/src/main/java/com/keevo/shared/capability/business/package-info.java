/**
 * Business identity capability of the core (Story 14.2): the name and logo the tenant's business shows on its
 * documents ({@link com.keevo.shared.capability.business.CompanyIdentity}) — export PDF headers, invoices.
 *
 * <p>Implemented by {@code identity} (the tenant owns its designation and logo); consumers ({@code reporting},
 * {@code invoicing}) depend on {@code shared} only.
 *
 * <p><b>Tenant convention:</b> every tenant-scoped method takes the tenant schema explicitly as its first
 * {@code String tenantSchema} parameter.
 */
package com.keevo.shared.capability.business;
