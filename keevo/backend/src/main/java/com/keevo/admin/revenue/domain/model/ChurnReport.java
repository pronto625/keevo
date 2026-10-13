package com.keevo.admin.revenue.domain.model;

import java.time.YearMonth;
import java.util.List;

/**
 * Churn of one month (Story 9-3).
 *
 * @param payingAtMonthStart churn base: paying on the previous month's last day ∪ churned in the month
 * @param churnRate          churned / payingAtMonthStart (0..1), 0 when the denominator is 0
 */
public record ChurnReport(YearMonth month, int payingAtMonthStart, double churnRate, List<ChurnedTenant> tenants) {}
