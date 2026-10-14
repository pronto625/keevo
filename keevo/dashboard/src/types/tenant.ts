export interface TenantListItem {
  /** Only used to open the drawer / call actions — never displayed. */
  id: string;
  name: string;
  /** Owner's first + last name, else « Propriétaire ». */
  ownerName: string;
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
  /** `PAID` (= trial + Premium) is still accepted by the backend for old links. */
  plan: 'ALL' | 'FREE' | 'PREMIUM_TRIAL' | 'PREMIUM' | 'PAID';
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
  /** Story 9-2 — current suspension (date + reason); null when not suspended. */
  suspension?: TenantSuspension | null;
}

// ── Story 9-2 — plan & payments ───────────────────────────────────────────────

export type PlanType = "FREE" | "PREMIUM_TRIAL" | "PREMIUM";

export type TenantStatus = "ACTIVE" | "DELETION_PENDING" | "SUSPENDED" | "DELETED";

export type PaymentMethod = "MOBILE_MONEY" | "CASH" | "BANK_TRANSFER" | "OTHER";

export interface TenantSubscription {
  planType: PlanType;
  expiresAt: string | null;
}

export interface TenantSuspension {
  suspendedAt: string;
  reason: string | null;
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
  /** Only the target of « Annuler ce paiement » — never displayed. */
  id: string;
  paidAt: string;
  amountXaf: number;
  method: PaymentMethod;
  /** yyyy-MM-dd */
  periodStart: string;
  /** yyyy-MM-dd */
  periodEnd: string;
  note: string | null;
  recordedByLabel: string;
  /** Set when the payment was voided (kept in the history, excluded from every count). */
  voidedAt?: string | null;
  voidReason?: string | null;
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
  /** Employee name, « Propriétaire » or « Employé » — no user id. */
  name: string;
  /** Role code (labelled with userRoleLabel). */
  role: string;
  /** Last active day (yyyy-MM-dd) from the daily activity log; null = never. */
  lastActivityAt: string | null;
}

/** One readable audit line — no entity id, no actor id. */
export interface AuditEvent {
  /** Action code (labelled with auditActionLabel). */
  action: string;
  /** Audited object type (labelled with auditEntityTypeLabel). */
  entityType: string;
  entityLabel: string | null;
  /** Employee name, « Propriétaire », « Système » or « Utilisateur inconnu ». */
  actorName: string;
  storeName: string | null;
  /** Null for a malformed legacy row. */
  occurredAt: string | null;
}

export interface AuditPage {
  events: AuditEvent[];
  total: number;
  page: number;
}
