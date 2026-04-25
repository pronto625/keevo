# Deferred Work

## Deferred from: code review of HF-2 — 2ème passe (2026-04-25)

- **D1 (decision_needed) — `DayClosureDeltaProvider.java` : `totalTransactions` mappe sur le mauvais index** : la requête SQL sélectionne `total_sales, total_revenue` (indices 4 et 5). Le code `map.put("totalTransactions", num(row[4]))` duplique `totalSales` — aucune colonne `total_transactions` n'existe dans le DDL actuel. Impact : chaque payload de sync day_closure envoie `totalTransactions == totalSales`. À investiguer : soit la clé est supprimée du payload, soit la requête SQL est étendue pour inclure la colonne manquante. Fichier : `catalog/dayclosure/adapter/out/persistence/DayClosureDeltaProvider.java`.
- **P2 — `PendingSaleController.java` : EMPLOYEE peut annuler une vente brouillon** : `cancelSale` porte `@PreAuthorize("hasAnyRole('OWNER', 'EMPLOYEE')")`. AC5 n'autorise l'EMPLOYEE qu'à **valider** (pas annuler). L'annulation devrait rester OWNER-only : `@PreAuthorize("hasRole('OWNER')")`. Fichier : `commerce/sale/adapter/in/rest/PendingSaleController.java` L71.

## Deferred from: code review of HF-2-stabilisation-online-rbac-employe-notifications-transferts (2026-04-24)

- **B3 — CRITIQUE : EMPLOYEE sans accès aux ventes brouillons (4 sous-problèmes UI)** — identifié le 2026-04-25 :
  - **B3.1** `main_shell.dart` L212 : `pendingSalesCount = isEmployee ? 0 : ...` — badge onglet Caisse toujours 0 pour l'employé. Fix : utiliser `ref.watch(pendingSalesCountProvider(storeId)).valueOrNull ?? 0` (déjà store-scoped).
  - **B3.2** `pos_page.dart` L254 : `if (!isEmployee) _PendingSalesBanner(storeId: storeId)` — bannière masquée pour l'employé. Fix : retirer le guard.
  - **B3.3** `app_router.dart` : `EMPLOYEE → ReportsPage` (vue simple, aucun onglet Brouillons). Fix : ajouter un onglet Brouillons à `ReportsPage` en réutilisant `PendingSalesPage`.
  - **B3.4** `reports_page.dart` L281 : `pendingSalesTotal` affiché avec formatage monétaire pour les employés. Fix : masquer via check `isEmployee` (cohérence AC6).
  - Criticité HAUTE — l'employé ne peut valider aucune vente brouillon sans deep link FCM. Cibler HF-3.

- **D1 — N+1 pending sales list** (`SaleSpringRepository.findByStoreIdAndStatus` + `findByStatus`): items chargés en lazy pour chaque vente de la liste. Fonctionne correctement dans `@Transactional` mais génère N requêtes DB supplémentaires. Fix future : `@Query` avec `JOIN FETCH DISTINCT` analogue à `findByIdWithItems`. Pré-existant (HF-2 ajoute `findByStoreIdAndStatus`).
- **D2 — Mismatch variant/stock dans `ValidateSaleService.validateSale()`**: disponibilité lue avec `findByProductAndStore` (sans variantId) mais décrémentée à niveau variant via `recordOperation(item.getVariantId())`. Peut déclencher `StockForcedZeroEvent` à tort si le produit a des variants. Pré-existant Story 4.3.

## Deferred from: code review of HF-1-registration-role-persistence-unified-auth-ui (2026-04-19)

