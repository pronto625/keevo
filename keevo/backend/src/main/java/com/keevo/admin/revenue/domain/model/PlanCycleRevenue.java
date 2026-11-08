package com.keevo.admin.revenue.domain.model;

/**
 * Story 21.10 — revenue of one plan × billing cycle (FR79 split by plan).
 *
 * @param planCode           plan paid for, {@code null} = « Plan non précisé » (legacy payment)
 * @param planLabel          French name of the plan
 * @param cycleCode          cycle paid for, {@code null} = « Durée libre »
 * @param cycleLabel         French name of the cycle
 * @param mrr                monthly recurring revenue today of the payments of this plan × cycle (XAF)
 * @param payingTenants      companies whose payment counted today is of this plan × cycle
 * @param revenueCollected   payments made in the range for this plan × cycle (XAF)
 * @param paymentsCollected  number of those payments
 */
public record PlanCycleRevenue(String planCode, String planLabel, String cycleCode, String cycleLabel, long mrr,
                               int payingTenants, long revenueCollected, int paymentsCollected) {
}
