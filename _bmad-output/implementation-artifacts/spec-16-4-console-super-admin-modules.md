---
title: '16-4 Super-admin « Modules » console: per-tenant modules, packs, plan profiles, async bulk jobs'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: '8325d05c2f0f09e2caa38f1353cae865bae708e8'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-2-a-la-carte-bundles.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-8-profils-plan-plafonds-configurables.md'
warnings: ['oversized', 'multiple-goals']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Module activation, packs (16.2), plan profiles (16.8), the backfill (16.5) and `provisionAll` exist only as super-admin REST endpoints; the super-admin (Toor) has no screen to sell modules after a Mobile Money payment (MOD-FR16, UX-DR2, FR78). The bulk operations (backfill, profile-composition re-sync with its notifications, `provisionAll`) run synchronously in one HTTP request (≈2 min for 282 tenants, lost report behind a proxy). Deactivating a free module with dependents only answers « has dependents », and a module whose `onDeactivate` hook keeps failing cannot be deactivated.

**Approach:** Backend: one admin job runner (persisted jobs, progress, retrievable report, one job at a time per kind) used by the backfill, `provisionAll(moduleId)` and the profile-composition re-sync (whose notifications are then sent from the job thread); a dependents-aware deactivation (`cascade`) and an audited `force` override; a per-tenant module history endpoint. Dashboard (Next.js): a « Modules » section in the tenant drawer, a « Packs » page, a « Profils & plafonds » page and a « Modules » page (catalogue + bulk jobs), all in French, reusing the dashboard patterns.

## Boundaries & Constraints

**Always:**
- Jobs: table `public.admin_jobs` (Flyway V35: id uuid, kind `MODULE_BACKFILL|MODULE_PROVISION_ALL|PROFILE_RESYNC`, params jsonb, status `QUEUED|RUNNING|SUCCEEDED|FAILED`, processed, total, report jsonb, actor, created_at, started_at, finished_at, error message French). `POST` starters answer **202** `{jobId}` immediately; `GET /api/v1/admin/jobs/{id}` and `GET /api/v1/admin/jobs?kind=` (latest first, 20) return status/progress/report. Single-node executor (bounded, 1 thread) + ShedLock / DB check so only one job of a kind runs at a time (409 French message otherwise). A job interrupted by a restart is marked `FAILED` « Interrompu par un redémarrage » at boot (no automatic resume). Reports keep the existing shapes (backfill report, provisioning report, re-sync report); tenants named by code only.
- `POST /admin/modules/backfill?dryRun=` and the new `POST /admin/modules/{moduleId}/provision-all` start jobs; `provisionAll` skips tenants whose `provisioned_version` already equals the module's latest version (no Flyway run for them).
- `PUT /admin/plan-profiles/{planType}`: the profile is saved synchronously (200 with the profile); when the granted set changes, the re-sync runs as a `PROFILE_RESYNC` job and the response carries its `jobId`. Notifications produced by that re-sync are sent from the job thread, never in the HTTP request.
- Deactivation: `POST /admin/tenants/{id}/modules/{moduleId}/deactivate` gains `cascade` (deactivates the active dependents first, in reverse dependency order, same transaction rules as today) and `force` (writes DISABLED even when `onDeactivate` fails; `[ADMIN AUDIT]` + audit entry « Désactivation forcée »). Without `cascade`, the 409 `MODULE_HAS_DEPENDENTS` details list the dependents by display name.
- History: `GET /admin/tenants/{id}/modules/history` returns the tenant's module audit entries (activation, deactivation, forced deactivation, pack applied) newest first, French labels, actor shown as a name/phone, no UUIDs.
- Dashboard (`keevo/dashboard`, Next.js 16, React 19, TanStack Query, existing `lib/api/client.ts`, patterns of `app/tenants`, `components/tenants`, `Sidebar.tsx`):
  - Tenant drawer « Modules » section: each module with state (Actif / Lecture seule / Désactivé / Essai), end date and grace date (JJ/MM/AAAA), origin (« Offre », « Super-admin », « Inclus »); activate (status Actif/Essai + optional end date; Essai without date = 14 jours shown as hint) / deactivate with the dependents confirmation (« Désactiver aussi : … ») and a « Forcer » action only after a failure; apply a pack (select + dates); history list.
  - `/packs`: list (name, description, modules, « Inapplicable » badge when `applicable=false`), create / edit (id slug, name, description, module checkboxes without core/platform), delete (« Les comptes gardent leurs modules »).
  - `/plans` « Profils & plafonds »: the three profiles, limits with « illimité » for empty, granted paid modules as checkboxes, `fallback` warning; saving sends the three limit keys explicitly; when a re-sync job starts, show its progress.
  - `/modules`: catalogue (name, gratuit/payant, version) + « Migration des modules » (dry-run then real run) and « Provisionner » per module with migrations, each as a job with a progress bar and the final report.
  - Sidebar entries « Modules », « Packs », « Profils & plafonds ». Every server error message shown as the French `message` returned; no raw ids or codes on screen.
