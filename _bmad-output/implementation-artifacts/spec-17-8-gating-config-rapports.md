---
title: '17-8 Report configuration gating — tenants without report-config get the fixed default report behaviour'
type: 'feature'
created: '2026-11-02'
status: 'done'
baseline_revision: 'a1f460c853e9387a1840bafde5da50ffc311d12a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-17-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** `PUT /tenant/report-preferences` is already gated by the paid module `report-config` (13.10a), but every job still reads the stored `tenant_preferences` row. A tenant that configured its reports during the trial (e.g. EOD report off, auto-closure at 23:00, SMS channel) keeps that configuration after falling back to Free, so FR60 ("Free = fixed behaviour") is not respected (deferred item from review 13-10a, Epic 17 header).

**Approach:** Apply the "effective preferences" rule in one place, the identity read port `TenantPreferencesQueryAdapter` (every consumer — EOD report, auto-closure, weekly report, stock alerts, trends, draft-sale notifications — goes through `TenantPreferencesQueryPort`): when `report-config` is DISABLED for the tenant, return the fixed defaults instead of the stored values. `GET /api/v1/tenant/preferences` returns the same effective values plus an additive `reportConfigEditable` flag. Stored values are never modified, so they come back if the tenant returns to Premium.

## Boundaries & Constraints

**Always:**
- Fixed defaults = the `tenant_preferences` column defaults (`TenantSchemaProvisioner`): EOD enabled, 20:00:00, WHATSAPP; weekly enabled, day 0 (Sunday), 20:00:00, WHATSAPP; inventory report enabled, WHATSAPP; stock alert enabled, PUSH; trend notification enabled. Define them once (e.g. a `TenantPreferencesView.fixedDefaults()` factory or a constant in the adapter) and reuse them in the DTO path.
- Only `ModuleState.DISABLED` switches to defaults. ENABLED and READ_ONLY (7-day grace after the end of a plan) keep the stored values: in the grace period the configuration is read-only, not reset.
- Weekly report, trends and stock alerts stay gated by their own modules (`advanced-reports`, `alerts`); this story does not change those gates.
- `GET /tenant/preferences` stays backward-compatible with V1 2.0.1 apps: same fields, same types; `reportConfigEditable` (boolean, `true` iff `report-config` is ENABLED) is the only addition.
- Platform-admin schema (`public`) keeps the stored values (EntitlementService grants every module there).
- If reading the module state throws, log a WARN with the tenant schema and return the stored values (fail-open, the same direction as the 13.10a unreadable-plan rule).

