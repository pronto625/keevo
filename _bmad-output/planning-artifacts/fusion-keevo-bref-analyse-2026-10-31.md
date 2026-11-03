# Keevo × Bref Point — Analyse profonde et stratégie de fusion

> **Document d'analyse et de décision** · 31 octobre 2026 · Claude Fable 5.1
> **Objet :** fusionner Keevo (Flutter + Spring Boot) et Bref Point (Expo React Native + NestJS) en une seule application mobile React Native et un seul backend, **sans perdre les utilisateurs ni les données** de l'une ou l'autre, avec une **migration progressive** (les deux flux coexistent, puis convergent).
> **Méthode :** lecture exhaustive des quatre bases de code (8 analyses ciblées : inventaires + flux écran par écran + flux de données serveur), sans aucune modification de code. Tous les faits cités sont vérifiés dans le code ; les chiffres sont ceux des dépôts au 31/10/2026.
> **Dépôts analysés :** `AI/Keevo` (branche `refonte/modulaire`, commit `c4e69b0a`), `AI/bref` (branche `develop`, commit `a435e9b`), `AI/api-bref` (branche `develop`, commit `376ad70`).
> **Révision du 1er novembre 2026 :** l'analyse initiale portait sur `main` des deux dépôts Bref ; elle a été mise à jour avec les 18 commits de `develop` (9 côté API, 9 côté app). Le détail de ce delta et ses conséquences sont en §3.7 ; les sections concernées ont été corrigées en place.

---

## 0. Résumé exécutif (décisions recommandées)