- Tests: backend (job runner, starters 202/409, restart marking, cascade/force, history, provisionAll skip), dashboard vitest (API wrappers, form validation, key components) and `next build` green.

**Never:**
- No change to the mobile app, no payment integration, no new lifecycle rules (16.1/16.2/16.8 rules stay as they are).
- No automatic job resume after restart; no multi-node job distribution.
- Never start the local backend without `--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false` (`.env` points to the real WhatsApp provider).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Backfill job | POST backfill dryRun=true | 202 jobId; GET job → RUNNING with processed/total, then SUCCEEDED with the report | — |
| Concurrent job | second backfill while one runs | 409 French message | — |
| Restart | backend stopped during a job | at boot the job is FAILED « Interrompu par un redémarrage » | — |
| provisionAll | 300 tenants already at latest version | report: all skipped, no Flyway run | — |
| Profile re-sync | PUT FREE granting `exports` | 200 profile + jobId; re-sync and notifications in the job | — |
| Cascade | deactivate `inventory` on a trial tenant with `counting`, `multi-store` active | without cascade: 409 listing « Inventaire tournant », « Multi-boutiques »; with cascade: dependents then `inventory` DISABLED | — |
| Force | `onDeactivate` throws | without force: error; with force: DISABLED + « Désactivation forcée » audited | — |
| History | tenant with activations | newest first, French labels, no UUIDs | — |
| Console activation | Essai without date on Facturation | drawer shows Essai until today+14 d | French error displayed as is |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/admin/module/adapter/in/rest/ModuleAdminController.java` -- activate/deactivate/modules/backfill endpoints (66, 83, 94, 103, 117); `BundleAdminController.java` (16.2); `.../admin/plan/adapter/in/rest/AdminPlanProfileController.java` + `PlanProfileAdminService` (16.8, re-sync currently in request).
- `.../shared/module/application/ModuleLifecycleService.java` -- `deactivate` (dependents check ~495 `MODULE_HAS_DEPENDENTS`), `ACTION_ACTIVATED` (112) audit, `[ADMIN AUDIT]` logs; `ModuleProvisionerService.provisionAll` (skip rule to add); `ModuleBackfillService` (16.5, ShedLock `moduleBackfill`).
- Audit storage of module actions: `AuditPort` entries `MODULE_ACTIVATED`/`MODULE_DEACTIVATED` (labels `shared/infrastructure/export/AuditLabels.java:88`).
- Scheduling/locks: ShedLock config `shared/infrastructure/config/SchedulerLockConfig`, `SchedulerLockCoverageTest`.
- Next migration: `keevo/backend/src/main/resources/db/migration/V35__admin_jobs.sql`.
- Dashboard: `keevo/dashboard/src/app/<route>/page.tsx` + `*PageClient.tsx`, `src/components/tenants/{TenantDetailDrawer,PlanSection,ActivatePremiumDialog}.tsx`, `src/components/layout/Sidebar.tsx` (33-48), `src/lib/api/client.ts`, `src/lib/api/admin-tenants.ts`, tests under `src/**/__tests__`, `src/lib/labels.ts` (French labels).

## Tasks & Acceptance

**Execution:**
- `V35__admin_jobs.sql`; `AdminJob` store + `AdminJobRunner` (executor, progress callback, boot-time interrupted marking, 409 per kind) + `AdminJobController` (GET by id / list).
- Backfill, `provisionAll` (with skip) and profile re-sync moved behind the runner; starters return 202 `{jobId}`; tests.
- `ModuleLifecycleService.deactivate` cascade + force (+ `ModuleLifecycle` port, controller params); 409 details with dependents' display names; tests.
- History endpoint + service reading the tenant's module audit entries; test.
- `ErrorCode`/`GlobalExceptionHandler` French messages for new codes (job running, job not found).
- Dashboard: `lib/api/admin-modules.ts`, `admin-bundles.ts`, `admin-plans.ts`, `admin-jobs.ts`; `components/modules/*` (drawer section, pack dialog, job progress), pages `/modules`, `/packs`, `/plans`; Sidebar; vitest tests; `labels.ts` additions.
- Planning doc: tick the 16.4 items (provisionAll async + skip, forced deactivation + upgrades via provisionAll, cascade, profiles, packs, backfill async, re-sync async).

**Acceptance Criteria:**
- Given keevo_dev and the dashboard running locally (`npm run dev`) against the local backend (WhatsApp noop, FCM off), when the super-admin opens a test tenant (KV-I3NPYM) and applies « Pack Pro » with an end date, then the drawer shows Facturation « Actif » until that date and the history lists the activation; restore the tenant afterwards.
- Given the same setup, when a backfill dry run is started from « Modules », then the page shows progress and the final report without the request timing out.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 44 findings — high 2, medium 14, low 18, false 3, maybe-false 7
- findings (grouped by root cause; every reviewer row is covered by one entry):
  - `[high]` `[patch]` (blind, edge ×3, verification-gap other) Profile saved then 409 with no re-sync (late reserve / submit after commit) — composition decided under lock, slot reserved before writing, nothing thrown after commit; `PlanProfileAdminServiceTest` (5 paths).
  - `[high]` `[patch]` (blind, edge ×2) A job failing to start (markRunning / queue-full + markFailed failure) blocked its kind until restart — best-effort FAILED or row deleted; tests.
  - `[medium]` `[patch]` (blind, edge) Boot marking could fail a job just started or another instance's job; success overwrote FAILED — only rows created before this instance started; updates guarded by status; tests.
  - `[medium]` `[patch]` (blind, edge ×2) `force` did not survive a rollback-only hook / null savepoint — hook runs in REQUIRES_NEW, failure swallowed; Postgres test with a `@Transactional` throwing hook.
  - `[medium]` `[patch]` (blind, edge ×2, verification-gap other) Console lost running jobs / reports on reload — pages seeded from the latest job of each kind; 409 switches to the running job.
  - `[medium]` `[patch]` (blind) No confirmation before a composition change notifying every owner — confirmation dialog.
  - `[medium]` `[patch]` (blind, edge ×2) Unavailable module stuck in a pack / profile form — untickable « Module indisponible » (packs), dropped with a note (profiles).
  - `[medium]` `[patch]` (blind) Cascade switches plan-granted paid modules off for good without warning — dialog wording.
  - `[medium]` `[patch]` (verification-gap) `ModuleJobsService` untested — `ModuleJobsServiceTest`.
  - `[medium]` `[patch]` (verification-gap) `migrations` flag / Provisionner button untested — controller assertion + `ModulesPageClient` test.
  - `[medium]` `[patch]` (verification-gap) /plans job display and /modules dry-run/real-run wiring untested — page tests.
  - `[medium]` `[patch]` (verification-gap) Re-sync notifications on the job thread untested — mocked notifier assertion (job thread, none in request).
  - `[medium]` `[patch]` (blind) Finished jobs did not refresh screens — `onFinished` invalidates queries.
  - `[medium]` `[patch]` (edge) Infinite polling of a failing job — stops after 3 errors with a French message.
  - `[low]` `[patch]` (blind) provisionAll report merged up-to-date into skipped — separate `upToDate`.
  - `[low]` `[patch]` (blind) No full validate on demand — `force=true` on provision-all.
  - `[low]` `[patch]` (blind) Stale PUT plan-profiles docs — updated.
  - `[low]` `[patch]` (blind) History limit applied before filtering — filtered in the query.
  - `[low]` `[patch]` (edge) Unknown job kind made the list 500 — skipped / 404.
  - `[low]` `[patch]` (edge) Drawer actions while the catalogue loads — disabled until loaded.
  - `[low]` `[patch]` (edge) Empty dependents list in the cascade dialog — `dependentsOf` returns null.
  - `[low]` `[reject]` (blind) One job per kind too coarse / busy target not named — deliberate simple rule; rare admin operations.
  - `[low]` `[reject]` (blind) Actor ambiguity with several super-admins, pack entries doubled — one super-admin today; pack + per-module entries are intended.
  - `[low]` `[reject]` (blind) Job actor not shown / table never purged — low volume, actor logged `[ADMIN AUDIT]`.
  - `[low]` `[reject]` (intent) Buttons instead of checkboxes in the drawer — same function, dialogs carry the terms.
  - `[low]` `[reject]` (intent) API contracts changed (202 jobs) — required by the routed items; the console is the only caller.
  - `[false]` `[reject]` (intent) Manual ACs not evidenced — run (see below).
  - `[false]` `[reject]` (intent) Cascade/force are new lifecycle rules — operational overrides of deactivation, routed to this story.
  - `[maybe-false]` `[reject]` (intent) UI never run against the backend — API contract exercised live below; component tests mock shapes.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.shared.module.**'` then `'com.keevo.admin.**'`, `'com.keevo.shared.infrastructure.**'`, `'com.keevo.architecture.**'`, `'com.keevo.messaging.**'` -- expected: green, one package at a time
- `cd keevo/dashboard && npm run lint && npm test && npm run build` -- expected: green

**Manual checks:**
- ACs above with the local backend started ONLY with `-Dspring-boot.run.arguments="--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false"`; dashboard pointed at it per its `.env.local`/README; check pages with `curl` of the rendered routes and the API calls if no browser automation is available; stop the backend with `fuser -k 4500/tcp` and the dashboard dev server afterwards; restore KV-I3NPYM's module rows.

## Auto Run Result

Status: done

**Summary:** super-admin console in the dashboard: « Modules » section in the tenant drawer (state, end/grace dates, origin, activate/deactivate with cascade confirmation and « Forcer », apply a pack, history), pages « Modules » (catalogue, backfill simulation/real run, provisioning per module), « Packs » and « Profils & plafonds » (confirmation before a composition change). Backend: persisted admin jobs (V35) with progress and retrievable reports for the backfill, provisionAll (skips up-to-date tenants, `force` to bypass) and the profile re-sync (notifications sent from the job thread); deactivation `cascade` / `force` (hook in its own transaction); module history per tenant.

**Review:** 44 findings — 21 patched entries (2 high, 13 medium, 6 low), 8 rejected entries with reasons. Follow-up review recommended: true — two high entries patched; named risk: the job slot reservation/commit ordering in `PlanProfileAdminService.update` and the runner's status guards under concurrency.

**Verification:** backend packages one at a time, all green — shared.module 449, shared.job 9, admin 558, shared.infrastructure 608, architecture 32, messaging 176, subscription 83, sync (0 failures), invoicing 170, identity.auth 261. Dashboard: lint clean, 67 files / 373 tests, `next build` green. keevo_dev (backend WhatsApp noop, FCM off, real super-admin login): backfill dry run answered 202 in 0.03 s, concurrent start 409, job SUCCEEDED 333/333 with its report; Pack Pro applied to KV-I3NPYM; history in French (« Modules activés : Facturation · Actif jusqu'au 30/06/2027 »). Test row restored, backend stopped. The drawer was not clicked through in a browser (no automation available).

**Note:** during the first round the implementer signed a super-admin token with the dev key for its manual check; not repeated — the orchestrator's check above used the normal login.
