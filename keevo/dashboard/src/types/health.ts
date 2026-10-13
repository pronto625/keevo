// Story 9-5 — « Santé système » (GET /api/v1/admin/health, GET /api/v1/admin/health/status).

/** Live platform health. A null value means « not measurable yet » (no sample, database down). */
export interface SystemHealth {
  uptimePercent30d: number | null;
  uptimeBreached: boolean;
  p50Ms: number | null;
  p95Ms: number | null;
  p99Ms: number | null;
  latencyBreached: boolean;
  errorRatePercent1h: number | null;
  errorRateBreached: boolean;
  dbActiveConnections: number | null;
  /** Pool size; the connections card is breached at 80 % of it. */
  dbMaxConnections: number | null;
  dbActiveConnectionsBreached: boolean;
  databaseOk: boolean;
  databaseBreached: boolean;
  syncRefusals1h: number | null;
  syncRefusalsBreached: boolean;
  lastSampleAt: string | null;
  checkedAt: string;
  incident: boolean;
  /** French labels of the breached metrics. */
  breachedMetrics: string[];
}

/** Cheap incident flag polled every 60 s by the admin layout. */
export interface HealthStatus {
  incident: boolean;
  /** French labels of the breached metrics. */
  breachedMetrics: string[];
}
