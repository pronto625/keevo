/**
 * Revenue capability of the core (Story 20.9, published here by Story 20.14): the single revenue source
 * ({@link com.keevo.shared.capability.revenue.RevenueQueryPort}) — sales ∪ invoices − credit notes, never double
 * counted.
 *
 * <p>Implemented by {@code reporting} (it owns the union); consumers ({@code reporting}, {@code crm} clients export,
 * {@code messaging} sales-trend alert) depend on {@code shared} only.
 *
 * <p><b>Tenant convention:</b> the relations are resolved in the current tenant's search_path (inside the caller's
 * transaction).
 */
package com.keevo.shared.capability.revenue;