- **D1 — CGU links non-tappables**: `TextSpan`s "Conditions d'utilisation" et "Politique de confidentialité" dans `auth_page.dart` ont une décoration underline+couleur mais aucun `GestureRecognizer`. Comportement hérité de l'ancien `RegisterPage`. Ajouter un `TapGestureRecognizer` vers `/docs/politique` lors d'une itération UI dédiée.
- **D2 — `kUserRoleKey = 'OWNER'` hardcodé sans lecture du serveur**: `auth_provider.dart` Registration.register() écrit `'OWNER'` en dur au lieu de lire le rôle depuis le `RegistrationResult`. La spec l'autorise explicitement ("hardcoded, since registration always creates an OWNER") mais en cas d'évolution du backend (multi-role onboarding), cela causera un privilege assignment silencieux côté client.
- **D3 — `prefs.setString()` non awaité**: Dans `auth_provider.dart` (Registration et Login) et `auth_page.dart`, les écritures SharedPreferences sont fire-and-forget. Un échec disque est silencieux. À corriger globalement lorsqu'une politique d'erreur persistence sera définie.
- **D4 — Pattern routing `role == 'OWNER' ? '/dashboard' : '/pos'` dupliqué**: Ce ternaire apparaît en 4 endroits (`app_router.dart`, `auth_page.dart`, `shop_name_page.dart` ×2). Extraire en helper `routeForRole(String? role) → String` lors du prochain refactor routing.
- **D5 — Router redirect autorise `role == null` sur routes OWNER-only**: le redirect `app_router.dart` vérifie `role == 'EMPLOYEE'` pour rediriger vers `/pos`, mais laisse passer un rôle null silence vers les routes owner. Pre-existing. Ajouter `if (role == null) return '/auth/login';` dans le guard.
- **D6 — Back en mode login sans historique → `/auth/register`**: comportement hérité de l'ancien `LoginPage`. Peut-être contre-intuitif pour les utilisateurs deep-link. À revoir dans une itération navigation.
- **D7 — `initialCountryCode: 'CM'` hardcodé**: `auth_page.dart` (et anciens pages) forcent le Cameroun sans détection de locale. Ajouter détection via `flutter_sim_locale` ou prefs dans une itération internationalisation.

## Deferred from: code review of 8-0-fcm-push-notifications-whatsapp-wassender (2026-04-03)

- `DeviceTokenController.deleteToken()` — pas de vérification de propriété (ownership) sur la suppression de token. Tout utilisateur authentifié connaissant un token peut le supprimer. Surface d'attaque faible (tokens opaques, ~512 chars) mais représente un pattern à améliorer quand une liste par user_id sera disponible.

## Deferred from: code review of 7-5-configuration-des-rapports-preferences-whatsapp (2026-04-02)

- `WhatsAppPort.isConfigured()` has a `default return true` — any new adapter that forgets to override would silently send real test reports. Consider making the method abstract, or at minimum adding a code smell lint rule. Pre-existing design decision; not caused by Story 7.5 changes.

## Deferred from: cart UI session (2026-04-07)

### Ventes brouillons en mode offline
Actuellement le flow "Vente brouillon" (`CartBottomSheet` → `onEncaisser`) déclenche un appel API direct qui échoue silencieusement hors connexion.
**Scope requis :**
- File Drift locale `pending_sales_queue` pour stocker les ventes brouillons créées offline
- Sync automatique au retour de la connectivité (écoute `ConnectivityProvider`)
- Indicateur d'état "en attente de sync" dans `pending_sales_page.dart`
- Backend endpoint idempotent (pas de doublon si retry)

### Stale sync_queue payloads with `'status'` key
Any `CREATE_SALE` operations queued before the `_buildPayload()` key rename (`'status'` → `'requestedStatus'`) will sync as COMPLETED (not PENDING_VALIDATION). These are pre-existing broken payloads — the rename doesn't make them worse, but they should be cleared manually if observed in production. A future migration step could detect and fix `sync_queue` entries that contain `"status"` instead of `"requestedStatus"`.

---

## Deferred from: code review of 8-1-alertes-stock-critique-tendances-de-ventes (2026-04-04)

- **D1 — `ConcurrentHashMap<BatchWindow>` never evicted** — `StockAlertNotificationListener.batchWindows` accumulates one entry per `tenantId:storeId`. Growth bounded by tenant×store count (~40 bytes each). Consider Caffeine cache with TTL or periodic cleanup post-MVP.
- **D2 — TOCTOU race between cooldown check and upsert** — Two concurrent `@Async` threads for the same product+store could both pass `existsActiveCooldown` and both dispatch. Narrow window, benign duplicate. Consider DB-level advisory lock if notification dedup becomes critical.
- **D3 — No `@SchedulerLock` for overlapping trend scheduler runs** — If a `detectTrends()` run exceeds 1 hour, the next cron invocation starts concurrently. Consider integrating ShedLock when scaling to many tenants.

---

## Deferred from: code review of 5-6-offline-first-transactional-writes-instant-ux (2026-04-23)

- **F6 — `RiverpodSyncTriggerDispatcher` captures `Ref` at construction** — If the Provider scope is recreated (test overrides or auth scope changes), the captured `_ref` may become stale. Pre-existing pattern across the project; negligible in production. Revisit when Provider scoping is formalized.
