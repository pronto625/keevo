// Story 16.4 — super-admin « Modules » console: catalogue, per-tenant modules, packs, plan profiles, jobs.

export type ModuleState = "ENABLED" | "READ_ONLY" | "DISABLED";
export type ModuleOrigin = "PLAN" | "ADMIN" | "SYSTEM";
export type ActivationStatus = "ACTIVE" | "TRIAL";

/** One installed module (`GET /admin/modules`). */
export interface CatalogueModule {
  moduleId: string;
  displayName: string;
  category: string;
  free: boolean;
  version: string;
  requires: string[];
  /** Ships tables the console can provision for every account. */
  migrations: boolean;
}

/** One module of a tenant (`GET /admin/tenants/{id}/modules`). */
export interface TenantModule {
  moduleId: string;
  displayName: string;
  state: ModuleState;
  /** Stored status (null: no row — included by the free rule). */
  status: string | null;
  expiresAt: string | null;
  graceUntil: string | null;
  provisionedVersion: string | null;
  origin: ModuleOrigin | null;
}

export interface ModuleRef {
  moduleId: string;
  displayName: string;
}

export interface ActivationInput {
  status: ActivationStatus;
  /** yyyy-MM-dd (last covered day), or null for none. */
  expiresAt: string | null;
}

export interface ModuleActivation {
  activated: ModuleRef[];
  status: string;
  expiresAt: string | null;
  provisionedVersion: string | null;
}

export interface DeactivationOptions {
  cascade?: boolean;
  force?: boolean;
}

export interface ModuleDeactivation {
  moduleId: string;
  displayName: string;
  status: string;
  deactivated: ModuleRef[];
  forced: ModuleRef[];
}

/** One entry of a tenant's module history (French labels, no id). */
export interface ModuleHistoryEntry {
  occurredAt: string;
  action: string;
  subject: string | null;
  detail: string | null;
  actorName: string | null;
  actorPhone: string | null;
}

// ── Packs ─────────────────────────────────────────────────────────────────

export interface Bundle {
  id: string;
  name: string;
  description: string | null;
  modules: ModuleRef[];
  /** False when a module of the pack is no longer available (« Inapplicable »). */
  applicable: boolean;
}

export interface BundleInput {
  name: string;
  description: string;
  modules: string[];
}

export interface BundleActivation {
  name: string;
  activated: ModuleRef[];
  alreadyActive: ModuleRef[];
}

// ── Plan profiles ─────────────────────────────────────────────────────────

export type PlanTypeCode = "FREE" | "PREMIUM_TRIAL" | "PREMIUM";

export interface PlanProfile {
  planType: PlanTypeCode;
  label: string;
  maxStores: number | null;
  maxProducts: number | null;
  maxEmployees: number | null;
  modules: { id: string; displayName: string }[];
  /** The stored profile is missing or unreadable: built-in defaults shown. */
  fallback: boolean;
}

export interface PlanProfileInput {
  maxStores: number | null;
  maxProducts: number | null;
  maxEmployees: number | null;
  modules: string[];
}

export interface PlanProfileUpdate {
  profile: PlanProfile;
  compositionChanged: boolean;
  jobId: string | null;
}

// ── Jobs ──────────────────────────────────────────────────────────────────

export type JobKind = "MODULE_BACKFILL" | "MODULE_PROVISION_ALL" | "PROFILE_RESYNC";
export type JobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";

export interface AdminJob {
  id: string;
  kind: JobKind;
  label: string;
  params: Record<string, unknown>;
  status: JobStatus;
  processed: number;
  total: number | null;
  report: unknown;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
}

export interface TenantFailure {
  tenantCode: string;
  reason: string;
}

/** Report of a backfill or of a profile re-sync (tenants by code). */
export interface BackfillReport {
  dryRun: boolean;
  tenantsProcessed: number;
  tenantsSkipped: number;
  rowsInserted: number;
  rowsUpdated: number;
  rowsUnchanged: number;
  rowsPreserved: number;
  modulesProvisioned: number;
  tenants: { tenantCode: string; inserted: number; updated: number; provisioned: string[] }[];
  failures: TenantFailure[];
}

/** Report of a module provisioning for every tenant (tenants by code). */
export interface ProvisioningReport {
  moduleId: string;
  provisioned: string[];
  skipped: string[];
  failures: TenantFailure[];
  /** Accounts already at the module's latest version, left without any migration run. */
  upToDate: number;
}
