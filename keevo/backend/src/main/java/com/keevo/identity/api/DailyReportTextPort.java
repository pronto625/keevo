package com.keevo.identity.api;

/**
 * A port identity <em>requires</em> and the reporting module implements (Story 12.2e, same pattern as
 * {@code catalog.api.InitialStockPort} / {@code subscription.api.ProductCountPort}): the WhatsApp text
 * of a store's daily report, used by the onboarding « send a test report » action.
 *
 * <p>Declared here rather than in {@code reporting.api} so that identity does not depend on reporting
 * (reporting already depends on {@code identity :: api}): no {@code identity ↔ reporting} cycle.
 * Read-only; joins the caller's transaction.
 */
public interface DailyReportTextPort {

    /**
     * Builds and formats the daily report of {@code request.storeId()} over the request window.
     *
     * @param tenantSchema must be the schema of the current tenant context (fail fast otherwise)
     */
    String dailyReportText(String tenantSchema, DailyReportTextRequest request);
}
