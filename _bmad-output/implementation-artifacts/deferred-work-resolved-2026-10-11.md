# Deferred work — resolved entries archive (2026-10-11)

Archive of the 22 entries of `deferred-work.md` found already resolved by the 2026-10-11 redistribution (plan: scratchpad `deferred-routing-plan.md`, code checked at deploy / 78dc9e6). Nothing to do; kept for traceability. Line numbers (L) refer to the pre-redistribution `deferred-work.md`.

## #38 — L68 SuspensionBanner trialExpired contrast 1.6:1

- Source: CR v1s-14-9 (2026-07-22)
- Evidence: now uses cs.warningContainer + AppTheme.onWarning (UI-3, 1041827)
- Original entry (L68):

> **`SuspensionBanner` variante `trialExpired` illisible (contraste ~1.6:1)** : texte blanc (`Colors.white`) sur fond ambre `AppTheme.warning` (`0xFFFCC419`) — bien en-dessous du minimum WCAG AA (4.5:1 texte normal, 3:1 UI/large). Le widget `suspension_banner.dart` existait déjà (non touché par le diff de cette story), mais n'était monté nulle part avant Story 14.9 — le défaut devient donc visible/live pour la première fois. Fix trivial (texte foncé sur fond ambre, ou fond ambre plus saturé), mais hors du diff strict de cette story. Fichier : `keevo/app/lib/features/settings/presentation/widget/suspension_banner.dart:47-56`.

## #41 — L74 ReportsDeltaProvider capped at 1000 rows

- Source: CR v1s-14-8 (2026-07-22)
- Evidence: now keyset-paged via DeltaPageQuery (4c188fd)
- Original entry (L74):

> **`ReportsDeltaProvider` plafonné à 1000 lignes sans pagination par curseur** : au-delà de 1000 rapports créés entre deux pulls, les lignes excédentaires sont silencieusement perdues (le watermark avance à `now()` sans égard à la troncature). Pattern pré-existant mirroré à l'identique depuis `DayClosureDeltaProvider.java` (fichier non touché par cette story, même défaut). Fichier : `ReportsDeltaProvider.java:41`.

## #44 — L80 Dead OWNER pending-sale validation code

- Source: CR v1s-13-6 (2026-07-22)
- Evidence: v1s-15-5 decision (b): kept as fallback, header comments present
- Original entry (L80):

> **Code mort validation OWNER (ventes brouillon)** : `PendingSalesPage`, `PendingSaleDetailPage`, `ValidateSaleService` (branche OWNER manuel), `SaleValidationCascadeService`, `pendingSalesCountProvider`, la variante ambre de `SaleSuccessPage` (`saleStatus == 'PENDING_VALIDATION'`) sont tous fonctionnels mais **plus jamais atteints** depuis le 2026-05-03 (commit `30bdd07`, redesign du flux brouillon vers inline stock + auto-promotion). Aucun chemin client ne produit de vente `PENDING_VALIDATION`. **Décision v1s-15-5 (2026-07-23) — Option (b) conservé comme outil de secours manuel** : commentaires de tête de classe ajoutés sur `ValidateSaleService.java` et `pending_sales_page.dart` expliquant l'état "actuellement inatteignable, conservé comme outil de secours". Les services/controllers backend et les pages Flutter sont conservés tels quels, prêts à être réactivés si un futur flow (import CSV, API d'administration) réintroduit `PENDING_VALIDATION`. Fichiers Flutter : `pending_sales_page.dart`, `pending_sale_detail_page.dart`, `pending_sales_count_provider` (pos_providers.dart), `sale_success_page.dart` (variante ambre). Backend : `ValidateSaleService.java`, `SaleValidationCascadeService.java`, `PendingSaleController.java`.

## #51 — L87 Emoji 🔶 accessibility on checkout label

- Source: CR v1s-13-6 (2026-07-22)
- Evidence: emoji replaced by Icon + 'Vente brouillon' text (UI-3)
- Original entry (L87):

> **Sémantique accessibilité emoji 🔶** `checkout_page.dart:311` : séparer l'icône du label via `Semantics`/`ExcludeSemantics` pour screen readers. Cosmétique.

## #58 — L104 D8 no tenantId=null EMPLOYEE test

- Source: CR v1s-12-5 (2026-07-21)
- Evidence: entry itself: redundant with AC3 (same IAE->401 path); no action
- Original entry (L104):

