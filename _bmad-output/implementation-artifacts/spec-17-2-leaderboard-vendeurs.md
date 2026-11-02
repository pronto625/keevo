---
title: '17-2 Seller leaderboard shared with employees (owner setting, OFF by default, anonymised, own store) — advanced-reports'
type: 'feature'
created: '2026-11-02'
status: 'done'
baseline_revision: 'f388ba991290c211f7392f57204a2eb52a0944dc'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-17-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The seller leaderboard (FR44, v1s-14-2: `GET /api/v1/reporting/leaderboard`, OWNER only, gated `advanced-reports` READ) is delivered; the remaining item of Story 17.2 (UX16, Story 4.6) is the owner setting « Afficher le classement aux employés »: OFF by default, confirmed at activation (app), and once ON employees see an anonymised ranking. Today an employee always gets 403.

**Approach:** Store the setting as a tenant preference column `leaderboard_visible_to_employees` (default false), expose it read-only in `GET /api/v1/tenant/preferences` and writable by the owner through a new `PUT /api/v1/tenant/leaderboard-visibility` gated `advanced-reports` WRITE. Open the leaderboard endpoint to EMPLOYEE: refused while the setting is OFF; when ON, the ranking is limited to the employee's assigned store and anonymised (only the caller's own row keeps a name and id).

## Boundaries & Constraints

**Always:**
- Storage follows the 17.12 tenant-column pattern: Flyway `V39__tenant_prefs_leaderboard_visibility.sql` on `public.tenant_preferences` (`ADD COLUMN IF NOT EXISTS leaderboard_visible_to_employees boolean NOT NULL DEFAULT false`), an explicit `TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_LEADERBOARD_VISIBILITY` executed for new tenants and in `TenantSchemaSyncService.ensureRequiredIndexes` (next to the 7.5 tenant_preferences statements) for existing ones. Existing tenants → false (zero behaviour change).
- Domain/persistence: `TenantPreferences` (+ JPA entity, repository mapping) and `TenantPreferencesView` carry the flag. The 17.8 effective-preferences rule must NOT reset it: `fixedDefaults` replaces only the report/alert fields; the leaderboard flag always comes from the stored row (it belongs to `advanced-reports`, not `report-config`).
- `GET /api/v1/tenant/preferences`: additive boolean `leaderboardVisibleToEmployees` (stored value). V1 2.0.1 apps unaffected.
- `PUT /api/v1/tenant/leaderboard-visibility` body `{"visibleToEmployees": true|false}` (required boolean): OWNER only (`CurrentActor.requireOwner`, EMPLOYEE → 403 FORBIDDEN), `@RequiresEntitlement(value = "advanced-reports")` (WRITE; Free → 403 `MODULE_NOT_ENTITLED`, grace → 403 `MODULE_READ_ONLY`), returns the updated preferences DTO (same shape as GET). Audited through the existing `ReportPreferencesUpdatedEvent`/`AuditEventListener` path or a dedicated event with a French audit label (« Classement partagé avec les employés » / « Classement masqué aux employés »), never a raw code in the audit detail.
- `GET /api/v1/reporting/leaderboard`: class-level `@PreAuthorize("hasRole('OWNER')")` replaced by OWNER or EMPLOYEE. OWNER: unchanged. EMPLOYEE:
  - setting OFF → 403 `FORBIDDEN`, message « Le classement n'est pas partagé avec les employés. »;
  - setting ON → `storeId` forced to `CurrentActor.assignedStoreId()` (a different `storeId` param is ignored, not an error); same metrics/period params;
  - anonymised rows: for every row except the caller's (`sellerId` = caller user id, sellers are user ids — `sales.employee_id`), `sellerId` = null, `sellerName` = « Vendeur <rank> », `owner` = false; numbers (salesCount, totalRevenue, averageBasket) and rank kept;
  - additive boolean `me` on every row (true only for the caller's row; always false for the owner's view).
