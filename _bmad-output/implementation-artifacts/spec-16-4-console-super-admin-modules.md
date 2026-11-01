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

## Dashboard super-admin (autre dépôt)

> Destinataire : agent IA qui construit le dashboard super-admin dans un **autre dépôt** (ce dépôt ne livre que l'API). `keevo/dashboard` (Next.js) n'est ici qu'un client de test de l'API, mais c'est la **référence de comportement** (les écrans décrits dans « Boundaries » ci-dessus y existent). Session et authentification : section « Dashboard super-admin » de `spec-16-11-sessions-longues-et-sures.md` (elle-même renvoyant aux « Conventions » de la section « Mobile » de la même spec). Cette section est la référence des **conventions admin** et des **tâches de fond** pour les sections « Dashboard super-admin » de 16-2, 16-5 et 16-8.

**Statut** : API livrée (commit `fc1e431f`) — dashboard à faire.

**Clé de suivi dashboard** : `d16-4-console-super-admin-modules`

### Ce qui est fait côté API

- Modules d'un compte : état effectif de chaque module (avec dates de fin et de grâce, origine de la ligne), activation (dépendances manquantes d'abord, tables créées ; un Essai sans date dure 14 jours — règle 16.1), désactivation avec `cascade` (dépendants actifs d'abord) et `force` (désactivation malgré l'échec de la mise hors service, auditée « Désactivation forcée »), historique des actions sur les modules.
- Catalogue des modules installés (gratuit/payant, version, tables à créer ou non).
- Tâches de fond persistées (`public.admin_jobs`, Flyway V35) : « Migration des modules » (16.5), « Provisionnement d'un module » (tous les comptes, comptes déjà à jour ignorés sauf `force`), « Mise à jour des comptes d'une offre » (16.8). Démarrage en **202** immédiat, suivi par sondage, rapport conservé ; une seule tâche par type à la fois (409) ; une tâche coupée par un redémarrage finit `FAILED` « Interrompu par un redémarrage » (aucune reprise automatique).
- Packs (16.2) et profils d'offre (16.8) : voir leurs sections « Dashboard super-admin ».

### À utiliser

**Conventions admin** (valables pour toutes les routes `/api/v1/admin/**`) :
- Bearer d'une session super-admin (`KV-ADMIN`, cf. 16-11) ; toute autre session → 403 `FORBIDDEN`.
- Succès enveloppé `{"data": …, "timestamp"}` ; `DELETE` → 204 sans corps. Erreur : `{"error": "<message français>", "code", "domainCode", "details", "timestamp"}`.
- Afficher `error` tel quel, y compris pour `VALIDATION_ERROR` : depuis 16-14, `error` porte le vrai message français quand le serveur en fournit un (routes admin des modules, offres, alertes, annonces, statistiques ; premier champ invalide d'un corps de requête, par ordre alphabétique du champ), sinon « Données invalides » ; `details.<champ>` donne en plus le message par champ (pour l'afficher sous le champ). Décider sur `domainCode`, ne jamais l'afficher.
- `tenantId` = `id` (UUID) d'un élément de `GET /api/v1/admin/tenants` (`data.items[]`, contrôleur `keevo/backend/src/main/java/com/keevo/admin/tenant/adapter/in/rest/AdminTenantController.java`) — à utiliser dans les URL, jamais à l'écran (afficher `name` ; la liste ne renvoie **pas** le code du compte, volontairement depuis 9-1). Recherche `?search=` : nom (partiel), téléphone du propriétaire et, depuis 16-14, **code du compte** (`KV-XXXXXX`, partiel, sans casse).
- Dates : ISO-8601 UTC → afficher en JJ/MM/AAAA, heure de Douala (UTC+1).

**Modules d'un compte et catalogue** (`keevo/backend/src/main/java/com/keevo/admin/module/adapter/in/rest/ModuleAdminController.java`, DTO dans `.../rest/dto/`) :