> **[v1s-12-5] D8 — Pas de test `tenantId=null` EMPLOYEE** : `JwtAuthFilterTest.java` — redondant avec AC3 (même code path `IllegalArgumentException` → 401).

## #59 — L105 D9 no tenantId="" EMPLOYEE test

- Source: CR v1s-12-5 (2026-07-21)
- Evidence: entry itself: redundant with AC3; no action
- Original entry (L105):

> **[v1s-12-5] D9 — Pas de test `tenantId=""` EMPLOYEE** : `JwtAuthFilterTest.java` — redondant avec AC3.

## #73 — L123 Prod JWT private key chmod 644

- Source: CR 10-5 (2026-06-19)
- Evidence: marked RÉSOLU in file: v1s-12-1 (2026-07-21)
- Original entry (L123):

> **[10-5] prod `deploy-backend.yml` : clé privée JWT en `chmod 644`** ✅ **RÉSOLU** — story `v1s-12-1` (2026-07-21) : split `chmod 600` + `chown 1001:1001` (UID keevo pinné Dockerfile) + assertion pre-flight + grep lint AC4. Plus world-readable check Java (ProdSecretsValidator) en défense-en-profondeur.

## #79 — L132 script_stop:false + systemctl status || true

- Source: CR 10-4 (2026-06-19)
- Evidence: script_stop: true at :438; only the informational status call keeps || true
- Original entry (L132):

> **[10-4] `script_stop:false` + `systemctl status || true`** (`deploy-backend.yml`) : un install cassé (sudo/systemd) rapporte « succès ». Gater proprement l'échec.

## #84 — L140 Checklist: ADMIN_PASSWORD rotation via env is a no-op

- Source: CR 10-3 (2026-06-19)
- Evidence: §4.1 now says re-hash is skipped and routes through the change-password API
- Original entry (L140):

> **[10-3] DEPLOYMENT_CHECKLIST — rotation ADMIN_PASSWORD via env = no-op** : pour un utilisateur admin déjà créé, changer `ADMIN_PASSWORD` n'a aucun effet (pas de re-hash). La doc doit renvoyer vers l'API change-password.

## #85 — L144 Commit hygiene (10-3 change inside 10-2 diff)

- Source: CR 10-2 (2026-06-19)
- Evidence: historical process note; nothing to fix in code
- Original entry (L144):

> **[10-2] Commit hygiene — `admin.password` story 10-3 change dans working tree 10-2** : le défaut `Admin@1234!` a été supprimé dans `application.yml` avec un commentaire `Story 10.3`, mais ce changement se retrouve dans le diff de la story 10-2. Non bloquant fonctionnellement ; à ne pas reproduire dans les prochaines stories.

## #106 — L177 SESSION_REVOKED vs concurrent refresh race

- Source: CR v1s-12-7 (2026-07-21)
- Evidence: SESSION_REVOKED handled first, _isRefreshing guard gone (1041827)
- Original entry (L177):

> **[v1s-12-7] Race condition SESSION_REVOKED vs TOKEN_EXPIRED refresh concurrent** [`auth_interceptor.dart:91-143`] : si Request A est en refresh (`_isRefreshing=true`) et Request B reçoit SESSION_REVOKED, le garde `!_isRefreshing` (ligne 91) empêche B d'entrer dans le handler SESSION_REVOKED → B tombe en `handler.next(err)` sans nettoyage. Auto-cicatrisant (prochaine requête recevra aussi 401 avec `_isRefreshing=false`). Fix : flag `_sessionDead` cross-path, à traiter dans story de suivi sécurité.

## #110 — L184 [CRITIQUE] sync push bypasses RBAC hardening

- Source: CR v1s-12-6 (2026-07-21)
- Evidence: marked RÉSOLU by v1s-12-8 in file
- Original entry (L184):