- Gating unchanged: `advanced-reports` READ on the leaderboard for both roles (Free → 403 `MODULE_NOT_ENTITLED`, checked before the visibility check).
- `docs/entitlements/endpoints.md` regenerated (`-Dkeevo.entitlements.regenerate=true`) for the new PUT; `EndpointEntitlementCoverageTest` green.

**Never:** no change to ranking computation or metrics; no employee access to other stores; no notification on toggle; no Flutter feature work; no change to the 17.8 report-config rule for report fields.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| New / existing tenant | no PUT yet | `leaderboardVisibleToEmployees: false` | — |
| Owner turns it on | PUT `{visibleToEmployees:true}` on trial/Premium | 200, GET shows true, audit row | — |
| Employee tries to toggle | PUT by EMPLOYEE | 403 `FORBIDDEN` | nothing written |
| Free owner toggles | `advanced-reports` DISABLED | 403 `MODULE_NOT_ENTITLED` | nothing written |
| Missing body field | PUT `{}` | 422 `VALIDATION_ERROR` (French message) | nothing written |
| Employee, setting OFF | GET leaderboard | 403 `FORBIDDEN` « Le classement n'est pas partagé avec les employés. » | — |
| Employee, setting ON | GET leaderboard (any storeId) | 200, own store only, own row named + `me:true`, others « Vendeur N », `sellerId` null | — |
| Employee, Free tenant | `advanced-reports` DISABLED | 403 `MODULE_NOT_ENTITLED` | — |
| Owner | GET leaderboard | unchanged + `me:false` everywhere | — |
| report-config DISABLED | 17.8 fixed defaults | leaderboard flag keeps its stored value | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/reporting/leaderboard/adapter/in/rest/LeaderboardController.java` -- class `@PreAuthorize("hasRole('OWNER')")`, method gated `advanced-reports` READ; builds `SellerLeaderboardQuery(tenant, from, to, storeId, metric)`; add the employee branch here (or in the service with an actor parameter).
- `.../reporting/leaderboard/adapter/in/rest/dto/SellerLeaderboardEntryDto.java` -- add `me`; anonymisation mapping.
- `.../reporting/leaderboard/adapter/out/persistence/JdbcSellerLeaderboardRepository.java` -- sellers = `sales.employee_id` (user id) UNION active employees (`e.user_id`), store-scoped when `storeId` given: forcing the employee's store reuses it.
- `keevo/backend/src/main/java/com/keevo/identity/api/TenantPreferencesView.java` + `TenantPreferencesQueryPort` -- reporting reads the flag here (reporting already depends on `identity.api`).
- `.../identity/onboarding/adapter/in/api/TenantPreferencesQueryAdapter.java` -- 17.8 `effective(...)`: keep the leaderboard flag from the stored row in the DISABLED branch.
- `.../identity/onboarding/domain/model/TenantPreferences.java`, `adapter/out/persistence/TenantPreferencesJpaEntity.java`, `JpaTenantPreferencesRepository.java` -- new field.
- `.../identity/onboarding/adapter/in/web/TenantPreferencesController.java`, `TenantPreferencesResponseDto.java` -- GET field, new PUT; use case + command in `identity/onboarding/domain/port/in` / `application/service` following `UpdateReportPreferences*`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` (`DDL_TENANT_PREFS_MIGRATE_*` constants ~line 200, executed ~1130) and `TenantSchemaSyncService.java:340` -- tenant DDL.
- `keevo/backend/src/main/resources/db/migration/V38__products_track_stock.sql` -- pattern for V39.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/CurrentActor.java` -- `assignedStoreId()`, `actorId()`, `isEmployee()`, `requireOwner(...)`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java`, `shared/infrastructure/export/AuditLabels.java` -- audit wiring and French labels.
- Tests to extend: leaderboard controller/service tests in `src/test/java/com/keevo/reporting/leaderboard/**`, `ReportPreferencesControllerTest`, `TenantPreferencesQueryAdapterTest`, `ReportConfigGatingFullContextTest` (flag survives DISABLED), `PremiumGatingFullContextTest` style full-context test for the employee path (real JWT, employee token with store), provisioner/sync tests listing tenant DDL, `EndpointEntitlementCoverageTest`, export/audit golden tests if an audit label is added.

