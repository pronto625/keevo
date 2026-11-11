---
title: '19.11 — Many tenants (R1): load measurement harness and report, notification inbox retention'
type: 'feature'
created: '2026-11-05'
status: 'done'
baseline_revision: 'f11862aee3bd993cf43f32b7a35deb23139891bd'
review_loop_iteration: 0
followup_review_recommended: true
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
  - summary: >-
      Messaging V3 ships while the parallel boot upgrade still fails a share of tenants from the packaged jar.
    evidence: |-
      Pre-existing 16-12 defect measured by this story; 16-12 reopened, fix required before any deploy (stopgap: upgrade-parallelism 1).
    location: >-
      keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/FlywayModuleMigrations.java
    severity: medium
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

### 2026-11-06 — Review pass
- verdicts: 31 findings — high 0, medium 5, low 23, false 3, maybe-false 0 (intent-alignment observations included)
- findings:
  - Patterns/complexity (FUS-NFR11):
    - `[medium]` `[patch]` `hasInbox` = one extra `pg_tables` query per tenant — one query before the loop returning the set of schemas that have `notifications`, membership test per tenant.
    - `[low]` `[patch]` departed-user DELETE is a sequential scan of each inbox (no selective predicate), Javadoc says index-driven — Javadoc states the per-tenant scan (inboxes are small; event-driven deletion not needed).
    - `[false]` `[reject]` unread rows never purged — spec « Never: no purge of unread rows by age ».
    - `[low]` `[reject]` deletes not batched — inbox tables are new (19.9) and small; first run cheap.
    - `[low]` `[patch]` `describeRoot` loops forever on a cause cycle longer than one — depth limit (32).
  - Intent alignment (descriptive):
    - `[false]` `[reject]` DDL part credited to 16-12 with no code here — the story AC is met by 16-12; the report cites it.
    - `[low]` `[defer]` local clone instead of staging — already routed to 17-9 (frontmatter `deferred`).
    - `[low]` `[reject]` reliability failure kept out of the AC2 « architecture decision » — the report's « Décision » makes the fix mandatory before any deploy and routes it to 16-12 (reopened).
    - `[low]` `[reject]` removed users purged by the nightly batch, not at removal time — the routed item asks for the multi-tenant job; nightly suffices for an inbox.
  - Blind hunter:
    - `[medium]` `[defer]` this diff adds a module script (messaging V3) while the parallel upgrade fails ~6 % of tenants from the packaged jar — pre-existing 16-12 defect; 16-12 reopened as required before any deploy; nothing is deployed without the user's request.
    - `[medium]` `[patch]` root-cause log line may carry PostgreSQL `Detail:` (row values, e.g. phone numbers) — keep only the first line of the root message (drop `Detail:`/`Where:`), plus SQLState when present.
    - `[low]` `[patch]` « index-driven » claim — grouped with the patterns entry.
    - `[low]` `[reject]` an inactive (suspended) member loses their inbox — spec rule « no ACTIVE membership »; notifications are ephemeral.
    - `[low]` `[reject]` V3 `CREATE INDEX` not CONCURRENTLY — small tables, `lock_timeout` 10 s, retried on failure.
    - `[low]` `[patch]` harness passes the DB password on the java command line (`ps`) — pass it through `SPRING_DATASOURCE_PASSWORD`.
    - `[low]` `[patch]` `--source-db` not protected — refuse a source name containing `prod`.
    - `[low]` `[patch]` restore failures hidden — verdict KO when `pg_restore` reports errors; record the failing chunk.
    - `[low]` `[patch]` backup threshold computed from local settings — report states it uses the local 64 × 100 and must be recomputed with production's `max_locks_per_transaction`, `max_connections`, `max_prepared_transactions`.
    - `[low]` `[reject]` no Spring-wiring test of the job, no log-line test, routing files outside the diff, hard-coded foundation list in the harness — wiring covered by context boot in package tests; routing is in the spec/sprint-status commit; the harness list is checked against the app's `Tenant tables` summary at run time.
  - Verification gap:
    - `[low]` `[patch]` guard test never runs in CI — add a step to `.github/workflows/backend-ci.yml` next to the backup-alert test (+ shellcheck of the two new scripts).
    - `[low]` `[patch]` only one of six `ModuleProvisioningException` sites passes the cause — pass it at the other five.
  - Edge case hunter:
    - `[low]` `[reject]` legacy rows with NULL `user_id` deleted as departed — such rows are unreachable (inbox reads filter on `user_id`); deleting them loses nothing.
    - `[false]` `[reject]` owner without a membership row loses their inbox — recipients come from active memberships only (`findOwnersByTenantSchemaName`), so no row exists for them (38 dev tenants have no active OWNER membership — a pre-existing data state, they receive no notifications at all).
    - `[low]` `[patch]` cause cycle — grouped with the patterns entry.
    - `[low]` `[reject]` truncation inside a surrogate pair — cosmetic.
    - `[medium]` `[patch]` harness verdict for the migration is duration-only (prints OK with failed tenants) — KO when `migration_failed > 0` (and on boot steps).
    - `[low]` `[reject]` `free` missing — every target host has procps.
    - `[low]` `[reject]` race between the connection check and `CREATE DATABASE TEMPLATE` — psql error is explicit; rerun.
    - `[low]` `[reject]` purge run longer than `lockAtMostFor` 2 h — seconds per tenant; thousands of tenants still fit.
    - `[low]` `[reject]` AC grep wording vs comments in public migrations — `src/main/java` is clean; comments in historical migrations are expected.
    - `[medium]` `[patch]` staging rerun would report migration OK despite failures — grouped with the harness verdict entry.

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