> **[v1s-12-6][CRITIQUE] `/api/v1/sync/push` contourne intégralement le durcissement RBAC de cette story** [`sync/sync/application/handler/CancelSaleSyncHandler.java`, `ValidateSaleSyncHandler.java`, `StockAdjustSyncHandler.java`, `TransferSyncHandler.java`] : ces 4 handlers appellent directement les use cases (`cancelPendingSaleUseCase`, `validateSaleUseCase`, `adjustStockUseCase`/`recordStockEntryUseCase`, `transferStockUseCase`) sans passer par les controllers REST durcis par cette story, et sans aucun contrôle de rôle ni de scope storeId (`CancelSaleSyncHandler`/`ValidateSaleSyncHandler` passent `assignedStoreId=null` codé en dur, quel que soit le rôle réel de l'appelant). Un EMPLOYEE peut ainsi annuler/valider des ventes de n'importe quelle boutique et ajuster du stock/initier des transferts hors de sa boutique, simplement en passant par la synchronisation offline plutôt que par l'API REST directe — annulant une bonne partie de la valeur sécurité de Story 12.6 (AC2/AC5/AC7, FR36, B-HIGH-9/10). ✅ **RÉSOLU par Story v1s-12-8 (2026-07-23)** : `extractAssignedStoreId()` + `isOwnerRole()` factorisés dans `AbstractSyncOperationHandler`, les 4 handlers lisent maintenant le SecurityContext au lieu de passer `null`.

## #111 — L185 StockTransferController.complete() without destination scope

- Source: CR v1s-12-6 (2026-07-21)
- Evidence: marked RÉSOLU by v1s-12-9 in file
- Original entry (L185):

> **[v1s-12-6] `StockTransferController.complete()` sans scope storeId destination** [`StockTransferController.java:99-107`, `CompleteTransferService.java`] : aucun contrôle ne vérifie que l'EMPLOYEE qui réceptionne un transfert est bien affecté à la boutique destination — n'importe quel EMPLOYEE peut compléter n'importe quel transfert `IN_TRANSIT`. Contraire à FR36 et à la justification même de l'AC7 (« l'employé à la boutique destination doit pouvoir réceptionner »). Pré-existant (Story 3.3), explicitement laissé hors scope par cette story (Task 6.3). ✅ **RÉSOLU par Story v1s-12-9 (2026-07-24)** : `extractAssignedStoreId()` ajouté à `StockTransferController`, `CompleteTransferCommand` porte `assignedStoreId`, `CompleteTransferService.execute()` vérifie `assignedStoreId == destinationStoreId` avant toute transition de statut. Flutter : condition d'affichage du bouton "Réceptionner" renforcée pour EMPLOYEE.

## #127 — L213 kDebugMode import breaks ~68 test files

- Source: CR v1s-13-4 (2026-07-22)
- Evidence: marked RÉSOLU by v1s-13-5 in file
- Original entry (L213):

> **[v1s-13-4] `kDebugMode` non résolu casse la compilation d'~68 fichiers de test** [`keevo/app/lib/core/router/app_router.dart:139`] — **RÉSOLU par v1s-13-5** : la story v1s-13-5 avait besoin d'exécuter `sale_detail_page_test.dart` (Task 7.4, importe transitivement `app_router.dart`) et ne pouvait donc pas laisser ce blocker non résolu sans renoncer à toute vérification TDD de sa propre Task 7. Cause exacte identifiée : import manquant de `package:flutter/foundation.dart` (un seul import ajouté en tête de fichier, aucune autre logique touchée). Après correctif, `flutter test` complet passe de 522 passed/68 failed (tous par échec de compilation, aucune assertion réellement exécutée) à 803 passed/12 failed — voir entrée suivante pour le détail des 12 échecs désormais visibles.

## #134 — L226 RBAC gap on PENDING cancel via sync (dup of 12-6 CRITIQUE)

- Source: CR v1s-13-5 (2026-07-22)
- Evidence: covered by v1s-12-8 (handler now uses extractAssignedStoreId)
- Original entry (L226):

> **[v1s-13-5] Trou RBAC sync adjacent sur l'annulation PENDING via `CancelSaleSyncHandler`** [`CancelSaleSyncHandler.java`] — aucun contrôle de rôle sur l'annulation d'une vente PENDING via sync (le même raisonnement que le correctif AC5 de cette story s'applique), mais explicitement hors scope par le texte même d'AC5. Déjà suivi comme defer CRITIQUE de v1s-12-6 (`sprint-status.yaml` ligne 37) — doublon volontaire noté ici pour visibilité, aucune nouvelle action requise au-delà du suivi 12.6 existant.

## #135 — L227 TextEditingController leak in sale_detail_page dialogs

- Source: CR v1s-13-5 (2026-07-22)
- Evidence: dialogs now own and dispose their controllers (UI-3)
- Original entry (L227):

> **[v1s-13-5] Fuite de `TextEditingController` dans `sale_detail_page.dart`** [`sale_detail_page.dart:186-187,256-260`] — les contrôleurs créés dans `_showCancelDialog`/`_showCorrectSheet` (+ un par article dans `qtyControllers`) ne sont jamais disposés. Impact mineur (action rare, déclenchée par le propriétaire), correction propre nécessiterait de restructurer `_SaleDetailContent` en widget stateful ou d'utiliser un `StatefulBuilder`/`AutoDispose` dédié au dialogue. Recommandation : à corriger si ce module Flutter est retouché pour une autre raison, pas prioritaire seul.

## #139 — L234 Offline INSERT hardcodes minimumThreshold 0

- Source: CR v1s-13-7 (2026-07-22)
- Evidence: by design (spec Task 3.4), entry says 'documented for memory'; backend value arrives on next pull
- Original entry (L234):

> **[v1s-13-7] AC4 INSERT hardcode `minimumThreshold: 0`** [`validate_inventory_provider.dart:129`] — quand aucune ligne `stock_levels` n'existe, la création utilise `minimumThreshold: 0` sans égard au seuil produit côté backend. By design (spec Task 3.4) : backend = source of truth, le seuil correct sera syncé à la prochaine pull. Documenté ici pour mémoire.

## #154 — L268 prefs.setString not awaited

- Source: legacy CR HF-1 (2026-04-19)
- Evidence: grep: every setString in features/auth is now awaited
- Original entry (L268):

> **D3 — `prefs.setString()` non awaité**: Dans `auth_provider.dart` (Registration et Login) et `auth_page.dart`, les écritures SharedPreferences sont fire-and-forget. Un échec disque est silencieux. À corriger globalement lorsqu'une politique d'erreur persistence sera définie.

## #162 — L285 Note: 'Ventes brouillon offline' not migrated

- Source: legacy cart UI session (2026-04-07)
- Evidence: obsolete since 30bdd07 pivot (informational note)
- Original entry (L285):

> **Note v1s-15-5** : l'entrée "Ventes brouillon en mode offline" du même bloc legacy n'est PAS migrée — obsolète depuis le pivot v1s-13-6 (commit `30bdd07`, 2026-05-03) qui a remplacé le flux brouillon par saisie de stock initial inline + auto-promotion.

## #174 — L325 D1 SaleSyncHandler CREATE_SALE without EMPLOYEE scope

- Source: CR v1s-12-8 (2026-07-24)
- Evidence: extractAssignedStoreId check added in 4c188fd
- Original entry (L325):

> **D1 — `SaleSyncHandler` (CREATE_SALE) sans scope check EMPLOYEE** [SaleSyncHandler.java:45-72] : le handler prend `storeId` du payload sans appel à `extractAssignedStoreId()`, alors que `SaleController` REST force `jwtStoreId` comme autorité (lignes 49-61). Gap RBAC réel pour l'opération la plus critique. Hors scope explicite de cette story. **Recommandation : story de suivi immédiate.**

## #199 — L371 Obsolete transfer_repository_backend_first_test.dart

- Source: fix getHistory/productListForPicker offline (2026-08-11)
- Evidence: file deleted (1041827)
- Original entry (L371):

> **`transfer_repository_backend_first_test.dart` teste un design obsolète, 3 tests rouges en permanence** [test/features/inventory/data/repository/transfer_repository_backend_first_test.dart] : ce fichier teste un chemin "backend-first" (remote appelé en premier dans `executeTransfer`) remplacé par le refactor offline-first de la Story 5.6 (`StockTransferRepositoryImpl.executeTransfer()` utilise désormais toujours le chemin local, cf. sa propre doc de classe). Confirmé pré-existant (rouge avant ce diff, via `git stash` + run isolé), sans rapport avec les bugs corrigés ici. À supprimer ou réécrire pour refléter le comportement actuel.

## #203 — L385 catalog_page_test EMPLOYEE empty-search failing

- Source: CR spec-catalog-product-visibility-other-stores (2026-09-08)
- Evidence: test realigned: now asserts the button is shown for EMPLOYEE
- Original entry (L385):

> summary: Test pré-existant en échec, sans rapport avec ce fix — `catalog_page_test.dart` "hides 'Créer un produit' button for EMPLOYEE on empty search" échoue déjà sur `HEAD` avant ce diff (le bouton reste visible pour le rôle EMPLOYEE sur une recherche sans résultat).
