export interface TenantListItem {
  id: string;
  code: string;
  name: string;
  ownerPhone: string;
  plan: 'FREE' | 'PAID';
  status: 'ACTIVE' | 'DELETION_PENDING' | 'SUSPENDED';
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
