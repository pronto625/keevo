---
baseline_commit: 8898a5b
---
# Story 13.3: Corriger `DayClosureDeltaProvider` + fenêtre hebdo lundi + timing EOD — 3 B-MED

Status: done

<!-- V1-stabilization track — tag A (patch V1 urgent, release-blocker).
     Branche : v1-stabilization (HEAD 8898a5b = story 13.2 done).
     Refonte absorption : « re-implement dans reporting/sync ».
     ⚠️  Story 100% backend + 1 fichier Flutter (sync payload consumer).
     Aucun changement DDL, aucune migration Flyway.
     Validation optionnelle : lancer validate-create-story avant dev-story. -->

## Story

**As a** Toor (opérateur de la plateforme),
**I want** que les payloads de sync day-closure, la fenêtre du rapport hebdomadaire et l'heure de clôture automatique soient corrects,
**so that** les données syncées vers Flutter reflètent la réalité (revenus ≠ nombre de ventes), les rapports hebdomadaires couvrent toujours la semaine ISO (lundi→dimanche) quel que soit le jour de déclenchement, et la clôture EOD respecte l'heure configurée par tenant (UX13 — fallback 20h WAT) (FR42, FR52, FR53, B-MED).

## Contexte V1-stabilization

- **Source backlog :** `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md:228-241` → Story 13.3 (Refs B-MED, FR42/52/53/60, UX13).
- **Audit — 3 bugs B-MED (tous confiance Haute, vérifiés) :**
  1. **B-MED #1 — `DayClosureDeltaProvider.totalTransactions == totalSales`** (`AUDIT_CONFORMITE_BMAD.md:138-139`) : `DayClosureDeltaProvider.java:46` mappe `row[4]` pour `totalSales` ET `totalTransactions`. Le SQL ne sélectionne aucune colonne `total_transactions`. **Conséquence production** : le Flutter side (`rest_sync_service.dart:601`) stocke `map['totalTransactions']` (= nombre de ventes, ex : 5) dans la colonne `total_revenue` locale (au lieu de `map['totalRevenue']` = somme des montants, ex : 50000). Corruption silencieuse des revenus en base Flutter.
  2. **B-MED #2 — Mauvaise fenêtre de semaine pour jour configurable non-dimanche** (`AUDIT_CONFORMITE_BMAD.md:141-142`) : `WeeklyReportScheduler.java:127-129` calcule `weekStart = todayWAT.minusDays(6)`. Cette formule ne donne le lundi que si `todayWAT = dimanche`. Pour un rapport déclenché un samedi (configurable via `weeklyReportDay=6`), `weekStart` = dimanche (au lieu de lundi). **Contraste** : `ReportController.triggerWeekly:172-175` calcule correctement via `with(DayOfWeek.SUNDAY).minusDays(6)` — les deux codes sont incohérents.
  3. **B-MED #3 — Clôture auto EOD ignore `eodReportTime` par tenant** (`AUDIT_CONFORMITE_BMAD.md:135-136`) : `DayClosureAutoScheduler.java:54` a un cron fixe `0 0 23 * * *` (= 00:00 WAT). Le fallback 20h WAT de UX13 n'existe plus. `tenant_preferences.eod_report_time` (colonne DDL présente, valeur par défaut `'20:00:00'`, field `TenantPreferences.eodReportTime`) est ignoré. **Contraste** : `DayClosureSchedulerNotifier.java:13` (Javadoc) dit explicitement *« The DayClosureAutoScheduler reads preferences live on each hourly tick »* — le notifier a été conçu pour un scheduler qui n'a jamais été implémenté.