| Méthode | Route | Corps / paramètres | Réponse `data` |
|---|---|---|---|
| GET | `/api/v1/admin/modules` | — | `[{moduleId, displayName, category ("core"\|"business"\|"premium"), free, version, requires: [moduleId], migrations}]` |
| GET | `/api/v1/admin/tenants/{tenantId}/modules` | — | `[{moduleId, displayName, state ("ENABLED"\|"READ_ONLY"\|"DISABLED"), status ("TRIAL"\|"ACTIVE"\|"EXPIRED"\|"DISABLED"\|"AVAILABLE"\|null), expiresAt, graceUntil, provisionedVersion, origin ("PLAN"\|"ADMIN"\|"SYSTEM"\|null)}]` |
| POST | `/api/v1/admin/tenants/{tenantId}/modules/{moduleId}/activate` | corps facultatif `{"status": "ACTIVE"\|"TRIAL", "expiresAt": "AAAA-MM-JJ" (fin de ce jour, Douala) ou instant ISO}` ; module gratuit : aucun corps | `{activated: [{moduleId, displayName}], status, expiresAt, provisionedVersion}` — `activated` vide = rien à changer (déjà actif) |
| POST | `/api/v1/admin/tenants/{tenantId}/modules/{moduleId}/deactivate?cascade=false&force=false` | aucun corps | `{moduleId, displayName, status, deactivated: [{moduleId, displayName}], forced: [{moduleId, displayName}]}` ; déjà désactivé → 200 |
| GET | `/api/v1/admin/tenants/{tenantId}/modules/history` | facultatifs (16-14) `limit` (1–500, défaut 100), `before` + `beforeId` (page suivante : `occurredAt` et `id` de la dernière entrée reçue — curseur composé, aucune entrée de même heure perdue ; `before` seul = strictement plus ancien) ; hors bornes, ou `beforeId` sans `before` → 400 `VALIDATION_ERROR` | `limit` entrées, la plus récente d'abord ; liste plus courte que `limit` = fin de l'historique ; chaque entrée porte aussi `id` (curseur, jamais affiché) : `[{occurredAt, action, subject, detail, actorName, actorPhone, id}]` (`action` déjà en français : « Module activé », « Module désactivé », « Désactivation forcée », « Pack appliqué » ; `detail` peut être null) |
| POST | `/api/v1/admin/modules/{moduleId}/provision-all?force=false` | — | **202** `{jobId}` |
| POST | `/api/v1/admin/modules/backfill?dryRun=true` | voir 16-5 | **202** `{jobId}` |

Règles d'activation (serveur) : payant `ACTIVE` sans date → refusé ; `TRIAL` sans date → 14 jours (seulement si le module n'est pas déjà actif) ; date passée ou à plus de 10 ans → refusée ; un module gratuit n'accepte ni statut ni date.

**Tâches de fond** (`keevo/backend/src/main/java/com/keevo/admin/job/adapter/in/rest/AdminJobController.java`, DTO `.../dto/AdminJobDto.java`, rapports `keevo/backend/src/main/java/com/keevo/shared/module/ModuleBackfill.java` et `ProvisioningReport.java`) :

| Méthode | Route | Réponse `data` |
|---|---|---|
| GET | `/api/v1/admin/jobs/{jobId}` | une tâche (ci-dessous) ; inconnue → 404 `ADMIN_JOB_NOT_FOUND` |
| GET | `/api/v1/admin/jobs?kind=MODULE_BACKFILL\|MODULE_PROVISION_ALL\|PROFILE_RESYNC` | les 20 dernières, la plus récente d'abord (`kind` facultatif) |

Tâche : `{id, kind, label (français : « Migration des modules », « Provisionnement d'un module », « Mise à jour des comptes d'une offre »), params, status ("QUEUED"\|"RUNNING"\|"SUCCEEDED"\|"FAILED"), processed, total (null tant qu'inconnu), report (null avant la fin), createdAt, startedAt, finishedAt, error (message français si FAILED)}`.
- `params` : backfill `{dryRun}` ; provisionnement `{moduleId, module (nom affichable), force}` ; mise à jour d'offre `{planType, plan (nom affichable)}` → afficher `module` / `plan`, jamais `moduleId` / `planType`.
- `report` de `MODULE_PROVISION_ALL` : `{moduleId, provisioned: [code compte], skipped: [code compte], failures: [{tenantCode, reason}], upToDate}` ; de `MODULE_BACKFILL` et `PROFILE_RESYNC` : forme 16.5 (voir section « Dashboard super-admin » de 16-5).
- Flux : `POST` → 202 `{jobId}` → `GET /jobs/{jobId}` toutes les ~1 s tant que `QUEUED`/`RUNNING` → `SUCCEEDED` (rapport) ou `FAILED` (`error`).

