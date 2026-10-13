// Mirrors com.keevo.admin.revenue.adapter.in.rest.dto.* (Story 9-3).
// Amounts are XAF integers, dates ISO yyyy-MM-dd, months YYYY-MM, rates 0..1.

/** Inclusive ISO date range sent to /admin/revenue/summary. */
export interface RevenueRange {
  from: string;
  to: string;
}

export interface RevenueSummary {
  from: string;
  to: string;
  /** Today's MRR. */
  mrr: number;
  arr: number;
  mrrPreviousMonth: number;
  /** Variation vs the previous month in %, null when the previous MRR is 0. */
  mrrTrendPercent: number | null;
  payingTenants: number;
  freeTenants: number;
  trialTenants: number;
  /** Tenants currently on PREMIUM (paid or not). */
  premiumTenants: number;
  /** PREMIUM tenants without a payment covering today (legacy/gifted, not in the MRR). */
  premiumWithoutPayment: number;
  newPayingTenants: number;
  churnedTenants: number;
  /** Paying tenants on `from` (churn rate denominator). */
  churnBase: number;
  churnRate: number;
  convertedTenants: number;
  /** Trials ended in the range (conversion denominator). */
  trialsEnded: number;
  conversionRate: number;
  revenueCollected: number;
}

export interface RevenueTrendPoint {
  /** YYYY-MM */
  month: string;
  mrr: number;
  payingTenants: number;
  newPayingTenants: number;
  churnedTenants: number;
}

export interface ChurnedTenant {
  /** Business name (« Sans nom » when unnamed). */
  tenantName: string;
  /** yyyy-MM-dd */
  planEndDate: string;
  /** Months of paid tenure (1 decimal), null when no payment was recorded. */
  tenureMonths: number | null;
  /** Completed sales of the 30 days before the end of the plan (XAF). */
  lastGmv30d: number;
}

export interface ChurnReport {
  /** YYYY-MM */
  month: string;
  churnedCount: number;
  payingAtMonthStart: number;
  churnRate: number;
  tenants: ChurnedTenant[];
}
