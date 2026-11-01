---
title: '16-1 Module licence lifecycle: nightly materialisation + notifications, 14-day reactivation trial, read-only banner, dependency rules'
type: 'feature'
created: '2026-11-01'
status: 'done'
baseline_revision: 'd05e1b5e373cc417fca43be02a6cf556c3351449'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-5-migration-backfill-tenant-modules.md'
warnings: ['oversized', 'multiple-goals']
deferred:
  - summary: >-
      A paid module requiring another paid module stays ENABLED when its dependency is READ_ONLY or DISABLED.
    evidence: |-
      Edge review 2026-11-01; no shipped paid module requires a paid one today. Settle when one ships: cap the dependent state at the minimum of its required modules' states.
    location: >-
      keevo/backend/src/main/java/com/keevo/shared/module/application/ModuleStateRules.java
    severity: medium (unverified)
  - summary: >-
      The materializer → notification listener wiring is not tested in a full Spring context.
    evidence: |-
      Verification-gap review 2026-11-01: unit tests call the listener directly and the Postgres test captures the event; a full-context test running materialize() on a due ADMIN row and asserting one push/WhatsApp after commit (none on rollback) would settle it.
    location: >-
      keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/ModuleExpiryNotificationListener.java
    severity: medium (unverified)
  - summary: >-
      The read-only banner wiring in KeevoApp (MaterialApp.router builder) is not covered by an automated test.
    evidence: |-
      Verification-gap review 2026-11-01: KeevoApp is never pumped in tests; verified manually on the Redmi.
    location: >-
      keevo/app/lib/main.dart
    severity: low (unverified)
---

<intent-contract>

## Intent

**Problem:** Real-time blocking already works (states are computed from `expires_at`/`grace_until` on every request, `MODULE_READ_ONLY` on writes, sync push guarded), but the rest of the licence lifecycle (MOD-FR13) is missing: nothing ever writes `EXPIRED`/`DISABLED` on `tenant_modules` rows nor tells the owner per module; a super-admin cannot grant the 14-day reactivation trial without typing a date; a module in read-only grace shows no banner nor upgrade action inside its screens; two dependency rules are open (a read-only paid dependency is not renewed by an activation; a free module could require a paid one).

**Approach:** Add a nightly ShedLock job that materialises row statuses from the dates (never the source of truth for blocking) and sends grouped owner notifications; align `PlanModuleRows` with it; default a `TRIAL` activation without date to 14 days; renew non-ENABLED dependencies during activation; reject free→paid `requires` at manifest load; show a read-only banner with the existing "request activation" action on every screen of a READ_ONLY module in the app. Pin the real-time and data-kept-for-life behaviour with tests.

## Boundaries & Constraints