**Codes à gérer** (messages : `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java`, `FR_MESSAGES` + `domainCodeToHttpStatus`) :

| `domainCode` | HTTP | Message (`error`) | Réaction attendue |
|---|---|---|---|
| `VALIDATION_ERROR` | 422 (400 pour un paramètre d'URL) | le vrai message (depuis 16-14 ; avant : « Données invalides ») ; aussi dans `details.status` / `details.expiresAt` / `details.modules` / `details.moduleId` / `details.kind` (« Statut invalide : choisissez Actif ou Essai », « Date de fin invalide », « La date de fin doit être dans le futur », « La date de fin ne peut pas dépasser 10 ans », « Une date de fin est requise pour un module payant (« Facturation ») », « Le module « X » est gratuit : il n'a ni date de fin ni période d'essai », « Le module « X » n'a pas de tables à créer », « Type de tâche inconnu ») | message sous le champ / dans la boîte de dialogue |
| `TENANT_NOT_FOUND` | 404 | Espace de travail introuvable | fermer la fiche, recharger la liste |
| `MODULE_NOT_FOUND` | 404 | Module introuvable | recharger le catalogue |
| `MODULE_ACTIVATION_BLOCKED` | 409 | « Activation impossible : … » (`details.reasons`, `details.modules` = noms) | message |
| `MODULE_HAS_DEPENDENTS` | 409 | « Désactivation impossible : ce module est utilisé par « A », « B » » ; `details.modules` = noms des dépendants | boîte « Désactiver aussi : A, B ? » → renvoyer avec `cascade=true` |
| `CORE_MODULE_REQUIRED` | 409 | « Le module « X » fait partie du socle et ne peut pas être désactivé » (nom du module depuis 16-14) | ne pas proposer « Désactiver » sur `category = "core"` |
| `MODULE_PROVISIONING_FAILED` | 500 | Le module n'a pas été activé : la création de ses tables a échoué. Vous pouvez réessayer. | message + réessayer |
| `MODULE_ACTIVATION_FAILED` | 500 | Le module n'a pas été activé : sa mise en service a échoué. Vous pouvez réessayer. | message + réessayer |
| `MODULE_DEACTIVATION_FAILED` | 500 | Le module n'a pas été désactivé : sa mise hors service a échoué. Vous pouvez réessayer. | message + bouton « Forcer la désactivation » (confirmation) → renvoyer avec `force=true` (en gardant `cascade` s'il était demandé) |
| `ADMIN_JOB_RUNNING` | 409 | « « <label> » est déjà en cours. Attendez la fin de la tâche avant d'en relancer une. » (`details.label`) | afficher le message et suivre la tâche en cours (première `QUEUED`/`RUNNING` de `GET /jobs?kind=`) |
| `ADMIN_JOB_NOT_FOUND` | 404 | Tâche introuvable | arrêter le sondage, message |
| `FORBIDDEN` | 403 | Vous n'avez pas les droits nécessaires | session non super-admin → connexion |

### À implémenter côté dashboard

