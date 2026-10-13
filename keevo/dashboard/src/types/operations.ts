// Mirrors com.keevo.admin.operations.adapter.in.rest.dto.* (Story 9.7).
// Java Instant fields serialize as ISO-8601 strings and may be null; amounts are XAF.

export type Period = "TODAY" | "7D" | "30D" | "90D" | "CUSTOM";

/** Period sent to the backend; `from`/`to` (inclusive ISO dates, yyyy-MM-dd) only for CUSTOM. */
export interface PeriodParams {
  period: Period;
  from?: string;
  to?: string;
}

export interface AdminOperationsOverview {
  totalGmv: number;
  totalOrders: number;
  activeTenantsCount: number;
  newTenantsCount: number;
  periodLabel: string;
}

export interface GmvTrendPoint {
  /** ISO date (yyyy-MM-dd), one per Africa/Douala day. */
  date: string;
  gmv: number;
  orderCount: number;
}

export interface TenantActivityRow {
  tenantId: string;
  tenantName: string | null;
  plan: string | null;
  gmv: number;
  orderCount: number;
  lastActivityAt: string | null;
  storeCount: number;
}

export interface AdminTenantActivityPage {
  content: TenantActivityRow[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface InactiveTenant {
  tenantId: string;
  tenantName: string | null;
  plan: string | null;
  lastActivityAt: string | null;
  daysSinceLastActivity: number | null;
}