| # | Décision | Recommandation | Justification courte |
|---|---|---|---|
| D1 | **Backend unifié** | **Keevo Spring Boot** (Java 21, Spring Modulith) devient le backend unique. api-bref (NestJS) est **absorbé fonctionnalité par fonctionnalité sous forme de modules Keevo**, puis décommissionné. | Keevo : socle modulaire opérationnel (registre, entitlements, provisioning, capacités, extension points), schéma-par-tenant, sync offline industrialisée (idempotence, LWW, delta keyset), audit immuable, **4 235 tests** + ArchUnit/Modulith. api-bref : schéma partagé, aucune transaction sur les flux critiques, **4 tests**, pas de migration initiale, blacklist JWT en mémoire. Réécrire Keevo en NestJS = réécrire 2 124 fichiers Java et 4 235 tests pour un résultat moins bon. |
| D2 | **Mobile unifié** | **Nouvelle app Expo / React Native**, construite à partir du **shell Bref** (Expo Router, design system HeroUI/uniwind, i18n 7 langues, EAS Build/Update, RevenueCat, mobile money) mais avec une **couche données réécrite sur le protocole de sync Keevo** (file d'opérations `sync_queue`, `push`/`pull` keyset, quarantaine, gate 7 jours). L'app Flutter Keevo passe en maintenance puis est retirée. | Le user veut React. Bref possède déjà le shell RN et la chaîne de release. La couche offline de Bref (pull complet 500 lignes, mutations TanStack sans rollback après redémarrage) est l'élément à ne **pas** reprendre : celle de Keevo est la référence. |
| D3 | **Modèle de tenancy** | **Un owner Bref = un tenant Keevo** (schéma `kv_xxxxxx`). Ses boutiques → `stores`, ses membres → `employees` + memberships, ses données → tables du tenant. | Le modèle Bref « owner possède N shops, membre rattaché à 1 shop » est **isomorphe** au modèle Keevo « tenant possède N stores, employee rattaché à 1 store ». |
| D4 | **Identité** | Étendre `public.users` Keevo : email, provider social, username ; ajouter OTP login (SMS/email/WhatsApp), Google, Apple ; **réconciliation par téléphone E.164**, puis par email. **Les hash bcrypt Bref sont réutilisables tels quels** (aucun reset de mot de passe imposé). | Keevo = téléphone + mot de passe uniquement. Bref = OTP email/téléphone, Google, Apple, identifiants membre. Les deux utilisent bcrypt (`$2a/$2b`), compatible avec `BCryptPasswordEncoder`. |
| D5 | **Plans et paiement** | Les plans Bref `free/pro/team/business` deviennent des **profils/bundles de modules** Keevo (story 16-2 et 16-8 déjà planifiées). Porter les connecteurs de paiement Bref (MyCoolPay, RevenueCat/StoreKit) comme module `payments` Keevo → **active l'auto-service** que Keevo n'a pas (aujourd'hui activation manuelle par le super-admin). | Keevo a un trial 6 mois + Premium manuel ; Bref a 4 plans, quotas (factures/mois, SMS, IA, emails) et paiement intégré. La cible doit honorer les abonnements payés en cours des deux côtés. |
| D6 | **Migration progressive** | **Strangler fig à double piste** : (1) les utilisateurs Keevo basculent sans migration de données (même backend) ; (2) les owners Bref sont migrés **par vagues**, un owner à la fois (gel → export → import idempotent → réconciliation chiffrée → redirection), api-bref restant en service pour les owners non encore migrés. **Deux identités d'app sur les stores** (bundle `com.brefpoint.app` et bundle Keevo) construites depuis **le même code** : chaque base d'utilisateurs reçoit la nouvelle app comme une simple mise à jour. | Zéro réinstallation, zéro perte, retour arrière possible owner par owner, pas de big-bang. |
| D7 | **Ce qu'il faut ajouter au socle Keevo** pour absorber Bref | Modules : `expenses`, `finance` (résultat), `quotes` (devis, extension d'`invoicing`), `invoicing` étendu (échéancier/paiements partiels, remise, livraison, templates de reçu, TVA par pays), `ai-assistant`, `whatsapp-cloud` (Meta Cloud API : inbox, templates, campagnes), `client-portal`, `payments` (MyCoolPay, StoreKit/RevenueCat), `identity` étendu (email, OTP, social, username, rôles ADMIN/VIEWER), `messaging` étendu (SMS MboaSMS/Infobip, email Resend, Expo push ou FCM), quotas d'usage (compteurs mensuels), `app-version`. | Voir §8. |
| D8 | **IA** *(décision utilisateur du 02/11/2026)* | **Couche d'outils unique sur les use cases Keevo**, consommée par l'assistant intégré (appel d'outils) et par un serveur MCP (Spring AI). **Pas de portage du constructeur SQL de Bref** (`ai-query-builder`). Parité « Bref Point » (sujets guidés, chat libre, conversations, quotas) assurée dès la migration. | Le SQL de Bref vise une base partagée filtrée par owner : à réécrire de toute façon pour le schéma par tenant, et il contournerait les règles métier (modules, périmètre employé, suivi de stock). Les outils héritent de ces règles et rendent MCP presque gratuit. Voir FUS-FR39, stories 21.8 / 21.4 / 21.9. |

**Chemin critique estimé** (solo, plein temps) : **≈ 7 à 9 mois** jusqu'au décommissionnement d'api-bref, avec une première valeur livrée dès le mois 3 (app RN pour les utilisateurs Keevo). Détail §13.

---

## 1. Périmètre et méthode

| Base de code | Chemin | Taille | Tests |
|---|---|---|---|
| Keevo backend (Spring Boot 3.5, Java 21, Spring Modulith 1.4, JPA, Flyway, PostgreSQL 16) | `Keevo/keevo/backend` | 2 124 fichiers `.java` (main + test), 60 contrôleurs, 199 handlers HTTP, 14 bounded contexts, 18 manifestes de modules | **4 235 `@Test`**, 58 IT, 8 Testcontainers, ArchUnit + Modulith `verify` |
| Keevo app (Flutter 3.41, Riverpod 3, go_router, Drift + SQLCipher, Melos) | `Keevo/keevo/app` | 481 `.dart` dans `lib/` + 102 dans `packages/` (~88 900 lignes hors généré), 21 features, 2 packages (`keevo_core`, `keevo_invoicing`), 22 tables Drift | **388 fichiers de test** |
| Keevo dashboard (Next.js 16, React 19, TanStack Query, vitest) | `Keevo/keevo/dashboard` | 170 fichiers ts/tsx, back-office **super-admin uniquement** | 59 fichiers de test |
| api-bref (NestJS 11, TypeORM 0.3, PostgreSQL 16) — `develop` | `AI/api-bref` | ~365 fichiers `.ts`, 26 modules Nest, 33 contrôleurs, ~145 routes, 36 migrations (sans migration initiale) | **7 `.spec.ts`** (refresh-token, otp, safe-fetch, product-key, finance-math, expense-date, app) + 1 e2e |
| bref app (Expo SDK 54, RN 0.81, Expo Router 6, TanStack Query 5, WatermelonDB, HeroUI/uniwind) — `develop` | `AI/bref` | 443 `.ts/.tsx`, 18 features, 79 fichiers de routes, 6 tables WatermelonDB (schéma v15) | **0 test** |

Les huit rapports d'analyse (inventaire + flux) sont synthétisés ici ; les chemins cités sont ceux des fichiers lus.

---

## 2. Radiographie Keevo

### 2.1 Architecture et socle modulaire

- **Backend hexagonal** par bounded context (`domain / application / adapter`), chaque package de premier niveau est un module Spring Modulith avec `allowedDependencies` déclarées : `admin`, `catalog`, `commerce`, `crm`, `feedback`, `identity`, `inventory`, `invoicing`, `messaging`, `reporting`, `shared`, `store`, `subscription`, `sync`.
- **Socle modulaire livré sur la branche** `refonte/modulaire` (= `origin/develop`, epics 12 → 15 terminés le 31/10/2026, epic 16 « licences, migration, go-live » en backlog). **Attention : la production ne l'a pas encore.** La branche `deploy` (production) est au commit `34949fec` du 20/10/2026 (V1 stabilisée, gelée en v2.0.1 sur `release/2.0.x`), soit 47 commits derrière `refonte/modulaire` : les tenants réels tournent sur la V1 sans registre de modules, sans entitlements et sans module `invoicing`. Le passage de la refonte en production (epic 16, backfill `tenant_modules`, canary) est donc un **préalable** à P1 (§9.2) et non un acquis. Les branches `kv-ui-update`, `rebrand` et `refractoring` sont des essais anciens (1 commit chacun, juin à septembre 2026), sans contenu à reprendre. `ModuleRegistry`, `DependencyResolver`, `ModuleProvisioner` (Flyway par module dans le schéma tenant), `EntitlementService` + `@RequiresEntitlement`, `@RequiresPermission`, `CapabilityRegistry` (`ClientLookupPort`, `WhatsAppSender`, `PushNotifier`, `PlanLimitCheck`, `CompanyIdentity`…), `ExtensionRegistry` (slot `pos.checkout.post-sale-actions`), `ModuleLifecycleService` + API super-admin.
- **18 manifestes** `module.json` : core, sync, admin (platform) ; gratuits : catalog, crm, inventory, messaging, pos, reporting, store ; premium : advanced-reports, alerts, counting, exports, invoicing, multi-store, profitability, report-config.
- **Contrats partagés** `keevo/contracts/` : `module-routes.json`, `invoice-sync-payloads.json`, `export-datasets.json`, vérifiés par des tests backend et Flutter.

### 2.2 Multi-tenancy et modèle de données

**Un schéma PostgreSQL par tenant** (`kv_[a-z0-9]{6}`, code public `KV-XXXXXX`), routé par `search_path` depuis le claim JWT `tenantId`. Le super-admin vit dans `public` (tenant système `KV-ADMIN`).

**Schéma `public`** (Flyway V1→V31) :

| Table | Colonnes clés |
|---|---|
| `users` | id UUID, phone_number(20) **UNIQUE**, password_hash (BCrypt), role (OWNER/EMPLOYEE/SUPER_ADMIN), is_active, failed_attempts, locked_until |
| `tenants` | id, code(10) UNIQUE, schema_name UNIQUE, name, plan_type, max_stores/products/employees, status (ACTIVE/SUSPENDED/DELETION_PENDING/DELETED), deletion_scheduled_at, suspended_*, designation |
| `user_tenant_memberships` | user_id, tenant_id, role(30), is_active, tokens_valid_after ; UNIQUE(user_id, tenant_id) |
| `refresh_tokens` | hash, rotation (`replaced_by_id`), détection de rejeu |
| `tenant_payments`, `tenant_plan_events`, `plan_expiry_reminders` | paiements saisis par l'admin (XAF, méthode, période), historique des changements de plan |
| `module_definitions`, `tenant_modules` | catalogue de modules, état par tenant (AVAILABLE/TRIAL/ACTIVE/EXPIRED/DISABLED, tier, expires_at, grace_until) |
| `user_sync_state`, `device_tokens`, `global_notifications`, `feedback`, `event_publication`, `shedlock` | sync devices, FCM, push globaux, outbox Modulith |

**Schéma tenant** (DDL Java dans `TenantSchemaProvisioner`, 1 276 lignes, + migrations de modules) :

| Domaine | Tables et colonnes essentielles |
|---|---|
| Identité | `employees` (user_id, store_id, first_name, last_name, status ACTIVE/INACTIVE, password_change_required), `roles`, `user_roles` (seedés, non utilisés pour l'autorisation), `users` (héritée, non alimentée), `subscriptions` (plan_type FREE/PREMIUM_TRIAL/PREMIUM, max_*, status, expires_at), `tenant_preferences` (secteur, heures de rapports, alertes) |
| Boutiques | `stores` (name, type STORE/WAREHOUSE, address, phone, is_active) |
| Catalogue | `products` (name UNIQUE insensible à la casse, description, sku(20) `KEV-XXXXXX`, category_id, **price INT, buy_price INT, transport_cost INT** en XAF, stock_quantity, photo_url, archived, status ACTIVE/DRAFT, minimum_threshold), `categories` (name, parent_id, is_active, is_custom) |
| Stock | `stock_levels` (product_id, store_id UNIQUE ensemble, quantity, minimum_threshold, version), `stock_movements` (type SALE/STOCK_ENTRY/TRANSFER_IN/TRANSFER_OUT/ADJUSTMENT/SALE_CANCELLED, quantity_before/change/after, actor_id, notes, occurred_at), `stock_transfers` (source, destination, product, quantity, status COMPLETED/PENDING_SYNC/CONFLICT/IN_TRANSIT, version) |
| Ventes | `sales` (store_id, employee_id, client_id FK, total_amount INT, discount_amount INT, payment_mode CASH/MOBILE_MONEY, mobile_money_ref, status COMPLETED/CANCELLED/PENDING_VALIDATION, occurred_at, version), `sale_items` (product_id, variant_id, product_name, applied_unit_price, catalogue_unit_price, quantity, subtotal), `day_closures` |
| CRM | `clients` (name, phone NOT NULL, email, notes, archived — **aucune unicité**), `suppliers`, `product_suppliers` |
| Inventaire | `inventory_sessions` (scope FULL/PARTIAL, status), `inventory_counts` |
| Facturation (module) | `invoicing_settings` (1 ligne : legal_name, address, phone, niu, rccm, vat_registered, vat_rate 19.25, prices_include_vat, payment_details, footer), `invoices` (number UNIQUE `FAC-yyyy-nnnnnn`, status DRAFT/ISSUED/PAID/CANCELLED, client_id + instantané client, instantané vendeur, total_ht/vat/ttc BIGINT, issued_at, paid_at, payment_method, cancelled_at, source_sale_id, superseded_at, deleted_at, last_sent_*), `invoice_items` (position, description, quantity NUMERIC(12,3), unit_price, vat_treatment STANDARD/EXEMPT/STATE_BORNE, totaux), `invoice_counters`, `credit_notes` (`AV-yyyy-nnnnnn`), `credit_note_items`, `sale_invoice_states` |
| Rapports | `reports`, `export_jobs` |
| Transverse | `audit_log` (append-only par trigger), `sync_operations_log`, `sync_conflicts_log`, `sync_error_log`, `device_tokens`, `draft_notifications`, `notification_cooldowns` |

Points structurants : **UUID partout**, **montants en entiers XAF** (facturation en BIGINT), `updated_at` + index keyset `(updated_at, id)` pour la sync, `@Version` sur `sales`, `stock_levels`, `stock_transfers`, soft delete par `archived` / `is_active` / `status`.

### 2.3 Identité et authentification

- **Identifiant unique = numéro de téléphone E.164** (`PhoneNumbers.normalize`), mot de passe BCrypt 8–72 caractères, verrouillage 15 min après 5 échecs. **Pas d'email, pas d'OTP au login, pas de social login.** OTP 6 chiffres **par WhatsApp** pour la réinitialisation du mot de passe uniquement.
- **Connexion en deux étapes** : `POST /auth/login` → `loginToken` (JWT RS256, 5 min, `scope=login_pending`) + liste des memberships ; `POST /auth/select-tenant` → access token (60 jours) + refresh token (360 jours, rotation, détection de rejeu). Claims : `sub`, `tenantId` (= schéma), `role`, `tenantStatus`, `storeId`, `passwordChangeRequired`, `firstName`, `onboardingCompleted` (claim `modules` prévu en 16-3).
- **Rôles** : OWNER, EMPLOYEE, SUPER_ADMIN. Permissions fines `@RequiresPermission` seulement pour `invoicing` (INVOICE_VIEW/CREATE/SEND pour EMPLOYEE, MANAGE pour OWNER). Un EMPLOYEE est **confiné à un `storeId`** (claim JWT imposé dans POS, stock, transferts, clôture, inventaire, sync).
- **Un même téléphone ne peut être OWNER d'un tenant et EMPLOYEE d'un autre** dans les flux actuels (`USER_ALREADY_EXISTS`), bien que la table de memberships soit N-N.

### 2.4 Flux métier clés (serveur)

**Inscription** (`RegistrationService.register`, une transaction) : normalisation téléphone → `TenantFactory` (code `KV-`, schéma, `tenants` en PREMIUM_TRIAL, provisioning DDL, événement REGISTRATION) → `public.users` OWNER → membership OWNER → `kv_x.user_roles` → JWT. **Seed tenant** : rôles OWNER/EMPLOYEE, `subscriptions` PREMIUM_TRIAL 6 mois, boutique « Ma Boutique ». **Onboarding** (`/onboarding/complete {sectorType, storeName}`) : 8 secteurs → catégories racines seedées (aucun produit), renommage de la boutique, `tenant_preferences`.

**Création d'employé** (OWNER) : téléphone validé, limite de plan, mot de passe temporaire 12 caractères généré serveur et **renvoyé dans la réponse HTTP** (l'app l'affiche une fois ; invitation WhatsApp par lien `wa.me` côté client, sans le mot de passe). Crée `public.users` EMPLOYEE + membership + `kv_x.employees`.

**Vente** (`RecordSaleService`, transaction, idempotente sur `saleId`) : contrôle client CRM si module actif, contrôle de stock (`INSUFFICIENT_STOCK`, sauf push sync avec `allowNegativeStock`), `total = Σ(applied_unit_price × qty) − discount`, **aucun prix d'achat capturé dans `sale_items`**, par ligne mouvement `SALE` (−qty) + `stock_levels` + événements seuil, `SaleCompletedEvent` → audit, rapports, **pont facturation automatique** (si `pos` et `invoicing` actifs : brouillon → émission → paiement dans une transaction, idempotent sur `source_sale_id`, remise répartie au prorata). Annulation COMPLETED (OWNER, justification ≥ 10 caractères) : remise en stock `SALE_CANCELLED` + avoir automatique. Correction : mouvement `ADJUSTMENT` + avoir + nouvelle facture (`superseded_at`). Clôture de journée : agrège ventes COMPLETED (nombre, CA, cash, momo, top produit) et PENDING, une par (store, jour WAT), clôture automatique par heure selon `eod_report_time`.

**Facture manuelle** (module premium `invoicing`) : machine d'états stricte DRAFT→ISSUED→PAID, ISSUED→CANCELLED ; **aucune modification hors DRAFT** ; émission bloquée sans ligne, sans nom de client ou sans `legal_name` vendeur ; numérotation **sans trou par année civile Africa/Douala**, allouée dans la transaction d'émission ; TVA HALF_UP au franc, prix TTC ou HT selon `prices_include_vat` ; **pas de remise, pas d'échéance, pas de paiement partiel** (un seul `paid_at` + `payment_method`) ; envoi WhatsApp d'un lien signé HMAC (30 jours, 30 envois/heure/tenant) ; PDF serveur (Thymeleaf + openhtmltopdf) ; avoirs totaux ou partiels.

**Plans** : FREE (1 boutique, 500 produits, 3 employés), PREMIUM_TRIAL 6 mois à l'inscription, PREMIUM. `PlanLimitGuard` à la création de boutique/produit/employé/réactivation. `SubscriptionExpiryService` (cron 2 h) rétrograde en FREE + WhatsApp ; rappels J-7/J-1. **Aucun paiement intégré** : le super-admin saisit les paiements Mobile Money reçus hors plateforme (`tenant_payments`) et active Premium (`AdminPlanService.activatePremium`). Modules premium : ENABLED tant que le plan est actif, READ_ONLY 7 jours de grâce, puis DISABLED (`ModuleStateRules`).

**Sync** (`SyncPushService`) : `POST /sync/push {deviceId, operations[{operationId, operationType, entityId, payload, clientTimestamp}]}` → tri (créations d'abord, puis `clientTimestamp`), **une transaction par opération**, idempotence par `operationId` (`sync_operations_log` → DUPLICATE), contrôle module (`MODULE_INACTIVE`/`MODULE_READ_ONLY`), LWW (`SUPERSEDED`), redirection des produits fusionnés par nom (`MERGED_INTO:<id>`), conflit `STOCK_NEGATIVE`, 423 `SYNC_REQUIRED` au-delà de 7 jours. `GET /sync/pull?since&cursor` : un `DeltaEntityProvider` par entité (produits, niveaux de stock, catégories, clients, fournisseurs, boutiques, employés, ventes + items, clôtures, mouvements, transferts, audit, inventaire, rapports, factures), pagination keyset 1 000, marge 30 s, modules DISABLED sautés, tombstones uniquement pour les brouillons de facture.

**Reporting** : `DashboardService` (OWNER) sur `sales COMPLETED` en zone WAT (CA jour/hier/mois, tendance, panier moyen, stock bas seuil 5 par défaut, top/flop 7 jours, séries jour/semaine/mois/année, vue par boutique) ; rapports hebdo WhatsApp ; rentabilité (`marge = Σ(applied × qty) − Σ((buy_price + transport) × qty)` avec le **prix d'achat courant**, remise globale non déduite) ; classement vendeurs ; exports CSV/XLSX/PDF (jobs de fond).

### 2.5 Application Flutter : écrans, offline, parcours

- **Entrée** : splash → slides → CGU → `/auth/register` (téléphone + mot de passe, ni prénom ni nom) → wizard secteur + nom de boutique → dashboard (OWNER) ou `/pos` (EMPLOYEE). Login 2 étapes avec `tenant-picker` si ≥ 2 memberships.
- **Navigation** OWNER : Dashboard, Caisse, Catalogue, Rapports, Plus. EMPLOYEE : sans Dashboard ; préfixes réservés à l'OWNER (`/dashboard`, `/clients`, `/suppliers`, `/stores`, `/settings/*`, `/audit`, `/exports`, rapports avancés).
- **Boutique courante** : SharedPreferences `active_store_id` (OWNER : « Toutes » à chaque connexion, puis première boutique ; EMPLOYEE : claim `storeId`), changée depuis Plus.
- **POS** : grille de produits fréquents (Drift), recherche locale sur le nom, panier avec prix unitaire modifiable par ligne (dérogation de prix) et **une remise globale** (% ou XAF), produit brouillon créé à la volée avec stock initial, client CRM optionnel, CASH (monnaie rendue) / MOBILE_MONEY (référence). Vente **toujours COMPLETED** côté app ; écrite dans une transaction Drift (sales, sale_items, stock_levels décrémenté, stock_movements SALE, `RECORD_STOCK_ENTRY` + `PROMOTE_PRODUCT` pour les brouillons) puis `CREATE_SALE`. Succès → slot d'extension « Facturer ».
- **Facturation** (package `keevo_invoicing`, écrans en `deferred`) : liste/détail/formulaire depuis `module_records` (JSON), 5 opérations hors ligne, « Émise — numéro attribué à la synchronisation », paiement antidaté (calendrier Douala), avoirs/PDF/WhatsApp/suppression en ligne seulement, profil vendeur.
- **Catalogue** : produit (nom, description, catégorie, SKU, prix de vente, prix d'achat, transport, photo ; **pas d'unité, pas de stock initial à la création**), calcul de marge en direct, stock par boutique (serveur d'abord), seuil (en ligne), import CSV (`nom,prix_vente,prix_achat,cout_transport,categorie,sku,quantite_initiale,seuil_min`, en ligne), brouillons, catégories.
- **Stock** : entrée, ajustement, transfert (source décrémentée localement, `PENDING_SYNC` → `IN_TRANSIT` → `COMPLETED`, réception en ligne), inventaire (session, comptage, écarts, validation → `ADJUSTMENT`).
- **Équipe** : création en ligne (mot de passe temporaire affiché), réassignation/désactivation hors ligne, activité, régénération du mot de passe.
- **Rapports** : Jour (depuis la dernière clôture), Historique, Marges, Boutiques, Équipe ; exports serveur et CSV local ; préférences de rapports WhatsApp.
- **Offline** : 22 tables Drift chiffrées SQLCipher (clé 32 octets en secure storage), `sync_queue` (push par lots de 50 toutes les 30 s, single-flight), pull toutes les 5 min, curseur en secure storage, politique de rejet (transitoire / held `MODULE_INACTIVE` / terminal → quarantaine ou rollback), **gate** : bannière J5, rouge J6, **écritures bloquées à J7** (`SyncRequiredModal`). Fonctionne hors ligne : vente, clôture, produits, catégories, clients/fournisseurs, boutiques, stock, inventaire, employés (réassignation), factures (5 ops). En ligne seulement : annulation/correction de vente COMPLETED, import CSV, PDF/WhatsApp/avoirs, profil entreprise.
- **Identité d'app** : `applicationId = "com.example.keevo"` dans `android/app/build.gradle` (à vérifier avec les flavors / la fiche Play Store réelle), version `2.0.17+89`, `ios/` et `web/` présents mais seul Android est livré en production (FCM via `google-services.json`, pas de `GoogleService-Info.plist`).

### 2.6 Dashboard Next.js

Back-office **super-admin uniquement** (login 2 étapes, membership `SUPER_ADMIN` sur `KV-ADMIN`, cookie `ks_admin_auth`) : entreprises (détail, suspension, plan free/premium, paiements, suppression forcée), revenus (MRR, churn), analytics, retours, alertes, santé, push globaux, catalogue global, activité, santé de la sync. **Aucune gestion des modules** dans l'UI (prévue en 16-4). **Aucun dashboard commerçant web.**

### 2.7 Intégrations et exploitation

- WhatsApp : Wassender / Twilio / failover (OTP reset, rappels de plan, rapports, factures, invitations). **Pas de SMS, pas d'email.** FCM (push) via `firebase-admin`. PDF Thymeleaf. Exports POI/CSV. Stockage fichiers **sur disque local** (photos, logos, exports). Prometheus + OTLP.
- Déploiement : Docker (Temurin 21), GHCR, Traefik, PostgreSQL ; CI `backend-ci`, `deploy-backend` (branche `deploy`), `deploy-staging` ; backups systemd ; secrets JWT PEM.

---

## 3. Radiographie Bref Point

### 3.1 Architecture

- **api-bref** : NestJS 11 + TypeORM 0.3 + PostgreSQL 16, **une base et un schéma partagés**, cloisonnement logique par `shop.userId` (owner) et `shopId`. 26 modules Nest, DTO `class-validator` (whitelist), Swagger hors prod, versioning URI `/api/v1`, CORS brefpoint.app. **Presque aucune relation TypeORM déclarée** (liens par colonnes texte `userId`/`shopId`/`ownerId`) ; les seules : Shop→User, Customer→Shop, Invoice→Items/Payments (cascade, eager).
- **bref app** : Expo SDK 54 / RN 0.81 / React 19 (New Architecture, React Compiler, typed routes), Expo Router (77 fichiers), TanStack Query 5 persisté (AsyncStorage, 24 h, mutations `offlineFirst` rejouées), WatermelonDB (6 tables, schéma v13, **synchronisation maison sans `synchronize()`**), HeroUI Native + uniwind (Tailwind 4), i18n **7 langues** (fr, en, ln, sw, ha, wo, ar ; seuls fr/en exposés), police Outfit, thème clair/sombre, app-lock PIN + biométrie, EAS Build/Update (OTA `fingerprint`), RevenueCat iOS, mobile money MyCoolPay.

### 3.2 Modèle de données (api-bref)

**UUID partout** (sauf `app_version`), clés **fournies par le client** pour `user`, `shop`, `invoice`, `expense` (idempotence). Montants `decimal(14,2)` (factures, produits, dépenses), entiers XAF (abonnements). **`timestamp without time zone` en UTC naïf** (TZ forcé UTC) ; `invoice.date` en **VARCHAR(10) `YYYY-MM-DD`**, échéances `dueDate`/`paidDate` en **texte**, `expense.expenseDate` en `date`.

| Table | Colonnes clés | Unicité |
|---|---|---|
| `user` | email?, phone?, username, password (bcrypt 12), firstName, lastName, role (`superadmin\|owner\|admin\|seller\|viewer`), **shopId** (membre : 1 boutique), plan (`free\|pro\|team\|business`, **plus lu comme droit depuis `develop`**), provider (email/google/apple), socialId, isVerified, **emailVerified** (develop), isActive, mustChangePassword, tokensValidAfter, scheduledDeletionAt, whatsappMarketingOptOutAt, totalSales/invoicesCreated (**jamais écrits**), deletedAt | email, phone, username (restent occupés après soft delete) |
| `refresh_token` (develop) | jti PK (uuid ou `legacy:<userId>:<iat>`), familyId (session), userId, expiresAt, replacedAt, revokedAt | — ; index family/user/expires |
| `shop` | userId (owner), name, address, category (19 valeurs), phone, photoUrl (Cloudinary), niu, rccm, country (défaut CM), description, isActive, totalSales/monthSales/invoices (**jamais écrits**), deletedAt | aucune |
| `customer` | shopId, name, phone, email, type (person/company), totalInvoices, totalSpent, lastPurchaseAt, marketingConsentAt/OptOutAt, **marketingConsentById** (develop), **nui, rccm, address, contactName, website, birthDate (texte `YYYY-MM-DD`), notes** (develop ; création manuelle `POST /customers` et édition `PATCH /customers/:id`, téléphone non modifiable) | (shopId, phone) — **téléphone brut non normalisé** |
| `product` | shopId, name, nameKey, productKey (`bc:<code>` ou `nm:<nom normalisé>`), reference, unitPrice, priceManual, timesSold, **totalQuantity `numeric(16,3)`** (develop), totalRevenue, lastSoldAt, isArchived, mergedIntoId | (shopId, productKey) |
| `invoice` | userId (créateur), shopId, **client en clair** (customerName/Phone/Email, **customerNui/customerRccm** en develop, sans FK), subtotal, tax, discount, deliveryFee, total, status (`pending\|paid\|partial\|overdue\|cancelled`), type (`sale\|quote`), paymentMethod (`cash\|mobile_money\|bank_transfer\|card\|other`), initialPayment, dueDate, date (texte), notes, publicToken (+ expiration 90 j, **rotation à l'annulation et sur demande** en develop), cancelledAt/By/Reason, validatedAt, deletedAt | publicToken ; **aucun numéro séquentiel** (référence affichée = 8 premiers caractères de l'UUID, préfixe `DV-` pour un devis) |
| `invoice_item` | invoiceId, reference, name, description, **quantity `numeric(12,3)`** (develop : décimales à 3 chiffres, total de ligne arrondi au franc si quantité non entière), unitPrice, total — **pas de productId** ; bornes develop : 200 lignes, 100 échéances, montant unitaire ≤ 10¹⁰, total ≤ 999 999 999 999 | — |
| `invoice_payment` | invoiceId, amount, dueDate (texte), isPaid, paidDate (texte), notes — échéancier + encaissements | — |
| `invoice_outbox` | invoiceId, status, attempts, channels (email/sms/whatsapp) | — |
| `expense` | ownerId, shopId, createdById, amount, category (10 valeurs), expenseDate, paymentMethod, description, deletedAt | — |
| `subscription` | ownerId, plan, status (`active\|scheduled\|expired\|cancelled`), billingCycle (`monthly\|quarterly\|yearly`), startedAt, endsAt, gracePeriodEndsAt (**lu pour l'accès depuis develop**), amountPaid, creditUsed, paymentMethod (`orange_money\|momo\|card\|wave\|apple\|admin_grant`), appleOriginalTransactionId | develop : index unique partiel `appleOriginalTransactionId` sur les abonnements actifs ; sinon plusieurs actifs possibles |
| `plan_payment_request`, `usage_counter` (ownerId, metric `invoices\|ai_credits\|sms\|email\|sms_free\|daily_*`, periodMonth), `account_credit` (ownerId, amount), `apple_iap_transaction`, `apple_iap_verification_request`, `sms_recharge_request`, **`revenuecat_webhook_event`** (develop : id, type, subjectKey, eventTimestampMs, processedAt — idempotence du webhook et rejet des événements périmés) | monétisation | (ownerId, metric, periodMonth) ; ownerId ; transactionId ; develop : **un seul checkout `pending` par owner** |
| `otp` (identifier, code, purpose, expiresAt, usedAt), `push_token` (Expo), `web_push_subscription`, `notification` (userId, type, channel, isRead, metadata) | transverse | token |
| `client` (**client final** : phone UNIQUE, email, name, password), `campaign` (plateforme), `customer_campaign` (owner → clients WhatsApp), `owner_whatsapp_template`, `whatsapp_account` (ownerId UNIQUE, wabaId, phoneNumberId, accessToken), `whatsapp_message`, `whatsapp_chat_message`, `ai_conversation` (messages jsonb), `app_version` | portail, marketing, IA | phone, email ; (ownerId, name, language) ; ownerId |

**Absences notables** : pas de table Sale (une vente = `invoice` de type `sale`), pas de stock, pas de Membership/Employee (un membre = un `user` avec `role` + `shopId`), pas de table Plan (constantes), pas de schéma par tenant, pas de RLS.

### 3.3 Identité et authentification

- **Owner** : inscription par email **ou** téléphone (`POST /auth/register` crée un `user` non vérifié, sans mot de passe, plan free) → `otp/request` (SMS **et** WhatsApp pour un téléphone, email sinon ; 5 min ; limites en mémoire) → `otp/verify` → tokens. **Aucune boutique ni abonnement créés à l'inscription** ; un owner sans boutique arrive sur des onglets vides.
- **Login owner** : OTP email/téléphone ; **Google** (tokeninfo + audience) et **Apple** (JWKS) avec **liaison automatique au compte existant de même email** et création automatique sinon.
- **Membre** (admin/seller/viewer) : `POST /auth/login/credentials {username, password}` ; `username` **fourni par l'owner** (regex `[a-zA-Z0-9._-]`), mot de passe fourni (6–128, bcrypt 12), `mustChangePassword` forcé pour seller/admin. L'app ne transmet rien au membre : l'owner communique lui-même les identifiants.
- **JWT HS256** `{id, role, shopId, email, typ, sid, jti}` (1 jour) + refresh JWT (7 jours). **Depuis `develop`** : les refresh tokens sont **persistés en base** (`refresh_token`, famille = session `sid`) avec **rotation à usage unique** (`jti`), détection de réutilisation (toute la famille est révoquée), `logout` révoque la famille, la suppression de compte révoque toutes les sessions ; la blacklist en mémoire ne sert plus qu'aux jetons émis avant la rotation (`legacy:<userId>:<iat>`). `JwtStrategy` relit rôle/shopId/isActive en base (cache 30 s). C'est désormais **le même modèle que Keevo** (rotation, famille, rejeu).
- **OTP (develop)** : identifiant normalisé (`normalizeOtpIdentifier` : email, ou E.164 limité aux préfixes **237, 241, 235, 243, 221** — Cameroun, Gabon, Tchad, RD Congo, Sénégal, surchargeable par `AUTH_ALLOWED_PHONE_PREFIXES`), réponse générique anti-énumération, **compteur d'échecs en base** (5 essais par code, 20 échecs / 24 h par identifiant), rate limit global 500, temps de réponse constant (hash factice). Contact de récupération lié à l'utilisateur connecté ; remplacer un contact exige une preuve OTP sur l'ancien, qui est prévenu par SMS/email.
- **Liaison sociale (develop)** : refusée vers un compte **membre** ou vers un owner dont l'email n'est **pas vérifié** (`emailVerified`) ; un OTP reçu sur l'email du compte le marque vérifié.
- **Superadmin** : seedé **à chaque démarrage** avec email et téléphone codés en dur, connexion OTP uniquement.
- **Suppression de compte** : `scheduledDeletionAt` +30 j, **le compte reste utilisable**, le cron refuse (409) si l'utilisateur a des factures → en pratique jamais supprimé ; sinon soft delete du `user` seul, sans anonymisation.
- **Client final** (portail) : table `client` séparée, inscription OTP puis mot de passe, JWT `{sub, phone, role:'client'}` signé avec **le même secret**, factures retrouvées par **téléphone exact toutes boutiques confondues**.

### 3.4 Flux métier clés (serveur)

**Facture** (`InvoiceService.create`, **aucune transaction globale**) : quota mensuel incrémenté **avant** la création (hors transaction) → calcul serveur (`item.total = qty × unitPrice`, `discount` borné, **TVA par pays** `getVatRate(shop.country)` CM = 19,25 %, 0 si `skipVat`, `total = netHT + tax + deliveryFee`, livraison hors assiette) → statut initial (`paid` sans échéancier ; `partial` si acompte ; `pending`) → échéancier en cascade → `publicToken` (défaut DB) → effets de bord **non transactionnels** : `customer.upsertFromInvoice` (clé (shopId, phone) ; nom écrasé ; **aucun client créé sans téléphone**), `product.upsertFromSale` (verrou consultatif par boutique ; prix remplacé sauf `priceManual`), notifications, outbox d'envoi (30 s, 20 lignes, 5 tentatives, quotas SMS/email **non bloquants**, WhatsApp document).
Statuts recalculés **uniquement** par les mutations d'échéance (`recalculateStatus` sous `FOR UPDATE`) : `paid` si `initialPayment + Σ échéances payées ≥ total`, sinon `overdue` si une échéance non payée est dépassée, sinon `partial`/`pending`. **Aucun cron ne passe une facture en `overdue`.** Une vente annulée ne revit pas via un paiement (develop).
**Depuis `develop`** : le statut et l'acompte à la création sont **calculés par le serveur** (`dto.status` ignoré, même règle qu'à la validation d'un devis) ; `PATCH /invoices/:id` n'accepte plus que `status = paid` hors devis et annulées, et « marquer payée » passe par `settle()` : toutes les échéances sont marquées payées et **un encaissement daté du reste dû est créé** ; `tax`, `discount`, `deliveryFee` ne sont modifiables que sur un **devis**, avec recalcul du total (sur `main`, une vente pouvait avoir un `total` incohérent : **les factures historiques créées avant ce correctif peuvent encore l'être**). L'annulation fait tourner le `publicToken` ; `POST /invoices/:id/public-link/regenerate` (owner/superadmin) le régénère ; la vue publique est restreinte (`PublicInvoiceDto` : ni boutique, ni notes, ni annulation). Devis (`type=quote`, sans quota ni effets) → `validate` (type `sale`, **`date` écrasée par aujourd'hui**, `paymentMethod` obligatoire, échéances, effets de bord). Annulation : décrémente les compteurs client/produit (sans restauration du quota). PDF pdfkit **uniquement via l'outbox** (aucun endpoint de téléchargement). Quantités **décimales** (3 chiffres, `lineTotal` arrondi au franc si la quantité n'est pas entière) ; NUI/RCCM du client entreprise recopiés sur la facture et sur la fiche client.

**Plans** (`plan-policy.constant.ts`) :

| Plan | Boutiques | Membres/boutique | Factures/mois | IA (mois/jour) | SMS | Email | Prix XAF (m/t/a) |
|---|---|---|---|---|---|---|---|
| free | 1 | 1 | 300 | 0 | 5 à vie | 100 | 0 |
| pro | 3 | 3 | ∞ | 500 / 20 | 20 | ∞ | 3 500 / 9 500 / 32 760 |
| team | 8 | 8 | ∞ | 750 / 30 | 50 | ∞ | 5 500 / 15 000 / 51 480 |
| business | ∞ | ∞ | ∞ | ∞ / 50 | 80 | ∞ | 8 500 / 23 000 / 79 560 |

(La doc `brefpoint_freemium_strategy.md` affiche des prix plus bas : 2 500 / 4 500 ; **le code fait foi**.) **Plan effectif (develop)** = abonnement **en vigueur** (`active`, commencé, payé, non terminé **ou** dans la grâce Apple `gracePeriodEndsAt`), sinon `free` ; **`user.plan` n'est plus jamais lu comme un droit** et `reconcileOwnerSubscriptions` le remet à `free` quand aucun abonnement n'est en vigueur (ce qui corrige le « plan payant indéfini » de `main`). `PlanGuard` : Pro pour dépenses/finance, Business pour campagnes/templates/inbox. Limites vérifiées à la création (boutique, membre), quota factures atomique, SMS/email seulement à l'envoi, IA à chaque chat. **Toujours pas de cron d'expiration** : la réconciliation ne tourne qu'à la demande (au premier appel de l'owner). Downgrade volontaire refusé si trop de boutiques/membres ; downgrade subi : rien n'est désactivé.

**Paiement** : MyCoolPay (`checkout` → `pay` USSD ou OTP → `pay/status` → webhook `/webhook/pay/:provider/:hash`), Apple StoreKit (`storekit/verify-purchase` + file de vérification 3 s) + webhook RevenueCat, `admin_grant`, crédit de compte (`account_credit`), recharge SMS. **Durcissements develop** : un seul checkout `pending` par owner (index unique), verrou atomique `INITIATING` sur `/pay` (reprise après 2 min), `transactionRef` vérifiée à l'OTP, crédit lu `FOR UPDATE` ; webhook RevenueCat idempotent (`revenuecat_webhook_event`), événements périmés ignorés, abonnements sandbox filtrés en production (sauf compte de review), `TRANSFER` entre comptes géré, remboursement → révocation ; index unique partiel sur `appleOriginalTransactionId` actif.

**Dashboard owner** (`owner-summary`, SQL brut, période obligatoire) : `revenue` (Σ total des ventes non annulées sur `date`), `collected` (acomptes + échéances payées dans la période), `outstanding` (à date), ventes/panier, clients nouveaux/récurrents (jointure sur le **téléphone**), devis (toutes périodes), finance Pro (HT, dépenses, résultat, marge %), séries jour/mois, top boutiques/vendeurs/produits (par `LOWER(TRIM(name))`), todo (retards, devis > 7 j, clients inactifs > 30 j).

### 3.5 Application mobile : écrans, offline, parcours

- **Entrée** : onboarding → choix owner / membre → OTP (ou Google/Apple) → onglets. Inscription : prénom, nom, email ou téléphone → OTP → contact de secours (passable) → succès. **Aucun onboarding de boutique.**
- **Onglets** : Dashboard, Boutiques (owner), Bref Point IA (owner), Factures, Équipe (masqué pour seller ; **visible pour viewer**). Rôles : seller limité à ses factures ; admin gère sa boutique et crée des sellers ; viewer **sans aucun blocage d'écriture côté app** ; owner tout (ProGate : dépenses, finance, rapports ; BusinessGate : marketing, messages).
- **Dashboard** : KPI **locaux** (`computeInvoiceStats`) avec sélecteur boutique (owner) et période ; finance via `GET /finance/summary` ; 3 dernières factures ; badges notifications / messages WhatsApp. Rapports 100 % locaux.
- **Création de facture** (5 étapes, `invoiceId` UUID généré à l'ouverture) : Infos (devis/vente, boutique, client recherché localement ou nouveau {name, phone, email, **type obligatoire** person/company, **NUI/RCCM si entreprise** — develop}) → Articles (catalogue local trié par `times_sold`, **champ de recherche avec résultats dynamiques et « créer "<nom>" »** (develop), dédoublonnage **par nom**, formulaire {reference auto `REF-xxxxxx`, name, description, **quantity décimale** (clavier décimal, virgule, 3 décimales — develop), unitPrice}, **scanner code-barres** avec lookup externe) → Paiement (remise % ou fixe, livraison, TVA on/off **à 19,25 % fixe dans l'écran**, taux d'intérêt affiché mais non envoyé, mode cash/mobile_money/bank_transfer/card/other, plein ou échelonné avec acompte + échéances) → Signature (client + vendeur, **non persistée, non envoyée**) → Récap (modèle de reçu parmi 8, notes, date, envoi email/sms/whatsapp selon quota, consentement marketing) → Succès (PDF local expo-print, impression, partage, lien `whatsapp://`). Écriture optimiste locale (`tax=0` en local) puis `POST /invoices` ; l'écran passe au succès **sans attendre le serveur**.
- **Détail facture** : versement, échéance payée, modifier échéance, annuler (motif), valider devis, éditer (devis seulement), PDF/impression, envoyer (en ligne). **Aucun contrôle de rôle** dans l'écran.
- **Clients** : liste, détail enrichi, consentement marketing, et **depuis `develop` création manuelle (`/customer/new`) et édition (`/customer/[id]/edit`)** : en ligne seulement, viewer exclu, type obligatoire, téléphone verrouillé à l'édition, champs entreprise (NUI, RCCM, contact, site web) ou particulier (date de naissance), adresse, notes ; conflit 409 si le téléphone existe déjà dans la boutique. **Produits** : édition {name, reference, unitPrice}, archivage, fusion ; **aucune création manuelle** (catalogue dérivé des ventes). **Dépenses** (owner, Pro) : CRUD avec UUID client. **Boutiques** : {name, address, category, phone, description, niu, rccm, photo} ; pas de « boutique active » globale (état local par écran). **Équipe** : {firstName, lastName, role admin/seller, shop, username, password} ; limite vérifiée localement.
- **Plan** : Android → checkout MyCoolPay (Orange Money, MTN MoMo ; USSD ou OTP ; polling 3 s) ; iOS → RevenueCat (SKU `brefpoint.{plan}.{month|monthly|yearly}`) + vérification serveur ; écran plan courant (statut, cycle, `endsAt`, grâce, `nextPlan`).
- **Portail client** : téléphone/email → `public/count` → compte client (OTP + mot de passe) → `GET /clients/invoices`.
- **Offline** : WatermelonDB `brefpoint` schéma **v15** (invoices avec `items_json`/`payments_json` + `customer_nui`/`customer_rccm`, shops, users, customers + nui/rccm/address/contact_name/website/birth_date/notes, products, expenses) ; `syncAll()` = `Promise.all` de 6 pulls **complets limités à 500 lignes** (clients/produits/dépenses bouclent sur les boutiques locales → vides à la première sync), LWW avec tolérance 5 s, suppression physique sur `deletedAt` ; push = mutations TanStack persistées, scope par enregistrement ; **une mutation reprise après redémarrage n'a ni `onError`, ni rollback, ni journal** (ligne optimiste orpheline). Badge « en attente » sur les cartes, bannière hors ligne, écran `sync-history`.
- **Identité d'app** : `com.brefpoint.app` (prod) / `com.brefpoint.dev`, Apple Team `N9534K29PS`, owner EAS `rolandsandjo`, version `2.0.0` (app.json) vs `1.0.0` (package.json), projet EAS Update `bb0b6dbe-…`, `GET /app-version/check` avec mise à jour forcée.

### 3.6 Intégrations et exploitation

SMS MboaSMS/Infobip, email Resend, **WhatsApp Meta Cloud API** (webhook HMAC, mTLS optionnel, un compte WABA par owner, inbox, templates, campagnes), Cloudinary (develop : MIME + octets de tête contrôlés), IA OpenAI/Gemini/Ollama (develop : constructeur de requêtes SQL de l'assistant durci — tables autorisées, transaction `READ ONLY`), Web Push VAPID (develop : endpoints autorisés, pas de réattribution silencieuse), Expo push, pdfkit, MyCoolPay, App Store Server Library, RevenueCat. Cron : outbox 30 s, campagnes 1 min, IAP 3 s, suppression 3 h. Docker node:20-alpine, GHCR, runner self-hosted, `docker compose pull && up -d` dans `/opt/api-bref`. **Variables d'environnement lues dans le code mais absentes de `.env.example`** : ~40 (auth, DB SSL, email, IA, RevenueCat, WhatsApp, VAPID, `AUTH_ALLOWED_PHONE_PREFIXES`).

### 3.7 Delta de la branche `develop` (mise à jour du 1er novembre 2026)

Les 18 commits de `develop` sont tous **postérieurs** à l'analyse initiale de `main` et vont dans le sens de la fusion : ils rapprochent Bref du modèle Keevo (rotation des refresh tokens, statuts calculés serveur, fiche client complète, quantités décimales) et corrigent plusieurs défauts relevés en §3. Ce que cela change pour la fusion :

| Changement `develop` | Section corrigée | Conséquence pour la fusion |
|---|---|---|
| **Fiche client complète** (création/édition manuelles, NUI, RCCM, adresse, contact, site web, date de naissance, notes, auteur du consentement) | §3.2, §3.5, §5.1, §5.2, §6.4 | Le CRM Bref n'est plus « dérivé seulement » : il rejoint le CRM Keevo (fiche saisie). Les colonnes `niu`, `rccm`, `address`, `contact_name`, `website`, `birth_date`, `client_type`, `marketing_consent_by` sont à ajouter à `clients` Keevo (qui a déjà `notes`). Keevo `invoices` a déjà `client_niu` et `client_address` : seul `client_rccm` manque. |
| **Quantités décimales** (`numeric(12,3)`, arrondi au franc par ligne) | §3.2, §6.6, §7.2 | Keevo `invoice_items.quantity` est **déjà** `NUMERIC(12,3)` avec arrondi HALF_UP par ligne : **aucune extension** pour les documents. Le POS Keevo (`sale_items.quantity INT`) reste entier : les produits « au poids » passent par le parcours Document. |
| **Statut et acompte calculés serveur**, `PATCH` limité à « marquer payée » (`settle()` crée l'encaissement du reste dû), montants de vente non modifiables, total recalculé sur devis, bornes de saisie | §3.4, §4, §7.2, §11 R3, §12 Q7 | La machine d'états Bref converge vers celle de Keevo. L'import conserve la règle `settle` : un « marquer payée » historique est représenté par un paiement daté. Le contrôle Q7 (`total` ≠ recalcul) ne concerne plus que les factures **antérieures** à `develop`. |
| **Refresh tokens persistés avec rotation, famille de session, détection de rejeu** ; `logout` et suppression de compte révoquent en base | §3.3, §4 | Même modèle que `public.refresh_tokens` Keevo. Les sessions ne sont pas migrées (les utilisateurs se reconnectent dans l'app unifiée), mais le **comportement attendu par les utilisateurs Bref est déjà celui de Keevo**. |
| **OTP** : identifiants normalisés E.164 limités à 5 préfixes pays, échecs comptés en base, anti-énumération, preuve de contact liée à l'utilisateur, `emailVerified` | §3.3, §6.1, §7.6, §12 Q12 | Bref est **explicitement multi-pays** (CM, GA, TD, CD, SN) : Q12 devient une décision structurante (Keevo est XAF/Cameroun). Le flag `emailVerified` conditionne la liaison sociale : à reprendre tel quel dans `users.email_verified_at`. |
| **Plan effectif = abonnement en vigueur** (grâce Apple incluse), `user.plan` n'est plus un droit, remise à `free` à la réconciliation | §3.4, §7.10 | Règle d'import simplifiée : le plan importé est celui d'`activePlanOf`, plus besoin d'interpréter `user.plan`. Toujours pas de cron : un owner inactif peut rester marqué payant en base jusqu'à son prochain appel ; l'import applique la règle en vigueur, pas la colonne. |
| **Paiement et RevenueCat durcis** (checkout unique, verrou `/pay`, idempotence webhook, événements périmés, sandbox filtré, `TRANSFER`, remboursement) | §3.4, §6.8, §9.6 | `revenuecat_webhook_event` doit être **importé** dans Keevo (table du module `payments`) pour que le routeur de webhooks à double livraison ne rejoue pas d'événements déjà traités ; la logique `TRANSFER`/remboursement est à porter avec le module. |
| **Rotation du lien public** à l'annulation et à la demande ; vue publique restreinte | §3.4, §6.6, §9.6 | La redirection `legacy_public_token` doit respecter l'expiration et l'état annulé (lien mort après annulation). |
| Pagination clampée partout, admin limité à sa boutique sur `/users`, upload et web-push durcis, constructeur SQL de l'assistant IA verrouillé | §3.6 | Pas d'impact sur le modèle de données ; à conserver comme exigences de sécurité du module `ai-assistant` porté (tables autorisées, lecture seule). |
| App : recherche de produits dans l'étape Articles, type de client obligatoire, NUI/RCCM dans les 8 templates PDF, intercepteur de refresh qui n'exclut plus `/auth/me` et `/auth/logout` | §3.5, §5.2 | Les templates PDF portés en Thymeleaf doivent inclure NUI/RCCM du client ; l'écran unifié « Facture / Devis » reprend la recherche + création nommée. |

---

## 4. Comparaison côte à côte

| Dimension | Keevo | Bref Point | Conséquence pour la fusion |
|---|---|---|---|
| **Positionnement** | Gestion de **stock + caisse** multi-boutiques, offline-first 7 jours, Cameroun, XAF | **Facturation rapide** + devis + impayés + marketing WhatsApp + IA, TPE/PME, multi-pays (TVA par pays, 7 langues) | Complémentaires plus que concurrents : Bref = « vendre et facturer sans stock », Keevo = « gérer le stock et la caisse ». La vision modulaire Keevo (« facturation seule » OU « facturation + vente liées ») est **exactement** le cas d'usage Bref. |
| **Backend** | Spring Boot 3.5 / Java 21 / Modulith, hexagonal, 4 235 tests | NestJS 11 / TypeORM, 4 tests, flux non transactionnels | Garder Keevo (D1). |
| **Tenancy** | Schéma par tenant, `search_path` par JWT | Schéma partagé, `ownerId`/`shopId` en colonnes texte, pas de RLS | Import Bref = création d'un schéma par owner. Nombre d'owners Bref à connaître (§12) : le schéma-par-tenant a été validé « < 100 tenants » ; `TenantSchemaSyncService` et les backups sont en O(nombre de schémas). |
| **Identifiant utilisateur** | Téléphone E.164 UNIQUE + mot de passe | Email **ou** téléphone (uniques, nullables) ; membre par `username` ; social `socialId+provider` | Étendre `public.users` (email, username, provider, social_id, is_verified) ; rendre `phone_number` nullable **ou** exiger un téléphone à la première connexion des comptes email-only (§7.6). |
| **Auth** | Mot de passe, 2 étapes, JWT RS256 60 j, refresh rotatif 360 j (famille, rejeu) | OTP SMS/email/WhatsApp, Google, Apple, identifiants membre, JWT HS256 1 j, refresh 7 j **rotatif persisté avec famille de session et détection de rejeu** (develop) | Modèles de session désormais équivalents. Keevo accueille OTP + social comme **nouveaux « authenticators »** émettant le même `loginToken`. |
| **Rôles** | OWNER, EMPLOYEE (1 store), SUPER_ADMIN ; permissions fines seulement sur invoicing | superadmin, owner, admin, seller, viewer (1 shop) | Ajouter MANAGER (= admin) et VIEWER à Keevo, exprimés en **permissions par module** (mécanisme `@RequiresPermission` déjà en place). |
| **Plans** | FREE / PREMIUM_TRIAL 6 mois / PREMIUM ; limites boutiques/produits/employés ; modules premium à la carte | free / pro / team / business ; limites boutiques, membres, factures/mois, SMS, emails, crédits IA | Profils de plan configurables (16-8) + bundles (16-2) ; ajouter des **quotas d'usage mensuels** au socle entitlement. |
| **Paiement** | Aucun (saisie admin) | MyCoolPay (Orange/MTN/Wave/carte), StoreKit + RevenueCat, crédit de compte | Porter en module `payments` → comble FR « Mobile Money automatique » de Keevo. |
| **Vente** | `sales` + `sale_items` (stock, prix catalogue vs appliqué, remise globale, CASH/MOMO, PENDING/COMPLETED/CANCELLED, correction, clôture) | `invoice type=sale` (pas de stock, remise, livraison, TVA, acompte, échéancier, 5 modes de paiement, devis) | Deux concepts à **réconcilier** (§7.1). |
| **Facture** | Module premium, DRAFT→ISSUED→PAID/CANCELLED, numéro sans trou `FAC-yyyy-nnnnnn`, TVA vendeur, avoirs, PDF serveur, lien signé 30 j, **pas de remise, échéance, paiement partiel** | Statuts pending/partial/paid/overdue/cancelled, référence = UUID tronqué, TVA par pays, remise, livraison, échéancier, lien public 90 j, 8 templates de reçu PDF **locaux**, signature | Étendre `invoicing` (§7.2) ; conserver le numéro Keevo pour les nouvelles factures et une **référence héritée** pour les factures importées. |
| **Produits** | Catalogue saisi (SKU, prix achat/vente/transport, catégorie, photo, seuil, brouillon), stock par boutique, import CSV, unicité par nom | Catalogue **dérivé des ventes** (productKey, compteurs, fusion), prix décimal, référence/code-barres, pas de stock | Produits Bref importés **sans suivi de stock** (`track_stock = false`, nouveau) ; lignes de facture sans `productId` restent des lignes libres. |
| **Clients** | `clients` (name, phone NOT NULL, email, notes), pas d'unicité | `customer` (shopId, phone) unique, type, NUI/RCCM, adresse, contact, site, naissance, notes, compteurs, consentement marketing (+ auteur) ; création/édition manuelles (develop) ; client sans téléphone = inexistant | Importer par (boutique → tenant, phone normalisé) ; ajouter `client_type`, `niu`, `rccm`, `address`, `contact_name`, `website`, `birth_date`, `marketing_consent_at/by` à `clients`. |
| **Quantités** | POS : entiers ; facturation : `NUMERIC(12,3)` arrondi HALF_UP par ligne | `numeric(12,3)`, 3 décimales, arrondi au franc par ligne (develop) | Règles identiques pour les documents ; le POS reste entier. |
| **Dépenses / finance** | Absent (module `expenses` listé « demande client réelle » dans le plan de refonte) | Dépenses par boutique (10 catégories), résultat financier | Nouveaux modules `expenses`, `finance`. |
| **Offline** | Drift chiffré, file d'opérations, push/pull delta keyset, LWW, quarantaine, gate 7 j, idempotence serveur par `operationId` | WatermelonDB + TanStack ; pull complet 500 lignes ; idempotence par UUID client ; pas de contrôle de concurrence (sauf `expectedUpdatedAt` sur devis/boutique) | Couche données de l'app unifiée = **port du protocole Keevo** en TypeScript (§8.2). |
| **WhatsApp** | Wassender/Twilio (envoi sortant texte + lien) | Meta Cloud API (WABA par owner, inbox, templates, campagnes, documents) | Les deux coexistent : provider plateforme (Keevo) + module `whatsapp-cloud` (Bref) pour les owners qui ont un WABA. |
| **SMS / email** | Aucun | MboaSMS/Infobip, Resend | À porter dans `messaging`. |
| **Push** | FCM (`firebase-admin`, `device_tokens`) | Expo push (`push_token`), Web Push | L'app RN peut remonter un **token FCM natif** (`getDevicePushTokenAsync`) → aucun changement serveur ; ou ajouter un `PushNotifier` Expo. |
| **PDF** | Thymeleaf + openhtmltopdf serveur | pdfkit serveur (outbox) + 8 templates HTML **sur l'appareil** (expo-print) | Garder le PDF serveur comme source de vérité ; reprendre les templates Bref comme **modèles Thymeleaf** + rendu local optionnel hors ligne. |
| **Fichiers** | Disque local | Cloudinary | Garder le disque + option S3 (déjà en deferred ops) ; migrer les logos Cloudinary (télécharger → stocker). |
| **i18n** | fr_FR, en_US (app) ; messages serveur en français | 7 langues (app) ; filtre d'exceptions i18n (API) | Reprendre l'i18n Bref dans l'app unifiée ; codes d'erreur Keevo déjà stables (`MODULE_NOT_ENTITLED`…) → traduction côté app. |
| **Plateformes** | Android (prod), iOS/web présents non livrés | Android + iOS (App Store, Team ID) + web Expo | L'app unifiée apporte iOS aux utilisateurs Keevo. |
| **Release** | Store Android, `release-flutter.yml` | EAS Build + **EAS Update OTA** + workflows | OTA = levier clé de la migration (pousser un « pré-migrateur » dans l'app Bref sans passer par la revue des stores). |
| **Tests** | 4 235 (back) + 388 fichiers (app) + 59 (dashboard) | 4 + 1 e2e (back), 0 (app) | Le socle de tests Keevo protège la migration ; l'app RN doit **naître avec des tests** (Jest + RNTL + Maestro/Detox). |
| **Dette connue** | DDL tenant en Java (hors Flyway), `TenantSchemaSyncService` au boot, `users` tenant hérité, applicationId `com.example.keevo` à vérifier | Pas de migration initiale, compteurs `shop`/`user` jamais écrits, `overdue` jamais recalculé par tâche, pas de cron d'expiration (réconciliation à la demande), dates texte, téléphones client non normalisés, totaux de ventes incohérents **hérités d'avant `develop`** (corrigés depuis : blacklist remplacée par des refresh tokens en base, statuts calculés serveur, `user.plan` plus lu comme droit) | Les défauts Bref restants sont **corrigés par construction** en important dans le modèle Keevo (recalcul des statuts, normalisation, numérotation). |

---

## 5. Cartographie fonctionnelle et écrans

### 5.1 Matrice des fonctionnalités (K = Keevo, B = Bref, U = cible unifiée, module Keevo cible)

| Fonctionnalité | K | B | U | Module cible | Note |
|---|---|---|---|---|---|
| Inscription téléphone + mot de passe | ✅ | — | ✅ | core/identity | conservé |
| Inscription / login par OTP email ou SMS/WhatsApp | — | ✅ | ✅ | identity (nouveau authenticator) | porté de Bref |
| Login Google / Apple | — | ✅ | ✅ | identity | porté ; liaison par email puis téléphone |
| Login membre par username | — | ✅ | ✅ (transitoire) | identity | nécessaire pour les membres Bref sans téléphone |
| Multi-tenant 2 étapes (un compte, plusieurs entreprises) | ✅ | — | ✅ | core | atout : un commerçant Bref **et** Keevo garde un seul compte |
| Onboarding secteur + catégories seedées | ✅ | — | ✅ (optionnel « mode facturation ») | core | secteur « Services / sans stock » à ajouter |
| Boutiques multiples, entrepôts | ✅ | ✅ (pas d'entrepôt) | ✅ | store | champs Bref : category (19), niu, rccm, country, photo, description → à ajouter sur `stores` |
| Équipe : employé 1 boutique | ✅ | ✅ | ✅ | core | rôles MANAGER/VIEWER à ajouter |
| Catalogue saisi, catégories, photos, import CSV, brouillons | ✅ | — | ✅ | catalog | |
| Catalogue auto-dérivé des ventes, fusion, code-barres | — | ✅ | ✅ | catalog | `productKey`, `merged_into_id`, `barcode`, `track_stock` à ajouter ; scanner dans l'app |
| Stock multi-boutiques, mouvements, transferts, seuils | ✅ | — | ✅ | inventory, multi-store | désactivable par produit (`track_stock=false`) |
| Inventaire (comptage, écarts) | ✅ | — | ✅ | counting | |
| Caisse (POS) : panier, dérogation prix, remise globale, CASH/MOMO, ventes en attente, clôture | ✅ | — | ✅ | pos | |
| Vente « document » : TVA, remise, livraison, acompte, échéancier, 5 modes de paiement | — | ✅ | ✅ | invoicing (étendu) | §7.2 |
| Devis → validation en vente | — | ✅ | ✅ | invoicing (`quotes`) | |
| Facture légale numérotée, avoirs, profil vendeur, lien signé, PDF serveur | ✅ | — | ✅ | invoicing | |
| Facture auto après vente POS (pont) | ✅ | n/a | ✅ | bridge pos→invoicing | |
| Envoi facture : WhatsApp | ✅ | ✅ | ✅ | messaging | |
| Envoi facture : SMS, email | — | ✅ | ✅ | messaging (SMS/email providers) | quotas |
| Templates de reçu (8) + impression locale + signature | — | ✅ | ✅ | invoicing (templates) | signature à **persister** (nouveau) |
| Lien public client 90 j | — | ✅ | ✅ | invoicing | redirection des anciens tokens (§9.6) |
| Portail client final (retrouver mes factures) | — | ✅ | ✅ | client-portal (nouveau, global) | |
| Clients : fiche saisie, historique, type, NUI/RCCM, consentement marketing | ✅ (fiche) | ✅ (develop : création/édition, fiche entreprise/particulier) | ✅ | crm | la fiche unifiée = union des champs |
| Quantités décimales sur les documents (kg, m) | ✅ (invoicing) | ✅ (develop) | ✅ | invoicing | POS entier |
| Fournisseurs, lien produit-fournisseur | ✅ | — | ✅ | crm | |
| Dépenses (10 catégories) | — | ✅ | ✅ | expenses (nouveau) | |
| Résultat financier (CA HT − dépenses, marge) | — | ✅ | ✅ | finance (nouveau, reporting) | |
| Dashboard CA/encaissé/impayés/retards | — | ✅ | ✅ | reporting | KPI « documents » ajoutés au dashboard Keevo |
| Dashboard CA jour/tendance/stock bas/top-flop/boutiques | ✅ | — | ✅ | reporting | |
| Rapports jour/hebdo WhatsApp, rentabilité, classement vendeurs, exports CSV/XLSX/PDF | ✅ | — | ✅ | reporting, profitability, advanced-reports, exports | |
| Audit immuable, mouvements de stock | ✅ | — | ✅ | core | |
| Assistant IA (chips + chat, crédits) | — | ✅ | ✅ | ai-assistant (nouveau) | OpenAI/Gemini/Ollama via SDK compatible |
| WhatsApp Cloud : inbox, templates, campagnes clients | — | ✅ | ✅ | whatsapp-cloud (nouveau, Business) | |
| Campagnes plateforme (super-admin) | ✅ (push globaux) | ✅ (push/sms/email/in_app/whatsapp) | ✅ | admin | |
| Notifications in-app, push | ✅ | ✅ | ✅ | messaging | |
| Abonnement en libre-service (Mobile Money, Apple) | — | ✅ | ✅ | payments (nouveau) | |
| Activation manuelle + paiements saisis par l'admin | ✅ | ✅ (`admin_grant`) | ✅ | admin | |
| Quotas d'usage (factures/mois, SMS, emails, IA) | — | ✅ | ✅ | entitlement (étendu) | |
| Mise à jour forcée de l'app | — | ✅ | ✅ | core (`app-version`) | |
| PIN / biométrie, thème sombre, 7 langues | — | ✅ | ✅ | app | |
| Offline 7 jours avec file d'opérations, quarantaine, gate | ✅ | partiel | ✅ | sync | |
| Feedback utilisateur, tutoriels, suppression de compte RGPD (grâce 30 j, purge) | ✅ | ✅ (suppression inopérante) | ✅ | core | |
| Super-admin : tenants, revenus, analytics, santé, sync, modules | ✅ | ✅ (dashboard global) | ✅ | admin | |

### 5.2 Matrice des écrans : Bref → Keevo → app unifiée

Lecture : chaque écran Bref est rapproché de son équivalent Keevo ; la colonne « Unifié » donne l'écran cible et le module qui le gate.

| Écran Bref (route) | Équivalent Keevo (route) | Écran unifié (module) | Décision |
|---|---|---|---|
| `auth/onboarding` | `/onboarding` (slides) + `/terms` | Slides + CGU | fusion (CGU Keevo obligatoires) |
| `auth/login`, `login/owner`, `login/by-email`, `by-phone`, `verify-otp` | `/auth/login` (téléphone + mdp) | `/auth/login` avec onglets « Mot de passe » / « Code OTP » / Google / Apple | union des méthodes |
| `auth/login/by-credentials` (membre) | `/auth/login` | même écran, champ identifiant accepte téléphone **ou** username | transitoire |
| `auth/login/change-password` | `/auth/change-password` | `/auth/change-password` | identique |
| `auth/register/*` (prénom, nom, email/tél, OTP, secours, succès) | `/auth/register` → `/onboarding/sector` → `/onboarding/shop-name` | Register (prénom, nom, téléphone **ou** email, mdp optionnel, OTP) → secteur (+ « Services / Facturation seule ») → nom boutique | Keevo exige une boutique dès l'inscription : **corrige** le trou Bref (owner sans boutique) |
| — | `/tenant-picker` | `/tenant-picker` | nouveau pour les ex-Bref |
| `(tabs)/index` Dashboard | `/dashboard` | Dashboard modulaire : bloc « Caisse » (K) + bloc « Documents : encaissé / impayés / retards » (B) + finance (B, Pro) + boutiques (K) | widgets contribués par modules (ExtensionRegistry côté app) |
| `(tabs)/invoices` (ventes + devis) | `/invoicing` (liste) + `/pos/sales-history` | Onglet « Ventes & Factures » : liste unifiée des **documents** (factures, devis) ; historique caisse séparé | §7.1 |
| `invoice/create` (5 étapes) | `/invoicing/new` (formulaire simple) et `/pos/checkout` | **Deux parcours** : « Caisse » (K, stock) et « Facture / Devis » (B, 5 étapes enrichies). Même catalogue, même client CRM. | l'owner choisit son parcours par défaut dans les préférences |
| `invoice/[id]`, `[id]/edit`, `[id]/validate` | `/invoicing/:id`, `/invoicing/:id/edit` | Détail document : actions selon statut + permissions (ajouter versement, marquer payé, valider devis, annuler, avoir, PDF, envoyer, lien public) | |
| `shop/*` | `/stores` | `/stores` enrichi (catégorie, NIU, RCCM, pays, photo) | |
| `user/*` Équipe | `/settings/team/*` | `/settings/team` avec rôles OWNER/MANAGER/EMPLOYEE/VIEWER | |
| `customer/*` (liste, détail, `new`, `[id]/edit` — develop) | `/clients`, `/clients/new`, `/clients/:id` | `/clients` (CRM) : fiche unifiée (type, NUI/RCCM, contact, site, naissance, adresse, notes) + historique des documents + consentement marketing | création manuelle (K et B) **et** auto-création depuis une facture (B) ; la création Bref est en ligne seulement, celle de Keevo est hors ligne (file) : la cible garde le mode hors ligne |
| `product/*` | `/products/*` | `/products` : fiche K (prix achat, marge, stock) + attributs B (référence/code-barres, fusion, compteurs) ; produits « sans stock » | |
| `expense/*` | — | `/expenses` (module expenses, Pro) | nouveau |
| `finance` | `/reports/rentabilite` | `/reports` onglet « Résultat » (finance) + onglet « Marges » (profitability) | |
| `reports/*` | `/reports`, `/reports/history` | `/reports` | |
| `marketing/*`, `messages/*` | — | `/marketing`, `/messages` (whatsapp-cloud, Business) | nouveau |
| `plan/*` | `/settings/subscription`, `/settings/modules` | `/settings/subscription` : plan + modules + **paiement en libre-service** (payments) | remplace le « Demander l'activation » WhatsApp |
| `find-my-invoices/*` (public) | — | `/portal/*` (client-portal), hors session | nouveau |
| `bref-point`, `bref-point-chat/[id]` | — | `/assistant` (ai-assistant, Pro) | nouveau |
| `notification` | `/notifications` | `/notifications` | |
| `security` (PIN/biométrie) | — | `/settings/security` | nouveau |
| `settings`, `profile`, `profile/edit` | `/settings`, `/settings/account`, `/settings/company` | `/settings/*` | |
| `sync-history` | `/settings/sync`, `/settings/sync/conflicts`, `/sync/blocked` | `/settings/sync` (file, conflits, quarantaine) | modèle Keevo |
| — | `/pos`, `/pos/checkout`, `/pos/pending`, clôture | Caisse (pos) | K |
| — | `/inventory/*`, `/stock/*` | Stock, transferts, inventaire | K |
| — | `/suppliers/*` | Fournisseurs (crm) | K |
| — | `/audit/*`, `/exports/*`, `/settings/reports`, `/products/import` | identiques | K |

**Écrans à concevoir spécifiquement pour la fusion** : sélecteur de parcours (Caisse vs Facture), dashboard composable, écran de **réconciliation de compte** (« Nous avons trouvé un compte Keevo avec ce numéro : fusionner ? »), écran « Vos données ont été migrées » (ex-Bref), bannière de pré-migration dans l'app Bref actuelle.

---

## 6. Cartographie des données : mapping Bref → Keevo (champ par champ)

Principes : **les UUID Bref sont conservés** (les deux systèmes sont en UUID ; cela rend l'import idempotent et traçable), chaque ligne importée reçoit `legacy_source = 'bref'` dans une **table de correspondance** `public.migration_records(source, source_table, source_id, tenant_schema, target_table, target_id, imported_at, checksum)`. Les montants `decimal(14,2)` passent en entiers XAF (arrondi HALF_UP ; écart cumulé contrôlé par la réconciliation §10). Les timestamps UTC naïfs deviennent `timestamptz` UTC. Les téléphones sont normalisés en E.164 (`PhoneNumbers.normalize`, pays par défaut = `shop.country`).

### 6.1 Owner → Tenant

| Bref `user` (role=owner) | Keevo | Règle |
|---|---|---|
| `id` | `public.users.id` | conservé si aucun compte Keevo avec le même téléphone ; sinon **liaison** au compte existant (§7.6) |
| `phone` | `public.users.phone_number` | normalisé E.164 ; si NULL (owner email-only) → `phone_number` NULL (colonne à rendre nullable) + obligation de renseigner un téléphone à la première connexion, ou login par email/OTP |
| `email`, `provider`, `socialId`, `isVerified`, `emailVerified` (develop) | nouvelles colonnes `public.users.email` (UNIQUE), `auth_provider`, `social_id`, `email_verified_at` (= `updatedAt` si `emailVerified`, sinon NULL), `phone_verified_at` (= date de vérification OTP si `isVerified` et téléphone) | la règle Bref « pas de liaison sociale sur un email non vérifié » est à conserver |
| `password` (bcrypt 12, `$2a/$2b`) | `password_hash` | **copié tel quel** (Spring `BCryptPasswordEncoder` lit `$2a/$2b`) ; owners OTP-only ont `password = NULL` → `password_hash` NULL autorisé + flag `password_set = false` |
| `firstName`, `lastName` | `public.users.first_name/last_name` (nouvelles) et `tenants.designation` | Keevo n'a pas de prénom/nom sur `users` (uniquement dans `employees`) |
| `isActive`, `tokensValidAfter`, `scheduledDeletionAt`, `deletedAt` | `users.is_active`, `memberships.tokens_valid_after`, `tenants.deletion_scheduled_at` / status DELETION_PENDING | |
| `plan` + `subscription` actif | `tenants.plan_type`, `kv_x.subscriptions`, `tenant_modules` (bundle), `tenant_plan_events` (cause `MIGRATION_BREF`) | §6.9 |
| `whatsappMarketingOptOutAt` | `tenant_preferences.marketing_opt_out_at` (nouvelle) | |
| — | `tenants.code`, `schema_name`, `name` | code généré `KV-xxxxxx`, `name` = nom de la première boutique ou prénom/nom |
| — | `kv_x.tenant_preferences` | secteur = mapping de `shop.category` (19 → 8 secteurs + OTHER), `invoice_flow_default = 'DOCUMENT'` |
| — | `user_tenant_memberships` | (user, tenant, OWNER) |

### 6.2 Shop → Store

| Bref `shop` | Keevo `kv_x.stores` | Règle |
|---|---|---|
| `id` | `id` | conservé |
| `name`, `address`, `phone`, `isActive` | `name`, `address`, `phone`, `is_active` | |
| `category` (19 valeurs), `description`, `photoUrl` (Cloudinary), `niu`, `rccm`, `country` | **nouvelles colonnes** `category`, `description`, `logo_path`, `niu`, `rccm`, `country` | logo téléchargé depuis Cloudinary → `KEEVO_COMPANY_LOGO_DIR` ; `niu`/`rccm` de la **première** boutique copiés dans `invoicing_settings` (profil vendeur) |
| `deletedAt` | `is_active = false` + `migration_records.note` | Keevo n'a pas de soft delete de boutique |
| `totalSales`, `monthSales`, `invoices` | ignorés | jamais écrits côté Bref |

### 6.3 Membre → Employee

| Bref `user` (admin/seller/viewer) | Keevo | Règle |
|---|---|---|
| `id` | `public.users.id` | conservé |
| `username`, `password` | `public.users.username` (nouvelle, UNIQUE), `password_hash` | login par username conservé ; `mustChangePassword` → `employees.password_change_required` |
| `phone`, `email` | `users.phone_number` (nullable), `users.email` | membres Bref souvent **sans téléphone** → colonne nullable obligatoire |
| `firstName`, `lastName`, `shopId`, `isActive` | `kv_x.employees (user_id, first_name, last_name, store_id, status)` | |
| `role` admin / seller / viewer | membership role **MANAGER** / **EMPLOYEE** / **VIEWER** (nouveaux) | permissions : MANAGER = EMPLOYEE + gestion équipe de sa boutique + lecture clients/produits ; VIEWER = lecture seule |
| — | `user_tenant_memberships` | (user, tenant du owner, rôle) |

### 6.4 Customer → Client

| Bref `customer` | Keevo `kv_x.clients` | Règle |
|---|---|---|
| `id` | `id` | conservé |
| `shopId` | `store_id` (**nouvelle colonne nullable**) | Keevo : clients au niveau tenant ; Bref : par boutique. Un même téléphone dans 2 boutiques Bref → **2 lignes conservées** (store_id différent) ou fusion si l'owner le choisit (option d'import) |
| `name`, `phone`, `email` | `name`, `phone` (normalisé), `email` | `phone` NOT NULL respecté (Bref ne crée pas de client sans téléphone) |
| `type` (person/company) | `client_type` (nouvelle) | |
| `nui`, `rccm`, `address`, `contactName`, `website`, `birthDate`, `notes` (develop) | `niu`, `rccm`, `address`, `contact_name`, `website`, `birth_date` (DATE, parse strict), `notes` (existe) | `nui` Bref = `niu` Keevo (même notion : Numéro d'Identifiant Unique) ; `birthDate` invalide → NULL + note |
| `totalInvoices`, `totalSpent`, `lastPurchaseAt` | **recalculés** depuis les documents importés | compteurs Bref approximatifs (nom écrasé, annulation de devis décrémente) |
| `marketingConsentAt`, `marketingOptOutAt`, `marketingConsentById` (develop) | nouvelles colonnes `marketing_consent_at`, `marketing_opt_out_at`, `marketing_consent_by` | un « STOP » client n'est jamais levé par l'équipe (règle develop à conserver) |
| Clients « en clair » sur facture sans téléphone | pas de ligne `clients` ; instantané conservé sur la facture (`client_name`) | comportement identique des deux côtés |

### 6.5 Product → Product

| Bref `product` | Keevo `kv_x.products` | Règle |
|---|---|---|
| `id` | `id` | conservé (sauf `mergedIntoId` ≠ NULL : ligne importée **archivée** avec `merged_into_id`) |
| `shopId` | — (produits au niveau tenant chez Keevo) + `stock_levels` **non créés** | collision de noms entre boutiques (Keevo : `uq_products_name` insensible à la casse) → suffixe « (nom boutique) » et note dans `migration_records` |
| `name`, `reference` | `name`, `sku` | `reference` ≠ format `KEV-XXXXXX` → stockée dans `barcode`/`reference` (**nouvelle colonne**, 100 caractères), `sku` généré |
| `productKey`, `nameKey` | `product_key` (nouvelle, index par tenant) | sert à la dérivation automatique depuis les documents |
| `unitPrice` decimal | `price` INT (arrondi) | |
| `priceManual` | `price_manual` (nouvelle) | |
| `timesSold`, `totalQuantity`, `totalRevenue`, `lastSoldAt` | recalculés (reporting) ou colonnes de cache (nouvelles) | |
| `isArchived` | `archived` | |
| — | `track_stock = false` (**nouvelle**), `buy_price = 0`, `transport_cost = 0`, `category_id` = catégorie « Produits divers » du secteur | POS n'exige pas de stock pour `track_stock=false` ; marge non calculée tant que `buy_price = 0` |

### 6.6 Invoice (sale / quote) → Invoice (module invoicing étendu)

| Bref `invoice` | Keevo `kv_x.invoices` | Règle |
|---|---|---|
| `id` | `id` | conservé |
| `type` sale / quote | `kind` (**nouvelle** : INVOICE / QUOTE) | devis = `kind=QUOTE`, statut DRAFT/ISSUED (devis émis) ; `validatedAt` → `converted_at` + `converted_from_quote_id` sur la facture |
| `userId` (créateur), `shopId` | `created_by`, `store_id` (**nouvelle**) | |
| `customerName/Phone/Email`, `customerNui/customerRccm` (develop) | `client_name`, `client_phone`, `client_email` (nouvelle), `client_niu` (existe), `client_rccm` (nouvelle), `client_id` = client importé si (shop, phone) trouvé | |
| `subtotal`, `discount`, `deliveryFee`, `tax`, `total` | `total_ht`, `discount_amount` (**nouvelle**), `delivery_fee` (**nouvelle**), `total_vat`, `total_ttc` | TVA Bref = sur (subtotal − discount), livraison hors assiette → `vat_rate` par facture = `tax / (subtotal − discount)` (ou 0) ; contrôle : `total_ttc = (subtotal − discount) + tax + delivery` à ±1 XAF |
| `status` pending / partial / overdue / paid / cancelled | `status` ISSUED / PAID / CANCELLED + **`payment_state`** (**nouvelle** : UNPAID / PARTIAL / PAID / OVERDUE, **dérivée**) | Keevo garde la machine légale (ISSUED/PAID/CANCELLED) ; l'état d'encaissement devient une colonne dérivée recalculée par un job quotidien (corrige « overdue jamais recalculé ») |
| `paymentMethod` 5 valeurs | `payment_method` (texte libre existant) | mapping : cash → Espèces, mobile_money → Mobile Money, bank_transfer → Virement, card → Carte, other → Autre |
| `initialPayment` + `invoice_payment[]` | **nouvelle table `invoice_payments`** (invoice_id, amount, due_date DATE, paid_at, is_paid, notes, source) | acompte = paiement `is_paid=true, paid_at = date` ; `paid_at` global = date du dernier paiement si `Σ ≥ total` |
| `dueDate` | `due_date` (**nouvelle**, DATE) | |
| `date` (texte `YYYY-MM-DD`) | `issue_date` | parse strict ; invalide → `createdAt` + note |
| `notes` | `notes` (nouvelle) | |
| `publicToken`, `publicTokenExpiresAt` | `legacy_public_token`, `legacy_public_token_expires_at` (**nouvelles**, UNIQUE) | redirection `brefpoint.app/invoices/public/<token>` → lien signé Keevo (§9.6) ; **respecter l'expiration et refuser si la facture est annulée** (develop fait tourner le token à l'annulation : un token importé d'une facture annulée est déjà périmé) |
| `cancelledAt/By/Reason`, `validatedAt`, `deletedAt` | `cancelled_at`, `cancelled_by`, `cancellation_reason`, `converted_at`, `deleted_at` | |
| — (référence = 8 car. UUID) | `number` = **`BRF-<8 car.>`** (référence héritée) + `legacy_reference` | les nouvelles factures reçoivent `FAC-yyyy-nnnnnn` ; le compteur Keevo **n'est pas** consommé par l'import |
| — | `seller_*` instantané | rempli depuis `invoicing_settings` du tenant à l'import (nom boutique, NIU, RCCM, adresse) |
| — | `vat_registered`, `prices_include_vat` | `vat_registered = (tax > 0)` ; `prices_include_vat = false` (Bref calcule en HT) |
| `invoice_item` {reference, name, description, quantity `numeric(12,3)`, unitPrice, total} | `invoice_items` {position, description = name (+ description), quantity NUMERIC(12,3) (**déjà compatible**), unit_price INT, vat_treatment STANDARD si tax>0 sinon EXEMPT, totaux} + `product_id` (**nouvelle, nullable**) + `reference` (**nouvelle**) | `product_id` résolu par (tenant, `productKey(name, reference)`) ; sinon NULL (ligne libre). Total de ligne : Bref arrondit au franc si la quantité est décimale, Keevo arrondit HALF_UP `round(q × pu)` : résultats identiques, contrôle à ±1 XAF |
| `invoice_outbox` | ignoré (jobs d'envoi historiques) | |
| signature (jamais persistée) | — | rien à migrer |

### 6.7 Expense → Expense (nouveau module)

| Bref `expense` | Keevo `kv_x.expenses` (nouvelle table du module `expenses`) | Règle |
|---|---|---|
| `id`, `shopId`, `createdById`, `amount`, `category`, `expenseDate`, `paymentMethod`, `description`, `deletedAt` | `id`, `store_id`, `created_by`, `amount` INT, `category`, `expense_date` DATE, `payment_method`, `description`, `deleted_at` | catégories reprises telles quelles (10) ; `ownerId` implicite (tenant) |

### 6.8 Monétisation

| Bref | Keevo | Règle |
|---|---|---|
| `subscription` (status active, endsAt, billingCycle, paymentMethod, amountPaid, appleOriginalTransactionId) | `kv_x.subscriptions (plan_type=PREMIUM, expires_at = endsAt)` + `tenant_modules` (bundle du plan Bref) + `public.tenant_payments (amount_xaf = amountPaid, method, period)` + `tenant_plan_events (cause MIGRATION_BREF)` + **`public.external_subscriptions`** (nouvelle : provider apple/mycoolpay, external_id, status, renews_at) | les abonnements Apple **continuent** : le webhook RevenueCat est repointé vers Keevo et retrouve le tenant par `appleOriginalTransactionId` |
| `user.plan` sans abonnement actif (cas « payant indéfini ») | plan effectif selon **règle explicite** : si `endsAt` passé → **grâce 30 jours** offerte au moment de la migration, puis FREE | décision produit à confirmer (§12) |
| `usage_counter` (invoices, sms, email, ai_credits par mois ; `sms_free` à vie) | `public.usage_counters (tenant_id, metric, period, used)` (nouvelle, socle entitlement) | compteurs du mois en cours copiés ; `sms_free` → `sms_lifetime_free_used` |
| `account_credit.amount` | `public.tenant_credits (tenant_id, amount_xaf)` (nouvelle) | **argent réel** : doit être reporté et visible dans l'app |
| `plan_payment_request`, `apple_iap_transaction`, `sms_recharge_request`, `revenuecat_webhook_event` (develop) | tables du module `payments` (importées à l'identique pour l'historique) ; `revenuecat_webhook_event` est **indispensable** : sans elle, le routeur de webhooks à double livraison (§9.6) rejouerait sur Keevo des événements déjà traités par api-bref | |
| `refresh_token` (develop) | non migré | les sessions sont réouvertes dans l'app unifiée |

### 6.9 Correspondance des plans

| Plan Bref | Profil Keevo cible (`plan_profiles`, 16-8) | Modules du bundle (`profile_modules`) | Plafonds |
|---|---|---|---|
| free | FREE | core, catalog, crm, inventory, messaging, pos, reporting, store, **invoicing-basic** (factures/devis limités) | 1 boutique, 1 employé (Bref) vs 3 (Keevo) → **à trancher** ; 300 factures/mois ; 5 SMS à vie |
| pro | PRO (nouveau) | FREE + invoicing complet, expenses, finance, exports, ai-assistant, report-config | 3 boutiques, 3 employés/boutique, 500 crédits IA, 20 SMS/mois |
| team | TEAM (nouveau) | PRO + multi-store, counting, profitability, advanced-reports, alerts | 8 / 8, 750 IA, 50 SMS |
| business | BUSINESS (nouveau) | TEAM + whatsapp-cloud | illimité, 80 SMS |
| — | PREMIUM (Keevo actuel) | = TEAM (tous les modules premium Keevo) | illimité |
| — | PREMIUM_TRIAL | = BUSINESS pendant 6 mois (Keevo) ; **à décider** pour les nouveaux inscrits de la cible |

### 6.10 Autres tables

| Bref | Keevo | Règle |
|---|---|---|
| `client` (portail) | `public.portal_clients` (module client-portal) | global, `phone` UNIQUE ; factures retrouvées par `client_phone` normalisé **dans tous les tenants** (requête cross-schema indexée, ou table d'index `public.invoice_phone_index`) |
| `whatsapp_account`, `owner_whatsapp_template`, `customer_campaign`, `whatsapp_message`, `whatsapp_chat_message` | tables du module `whatsapp-cloud` dans le tenant | `accessToken` → chiffré au repos |
| `ai_conversation` | `kv_x.ai_conversations` (module ai-assistant) | |
| `notification` | `kv_x.notifications` (messaging, nouvelle table persistante) | Keevo n'a pas de table de notifications in-app serveur (seulement `draft_notifications`) |
| `push_token` (Expo) | `kv_x.device_tokens` | Expo push tokens inutilisables par FCM → **les appareils ré-enregistrent leur token** au premier lancement de l'app unifiée ; pas de migration |
| `otp`, `invoice_outbox`, `apple_iap_verification_request`, `web_push_subscription` | non migrés (transitoires) | |
| `campaign` (plateforme), `app_version` | `public.global_notifications` (admin), `public.app_versions` | |

---

## 7. Écarts sémantiques et décisions à trancher

Ce sont les points où les deux produits **ne disent pas la même chose avec les mêmes mots**. Chaque écart a une recommandation ; ceux marqués ⚠️ demandent une validation produit.

### 7.1 « Vente » Keevo vs « Facture de type vente » Bref ⚠️

- Keevo : une vente est un **mouvement de caisse** qui décrémente le stock, avec prix catalogue vs prix appliqué, remise globale, CASH/MOMO, clôture de journée ; la facture est un **document légal dérivé** (pont automatique).
- Bref : une vente est un **document** (facture ou devis) avec TVA, remise, livraison, acompte, échéancier ; pas de stock ; le catalogue et les clients sont **dérivés** du document.
- **Recommandation** : dans l'app unifiée, deux parcours sur le même modèle :
  1. **Caisse** (module `pos`) → `sales` + stock + facture auto (comportement Keevo) ;
  2. **Document** (module `invoicing`) → `invoices` (kind INVOICE/QUOTE) **sans** `sales` ni stock, avec **dérivation optionnelle** du catalogue et du CRM (comportement Bref, porté dans `invoicing` via les capacités `ClientLookupPort` / `ProductLookupPort`).
  - Les factures importées de Bref sont des **documents** (pas de `sales` créées, pas de mouvements de stock).
  - Le reporting devient **multi-source** : CA = ventes caisse ∪ factures émises sans vente source (`source_sale_id IS NULL`) ; les factures issues du pont ne sont pas comptées deux fois. Dashboard, rapports hebdo, rentabilité, classement vendeurs doivent lire cette union (aujourd'hui tous sur `sales COMPLETED`).
  - Un tenant choisit son **parcours par défaut** (`tenant_preferences.default_sale_flow = POS | DOCUMENT`) ; les ex-Bref démarrent en DOCUMENT, les ex-Keevo en POS.

### 7.2 Capacités manquantes dans `invoicing` Keevo pour accueillir Bref

| Besoin Bref | État Keevo | Extension |
|---|---|---|
| Remise, frais de livraison | absents | `discount_amount`, `delivery_fee` (livraison hors assiette TVA, remise avant TVA) |
| Acompte, paiements partiels, échéancier, statut d'encaissement | un seul `paid_at` | table `invoice_payments` + `payment_state` dérivé + job quotidien `OVERDUE` |
| Date d'échéance | absente | `due_date` |
| Devis + validation | absents | `kind = QUOTE`, `convert` (crée la facture, fige `issue_date` à la conversion — comportement Bref qui écrase `date`) |
| TVA par pays / par facture | taux vendeur unique | garder le taux vendeur par défaut, autoriser un `vat_rate` par facture et le toggle « sans TVA » (= EXEMPT sur toutes les lignes) |
| Édition après émission | interdite | **garder l'interdiction** (conformité DGI, story 16-9) ; les devis restent éditables ; corriger une facture = avoir + nouvelle facture (déjà en place) |
| Référence libre / UUID | numéro sans trou | `FAC-` pour les nouvelles ; `BRF-` pour l'historique ; afficher les deux |
| Templates de reçu, largeur ticket, signature | un template serveur | `invoice_template` (8 modèles portés en Thymeleaf), `ticket_width`, `signature_client_path`, `signature_seller_path` (persistance nouvelle) ; rendu local hors ligne conservé dans l'app |
| Envoi SMS/email avec quotas | WhatsApp seul | providers SMS/email + `usage_counters` |
| Lien public 90 j | lien signé 30 j | durée paramétrable + `legacy_public_token` |
| Lignes sans produit | `description` libre déjà | ajouter `product_id` nullable + `reference` |
| Quantités décimales (3 chiffres) | `NUMERIC(12,3)` déjà, arrondi HALF_UP par ligne | **aucune extension** ; l'app unifiée reprend `lib/quantity.ts` de Bref (saisie, virgule, format) |
| NUI / RCCM du client sur la facture | `client_niu`, `client_address` déjà | ajouter `client_rccm`, `client_email` |

### 7.3 Produits sans stock ✅ (tranché le 2026-11-01)

> **Décision du 2026-11-01** : le suivi de stock se règle **par produit** (`track_stock`), pas selon le secteur ni le type de boutique ; chaque boutique vend librement des produits suivis et non suivis. Livré dans la refonte par la story 17.12. La mention ci-dessous d'un défaut `false` « pour le secteur Services » est abandonnée : le secteur ne fixe pas le réglage, seuls les produits importés de Bref arrivent non suivis.


Keevo bloque une vente si `availableQuantity < qty` (`INSUFFICIENT_STOCK`), et le POS Flutter limite la quantité au stock disponible. Les produits Bref n'ont pas de stock. **Recommandation** : colonne `products.track_stock` (défaut `true` pour Keevo, `false` à l'import Bref et pour le secteur « Services »). Quand `track_stock=false` : pas de contrôle de stock, pas de mouvement SALE, pas d'alerte de seuil, pas de ligne `stock_levels`, exclusion des exports de stock et de l'inventaire. L'owner peut activer le suivi plus tard (saisie d'un stock initial).

### 7.4 Unicité des produits

Keevo : nom unique par tenant (insensible à la casse). Bref : `productKey` unique par boutique (plusieurs boutiques → même nom possible). **Recommandation** : relâcher l'unicité Keevo en `(lower(name), store_scope)` ? Non : garder l'unicité par tenant (simplicité du POS multi-boutiques) et **fusionner à l'import** les produits homonymes de boutiques différentes (même `productKey`) en un seul produit tenant, avec `migration_records` gardant les deux ids source (les lignes de facture restent intactes car elles référencent le nom). Les produits `mergedIntoId` sont importés archivés et redirigés.

### 7.5 Clients : par boutique ou par tenant ⚠️

Bref : `(shopId, phone)`. Keevo : par tenant, aucune unicité. **Recommandation** : client au niveau tenant avec `store_id` **optionnel** (boutique d'origine) et **unicité souple** sur `(tenant, phone normalisé)` appliquée par le service (avertissement, pas de contrainte DB, pour ne pas casser l'historique). À l'import, un même téléphone dans plusieurs boutiques → une seule fiche (fusion), compteurs recalculés par boutique dans la fiche.

### 7.6 Identité : collisions et comptes sans téléphone ⚠️

| Cas | Fréquence attendue | Traitement |
|---|---|---|
| Owner Bref avec téléphone **absent de** Keevo | majoritaire | création `public.users` avec l'UUID Bref |
| Owner Bref dont le téléphone **existe** dans Keevo (commerçant qui utilise les deux) | à mesurer (§12) | **liaison** : même `public.users` (celui de Keevo, hash Keevo conservé), **deuxième membership** OWNER sur le tenant importé ; au login l'utilisateur voit deux entreprises dans le tenant-picker ; option ultérieure « fusionner mes deux entreprises » (hors périmètre initial) |
| Owner Bref email-only (Google/Apple/OTP email) | à mesurer | `phone_number` NULL ; login par email/OTP/social ; invitation à ajouter un téléphone (le WhatsApp Keevo en a besoin) ; reprendre la règle develop : un email non vérifié (`emailVerified=false`) ne permet pas la liaison sociale |
| Owner Bref avec un téléphone hors Cameroun (Bref accepte +241, +235, +243, +221 — develop) | à mesurer | `PhoneNumbers.normalize` Keevo accepte tout E.164 ; mais SMS/WhatsApp, TVA et devise sont à valider pays par pays (Q12) |
| Membre Bref avec username, sans téléphone | majoritaire chez les membres | login `username + mot de passe` (hash copié) ; `phone_number` NULL |
| Membre Bref dont le username égale un téléphone d'un autre compte | improbable | contrôle à l'import |
| Même personne owner d'un tenant et employé d'un autre | aujourd'hui refusé par Keevo | **lever la restriction** `USER_ALREADY_EXISTS` dans `CreateEmployeeService` : ajouter une membership au compte existant (le modèle N-N le permet déjà) |
| Superadmin Bref (email codé en dur) | 1 | non migré ; le super-admin Keevo gère tout |

Conséquences schéma `public.users` : `phone_number` nullable, `email` UNIQUE nullable, `username` UNIQUE nullable, contrainte CHECK « au moins un identifiant », `password_hash` nullable, colonnes `auth_provider`, `social_id`, `first_name`, `last_name`, `email_verified_at`, `phone_verified_at`.

### 7.7 Rôles

Keevo OWNER/EMPLOYEE + permissions par module (seulement `invoicing` aujourd'hui) ; Bref owner/admin/seller/viewer. **Recommandation** : rôles de membership OWNER, MANAGER, EMPLOYEE, VIEWER ; chaque module déclare `permissions` dans son manifeste et `KeevoModule.permissionsFor(role)` (généralisation de `employeePermissions()`). Un VIEWER n'a aucune permission d'écriture ; un MANAGER a celles de l'EMPLOYEE + gestion de l'équipe de sa boutique + lecture CRM/produits. Les écrans Bref qui n'imposaient aucun contrôle (détail facture, viewer) deviennent conformes par construction.

### 7.8 Dates, fuseaux, montants

- Bref : UTC naïf partout, « aujourd'hui » = UTC, `invoice.date` texte. Keevo : `timestamptz`, journées en **Africa/Douala (WAT)** pour clôtures, numérotation, dashboard. À l'import : `date` → `issue_date` tel quel (date civile) ; timestamps → UTC explicite ; une facture Bref créée à 23h30 WAT le 3 est datée du 3 (champ `date`) même si `createdAt` est le 4 UTC : **utiliser `date`**, pas `createdAt`, pour les KPI.
- Montants : `decimal(14,2)` → INT XAF. Le XAF n'a pas de centimes ; les décimales Bref devraient être nulles. Contrôle d'import : rejeter (ou signaler) toute valeur non entière ; total recalculé ≠ total stocké à plus de 1 XAF → signalé dans le rapport de réconciliation (le `total` Bref peut être incohérent après un PATCH de `tax`/`discount` sans recalcul).
- Devise : Keevo XAF unique (FR86) ; Bref a `country` par boutique mais des prix en XAF. Les boutiques hors zone XAF (si elles existent, §12) sont un **cas à isoler** avant migration.

### 7.9 Quotas et crédits

Keevo n'a aucune notion de quota mensuel. Il faut au socle entitlement : `usage_counters` (tenant, metric, period), `assertAndIncrement` atomique (porter la requête `UPDATE … WHERE used < limit RETURNING` de Bref), lecture des limites dans `plan_profiles`. Les **crédits de compte** (argent) et les **packs SMS** sont des engagements commerciaux : ils doivent être reportés à l'identique et affichés.

### 7.10 Expiration d'abonnement

Depuis `develop`, le plan effectif Bref est celui de l'abonnement **en vigueur** (grâce Apple comprise) et `user.plan` n'est plus un droit ; mais la réconciliation n'est faite qu'à la demande (pas de cron), donc la colonne peut rester en retard pour un owner inactif. **Règle d'import** : appliquer la définition `SUBSCRIPTION_IN_FORCE` de `entitlement.service.ts` à la date de migration, jamais la colonne `user.plan`. ⚠️ Décision commerciale pour les owners dont l'abonnement Mobile Money est **expiré depuis peu** : (a) grâce de 30 jours offerte à la migration, puis mécanique Keevo (READ_ONLY 7 j → FREE) ; (b) FREE immédiat avec incitation à re-souscrire dans la nouvelle app. Recommandation : (a), communiquée avant la migration.

---

## 8. Architecture cible

### 8.1 Backend unifié = Keevo Spring Boot + nouveaux modules

Le socle (`shared/module`) est conçu pour greffer des modules sans toucher au cœur. Chaque capacité Bref devient un module avec son manifeste, ses migrations Flyway `db/modules/<id>/`, ses permissions, ses routes d'app et ses `DeltaEntityProvider`.

| Module cible | Provenance Bref (fichiers de référence) | Contenu | Catégorie / gating |
|---|---|---|---|
| `identity` (extension du core) | `src/auth/*`, `src/otp/*`, `social-auth.service.ts`, `src/clients/*` (hors portail) | authenticators OTP (SMS/WhatsApp/email), Google (tokeninfo + audience), Apple (JWKS), username ; colonnes `users` ; rôles MANAGER/VIEWER ; recovery contact | core |
| `messaging` (extension) | `src/sms/*`, `src/email/*`, `src/notification/*`, `src/web-push/*` | providers SMS (MboaSMS, Infobip), email (Resend), notifications in-app persistantes, Expo push optionnel, web push | core |
| `invoicing` (extension) | `src/invoice/*`, `invoice-pdf.service.ts`, `constants/vat-rates`, templates app `features/invoice/components/invoice-templates/*` | devis, échéancier, remise/livraison, TVA par facture, templates, signature, lien hérité, dérivation catalogue/CRM, `payment_state` | premium (bundle PRO) ; `invoicing-basic` gratuit limité (300 docs/mois) |
| `expenses` | `src/expense/*` | dépenses par boutique, catégories, sync offline (`CREATE/UPDATE/DELETE_EXPENSE`) | premium (PRO) |
| `finance` | `src/finance/*` (`finance-math.ts`), `owner-dashboard.service.ts` (partie finance) | résultat (CA HT − dépenses), marge, deltas, séries | premium (PRO), `requires: [expenses, reporting]` |
| `ai-assistant` | `src/ai/*` (`ai-schema.constant.ts`, providers) | conversations, chips guidés gratuits, chat libre à crédits, fournisseur configurable | PRO (chat), gratuit (chips) |
| `whatsapp-cloud` | `src/whatsapp/*`, `src/customer-campaign/*` | WABA par tenant, webhook, inbox, templates, campagnes clients, envoi de documents | BUSINESS |
| `client-portal` | `src/clients/*`, `invoice-public.controller.ts` | comptes clients finaux globaux, OTP, recherche de factures cross-tenant | plateforme (global) |
| `payments` | `src/payment-gateway/*` (MyCoolPay), `src/storekit/*`, `src/entitlement/*` (checkout, credit, recharge) | checkout, USSD/OTP, webhooks, StoreKit/RevenueCat, crédit de compte, recharge SMS, **activation automatique du bundle** via `ModuleLifecycleService` | plateforme |
| `entitlement` (extension du core) | `plan-policy.constant.ts`, `quota.service.ts`, `usage-counter.entity.ts` | profils PRO/TEAM/BUSINESS, bundles, `usage_counters`, `assertAndIncrement`, limites membres **par boutique** | core (16-2, 16-8) |
| `catalog` (extension) | `product-key.ts`, `ProductsRelationalRepository.upsertFromSale/merge` | `product_key`, `barcode`, `track_stock`, `price_manual`, fusion, dérivation depuis documents, lookup code-barres | gratuit |
| `crm` (extension) | `customer.repository.ts` | `client_type`, `store_id`, consentement marketing, dérivation depuis documents | gratuit |
| `store` (extension) | `shop.entity.ts` | category, description, logo, niu, rccm, country | gratuit |
| `admin` (extension) | `src/campaign/*`, `src/app-version/*`, `dashboard.repository.ts` | campagnes multi-canal, versions d'app forcées, **import Bref** (outil de migration, §10), console modules (16-4) | plateforme |
| `migration` (temporaire) | — | importeur idempotent, réconciliation, redirections de liens, bridge d'identité | plateforme |

**Ce que le backend Keevo doit aussi régler avant d'accueillir Bref** (déjà en backlog ou deferred) : DDL tenant sous Flyway par module (16-5 deferred), `TenantSchemaSyncService` retiré, `public.users.phone_number` nullable, claim JWT `modules` (16-3), console super-admin modules (16-4), profils configurables (16-8), stockage fichiers S3 optionnel.

**Alternative écartée** : NestJS comme backend cible. Elle impliquerait de réécrire les 14 bounded contexts, le socle modulaire, la sync, l'audit et 4 235 tests ; api-bref n'apporte aucune brique de plateforme réutilisable (tenancy, sync, audit, modules). Le seul argument (« tout en TypeScript ») ne compense pas 6 à 9 mois de réécriture à risque. Le TypeScript reste présent côté app, dashboard et contrats.

### 8.2 App mobile unifiée = Expo / React Native

**Base** : fork du shell Bref (`app/`, `components/ds`, `lib/i18n`, `lib/theme`, `lib/app-lock`, `eas.json`, workflows EAS, RevenueCat, mobile money) ; **réécriture** de la couche données.

| Couche | Choix | Référence Keevo portée |
|---|---|---|
| Stockage local | SQLite chiffré (`op-sqlite` + SQLCipher, ou `expo-sqlite` avec chiffrement) ; schéma = **les 22 tables Drift + `module_records`** ; migrations versionnées | `packages/keevo_core/lib/storage/*_table.dart` |
| File d'opérations | table `sync_queue` (id, operation, payload, createdAt, synced, retryCount, lastAttemptAt, entityId, lastError, quarantinedAt) ; push par lots de 50 ; single-flight ; dédoublonnage one-shot vs append-only | `rest_sync_service.dart` (2 106 lignes), `sync_rejection_policy.dart` |
| Pull | `GET /sync/pull` keyset, curseur en secure store, mergers par entité, `replaceUnlessPending` pour les `module_records` | `rest_sync_service.dart` |
| Politique d'erreur | transitoire / held (module inactif) / terminal → quarantaine ou rollback ; écran « opérations bloquées » | `sync_quarantine_service.dart` |
| Gate offline | J5 bannière, J6 rouge, J7 blocage des écritures ; 423 `SYNC_REQUIRED` | `sync_gate_state.dart` |
| Entitlements | snapshot `GET /tenants/me/modules` + claim JWT ; garde de route par module ; `isRouteAvailable`, `isModuleActive` | `entitlements_provider.dart`, `module_routes.dart`, `feature_modules.dart` |
| Extension slots | `GET /tenants/me/extensions/{pointId}` → actions contribuées (« Facturer ») | `extension_slot.dart` |
| Modules UI | un package par module (monorepo pnpm : `packages/core`, `packages/pos`, `packages/invoicing`, `packages/expenses`…), chargement différé, enregistrement dans un `ModuleRegistry` client ; **table feature→module** vérifiée par test | `registered_modules.dart`, `feature_modules_test.dart`, `contracts/module-routes.json` |
| État serveur | TanStack Query pour les lectures « serveur d'abord » (dashboard, activité, exports), **jamais pour les écritures métier** (elles passent par la file) | — |
| Auth | login 2 étapes + tenant-picker ; authenticators OTP/Google/Apple/username ; tokens en `expo-secure-store` ; refresh rotatif | `login_usecase.dart`, `auth_interceptor.dart` |
| Push | token FCM natif via `expo-notifications` (`getDevicePushTokenAsync`) → `POST /devices/token` | `fcm_service.dart` |
| Tests | Jest + RNTL (unitaires, mergers, politique de rejet, calculs), Maestro (parcours), contrat JSON partagé avec le backend (`contracts/`) | 388 fichiers de test Flutter comme spec |

**Deux identités d'app, un seul code** : `app.config.js` lit déjà `APP_BUNDLE_ID` / `APP_NAME` depuis l'environnement EAS. Ajouter des profils `production-keevo` (bundle de la fiche Play Store Keevo, nom « Keevo ») et `production-bref` (`com.brefpoint.app`, « Bref Point ») ; même code, même backend, **thème/marque par profil** (`EXPO_PUBLIC_BRAND`). Chaque base d'utilisateurs reçoit l'app unifiée comme **mise à jour** de l'app qu'elle a déjà installée. La convergence de marque (une seule fiche) est une décision marketing ultérieure, techniquement indépendante.

### 8.3 Dashboard web

Le dashboard Next.js reste le back-office super-admin, étendu : console modules (16-4), import/migration Bref (suivi des vagues, réconciliation, redirections), campagnes multi-canal, versions d'app, abonnements externes. Un **dashboard commerçant web** (que ni Keevo ni Bref n'ont) est hors périmètre de la fusion.

---

## 9. Stratégie de migration progressive (double piste → convergence)

### 9.1 Principes

1. **Aucun utilisateur ne réinstalle, ne perd de données, ni ne change de mot de passe.**
2. **Keevo d'abord** : ses utilisateurs sont sur le backend cible ; la nouvelle app RN est pour eux un simple changement de client. On valide l'app unifiée sur cette base avant de toucher aux données Bref.
3. **Bref par vagues** : chaque owner est migré individuellement, de façon idempotente, avec réconciliation chiffrée et retour arrière possible ; api-bref reste en production pour les owners non migrés.
4. **Les deux flux coexistent** le temps nécessaire : app Flutter Keevo + app Bref actuelle continuent de fonctionner ; la convergence se fait par mises à jour forcées progressives.
5. **Le serveur est la source de vérité** ; les données locales des appareils sont vidées (file d'opérations poussée) avant toute bascule.
6. **Observabilité de la migration** : chaque vague produit un rapport (compteurs, sommes, anomalies) archivé.

### 9.2 Vue d'ensemble des phases

| Phase | Objectif | Durée indicative | Utilisateurs impactés |
|---|---|---|---|
| **P0 — Cadrage & mesures** | chiffrer les bases (owners, boutiques, factures, membres sans téléphone, collisions de téléphone, pays), valider les décisions ⚠️ du §7, suivre les évolutions de Bref (pas de gel : décision du 03/11/2026) | 2 sem | aucun |
| **P1 — Socle d'accueil backend** | **d'abord mise en production de la refonte modulaire** (epic 16 : backfill `tenant_modules`, claim `modules`, console, canary — la prod est encore en V1, voir §2.1), puis extensions identité (OTP, social, username, rôles), `users` nullable, quotas/profils/bundles, `track_stock`, extensions invoicing (devis, paiements, remise, livraison), modules `expenses`/`finance`, providers SMS/email, outil d'import + table de correspondance, dry-run sur copie de prod Bref | 8–10 sem (+ epic 16) | aucun (API `/api/v1` reste compatible avec l'app Flutter) |
| **P2 — App RN v1 « parité Keevo »** | shell Bref + couche données Keevo + modules core/catalog/pos/inventory/crm/store/reporting/invoicing ; tests ; bêta fermée sur Keevo ; publication sous l'identité Keevo | 10–12 sem (en parallèle de P1 après ses 4 premières semaines) | utilisateurs Keevo (mise à jour) |
| **P3 — App RN v2 « parité Bref »** | parcours Document (5 étapes), devis, échéancier, templates/impression, dépenses, finance, assistant IA, WhatsApp Cloud, portail client, paiement en libre-service, PIN/biométrie, 7 langues ; publication sous l'identité Bref **mais pas encore activée pour les comptes Bref** (login Bref → « migration à venir ») | 8–10 sem | aucun côté Bref (l'app Bref actuelle reste servie par api-bref) |
| **P4 — Migration Bref par vagues** | pré-migrateur OTA dans l'app Bref actuelle, vagues (pilotes volontaires → free → payants), redirection, support | 4–8 sem selon le volume | owners Bref, vague par vague |
| **P5 — Convergence & décommissionnement** | retrait de l'app Flutter, api-bref en lecture seule puis arrêt, redirections permanentes, nettoyage des tables de migration, décision de marque | 4 sem | tous |

### 9.3 Piste Keevo : Flutter → RN sans migration de données

1. **Compatibilité API** : l'app Flutter 2.0.x continue d'appeler `/api/v1` ; les extensions P1 sont **additives** (nouvelles colonnes nullables, nouveaux endpoints, nouveaux modules). Les `DeltaEntityProvider` existants gardent leur format ; les nouveaux champs sont ajoutés, jamais renommés. Test de non-régression : la suite Flutter (388 fichiers) + `curl-tests-story-*.sh`.
2. **Flush des files locales** : avant la mise à jour, l'app Flutter doit avoir poussé sa `sync_queue`. Le serveur connaît par device (`public.user_sync_state`) la date du dernier push ; la page `/monitoring/sync` du dashboard montre les devices en retard. Mécanisme : la release Flutter N+1 (dernière) affiche « Mise à jour disponible » **uniquement** quand `sync_queue` est vide et non quarantinée ; sinon elle force une sync. Les opérations en quarantaine doivent être exportées/traitées (`/sync/blocked`) avant.
3. **Première ouverture de l'app RN** : même `applicationId` → l'app RN remplace Flutter ; la base Drift SQLCipher n'est pas lisible (clé dans le Keystore Flutter) et **n'a pas besoin de l'être** : l'app RN s'authentifie (session à ressaisir : les tokens Flutter sont dans flutter_secure_storage, non partagé) puis fait un **full pull**. Le `deviceId` est régénéré ; `user_sync_state` accepte un nouveau device. Les tables Flutter résiduelles sont supprimées au premier lancement.
4. **Déploiement** : bêta fermée (testeurs Play) → 10 % → 50 % → 100 % ; l'app Flutter reste disponible 4 semaines pour les retardataires, puis la version minimale est relevée (`app_versions`, endpoint `app-version/check` porté de Bref).
5. **iOS** : première publication Keevo sur l'App Store (identité Apple à créer ; Bref a déjà un Team ID).

### 9.4 Piste Bref : migration par vagues

**Pré-requis techniques (P1/P3)** : importeur idempotent (§10), bridge d'identité, endpoints de compatibilité minimaux dans api-bref (gel d'un owner, export d'un owner, statut de migration), pré-migrateur OTA dans l'app Bref.

**Pré-migrateur (EAS Update sur la branche `production` de l'app Bref actuelle)** : une mise à jour OTA (pas de revue des stores) qui :
- affiche une bannière « Bref Point devient … / migration prévue » ;
- force `syncAll()` puis **`resumePausedMutations()` et refuse la migration tant que des mutations sont en pause** (`usePendingMutations`), avec affichage de la liste ;
- expose `GET /v1/auth/me` → `migration: {status: none|scheduled|frozen|migrated, redirectBundle, minVersion}` ;
- quand `status = migrated` : écran « Votre compte a été migré. Mettez à jour l'application » avec lien store (même identité `com.brefpoint.app` → la mise à jour est l'app unifiée) ; connexion bloquée sur api-bref.

**Déroulé d'une vague (par owner, scriptable, idempotent)** :

| Étape | Action | Contrôle / rollback |
|---|---|---|
| 1. Sélection | owners de la vague (critères : volontaires, puis free, puis payants ; exclure les cas ⚠️ non tranchés) | liste signée dans `migration_batches` |
| 2. Notification | WhatsApp/SMS/email J-3 : date, durée (quelques minutes), consignes (ouvrir l'app, synchroniser) | taux d'ouverture suivi |
| 3. Gel | api-bref : `user.migrationStatus = frozen` → toutes les écritures de l'owner et de ses membres renvoient 423 `ACCOUNT_MIGRATING` ; lectures autorisées ; mutations en pause côté app refusées (l'app affiche le motif) | réversible (`unfreeze`) |
| 4. Export | lecture directe de la base api-bref (réplica ou `pg_dump --table` filtré par owner) → JSON par owner, checksum | taille, compteurs |
| 5. Import | importeur Keevo : tenant + schéma + données dans l'ordre du §10, `migration_records` | transaction par entité ; rejouable (`ON CONFLICT DO NOTHING` sur les ids) |
| 6. Réconciliation | comparaison automatique (§10.3) : compteurs par table, Σ `total` des ventes non annulées par boutique et par mois, Σ encaissé, Σ dépenses, nombre de clients/produits/membres, échantillon de 20 factures comparées champ à champ | **écart = vague bloquée pour cet owner**, rapport dans le dashboard |
| 7. Activation | `migrationStatus = migrated` côté api-bref (redirection), `tenants.status = ACTIVE` côté Keevo, modules du bundle activés, abonnements externes repointés, WhatsApp de confirmation avec le lien store | — |
| 8. Vérification humaine | pour les 2 premières vagues : appel/WhatsApp à chaque owner après sa première connexion | — |
| Rollback | avant l'étape 7 : `TenantDeletionService.deleteNow` (drop schéma + memberships + users orphelins) + `unfreeze` api-bref ; après l'étape 7 : refreeze + ré-import différentiel impossible → **fenêtre de retour arrière = jusqu'à l'activation** ; au-delà, correction dans Keevo | documenté |

**Ordre recommandé des vagues** : (1) 3–5 owners pilotes volontaires, (2) owners free inactifs depuis > 60 jours (faible risque), (3) owners free actifs, (4) owners payants MyCoolPay, (5) owners payants Apple (après validation du webhook RevenueCat sur Keevo), (6) owners à collision (déjà clients Keevo), (7) retardataires (gel global).

### 9.5 Coexistence et bascule des stores

| Période | App Flutter Keevo | App RN (identité Keevo) | App Bref actuelle | App RN (identité Bref) | api-bref | Keevo backend |
|---|---|---|---|---|---|---|
| P1 | prod | — | prod | — | prod | prod (+ extensions additives) |
| P2 | prod → min version relevée | bêta → prod | prod | — | prod | prod |
| P3 | retirée | prod | prod + pré-migrateur OTA | publiée (login Bref non migré → message) | prod | prod |
| P4 | — | prod | sert les owners non migrés | sert les owners migrés (**même fiche store** : la mise à jour est l'app unifiée) | prod (gel par owner) | prod |
| P5 | — | prod | — (version minimale forcée) | prod | lecture seule → arrêt | prod |

Point d'attention : dès que l'app unifiée est publiée sous `com.brefpoint.app`, **tous** les utilisateurs Bref qui mettent à jour reçoivent l'app unifiée, migrés ou non. Elle doit donc savoir parler aux deux backends pendant P4 : au login, elle interroge Keevo (`POST /auth/login` ou `/auth/otp/request`) ; si le compte est inconnu, elle appelle `api-bref /v1/auth/me/migration` et affiche « Migration prévue le … » (lecture seule de l'ancienne API possible via un **mode legacy** minimal : consulter ses factures). Alternative plus simple : **ne pas publier** l'app unifiée sous l'identité Bref avant la fin des vagues et laisser l'app Bref actuelle (OTA) gérer la transition ; les owners migrés sont alors invités à mettre à jour **après** la dernière vague. Recommandation : publier tôt (meilleure expérience pour les migrés) avec le mode legacy minimal.

### 9.6 Continuité des services externes

| Service | Pendant la migration | Après |
|---|---|---|
| Liens publics de facture `brefpoint.app/...` | api-bref répond pour les owners non migrés ; pour les migrés, api-bref renvoie 302 vers `keevo/invoices/shared?legacy=<token>` (Keevo résout `legacy_public_token`) | redirection permanente conservée côté DNS/reverse proxy |
| Webhooks RevenueCat / MyCoolPay / WhatsApp Meta | double livraison : les webhooks sont configurés vers un **routeur** (reverse proxy) qui livre à api-bref et à Keevo ; chacun ignore les événements d'owners qu'il ne connaît pas | pointés sur Keevo seul |
| Comptes WhatsApp Business (WABA) des owners | `whatsapp_account` migré (token chiffré) ; le webhook Meta est par app Meta, donc unique : le routeur le duplique | Keevo seul |
| Push | tokens Expo non migrés : l'app unifiée enregistre un token FCM au premier lancement | — |
| Emails / SMS transactionnels | mêmes providers (clés reprises) | — |
| Domaine API `api.brefpoint.app` | reste sur api-bref | redirection 410/301 vers la doc de migration |

### 9.7 Données locales sur les appareils

| Appareil | Risque | Mesure |
|---|---|---|
| Flutter Keevo | opérations en file ou en quarantaine non poussées | blocage de la mise à jour tant que la file n'est pas vide ; export CSV local comme filet (`/exports/local`) |
| Bref actuelle | mutations TanStack en pause ; mutations reprises après redémarrage sans rollback (lignes optimistes orphelines) | pré-migrateur : refus de gel tant que `pendingMutations > 0` ; détection des lignes locales sans équivalent serveur (id absent du pull) → listées à l'owner avant gel ; journal `sync-log` exporté |
| App unifiée | aucune donnée héritée lue | full pull au premier lancement |

---

## 10. Mécanique d'import Bref → Keevo (ETL)

### 10.1 Forme de l'outil

Un **module Keevo `migration`** (temporaire, catégorie platform, SUPER_ADMIN) plutôt qu'un script externe : il réutilise `TenantFactory`, `TenantSchemaProvisioner`, `ModuleProvisioner`, `PlanChangeRecorder`, `ModuleLifecycleService`, les ports des modules (création de client, produit, facture) et l'audit. Entrée : un fichier JSON par owner (export api-bref) déposé sur le serveur ou lu depuis une connexion JDBC **en lecture seule** vers un réplica de la base Bref. Sortie : `migration_records`, `migration_batches`, rapport de réconciliation JSON + page dashboard.

Un endpoint d'export côté api-bref (`GET /v1/admin/migration/owners/:id/export`, SUPER_ADMIN, streaming) évite de donner à Keevo un accès direct à la base Bref ; il sérialise, pour un owner : user, shops, membres, customers, products, invoices (+items, +payments), expenses, subscriptions, payment requests, usage counters, credit, apple transactions, whatsapp account/templates/campaigns/messages, ai conversations, notifications. C'est la seule évolution **fonctionnelle** demandée à api-bref (avec le gel et le statut de migration).

### 10.2 Ordre d'import (par owner, chaque étape idempotente)

1. **Identité** : résolution du compte owner (création ou liaison), tenant + schéma + seed (sans la boutique « Ma Boutique » : flag `seedDefaultStore=false`), `tenant_preferences` (secteur, `default_sale_flow=DOCUMENT`), événement `MIGRATION_BREF`.
2. **Boutiques** (`stores` + colonnes étendues, logos rapatriés) ; la première boutique alimente `invoicing_settings`.
3. **Membres** : comptes + memberships (MANAGER/EMPLOYEE/VIEWER) + `employees`.
4. **Catégories** : seed du secteur + catégorie « Produits importés ».
5. **Produits** : fusion des homonymes inter-boutiques, `track_stock=false`, `product_key`, `barcode`, archivés/fusionnés.
6. **Clients** : fusion par téléphone normalisé, `client_type`, consentement.
7. **Documents** : devis puis ventes, `invoice_items` (résolution `product_id` par `product_key`), `invoice_payments`, `payment_state` recalculé, `legacy_public_token`, numéro `BRF-xxxxxxxx`, instantané vendeur. **Sans** passer par `InvoiceLifecycleUseCase.issue` (pas de consommation de compteur, pas d'événements de pont) : insertion directe via un port d'import dédié du module invoicing, puis recalcul des compteurs client/produit.
8. **Dépenses**.
9. **Monétisation** : `subscriptions`, `tenant_modules` (bundle), `tenant_payments`, `external_subscriptions`, `usage_counters`, `tenant_credits`, historique IAP.
10. **Modules annexes** : WhatsApp Cloud (token chiffré), conversations IA, notifications in-app.
11. **Index portail** : `public.invoice_phone_index` (phone normalisé → tenant, invoice_id) pour `client-portal`.
12. **Provisioning** des modules activés (`ModuleProvisioner.provisionActiveModules`) et audit `TENANT_IMPORTED`.

Performances : insertion par lots JDBC (1 000 lignes), une transaction par étape ; un owner moyen (quelques milliers de factures) doit s'importer en moins d'une minute. Dry-run obligatoire sur **copie de prod** (`STAGING.md` existe déjà) avec mesure du temps par owner et de la taille des schémas.

### 10.3 Réconciliation (automatique, bloquante)

| Contrôle | Source Bref | Cible Keevo | Tolérance |
|---|---|---|---|
| Nombre de boutiques (non supprimées), de membres, de clients (après fusion : attendu = distinct phone normalisé), de produits (après fusion), de documents par type et statut, de dépenses | COUNT | COUNT | 0 (les fusions sont expliquées dans le rapport) |
| Σ `total` des ventes non annulées par boutique et par mois civil (`date`) | SUM(decimal) | SUM(total_ttc) | ±1 XAF × nombre de documents (arrondis) |
| Σ encaissé (acomptes + échéances payées) | SUM | SUM(`invoice_payments.amount` où is_paid) | idem |
| Σ dépenses par mois | SUM | SUM | 0 |
| Impayés à date (pending/partial/overdue) | formule Bref `outstanding` | `total_ttc − Σ payés` sur `payment_state ≠ PAID` | ±1 XAF/doc |
| Échantillon de 20 documents (aléatoire + les 5 plus gros) | champ à champ | champ à champ | 0 |
| Abonnement : plan, `endsAt`, crédit | valeurs | `tenant_modules`, `subscriptions.expires_at`, `tenant_credits` | 0 |
| Liens publics : 5 tokens testés | 200 | 302 → 200 | — |

Tout écart hors tolérance bloque l'activation (étape 7 du §9.4) et produit un diff lisible.

### 10.4 Idempotence et traçabilité

- Tous les ids sont conservés ; `INSERT … ON CONFLICT (id) DO NOTHING` partout ; relancer l'import d'un owner est sans effet ou complète ce qui manquait.
- `public.migration_records` lie chaque ligne cible à sa source (table, id, checksum) : permet l'audit, la réponse aux réclamations et une éventuelle ré-extraction.
- Les lignes importées portent `created_at`/`updated_at` d'origine (pas la date d'import) ; l'audit Keevo reçoit une entrée synthétique `TENANT_IMPORTED {source: bref, counts}`.
- Les `DeltaEntityProvider` voient les lignes importées comme « modifiées » à leur `updated_at` d'origine : un appareil qui ferait un pull `since` récent ne les recevrait pas. Comme tous les appareils ex-Bref font un **full pull** au premier lancement, ce n'est pas un problème ; pour un compte lié (ex-Keevo + ex-Bref), le tenant importé est nouveau donc pull complet aussi.

---

## 11. Risques et mitigations

| # | Risque | Probabilité / impact | Mitigation |
|---|---|---|---|
| R1 | **Volume d'owners Bref** rendant le schéma-par-tenant coûteux (boot, `TenantSchemaSyncService`, backups, Flyway par module × N schémas) | inconnu / élevé | mesurer en P0 ; finir la conversion du DDL tenant en Flyway par module (deferred 16-5) ; retirer la sync au boot ; si > ~1 000 tenants, prévoir des **schémas partagés pour les tenants free inactifs** (hors périmètre, mais à décider avant P4) |
| R2 | Collisions d'identité (téléphone présent des deux côtés, membres sans téléphone, usernames) | moyenne / élevé | mesure P0 ; bridge d'identité testé sur copie ; vague dédiée ; écran de réconciliation |
| R3 | Factures Bref incohérentes (`total` non recalculé après un PATCH **antérieur à `develop`**, dates invalides, décimales) | moyenne / moyen | contrôles d'import, rapport, **ne jamais corriger silencieusement** : conserver la valeur Bref dans `migration_records.note` |
| R4 | Rupture du reporting Keevo par l'ajout des documents sans vente (double comptage, KPI incohérents) | moyenne / élevé | spécifier l'union CA (§7.1) avec tests d'acceptation ; dashboards ex-Bref comparés aux KPI Bref sur l'échantillon |
| R5 | Qualité de l'app RN inférieure à Flutter (offline, conflits) | moyenne / élevé | porter la politique de rejet et les tests Flutter comme spécification ; contrats JSON partagés ; Maestro sur les parcours offline ; bêta Keevo avant tout contact avec Bref |
| R6 | Abonnements Apple interrompus (webhook, `originalTransactionId`) | faible / élevé | routeur de webhooks à double livraison ; vague Apple en dernier ; test en sandbox |
| R7 | Utilisateurs Bref qui ne mettent jamais à jour (app ancienne sans pré-migrateur) | certaine pour une minorité / moyen | OTA atteint tous ceux qui ouvrent l'app ; mise à jour forcée `app-version/check` déjà en place ; après la dernière vague, api-bref renvoie un message de migration ; données toujours accessibles via le portail |
| R8 | Perte de mutations locales Bref (mutations reprises sans rollback) | moyenne / moyen | pré-migrateur : refus de gel si `pendingMutations > 0` + détection des orphelins |
| R9 | Crédits de compte et packs SMS (argent) mal reportés | faible / élevé | contrôle de réconciliation dédié ; affichage dans l'app ; relevé envoyé à l'owner |
| R10 | Secrets Bref (WhatsApp tokens, clés MyCoolPay, RevenueCat) réutilisés hors cadre | faible / élevé | chiffrement au repos des tokens WABA ; secrets via le mécanisme fail-fast Keevo (10-3) |
| R11 | `applicationId` Keevo = `com.example.keevo` dans le dépôt | à vérifier / élevé | confirmer l'identité réelle publiée (flavors, fiche Play) avant de configurer le profil EAS `production-keevo` |
| R12 | Charge solo : backend + app + migration en parallèle | élevée / élevé | ordonnancement P1→P2 avec recouvrement limité ; BMAD `bmad-build-auto` par story comme pour la refonte ; Bref **non gelé** (décision du 03/11/2026 : en prod, correctifs mineurs continus) → journal des évolutions Bref et stories miroirs (18.3) |

---

## 12. Questions ouvertes et mesures à collecter (P0)

> **Mise à jour du 03/11/2026 :** Q10, Q11, Q12, §7.5, §7.10 et §9.5 sont tranchées (ADR-01 à ADR-09, Annexe C de `epics-fusion-keevo-bref.md`). Q9 : Keevo n'est sur aucun store (APK GitHub Releases), la marque finale est Bref Point. Plusieurs décisions s'écartent des recommandations de ce document (marque, FREE à 1 employé, expirés en FREE, clients par établissement, offre entièrement configurable) : l'annexe C fait foi.

| # | Question | Pourquoi | Où mesurer |
|---|---|---|---|
| Q1 | Nombre d'owners Bref actifs (connexion < 90 j), total, payants par plan, par méthode de paiement (Apple / MyCoolPay / admin) | dimensionne R1, l'ordre des vagues, le modèle de plans | base api-bref : `user WHERE role='owner'`, `subscription` |
| Q2 | Nombre de boutiques, documents (ventes/devis), lignes, paiements, clients, produits, dépenses, membres | durée des vagues, performances | `COUNT` par table |
| Q3 | Owners Bref sans téléphone (email-only, Google, Apple) ; membres sans téléphone | §7.6 | `user WHERE phone IS NULL` par rôle |
| Q4 | Intersection téléphones Bref ∩ `public.users` Keevo (normalisés) | vague « collisions » | jointure sur export |
| Q5 | Boutiques avec `country ≠ CM` et TVA ≠ 19,25 % ; existence de montants non entiers | §7.8 | `shop.country`, `invoice.tax/subtotal` |
| Q6 | Crédits de compte > 0, packs SMS restants | R9 | `account_credit`, `usage_counter` |
| Q7 | Nombre de factures avec `total` ≠ recalcul (PATCH antérieurs à `develop`) | R3 | requête de contrôle |
| Q8 | Owners `plan ≠ free` **sans** abonnement en vigueur (`SUBSCRIPTION_IN_FORCE`) | §7.10 | `user.plan` vs `subscription` |
| Q13 | Date de déploiement de `develop` en production api-bref (les règles de calcul des statuts et des totaux changent à cette date) | §3.7, R3 | historique des déploiements GHCR |
| Q9 | Identité réelle de l'app Keevo sur le Play Store (applicationId, signature), comptes développeur Apple/Google disponibles | R11, iOS | consoles |
| Q10 | Décision produit : plan FREE unifié (1 ou 3 employés ? 300 docs/mois ?), trial 6 mois conservé pour les nouveaux inscrits ? grille tarifaire unique ? | §6.9 | owner produit |
| Q11 | Décision de marque : une fiche store finale (laquelle ?) ou deux marques durables sur un code | §8.2 | owner produit |
| Q12 | Secteur / langue / pays : la cible reste-t-elle Cameroun-XAF (Keevo FR86) ou multi-pays (Bref autorise explicitement CM, GA, TD, CD, SN à la connexion depuis `develop`) ? | TVA, SMS, devise, fuseau des journées (WAT) | owner produit |

---

## 13. Roadmap et estimation

Hypothèse : un développeur plein temps assisté (même mode que la refonte modulaire : spécification BMAD par story puis `bmad-build-auto`), backend Keevo déjà sur `refonte/modulaire` avec epics 12–15 terminés.

| Phase | Contenu principal | Semaines | Jalon de sortie |
|---|---|---|---|
| **P0** Cadrage | mesures Q1–Q9, décisions Q10–Q12 et ⚠️ §7, product brief + PRD de fusion (BMAD), suivi des évolutions de Bref (sans gel), endpoint d'export api-bref | 2 | décisions écrites, chiffres connus |
| **P1a** Socle d'accueil | epic 16 restant (16-2 bundles, 16-3 claim modules, 16-4 console, 16-5 backfill + DDL tenant en Flyway, 16-8 profils), `users` étendu + authenticators OTP/Google/Apple/username, rôles MANAGER/VIEWER, quotas, SMS/email providers | 6 | app Flutter inchangée fonctionne ; login OTP/social possible |
| **P1b** Modules métier Bref | invoicing étendu (devis, paiements, remise, livraison, templates, signature, dérivation), `track_stock`, catalog/crm/store étendus, `expenses`, `finance`, reporting multi-source, `payments` (MyCoolPay + StoreKit/RevenueCat), `ai-assistant`, `whatsapp-cloud`, `client-portal`, `app-version` | 8 | API complète pour l'app unifiée ; tests d'acceptation « parcours Bref » sur Keevo |
| **P1c** Outil de migration | module `migration`, importeur, réconciliation, dry-run sur copie de prod Bref, redirections de liens, routeur de webhooks, bridge d'identité | 3 (recouvrement avec P1b) | 100 % des owners d'une copie de prod importés sans écart |
| **P2** App RN v1 (parité Keevo) | monorepo, couche données (stockage, file, push/pull, rejet, gate), auth 2 étapes, modules core/catalog/pos/inventory/crm/store/reporting/invoicing, tests, bêta Keevo, publication | 10–12 (démarre à S+4 de P1a) | 100 % des utilisateurs Keevo sur RN ; Flutter retiré |
| **P3** App RN v2 (parité Bref) | parcours Document, devis, échéancier, templates/impression, dépenses, finance, assistant, WhatsApp Cloud, portail, paiement libre-service, PIN/biométrie, 7 langues, profil EAS Bref, mode legacy | 8–10 | publiée sous les deux identités |
| **P4** Vagues | pré-migrateur OTA, pilotes, vagues 2→7, support | 4–8 | api-bref sans owner actif |
| **P5** Convergence | api-bref lecture seule → arrêt, redirections, nettoyage, retrait des authenticators transitoires si décidé, marque | 2–4 | une app, un backend |

**Total** : ≈ 32–40 semaines calendaires avec recouvrement P1/P2 (**7 à 9 mois**). Première valeur visible : fin de P2 (mois 3–4). Point de non-retour : début de P4.

### Prochaines étapes BMAD proposées

1. `bmad-product-brief` « Fusion Keevo × Bref » alimenté par ce document (décisions D1–D7, questions Q10–Q12).
2. `bmad-prd` de fusion : FR par module (en reprenant les FR Keevo existantes et en ajoutant les FR issues de Bref), NFR de migration (zéro perte, réconciliation, délais de gel).
3. `bmad-architecture` : mise à jour de `architecture.md` (identité étendue, invoicing étendu, reporting multi-source, app RN, migration) — ce document en est le brouillon.
4. `bmad-create-epics-and-stories` : epics « 18 Socle d'accueil », « 19 Modules Bref », « 20 App RN », « 21 Migration », « 22 Convergence », puis `bmad-sprint-planning`.

---

## Annexe A — Correspondance des routes api-bref → Keevo

| api-bref (`/api/v1`) | Keevo (`/api/v1`) | Statut |
|---|---|---|
| `POST auth/register` | `POST auth/register` (étendu : email ou téléphone, prénom/nom) | extension |
| `POST auth/login/email`, `login/phone`, `otp/request`, `otp/verify` | `POST auth/otp/request`, `auth/otp/verify` → `loginToken` + memberships | nouveau |
| `POST auth/login/google`, `login/apple` | `POST auth/social/google`, `auth/social/apple` → `loginToken` | nouveau |
| `POST auth/login/credentials` | `POST auth/login` (identifiant = téléphone ou username) | extension |
| `POST auth/refresh`, `logout`, `change-password`, `GET auth/me` | `auth/refresh`, `logout`, `change-password`, `auth/profile` | existant |
| `DELETE auth/me`, `POST auth/me/restore` | `POST account/delete`, `account/delete/cancel` | existant |
| `PATCH auth/me/recovery` | `PUT account/recovery` | nouveau |
| `clients/*` (portail) | `portal/*` | nouveau module |
| `invoices` CRUD, `validate`, `cancel`, `payments/*`, `send-to-customer` | `invoices` CRUD, `issue`, `convert` (devis), `cancel`, `payments/*`, `send` (canaux) | extension |
| `invoices/public/:token`, `public/count` | `invoices/shared?legacy=`, `portal/count` | extension |
| `shop/*`, `shop/:id/stats` | `stores/*`, `dashboard/summary?storeId` | extension |
| `shop/:shopId/customers/*`, `customers/*`, `marketing-consent` | `clients/*` (+ `storeId`), `clients/:id/marketing-consent` | extension |
| `shop/:shopId/products/*`, `suggest`, `lookup`, `merge` | `products/*`, `products/suggest`, `products/lookup?barcode=`, `products/:id/merge` | extension |
| `shop/:shopId/expenses/*`, `expenses` | `expenses/*` | nouveau module |
| `finance/summary` | `reporting/finance/summary` | nouveau module |
| `dashboard/owner-summary` | `dashboard/summary` (bloc documents ajouté) | extension |
| `dashboard/overview|growth|top-shops|activity` (SA) | `admin/analytics/*`, `admin/operations/*` | existant |
| `users/*` (membres) | `employees/*` (+ rôle MANAGER/VIEWER, username) | extension |
| `users/me/plan/*`, `storekit/*`, `webhook/pay`, `webhook/revenuecat`, `quota/*`, `plans` | `subscription/me`, `subscription/checkout/*`, `subscription/storekit/*`, `webhook/pay/*`, `webhook/revenuecat`, `subscription/usage`, `plans` | nouveau module `payments` + extension entitlement |
| `ai/*` | `assistant/*` | nouveau module |
| `campaigns/*` (SA) | `admin/notifications/global` (étendu multi-canal) | extension |
| `customer-campaigns/*`, `whatsapp/*`, `webhook/whatsapp` | `whatsapp/*` (module whatsapp-cloud) | nouveau module |
| `notifications/*`, `notifications/push-token` | `notifications/*`, `devices/token` | extension |
| `web-push/*` | `admin/web-push/*` (dashboard) | optionnel |
| `uploads` | `products/:id/photo`, `account/company/logo`, `stores/:id/logo` | existant/extension |
| `app-version/*` | `app-version/check`, `admin/app-versions` | nouveau |

## Annexe B — Catalogue de modules Keevo après fusion

| Module | Catégorie | Gratuit | Requires | Origine |
|---|---|---|---|---|
| core, sync, admin | core / platform | — | — | Keevo |
| catalog, crm, inventory, messaging, pos, reporting, store | business | oui | core | Keevo (étendus) |
| invoicing-basic | business | oui (quota) | core | fusion |
| invoicing (complet : devis, échéancier, templates, envoi multi-canal) | premium | non | catalog?, crm? (enhances) | Keevo + Bref |
| expenses | premium | non | store | Bref |
| finance | premium | non | expenses, reporting | Bref |
| exports, counting, multi-store, profitability, advanced-reports, alerts, report-config | premium | non | selon manifeste | Keevo |
| ai-assistant | premium (chips gratuits) | partiel | reporting | Bref |
| whatsapp-cloud | premium (BUSINESS) | non | crm, messaging | Bref |
| payments | platform | — | core | Bref |
| client-portal | platform | — | invoicing | Bref |
| migration | platform (temporaire) | — | core | fusion |

## Annexe C — Glossaire

- **Tenant** : une entreprise (un owner Bref, un commerçant Keevo), un schéma PostgreSQL `kv_xxxxxx`.
- **Membership** : lien compte ↔ tenant avec un rôle ; un compte peut en avoir plusieurs.
- **Document** : facture (`INVOICE`) ou devis (`QUOTE`) du module `invoicing` ; une vente Bref est un document.
- **Vente caisse** : ligne `sales` du module `pos`, avec impact stock.
- **Bundle / profil** : ensemble de modules et de plafonds associé à un plan commercial (FREE, PRO, TEAM, BUSINESS).
- **Vague** : lot d'owners Bref migrés ensemble.
- **Pré-migrateur** : mise à jour OTA de l'app Bref actuelle qui prépare et signale la migration.
- **Réconciliation** : comparaison chiffrée source/cible qui conditionne l'activation d'un owner migré.