**Never:** no write to `tenant_preferences`, no migration, no change to the PUT/report-test gating, no Flutter feature work, no change to the 16.8 `profile_modules` logic.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Free, trial prefs stored | FREE, row: eod off, 23:00, SMS | port returns eod on, 20:00:00, WHATSAPP; auto-closure triggers at 20:00 WAT; EOD report generated on WHATSAPP; GET shows 20:00 + `reportConfigEditable=false` | — |
| Premium / unexpired trial | `report-config` ENABLED | stored values, `reportConfigEditable=true` | — |
| Grace (READ_ONLY) | plan ended < 7 days | stored values, `reportConfigEditable=false` | PUT still 403 `MODULE_READ_ONLY` (unchanged) |
| Free + à-la-carte `report-config` row ACTIVE | row wins | stored values, editable | — |
| No preferences row | any plan | `Optional.empty()` as today (consumers keep their own fallbacks) | — |
| Module state unreadable | `EntitlementService` throws | stored values | WARN log |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/onboarding/adapter/in/api/TenantPreferencesQueryAdapter.java` -- single read port used by all consumers; add the `EntitlementService` check (`state(schema, "report-config")`) and the defaults substitution in `find`.
- `keevo/backend/src/main/java/com/keevo/identity/api/TenantPreferencesView.java` -- record of the 12 preference fields; natural home for a `fixedDefaults()` factory.
- `keevo/backend/src/main/java/com/keevo/shared/module/EntitlementService.java` -- `state(tenantSchema, moduleId)` returns the effective `ModuleState` (rows, `profile_modules` no-row fallback from 16.8, grace).
- `keevo/backend/src/main/java/com/keevo/identity/onboarding/adapter/in/web/TenantPreferencesController.java:55` -- `GET /preferences` builds the DTO straight from `TenantPreferencesRepository`; switch it to the effective values + flag (keep `sectorType`, `createdAt` from the stored row).
- `keevo/backend/src/main/java/com/keevo/identity/onboarding/adapter/in/web/TenantPreferencesResponseDto.java` -- add `reportConfigEditable` as the last component; keep `fromDomain` behaviour for the PUT response (PUT is only reachable when ENABLED → `true`).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java:182-195` -- read-only evidence of the column defaults.
- Consumers (read-only, unchanged, covered through the port): `reporting/report/application/service/EndOfDayReportListener.java:136`, `commerce/sale/application/service/DayClosureAutoScheduler.java:118`, `reporting/report/application/service/WeeklyReportScheduler.java:120`, `messaging/notification/application/listener/StockAlertNotificationListener.java:112`, `messaging/notification/application/scheduler/SalesTrendDetectionScheduler.java:131`, `messaging/notification/application/listener/SaleDraftValidatedNotificationListener.java:125`.
- `keevo/backend/src/main/java/com/keevo/shared/module/application/ModuleStateRules.java:49-63` -- evidence that the 13.10a transition rule already reads `profile_modules` (16.8): the second half of the deferred item is already satisfied; tick it with this evidence.
- Tests: `keevo/backend/src/test/java/com/keevo/identity/onboarding/**/TenantPreferencesQueryAdapterTest.java`, `ReportPreferencesControllerTest.java`; full-context pattern in `shared.module.PremiumGatingFullContextTest` (Free tenant via the production `TenantFactory`, plan changed in its `subscriptions` row).

## Tasks & Acceptance

**Execution:**
- `TenantPreferencesView.java` -- add `static TenantPreferencesView fixedDefaults()` with the column defaults -- one definition.
- `TenantPreferencesQueryAdapter.java` -- inject `EntitlementService`; in `find`, DISABLED → `fixedDefaults()`, unreadable → stored + WARN -- the single rule for every job.
- `TenantPreferencesResponseDto.java` + `TenantPreferencesController.java` -- GET returns effective values and `reportConfigEditable` -- the app shows what really happens.
- `TenantPreferencesQueryAdapterTest.java` -- unit tests for every matrix row.
- New full-context test (identity or `shared.module` package, same style as `PremiumGatingFullContextTest`) -- Free tenant with stored trial prefs: GET shows defaults + flag false; `TenantPreferencesQueryPort` returns defaults; trial: stored values + flag true; plus `DayClosureAutoScheduler`/`EndOfDayReportListener` unit tests unaffected.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the Epic 17 deferred item with evidence (17-8 + 16.8 `ModuleStateRules`).
- `keevo/backend/docs/entitlements/endpoints.md` -- regenerate only if `EndpointEntitlementCoverageTest` requires it (no annotation change expected).

**Acceptance Criteria:**
- Given a Free tenant whose stored preferences disable the EOD report, when the hourly auto-closure job runs after 20:00 WAT, then its stores are closed and a WHATSAPP EOD report is generated.
- Given a Premium tenant, when it reads or changes its report preferences, then behaviour is unchanged from today.
- Given an installed V1 2.0.1 app, when it calls `GET /tenant/preferences`, then it parses the response (only an added boolean).

## Verification

