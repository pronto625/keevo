package com.keevo.identity.api;

/**
 * A tenant's report / alert preferences (Stories 7.5, 8.1) as seen by other modules (Story 12.2d).
 * Same values as the onboarding preferences row; times are {@code "HH:mm:ss"} strings.
 *
 * @param weeklyReportDay 0 = Sunday … 6 = Saturday
 * @param leaderboardVisibleToEmployees Story 17.2 — the owner shares the seller leaderboard (anonymised, own store)
 *                                      with employees; owned by {@code advanced-reports}, never replaced by the
 *                                      {@code report-config} fixed defaults
 */
public record TenantPreferencesView(
        String eodReportTime,
        boolean stockAlertEnabled,
        boolean eodReportEnabled,
        ReportChannel eodReportChannel,
        boolean weeklyReportEnabled,
        int weeklyReportDay,
        String weeklyReportTime,
        ReportChannel weeklyReportChannel,
        boolean inventoryReportEnabled,
        ReportChannel inventoryReportChannel,
        StockAlertChannel stockAlertChannel,
        boolean trendNotificationEnabled,
        boolean leaderboardVisibleToEmployees
) {

    /** Pre-17.2 arity — {@code leaderboardVisibleToEmployees = false}. */
    public TenantPreferencesView(String eodReportTime, boolean stockAlertEnabled, boolean eodReportEnabled,
                                 ReportChannel eodReportChannel, boolean weeklyReportEnabled, int weeklyReportDay,
                                 String weeklyReportTime, ReportChannel weeklyReportChannel,
                                 boolean inventoryReportEnabled, ReportChannel inventoryReportChannel,
                                 StockAlertChannel stockAlertChannel, boolean trendNotificationEnabled) {
        this(eodReportTime, stockAlertEnabled, eodReportEnabled, eodReportChannel, weeklyReportEnabled,
                weeklyReportDay, weeklyReportTime, weeklyReportChannel, inventoryReportEnabled, inventoryReportChannel,
                stockAlertChannel, trendNotificationEnabled, false);
    }

    /** The same report / alert values with the given leaderboard visibility (Story 17.2). */
    public TenantPreferencesView withLeaderboardVisibleToEmployees(boolean visible) {
        return new TenantPreferencesView(eodReportTime, stockAlertEnabled, eodReportEnabled, eodReportChannel,
                weeklyReportEnabled, weeklyReportDay, weeklyReportTime, weeklyReportChannel, inventoryReportEnabled,
                inventoryReportChannel, stockAlertChannel, trendNotificationEnabled, visible);
    }

    /**
     * The fixed behaviour of a tenant without the paid {@code report-config} module (Story 17.8, FR60): the
     * {@code tenant_preferences} column defaults — end-of-day report on at 20:00 by WhatsApp, weekly report on
     * (Sunday 20:00, WhatsApp), inventory report on (WhatsApp), stock alerts on (push), trends on.
     * Times in the repository's read format ({@code LocalTime#toString}: {@code "20:00"}).
     * {@code leaderboardVisibleToEmployees} is {@code false} here: it is not a report field — consumers of the
     * effective preferences get the stored flag ({@link #withLeaderboardVisibleToEmployees}).
     */
    public static TenantPreferencesView fixedDefaults() {
        return FIXED_DEFAULTS;
    }

    private static final TenantPreferencesView FIXED_DEFAULTS = new TenantPreferencesView("20:00", true, true,
            ReportChannel.WHATSAPP, true, 0, "20:00", ReportChannel.WHATSAPP, true, ReportChannel.WHATSAPP,
            StockAlertChannel.PUSH, true);
}
