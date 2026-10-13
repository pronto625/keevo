// Mirrors com.keevo.admin.analytics.adapter.in.rest.dto.* (Story 9-4).
// Amounts are XAF integers, dates ISO yyyy-MM-dd, months YYYY-MM, rates 0..1.

export interface AnalyticsOverview {
  /** Distinct active users today (WAT). */
  dau: number;
  /** Distinct active users over the last 30 days. */
  mau: number;
  /** dau / mau, null when mau = 0. */
  dauMauRatio: number | null;
  totalTenants: number;
  totalStores: number;
  /** Stores with at least one completed sale in the last 30 days. */
  activeStores: number;
  totalProducts: number;
  totalCompletedSales: number;
  totalGmv: number;
}

export interface GrowthPoint {
  /** YYYY-MM */
  month: string;
  newTenants: number;
  cumulativeTenants: number;
  salesCount: number;
  gmv: number;
  mau: number;
}

export interface CountryCount {
  /** ISO country code, `OTHER` or `UNKNOWN` — labelled with `countryLabel`. */
  country: string;
  tenantCount: number;
}

export interface RetentionCohort {
  /** Monday of the signup week, yyyy-MM-dd. */
  weekStart: string;
  tenantCount: number;
  /** Index k-1 = share of the cohort active during week k after signup; null = not started yet / empty cohort. */
  retention: (number | null)[];
}

export interface RetentionReport {
  weeks: number;
  /** Most recent first. */
  cohorts: RetentionCohort[];
}

export interface TopStore {
  storeName: string;
  tenantName: string;
  gmv: number;
  transactions: number;
  averageBasket: number;
  /** Variation vs the previous period of equal length (%), null when the previous GMV is 0 (« Nouveau »). */
  growthPercent: number | null;
}

export interface TopStoresReport {
  from: string;
  to: string;
  stores: TopStore[];
}

export interface FeatureAdoption {
  /** Feature code — labelled with `featureLabel`. */
  feature: string;
  tenantCount: number;
  rate: number;
}

export interface AdoptionReport {
  activeTenants: number;
  features: FeatureAdoption[];
}

/** Inclusive ISO date range sent to /admin/analytics/top-stores. */
export interface AnalyticsRange {
  from: string;
  to: string;
}
