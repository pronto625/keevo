-- Story 17.2 — seller leaderboard shared with employees (owner setting, advanced-reports).
--
-- tenant_preferences.leaderboard_visible_to_employees: true = employees see an anonymised ranking of their own
-- store (GET /api/v1/reporting/leaderboard). Default false: every existing tenant keeps the leaderboard
-- owner-only (zero behaviour change).
-- TenantSchemaSyncService copies this column definition into every tenant schema (existing rows → false);
-- TenantSchemaProvisioner also adds it explicitly (DDL_TENANT_PREFS_MIGRATE_LEADERBOARD_VISIBILITY).
ALTER TABLE public.tenant_preferences ADD COLUMN IF NOT EXISTS leaderboard_visible_to_employees boolean NOT NULL DEFAULT false;