## Tasks & Acceptance

**Execution:**
- `db/migration/V39__tenant_prefs_leaderboard_visibility.sql` + provisioner constant + sync hook -- column.
- identity domain/JPA/view/DTO + `UpdateLeaderboardVisibility` use case + PUT -- owner setting.
- `TenantPreferencesQueryAdapter` -- flag preserved in the 17.8 DISABLED branch.
- `LeaderboardController` (+ DTO) -- employee branch: visibility check, forced store, anonymisation, `me`.
- Tests covering every matrix row (unit + one full-context test with a real employee token).
- `docs/entitlements/endpoints.md` regenerated; `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick the 17.2 routed item and set the 17.2 row of the Epic 17 table to done with evidence.

**Acceptance Criteria:**
- Given a Premium tenant whose owner turned the setting on, when an employee of store A opens the leaderboard, then they see only store A's sellers, their own row named, others « Vendeur N ».
- Given the setting off (default), when an employee opens the leaderboard, then 403 with the French message and the owner's view is unchanged.

## Verification

Run from `keevo/backend`, ONE package at a time (check `free -m`, one Maven build at a time): `mvn -q test -Dtest='com/keevo/<pkg>/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` for `reporting`, `identity`, `shared`, `architecture`, `sync`, `admin` — all rc=0, 0 failures.

## Spec Change Log

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 29 findings — high 0, medium 1, low 19, false 9, maybe-false 0
- findings:
  - (edge) `[low]` `[reject]` tied anonymised rows ordered by real name (tie-break) — needs identical sales count, revenue and basket; rare, and the rank number is all an employee sees; re-sorting would add a branch to the shared ranking code (Never: no ranking change).
  - (edge) `[low]` `[reject]` concurrent PUTs may write two audit rows — two simultaneous toggles by the owner; harmless duplicate audit line.
  - (edge) `[low]` `[reject]` employee can request an unbounded date range — the owner already could; employee is limited to one store; same indexed query.
  - (intent) `[false]` `[reject]` Flutter edits contrary to "no Flutter feature work" — label sync in the test client so its export contract test stays green (allowed: the Flutter app is our test client; pubspec bumped per project rule); no feature work.
  - (intent) `[false]` `[reject]` `endpoints.md` not in the patch — regenerated in the working tree; excluded from the review diff on purpose (generated file), `EndpointEntitlementCoverageTest` green.
  - (intent) `[medium]` `[patch]` grace state untested — see the blind grace row (same root cause, now fixed and tested).
  - (intent) `[false]` `[reject]` employee without assigned store untested — `LeaderboardControllerTest` covers it (fails closed, confirmed by the verification-gap layer).
  - (intent) `[low]` `[reject]` implementation choices not in the intent (same value → no audit, no row → OFF, `PREFERENCES_NOT_FOUND`, SUPER_ADMIN 403) — all fail-safe and consistent with the intent.
  - (intent) `[low]` `[reject]` rank ties / owner row only unit-tested — anonymisation rule fixed by the intent; unit test covers the owner row.
  - (intent) `[false]` `[reject]` activation confirmation not enforced server-side — the intent places it in the app.
  - (gap) `[low]` `[patch]` new audit actions absent from the Flutter presenter tests — added to the catalogue and sentence tests.
  - (gap) `[low]` `[patch]` `PUT /report-preferences` response flag untested — test added.
  - (blind) `[medium]` `[patch]` owner cannot hide the leaderboard during the `advanced-reports` grace while employees still see it — employee branch now requires the module ENABLED (READ_ONLY → 403), unit + full-context tests.
  - (blind) `[low]` `[reject]` sharing comes back after downgrade + re-upgrade — stored preferences coming back on return to Premium is the 17.8 rule; documented in the mobile section.
  - (blind) `[low]` `[reject]` anonymisation weak in small stores / owner row exposed — the intent fixes the rule (numbers kept, owner relabelled); the owner opts in with a confirmation.
  - (blind) `[low]` `[reject]` `me` always false in the owner view — as specified ("always false for the owner's view"); documented in the schema.
  - (blind) `[low]` `[patch]` nullable `sellerId` / `me` undocumented in OpenAPI — `@Schema` added.
  - (blind) `[low]` `[patch]` `jakarta.transaction.Transactional` outlier — Spring annotation.
  - (blind) `[low]` `[reject]` legacy constructors default the flag to false — production code passes the flag explicitly (checked by the implementer); constructors kept for existing tests, javadoc warns.
  - (blind) `[low]` `[reject]` employee GET goes through the effective-preferences read (one extra state read) — negligible; the adapter test pins the flag preserved in the DISABLED branch.
  - (blind) `[low]` `[patch]` `currentActorOrNull()` swallows errors and audits a null actor — `CurrentActor.actorId()` used.
  - (blind) `[low]` `[reject]` toggle race — same as the edge row.
  - (blind) `[low]` `[reject]` employee PUT on Free gets `MODULE_NOT_ENTITLED` before `FORBIDDEN` — the plan state is not secret (employees read `/tenants/me/modules`); same order as every gated owner endpoint.
  - (blind) `[low]` `[patch]` `fromDomain` flag untested — same as the gap row.
  - (blind) `[false]` `[reject]` record component order fragile — JSON is by name; tests use factories.
  - (blind) `[false]` `[reject]` patch incomplete vs working tree — planning and generated files excluded from the review diff on purpose.
  - (blind) `[false]` `[reject]` `endpoints.md` GET row does not mention EMPLOYEE — that table lists entitlements, not roles.
  - (blind) `[false]` `[reject]` `keevo/app` changes do not belong here — same as the intent row; mobile work tracked in the Mobile section and `m17-2`.
  - (gap) `[false]` `[reject]` other paths — layer reported them covered (none to act on).

