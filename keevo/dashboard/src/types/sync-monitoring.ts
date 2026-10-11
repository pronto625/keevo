// Mirrors com.keevo.admin.sync_monitoring.adapter.in.rest.dto.* (Story 9.8).
// Java Instant fields serialize as ISO-8601 strings and may be null.

export type SyncHealthStatus = "OK" | "WARN" | "ERROR";

export interface AdminSyncOverview {
  totalDevices: number;
  totalFailedOps7d: number;
  totalConflicts7d: number;
  tenantsInAlert: number;
  latestPushGlobally: string | null;
}

export interface AdminTenantSyncHealth {
  tenantId: string;
  tenantName: string | null;
  tenantPlan: string | null;
  failedOps7d: number;
  conflicts7d: number;
  deviceCount: number;
  lastPushAt: string | null;
  lastPullAt: string | null;
  status: SyncHealthStatus;
}

export interface AdminDeviceInfo {
  deviceId: string;
  userId: string;
  lastPushAt: string | null;
  lastPullAt: string | null;
  updatedAt: string | null;
}

export interface AdminSyncFailedOp {
  operationType: string;
  errorReason: string | null;
  processedAt: string | null;
}

export interface AdminSyncConflict {
  entityType: string;
  conflictType: string;
  strategy: string;
  resolvedAt: string | null;
}

export interface AdminSyncTenantDetail {
  tenantId: string;
  tenantName: string | null;
  tenantPlan: string | null;
  devices: AdminDeviceInfo[];
  recentFailedOps: AdminSyncFailedOp[];
  recentConflicts: AdminSyncConflict[];
}