- **Architecture :** `_bmad-output/planning-artifacts/architecture.md` — Hexagonal ports/adapters. `TenantPreferences` + `TenantPreferencesRepository` déjà en place (Story 7.5). Pattern de référence : `WeeklyReportScheduler.java` (cron horaire, per-tenant prefs, Clock injectable, guard check).
- **Index track :** `sprint-status.yaml` — `v1s-13-3` DayClosureDeltaProvider + fenêtre hebdo lundi + timing EOD · B-MED (3 bugs) · release-blocker. Position 3 dans Epic 13 (dépend de 13.1/13.2 terminés — aucune dépendance technique directe, mais cohérence d'ensemble du track concurrency/integrity).
- **Aucun changement DDL** : les colonnes `total_sales`, `total_revenue` existent dans `day_closures` depuis Story 4.4. Pas de migration Flyway.
- **Aucun changement Flutter Drift table** : `day_closures_table.dart` n'est pas modifié — seul le mapping du payload backend→Flutter dans `rest_sync_service.dart` est corrigé.

## Problèmes mécaniques identifiés

### Problème 1 : `totalTransactions` = `totalSales` → corruption `total_revenue` Flutter

**État actuel (`DayClosureDeltaProvider.java:28-51`) :**
```java
String sql = "SELECT id, store_id, actor_id, closed_at, " +
        "total_sales, total_revenue, " +     // ← row[4]=total_sales, row[5]=total_revenue
        "cash_amount, momo_amount, is_automatic " +
        "FROM day_closures WHERE closed_at > :since ORDER BY closed_at ASC";
// ...
map.put("totalSales", num(row[4]));          // ✅ OK
map.put("totalTransactions", num(row[4]));   // ❌ BUG : dupliqué de totalSales (row[4])
map.put("totalRevenue", num(row[5]));        // ✅ OK
```

**Côté Flutter (`rest_sync_service.dart:588-606`) :**
```dart
'INSERT INTO day_closures (... total_sales, total_revenue, cash_amount, ...) ...'
[
  map['totalSales'],          // total_sales ← 5       ✅ OK
  map['totalTransactions'],   // total_revenue ← 5     ❌ DEVRAIT ÊTRE totalRevenue (50000)
  map['cashTotal'],           // cash_amount ← 40000   ✅ OK
]
```

**Scénario de bug :**
1. Backend émet `{ totalSales: 5, totalTransactions: 5, totalRevenue: 50000, ... }`
2. Flutter stocke `total_revenue = 5` dans SQLite (au lieu de 50000)
3. Le dashboard Flutter affiche un CA de 5 XAF pour la journée
4. **Deadlock silencieux** : l'utilisateur voit des revenus incohérents, mais aucun message d'erreur

### Problème 2 : Fenêtre de semaine incorrecte pour jour configurable ≠ dimanche

**État actuel (`WeeklyReportScheduler.java:127-129`) :**
```java
// Monday 00:00:00 WAT → Sunday 23:59:59 WAT
Instant weekStart = todayWAT.minusDays(6).atStartOfDay(WAT).toInstant();
Instant weekEnd   = todayWAT.atTime(23, 59, 59).atZone(WAT).toInstant();
```

**Scénarios de bug :**

| Jour de trigger | `todayWAT.minusDays(6)` | Semaine couverte | Semaine correcte (ISO Mon→Sun) |
|---|---|---|---|
| Dimanche (config=0, default) | Dimanche - 6 = **Lundi** ✅ | Lun→Dim | Lun→Dim ✅ |
| Samedi (config=6) | Samedi - 6 = **Dimanche** ❌ | Dim→Sam | Lun→Dim ❌ |
| Mercredi (config=3) | Mercredi - 6 = **Jeudi** (semaine précédente) ❌ | Jeu→Mer | Lun→Dim ❌ |

**Référence correcte (`ReportController.triggerWeekly:172-175`) :**
```java
LocalDate weekEndDate   = todayWAT.with(DayOfWeek.SUNDAY);
LocalDate weekStartDate = weekEndDate.minusDays(6);  // Toujours lundi
```

### Problème 3 : Scheduler EOD ignore `eodReportTime` par tenant

**État actuel (`DayClosureAutoScheduler.java:54,77-100`) :**
```java
@Scheduled(cron = "0 0 23 * * *")   // 23:00 UTC = 00:00 WAT, point final
public void runAutoClosure() {
    for (Tenant tenant : tenantRepository.findAll()) {
        // ...
        LocalDate closureDate = LocalDate.now(WAT).minusDays(1);  // ferme J-1
        // ... aucun check de eodReportTime, aucun check de eodReportEnabled
    }
}
```

**Scénario de bug :**
1. Un tenant configure `eodReportTime = "20:00:00"` via `PUT /api/v1/tenant/preferences` (Story 7.5)
2. Le setting est enregistré dans `tenant_preferences.eod_report_time`
3. `DayClosureAutoScheduler` ne lit jamais cette valeur → clôture toujours à 00:00 WAT
4. UX13 (PRD) : *« Fallback automatique à 20h »* n'est pas respecté
5. `DayClosureSchedulerNotifier` (conçu pour notifier le scheduler des changements) log un message… mais le scheduler ne l'écoute pas

**Référence pattern (`WeeklyReportScheduler.java:79-141`) :**
```java
@Scheduled(cron = "0 0 * * * *")  // Hourly tick
public void runWeeklyReport() {
    LocalDate todayWAT = LocalDate.now(clock.withZone(WAT));
    LocalTime nowWAT   = LocalTime.now(clock.withZone(WAT));
    tenantRepository.findAll().forEach(tenant -> {
        TenantPreferences prefs = tenantPreferencesRepository.findByCurrentTenant().orElse(null);
        LocalTime triggerAt = prefs != null ? parseTime(prefs.weeklyReportTime()) : DEFAULT_REPORT_TIME;
        if (nowWAT.isBefore(triggerAt)) return;
        // ... generate report
    });
}
```

## Acceptance Criteria

> AC BDD de `epics-remediation-audit.md` Story 13.3, décomposées en AC numérotées 1-6 pour la traçabilité TDD.

### AC1 (`DayClosureDeltaProvider` — suppression de `totalTransactions` dupliqué, ferme B-MED #1 backend)

**Given** `DayClosureDeltaProvider.java:46` émet `totalTransactions = num(row[4])` (dupliqué de `totalSales`),
**When** corrigé,
**Then** :
- La clé `totalTransactions` est **supprimée du payload** (elle n'apporte aucune information — `totalSales` = count of COMPLETED sales = la sémantique "transaction count").
- Le SQL SELECT reste inchangé (pas de nouvelle colonne).
- `mapRow()` ne contient plus `map.put("totalTransactions", ...)`.
- **Note** : suppression acceptée car (a) la clé était un alias incorrect de `totalSales`, (b) Flutter doit de toute façon être corrigé (AC2), (c) aucune API publique ne consomme cette clé (sync pull est interne backend↔Flutter).

### AC2 (Flutter `rest_sync_service.dart` — fix `total_revenue` column mapping, ferme B-MED #1 Flutter)

**Given** `rest_sync_service.dart:601` utilise `map['totalTransactions']` pour la colonne `total_revenue`,
**When** corrigé,
**Then** :
- L'INSERT `_upsertDayClosures` remplace `map['totalTransactions']` par `map['totalRevenue']` pour la colonne `total_revenue`.
- Aucune autre colonne Flutter n'est modifiée.
- Le Drift schema (`day_closures_table.dart`) reste inchangé.

### AC3 (`WeeklyReportScheduler` — fenêtre semaine ISO lundi→dimanche invariante, ferme B-MED #2)

**Given** `WeeklyReportScheduler.java:127-129` calcule `weekStart = todayWAT.minusDays(6)`,
**When** corrigé,
**Then** :
- La fenêtre est calculée via `TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)` (recommandation audit) **OU** via le même pattern que `ReportController.triggerWeekly` (`todayWAT.with(DayOfWeek.SUNDAY).minusDays(6)`). Les deux sont équivalents — le dev choisit, mais la cohérence avec `ReportController` est recommandée.
- Quel que soit le jour de trigger (lundi, mercredi, samedi, dimanche), `weekStart` = lundi 00:00 WAT et `weekEnd` = dimanche 23:59:59 WAT.
- Les tests existants de `WeeklyReportSchedulerTest` (8 tests) passent toujours.
- Le cas `configuredSaturday_triggersOnSaturday` (l.174-194) vérifie que la fenêtre reste Lun→Dim même quand le trigger est samedi.

### AC4 (`DayClosureAutoScheduler` — per-tenant `eodReportTime` + fallback 20h WAT, ferme B-MED #3)

**Given** `DayClosureAutoScheduler.java` utilise un cron fixe `0 0 23 * * *` et ignore `tenant_preferences.eod_report_time`,
**When** corrigé,
**Then** (Option A recommandée — voir Décision D1) :
- Le cron devient horaire : `0 0 * * * *` (même pattern que `WeeklyReportScheduler`).
- `TenantPreferencesRepository` est injecté dans le constructeur.
- `Clock` est injecté (constructor package-private pour tests, public pour prod — mirroring `WeeklyReportScheduler`).
- Pour chaque tenant actif :
  1. Lire `prefs.eodReportEnabled()` → si `false`, skip.
  2. Lire `prefs.eodReportTime()` → parser en `LocalTime` (fallback `20:00:00` si null/parse error).
  3. Si `nowWAT.isBefore(triggerAt)` → skip (pas encore l'heure).
  4. Guard : si `dayClosureRepository.existsByStoreIdAndDate(store.id(), todayWAT)` → skip (déjà clos).
  5. `closureDate = todayWAT` (pas `minusDays(1)` — quand on déclenche à 20h, on clôture le jour courant, pas J-1).
  6. Trigger `closeDayUseCase.closeDay(new CloseDayCommand(store.id(), SYSTEM_UUID, tenantSchema, true))`.
- Le fallback 20:00 WAT est matérialisé par `DEFAULT_EOD_TIME = LocalTime.of(20, 0)` (cohérent avec `TenantPreferences.withDefaults` et UX13).
- Le message de log d'information indique l'heure de déclenchement effective par tenant.

### AC5 (TDD — 6 tests minimum, ferme epics-remediation-audit.md TDD)

**Given** les AC ci-dessus,
**Then** les tests suivants doivent exister et passer :
- **AC1** : `shouldNotEmitTotalTransactionsInPayload()` — dans `DayClosureDeltaProviderTest` : vérifier que `queryDelta(...)` retourne des maps sans clé `totalTransactions`. Et `shouldMapTotalRevenueCorrectly()` — vérifier que `totalRevenue` = `row[5]` (pas `row[4]`).
- **AC2** : test Flutter `_upsertDayClosures_storesTotalRevenue` dans `rest_sync_service_test.dart` (si existant) ou via vérification manuelle en review. Si la suite test Flutter ne couvre pas `rest_sync_service.dart`, documenter la vérification manuelle dans le Dev Agent Record.
- **AC3** : `shouldComputeMondayToSundayWindowWhenTriggeredOnSaturday()` — dans `WeeklyReportSchedulerTest` : horaire samedi 20:05 + prefs day=6 → weekStart = lundi (pas dimanche), weekEnd = dimanche. Utiliser le même helper `fixedClockAt` que les tests existants.
- **AC4** : `shouldFireEodAtConfiguredTenantTime()` — dans `DayClosureAutoSchedulerTest` : injecter Clock fixée à 20:05 WAT + prefs `eodReportTime="20:00:00"` + `eodReportEnabled=true` → `closeDayUseCase.closeDay` appelé.
- **AC4** : `shouldNotFireEodBeforeConfiguredTime()` — Clock à 19:30 WAT + prefs `eodReportTime="20:00:00"` → `closeDayUseCase` jamais appelé.
- **AC4** : `shouldSkipEodWhenDisabled()` — prefs `eodReportEnabled=false` → `closeDayUseCase` jamais appelé (même à 20:05).
- **AC4** : `shouldUseDefaultEodTimeWhenPrefsNull()` — pas de prefs → fallback 20:00 WAT, trigger à 20:05.

### AC6 (Full regression — 0 NEW failure)

**Given** les correctifs ci-dessus,
**Then** :
- `mvn test` → 0 NEW failures, 0 NEW errors (même baseline que 13.1/13.2 : 1 flaky pre-existing `OnboardingServiceTest` + 40 DB-dependent errors).
- `flutter test` → 0 NEW failures (si la modif Flutter est testable).
- Vérifier que `DayClosureSyncHandlerTest`, `EndOfDayReportListenerTest`, `WeeklyReportSchedulerTest` (8 tests), `DayClosureAutoSchedulerTest` (5 tests), `DayClosureDeltaProviderTest` (2 tests) passent tous.

## Tasks / Subtasks

- [x] **Task 1 — Fix `DayClosureDeltaProvider` payload (AC1)** [sync]
  - [x] 1.1 Supprimer `map.put("totalTransactions", num(row[4]));` à la ligne 46
  - [x] 1.2 Mettre à jour le test `DayClosureDeltaProviderTest.queryDelta_withSince_returnsOnlyRecentClosures` pour vérifier l'absence de `totalTransactions`
  - [x] 1.3 Ajouter `shouldMapTotalRevenueCorrectly()` — assert `totalRevenue` = `row[5]`
- [x] **Task 2 — Fix Flutter `rest_sync_service.dart` mapping (AC2)** [flutter/sync]
  - [x] 2.1 Remplacer `map['totalTransactions']` par `map['totalRevenue']` ligne 601
  - [x] 2.2 Vérifier qu'aucun autre site Flutter ne lit `totalTransactions` dans le payload day_closures (grep)
  - [x] 2.3 Ajouter test Flutter ou vérification manuelle documentée
- [x] **Task 3 — Fix `WeeklyReportScheduler` fenêtre semaine (AC3)** [reporting]
  - [x] 3.1 Remplacer `todayWAT.minusDays(6)` par `todayWAT.with(DayOfWeek.SUNDAY).minusDays(6)` (mirror `ReportController.triggerWeekly`)
  - [x] 3.2 Ajouter `shouldComputeMondayToSundayWindowWhenTriggeredOnSaturday()` dans `WeeklyReportSchedulerTest`
  - [x] 3.3 Vérifier que `configuredSaturday_triggersOnSaturday` (existant) capture bien la fenêtre via un `ArgumentCaptor` sur `WeeklyReportCommand.weekStart()`/`weekEnd()`
- [x] **Task 4 — Refactor `DayClosureAutoScheduler` per-tenant EOD (AC4)** [scheduler]
  - [x] 4.1 Changer cron de `0 0 23 * * *` → `0 0 * * * *`
  - [x] 4.2 Injecter `TenantPreferencesRepository` + `Clock` (2 constructeurs : @Autowired public + package-private test — mirror `WeeklyReportScheduler`)
  - [x] 4.3 Ajouter constantes `DEFAULT_EOD_TIME = LocalTime.of(20, 0)` et `WAT = ZoneId.of("Africa/Lagos")`
  - [x] 4.4 Refactorer `runAutoClosure()` : boucle tenant → check enabled → parse eodReportTime → guard nowWAT.isBefore(triggerAt) → guard existsByStoreIdAndDate(store, todayWAT) → closeDay(todayWAT)
  - [x] 4.5 `closureDate = todayWAT` (pas minusDays(1) — le 20h WAT clôture le jour courant)
  - [x] 4.6 Ajouter méthode `parseTime(String)` (mirror `WeeklyReportScheduler.parseTime`)
  - [x] 4.7 Mettre à jour `DayClosureAutoSchedulerTest` : injecter Clock + TenantPreferencesRepository, adapter les 5 tests existants, ajouter 4 nouveaux tests AC4
- [x] **Task 5 — Full regression + validation (AC5, AC6)** [testing]
  - [x] 5.1 `mvn test` → 0 NEW failures
  - [x] 5.2 `flutter test` → 0 NEW failures (si modif Flutter testée)
  - [x] 5.3 Vérifier que `DayClosureSyncHandler` n'est pas impacté (lit le payload via `CloseDayUseCase`, pas via le delta provider)
  - [x] 5.4 Vérifier que `EndOfDayReportListener` n'est pas impacté (réagit à `DayClosedEvent`, pas au scheduler)
  - [x] 5.5 curl script : non applicable (backend-only, pas de changement de contrat API publique)

## Dev Notes

### Patterns architecturaux à respecter

- **Hexagonal (ports/adapters)** : `DayClosureAutoScheduler` est dans `application/service`. Il dépend des ports `TenantRepository`, `StoreRepository`, `DayClosureRepository`, `CloseDayUseCase`, et (nouveau) `TenantPreferencesRepository`. Aucun de ces dépendances ne change de nature — on ajoute juste un port existant.
- **Scheduler pattern de référence** : `WeeklyReportScheduler.java` — hourly cron, per-tenant preferences lues live sur chaque tick, Clock injectable, guard check (déjà généré ?), fallback default. **Miroir exact pour `DayClosureAutoScheduler`.**
- **Pas de modification DDL** : toutes les colonnes nécessaires existent (`day_closures.total_sales`, `day_closures.total_revenue`, `tenant_preferences.eod_report_time`, `tenant_preferences.eod_report_enabled`). Pas de migration Flyway.
- **Clock injection** : `java.time.Clock` — production utilise `Clock.systemDefaultZone()`, tests utilisent `Clock.fixed(instant, zone)`. Pattern déjà établi dans `WeeklyReportScheduler`.

### Fichiers à modifier (File List exhaustive)

| Fichier | Action | Raison |
|---------|--------|--------|
| `sync/sync/application/provider/DayClosureDeltaProvider.java` | UPDATE | Supprimer clé `totalTransactions` |
| `sync/sync/application/provider/DayClosureDeltaProviderTest.java` | UPDATE | Assert absence `totalTransactions` + assert `totalRevenue` correct |
| `reporting/report/application/service/WeeklyReportScheduler.java` | UPDATE | Fix weekStart = lundi invariant |
| `reporting/report/application/service/WeeklyReportSchedulerTest.java` | UPDATE | +1 test Saturday window |
| `commerce/sale/application/service/DayClosureAutoScheduler.java` | UPDATE | Cron horaire + per-tenant prefs + Clock |
| `commerce/sale/application/service/DayClosureAutoSchedulerTest.java` | UPDATE | Adapter 5 tests + 4 nouveaux AC4 |
| `keevo/app/lib/core/sync/rest_sync_service.dart` | UPDATE | Fix `totalRevenue` pour colonne `total_revenue` |

### ⚠️ Pièges connus (anti-patterns)

1. **Suppression `totalTransactions` — rétro-compatibilité Flutter** : si une version ancienne de l'app Flutter reçoit un payload sans `totalTransactions`, `map['totalTransactions']` sera `null`. En Flutter `null as num` throws. **Atténuation** : l'app doit être mise à jour en même temps que le backend. Vérifier que `map['totalTransactions']` est bien la seule occurrence dans Flutter (via `grep`). Si d'autres sites existent, les corriger aussi.

2. **`closureDate = todayWAT` vs `minusDays(1)`** : le changement de sémantique est important. Aujourd'hui (00:00 WAT) on ferme J-1. Avec eodReportTime=20:00, on ferme J (le jour qui se termine à 20h). **Ne pas** garder `minusDays(1)` — sinon à 20h on fermerait J-1 alors que les ventes de J-1 ont déjà été clôturées à minuit. **Tester explicitement** : Clock à mardi 20:05 → closureDate = mardi (pas lundi).

3. **Guard `existsByStoreIdAndDate` avec `todayWAT`** : avec le cron horaire, le scheduler tourne 24 fois par jour. La guard doit empêcher les double-clôtures le même jour. Utiliser `todayWAT` (pas `minusDays(1)`) pour le check, cohérent avec `closureDate`.

4. **`eodReportTime` null ou invalide** : `TenantPreferences.eodReportTime()` peut être null (théoriquement, la colonne est `NOT NULL DEFAULT '20:00:00'` — donc toujours présente). Mais par robustesse, appliquer `parseTime()` avec fallback `DEFAULT_EOD_TIME = 20:00`. Mirror `WeeklyReportScheduler.parseTime()`.

5. **`eodReportEnabled` = false** : si le tenant a désactivé le rapport EOD, le scheduler ne doit PAS clore automatiquement. Mais attention : la clôture manuelle (bouton Flutter) doit rester possible. `closeDayUseCase` n'est pas impacté — seul le scheduler automatique skip.

6. **`TenantContext.setCurrentTenant` + `findByCurrentTenant()`** : le pattern `WeeklyReportScheduler` fait `TenantContext.setCurrentTenant(tenant.getSchemaName())` puis `tenantPreferencesRepository.findByCurrentTenant()`. **Reproduire exactement** dans `DayClosureAutoScheduler` — le `TenantPreferencesRepository` lit le tenant via `TenantContext`, pas via un paramètre.

7. **Test existants `DayClosureAutoSchedulerTest`** : les 5 tests actuels construisent le scheduler avec 4 args (sans prefs, sans clock). Ils doivent être adaptés :
   - Ajouter `TenantPreferencesRepository` mock aux 5 tests (retourner des prefs par défaut avec `eodReportTime="20:00:00"`, `eodReportEnabled=true`).
   - Ajouter `Clock` mock (fixe à 20:05 WAT pour les tests qui doivent trigger, ou 19:30 pour ceux qui ne doivent pas trigger).
   - Les tests existants qui attendent `closeDay` called → horaire 20:05 + prefs 20:00 → OK.
   - `scheduler_skipsInactiveTenants` → pas de prefs nécessaires (tenant suspendu skip avant).

8. **`DayClosureSchedulerNotifier`** : ce listener observe `PreferenceUpdatedEvent` et log un message. Il n'a pas besoin d'être modifié — le Javadoc dit déjà *« The DayClosureAutoScheduler reads preferences live on each hourly tick »*. Cette story rend cette promesse vraie.

9. **`CloseDayCommand` — `closureDate` vs `closedAt`** : attention à la sémantique. `CloseDayCommand` ne contient pas de `closureDate` explicite — `CloseDayService` dérive `reportDate = LocalDate.now(WAT).minusDays(1)` pour les automatic closures (d'après le commentaire Story 4.4/7.6). **Vérifier dans `CloseDayService.java`** ce qu'il fait réellement pour `isAutomatic=true`. Si le service calcule `reportDate = now(WAT).minusDays(1)`, il faut le modifier pour `isAutomatic=true` : utiliser `now(WAT)` (pas minusDays). **C'est un point critique à investiguer en priorité au démarrage.**

### Décisions à trancher en code review

- **D1 (Option A vs Option B pour EOD timing)** :
  - **Option A (recommandée)** : cron horaire + per-tenant `eodReportTime` + fallback 20h WAT + `closureDate = todayWAT`. Honore UX13 et `tenant_preferences` schema existant. Modifie le comportement Story 7.6 (midnight → 20h par défaut).
  - **Option B (alternative)** : garder cron midnight + documenter que 20h est retiré. Nécessite mise à jour formelle du PRD/AC (Story 4.4, 7.6) — en dehors du scope d'un patch V1.
  - **Recommandation : Option A**. Le schema existe, le notifier existe, UX13 le demande.
  - **À confirmer en code review.**

- **D2 (Suppression `totalTransactions` vs ajout colonne DDL)** :
  - **Option A (recommandée)** : supprimer la clé du payload (alias inutile de `totalSales`). Fix Flutter.
  - **Option B** : ajouter colonne `total_transactions` à `day_closures`, populate = `total_sales`. Plus de DDL, plus de code, même sémantique.
  - **Recommandation : Option A**.
  - **À confirmer en code review.**

- **D3 (`previousOrSame(MONDAY)` vs mirror `ReportController`)** :
  - Les deux approaches sont équivalentes pour le calcul lundi→dimanche.
  - Mirror `ReportController` : `with(SUNDAY).minusDays(6)` — même pattern, même résultat, cohérence du code.
  - `previousOrSame(MONDAY)` : plus littéral par rapport à l'audit.
  - **Recommandation : mirror `ReportController`** pour la cohérence.
  - **À confirmer en code review.**

### Out of scope (différé)

- **Contenu rapport hebdomadaire — bénéfices & marges** (B-MED FR53) : mentionné dans le même audit block mais pas dans le scope de cette story (nécessite des changements dans `DailyReportFormatter` + `WeeklyReportBuilder` + éventuellement le pricing calculator). Sera traité dans la refonte reporting.
- **`FailoverWhatsAppAdapter` SMS fallback** (FR58) : le « fallback SMS » envoie en réalité via WhatsApp (Twilio `whatsapp:` prefix). Hors scope — refonte messaging.
- **Test d'intégration `@SpringBootTest`** avec vrai scheduler : hors scope AC5 (unit tests suffisants). Pattern pré-existant : les schedulers (`WeeklyReportScheduler`, `DayClosureAutoScheduler`) sont testés en unit avec Clock injecté.
- **Migration rétroactive des `total_revenue` Flutter incorrects** : les données déjà corrompues dans les SQLite clients ne peuvent pas être réparées à distance. Un pull sync ultérieur écrasera avec les bonnes valeurs au prochain cycle.

### Contrepartie mobile (Flutter)

- **1 fichier Flutter modifié** : `rest_sync_service.dart` (Task 2) — fix de mapping payload→SQLite.
- **Aucun changement UI** : le bug est silencieux (données incorrectes mais pas de crash). Pas de UX à modifier.
- **Aucun changement Drift schema** : `day_closures_table.dart` reste identique.
- **Aucun changement provider** : `day_closure_providers.dart` reste identique.
- **Test Flutter** : si `rest_sync_service_test.dart` existe, ajouter un test. Sinon, vérification manuelle documentée dans le Dev Agent Record.

### References

- [Source: _bmad-output/planning-artifacts/epics/epics-remediation-audit.md#Story 13.3]
- [Source: AUDIT_CONFORMITE_BMAD.md:135-142 — 3 B-MED]
- [Source: _bmad-output/planning-artifacts/archive/epics.md:263 — UX13 « fallback 20h »]
- [Source: DayClosureDeltaProvider.java:28-51 — SQL + mapRow (totalTransactions bug)]
- [Source: WeeklyReportScheduler.java:127-129 — weekStart bug + :79-141 — pattern de référence]
- [Source: ReportController.java:172-175 — weekStart correct]
- [Source: DayClosureAutoScheduler.java:54-101 — cron fixe + minusDays(1)]
- [Source: DayClosureSchedulerNotifier.java:13 — Javadoc « reads preferences live on each hourly tick »]
- [Source: TenantPreferences.java:19,23 — eodReportTime + eodReportTime fields]
- [Source: TenantPreferencesJpaEntity.java:30-31 — eod_report_time column]
- [Source: TenantSchemaProvisioner.java:162 — eod_report_time DDL DEFAULT '20:00:00']
- [Source: rest_sync_service.dart:588-606 — _upsertDayClosures Flutter (totalTransactions bug)]
- [Source: day_closures_table.dart — Drift schema (non modifié)]
- [Source: WeeklyReportSchedulerTest.java — pattern de test à reproduire]
- [Source: Story 4.4 (original day closure), Story 7.3 (weekly scheduler), Story 7.5 (preferences), Story 7.6 (simplified EOD)]

## Dev Agent Record

### Agent Model Used

GitHub Copilot (DeepSeek V4 Pro)

### Debug Log References

- Terminal: mvn test -Dtest="DayClosureDeltaProviderTest,WeeklyReportSchedulerTest,DayClosureAutoSchedulerTest" → 21/21 GREEN
- Terminal: mvn test (full regression) → 1488 tests, 1 pre-existing flaky (OnboardingServiceTest), 40 pre-existing DB errors, 0 NEW regressions

### Completion Notes List

**Résumé:** 3 bugs B-MED corrigés — (1) `totalTransactions` dupliqué supprimé du payload sync, (2) fenêtre hebdo corrigée avec mirror `ReportController.triggerWeekly`, (3) `DayClosureAutoScheduler` refactorisé en cron horaire + per-tenant `eodReportTime` + fallback 20h WAT.

**Décisions prises:**
- D1: Option A (cron horaire + per-tenant eodReportTime + closureDate = todayWAT)
- D2: Option A (suppression clé `totalTransactions`, pas de nouvelle colonne DDL)
- D3: Mirror `ReportController.triggerWeekly` (`with(SUNDAY).minusDays(6)`) pour cohérence

**Fichiers modifiés (8):**
- `DayClosureDeltaProvider.java`: ligne `totalTransactions` supprimée
- `DayClosureDeltaProviderTest.java`: 2 assertions ajoutées (absence `totalTransactions` + `totalRevenue` correct)
- `WeeklyReportScheduler.java`: `weekStart` calculé via `with(SUNDAY).minusDays(6)` (invariant lundi)
- `WeeklyReportSchedulerTest.java`: +1 test `shouldComputeMondayToSundayWindowWhenTriggeredOnSaturday` (10 tests total)
- `DayClosureAutoScheduler.java`: refactor complet — cron horaire, Clock + TenantPreferencesRepository injectés, per-tenant eodReportTime, guard isBefore/enabled/existsByStoreIdAndDate, parseTime fallback 20h
- `DayClosureAutoSchedulerTest.java`: déplacé dans `service/` package, 5 tests adaptés + 4 nouveaux (9 tests total)
- `CloseDayService.java`: `minusDays(1)` retiré pour `isAutomatic=true` — utilise `now(WAT)` (cohérent avec scheduler 20h)
- `CloseDayServiceTest.java`: `windowIsYesterdayCalendarDay_forAutoClose` → `windowIsTodayCalendarDay_forAutoClose`
- `rest_sync_service.dart`: `map['totalTransactions']` → `map['totalRevenue']` pour colonne `total_revenue`

**Tests:**
- Backend: 21/21 nouveaux + adaptés GREEN, 1488 full regression (0 NEW regressions)
- Flutter: vérification manuelle — grep confirmé aucun autre site `totalTransactions` dans contexte day_closures

**AC vérifiés:** AC1 (payload sans totalTransactions) ✅, AC2 (mapping Flutter totalRevenue) ✅, AC3 (fenêtre lundi→dimanche invariante) ✅, AC4 (per-tenant eodReportTime + fallback 20h) ✅, AC5 (6+ tests TDD) ✅, AC6 (0 NEW regression) ✅

### File List

| Fichier | Action |
|---------|--------|
| `keevo/backend/src/main/java/com/keevo/sync/sync/application/provider/DayClosureDeltaProvider.java` | UPDATE |
| `keevo/backend/src/test/java/com/keevo/sync/sync/application/provider/DayClosureDeltaProviderTest.java` | UPDATE |
| `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/WeeklyReportScheduler.java` | UPDATE |
| `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/WeeklyReportSchedulerTest.java` | UPDATE |
| `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureAutoScheduler.java` | UPDATE |
| `keevo/backend/src/test/java/com/keevo/commerce/sale/application/service/DayClosureAutoSchedulerTest.java` | MOVED+UPDATE (was `application/`) |
| `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/CloseDayService.java` | UPDATE |
| `keevo/backend/src/test/java/com/keevo/commerce/sale/application/CloseDayServiceTest.java` | UPDATE |
| `keevo/app/lib/core/sync/rest_sync_service.dart` | UPDATE |
| `_bmad-output/implementation-artifacts/sprint-status.yaml` | UPDATE |

### Change Log

- 2026-07-21: Story 13.3 implemented — 3 B-MED fixes: DayClosureDeltaProvider totalTransactions removed, WeeklyReportScheduler ISO week window fix, DayClosureAutoScheduler per-tenant eodReportTime refactor. 21/21 new tests GREEN, 0 NEW regressions (1488 total). Status → review.
- 2026-07-21: Code review bmad-code-review passé (adversarial 3 layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor). 2 patches appliqués (P1 dead ternary CloseDayService, P2 weak negative tests DayClosureAutoSchedulerTest), 6 defers, 22 dismiss. 28/28 tests impactés GREEN, 0 NEW régression. Status → done.

### Review Findings

**Code Review — 2026-07-21 (adversarial: Blind Hunter + Edge Case Hunter + Acceptance Auditor)**

- [x] [Review][Patch] Dead ternary dans `CloseDayService.closeDay()` — les deux branches identiques après fix (`isAutomatic() ? now() : now()`) simplifiées en `LocalDate.now(WAT_ZONE)` [CloseDayService.java:56-58] — **APPLIQUÉ**
- [x] [Review][Patch] Tests négatifs faibles `shouldNotFireEodBeforeConfiguredTime` + `shouldSkipEodWhenDisabled` — ajout `verify(storeRepository, never()).findAllActive()` pour prouver que les guards `isBefore`/`!enabled` fonctionnent (pattern mirror `scheduler_skipsInactiveTenants`) [DayClosureAutoSchedulerTest.java:205,218] — **APPLIQUÉ**
- [x] [Review][Defer] CloseDayService clock injection — `LocalDate.now(WAT_ZONE)` direct, pas de Clock injecté (incohérent avec scheduler qui injecte Clock). Pre-existing, pas introduit par cette story. Recommandation : injecter Clock dans CloseDayService pour testabilité (story de suivi).
- [x] [Review][Defer] TOCTOU race condition — pattern check-then-act `existsByStoreIdAndDate` puis `closeDay` non atomique. Pre-existing (ancien code avait le même pattern). Requiert distributed lock ou contrainte unique DB.
- [x] [Review][Defer] Hourly cron 24x overhead — N tenants × M stores × 24 checks/jour. Décision spec (AC4 Option A = mirror WeeklyReportScheduler). Acceptable pour échelle actuelle (<100 tenants). À surveiller si scale-up.
- [x] [Review][Defer] parseTime format validation — formats invalides (`"25:00:00"`, `"20:00"` sans secondes) fallback silencieux vers 20:00. Pas de validation à l'écriture. Recommandation : valider dans `TenantPreferencesRepository.save()` ou controller.
- [x] [Review][Defer] Day closure window — ventes après 20h — closure à 20h capture ventes 00:00-23:59, mais ventes 20:01-23:59 pas encore en DB à 20h. Question design : clôturer à 20h (UX13) ou à 23:59 (complétude) ? Décision produit requise.
- [x] [Review][Defer] Idempotency across restarts — si scheduler crash à 20:30 après 50% tenants, restart à 21:00 re-traite tous. `existsByStoreIdAndDate` skip déjà clôturés, mais tenants mid-closure pourraient recevoir double closeDay. Pre-existing.