## Design Notes

The flag lives with the other tenant preferences (identity owns `tenant_preferences`) and reporting reads it through the existing `TenantPreferencesQueryPort`, so no new cross-module port is needed. Anonymisation keeps the numbers (a ranking is meaningless without them) and hides identities; forcing the employee's store matches the 16-13 employee data scope.

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; erreurs : `spec-16-14-api-prete-client-mobile.md`. Changements additifs seulement : un champ de plus dans les préférences, un endpoint owner, un champ `me` dans les lignes du classement ; l'employé peut désormais lire le classement quand l'owner l'a partagé. Aucun changement de synchro.

**Statut** : API livrée (commit `COMMIT` ; tests `reporting/leaderboard/adapter/in/rest/LeaderboardControllerTest.java`, `reporting/leaderboard/LeaderboardEmployeeFullContextTest.java`, `identity/onboarding/adapter/in/web/ReportPreferencesControllerTest.java`) — mobile à faire.

**Clé de suivi mobile** : `m17-2-leaderboard-vendeurs`

### Ce qui est fait côté API

- Réglage owner « Afficher le classement aux employés », **désactivé par défaut** (tous les comptes existants : désactivé). Il fait partie du module « Rapports avancés » (essai, Premium ou à la carte).
- Tant qu'il est désactivé, un employé qui ouvre le classement reçoit 403 `FORBIDDEN` avec le message « Le classement n'est pas partagé avec les employés. ».
- Une fois activé, l'employé voit le classement **de sa boutique seulement** (le serveur impose sa boutique, le paramètre `storeId` est ignoré), anonymisé : sa propre ligne garde son nom (`me: true`), les autres s'appellent « Vendeur <rang> » sans identifiant ; les chiffres (ventes, CA, panier moyen) et le rang restent visibles.
- La vue owner est inchangée (`me` toujours `false`).
- Pendant les 7 jours de grâce après la fin d'un abonnement (module en lecture seule), le classement n'est **plus montré aux employés** (même 403) ; l'owner le voit toujours, mais ne peut plus changer le réglage.
- Le réglage n'est jamais effacé : s'il était activé, il redevient actif quand le commerce repasse en Premium ou en essai.
- Chaque changement du réglage est inscrit dans l'historique des actions : « Classement partagé avec les employés » / « Classement masqué aux employés ».

