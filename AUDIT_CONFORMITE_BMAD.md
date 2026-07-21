# Keevo — Audit de Conformité d'Implémentation BMAD

**Date d'audit :** 2026-07-20 · **Branche auditée :** `refractoring` (HEAD `d8d61d0`) · **Méthode :** audit de l'implémentation ligne par ligne (source de vérité) croisé avec les artefacts de planification BMAD (PRD, 9 épics, architecture, spec UX, 50+ stories d'implémentation). Couverture : 781 fichiers backend Java, 428 fichiers Dart, 48 fichiers dashboard TS/TSX, 3 migrations Flyway, 447 tests. 16 tranches d'audit parallèles + 7 vérifications ponctuelles indépendantes par l'auditeur principal.

---

## 1. Résumé Exécutif

Keevo est un SaaS multi-tenant, offline-first, de gestion retail/inventaire (app Flutter + monolithe modulaire Spring Boot + dashboard Next.js super-admin) destiné au commerce ouest-africain francophone. **L'épine dorsale architecturale est réellement solide** : isolation multi-tenant par schéma avec routage ThreadLocal + reset `search_path`, architecture hexagonale sur 10 domaines, triggers d'audit append-only (appliqués par la DB), JWT RS256 + bcrypt(12), socle ArchUnit/Spring Modulith, monnaie entier-XAF, vrai moteur de synchronisation delta-based avec idempotence/garantie zéro-perte-de-données, et port WhatsApp réellement plugabble (Strategy). Le code ressemble largement à sa documentation.

Cependant, l'audit a remonté **un cluster de défauts de correction et de sécurité matériels pour une release V1**, ainsi que **plusieurs FR documentées entièrement absentes** (non différées — absentes du code ET du tracker) :

- **Défauts de concurrence critiques** : la complétion de transfert a une race de double-crédit (pas de `@Version`) ; les mutations de stock n'ont pas de verrou optimiste (lost-update sur toutes les ventes/transferts/inventaires) ; la porte de synchronisation hors-ligne de 7 jours, une fois déclenchée, ne peut plus jamais se lever via l'API (verrouillage permanent du push).
- **Gaps de sécurité critiques** : clé JWT **privée** de prod installée `chmod 644` (lisible par tous → forgeable de tokens admin) ; la révocation de session (NFR12, <5 min) n'est pas implémentée pour les rôles non-employé ; `JwtAuthFilter` interpole un `tenantId` dérivé du JWT dans du SQL brut sans validation ; les endpoints admin s'appuient sur un `requireSuperAdmin()` par-méthode **sans enforcement au niveau chemin dans `SecurityConfig`** ; le garde-fou de route du dashboard Next.js (`proxy.ts`) est mal nommé et ne s'exécute jamais.
- **Gaps fonctionnels critiques** : FR22 (variantes produit), FR44 (leaderboard ventes), FR55 (export PDF/Excel), FR64 (alertes colis), FR65 (invitation employé WhatsApp), FR67 (activité employé), FR90 (annulation vente complétée + restauration stock), FR91 (suppression compte), FR92 (feedback), FR79 (dashboard revenus), FR82 (push global), FR83 (santé système) sont absents ou stub.
- **Déviation de règle métier** : `PlanType.FREE = (3 boutiques, 500 produits, 5 employés)` contredit AC1.6 `(1, 500, 3)` — les tenants Free obtiennent 3× le plafond contractuel de boutiques.
- **Cassure d'intégration** : Flutter appelle `PATCH /api/v1/products/{id}/unarchive` qui n'a **aucun endpoint backend** (404/405 au runtime).
- **Discipline de tests** : zéro E2E (`integration_test/` vide), zéro vrai `@DataJpaTest` Testcontainers (ARCH12 non respecté), et un bug de corruption silencieuse des données en production (`DayClosureDeltaProvider.totalTransactions == totalSales`) qui a survécu à des stories marquées « done ».

L'équipe montre de solides instincts d'ingénierie et une bonne hygiène documentaire, mais plusieurs stories « done » ont été livrées sans tests RED qui auraient attrapé les bugs de concurrence/données, et ~la moitié des différés documentés sont réellement encore ouverts tandis que 5 sont corrigés-mais-encore-listés (tracker périmé).

---

## 2. Score de Conformité

Scores pondérés par nombre d'exigences et sévérité des déviations vérifiées. Échelle : A (90+) → F (<40).

| Dimension | Score | Verdict |
|---|---|---|
| **Global** | **C+ (68%)** | Base solide, mais les gaps critiques de sécurité/concurrence bloquent une V1 défendable |
| **Backend fonctionnel** | B− (72%) | CRUD/sync/audit solides ; FR22/44/79/90 manquants |
| **Frontend (Flutter) fonctionnel** | C (65%) | POS/sync/inventory bien construits ; nombreuses FR secondaires absentes (vidéos, suppression, feedback) |
| **Règles métier** | C (62%) | Limites FREE erronées ; timing EOD dévie ; workflow d'annulation manquant |
| **Architecture (ARCH1–31)** | B (80%) | Hexagonal + Modulith + naming solides ; ARCH10 (pureté domaine) violé ; ArchUnit non-enforcing |
| **Sécurité (NFR7–13, ARCH20–23)** | D+ (48%) | bcrypt/RS256/audit-append bons ; exposition clé prod + pas de révocation de session + gap chemin admin sévères |
| **API (ARCH25/28/29)** | C+ (68%) | Paths/enums/XAF excellents ; enveloppe ARCH28 (`meta`) violée ; un endpoint manquant critique |
| **Workflows** | C (63%) | Flux vente/sync/inventory solides ; annulation/validation-draft/fallback-day-close cassés |
| **Tests (ARCH11–14)** | D (45%) | Couche unitaire solide (1512 @Test backend) ; E2E + Testcontainers-PG absents ; TDD RED manquant sur les bugs |
| **Alignement documentation** | B− (70%) | Trackers détaillés ; ~5 entrées « différées » périmées ; statut metadata en retard sur le code pour 11-3 |

---

## 3. Matrice de Traçabilité des Exigences (condensée — ensemble FR complet)

Légende de statut : ✅ Implémenté & vérifié · ◐ Partiel · ✗ Manquant · ⚠ Déviation

| FR | Epic | Backend | Frontend | DB/Infra | Statut | Preuve (file:line) |
|---|---|---|---|---|---|---|
| FR1 Inscription WhatsApp | 1 | ✅ | ✅ | ✅ | ✅ | `AuthController:107`; `auth_page.dart:233` |
| FR2 Provisioning tenant (KV-XXXXXX) | 1 | ✅ | n/a | ✅ | ◐ | `TenantSchemaProvisioner:724`; schéma orphelin si échec (F5) |
| FR3 Login JWT (mobile+desktop) | 1 | ✅ | ✅ | ✅ | ✅ | `JwtTokenProvider:66` RS256 |
| FR4-6 Wizard onboarding <5min | 1 | ✅ | ✅ | ✅ | ✅ | `OnboardingService:73`; 7 stratégies secteur |
| FR7 Tutoriels vidéo (FR) | 1 | n/a | ✗ | n/a | ✗ | `settings_page.dart:330` stub « Bientôt » |
| FR8-14 Compte owner + init tenant | 1 | ✅ | n/a | ✅ | ✅ | `TenantFactory`; `TenantSchemaProvisioner` |
| FR15/15b Premium Trial 6mo→FREE | 1 | ✅ | n/a | ✅ | ✅ | `SubscriptionExpiryService:48` |
| FR16 Limites plan (1 boutique/3 emp) | 1 | ⚠ | ✅ | ⚠ | ⚠ | `PlanType.FREE(3,500,5)` erroné |
| FR20 UI statut souscription | 1 | ✅ | ⚠ | n/a | ◐ | `accountStatusProvider` stub; `SuspensionBanner` non monté |
| FR21 CRUD produit (incl. photo) | 2 | ◐ | ◐ | ◐ | ◐ | photo jamais persistée (`ProductRepositoryAdapter:128`) |
| FR22 Variantes (taille/couleur) | 2 | ✗ | ✗ | ✗ | ✗ | `catalog/variant/` vide (`.gitkeep` seul) |
| FR23-24 Prix + marge nette | 2 | ✅ | ✅ | ✅ | ✅ | `PricingCalculator:39` |
| FR25 Catégories | 2 | ✅ | ✅ | ✅ | ✅ | (non testé — `CategoryController` zéro test) |
| FR26 Seuil stock | 2 | ✅ | ✅ | ✅ | ◐ | bug comptage agrégat (`MultiStoreStockRepositoryAdapter:47`) |
| FR27 Historique mouvements | 2 | ✅ | ◐ | ✅ | ◐ | typo locale `STOCK_ADJUST` (`stock_repository_impl.dart:259`) |
| FR28 Import CSV | 2 | ✅ | ✅ | ✅ | ✅ | `ImportCsvProductsUseCase:206` |
| FR29-36 Multi-boutique/warehouse/scope-employé | 3 | ✅/⚠ | ✅/⚠ | ✅ | ◐ | **FR36 scope serveur manquant** pour stock/inventory/stores |
| FR37-39 Vente POS + paiement + remise | 4 | ✅ | ✅ | ✅ | ✅ | prix panier isolé (UX10) |
| FR40 Vérif cross-boutique depuis POS | 4 | ✅ | ✗ | n/a | ◐ | callback non branché (`product_card.dart:182`) |
| FR41-43 Clôture + historique | 4 | ◐ | ✅ | ✅ | ◐ | idempotence par boutique+date (pas boutique+acteur); EOD à 00:00 WAT |
| FR44 Leaderboard | 4 | ✗ | ✗ | n/a | ✗ | aucun code nulle part |
| FR45-49 Inventaire assisté | 6 | ◐ | ◐ | ✅ | ◐ | race transfert deux-étapes; validate offline n'émet pas d'event d'audit |
| FR50-56 Dashboard/rapports/rentabilité | 7 | ✅/◐ | ✅/◐ | ✅ | ◐ | FR55 export manquant; FR56 OK |
| FR57-60 Rapports WhatsApp + config | 7 | ✅/◐ | ✅ | n/a | ◐ | port plugabble (ARCH23) ✅; FR59 pas envoyé serveur |
| FR61-64 Alertes/notifications | 8 | ✅/◐ | ◐/◐ | ✅ | ◐ | FR61/62 OK; FR64 manquant |
| FR65 Invitation employé WhatsApp | 3/8 | ✅ | ✗ | n/a | ◐ | temp-password seul, pas de partage |
| FR66-68 Rôles/activité/désactivation | 8 | ✅/◐ | ✅/◐ | ✅ | ◐ | FR67 manquant; FR68 revoke UX manquant côté client |
| FR69-76 Sync offline | 5 | ✅/◐ | ✅/◐ | ✅ | ◐ | verrouillage permanent de la porte (F1); pull 5min pas 60s |
| FR77-83 Dashboard super-admin | 9 | ✅/◐ | ✅/✗ | n/a | ◐ | FR78 partiel; **FR79/82/83 manquants** |
| FR84 Journal d'audit immuable | 1 | ✅ | n/a | ✅ | ◐ | trigger append-only ✅; REQUIRES_NEW dévie AC (audit-on-attempt) |
| FR85 SQLCipher local | 1 | n/a | ✅ | n/a | ✅ | `app_database.dart:365` PRAGMA key |
| FR86 Devise XAF | 1 | ✅ | ✅ | ✅ | ✅ | entier partout |
| FR87-89 Clients/fournisseurs | 2/4 | ✅ | ◐ | ✅ | ◐ | stats/tap-to-call manquants |
| FR90 Annulation vente + justification | 4 | ✗ | ✗ | n/a | ✗ | annulation PENDING seule; pas de restauration COMPLETED |
| FR91 Suppression compte/données | 8 | ✗ | ✗ | n/a | ✗ | aucune action de suppression |
| FR92 Formulaire feedback | 8 | ✗ | ✗ | n/a | ✗ | stub « Bientôt » |
| FR93 Rate limit/tenant | 1 | ✅ | n/a | n/a | ◐ | en mémoire mono-instance seul |

**Décompte (93 FR) :** ✅ Implémenté ≈ 38 · ◐ Partiel ≈ 31 · ⚠ Déviation ≈ 2 · ✗ Manquant ≈ 22.

---

## 4. Findings Backend (plus sévères d'abord — vérifiés)

### B-CRIT-1 — Race de double-crédit à la complétion de transfert (FR32)
- **Cat :** correction/concurrence · **Réf :** FR32 AC Step2 · **Fichier :** `catalog/stock/application/usecase/CompleteTransferService.java:50-71`; `StockTransferJpaEntity` (pas de `@Version`)
- **Attendu :** Check atomique `status==IN_TRANSIT` → crédit → bascule en COMPLETED.
- **Actuel :** Pas de `@Version`, pas de `SELECT … FOR UPDATE`, READ_COMMITTED par défaut. Deux `POST /api/v1/stock/transfers/{id}/complete` concurrents lisent chacun IN_TRANSIT, crédite chacun la destination.
- **Impact :** Inflation de stock / perte financière. **Confiance : Haute.**

### B-CRIT-2 — Lost-update de stock sur TOUTES les opérations (FR32/FR48/FR37)
- **Cat :** concurrence · **Fichier :** `catalog/stock/domain/service/StockOperationService.java:87-120`; `StockLevelJpaEntity` (pas de `@Version`); `StockLevelRepositoryAdapter:43-49` (écrasement aveugle)
- **Actuel :** read-modify-write sans verrou optimiste/pessimiste ; affecte chaque vente, transfert, validation d'inventaire. **Confiance : Haute.**

### B-CRIT-3 — La porte de sync 7 jours est un verrouillage permanent (FR74)
- **Cat :** correction/accès · **Fichier :** `SyncController.java:82-95,107`; `SyncGateCheckService:40`; `UserSyncStateRepositoryAdapter:30-42`
- **Actuel :** La porte vérifie `last_push_at` exclusivement ; seul un push **non gardé** réussi met à jour cette valeur. Un device bloqué peut pull mais ne peut plus jamais push → écritures en file bloquées pour toujours. Contredit directement « sync pour débloquer » du FR74. **Confiance : Haute.**

### B-HIGH-4 — Limites plan FREE incorrectes (FR16/AC1.6)
- **Cat :** déviation règle métier · **Fichier :** `identity/auth/domain/model/PlanType.java:11` `FREE(3, 500, 5)`
- **Attendu :** `(1, 500, 3)`. **Vérifié.** Les tenants Free obtiennent 3× boutiques, 1,67× employés. Le downgrade atterrit sur des limites permissives. **Confiance : Haute.**

### B-HIGH-5 — Révocation de session <5min non implémentée (NFR12/FR68/Story 11-6)
- **Cat :** sécurité · **Fichier :** `JwtAuthFilter.java:113-211` (pas de révocation); pas de migration V4; `sprint-status: ready-for-dev`
- **Actuel :** Seul EMPLOYEE bloqué via check DB per-request `employees.status`. Les tokens OWNER/compromis valides jusqu'à 24h. **Confiance : Haute.**

### B-HIGH-6 — `JwtAuthFilter` interpole un tenantId non validé dans du SQL brut (ARCH18/NFR10)
- **Cat :** sécurité/injection SQL · **Fichier :** `JwtAuthFilter.java:157` `"...\"" + tenantId + "\".employees WHERE user_id = ?..."`
- **Attendu :** `TenantSchema.validate(tenantId)` avant interpolation (le pattern existe ailleurs). Mitigé par signature RS256 mais contourne la porte de la couche de routage. **Vérifié.** **Confiance : Haute.**

### B-HIGH-7 — FR44 leaderboard, FR79 dashboard revenus, FR82 push global, FR83 santé système entièrement manquants
- **Cat :** fonctionnalité manquante · **Réf :** FR44/79/82/83
- **Preuve :** grep retourne zéro hit (pas de MRR/churn, pas de broadcast, pas de system-health). 5 des 7 FR de l'Epic 9 non implémentés. **Confiance : Haute.**

### B-HIGH-8 — FR90 annulation/correction de vente complétée manquante (Story 4.5)
- **Cat :** fonctionnalité manquante · **Fichier :** `commerce/sale/.../ValidateSaleService.java:144-153` jette `SALE_NOT_PENDING` pour COMPLETED
- **Attendu :** Annuler une vente COMPLETED avec justification + restauration auto du stock + `SaleCorrectedEvent`. Absent. **Confiance : Haute.**

### B-HIGH-9 — Sur-permission RBAC sur endpoints owner-only (FR36/AC6/AC9/AC10)
- **Cat :** sécurité/authz · **Fichiers :** `PendingSaleController.java:43,60` `hasAnyRole('OWNER','EMPLOYEE')`; `DayClosureController.java:30` classe-level idem
- **Attendu :** OWNER-only (EMPLOYEE→403). **Vérifié.** Un EMPLOYEE peut valider des ventes en attente & lire le chiffre d'affaires de clôture. **Confiance : Haute.**

### B-HIGH-10 — FR36 scope serveur boutique-employé manquant pour stock/inventory/stores
- **Cat :** sécurité/authz · **Fichiers :** `StockController.java:74-86` (toutes boutiques, pas de filtre EMPLOYEE); `InventorySessionController` (`storeId` client); `StoreController` (pas de `@PreAuthorize`)
- **Impact :** Un EMPLOYEE avec token peut lire/modifier le stock/inventaire d'autres boutiques, créer des boutiques via l'API directe. Le FR36 critique de sécurité n'est **pas satisfait**. **Confiance : Haute.**

### B-MED — Clôture auto EOD tire à 00:00 WAT, ignore `eodReportTime` par tenant (FR51/FR60/UX13)
- **Fichier :** `commerce/sale/application/service/DayClosureAutoScheduler.java:23` `@Scheduled(cron="0 0 23 * * *")` (23:00 UTC=00:00 WAT, ferme J-1). Le fallback 20h n'existe plus. **Confiance : Haute.**

### B-MED — `DayClosureDeltaProvider.totalTransactions == totalSales` (corruption silencieuse de données)
- **Fichier :** `sync/sync/application/provider/DayClosureDeltaProvider.java:46` mappe `row[4]` pour les deux ; le SQL n'a pas de colonne `total_transactions`. **Vérifié en production.** Différé depuis 2026-04-25. **Confiance : Haute.**

### B-MED — Rapport hebdomadaire mauvaise fenêtre de semaine pour jour configuré non-dimanche (FR52)
- **Fichier :** `reporting/report/application/service/WeeklyReportScheduler.java:127` `todayWAT.minusDays(6)` n'égal lundi que si aujourd'hui=dimanche. **Confiance : Haute.**

### B-MED — Contenu rapport omet bénéfices & marges (FR53); le « fallback SMS » n'est pas du vrai SMS (FR58)
- **Fichiers :** `DailyReportFormatter`; `FailoverWhatsAppAdapter:112-140` (Twilio envoie `whatsapp:` pas SMS). **Confiance : Haute.**

### B-MED — Schéma tenant orphelin si échec post-provisioning (AC1.2 AC4)
- **Fichier :** `identity/auth/application/service/RegistrationService.java:64-112`; le provisioner utilise `dataSource.getConnection()` hors transaction Spring → DDL non rollbackée si une étape ultérieure échoue. **Confiance : Haute.**

### B-MED — Audit-on-attempt dévie AC1.8 (REQUIRES_NEW)
- **Fichier :** `AuditLogRepositoryAdapter.java:76` `@Transactional(REQUIRES_NEW)` → l'audit persiste même si la tx principale est rollbackée, contredisant AC1.8 « même tx ». Choix de design documenté nécessitant réconciliation d'AC. **Confiance : Haute.**

### B-MED — La couche domaine a des dépendances Spring (violation ARCH10)
- **Fichiers :** `catalog/stock/domain/port/out/*Repository.java` importent `org.springframework.data.domain.{Page,Pageable}`; `StockOperationService` annoté `@Service`/`@Transactional`. Les *entités* domaine sont propres. **Confiance : Haute.**

### B-MED — Transfert 500 au lieu de 404/422 (ARCH/AC7); comptage stock-bas agrégat erroné (FR26)
- `GlobalExceptionHandler.java:212-260` manque `TRANSFER_NOT_FOUND`(→404)/`TRANSFER_INVALID_STATUS`(→422) → défaut 500. `MultiStoreStockRepositoryAdapter.java:47` `COALESCE(NULLIF(p.minimum_threshold,0),5)` compte ruptures & produits sans seuil comme « bas ». **Tous deux vérifiés.**

---

## 5. Findings Frontend (Flutter — plus sévères)

### F-CRIT-1 — Annulation de vente non implémentée (FR90)
- **Fichier :** `pos/presentation/page/pending_sale_detail_page.dart:243` `_doCancel(saleId, '')` — justification vide, pas d'input/≥10, pas de restauration stock, pas de `SALE_CANCELLED`. Pas d'action d'annulation sur les ventes complétées. **Confiance : Haute.**

### F-CRIT-2 — Ventes brouillon enregistrées COMPLETED au lieu de PENDING_VALIDATION (Story 4.3)
- **Fichier :** `pos/presentation/provider/record_sale_notifier.dart:96-111` hardcoded `status:'COMPLETED'` et promeut immédiatement les drafts. Court-circuite le workflow de validation admin. **Confiance : Haute.**

### F-CRIT-3 — Validation inventaire offline n'émet aucun event/mouvement/seuil d'audit (FR48/Story 6.4)
- **Fichier :** `inventory/presentation/provider/validate_inventory_provider.dart:79-111` — met à jour `stock_levels.quantity` seulement ; pas de `StockAdjustedEvent`, pas de `StockMovement ADJUSTMENT`, pas de check de seuil. L'inventaire offline ne laisse aucune trace d'audit locale. **Confiance : Haute.**

### F-HIGH-4 — Enqueue `sync_queue` hors de la transaction Drift (atomicité vente) (AC 4-1/5-6)
- **Fichier :** `pos/data/repository/sale_repository_impl.dart:41-84` — la txn `insertAll` couvre sale+items+stock+movements seulement ; `queueOperation` appelé après, hors txn. Crash entre → perte silencieuse de données. **Confiance : Haute.**

### F-HIGH-5 — FR40 vérif cross-boutique absente du POS
- **Fichier :** `pos/presentation/widget/product_card.dart:182` `onVerifierStock` déclaré, jamais branché. **Confiance : Haute.**

### F-HIGH-6 — FR7/FR65/FR67/FR91/FR92 manquants (tranche auth/onboarding)
- Tutoriels vidéo, invitation WhatsApp, activité employé, suppression compte, feedback — tous stubs/no-ops. **Confiance : Haute.**

### F-HIGH-7 — FR68 UX de révocation de session côté client manquante
- **Fichier :** `core/network/auth_interceptor.dart:74-139` ne gère que `TOKEN_EXPIRED`; `ACCOUNT_INACTIVE`/`SESSION_REVOKED` passent au travers sans teardown de session. **Confiance : Haute.**

### F-HIGH-8 — FR20 bannières trial/suspension jamais affichées
- `account_status_provider.dart:26` stub retourne `active`; `SuspensionBanner` jamais monté. **Confiance : Haute.**

### F-HIGH-9 — `FlutterSecureStorage` utilisé avec options par défaut ; Bearer JWT logué en clair
- **Fichiers :** 6 sites `const FlutterSecureStorage()` ad-hoc (pas de `AndroidOptions(encryptedSharedPreferences:true)`/`IOSOptions`); `auth_provider.dart:102` `LogInterceptor(requestHeader:true)` non gated par `kDebugMode`. URL API base par défaut en `http://localhost`. **Confiance : Haute.**

### F-HIGH-10 — Écritures store offline bypassent la sync_queue (ARCH19/FR30)
- **Fichier :** `stores/data/repository/store_repository_impl.dart:48-116` — pas de `queueOperation` (contraste clients/suppliers qui le font). Create/deactivate store offline n'atteint jamais le backend → perte silencieuse. **Confiance : Haute.**

### F-MED — Fallback day-close 20h startup-only, exclut EMPLOYEE, pas de WhatsApp (UX13); pull périodique 5min pas 60s (FR76); leaderboard absent (FR44); typo type-mouvement `STOCK_ADJUST` (FR27); recherche catalogue pas de debounce/pas fuzzy (UX9); route `/pos/pending` non gardée par rôle; 41 `Colors.white` hardcodés cassent le dark mode (UX20). **Confiance : Haute.**

---

## 6. Incohérences Cross-Système

### X-CRIT-1 — Flutter appelle `PATCH /api/v1/products/{id}/unarchive` inexistant (M1)
- **Backend :** `ProductController.java:161` a seulement `/{id}/archive`; `UnarchiveProductUseCase` existe mais est branché **seulement** via `ProductSyncHandler`. **Flutter :** `remote_product_datasource.dart:69` appelle `/unarchive` directement → 404/405 runtime en online. **Vérifié.** **Confiance : Haute.**

### X-HIGH-2 — Param query historique-transferts `dest` vs `destination` (M2)
- **Backend :** `StockTransferController.java:111` attend `destination`; **Flutter :** `remote_stock_transfer_datasource.dart:74` envoie `dest`. Le filtre ne s'applique pas silencieusement ; `productId` aussi droppé. **Vérifié.** **Confiance : Haute.**

### X-MED-3 — Pas de `ReportsDeltaProvider` → rapports jamais pull en DB locale (M3)
- `rest_sync_service.dart:235` upsert `entities['reports']` mais aucun provider n'émet cette clé → rapports indisponibles offline. **Confiance : Haute.**

### X-MED-4 — `DioApiService` lit le mauvais champ d'enveloppe (M4)
- `api_service.dart:38` `domainCode = data['code']` (nom statut HTTP, pas code domaine). Bug localisé ; `auth_interceptor` lit `domainCode` correctement. **Confiance : Haute.**

### X-MED-5 — Enveloppe de pagination ARCH28 violée (M5)
- L'enveloppe succès est `{data, timestamp}` — **pas de `meta{page,total}`**; 5+ formes de pagination différentes par endpoint. L'enveloppe erreur est conforme. **Confiance : Haute.**

### X-LOW-6 — Convention header `X-Keevo-*` inutilisée (seulement `X-Device-Id`); auth/onboarding bypassent l'enveloppe succès (M6/M8). Pas de cassure runtime. **Confiance : Haute.**

---

## 7. Fonctionnalités Manquantes (consolidé — non différées, réellement absentes)

| Exigence | Backend | Frontend | Dashboard | Sévérité |
|---|---|---|---|---|
| FR7 Tutoriels vidéo (FR, 2min/module) | n/a | ✗ stub | n/a | Haute |
| FR22 Variantes produit (taille/couleur) | ✗ | ✗ | n/a | Haute |
| FR44 Leaderboard ventes | ✗ | ✗ | n/a | Moyenne |
| FR55 Export PDF/Excel (Plan Payant) | ✗ | ✗ | n/a | Moyenne |
| FR64 Alertes statut colis (Payant) | ✗ | n/a | n/a | Basse (Growth) |
| FR65 Invitation employé WhatsApp | ✅ (temp-pw) | ✗ | n/a | Moyenne |
| FR67 Tracking activité employé | ✗ | ✗ | n/a | Moyenne |
| FR79 Dashboard revenus (MRR/churn) | ✗ | n/a | ✗ | Haute |
| FR82 Push global à tous tenants | ✗ | n/a | ✗ | Moyenne |
| FR83 Dashboard santé système | ✗ | n/a | ✗ partiel | Moyenne |
| FR90 Annulation vente complétée | ✗ | ✗ | n/a | Haute |
| FR91 Suppression compte/données | ✗ | ✗ | n/a | Haute (RGPD) |
| FR92 Feedback/signalement | ✗ | ✗ stub | n/a | Moyenne |
| Endpoint REST `/products/{id}/unarchive` | ✗ | (l'appelle) | n/a | Critique |
| `ReportsDeltaProvider` | ✗ | (l'attend) | n/a | Moyenne |

---

## 8. Implémentations Incorrectes (déviations vérifiées)

1. **`PlanType.FREE(3,500,5)`** vs spec `(1,500,3)` — mauvais plafond appliqué serveur-wide (B-HIGH-4).
2. **Scheduler EOD** `0 0 23 * * *` (00:00 WAT, J-1) vs AC 20:00 WAT; ignore `eodReportTime` (B-MED).
3. **Idempotence day-close** clé `storeId+date` vs AC `storeId+actorId+date` — un employé bloque tous (commerce F7).
4. **Conflit LWW « previous »** ordonné par `processedAt` serveur pas `clientTimestamp` — attribution d'audit peu fiable (sync F3).
5. **`STOCK_ADJUST`** type mouvement local au lieu de `ADJUSTMENT` — filtre historique cassé + violerait CHECK DB si persisté (F-CRIT-3/Med).
6. **`totalTransactions`** duplique `totalSales` dans le payload sync (B-MED).
7. **Comptage stock-bas agrégat** compte ruptures & produits sans seuil comme « bas » via `COALESCE(...,5)` (B-MED).
8. **Porte 7 jours** clé sur `deviceId` fourni par le client, contournable trivialement en le rotant (sync F2).

---

## 9. Comportements Cachés (non documentés/contradictoires)

- **Audit-on-attempt** : `REQUIRES_NEW` signifie que les entrées d'audit persistent pour des opérations rollbackées — contredit AC1.8 et peut induire en erreur les analyses forensiques.
- **DayClosureWhatsAppListener** désactivé par défaut (`@ConditionalOnProperty matchIfMissing=false`); hardcoded `DEFAULT_OWNER_PHONE="+243000000000"` — mort mais mine si jamais réactivé.
- **DayCloseButton / PosSpeedDial** sont du code mort (jamais rendus); day-close atteignable seulement via Reports.
- **`accountStatusProvider`** retourne hardcodé `active` — les bannières trial/suspension passent les tests via override mais ne s'affichent jamais en prod.
- **`UserSyncStateDdlInitializer`** duplique encore le DDL Flyway V1 `user_sync_state` — deux sources de vérité.
- **`WhatsAppPort.isConfigured()`** défaut `true` — risque de vrai rapport envoyé silencieusement si un adapter oublie d'override.
- **Staging réutilise les clés JWT/Firebase prod** (selon deferred-work [10-5]) — rayon d'impact.
- **Module sync utilise `@EventListener` (pas `@TransactionalEventListener`)** avec une Javadoc déclarant à tort « after commit ».

---

## 10. Findings Sécurité (consolidé — plus risque d'abord)

| # | Sévérité | Finding | Preuve | Réf |
|---|---|---|---|---|
| S1 | Critique | Clé JWT **privée** de prod `chmod 644` (lisible par tous) | `deploy-backend.yml:112` (staging corrigé en 600) | NFR11/ARCH16 |
| S2 | Haute | Révocation de session <5min non implémentée (non-employé) | pas de `TokenRevocationPort`/V4 | NFR12/FR68 |
| S3 | Haute | `SecurityConfig` pas de `hasRole("SUPER_ADMIN")` au niveau chemin sur `/api/v1/admin/**` | `SecurityConfig.java:91-96` | défense-en-profondeur |
| S4 | Haute | Garde-fou de route dashboard jamais exécuté (fichier mal nommé `proxy.ts`) | pas de `middleware.ts` | bypass auth |
| S5 | Haute | `JwtAuthFilter` `tenantId` non validé dans SQL brut | `JwtAuthFilter.java:157` | ARCH18/NFR10 |
| S6 | Haute | EMPLOYEE peut valider ventes en attente & lire chiffre clôture | `PendingSaleController:43,60`; `DayClosureController:30` | FR36/AC6/AC9/AC10 |
| S7 | Haute | FR36 scope serveur manquant pour stock/inventory/stores | `StockController:74-86`; `StoreController` pas de guard | FR36 |
| S8 | Moyenne | `FlutterSecureStorage` options par défaut (pas de EncryptedSharedPreferences) | 6 sites | ARCH21 |
| S9 | Moyenne | Bearer JWT logué en clair (tous builds) | `auth_provider.dart:102` | ARCH21 |
| S10 | Moyenne | JWT dashboard en `sessionStorage` (XSS-extractible) | `auth-context.tsx:11` | Story 9.1 |
| S11 | Moyenne | `POSTGRES_PASSWORD` pas fail-fast; force admin-pw exact-match seul | `ProdSecretsValidator:54`; `application.yml:17` | NFR11/[10-3] |
| S12 | Moyenne | Pas de garde auth global Flutter ; les deep links bypassent le check de session | `app_router.dart:336` | ARCH9 |
| S13 | Basse | Phone PII en SharedPreferences clair; `/actuator/prometheus` requiert JWT; `DeviceTokenController.deleteToken` pas de check d'ownership | divers | NFR7/FR93 |

**Approfondissement isolation multi-tenant :** Le modèle est sain en design — `TenantContext` ThreadLocal posé dans `JwtAuthFilter:130`, nettoyé dans `finally:204`; `SchemaAwareMultiTenantConnectionProvider` applique `SET search_path TO "kv_xxx", public` et reset à `public` sur release avec une porte regex `^kv_[a-z0-9]{6}$`. Mais (a) la requête directe `JdbcTemplate` dans `JwtAuthFilter:157` contourne cette porte (S5), (b) le seul test d'isolation exerce `audit_log` via JDBC brut et **se skip silencieusement en CI** quand `localhost:5444` est injoignable — le routage JPA est « validé implicitement » (pas réellement). L'isolation des entités métier (products/sales/stock) à travers l'ORM n'est **pas prouvée**. Risque : une régression dans le connection provider ne serait pas attrapée.

---

## 11. Findings Performance

- **P1 (Haute) :** `AdminTenantService:219` requête N+1 `schema_name` par ligne tenant malgré la colonne déjà dans le SELECT principal — O(N) requêtes supplémentaires sur la liste tenants.
- **P2 (Moyenne) :** `SaleSpringRepository:34` `findByStoreIdAndStatus` méthode Spring-Data nue (pas de `@Query`/JOIN FETCH) → N+1 sur items ventes en attente.
- **P3 (Moyenne) :** Recherche catalogue **pas de debounce** (commentaire faussement 300ms) — re-quête Drift par frappe clavier (UX9).
- **P4 (Basse) :** Fenêtre de batch stock-alert en mémoire `ConcurrentHashMap` par-JVM — casse la consolidation sous scale horizontal.
- **P5 (Basse) :** Pas de Timers/Counters Micrometer dans le module sync (latence NFR29 non mesurée serveur).
- **Conformité NFR :** NFR1/4 (fluidité offline, <100Mo) structurellement OK; NFR2/3 (<60s sync, <3s dashboard) non vérifiées runtime; NFR5 (<500ms recherche) à risque via P3; NFR14-17 (scalabilité) non testés.

---

## 12. Findings Architecture

- **ARCH5/7 (hexagonal + décomposition domaine) :** Largement honorés; 10 domaines avec layering `domain/application/adapter`.
- **ARCH10 (domaine zéro dépendance framework) : VIOLÉ** — `catalog/stock/domain/port/out/*` importent Spring `Page`/`Pageable`; `StockOperationService`/`DefaultTransferValidationStrategy` sont `@Service`/`@Transactional`. Les *entités* domaine sont propres.
- **ARCH8 (Riverpod 3.0) :** Le client utilise Riverpod **2.x** (`flutter_riverpod ^2.5.1`); le DI core est manuel `Provider`, pas `@riverpod` (~moitié respecté).
- **ARCH11/14 (TDD NON-NÉGOCIABLE) : PARTIELLEMENT RESPECTÉ** — couche unitaire solide, mais plusieurs classes livrées (`CategoryController`, `CompleteTransferService`, les 3 adapters WhatsApp, 7 stratégies de tri rentabilité) ont **zéro test**, et des bugs connus (`DayClosureDeltaProvider`, N+1) ont survécu à des stories « done » → les tests RED n'ont pas été écrits.
- **ARCH12 (@DataJpaTest + Testcontainers PG) : NON RESPECTÉ** — les 4 `@DataJpaTest` utilisent H2 embarqué; le seul fichier Testcontainers se connecte à un PG existant (`localhost:5444`), pas `@Container`. CI-unsafe.
- **ARCH13 (integration_test E2E Flutter) : NON RESPECTÉ** — `integration_test/` vide; deps `integration_test`/`riverpod_test` absentes.
- **ARCH18 (schema-per-tenant via JDBC, pas Flyway) :** Correctement implémenté par-tenant; **contradit la décision Epic-10 Flyway-propriétaire-du-schéma** seulement sur la table `user_sync_state` (initializer DDL dupliqué).
- **ARCH22/FR93 (rate limit 100/min/tenant) :** Implémenté mais en mémoire mono-instance — sous-compte dès le scale-out.
- **ARCH24-29 (naming/enveloppe) :** DB snake_case/UUID/integer-XAF excellents; **enveloppe ARCH28 `meta{page,total}` violée**.
- **ArchUnit/Modulith (Story 10.6) :** Présents mais **mode-warning (non-asserting)** — les frontières peuvent dériver silencieusement jusqu'à l'enforcement Epic 12.6.

---

## 13. Dette Technique

- **Tracker différés périmé :** ~5 items corrigés-mais-encore-listés (RBAC cancelSale, B3.1/B3.2/B3.4 UI, audit append-only 11-3) vs ~12 réellement encore ouverts. `sprint-status.yaml` en retard sur le code pour 11-3 (implémenté, marqué in-progress).
- **Packages morts/vides :** `com/keevo/common` (0 fichiers); `lib/features/products` (0 .dart, vraisemblablement fusionné dans catalog); `catalog/variant/` (`.gitkeep` seul); feature `debug` livrée en lib sans gating.
- **TODOs périmés :** `CategoryRepository.java` (6), `SubscriptionExpiryService:84` (pas d'event downgrade), `SelectTenantService:107` (colonne OWNER `first_name` manquante), `JpaCategoryRepository:81,100` (TODOs event audit).
- **Logique dupliquée :** ternaire role-routing dupliqué à `app_router.dart:275,366`; `JwtAuthFilter` double-enregistrement (`@Component` + `addFilterBefore`, mitigé par `OncePerRequestFilter`).
- **Pin partiel `docker-java` 3.4.1** (api+transport seuls, risque de skew `core`).
- **Hardening backup :** mécanisme NFR22 OK (daily 02:00, rotation 30 jours) mais pas d'alerting `OnFailure`, S3 non IAM-locké, smoke-test restauration bloqué (AC4).

---

## 14. Correctifs Recommandés (ordonnés par priorité)

**À corriger avant toute release V1 (Critique/Haute, sécurité & intégrité-données) :**
1. Ajouter `@Version` à `StockTransferJpaEntity` + `StockLevelJpaEntity` (ou `SELECT … FOR UPDATE` / `UPDATE … WHERE status='IN_TRANSIT'` conditionnel) — tue B-CRIT-1/2.
2. Corriger la porte 7 jours : `SyncGateCheckService.isStalePush` → vérifier `GREATEST(last_push_at, last_pull_at)` pour qu'un pull réussi lève la porte push — tue B-CRIT-3.
3. `deploy-backend.yml:112` → `chmod 600 keys/private_key.pem` (séparer du public) — tue S1.
4. Implémenter Story 11-6 : V4 `tokens_valid_after` + `TokenRevocationPort` + cutoff iat dans `JwtAuthFilter` — tue S2/B-HIGH-5.
5. `SecurityConfig` : ajouter `.requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")` — tue S3.
6. `JwtAuthFilter:157` : `TenantSchema.validate(tenantId)` avant interpolation — tue S5.
7. Resserer RBAC : `PendingSaleController`/`DayClosureController`/`StockController`/`InventorySessionController`/`StoreController` à OWNER-only + `storeId` dérivé du JWT pour EMPLOYEE — tue S6/S7/B-HIGH-9/10.
8. Ajouter l'endpoint `PATCH /api/v1/products/{id}/unarchive` OU router Flutter via sync — tue X-CRIT-1.
9. Renommer dashboard `proxy.ts` → `middleware.ts` (`export function middleware`) — tue S4.
10. Corriger `PlanType.FREE` à `(1, 500, 3)` — tue B-HIGH-4.

**Fonctionnel/correction haute priorité :**
11. Implémenter FR90 annulation vente (backend + Flutter) avec justification + restauration stock `SALE_CANCELLED`.
12. Corriger `record_sale_notifier.dart:96-111` pour soumettre `PENDING_VALIDATION` pour paniers draft.
13. Corriger validate inventaire offline pour émettre `StockMovement(ADJUSTMENT, source=INVENTORY, inventorySessionId)` + check seuil.
14. Déplacer l'enqueue `sync_queue` à l'intérieur de la txn Drift dans `sale_repository_impl.dart`.
15. Corriger `DayClosureDeltaProvider.java:46` (dropper la clé ou ajouter la colonne) + fenêtre-semaine scheduler hebdo + timing EOD.
16. Flutter : renommer `dest`→`destination` historique transferts; ajouter `ReportsDeltaProvider`; corriger `api_service.dart:38` pour lire `domainCode`; centraliser un `FlutterSecureStorage` hardené; redacter logs Bearer; imposer défaut HTTPS.
17. Brancher `accountStatusProvider` + monter `SuspensionBanner`; ajouter branche `ACCOUNT_INACTIVE`/`SESSION_REVOKED` dans `AuthInterceptor`.
18. Implémenter FR79/FR82/FR83 (revenus/push-global/santé super-admin) — actuellement 5/7 FR Epic-9 manquants.

**Discipline tests (ARCH11–14) :**
19. Ajouter une base `@Testcontainers` `PostgreSQLContainer`; migrer les 4 `@DataJpaTest` + test d'isolation vers elle; étendre le test d'isolation aux entités métier via JPA.
20. Ajouter E2E `integration_test` Flutter (login→vente→clôture→sync-offline) + dep `riverpod_test`.
21. Écrire les tests RED manquants pour `CategoryController`, `CompleteTransferService`, adapters WhatsApp, stratégies rentabilité.

**Hygiène (Basse) :**
22. Réconcilier les entrées différées périmées; supprimer `UserSyncStateDdlInitializer`, packages vides `common`/`products`, TODOs périmés.
23. Standardiser l'enveloppe pagination à ARCH28 `meta{page,total}`.
24. Convertir ArchUnit/Modulith du mode-warning à un enforcement ratchet (Epic 12.6).

---

## 15. Évaluation Finale de Conformité

**Keevo est architecturalement mature mais pas encore prêt pour la release V1.** La qualité d'ingénierie déployée — isolation multi-tenant par schéma, layering hexagonal, triggers d'audit append-only, un vrai moteur de sync delta avec idempotence et sémantique zéro-perte-de-données, un Strategy WhatsApp plugabble, et la discipline monnaie entier-XAF — est bien au-dessus d'un SaaS early-stage typique. La documentation est inhabituellement exhaustive et le suivi du travail différé est globalement honnête.

**Cependant, l'audit identifie quatre classes de risque bloquantes :**

1. **Races d'intégrité-données** (B-CRIT-1/2/3) : l'absence de verrou optimiste sur stock et transferts, plus le verrouillage permanent de sync 7 jours, signifient que l'activité concurrente POS/transfert peut corrompre le stock et bloquer définitivement les écritures offline. Ce sont les findings les plus sévères et ils sont mécaniquement certains depuis le code.
2. **Exposition sécurité** (S1–S7) : une clé de signature JWT prod lisible par tous, révocation de session non implémentée, pas d'enforcement admin au niveau chemin, tenantId non validé en SQL, et RBAC où l'EMPLOYEE dépasse ses droits, minent collectivement la garantie de sécurité multi-tenant qui est la promesse centrale de NFR10 (« aucune fuite cross-tenant, 100% »). Le *design* d'isolation est sain ; les *coutures d'enforcement* ne le sont pas.
3. **FR documentées absentes** (FR22/44/55/64/65/67/79/82/83/90/91/92) : ~22 des 93 FR sont manquantes ou stub — plusieurs (FR91 RGPD, FR90 correction vente, FR79 revenus) sont centrales à la proposition produit, pas des fonctionnalités d'angle.
4. **Gaps de discipline tests** (ARCH11–14) : zéro E2E, zéro vrai test repository Testcontainers-PG, et un bug de corruption silencieuse des données en production qui a survécu au statut « done » — indiquant que les tests RED n'ont pas été écrits là où ça comptait le plus.

**Verdict net :** Le codebase est une base fondation crédible et bien structurée qui a besoin **d'un sprint de hardening ciblé** (verrous concurrence + les 7 correctifs sécurité + les 10 items endpoints/FR manquants) et **d'une élévation de l'infrastructure de tests** (Testcontainers + E2E) avant de pouvoir défendre la conformité avec sa propre spécification BMAD. Une V1 défendable est atteignable ; ce n'est pas l'état actuel.

---

**Notes de confiance & couverture :** Cet audit est statique (lecture du code + source des tests; pas d'exécution `mvn test`/`flutter test` ou DB live). Tous les findings Critique/Haute portent une preuve `file:line` directe et ont été re-vérifiés indépendamment par l'auditeur principal pour les 7 items les plus impactants. Les items marqués « Unverified » dans les rapports par-tranche concernent le comportement runtime (timing scheduler sur horloge live, FCM sur vrai device, statut de passage `mvn test`) et sont signalés comme tels — ils n'affectent pas la correction des findings statiques. Les fichiers de preuve par-tranche sont persistés sous `/tmp/keevo-audit/` (16 fichiers) pour traçabilité.
