export interface TenantListItem {
  id: string;
  code: string;
  name: string;
  ownerPhone: string;
  /** Plan code (labelled with planLabel). */
  plan: PlanType;
  status: TenantStatus;
  registeredAt: string;
  lastActivityAt: string | null;
  storeCount: number;
  employeeCount: number;
  deletionScheduledAt: string | null;
}

export interface TenantPage {
  items: TenantListItem[];
  totalCount: number;
  page: number;
  pageSize: number;
}

export interface TenantFilters {
  search: string;
  plan: 'ALL' | 'FREE' | 'PAID';
  status: 'ALL' | 'ACTIVE' | 'DELETION_PENDING' | 'SUSPENDED';
  registeredFrom: string | null;
  registeredTo: string | null;
  lastActivityFrom: string | null;
  lastActivityTo: string | null;
}

export interface TenantDetail {
  tenant: TenantListItem;
  stores: StoreInfo[];
  employees: EmployeeInfo[];
  auditLog: AuditPage;
  /** Story 9-2 — current subscription read from the tenant schema; null when unreadable. */
  subscription: TenantSubscription | null;
}

// ── Story 9-2 — plan & payments ───────────────────────────────────────────────

export type PlanType = "FREE" | "PREMIUM_TRIAL" | "PREMIUM";

export type TenantStatus = "ACTIVE" | "DELETION_PENDING" | "SUSPENDED" | "DELETED";

export type PaymentMethod = "MOBILE_MONEY" | "CASH" | "BANK_TRANSFER" | "OTHER";

export interface TenantSubscription {
  planType: PlanType;
  expiresAt: string | null;
}

/** POST /admin/tenants/{id}/plan/premium body. `expiresAt` is a yyyy-MM-dd date. */
export interface ActivatePremiumInput {
  expiresAt: string;
  amountXaf: number;
  method: PaymentMethod;
  /** yyyy-MM-dd payment date; omitted → now. */
  paidAt?: string;
  note?: string;
}

export interface PlanState {
  planType: PlanType;
  expiresAt: string | null;
}

export interface TenantPayment {
  paidAt: string;
  amountXaf: number;
  method: PaymentMethod;
  /** yyyy-MM-dd */
  periodStart: string;
  /** yyyy-MM-dd */
  periodEnd: string;
  note: string | null;
  recordedByLabel: string;
}

export interface PlanAlert {
  /** Only used to open the tenant drawer — never displayed. */
  tenantId: string;
  tenantName: string | null;
  kind: "EXPIRING" | "EXPIRED";
  plan: PlanType | null;
  daysLeft: number | null;
  expiresAt: string | null;
  occurredAt: string | null;
}

export interface StoreInfo {
  id: string;
  name: string;
  type: string;
  totalRevenue: number;
}

export interface EmployeeInfo {
  id: string;
  name: string;
  role: string;
  lastLoginAt: string | null;
}

export interface AuditEvent {
  id: string;
  eventType: string;
  actorId: string;
  occurredAt: string;
  details: string;
}

export interface AuditPage {
  events: AuditEvent[];
  total: number;
  page: number;
}