**Always:**
- Blocking stays computed in real time by `ModuleStateRules`/`ModuleEntitlementService`; the job only materialises: `TRIAL`/`ACTIVE` rows with `expires_at <= now` → `EXPIRED` (dates kept); `EXPIRED` rows with `grace_until` null or `<= now` → `DISABLED`, `disabled_at = now` (dates kept). Applies to `PLAN` and `ADMIN` rows; `SYSTEM` (free) rows and core modules are never touched. Idempotent; one transaction per tenant; a failing tenant is logged and skipped.
- `PlanModuleRows`: when the computed row is `EXPIRED` with `grace_until <= now`, emit `DISABLED` (`disabled_at` = the row's existing `disabled_at` if already DISABLED, else now) so the job and the plan sync never flip a row back and forth (backfill re-run stays at 0 writes). State parity with `planState` must still hold (DISABLED == grace over).
- Notifications (after commit, grouped per tenant, French, display names only, no ids): (a) `ADMIN` rows that became `EXPIRED` → « Votre module X est arrivé à échéance : il reste consultable en lecture seule jusqu'au JJ/MM/AAAA. Contactez-nous pour le renouveler. » (`PLAN` rows are already covered by the plan expiry message); (b) any rows that became `DISABLED` → « Les modules X, Y sont désactivés. Vos données sont conservées et réapparaîtront dès la réactivation. ». Channels: push to owners (`PushNotifier.notifyOwners`) + WhatsApp to the owner the same way `PlanWhatsAppNotificationListener` does. A transition notifies once (the transition itself is the de-duplication).
- Job: daily 02:30 `Africa/Douala` (after the 02:00 plan downgrade), `@SchedulerLock` (covered by `SchedulerLockCoverageTest`), reuses the injected `Clock`.
- `ModuleLifecycleService.activate`: status `TRIAL` without `expiresAt` → `expires_at = now + 14 days` (grace +7 d as today); a paid `ACTIVE` without date is still refused. Dependencies of the target that are not `ENABLED` (READ_ONLY or DISABLED) are activated/renewed in the same plan with the same status and expiry as the target when paid; free dependencies follow the existing free rule.
- `ModuleManifestLoader`: a free module whose `requires` contains a paid module fails the catalogue load with a clear message (no shipped manifest does this today).
- App: on every route of a module whose state is READ_ONLY, show a persistent banner « Module en lecture seule jusqu'au JJ/MM/AAAA » with a « Réactiver » / « Demander l'activation » button reusing the "Mes modules" support request (`my_modules_page.dart` `supportMessage`); the date comes from `GET /tenants/me/modules` (`graceUntil`); employees see the banner without the button. Bump `keevo/app/pubspec.yaml`.

**Never:**
- Never delete a `tenant_modules` row, a table or tenant data when disabling; never drop module DDL.
- Do not make the job a blocking gate (no state read may depend on it having run).
- No per-module J-7/J-1 reminders (plan reminders already exist), no self-service payment, no new backend activation-request endpoint (16.4/16.2 scope).
- Do not change `TenantModulesController` response shape except adding fields if strictly needed.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Real-time block before cron | ADMIN `invoicing` ACTIVE, `expires_at` 1 min ago, job not run | write → 403 `MODULE_READ_ONLY`; read → 200 | — |
| Grace read | EXPIRED, `grace_until` in 3 d | reads pass, writes refused, app banner with date | — |
| Job: expiry | ADMIN row ACTIVE past end | row EXPIRED (dates kept); owner push + WhatsApp (a) once; 2nd run: no change, no message | — |
| Job: grace over | PLAN rows EXPIRED, grace over (8 modules) | rows DISABLED, one grouped message (b) listing 8 names | — |
| Job: free row | SYSTEM `crm` row | untouched | — |
| Job: tenant failure | one tenant's update throws | logged, others processed | — |
| Sync after job | DISABLED PLAN rows, later plan write for FREE tenant | sync sees desired DISABLED → 0 updates | — |
| Reactivation | DISABLED `invoicing` with 5 invoices; super-admin activate TRIAL, no date | ACTIVE-able TRIAL until now+14 d, the 5 invoices readable | — |
| Paid ACTIVE no date | activate ACTIVE without expiresAt | 422 as today | French message |
| Read-only dependency | target paid, its dependency READ_ONLY | dependency renewed in the same activation | — |
| Bad manifest | free module requiring a paid one | catalogue load fails, message names both modules | startup error |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/application/ModuleStateRules.java:43-117` -- real-time states (exclusive bounds) — read-only reference for the job's conditions.
- `.../shared/module/application/ModuleEntitlementService.java:47-70`, `.../infrastructure/EntitlementAspect.java:~73`, `.../sync/sync/application/service/SyncPushService.java:87-128` -- real-time gates to pin with tests.
- `.../shared/module/application/PlanModuleRows.java:65-95` -- `planDerived`, `rowFor`; add the grace-over → DISABLED rule.
- `.../shared/module/adapter/out/persistence/JdbcPlanModuleRowStore.java` -- PLAN upsert; `JdbcTenantModuleLifecycleStore.java:50-70` -- lifecycle upserts (origin ADMIN).
- `.../shared/module/application/ModuleLifecycleService.java:154-252` -- `activate` (TRIAL needs a date at 162-164, `checkExpiryRules` 244-252, renewal of the target 177-181, `GRACE` 93), `deactivate` 368.
- `.../shared/module/application/DependencyResolverService.java:80-109` -- `planActivation` puts READ_ONLY deps in `alreadyActive` (95).
- `.../shared/module/ModuleManifestLoader.java:~463-480` -- cross-checks (core, unknown ids, cycles): add free→paid rule.
- `.../subscription/plan/adapter/in/scheduling/SubscriptionExpiryScheduler.java:39-40` -- cron + ShedLock pattern; `PlanExpiryReminderScheduler` -- Douala zone pattern.
- `.../subscription/plan/application/service/PlanWhatsAppNotificationListener.java` -- after-commit WhatsApp pattern; ports `shared/capability/notification/WhatsAppSender.java`, `PushNotifier.java`, `identity/api/OwnerRecipientResolver.java`.
- `keevo/app/lib/core/router/app_router.dart:248` (`moduleRouteRedirect`), `core/router/module_routes.dart` (`moduleOfPath`, `isModuleWritable`), `core/entitlements/entitlements_provider.dart` -- module of a route and its state; `features/settings/presentation/page/my_modules_page.dart:325,362,485` -- read-only wording, `supportMessage`, request button.
- Tests: `shared/module/application/{ModuleLifecycleServiceTest,DependencyResolverServiceTest,PlanModuleRowsTest}`, `shared/module/{ModuleLifecyclePostgresIntegrationTest,ModuleBackfillPostgresIntegrationTest,EntitlementAspectFullContextTest,ModuleManifestLoaderTest}`, `sync/.../SyncModuleGuardPostgresIntegrationTest`, `shared/infrastructure/config/SchedulerLockCoverageTest`; app `test/features/settings/presentation/page/my_modules_page_test.dart`, `test/core/router/`.

## Tasks & Acceptance

**Execution:**
- `.../shared/module/application/ModuleExpiryMaterializer.java` (+ port/JDBC store in `adapter/out/persistence`) -- per tenant: transition rows as above, return the transitions (module ids, new status, grace date, origin); publish an after-commit event with them.
- `.../shared/module/adapter/in/scheduling/ModuleExpiryScheduler.java` -- 02:30 Africa/Douala, `@SchedulerLock(name = "moduleExpiryScheduler.materialize")`.
- `.../messaging/...` or `.../shared/module/...` listener `ModuleExpiryNotificationListener` -- grouped push + WhatsApp messages (a)/(b) after commit; owner resolution via `OwnerRecipientResolver`; failures logged, never thrown.
- `PlanModuleRows.java` -- grace-over → DISABLED; extend `PlanModuleRowsTest` (parity property still holds).
- `ModuleLifecycleService.java` -- 14-day default for TRIAL without date; `DependencyResolverService.java` -- non-ENABLED dependencies included in the activation plan; tests.
- `ModuleManifestLoader.java` -- free→paid `requires` rejected; `ModuleManifestLoaderTest` case.
- Backend tests -- `ModuleExpiryMaterializerPostgresIntegrationTest` (matrix job rows incl. idempotence, SYSTEM untouched, failure isolation, sync-after-job 0 updates), listener unit test (grouping, French text, ADMIN-only expiry message), full-context real-time test (write refused / read allowed with job never run), reactivation keeps data (Postgres: DISABLED invoicing with invoices → activate TRIAL → invoices readable, expiry = now+14 d).
- `keevo/app/lib/core/entitlements/` (or a shared widget under `core/widgets/`) `ModuleReadOnlyBanner` -- shown by the module route shell/scaffold for READ_ONLY modules with grace date and owner-only button opening the support request; widget tests (owner/employee, date format, hidden when ENABLED).
- `keevo/app/pubspec.yaml` -- bump version (patch + build).

**Acceptance Criteria:**
- Given the Redmi connected to a local backend, when a test tenant's `invoicing` is set READ_ONLY (expired ADMIN row with grace), then the Facturation screens show the banner with the date and the request button, creating an invoice is refused with the French read-only message, and the existing invoices stay readable.
- Given the job ran on keevo_dev, when the backfill (16.5) is run again, then it reports 0 rows written.

## Spec Change Log

## Review Triage Log

### 2026-11-01 — Review pass
- verdicts: 38 findings — high 0, medium 6, low 19, false 9, maybe-false 4
- findings:
  - `[false]` `[reject]` (intent) 6-month signup trial absent — delivered by 16.5 (signup sync writes TRIAL rows to the PREMIUM_TRIAL end, seeded at +6 months).
  - `[low]` `[reject]` (intent) 14-day trial only when the super-admin picks TRIAL without a date — spec decision; reactivation goes through the super-admin until self-service (16.2/16.4).
  - `[low]` `[reject]` (intent) Real-time test uses a test bean, ACTIVE only — the gate is shared by every `@RequiresEntitlement` endpoint; device check covered a real invoice write refused.
  - `[low]` `[reject]` (intent) Banner only tested on a fake router — device check on the Redmi (Factures list, banner 08/11/2026, invoices readable) recorded below.
  - `[low]` `[reject]` (intent) "or an access" materialisation not implemented — epic says cron *or* access; states are real time anyway.
  - `[false]` `[reject]` (intent) Reactivation data not tested via API — covered at Postgres level; data is never touched by deactivation.
  - `[false]` `[reject]` (intent) Null `grace_until` goes straight to DISABLED — every write now sets it (lifecycle + plan rows); null only for never-expiring PREMIUM rows.
  - `[low]` `[reject]` (intent) New scheduler instead of refactoring SubscriptionExpiryService — spec decision (separate concern, 02:30 after the plan downgrade).
  - `[false]` `[reject]` (intent) Backfill 0-write AC only on a test DB — the implementer ran it on keevo_dev (0 inserted, 0 updated, 2 648 unchanged).
  - `[medium]` `[patch]` (blind, edge) Plan sync writing DISABLED before the job skips message (b) — the real sync now publishes `ModuleLicencesChangedEvent` for existing rows it disables (`syncDisablingPublishes`).
  - `[low]` `[patch]` (blind) `disabled_at` of an EXPIRED→DISABLED sync untested — asserted = now and kept on re-sync.
  - `[medium]` `[patch]` (blind, edge, verification-gap) Dead free-target branch and tests on a catalogue the loader refuses — branch removed; tests rebuilt on a paid→paid dependency (`lcpremium` requires `lcpaid`) through `activate`.
  - `[medium]` `[patch]` (blind, edge ×2) 14-day default shortens an ENABLED paid module / re-extends a running trial — default applies only to non-ENABLED targets; ENABLED keeps its stored expiry (`trialWithoutEndOnEnabledModuleKeepsIt`).
  - `[low]` `[reject]` (blind) Renewing a PLAN dependency relabels it ADMIN — an explicit super-admin activation is a super-admin decision by design (16.5 origin rules).
  - `[medium]` `[patch]` (verification-gap, blind) Listener not `@DeliverOnce` → replays re-send WhatsApp/push — annotation + reflection test added, like the plan listener.
  - `[low]` `[reject]` (blind, edge) Synchronous after-commit sends slow the nightly loop — same pattern as the plan expiry listener; per-tenant volume is one message.
  - `[low]` `[patch]` (blind, edge) Banner date in device zone vs WAT in messages — formatted in WAT; literal-expectation tests near midnight.
  - `[low]` `[reject]` (blind) Employees see no date — `/tenants/me/modules` sends dates to owners only (15.6 contract); spec's "banner with date" is the owner case.
  - `[low]` `[patch]` (blind) « le module « demandé » » fallback — neutral sentence via `reactivationMessage`.
  - `[low]` `[patch]` (blind) Singular push title for several modules — singular/plural titles.
  - `[low]` `[patch]` (blind) Event "never empty" not enforced — compact constructor rejects null/empty, immutable copy.
  - `[low]` `[reject]` (blind) Catalogue filter of the materializer untested — SQL already restricts to PLAN/ADMIN; free/core paid rows do not exist.
  - `[low]` `[reject]` (blind) Rows of uninstalled modules re-locked nightly — none exist; negligible.
  - `[low]` `[reject]` (blind) Unused `DueRow` fields — cosmetic.
  - `[low]` `[reject]` (blind) No scheduler test of its own — thin delegate covered by `SchedulerLockCoverageTest`.
  - `[low]` `[reject]` (blind) Failures only logged, no metric — consistent with the other nightly jobs (logs feed the alerting stack).
  - `[false]` `[reject]` (blind) No AC evidence — recorded in Auto Run Result.
  - `[maybe-false]` `[defer]` (edge) Paid→paid dependency cascade (dependent ENABLED on a READ_ONLY dependency) — no shipped paid module requires a paid one; settle when one ships (state cap = min of required states).
  - `[low]` `[reject]` (edge) SUSPENDED tenants still get module messages — harmless information.
  - `[maybe-false]` `[defer]` (verification-gap) Materializer → listener wiring not tested in full context — device/manual check and unit tests; one full-context test would settle it.
  - `[maybe-false]` `[defer]` (verification-gap) Banner wiring in `KeevoApp` not tested — `KeevoApp` is never pumped in tests (bootstrap); verified on device.
  - `[false]` `[reject]` (edge duplicate of 14-day shortening) — grouped with the patched entry above.
  - `[false]` `[reject]` (edge duplicate of dead branch) — grouped with the patched entry above.
  - `[false]` `[reject]` (edge duplicate of sync notification) — grouped with the patched entry above.
  - `[false]` `[reject]` (edge duplicate of banner zone) — grouped with the patched entry above.
  - `[low]` `[reject]` (verification-gap other) Lifecycle tests rely on a refused catalogue — fixed by the dead-branch patch.
  - `[maybe-false]` `[reject]` (blind) Notification lost if the listener crashes after commit — now `@DeliverOnce` like its siblings (at-most-once is the repo policy for messages).

## Design Notes

The job is bookkeeping, not enforcement: every state is already decided from dates at request time, so a missed run never lets a module be used for free. Making `PlanModuleRows` emit DISABLED once the grace is over keeps the plan sync and the job writing the same row, which keeps the 16.5 idempotence guarantee.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -DforkCount=1 -DargLine=-Xmx1536m -Dtest='com.keevo.shared.module.**'` then `'com.keevo.subscription.**'`, `'com.keevo.messaging.**'`, `'com.keevo.shared.infrastructure.**'`, `'com.keevo.sync.**'`, `'com.keevo.invoicing.**'`, `'com.keevo.architecture.**'` -- expected: green (one package at a time)
- `cd keevo/app && flutter analyze && flutter test --concurrency=4 test/core` then `test/features/settings` -- expected: green

**Manual checks:**
- Device (Redmi, `adb devices`): local backend on 4500 with `adb reverse tcp:4500 tcp:4500`, app built against it; check the AC above. Never open share sheets / wa.me on the device (personal WhatsApp): tap the request button only if it is intercepted, otherwise verify its presence. Stop the backend with `fuser -k 4500/tcp`.

## Auto Run Result

Status: done

**Summary:** module licences now have their full lifecycle. Blocking stays real time (dates read on every request); a nightly job (02:30 Douala, ShedLock) materialises TRIAL/ACTIVE→EXPIRED and EXPIRED→DISABLED on PLAN/ADMIN rows, never deleting data, and owners get grouped push + WhatsApp messages (expiry of super-admin modules, disabled modules — also when a plan change disables them). A super-admin TRIAL activation without a date grants 14 days (only to a module that is not already enabled). Non-ENABLED dependencies are renewed with the target; a free module can no longer require a paid one. The app shows « Module en lecture seule jusqu'au JJ/MM/AAAA » with « Réactiver » (owner) on every screen of a read-only module. App 2.0.18+90.

**Files changed:** `ModuleExpiryMaterializer` + `ModuleExpiryStore`/`JdbcModuleExpiryStore`, `ModuleExpiryScheduler`, `ModuleLicencesChangedEvent`, `ModuleExpiryNotificationListener` (new); `PlanModuleRows`, `PlanModuleRowSyncService` (grace over → DISABLED + event), `ModuleLifecycleService` (14-day default), `DependencyResolverService` (renew non-ENABLED deps), `ModuleManifestLoader` (free→paid refused); app `module_read_only_banner.dart`, `main.dart` (router builder), `pubspec.yaml`; tests listed in the triage log; epics doc: 16.1 deferred items ticked.

**Review:** 38 findings — 10 patched (5 medium entries, 5 low), 3 deferred (spec frontmatter), 25 rejected with reasons. Follow-up review recommended: true — several medium entries were patched on a first pass; named risk: the new event published from the plan sync inside subscription writers (an admin downgrade now also sends « Modules désactivés »), covered by `syncDisablingPublishes` and listener tests but not by a full-context replay test.

**Verification:** backend packages one at a time, all green — shared.module 409, subscription 73, messaging 176, shared.infrastructure 604, sync 491, invoicing 170, architecture 32, admin 521. App: `flutter analyze` 0 errors/warnings, `test/core` 521 and `test/features/settings` 110 passed. keevo_dev: job processed 331 tenants (40 grace-over PLAN rows disabled), backfill re-run 0 writes. Redmi (2.0.18 debug, tenant KV-ZGGT7G owner, invoicing set to an expired ADMIN row): Factures shows the banner « Module en lecture seule jusqu'au 08/11/2026 » + « Réactiver », invoices readable; a draft save was refused with the French read-only message (implementer run). Test row restored afterwards; « Réactiver » not tapped (opens WhatsApp).

**Residual risks:** the app reads module states from its stored snapshot until a token is issued (refresh on reconnection is 16.3); paid→paid dependency cascade undecided (no such module ships).

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; lecture des états de modules : section « Mobile » de `spec-16-3-modules-actifs-app-sans-claim-jwt.md` (à faire d'abord).

**Statut** : API livrée (commit `6ea22538`) — mobile à faire.

**Clé de suivi mobile** : `m16-1-cycle-vie-licences-grace-temps-reel`

### Ce qui est fait côté API

- Le blocage est calculé **en temps réel** à chaque requête depuis les dates d'abonnement d'un module : échu → `READ_ONLY` pendant la grâce (7 jours après l'échéance) → `DISABLED`. Aucune attente du job de nuit.
- Job de nuit (02:30 heure de Douala) qui matérialise les statuts et prévient le propriétaire (push + WhatsApp, une fois par transition). Les données d'un module désactivé ne sont jamais supprimées.
- Un essai (`TRIAL`) accordé par le super-admin sans date dure 14 jours.

### À utiliser

- États : `GET /api/v1/tenants/me/modules` (voir 16-3). Pour un module `READ_ONLY`, `graceUntil` (ISO-8601 UTC) donne la fin de la lecture seule et `status` son statut enregistré — envoyés **au propriétaire et, depuis 16-14, à l'employé** (pour l'employé, seulement sur un module en lecture seule ; `expiresAt` reste réservé au propriétaire).
- Écriture REST sur un module en grâce : **403** `MODULE_READ_ONLY` « Ce module est en lecture seule : abonnement expiré » ; les lectures (GET) passent. Module désactivé : 403 `MODULE_NOT_ENTITLED` « Ce module n'est pas inclus dans votre abonnement ». Routes → module : `keevo/backend/docs/entitlements/endpoints.md`.
- Synchro : une opération poussée d'un module `READ_ONLY` / `DISABLED` revient `REJECTED` avec `reason` = `MODULE_READ_ONLY` / `MODULE_INACTIVE` → opération **retenue** sur l'appareil (voir protocole de synchro, section « Mobile » de `spec-16-10-scope-employe-historique-transferts.md`).
- Notifications push au propriétaire (`keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/ModuleExpiryNotificationListener.java`) : données FCM `type` = `MODULE_EXPIRED` (module accordé par le super-admin arrivé à échéance : « Votre module X est arrivé à échéance : il reste consultable en lecture seule jusqu'au JJ/MM/AAAA. Contactez-nous pour le renouveler. ») ou `MODULE_DISABLED` (« Les modules X, Y sont désactivés. Vos données sont conservées et réapparaîtront dès la réactivation. »), `deepLink` = `/settings/modules` (route Flutter de « Mes modules »). Enregistrement du jeton push : `POST /api/v1/devices/token` (module `messaging`, contrôleur `.../messaging/notification/adapter/in/rest/DeviceTokenController.java`).

### À implémenter côté mobile

1. **Bandeau persistant** au-dessus de **chaque écran** d'un module dont `state` = `READ_ONLY` : « Module en lecture seule jusqu'au JJ/MM/AAAA » (date = `graceUntil` convertie en heure de Douala, WAT = UTC+1 sans heure d'été, format `dd/MM/yyyy`) ; sans `graceUntil` (ne devrait plus arriver depuis 16-14, employé compris) : « Module en lecture seule ». Le bandeau suit la navigation (apparaît/disparaît selon l'écran courant) sans réinitialiser la pile de navigation.
2. **Bouton « Réactiver »** dans le bandeau, **owner seulement** : ouvre WhatsApp vers le support Keevo avec le même message que « Mes modules » (nom du module, nom du commerce, téléphone de l'owner). L'employé voit le bandeau sans bouton.
3. **Actions d'écriture** désactivées ou interceptées sur un module `READ_ONLY` ; si le serveur répond 403 `MODULE_READ_ONLY`, afficher le message français, ne rien perdre de la saisie locale.
4. Les données du module restent consultables pendant la grâce (lectures en ligne et données locales).
5. **Notifications** `MODULE_EXPIRED` / `MODULE_DISABLED` : afficher titre/texte reçus ; à l'ouverture, aller sur l'écran « Mes modules » de l'app RN (mapper le `deepLink` Flutter `/settings/modules`) et relancer `GET /tenants/me/modules`.
6. Aucune date ISO, aucun identifiant de module ni statut brut à l'écran.

### Implémentation de référence (Flutter, `keevo/app`)

- `lib/core/entitlements/module_read_only_banner.dart` (texte, date WAT, bouton owner, placement au-dessus du navigateur), branché dans `lib/main.dart`
- `lib/features/settings/presentation/page/my_modules_page.dart` (`supportMessage`, « Mes modules »)
- `lib/core/notification/fcm_service.dart` (ouverture du `deepLink`), `lib/core/router/app_router.dart`

### Critères d'acceptation mobile / scénarios de test

Préparation (backend local WhatsApp noop / FCM coupé ; owner connecté) — mettre `invoicing` en grâce en base, puis restaurer la ligne d'origine :

```bash
psql "$KEEVO_DB_URL" -c "UPDATE public.tenant_modules SET status='ACTIVE', expires_at=now()-interval '1 minute', grace_until=now()+interval '7 days' WHERE tenant_id=(SELECT id FROM public.tenants WHERE code='KV-XXXXXX') AND module_id='invoicing';"
curl -s $BASE/api/v1/tenants/me/modules -H "Authorization: Bearer $AT" | jq '.data[] | select(.moduleId=="invoicing")'
```

1. **Bandeau owner** — Given `invoicing` en grâce (`READ_ONLY`, `graceUntil` dans 7 jours), When l'owner ouvre Factures (après rafraîchissement des états, cf. 16-3), Then le bandeau « Module en lecture seule jusqu'au JJ/MM/AAAA » (date de Douala) et le bouton « Réactiver » s'affichent sur chaque écran Factures, et les factures restent lisibles. (En test sur appareil, **ne pas toucher « Réactiver »** : il ouvre WhatsApp.)
2. **Écriture refusée** — Given le même état, When l'owner tente d'enregistrer un brouillon, Then message « Ce module est en lecture seule : abonnement expiré », la saisie n'est pas perdue.
3. **Employé** — Given un employé du même compte, Then bandeau « Module en lecture seule jusqu'au JJ/MM/AAAA » (même date que l'owner, `graceUntil` reçu depuis 16-14) sans bouton.
4. **Fin de grâce** — Given `grace_until` passé (`UPDATE … SET grace_until=now()-interval '1 minute'`), When l'app rafraîchit les états, Then Factures quitte les menus (« Module non activé »), les données réapparaissent à la réactivation.
5. **Synchro retenue** — Given une opération de facture créée hors ligne puis poussée pendant la grâce, Then elle reste sur l'appareil (retenue, non supprimée) et part dès que le module redevient `ENABLED`.
6. **Notification** — Given une notification `MODULE_DISABLED` reçue (FCM réel requis : sinon vérifier dans les journaux backend `[NOTIFICATION STUB]`), When l'owner la touche, Then l'app ouvre « Mes modules ».
