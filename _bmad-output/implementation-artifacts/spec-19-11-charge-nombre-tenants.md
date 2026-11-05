---
title: '19.11 — Many tenants (R1): load measurement harness and report, notification inbox retention'
type: 'feature'
created: '2026-11-05'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: []
deferred:
  - summary: >-
      The load measurement must be replayed on staging (the AC names staging; the server is not available to this run).
    evidence: |-
      Local run on a 363-schema clone of keevo_dev (7 x N). Routed to 17-9 (staging e2e gate) in epics-refonte-modulaire.md.
    location: >-
      keevo/backend/scripts/load/measure-tenants.sh
    severity: low
---

<intent-contract>

## Intent

**Problem:** Before Bref owners arrive (epic 24) nobody has measured whether the backend boots, provisions and migrates fast enough with N tenant schemas (FUS-NFR3; N = measured tenants × 2 = 52 per the 18-1 report), and the per-tenant notification inbox grows forever, including rows of users who left the tenant (routed from 19.9).

**Approach:** The DDL part of the AC is already delivered by 16-12 (core tenant DDL under per-module Flyway, `TenantSchemaSyncService` deleted) — verify and record it. Add a repeatable measurement harness (script) that runs against a throwaway copy of a database, measure the four targets locally on a clone of `keevo_dev` (363 tenants ≈ 7 × N), write the report with an explicit decision, and add a nightly set-based notification purge per tenant.

## Boundaries & Constraints

**Always:**
- Harness `keevo/backend/scripts/load/measure-tenants.sh` (bash, documented `--help`), parameters by env/flags: source DB, throwaway DB name (default `keevo_load`), container (default `keevo_postgres`), app port (default 4600, never 4500), number of registrations to time (default 10). It:
  1. creates the throwaway DB as `CREATE DATABASE <load> TEMPLATE <source>` (refuses if the target name equals the source or `keevo_dev`/`keevo_postgres`-like prod names; drops it at the end unless `--keep`);
  2. **boot (warm)**: starts the backend on the load DB with `--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false --keevo.push.expo.enabled=false --keevo.scheduling.enabled=false`, prod-like log levels (no `dev` profile SQL/TRACE logging), measures time to `Started KeevoApplication` and the `Tenant tables: …` summary line;
  3. **one module migration on all schemas**: deletes the latest `flyway_history_<module>` row of one foundation module (default `messaging`, its latest script must be idempotent per README) in every live schema, restarts, records the upgrade duration and checked/provisioned/failed counts;
  4. **provisioning**: times `POST /api/v1/auth/register` for the configured count with throwaway phone numbers (`+2376990xxxxx` style, created only in the load DB), reports min/median/max;
  5. **backup/restore**: `pg_dump -Fc` of the load DB inside the container, then `pg_restore` into a second throwaway DB, both timed, sizes recorded; drops the restore DB;
  6. stops the app by port (`fuser -k <port>/tcp`, never `pkill`), prints a Markdown summary table (metric, value, target, OK/KO) and the schema count.
  - It checks ≥ 4 GB available memory (`free -m`) before starting the app and before the dump, waiting otherwise.
- Report `_bmad-output/planning-artifacts/research/fusion-mesures/rapport-charge-tenants-2026-11-05.md`: N and how it was derived (18-1 report), environment (host, Postgres version, `upgrade-parallelism`, pool sizes), the measured values for the 363-schema clone and the value scaled to N, verdict per target, and a « Décision » section: targets met at N ⇒ no architecture change before epic 24, with the thresholds (tenant count) at which each target would be missed by linear extrapolation and the planned levers (upgrade parallelism, connection pool, lazy per-tenant migration, shared schema for inactive FREE tenants). If a target is missed at N, the decision names the lever chosen. No personal data in the report (no phone numbers, tenant codes or names).
- Notification retention (messaging module):
  - module script `db/modules/messaging/V3__notifications_retention_index.sql`: idempotent `CREATE INDEX IF NOT EXISTS idx_notifications_read_at ON notifications (read_at) WHERE read_at IS NOT NULL`, `SET LOCAL lock_timeout='10s'`.
  - `NotificationRetentionJob` nightly (cron `keevo.notification-retention.cron`, default `0 50 3 * * *`, zone Africa/Douala, ShedLock, gated by `keevo.scheduling.enabled`), iterating live tenants (same source as `ModuleExpiryMaterializer`, status ≠ DELETED and schema present), one transaction per tenant, two set-based statements per tenant: delete rows read before `now − keevo.notification-retention.read-days` (default 90), and delete rows whose `user_id` has no ACTIVE membership in this tenant (`public.user_tenant_memberships`, NOT EXISTS). Failures caught per tenant; one summary log line (tenants, rows deleted, failures, ms). Only for tenants where the `messaging` module tables exist (skip schemas without `notifications`).
  - Port method on `NotificationInboxRepository` (+ JDBC adapter) — no SQL outside the adapter.
- Fix the stale comment `application.yml:63` (DDL now comes from module Flyway scripts).

