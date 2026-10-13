package com.keevo.admin.revenue.domain.model;

import java.time.YearMonth;

/**
 * One month of the 12-month revenue chart (Story 9-3). {@code mrr} / {@code payingTenants} are taken at the
 * month's last day (today for the current month).
 */
public record RevenueTrendPoint(YearMonth month, long mrr, int payingTenants, int newPayingTenants, int churnedTenants) {}