1. **Fiche compte › section « Modules »** : une ligne par module de `GET …/modules` : nom, état (« Actif » = `ENABLED` hors essai, « Essai » = `ENABLED` + `status TRIAL`, « Lecture seule » = `READ_ONLY`, « Désactivé » = `DISABLED`), « Fin » (`expiresAt`), « Lecture seule jusqu'au » (`graceUntil`), origine (« Offre » = `PLAN`, « Super-admin » = `ADMIN`, « Inclus » = `SYSTEM` ou null). Jamais `moduleId`, `status` brut ni `provisionedVersion`. Actions désactivées tant que le catalogue charge.
2. **Activer** : boîte de dialogue ; module payant → Statut « Actif » / « Essai » + date de fin (obligatoire pour Actif ; pour Essai, champ vide avec l'aide « 14 jours si vide ») ; module gratuit → aucune option, aucun corps envoyé. Résultat : « Modules activés : A, B » (`activated`, dépendances incluses) ou « Déjà actif » si la liste est vide ; recharger la section et l'historique.
3. **Désactiver** : confirmation ; sur 409 `MODULE_HAS_DEPENDENTS`, seconde boîte « Désactiver aussi : A, B » (avertir que les modules payants inclus dans l'offre ainsi coupés restent coupés) → `cascade=true`. « Forcer la désactivation » n'apparaît **qu'après** un `MODULE_DEACTIVATION_FAILED`, avec confirmation (action auditée). Résultat : modules désactivés (`deactivated`) et forcés (`forced`) par leur nom.
4. **Historique** de la fiche : date + heure, `action`, `subject`, `detail`, acteur « nom (téléphone) » ; « — » si inconnu. Bouton « Voir plus » : `before` = `occurredAt` et `beforeId` = `id` de la dernière ligne affichée, masqué quand une page revient incomplète.
5. **Page « Modules »** : catalogue (nom, « Gratuit » / « Payant », version) ; carte « Migration des modules » (voir 16-5) ; bouton « Provisionner » pour chaque module avec `migrations = true`, confirmation, option « Vérification complète » (`force=true`), puis suivi de tâche.
6. **Suivi de tâche** (composant commun aux pages Modules et Profils & plafonds) : après le 202, sonder `GET /jobs/{jobId}` chaque seconde tant que `QUEUED`/`RUNNING` ; barre « x / y comptes » (`total` null → barre indéterminée « Démarrage de la tâche… ») ; fin : rapport lisible (`SUCCEEDED`) ou `error` (`FAILED`). Arrêter le sondage après 3 lectures en échec consécutives avec « Impossible de suivre la tâche pour le moment. Rechargez la page pour réessayer. » À la fin, recharger catalogue, modules des comptes, profils et listes de tâches.
7. **Reprise au rechargement** : à l'ouverture d'une page, `GET /jobs?kind=…` : suivre la tâche encore en cours, sinon afficher le dernier rapport. Sur 409 `ADMIN_JOB_RUNNING`, basculer sur la tâche en cours.
8. **Rapport de provisionnement** : nom du module (`params.module`), comptes provisionnés / ignorés / déjà à jour (`upToDate`) / en échec, échecs « KV-XXXXXX : raison ». Les comptes sont nommés par leur code public — c'est le seul identifiant admis à l'écran.
9. **Barre latérale** : « Modules », « Packs », « Profils & plafonds ».
10. **Erreurs** : toujours le message français du serveur (`error`, y compris `VALIDATION_ERROR` depuis 16-14) ; jamais un `domainCode`, un UUID, un `moduleId` ni un statut brut.

### Implémentation de référence

- API : `keevo/dashboard/src/lib/api/admin-modules.ts`, `admin-jobs.ts`, `console-errors.ts` (`consoleErrorMessage`, `dependentsOf`, `isDeactivationFailure`), types `keevo/dashboard/src/types/modules.ts`, hooks `keevo/dashboard/src/hooks/useModules.ts` (`useJob`, `JOB_MAX_ERRORS`)
- Fiche compte : `keevo/dashboard/src/components/modules/TenantModulesSection.tsx`, `components/modules/activation.ts`, hôte `keevo/dashboard/src/components/tenants/TenantDetailDrawer.tsx`
- Tâches : `keevo/dashboard/src/components/modules/JobProgress.tsx`, `JobReport.tsx` ; page `keevo/dashboard/src/app/modules/ModulesPageClient.tsx`
- Libellés : `keevo/dashboard/src/lib/labels.ts` (états, origines) ; barre latérale `keevo/dashboard/src/components/layout/Sidebar.tsx`
- Tests : `keevo/dashboard/src/components/modules/__tests__/`, `keevo/dashboard/src/app/modules/__tests__/`, `keevo/dashboard/src/lib/api/__tests__/admin-modules.test.ts`

### Critères d'acceptation / scénarios de test

Préparation (backend local démarré **uniquement** avec `--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, arrêt `fuser -k 4500/tcp` ; connexion par l'API, jamais de jeton fabriqué ; compte de test Free `KV-I3NPYM` à remettre dans son état d'origine après coup) :

```bash
BASE=http://localhost:4500
PW='Admin@1234!'   # défaut dev de keevo.admin.password (application.yml), sauf si ADMIN_PASSWORD est défini
LT=$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "$(jq -n --arg p "$PW" '{phoneNumber:"+237600000000",password:$p}')" | jq -r .loginToken)
AT=$(curl -s -X POST $BASE/api/v1/auth/select-tenant -H 'Content-Type: application/json' \
  -d "$(jq -n --arg t "$LT" '{loginToken:$t,tenantCode:"KV-ADMIN",deviceLabel:"curl · test"}')" | jq -r .accessToken)
H="Authorization: Bearer $AT"
TID=$(psql "$KEEVO_DB_URL" -tAc "SELECT id FROM public.tenants WHERE code='KV-I3NPYM'")
curl -s $BASE/api/v1/admin/tenants/$TID/modules -H "$H" | jq '.data[] | {displayName,state,status,expiresAt,origin}'   # état d'origine à noter
```

1. **Section Modules** — Given `KV-I3NPYM`, When le super-admin ouvre sa fiche, Then chaque module apparaît avec un état français, ses dates en JJ/MM/AAAA et son origine, sans identifiant technique.
2. **Essai sans date** — When il active « Facturation » en Essai sans date (`curl -X POST $BASE/api/v1/admin/tenants/$TID/modules/invoicing/activate -H "$H" -H 'Content-Type: application/json' -d '{"status":"TRIAL"}'`), Then la fiche montre « Essai » jusqu'à aujourd'hui + 14 jours et la première entrée de l'historique est « Module activé » / « Facturation ».
3. **Actif sans date refusé** — When `-d '{"status":"ACTIVE"}'` sur un module payant non actif, Then 422 et le dashboard affiche « Une date de fin est requise pour un module payant (« … ») » (pris dans `details.expiresAt`), pas « Données invalides ».
4. **Dépendants** — Given un compte de test (Premium ou essai) où « Alertes et notifications » est actif, When on désactive « Messagerie et notifications » (`POST …/modules/messaging/deactivate`), Then 409 et la boîte « Désactiver aussi : Alertes et notifications » ; confirmée (`?cascade=true`), Then les deux modules sont « Désactivé » (`deactivated` les nomme). Restaurer ensuite les lignes. (Éviter « Stock et inventaire » en test : « Point de vente » en dépend et serait coupé par la cascade.)
5. **Forcer** — Given une désactivation qui échoue (500 `MODULE_DEACTIVATION_FAILED`, simulable par une réponse moquée dans les tests du dashboard), Then le message français s'affiche avec « Forcer la désactivation », et seulement alors ; confirmé, l'appel part avec `force=true`.
6. **Provisionnement** — When « Provisionner » sur « Clients et fournisseurs » (`curl -s -X POST $BASE/api/v1/admin/modules/crm/provision-all -H "$H" -w '%{http_code}'` → 202 `{jobId}`), Then la barre « x / y comptes » avance puis le rapport montre les comptes déjà à jour (`upToDate`) ; un second démarrage pendant la tâche → 409 avec « « Provisionnement d'un module » est déjà en cours… » et le dashboard suit la tâche en cours.
7. **Rechargement** — Given une tâche en cours, When la page est rechargée, Then la progression reprend (`GET $BASE/api/v1/admin/jobs?kind=MODULE_PROVISION_ALL -H "$H" | jq '.data[0].status'`).
8. **Tâche inconnue** — `curl $BASE/api/v1/admin/jobs/00000000-0000-0000-0000-000000000000 -H "$H"` → 404 « Tâche introuvable ».
9. **Non super-admin** — Given un jeton d'owner (connexion owner normale), When il appelle `GET /api/v1/admin/modules`, Then 403.