**Never:**
- Never run the harness against `keevo_dev` itself, a production database or port 4500; never enable messaging providers; no change to the boot upgrade algorithm, pool sizes or parallelism defaults in this story (levers are decided in the report, applied by a later story if needed); no staging run here (staging needs the server — routed); no purge of unread rows by age.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Read old | notification read 100 days ago | deleted | — |
| Read recent | read 10 days ago | kept | — |
| Unread old | unread, created 400 days ago, active member | kept | — |
| Left user | user's membership in this tenant inactive (or absent) | all their rows deleted | — |
| Other tenant | same user active in tenant B, inactive in A | rows in B kept, in A deleted | — |
| Broken tenant | one schema without `notifications` / SQL error | skipped / logged, others processed | per-tenant catch |
| Harness guard | target DB = source or `keevo_dev` | refuses, exit ≠ 0 | message |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaMigrationRunner.java:34-78` -- boot upgrade (`keevo.tenant-schema.upgrade-parallelism`, default 4), summary line « Tenant tables: N checked, P provisioned, F failed, D ms ».
- `.../shared/module/application/ModuleProvisionerService.java:365-421` (`upgradeFoundation`), `:466-529` (`provisionAll`, serial), `:548-596` (`provisionActiveModules` after registration).
- `.../shared/module/adapter/out/persistence/FlywayModuleMigrations.java:79-80,294-330,368-384,449-485` -- history tables `flyway_history_<id>`, dedicated pool.
- `.../identity/auth/application/service/TenantFactory.java:96-144`, `.../shared/infrastructure/persistence/TenantSchemaProvisioner.java:123-166` -- provisioning cost.
- `_bmad-output/implementation-artifacts/spec-16-12-conversion-ddl-tenant-modules.md:104-105,151,158` -- DDL conversion done, first-boot 143 s at 343 tenants, warm 1.1 s.
- `_bmad-output/planning-artifacts/research/fusion-mesures/rapport-mesures-2026-11-03-fusion.md:13,21,29` -- N = 52.
- `keevo/backend/scripts/backup-db.sh:103-134`, `keevo/backend/docs/BACKUP_RESTORE.md:245-292` -- dump/restore commands to mirror.
- `.../messaging/notification/adapter/out/persistence/impl/JdbcNotificationInboxAdapter.java:50-61,163-169`, `.../domain/port/out/NotificationInboxRepository.java`, `src/main/resources/db/modules/messaging/V2__notifications.sql` -- inbox table (no FK on `user_id`, no `read_at` index).
- `.../shared/module/application/ModuleExpiryMaterializer.java:81-115` + `ModuleExpiryScheduler` -- live-tenant loop, per-tenant `TransactionTemplate`, ShedLock; `.../sync/sync/application/service/SyncErrorLogCleanupJob.java:37-63` -- per-schema purge.
- `.../shared/infrastructure/config/SchedulingConfig.java:19` -- `keevo.scheduling.enabled`.
- `src/main/resources/application.yml:32-35,63-70` -- Hikari, stale comment, Flyway.
- Local DB: container `keevo_postgres` (postgres 16, port 5444, user `keevo` superuser), `keevo_dev` 363 live tenants; `application-dev.yml` logs SQL/TRACE (distorts timings).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/scripts/load/measure-tenants.sh` (+ short `README.md` next to it) -- harness as above.
- `db/modules/messaging/V3__notifications_retention_index.sql` -- index.
- messaging notification port/adapter + `NotificationRetentionJob` + config keys in `application.yml`.
- `application.yml:63` comment.
- Run the harness locally (clone of `keevo_dev`, app on port 4600) and write the report with the measured numbers.
- Tests: unit test of the job (per-tenant failure isolation, summary), Postgres integration test of the two DELETEs covering every matrix row (two tenants, memberships active/inactive), migration test that V3 is idempotent; `bash -n` + a `--help`/guard test of the harness (refuses `keevo_dev`).

**Acceptance Criteria:**
- Given the harness run on the 363-schema clone, when the report is written, then each of the four targets has a measured value, a verdict at N = 52, and the report states the decision before epic 24.
- Given the story delivered, when searching `src/main` for `TenantSchemaSyncService`, then nothing is found and the report cites 16-12 for the DDL part of the AC.

## Spec Change Log

## Review Triage Log

## Design Notes

**Pattern.** Retention = Template Method job (iterate live tenants, per-tenant transaction, set-based statements, summary) reusing the `ModuleExpiryMaterializer` loop; SQL behind the inbox port (hexagonal). Harness = scripted experiment, idempotent and self-cleaning, safe by construction (throwaway DB, other port, providers off, scheduling off).

**Complexity.** Purge: 2 statements per tenant (O(tenants) round trips, the unavoidable minimum with schema-per-tenant), each index-driven: `read_at` partial index for the age purge; the membership purge is an anti-join on `public.user_tenant_memberships` (PK/unique on `(user_id, tenant_id)` — verify an index exists, add one in a public migration only if missing) over the tenant's distinct `user_id`s (`idx_notifications_user_created` prefix).

**Why local, not staging.** The AC names staging; the server is not available to this run. The clone holds 363 schemas (7 × N), so a pass locally is conservative; the same script runs on staging with `--container`/DB flags — the staging run is routed to 17-9 (staging e2e gate).

## Verification

**Commands:**
- `cd keevo/backend && mvn -q -o test -Dtest='com/keevo/messaging/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- green; then shared and architecture packages one at a time (full suite package by package before finalizing).
- `bash -n keevo/backend/scripts/load/measure-tenants.sh && keevo/backend/scripts/load/measure-tenants.sh --help` -- usage printed.
- `keevo/backend/scripts/load/measure-tenants.sh` (local clone, port 4600) -- summary table printed, throwaway DBs dropped.

**Manual checks:**
- `psql` on `keevo_dev` after a local boot: `idx_notifications_read_at` present in tenant schemas.