## Auto Run Result

Status: done

**Summary.** The DDL part of the AC was already delivered by 16-12 (core tenant DDL under per-module Flyway, `TenantSchemaSyncService` gone from `src/main/java`) and is cited by the report. New repeatable harness `scripts/load/measure-tenants.sh` (throwaway clone, port 4600, providers and scheduling off, memory guard, guards against `keevo_dev`/prod names, verdict KO on any failed tenant or dump/restore error, chunked dump fallback) with a CI guard test. Local measurement on a 363-schema clone of `keevo_dev` (≈ 7 × N = 52) and report `planning-artifacts/research/fusion-mesures/rapport-charge-tenants-2026-11-05.md`: boot 10–17 s, tenant provisioning ≈ 3 s, one module migration on all schemas 2–6 s, chunked dump 13.8 s, restore 883 s (≈ 2 min at N) — timing targets met at N, no architecture change before epic 24. Two defects found and routed: the parallel boot upgrade fails ~6 % of tenants from the packaged jar (concurrent jar reads; 0 failures at parallelism 1) → 16-12 reopened, required before any deploy; a single `pg_dump` breaks around 110 tenants with local lock settings → 10-8. Notification retention: messaging V3 partial index on `read_at`, nightly `NotificationRetentionJob` (03:50 Douala, ShedLock) deleting per tenant, set-based, read rows older than 90 days and rows of users without an active membership. Boot-upgrade failures now log their root cause (first line only, SQLState, no row values).

**Files.** New: `db/modules/messaging/V3__notifications_retention_index.sql`, `NotificationRetentionJob`, `scripts/load/measure-tenants.sh` + `README.md`, `scripts/tests/measure-tenants-guard-test.sh`, tests (`NotificationRetentionJobTest`, `NotificationRetentionPostgresTest`, `ModuleProvisioningExceptionTest`), the report. Changed: `NotificationInboxRepository` + `JdbcNotificationInboxAdapter` (`schemasWithInbox`, `purge`), `ModuleProvisioningException` (+cause, `describeRoot`), `FlywayModuleMigrations`, `ModuleProvisionerService` (root cause in the log line), `application.yml` (retention keys, stale comment), `.env.example`, `.github/workflows/backend-ci.yml`, tenant-shape fixture, `FoundationMigrationsPostgresIntegrationTest`, `SchedulerLockCoverageTest`.

**Review.** 31 findings (5 layers): 0 high, 5 medium, 23 low, 3 false. Patched: 3 medium entries (one catalogue query instead of one per tenant; root-cause log limited to the first line; harness verdict KO on failed tenants) and 10 low (cause-cycle bound, causes passed at every site, honest scan wording, password via env, prod-like source refused, restore failures visible, CI step, report caveat on lock settings). Deferred: 2 (staging replay → 17-9; V3 shipped while the parallel-upgrade defect is open → 16-12, already routed). Rejected: 16 (reasons in the triage log). Full-suite fix: `SchedulerLockCoverageTest` lists the new locked job.

**Follow-up review: recommended (true)** — 3 medium patches; unverified risk: the root-cause log line in production (first line of PostgreSQL messages only, checked by unit test, never seen on a real failure after the patch), and the reopened 16-12 defect that blocks any deploy.

**Verification.** Full suite package by package: 5 887 tests, 0 failures (after registering the job in `SchedulerLockCoverageTest`). Guard test 13/13. Harness runs 1–5 on the clone (results in the report; run 4 interrupted at its last step by an edit of the script, cleanup ran). Incident: during implementation the `--help` text of the first harness version executed `mvn spring-boot:run` once (≈ 2 min, port 4500, `keevo_dev`, dev profile): it applied messaging V3 to `keevo_dev`; log checked — no outgoing message of any kind, no outstanding publication; fixed (quoted here-doc, stubbed guard test).

**Mobile.** No app change needed: read notifications older than 90 days and notifications of people no longer in the company disappear from `GET /api/v1/notifications`.

### Deferred items routed 2026-11-10
- [ ] [Deferred-from: revue 19-6b, 2026-11-10] Retention of `public.membership_invitations` rows that will never become memberships (DECLINED, CANCELLED, expired PENDING keep the typed phone/username and names of people who refused) [keevo/backend/src/main/resources/db/migration/V74__membership_invitations.sql] — add a set-based purge (e.g. answered or expired > 90 days, one DELETE per run) to the platform retention job.
