package com.keevo.admin.revenue.domain.model;

import java.time.LocalDate;

/**
 * Revenue KPIs (Story 9-3). MRR / ARR / paying / free / trial are « aujourd'hui »; the other figures
 * cover the selected range {@code from..to} (inclusive WAT dates).
 *
 * @param mrr                current MRR (XAF)
 * @param arr                MRR × 12
 * @param mrrPreviousMonth   MRR at the last day of the previous month
 * @param mrrTrendPercent    (mrr − previous) / previous × 100, 1 decimal; null when previous is 0
 * @param payingTenants      tenants counted in the current MRR
 * @param freeTenants        tenants currently on the FREE plan (tenant schemas)
 * @param trialTenants       tenants currently on PREMIUM_TRIAL (tenant schemas)
 * @param premiumTenants     tenants currently on PREMIUM (tenant schemas), paid or not
 * @param premiumWithoutPayment PREMIUM tenants with no payment counted today (legacy/gifted: not in the MRR)
 * @param newPayingTenants   tenants whose first-ever payment was made in the range
 * @param churnedTenants     tenants lost in the range (PREMIUM → FREE without return in the range)
 * @param churnBase          paying the day before {@code from} ∪ churned in the range (rate denominator)
 * @param churnRate          churned / churnBase (0..1), 0 when churnBase is 0
 * @param convertedTenants   tenants with a trial history whose first PREMIUM activation is in the range
 * @param trialsEnded        tenants whose trial ended (expiry or first activation) in the range
 * @param conversionRate     converted / trialsEnded, 0 when trialsEnded is 0
 * @param revenueCollected   sum of the payments made in the range (XAF)
 */
public record RevenueSummary(
        LocalDate from,
        LocalDate to,
        long mrr,
        long arr,
        long mrrPreviousMonth,
        Double mrrTrendPercent,
        int payingTenants,
        int freeTenants,
        int trialTenants,
        int premiumTenants,
        int premiumWithoutPayment,
        int newPayingTenants,
        int churnedTenants,
        int churnBase,
        double churnRate,
        int convertedTenants,
        int trialsEnded,
        double conversionRate,
        long revenueCollected
) {}
