package com.keevo.admin.analytics.domain.model;

/**
 * Share of the active tenants using a feature (Story 9-4).
 *
 * @param feature     {@code SALES | INVENTORY | TRANSFERS | WHATSAPP_REPORTS | STOCK_ALERTS | OFFLINE_SYNC}
 * @param tenantCount active tenants that used it in the window
 * @param rate        tenantCount / active tenants (0..1), 0 when there is no active tenant
 */
public record FeatureAdoption(String feature, long tenantCount, double rate) {}