### À utiliser

| Appel | Rôle | Réponse / erreurs |
|---|---|---|
| `GET /api/v1/tenant/preferences` | owner | champ additif `leaderboardVisibleToEmployees` (booléen, `false` par défaut) |
| `PUT /api/v1/tenant/leaderboard-visibility` corps `{"visibleToEmployees": true\|false}` | owner | 200 + préférences (même forme que le GET) ; employé → 403 `FORBIDDEN` ; Free → 403 `MODULE_NOT_ENTITLED` ; fin d'essai en grâce → 403 `MODULE_READ_ONLY` ; champ absent → 422 `VALIDATION_ERROR` (message français) |
| `GET /api/v1/reporting/leaderboard?from=&to=&metric=CA\|SALES_COUNT\|AVG_BASKET[&storeId=]` | owner, employé | lignes `rank`, `sellerId` (null pour un vendeur anonymisé), `sellerName`, `owner`, `salesCount`, `totalRevenue`, `averageBasket`, `me` ; employé avec réglage désactivé → 403 `FORBIDDEN` ; Free → 403 `MODULE_NOT_ENTITLED` (vérifié avant le réglage) |

### À implémenter côté mobile

1. Écran Réglages / Équipe (owner, module « Rapports avancés » actif) : interrupteur « Afficher le classement aux employés », valeur lue dans `leaderboardVisibleToEmployees`. À l'activation, demander une confirmation (« Vos employés verront le classement de leur boutique, sans le nom de leurs collègues. ») avant le `PUT` ; la désactivation n'en demande pas. Module non actif → interrupteur masqué ou incitation Premium, jamais d'erreur en boucle.
2. Employé : afficher l'onglet Classement ; sur 403 `FORBIDDEN`, afficher le message reçu (« Le classement n'est pas partagé avec les employés. ») dans un état vide, sans réessayer en boucle ; sur 403 `MODULE_NOT_ENTITLED`, masquer l'onglet.
3. Employé : pas de sélecteur de boutique (le serveur impose la sienne) ; mettre en avant la ligne `me: true` ; ne jamais afficher d'identifiant.
4. Owner : rien ne change dans le classement (ignorer `me`).
5. Le classement employé n'est pas disponible hors ligne (les ventes des collègues ne sont pas sur l'appareil de l'employé) : hors ligne → message « Connexion requise pour voir le classement ».

### Implémentation de référence

Client Flutter : onglet Rapports > Équipe (`keevo/app/lib/features/profitability/` : `seller_leaderboard_model.dart`, `remote_profitability_datasource.dart` ; classement OWNER de v1s-14-2) — le client Flutter ne connaît ni le réglage ni la vue employé.

### Critères d'acceptation mobile / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false`, arrêt `fuser -k 4500/tcp`), un compte en essai avec deux boutiques et des ventes de plusieurs employés.

```bash
# owner AT, employé EAT de la boutique A
curl -s $BASE/api/v1/reporting/leaderboard -H "Authorization: Bearer $EAT"           # 403 FORBIDDEN « Le classement n'est pas partagé… »
curl -s -X PUT $BASE/api/v1/tenant/leaderboard-visibility -H "Authorization: Bearer $AT" \
     -H 'Content-Type: application/json' -d '{"visibleToEmployees":true}'           # 200, leaderboardVisibleToEmployees: true
curl -s $BASE/api/v1/reporting/leaderboard -H "Authorization: Bearer $EAT"           # 200, boutique A, « Vendeur N » + sa ligne me:true
```