Run from `keevo/backend`, ONE package at a time (never the full suite at once; host has 15.6 GB — check `free -m`, one Maven build at a time):
- `mvn -q test -Dtest='com.keevo.identity.**'` — then `com.keevo.reporting.**`, `com.keevo.commerce.**`, `com.keevo.messaging.**`, `com.keevo.shared.**`, `com.keevo.architecture.**` (adjust the exact `-Dtest` pattern to what the module's surefire config accepts); all rc=0, 0 failures.

## Spec Change Log

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 35 findings — high 0, medium 7, low 18, false 6, maybe-false 0 (4 rejected lows counted in low)
- findings:
  - (blind) `[medium]` `[patch]` Default times "20:00:00" vs stored "HH:mm" → GET format depends on plan — defaults set to the repository format ("20:00").
  - (blind) `[medium]` `[patch]` GET reads row + `report-config` state twice; values and flag may disagree — single state read per GET drives values and flag.
  - (blind) `[low]` `[patch]` `orElseGet(fixedDefaults)` invents Free defaults when the second read misses — fallback removed (single row read).
  - (blind) `[low]` `[patch]` module id literal duplicated controller/adapter — one constant.
  - (blind) `[low]` `[reject]` catch(RuntimeException) too wide (IllegalArgumentException on invalid schema) — the schema comes from the validated tenant context; an invalid one never reaches this code; narrowing adds branches for no reachable case.
  - (blind) `[false]` `[reject]` epics deferred item not ticked — it is ticked in `epics-refonte-modulaire.md`; planning files were excluded from the review diff on purpose; `EndpointEntitlementCoverageTest` passes, no annotation changed.
  - (blind) `[low]` `[reject]` no test for platform-admin `public` schema — behaviour comes entirely from `EntitlementService` (all modules ENABLED for `public`, already tested in shared.module); the port adds no branch for it.
  - (blind) `[low]` `[patch]` no test for a trial ended > 7 days (DISABLED path through the real rule) — full-context case added.
  - (blind) `[low]` `[patch]` `GET_preferences_readOnlyGrace_notEditable` stubs fixed defaults under READ_ONLY — fixture now returns stored values and asserts them.
  - (blind) `[low]` `[reject]` no DISABLED tests for weekly/stock-alert/trend/draft consumers — they read only through the port, whose DISABLED rule is pinned by unit + full-context tests; per-consumer tests would re-test the port.
  - (blind) `[low]` `[patch]` DayClosure test does not check the converse (no closure before 20:00 when stored time is earlier) — test added (18:00 stored, 19:05 → no closure).
  - (blind) `[low]` `[reject]` extra entitlement DB reads in scheduled loops — one indexed module-row read per tenant per hourly tick (~330 tenants on dev) and per stock-alert event; negligible next to the jobs' own queries.
  - (blind) `[low]` `[patch]` stray blank line between `fromDomain` Javadoc and method — removed.
  - (blind) `[low]` `[reject]` `fromDomain` hard-codes editable=true — only caller is the gated PUT (403 unless ENABLED); unlikely misuse, parameter adds surface.
  - (blind) `[low]` `[reject]` `FIXED_DEFAULTS` declared after its getter — cosmetic, no effect.
  - (blind) `[low]` `[reject]` FQNs inline in tests — cosmetic, test-only.
  - (edge) `[medium]` `[patch]` two state reads per GET can disagree — same root cause as the blind double-read row; fixed by the single read.
  - (edge) `[medium]` `[patch]` unreadable state: stored values + flag false (mixed directions) — kept as the deliberate outcome (fail-open values, fail-closed editing), now derived from the single read, documented and tested.
  - (edge) `[low]` `[patch]` row deleted between reads → Premium sees defaults — single row read removes the window.
  - (edge) `[medium]` `[patch]` time format differs by plan — same as the blind time-format row.
  - (edge) `[low]` `[reject]` `fromDomain` public with editable=true — same as the blind row, rejected for the same reason.
  - (gap) `[medium]` `[patch]` controller fail-closed catch untested — test added (state throws → 200, flag false, stored values).
  - (gap) `[low]` `[reject]` `AdminAnalyticsService:428` stock-alert adoption reads the stored flag — a Free tenant without `alerts` sends no stock alert (module-scoped listener), so the metric's `EXISTS STOCK_ALERT` part already excludes it; only an à-la-carte `alerts` without `report-config` tenant could be miscounted; analytics-only skew.
  - (gap) `[low]` `[patch]` stray Javadoc blank line — same as the blind row.
  - (gap) `[low]` `[patch]` time format differs — same as the blind row.
  - (intent) `[low]` `[reject]` 4 consumers covered only through the port — same as the blind consumer-tests row.
  - (intent) `[false]` `[reject]` "every consumer goes through the port" broken by `AdminAnalyticsService` / `SessionTokenIssuer` — neither is a report/alert job: one is a super-admin aggregate (see gap row), the other only checks the row exists.
  - (intent) `[medium]` `[patch]` unreadable state split between port and controller — same as the edge row.
  - (intent) `[medium]` `[patch]` two independent lookups — same as the blind double-read row.
  - (intent) `[false]` `[reject]` time-format V1 compatibility not checked — checked: V1 app normalises both (`tenant_preferences_model.dart:65`), and the patch removes the difference anyway.
  - (intent) `[false]` `[reject]` dead `orElseGet` fallback — duplicate of the blind row (patched), not a separate defect.
  - (intent) `[low]` `[reject]` DTO repeats "WHATSAPP"/"PUSH" null fallbacks — the effective view never carries null channels for defaults; the literals only guard legacy stored rows, as before.
  - (intent) `[false]` `[reject]` PUT response gained a field — additive, allowed by the V1 compatibility rule.
  - (intent) `[false]` `[reject]` `public` schema untested — duplicate of the blind row.
  - (intent) `[false]` `[reject]` à-la-carte `advanced-reports` with `report-config` DISABLED resets weekly prefs — the intent's default list covers all 12 fields (reading a), so this is the intended behaviour.

### 2026-11-03 — Review pass (follow-up, 5 layers incl. patterns/complexity)
- verdicts: 27 findings — high 0, medium 2, low 15, false 4, maybe-false 0 (6 carried rows counted in their verdict)
- findings:
  - (blind, gap) `[medium]` `[patch]` `reportConfigDisabled_ignoresStoredEarlierTime_noClosureBeforeFixedTime` cannot fail: no active store stubbed, so `verifyNoInteractions(closeDayUseCase)` passes whatever time applies — stub an active store and `existsByStoreIdAndDate=false` like the sibling test.
  - (patterns 1) `[medium]` `[patch]` each `find()` in a scheduler/listener costs the full module-state lookup (rows + plan read on a second connection), with no cache outside HTTP requests, and the weekly/trend schedulers read it twice per tenant (gate + port) — the first-pass row « one indexed module-row read » understated it (code read again) → per-tenant unit-of-work scope for the module-state cache in the scheduler loops and the EOD listener, so gate and port share one read per tenant per tick.
  - (blind, patterns 2) `[low]` `[patch]` web controller depends on the concrete `adapter.in.api` adapter made public; the « effective preferences » rule belongs to the application layer → move it to an application service behind an in-port used by both; adapter back to package-private; stale `IdentityOnboardingApiTestSupport` Javadoc fixed.
  - (edge) `[low]` `[patch]` `state == null` treated silently as « not DISABLED » → explicit WARN + stored values, editable=false.
  - (blind) `[low]` `[patch]` GET passes `TenantContext.getCurrentTenant()` unchecked (possible null schema) → `TenantContext.requireCurrent()` as on the port path.
  - (blind) `[low]` `[patch]` `"report-config"` literal still in `@RequiresEntitlement` on PUT/report-test → the shared constant.
  - (blind) `[low]` `[patch]` no test pins `fixedDefaults()` to the column defaults → full-context test comparing it with a freshly inserted default row (`db/modules/core/V1`).
  - (blind) `[low]` `[patch]` full-context test never checks the write side → Free and grace: PUT `/report-preferences` and POST `/report-test` refused (403 / `MODULE_READ_ONLY`).
  - (blind) `[low]` `[patch]` DTO builds the same 15-field record twice → `fromDomain(p)` delegates to `effective(p, view(p), true)`.
  - (intent) `[low]` `[patch]` `TenantPreferencesView` Javadoc says times are « HH:mm:ss » → « HH:mm » (repository format).
  - (blind) `[false]` `[reject]` an upgraded tenant keeps defaults until the registry cache expires → the cache is request-scoped (`ActiveModulesCache`, one HTTP request); the upgrade and the next read are different requests; the test's manual `evict` only clears its own thread-bound entry.
  - (gap other) `[false]` `[reject]` inventory-report defaults have no effect → the intent lists all report fields in the fixed defaults; no consumer reads them today, which is not a defect of this story.
  - (blind) `[low]` `[reject]` fail-open WARN can flood the logs during an outage → only during a registry/database outage, where other errors dominate; throttling adds state for no user benefit.
  - (blind) `[low]` `[reject]` full-context test leaves one tenant per test → the shared Testcontainers database lives for one run only; the other full-context tests do the same.
  - (intent) `[low]` `[reject]` time format « 20:00 » vs the matrix « 20:00:00 » → carried: deliberate (repository format, first pass), schedulers parse both.
  - carried `[low]` `[reject]` (blind, intent) per-consumer DISABLED tests for weekly/stock-alert/trend/draft → carried from 2026-11-02, port rule pinned by unit + full-context tests.
  - carried `[low]` `[reject]` (gap) `AdminAnalyticsService:428` reads the stored `stock_alert_enabled` → carried: a Free tenant has no `alerts` module, `StockAlertNotificationListener` is module-scoped and sends nothing, so the metric is right.
  - carried `[low]` `[reject]` (blind) `fromDomain` hard-codes editable=true → carried (only caller is the gated PUT).
  - carried `[low]` `[reject]` (blind) `"WHATSAPP"`/`"PUSH"` fallbacks repeated → carried (guard legacy rows only).
  - carried `[false]` `[reject]` (intent) `public` schema untested → carried (behaviour entirely from `EntitlementService`).
  - carried `[false]` `[reject]` (intent) paths bypassing the port (`SessionTokenIssuer`, admin analytics) → carried: not report/alert jobs.
  - (intent) `[low]` `[reject]` READ_ONLY PUT 403 only backed by unchanged gating → covered by the write-side test patched above.
  - (intent) `[low]` `[reject]` unreadable state tested only with mocks → the unreadable path is a catch around one call; the full-context test cannot make the real registry throw without a fake.
  - (intent) `[low]` `[reject]` V1 2.0.1 field set not pinned → additive field only, existing controller tests assert the existing fields.
  - (gap) `[low]` `[reject]` admin metric test with stored false + DISABLED → same as the carried AdminAnalytics row.
  - (patterns 3) `[low]` `[reject]` row read wasted on the defaults path → one primary-key read; the leaderboard flag (17-2) still needs it.
  - (edge) `[false]` `[reject]` all other branches traced → reported as sound, no action.

## Design Notes

The port is the right seam: identity owns the preferences and already depends on `shared.module`; putting the rule in each consumer would duplicate it six times and miss future consumers. READ_ONLY keeps the stored values because a grace period must not change behaviour silently; the change happens when the module becomes DISABLED.

## Auto Run Result

**Summary:** with the paid module `report-config` DISABLED (Free, trial or Premium over for more than 7 days, no à-la-carte row), every report/alert job reads the fixed defaults instead of the preferences stored during the trial: EOD report on, 20:00, WhatsApp (auto-closure at 20:00 WAT); weekly Sunday 20:00 WhatsApp; inventory report WhatsApp; stock alerts on, push; trends on. One rule in `TenantPreferencesQueryAdapter.effective(...)` (one module-state read → values + `reportConfigEditable`); `GET /api/v1/tenant/preferences` returns the effective values plus the additive `reportConfigEditable`. Stored row never written — it comes back on return to Premium. READ_ONLY (grace) keeps stored values, not editable; unreadable state → stored values, not editable, WARN.

**Files changed:**
- `identity/api/TenantPreferencesView.java` — `fixedDefaults()` (times in the repository format "HH:mm").
- `identity/onboarding/adapter/in/api/TenantPreferencesQueryAdapter.java` — `REPORT_CONFIG`, `Effective` record, `effective(schema, stored)`; `find` goes through it.
- `identity/onboarding/adapter/in/web/TenantPreferencesController.java` — GET: one row read, one state read.
- `identity/onboarding/adapter/in/web/TenantPreferencesResponseDto.java` — `reportConfigEditable` (last component), `effective(...)` factory.
- Tests: `TenantPreferencesQueryAdapterTest`, `ReportPreferencesControllerTest`, `IdentityOnboardingApiTestSupport`, `DayClosureAutoSchedulerTest` (+2), `EndOfDayReportListenerTest` (+1), new `shared/module/ReportConfigGatingFullContextTest` (6).
- `epics-refonte-modulaire.md` — Epic 17 deferred item ticked (17-8 + 16.8 `ModuleStateRules`/`PlanModuleRows` for the transition rule), 17.8 row done.

**Review:** 35 findings — 0 high, 7 medium (all patched), 18 low (8 patched, 10 rejected with reasons), 6 false (rejected), 0 deferred. Patched: single state read per GET (values and flag can no longer disagree), one row read (no invented defaults), time format unified, constant, Javadoc, tests (controller unreadable state, READ_ONLY values, grace over > 7 days, no closure before 20:00).

**Follow-up review recommended:** true — 2+ medium entries patched on a first pass (time format, single-read GET); named risk: the reshaped GET path (adapter now public and used directly by the controller) was only re-tested, not re-reviewed.

**Verification performed (2026-11-02):** one package at a time, after the review patches: identity 467, reporting 405, commerce 263, messaging 176, shared 1151, architecture 32 — all rc=0, 0 failures/errors. Live (local backend, WhatsApp noop, FCM off, owner of KV-EW02A2 on PREMIUM_TRIAL): GET → stored values, `reportConfigEditable: true`; prefs set to EOD off 23:00 via PUT; `report-config` row set DISABLED → GET `20:00`, EOD on, WHATSAPP, `reportConfigEditable: false`, PUT 403, stored row still `23:00:00|f`; row restored → stored values back; original preferences restored. Redmi (Flutter test client): « En ligne », fresh `last_pull_at`, 0 ERROR in the backend log.

**Residual risks:** pre-existing format asymmetry — GET returns times as "HH:mm", PUT requires "HH:mm:ss" (unchanged by this story; documented for the mobile app). The admin stock-alert adoption metric reads the stored flag (rejected finding, analytics only).

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; protocole de synchro : section « Mobile » de `spec-16-10-scope-employe-historique-transferts.md` ; messages d'erreur : `spec-16-14-api-prete-client-mobile.md`. Ajout **additif** (compatible V1 2.0.1) : un champ booléen en plus dans la réponse, aucun champ retiré, aucun code d'erreur changé.

**Statut** : API livrée (commit `bab15029` ; tests `shared/module/ReportConfigGatingFullContextTest.java`, `identity/onboarding/adapter/in/web/ReportPreferencesControllerTest.java`) — mobile à faire.

**Clé de suivi mobile** : `m17-8-gating-config-rapports`

### Ce qui est fait côté API

- La configuration des rapports (rapport de fin de journée, rapport hebdomadaire, rapport d'inventaire, alertes de stock, tendances : activation, heure, jour, canal) est réservée au module payant « Configuration des rapports » (`report-config`), inclus dans l'essai et le Premium.
- Sans ce module (Free, ou essai/Premium terminé depuis plus de 7 jours), **le serveur applique un comportement fixe**, quels que soient les réglages enregistrés pendant l'essai : rapport de fin de journée activé à **20:00** sur **WhatsApp** (la clôture automatique de la journée se fait donc à 20:00, heure du Cameroun), rapport hebdomadaire le dimanche à 20:00 sur WhatsApp, rapport d'inventaire sur WhatsApp, alertes de stock en notification push, tendances activées. Les rapports hebdomadaires, tendances et alertes restent soumis à leurs propres modules payants.
- Les réglages enregistrés ne sont **jamais effacés** : s'il repasse en Premium, le commerce retrouve ses réglages.
- Pendant les 7 jours de grâce après la fin d'un abonnement, les réglages enregistrés continuent de s'appliquer mais ne sont plus modifiables.
- `GET /api/v1/tenant/preferences` renvoie maintenant les valeurs **réellement appliquées** et un nouveau champ `reportConfigEditable` (`true` seulement si le module est actif).

### À utiliser

| Cas | Requête | Réponse | Source |
|---|---|---|---|
| Lire les réglages | `GET /api/v1/tenant/preferences` | 200, `data` : mêmes champs qu'avant (`eodReportEnabled`, `eodReportTime`, `eodReportChannel`, `weeklyReport*`, `inventoryReport*`, `stockAlertEnabled`, `stockAlertChannel`, `trendNotificationEnabled`, `sectorType`, `createdAt`) + `reportConfigEditable` (booléen) | `TenantPreferencesController#getPreferences`, `TenantPreferencesQueryAdapter#effective` |
| Pas encore de réglages | idem | 404 | idem |
| Modifier (module actif) | `PUT /api/v1/tenant/report-preferences` (owner) — heures au format **`HH:mm:ss`** | 200, même forme + `reportConfigEditable: true` | `TenantPreferencesController#updateReportPreferences` |
| Modifier sans le module | idem | 403 `MODULE_NOT_ENTITLED` « Ce module n'est pas inclus dans votre abonnement » | `@RequiresEntitlement("report-config")` |
| Modifier pendant la grâce | idem | 403 `MODULE_READ_ONLY` | idem |
| Heure invalide | idem avec `"23:00"` | 422 `VALIDATION_ERROR`, `details.eodReportTime` « Heure du rapport de fin de journée invalide (HH:mm:ss) » | `UpdateReportPreferencesRequestDto` |

Attention au format des heures : le GET renvoie `"20:00"` (HH:mm), le PUT exige `"20:00:00"` (HH:mm:ss) — comportement existant, à normaliser côté app (référence Flutter : `_normalizeTime`).

### À implémenter côté mobile

1. Écran « Rapports » (réglages) : lire `reportConfigEditable` à chaque ouverture (pas de cache de session). `true` → écran modifiable comme aujourd'hui. `false` → **lecture seule** : afficher les valeurs reçues (ex. « Rapport de fin de journée à 20:00 sur WhatsApp ») sans interrupteurs actifs, avec le texte « La personnalisation des rapports est incluse dans Premium. » et le bouton habituel vers l'abonnement.
2. Absence du champ (ancien serveur) → considérer `true` (comportement actuel).
3. Toujours afficher les valeurs renvoyées par le GET, jamais des valeurs locales enregistrées pendant l'essai : ce sont celles que le serveur applique.
4. Envoyer les heures au PUT en `HH:mm:ss` ; accepter `HH:mm` et `HH:mm:ss` en lecture.
5. 403 `MODULE_NOT_ENTITLED` / `MODULE_READ_ONLY` sur le PUT (droits changés entre la lecture et l'enregistrement) → recharger le GET et passer en lecture seule, message « Votre abonnement ne permet plus de modifier ces réglages. »
6. Jamais de nom de module, de code ou d'identifiant à l'écran.

### Implémentation de référence

`keevo/app/lib/features/identity/domain/model/tenant_preferences_model.dart` (lecture des préférences, `_normalizeTime`) ; écran de réglages des rapports du client Flutter (`keevo/app/lib/features/settings/`). Le client Flutter ne lit pas encore `reportConfigEditable`.

### Critères d'acceptation mobile / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, arrêt `fuser -k 4500/tcp`), connexion par l'API comme en 16-11 (`BASE`, `AT`).

```bash
curl -s $BASE/api/v1/tenant/preferences -H "Authorization: Bearer $AT" | jq '.data | {eodReportTime, eodReportEnabled, reportConfigEditable}'
# Essai : valeurs enregistrées, reportConfigEditable true.
# Simuler un Free : désactiver la ligne report-config du tenant (base de dev uniquement)
#   UPDATE public.tenant_modules SET status='DISABLED' WHERE module_id='report-config' AND tenant_id=(SELECT id FROM public.tenants WHERE code='KV-XXXXXX');
# → eodReportTime "20:00", eodReportEnabled true, eodReportChannel "WHATSAPP", reportConfigEditable false ; remettre status='TRIAL' ensuite.
```

1. Given un owner en essai, When il ouvre « Rapports », Then l'écran est modifiable et l'enregistrement (heure 23:00) réussit.
2. Given le même commerce sans le module (Free), When l'owner ouvre « Rapports », Then l'écran est en lecture seule, affiche 20:00 et WhatsApp (pas 23:00), avec l'incitation Premium.
3. Given l'écran ouvert en modifiable, When le module est retiré puis l'owner enregistre, Then message « Votre abonnement ne permet plus de modifier ces réglages. » et passage en lecture seule.
4. Given le commerce repassé en essai/Premium, When l'owner rouvre « Rapports », Then ses anciens réglages (23:00) réapparaissent et sont modifiables.
5. Given un employé, When il ouvre l'app, Then aucun écran de réglage des rapports (owner seulement, inchangé).

### Auto Run Result — follow-up review pass (2026-11-03)

Status: done.

**Implemented (patches of the follow-up pass):** the « no closure before 20:00 » test now stubs an active store and fails without the rule; per-tenant `ModuleStateScope` (ThreadLocal unit of work) shares one module-state lookup per tenant per tick between module gates and the preferences port in `DayClosureAutoScheduler`, `WeeklyReportScheduler`, `SalesTrendDetectionScheduler` and every `@ModuleScoped` listener (HTTP request cache unchanged); the effective-preferences rule moved to `EffectiveTenantPreferencesService` behind the in-port `GetEffectiveTenantPreferencesUseCase` (adapter back to package-private, controller on the in-port); null module state → WARN + stored values, not editable; `TenantContext.requireCurrent()` on GET/PUT; one `REPORT_CONFIG` constant for read and write gating; full-context tests pinning `fixedDefaults()` to the column defaults and refusing writes for Free (403 `MODULE_NOT_ENTITLED`) and grace (403 `MODULE_READ_ONLY`); DTO built once; Javadoc time format « HH:mm ».

**Review findings (follow-up pass):** 27 findings — patched 10 (medium 2, low 8), rejected 17 (9 newly with reasons, 8 carried from 2026-11-02). Deferred: none.

**Follow-up review recommendation:** false — this follow-up pass patched no high finding (patched: high 0, medium 2, low 8); the work has converged.

**Verification performed:** full backend suite by package (16 packages, 4 997 tests, 0 failures, 0 errors).

**Residual risks:** none new; the module-state scope is opened in scheduler loops and module-scoped listeners only (HTTP keeps the request cache).