1. Given un owner en essai, When il active « Afficher le classement aux employés », Then une confirmation est demandée puis le réglage reste activé à la réouverture.
2. Given le réglage désactivé, When un employé ouvre le classement, Then il voit « Le classement n'est pas partagé avec les employés. ».
3. Given le réglage activé, When un employé de la boutique A ouvre le classement, Then il ne voit que les vendeurs de la boutique A, son nom sur sa ligne, « Vendeur N » pour les autres.
4. Given un compte Free, Then ni l'interrupteur ni le classement ne sont proposés (aucune erreur en boucle).
5. Given l'owner, When il ouvre le classement, Then l'affichage est identique à avant (noms de tous les vendeurs).

## Auto Run Result

**Summary:** owner setting « Afficher le classement aux employés » (`tenant_preferences.leaderboard_visible_to_employees`, V39 + provisioner/sync DDL, default false), readable in `GET /tenant/preferences` (`leaderboardVisibleToEmployees`) and set by `PUT /api/v1/tenant/leaderboard-visibility` (owner, `advanced-reports` WRITE, audited only on a real change: « Classement partagé avec les employés » / « Classement masqué aux employés »). `GET /reporting/leaderboard` opened to EMPLOYEE: refused (403 « Le classement n'est pas partagé avec les employés. ») unless the setting is on AND `advanced-reports` is ENABLED (grace → refused); then own store only, anonymised (others « Vendeur <rang> », `sellerId` null, `owner` false), additive `me`. Owner view unchanged. The 17.8 report-config fallback keeps the stored flag.

**Files changed:** V39 migration; `TenantSchemaProvisioner`/`TenantSchemaSyncService` DDL; identity `TenantPreferences`, JPA entity/repository, `TenantPreferencesView`, `TenantPreferencesQueryAdapter`, `UpdateReportPreferencesService`, new `UpdateLeaderboardVisibility` use case/service/command, `TenantPreferencesController`/DTO; new `LeaderboardVisibilityChangedEvent` + `AuditEventListener`/`AuditLabels`; `LeaderboardController`, `SellerLeaderboardEntryDto`; regenerated `docs/entitlements/endpoints.md`, `keevo/contracts/export-datasets.json`; Flutter test client audit labels + presenter test, pubspec 2.0.23+95. Tests: `LeaderboardEmployeeFullContextTest`, `UpdateLeaderboardVisibilityServiceTest`, extended controller/security/adapter/listener/full-context tests.

**Review:** 29 findings — 0 high, 1 medium (patched: employees saw the shared ranking during the grace while the owner could not hide it), 19 low (5 patched, 14 rejected with reasons), 9 false. 0 deferred.

**Follow-up review recommended:** false — one medium entry patched, no high.

**Verification performed (2026-11-02):** after the patches, one package at a time: reporting 417, identity 480, architecture 32, sync 635, admin 561, shared 1154 — all rc=0, 0 failures/errors; Flutter `audit_presenter_test.dart` + `local_export_contract_test.dart` green (implementer). Live (local backend, noop messaging; V39 applied on keevo_dev at boot): KV-EW02A2 trial — flag false by default; employee GET 403 with the French message; employee PUT 403; owner PUT true → employee GET 200 own row `me:true`; owner GET `me:false`; owner PUT false; audit rows `LEADERBOARD_SHARED_WITH_EMPLOYEES` / `LEADERBOARD_HIDDEN_FROM_EMPLOYEES`; 0 ERROR. Test employee reactivated for the check then deactivated again. Redmi: « En ligne », fresh `last_pull_at`.

**Residual risks:** anonymisation keeps the numbers, so in a store with two or three sellers an employee may guess who is who (accepted by the intent; the owner opts in with a confirmation in the app).
