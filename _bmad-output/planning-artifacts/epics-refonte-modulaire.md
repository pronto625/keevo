---
stepsCompleted: [step-01-validate-prerequisites, step-02-design-epics, step-03-create-stories, step-04-final-validation]
status: complete
inputDocuments:
  - plan-refonte-modulaire.md
  - spec-socle-modulaire.md
  - review_claude_opus_8.md
  - review_qwen_6.md
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/gap-analysis-client-importateur.md
scope: "Refonte modulaire — 7 phases complètes (Epics 10-16). Préserve les Epics 1-9 existants."
decisionsLocked:
  - "Schema-per-tenant conservé (< 100 tenants) ; provisioning module = Flyway par module dans le schéma tenant"
  - "Entitlements à-la-carte hybride ; bundle = liste d'IDs ; trial HYBRIDE (6 mois global à l'inscription + 14j par module réactivé)"
  - "Blocage : enforcement TEMPS RÉEL (expires_at < now à chaque requête, cron = nettoyage/notif + @SchedulerLock)"
  - "Module payant expiré : grâce 7j LECTURE SEULE + CTA → puis MASQUÉ ; Core (gratuit) JAMAIS auto-bloqué (plafonds Free seulement)"
  - "Données conservées à VIE (réactivation = tout réapparaît) ; seule la suppression RGPD purge (DROP SCHEMA)"
  - "Plafonds Free DYNAMIQUES & éditables super-admin (table plan_profiles, plus l'enum PlanType) ; défaut Free = 1 boutique / 500 produits / 2 employés"
  - "Classement Free/Premium validé (cf. plan-refonte-modulaire.md §6.1) ; config rapports + alertes/notif owner + multi-store + counting + profitabilité + exports + facturation = Premium"
  - "Liaison modules : events + capabilities par défaut ; bridges réservés à l'orchestration ; catalogue d'events partagé"
  - "Activation super-admin only en V1 ; payment supprimé du découpage ; warehouse = type de store"
  - "Différé jusqu'à traction : Redis/HA, canary, plateformes iOS/desktop"
---

# Keevo — Epic Breakdown : Refonte Modulaire « Mini-Apps Connectées »

## Overview

Ce document décompose la **refonte modulaire** de Keevo (transformer le monolithe couplé en plateforme de mini-apps activables/désactivables par tenant) en epics et stories implémentables. Il **complète** — sans remplacer — les Epics 1-9 du produit existant. Les epics de refonte sont numérotés **10 à 16** et mappent les 7 phases de la roadmap (`plan-refonte-modulaire.md §8`).

Sources d'autorité : `plan-refonte-modulaire.md` (vision, décisions, roadmap) + `spec-socle-modulaire.md` (SDK technique). Contexte : audits (`review_*`), PRD, architecture, gap-analysis client.

## Requirements Inventory

### Functional Requirements (Modular Platform — `MOD-FR`)

> Capacités fonctionnelles que la refonte doit livrer. Ces exigences sont **transverses au socle** (Epics 13-16) sauf indication.

- **MOD-FR1 :** Le système peut installer un module à partir de son manifest (`module.json`) et l'enregistrer dans un catalogue global (`module_definitions`), de façon idempotente au démarrage.
- **MOD-FR2 :** Un super-admin peut **activer** un module pour un tenant donné, sans redéploiement.
- **MOD-FR3 :** Un super-admin peut **désactiver** un module pour un tenant : routes/permissions/handlers retirés, **données conservées à vie** (réapparaissent à la réactivation ; aucune purge sauf RGPD).
- **MOD-FR4 :** Le système résout les **dépendances dures (`requires`)** à l'activation et propose l'activation en cascade des modules manquants (avec vérification d'entitlement).
- **MOD-FR5 :** Le système **bloque la désactivation** d'un module dont d'autres modules actifs dépendent (`requires`), en listant les dépendants.
- **MOD-FR6 :** Le système **détecte les cycles** dans le graphe `requires` et refuse les états incohérents (au boot + à l'ajout d'un module).
- **MOD-FR7 :** Un module **fonctionne de manière autonome** (aucune dépendance dure vers un module optionnel) — mode dégradé si ses intégrations sont absentes.
- **MOD-FR8 :** Deux modules complémentaires **s'intègrent automatiquement via events** lorsqu'ils sont tous deux actifs (ex. facture auto sur `sale.completed`).
- **MOD-FR9 :** Un module **résout une capacité optionnelle** d'un autre module si présent (`CapabilityRegistry` → `Optional`), sinon dégrade proprement.
- **MOD-FR10 :** Un module **contribue aux points d'extension** (slots UI/workflow) d'un autre module uniquement quand les deux sont actifs.
- **MOD-FR11 :** L'activation d'un module **provisionne ses tables** dans le schéma du tenant via migrations **Flyway par module** (historique isolé `flyway_history_<id>`).
- **MOD-FR12 :** Chaque module est **entitleable individuellement (à-la-carte)** ; un **bundle** applique un ensemble de modules en un lot.
- **MOD-FR13 :** Le système gère le **cycle de vie d'un module par tenant** (`AVAILABLE→TRIAL→ACTIVE→EXPIRED(grâce 7j lecture seule)→DISABLED(masqué, données à vie)`), l'**enforcement temps réel** (`expires_at < now`), et le **trial hybride** (6 mois global + 14j par module).
- **MOD-FR13b :** Les **plafonds et profils de plan (Free/Premium) sont configurables par le super-admin** (`plan_profiles`/`profile_modules` en base, plus l'enum `PlanType`) ; le Core n'est jamais auto-bloqué.
- **MOD-FR14 :** Les **entitlements gâtent** : endpoints backend (`@RequiresEntitlement`), handlers d'events (ignorés si module off), routes & UI frontend.
- **MOD-FR15 :** Les modules actifs sont **reflétés dans le JWT** (claim `modules`) pour le gating offline ; le **backend reste la source de vérité**.
- **MOD-FR16 :** Le **super-admin (console web)** peut activer/désactiver des modules et appliquer des bundles par tenant, et consulter le statut/expiration.
- **MOD-FR17 :** Le **module pilote Facturation** : factures **manuelles autonomes** (PDF + WhatsApp) ; **facture auto** sur `sale.completed` si POS actif ; client issu du CRM si actif.
- **MOD-FR18 :** Le **frontend (Flutter)** ne monte/charge que les modules actifs du tenant (routing dynamique + slots d'extension + lazy loading).
- **MOD-FR19 :** La migration de données **backfill `tenant_modules`** des tenants existants (Core + plan courant) de manière idempotente.
- **MOD-FR20 :** Un owner peut **consulter ses modules actifs** (statut, expiration) ; un utilisateur peut **supprimer son compte et ses données** (RGPD, FR91).

### NonFunctional Requirements (Refonte — `MOD-NFR`)

- **MOD-NFR1 :** Schéma **versionné par Flyway** (suppression de `ddl-auto=update` en prod) ; migrations reproductibles & réversibles.
- **MOD-NFR2 :** **Isolation tenant maintenue** (schema-per-tenant) ; aucun accès cross-tenant ni cross-module aux données (un module ne lit jamais les tables d'un autre).
- **MOD-NFR3 :** **Aucun import direct entre bounded contexts** — uniquement `core.*`, `<autre>.api.*`, `core.event.catalog.*` ; **vérifié par ArchUnit en build bloquant**.
- **MOD-NFR4 :** **Observabilité de base** : logs JSON structurés + traces (OpenTelemetry) + métriques (Micrometer/Prometheus), corrélés `tenantId/traceId`.
- **MOD-NFR5 :** **Livraison d'events fiable & atomique** (outbox transactionnel) ; handlers **idempotents** ; relay verrouillé (`@SchedulerLock`).
- **MOD-NFR6 :** **Audit append-only au niveau DB** (révocation UPDATE/DELETE + trigger).
- **MOD-NFR7 :** Activation/désactivation **idempotentes et non destructives** (données conservées).
- **MOD-NFR8 :** **Open/Closed** : ajouter un nouveau module ne nécessite **aucune modification** du core ni des autres modules.
- **MOD-NFR9 :** **Continuité de service** : pendant toute la migration, les tenants existants et l'app en production continuent de fonctionner (approche *strangler*).
- **MOD-NFR10 :** Résolution **tenant-aware** des capacités/events (seuls les modules actifs participent).
- **MOD-NFR11 :** **Secrets externalisés** ; fail-fast si un secret de prod est absent.
- **MOD-NFR12 :** Migration vers le modèle modulaire **réversible par étape** (Flyway) avec **dry-run sur copie de prod**.

### Additional Requirements (Architecture / Spec du socle)

- **Packages `core`** à créer : `module/`, `capability/`, `extension/`, `event/` (+ `catalog/`), `entitlement/`, `admin/` (cf. `spec-socle-modulaire.md §1`).
- **Tables** : `public.module_definitions`, `public.tenant_modules`, `event_outbox` (par schéma tenant).
- **Flyway par module** : migrations sous `resources/db/modules/<moduleId>/`, schéma cible = `kv_xxxxxx`, `table=flyway_history_<moduleId>`, `baselineOnMigrate=true`.
- **Remplacement du hack runtime** `TenantSchemaSyncService` par le `ModuleProvisioner` propre.
- **Catalogue d'events partagé** `core/event/catalog` (contrats légers) — le consommateur ne dépend pas de l'`api` du producteur.
- **6 règles ArchUnit** (cf. `spec §13`) en CI bloquante dès la fin de Phase 2.
- **API super-admin** : `POST /admin/tenants/{id}/modules/{moduleId}/activate|deactivate`, `POST /admin/tenants/{id}/bundles/{bundleId}`, `GET /admin/modules`, `GET /tenants/me/modules`.
- **Décisions verrouillées** : trial **hybride** (6 mois global + 14j/module) ; **enforcement temps réel** ; module payant expiré → **grâce 7j lecture seule → masqué** ; **données à vie** (purge = RGPD only) ; **plafonds Free configurables** (défaut 1/500/2) ; Core jamais auto-bloqué ; classement Free/Premium validé (§6.1 du plan) ; activation super-admin only (V1) ; `payment` supprimé (mode de paiement sur `Sale`) ; `warehouse` = type de `store` ; ré-alignement `inventory`.
- **Différé jusqu'à traction** : Redis (externalisation d'état), HA/canary, plateformes iOS/desktop.
- **Dette consensuelle à corriger** (Phase 1) : N+1 (liste tenants, pending sales), bug `DayClosureDeltaProvider` (mauvais index), RBAC (EMPLOYEE annule brouillon), `DeviceToken.delete` sans ownership, CORS prod (`localhost:3000`), allow-list schéma `^kv_[a-z0-9]+$`.

### UX Design Requirements

> La refonte est majoritairement **architecturale/backend**. Les exigences UX dédiées (à détailler en Phase 5 / Epic 15) :
- **UX-DR1 :** Écran « **Mes modules** » (owner) — liste des modules actifs, statut, date d'expiration, CTA upgrade.
- **UX-DR2 :** **Console super-admin « Modules »** — activer/désactiver par tenant, appliquer un bundle, voir l'état (réutilise les patterns du dashboard Next.js existant).
- **UX-DR3 :** **Slots d'extension UI** — composant `ExtensionSlot(pointId)` (Flutter) rendant les contributions des modules actifs (ex. bouton « Facturer » dans le checkout POS).
- **UX-DR4 :** **Navigation dynamique** — la barre/menu ne montre que les modules activés ; états vides cohérents quand un module est désactivé.
- **UX-DR5 :** **Module Facturation** — liste factures, création manuelle, aperçu PDF, envoi WhatsApp, sélecteur client (CRM si présent, sinon texte libre).

### FR Coverage Map

| Exigence | Épic | Note |
|---|---|---|
| MOD-FR1, FR2, FR3 | **13** | Install / activate / deactivate par tenant (socle) |
| MOD-FR4, FR5, FR6 | **13** | Résolution dépendances dures, blocage, cycles |
| MOD-FR7 | **13** (activé) / **14** (prouvé) | Autonomie standalone |
| MOD-FR8 | **14** | Intégration auto via events (facture sur vente) |
| MOD-FR9 | **13** (mécanisme) / **14** (usage CRM) | Capacité optionnelle `Optional` |
| MOD-FR10 | **14** (backend) / **15** (UI slot) | Points d'extension |
| MOD-FR11 | **13** | Provisioning Flyway par module |
| MOD-FR12, FR13, FR13b | **16** | À-la-carte + bundles + trial hybride + lifecycle (grâce/temps réel) + plafonds configurables |
| MOD-FR14 | **13** (backend) / **15** (frontend) | Gating entitlements |
| MOD-FR15 | **15** (client) / **16** (claim JWT) | Miroir JWT |
| MOD-FR16 | **16** | Console super-admin |
| MOD-FR17 | **14** | Module pilote Facturation |
| MOD-FR18 | **15** | Frontend dynamique |
| MOD-FR19 | **16** | Backfill données |
| MOD-FR20 | **16** | RGPD / consultation modules |
| MOD-NFR1 | **10** | Flyway versionné |
| MOD-NFR2 | **12** | Isolation maintenue |
| MOD-NFR3 | **10** (baseline) / **12** (bloquant) | ArchUnit |
| MOD-NFR4 | **11** | Observabilité de base |
| MOD-NFR5 | **12** | Outbox atomique |
| MOD-NFR6 | **11** | Audit append-only DB |
| MOD-NFR7 | **13** | Activation idempotente/non destructive |
| MOD-NFR8 | **12** / **13** | Open/Closed |
| MOD-NFR9 | **transverse** (piloté en 12) | Continuité / strangler |
| MOD-NFR10 | **13** | Tenant-aware |
| MOD-NFR11 | **10** | Secrets externalisés |
| MOD-NFR12 | **10** / **16** | Réversibilité (backups/staging + migration) |
| UX-DR1 | **15** | Écran « Mes modules » |
| UX-DR2 | **16** | Console super-admin Modules |
| UX-DR3 | **14** / **15** | Slots d'extension |
| UX-DR4 | **15** | Navigation dynamique |
| UX-DR5 | **14** | UI Facturation |

> **Dette consensuelle** (audits) → Epic **11** ; **complétion FR produit** liée à la monétisation (FR78/79) → Epic **16** ; tests/CI dashboard + complétion Epic 9 → Epic **15**.

## Epic List

### Epic 10 : Préparation & Garde-fous (Phase 0)
**Valeur :** rendre le socle « production-safe » pour pouvoir refondre **sans risque** — migrations versionnées, secrets sécurisés, sauvegardes restaurables, staging, et filet ArchUnit. Sans cet épic, toute la refonte se ferait à l'aveugle.
**Exigences couvertes :** MOD-NFR1, MOD-NFR3 (baseline *warning*), MOD-NFR11, MOD-NFR12 (backups/staging).

#### Story 10.8 : Exécution serveur des runbooks ops (prod & staging)

> Clé tracker : `10-8-ops-runbook-execution-serveur` — créée le 2026-10-13 par la revue de 10-7 (items livrés côté dépôt, exécution serveur restante). Owner : agent ops (le déploiement backend est manuel).

As a **exploitant**,
I want **appliquer sur les serveurs les procédures livrées par 10.7 et vérifier leur effet**,
So that **les garde-fous backups / staging / sécurité soient réellement actifs en production, pas seulement documentés**.

**Items (routés depuis la revue 10-7, 2026-10-13) :**
- [ ] [Deferred-from: review 10-7, 2026-10-13] TLS staging via le Traefik prod : réseau `keevo_edge`, routeur `keevo/ops/traefik/staging-router.yml`, DNS `staging.api.keevo.cm` — checklist `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §15 ; vérifier le resolver réel (`letsencrypt` vs `mytlschallenge`) ; décider comment persister le routeur malgré la réécriture de `dynamic.yml` à chaque déploiement prod (décision owner).
- [ ] [Deferred-from: review 10-7, 2026-10-13] Secrets staging dédiés créés dans GitHub (`STAGING_JWT_*_PEM`, `STAGING_DB_PASSWORD`, `STAGING_FIREBASE_CREDENTIALS`, optionnel `DB_PASSWORD` repo-level pour la garde) — §14.
- [ ] [Deferred-from: review 10-7, 2026-10-13] S3 : politique IAM moindre privilège, versioning, Object Lock GOVERNANCE 30 j — §12 ; vérifier `aws s3 rm` → AccessDenied.
- [ ] [Deferred-from: review 10-7, 2026-10-13] Alerte de backup : `.env.backup-alert`, test d'échec §11.3, heartbeat §11.5 (alerte si pas de ping en 26 h), `BACKUP_REQUIRE_S3=1`.
- [ ] [Deferred-from: review 10-7 / v1s-15-9, 2026-10-13] Tenants `DELETED` hérités avec schéma / adhésions restants — §16 (requêtes de détection + nettoyage), confirmer la purge.
- [ ] [Deferred-from: review 11-4, 2026-10-13] `DASHBOARD_ORIGIN` renseigné dans le `.env` prod avant le prochain déploiement (CORS explicite).

**Interim AC :** **Given** la checklist §11–§16, **When** l'agent ops a exécuté chaque étape sur le serveur, **Then** chaque vérification indiquée (curl HTTPS staging 200, AccessDenied sur suppression S3, alerte reçue sur un échec simulé, 0 adhésion de tenant DELETED) est consignée avec sa date dans cette story.

**Items (routed 2026-10-28 from review 11-1/11-2):**
- [ ] [Deferred-from: review 11-1/11-2, 2026-10-28] Set `KEEVO_PROMETHEUS_TOKEN` (and optionally `KEEVO_OTLP_*`) in prod/staging `~/keevo/.env` and add the Prometheus scrape job with the bearer token [server env, Prometheus config] — until then `/actuator/prometheus` answers 404 and monitoring is silently off

## Epic 11 : Stabilisation du Socle & Dette (Phase 1 allégée)
**Valeur :** servir les tenants réels (< 100) de façon **fiable et diagnosticable** — observabilité de base + correction des bugs/sécurité consensuels. (Redis/HA différés : non requis sous ~500 tenants.)
**Exigences couvertes :** MOD-NFR4, MOD-NFR6 + dette consensuelle (N+1, bug `DayClosureDeltaProvider`, RBAC brouillon, `DeviceToken` ownership, CORS prod, allow-list schéma) ; durcissement FR68/FR93.

### Epic 12 : Découplage en Bounded Contexts (Phase 2)
**Valeur :** rendre chaque domaine **évolutif indépendamment** — c'est le prérequis *absolu* de l'activation/désactivation. API publiées par module, ArchUnit bloquant, EventBus + Outbox, ré-alignement des modules mal placés.
**Exigences couvertes :** MOD-NFR2, MOD-NFR3 (bloquant), MOD-NFR5, MOD-NFR8, MOD-NFR9 (pilotage strangler).

### Epic 13 : Moteur de Modularité — le Socle (Phase 3)
**Valeur :** **activer/désactiver un module par tenant**, sans redéploiement, avec gestion des dépendances — le cœur de la plateforme « mini-apps ».
**Exigences couvertes :** MOD-FR1-6, MOD-FR9 (mécanisme), MOD-FR10 (backend), MOD-FR11, MOD-FR14 (backend), MOD-NFR7, MOD-NFR10.

### Epic 14 : Module Pilote Facturation (Phase 4)
**Valeur :** livrer le **premier module vendable, autonome ET composable**, qui **prouve la vision** (facturation seule → +CRM → +POS auto → POS désactivé sans casse).
**Exigences couvertes :** MOD-FR7, MOD-FR8, MOD-FR9 (usage), MOD-FR10, MOD-FR17, UX-DR3, UX-DR5.

### Epic 15 : Frontend Modulaire (Phase 5)
**Valeur :** le marchand ne **voit/charge que ses modules** (mini-apps côté client) ; dashboard admin fiabilisé (tests/CI) + complétion Epic 9.
**Exigences couvertes :** MOD-FR18, MOD-FR14 (frontend), MOD-FR15 (client), UX-DR1, UX-DR3, UX-DR4 ; tests/CI dashboard ; complétion Epic 9 (9-3/9-4/9-5).

### Epic 16 : Licences, Migration & Go-Live (Phase 6)
**Valeur :** **commercialisation modulaire effective** — à-la-carte + bundles, console super-admin, conformité RGPD, migration des tenants existants, déploiement canary.
**Exigences couvertes :** MOD-FR12, MOD-FR13, MOD-FR13b (plafonds/profils configurables), MOD-FR15 (claim JWT), MOD-FR16, MOD-FR19, MOD-FR20, MOD-NFR12 (migration), UX-DR2 ; monétisation (FR78/FR79).

---

## Epic 10 : Préparation & Garde-fous (Phase 0)

> Rendre le socle « production-safe » pour refondre sans risque. **N'introduit aucune fonctionnalité** mais conditionne toute la suite. Stack : Spring Boot 3.5 / Flyway (déjà au classpath) / PostgreSQL 16 schema-per-tenant.

### Story 10.1 : Activer Flyway et établir la baseline du schéma `public`

As a **développeur plateforme**,
I want **Flyway activé avec une migration baseline reflétant le schéma `public` actuel**,
So that **les évolutions de schéma deviennent versionnées et reproductibles au lieu d'être improvisées par Hibernate**.

**Acceptance Criteria:**

**Given** la base de prod existante (tables `public` créées par `ddl-auto=update`)
**When** on active Flyway avec `baseline-on-migrate=true` et `baseline-version=1`
**Then** `flyway migrate` ne recrée rien et marque la base à la version 1
**And** `V1__baseline_public.sql` contient le DDL exact des tables `public` (`users`, `tenants`, `user_tenant_memberships`, `refresh_tokens`, `audit_log`, + futures `module_definitions`/`tenant_modules` en stories ultérieures)

**Given** une base vierge (CI / nouveau dev)
**When** l'app démarre
**Then** Flyway crée tout le schéma `public` depuis `V1` et Hibernate `ddl-auto=validate` passe sans erreur

**Implementation Notes:**
- `application.yml` : `spring.flyway.enabled: true`, `baseline-on-migrate: true`, `baseline-version: 1`, `schemas: public`, `default-schema: public`, `locations: classpath:db/migration`.
- Générer `V1__baseline_public.sql` via `pg_dump --schema-only --schema=public` puis nettoyer (retirer `OWNER TO`, commentaires) ; **inclure la colonne dangling `users.tenant_id`** telle quelle (sa suppression = story séparée).
- **NE PAS** toucher aux schémas `kv_xxxxxx` (Flyway public uniquement ; provisioning tenant = Epic 13).
- TDD : Testcontainers PostgreSQL → base vierge → `Flyway.migrate()` → `hbm2ddl validate` vert. Test « baseline idempotent » : relancer `migrate()` = no-op.
- Garder `ddl-auto=update` **temporairement** (la bascule en `validate` est en 10.2, après vérification).

### Story 10.2 : Basculer le schéma `public` en migrations versionnées (fin de `ddl-auto=update`)

As a **développeur plateforme**,
I want **toute évolution du schéma `public` passée par une migration Flyway et `ddl-auto=validate` en prod**,
So that **aucune modification de schéma non revue/non réversible n'atteigne la production (R1/MOD-NFR1)**.

**Acceptance Criteria:**

**Given** Flyway baseline en place (10.1)
**When** on ajoute une colonne à une entité `public`
**Then** la build échoue tant qu'une migration `V{n}__*.sql` correspondante n'est pas créée (Hibernate `validate` détecte le mismatch)

**Given** le profil `prod`
**When** l'app démarre
**Then** `hibernate.ddl-auto=validate` (jamais `update`) ; toute divergence schéma↔entités stoppe le boot

**Implementation Notes:**
- `application-prod.yml` : `spring.jpa.hibernate.ddl-auto: validate`. Dev peut rester `validate` aussi (recommandé) pour parité.
- Documenter le workflow « ajout de colonne public » dans `architecture.md` (remplace la section « pas de migrations »).
- Story de nettoyage incluse : `V{n}__drop_users_tenant_id.sql` (colonne dangling, cf. audit) — **après** confirmation qu'aucune lecture Hibernate ne la mappe.
- ⚠️ Les schémas tenant restent gérés hors Flyway-public jusqu'à Epic 13 (ModuleProvisioner). Documenter cette dualité transitoire.

### Story 10.3 : Externaliser les secrets et fail-fast en production

As a **responsable sécurité**,
I want **aucun secret par défaut ni fichier de credentials sur le disque applicatif, et un refus de démarrage si un secret de prod manque**,
So that **on supprime les risques de fuite et de mot de passe par défaut en prod (MOD-NFR11, audits)**.

**Acceptance Criteria:**

**Given** le profil `prod`
**When** `ADMIN_PASSWORD` (ou une clé JWT, ou les credentials Firebase) est absent
**Then** l'application **refuse de démarrer** avec un message explicite (fail-fast), au lieu d'utiliser un défaut

**Given** le déploiement
**When** on inspecte l'image/le dépôt
**Then** le fichier `keevo-*-firebase-adminsdk-*.json` n'est **pas** présent (monté/injecté au runtime via `GOOGLE_APPLICATION_CREDENTIALS`)
**And** `application.yml` ne contient plus de valeur par défaut pour `admin.password`

**Implementation Notes:**
- Retirer le défaut `ADMIN_PASSWORD:Admin@1234!` ; en prod, `@Value` sans défaut + validation `@PostConstruct` (ou `EnvironmentPostProcessor`) qui jette si vide.
- Sortir le Firebase JSON du workspace ; le monter en volume / variable au runtime. Vérifier qu'il est gitignored (déjà le cas) **et** absent de l'image Docker.
- Option coffre : variables d'env chiffrées sur le serveur (suffisant V1) ; documenter la procédure de rotation dans `docs/DEPLOYMENT_CHECKLIST.md`.
- Test : contexte Spring profil `prod` sans secrets → `ApplicationContextException`.

### Story 10.4 : Sauvegardes PostgreSQL automatisées + test de restauration

As a **exploitant**,
I want **des sauvegardes quotidiennes (rétention 30 j) et une restauration vérifiée**,
So that **une perte de données est récupérable (NFR22, MOD-NFR12)**.

**Acceptance Criteria:**

**Given** la base de prod
**When** le cron de backup s'exécute
**Then** un `pg_dump` complet (tous schémas, incl. `kv_*`) est stocké hors-serveur avec rotation 30 j

**Given** une sauvegarde
**When** on exécute la procédure de restauration sur le staging
**Then** la base est restaurée intègre et l'app démarre dessus (test documenté, exécuté au moins une fois)

**Implementation Notes:**
- Script `pg_dump -Fc` (format custom) + upload (S3/stockage objet) ; cron quotidien (systemd timer ou conteneur sidecar).
- Restauration : `pg_restore` sur staging → smoke test (`/actuator/health` + login).
- Documenter dans `docs/` ; ne pas committer de credentials de stockage.

### Story 10.5 : Environnement de staging iso-prod

As a **développeur plateforme**,
I want **un environnement staging reproduisant la prod (DB séparée, mêmes profils)**,
So that **les migrations et déploiements sont validés avant la prod (réduit R-fiabilité)**.

**Acceptance Criteria:**

**Given** la pipeline de déploiement
**When** on pousse sur la branche de staging
**Then** l'app + une base dédiée sont déployées avec le profil `staging` (clone de `prod`), sans toucher la prod

**Implementation Notes:**
- Réutiliser `deploy-backend.yml` paramétré par environnement (host/DB/domaine via secrets).
- Staging = cible des dry-runs de migration (Epic 16) et des tests canary.

### Story 10.6 : Filets d'architecture — Spring Modulith `verify` + ArchUnit (baseline *warning*)

As a **architecte**,
I want **une vérification automatique des frontières de modules en CI, en mode non bloquant au départ**,
So that **on mesure le couplage actuel et on empêche sa dégradation avant le découplage (MOD-NFR3, prépare Epic 12)**.

**Acceptance Criteria:**

**Given** le code actuel (couplage inter-domaines existant)
**When** la CI exécute `ApplicationModules.of(KeevoApplication.class).verify()` et les règles ArchUnit
**Then** un **rapport** des violations est produit **sans faire échouer la build** (mode baseline/warning)
**And** une nouvelle violation introduite est visible dans le diff du rapport

**Implementation Notes:**
- Ajouter `spring-modulith-starter-test` (BOM Spring Modulith 1.x, compatible Spring Boot 3.5) + `archunit-junit5`.
- Déclarer les modules via `package-info.java` `@ApplicationModule` au niveau des bounded contexts (`com.keevo.catalog`, `commerce`, etc.) — première passe tolérante (`allowedDependencies`).
- `verify()` en mode rapport (capturer les violations, ne pas `fail`) ; le passage en **bloquant** est Story 12.2/12.6.
- Référence : Spring Modulith `ApplicationModules.verify()` ([docs](https://docs.spring.io/spring-modulith/reference/verification.html)).

---

### Story 10.7 : Durcissement ops — backups & staging

> Clé tracker : `10-7-ops-hardening-backups-staging` (numéro 10.7 ; 10.6 = filets ArchUnit déjà livrés)

As a **exploitant**,
I want **des sauvegardes et un staging durcis (unité systemd templatée, secrets staging dédiés, alerting, S3 à moindre privilège, TLS staging via le Traefik prod)**,
So that **les garde-fous ops de 10.4 / 10.5 tiennent en production et qu'un staging HTTPS valide existe sans toucher la prod à l'aveugle (MOD-NFR11, MOD-NFR12)**.

**Acceptance Criteria:**

**Given** le VPS prod et le pipeline `deploy-staging.yml`
**When** le routage TLS staging via le Traefik prod est appliqué (réseau `keevo_edge`, router `staging`, DNS)
**Then** `https://staging.api.keevo.cm` répond avec un certificat valide et le backend staging n'est plus exposé publiquement

**Given** l'unité de backup et le déploiement
**When** un backup échoue ou `.env.backup` est absent
**Then** une alerte est émise (`OnFailure=`) et le déploiement avertit ; le chemin de l'unité est templaté et les sauvegardes S3 sont protégées (IAM moindre privilège, versioning / Object-Lock)

**Given** le staging
**When** il est déployé
**Then** il utilise des secrets JWT/Firebase/DB dédiés, `workflow_dispatch` est gardé par branche, le montage firebase est conditionnel et aucune étape mutante n'est annulable en cours

**And** chaque item ci-dessous est résolu ou explicitement rejeté avec motif (coche + commit / décision)

**Owner decision — staging TLS via prod Traefik (copied verbatim from 10-5 Review Findings, 2026-06-19):**

- [x] [Review][Decision] **TLS staging — ACME HTTP-01 sur :8080 ne sera jamais validé** — **DÉCISION (Toor) : router via le Traefik prod.** Le Traefik prod (:80/:443) gère le FQDN + ACME staging puis proxy vers `keevo_staging_backend`. ⚠️ Implémentation = réécriture cross-stack du pipeline **prod** (non testable en review, à fort enjeu) → **non appliquée à l'aveugle**, consignée comme tâche deploy-time. Étapes concrètes :
  1. **Réseau partagé** : créer un réseau Docker externe (ex. `keevo_edge`) ; y attacher le `traefik` prod ET le `keevo_staging_backend`.
  2. **Prod `traefik/dynamic.yml`** : ajouter un router `staging` (`Host(\`staging.api.keevo.cm\`)`, entrypoint `websecure`, `certResolver: letsencrypt`) + un service pointant `http://keevo_staging_backend:4500`.
  3. **Staging `deploy-staging.yml`** : retirer le Traefik staging + l'ACME + le mapping `:8443` (plus de TLS local) ; attacher le backend staging au réseau `keevo_edge` ; ne plus exposer publiquement le backend (accès uniquement via Traefik prod).
  4. **DNS** : `staging.api.keevo.cm` → IP du VPS (déjà :80/:443 prod).
  À appliquer + valider lors du 1er bring-up staging réel (touche la prod).
  — ✅ étapes 1 et 3 : `deploy-staging.yml` + gabarit `keevo/ops/traefik/staging-router.yml` · 📋 runbook `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §15 (étapes 1, 2, 4 côté serveur, vérification, rollback) — exécution serveur : 10-8

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR 10-5, 2026-06-19] Staging TLS via prod Traefik (owner decision) [traefik/dynamic.yml, deploy-staging.yml] — shared keevo_edge network + staging router; prerequisite for valid staging HTTPS — ✅ `.github/workflows/deploy-staging.yml` (no staging Traefik/ACME, no `:8080/:8443`, backend on external `keevo_edge`) + `keevo/ops/traefik/staging-router.yml` · 📋 runbook `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §15 — exécution serveur : 10-8
- [x] [Deferred-from: CR 10-5, 2026-06-19] Staging reuses prod JWT/Firebase/DB secrets [deploy-staging.yml] — staging compromise could forge prod tokens; dedicated keys — ✅ `deploy-staging.yml` (`STAGING_JWT_*_PEM`, `STAGING_DB_PASSWORD`, `STAGING_FIREBASE_CREDENTIALS`, fail-fast secret check, prod JWT key refused) · 📋 runbook §14.1
- [x] [Deferred-from: CR 10-5, 2026-06-19] workflow_dispatch without branch guard [.github/workflows/deploy-staging.yml:8] — check github.ref — ✅ `deploy-staging.yml` (job-level `if: github.ref == 'refs/heads/staging'` on both jobs)
- [x] [Deferred-from: CR 10-5, 2026-06-19] Unconditional firebase bind-mount + no .env guard [deploy-staging.yml:231] — conditional mount + [ -f .env ] || exit 1 — ✅ `deploy-staging.yml` (Firebase compose override only when the staging secret is set, leftover file removed; `[ -f .env ] || { echo "missing .env"; exit 1; }`)
- [x] [Deferred-from: CR 10-5, 2026-06-19] cancel-in-progress can cut a staging deploy mid-way [deploy-staging.yml:13] — disable cancellation on mutating steps — ✅ `deploy-staging.yml` (`concurrency.cancel-in-progress: false`)
- [x] [Deferred-from: follow-up review v1s-15-9, 2026-10-13] Legacy DELETED tenants may still hold their schema, memberships and tokens [admin/tenant/application/service/AdminTenantService.java forceDelete] — before v1s-15-9 the admin force-delete only flipped the status, and the new forceDelete refuses DELETED tenants (TENANT_NOT_FOUND), so such leftovers can no longer be cleaned through the API. Settle on prod with `SELECT id, schema_name FROM public.tenants WHERE status = 'DELETED' AND to_regnamespace(schema_name) IS NOT NULL`; if rows exist, set them DELETION_PENDING with `deletion_scheduled_at = now()` and let the daily AccountDeletionScheduler run purge them (DROP IF EXISTS, memberships, orphan users, tokens). Severity medium (unverified). — 📋 runbook `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §16 (detection query, `UPDATE … SET status='DELETION_PENDING', deletion_scheduled_at=now()`, purge confirmation, leftovers of already-dropped schemas) — exécution serveur : 10-8
- [x] [Deferred-from: CR 10-4, 2026-06-19] Backup systemd unit hardcodes /home/ubuntu [keevo/backend/scripts/keevo-backup.service:8-9] — template the path at deploy — ✅ `keevo/backend/scripts/keevo-backup.service` (`__KEEVO_HOME__`, substituted by `sed` at install in `deploy-backend.yml`)
- [x] [Deferred-from: CR 10-4, 2026-06-19] .env.backup not created/validated by deploy [keevo-backup.service, deploy-backend.yml] — EnvironmentFile=- + deploy-time WARN — ✅ `keevo-backup.service` (`EnvironmentFile=-`) + `deploy-backend.yml` (warning when `.env.backup` or the alert webhook is missing) · 📋 runbook §11.1
- [x] [Deferred-from: CR 10-4, 2026-06-19] No alerting on backup failure [keevo-backup.service] — OnFailure= unit (email/webhook) — ✅ `keevo-backup.service` (`OnFailure=keevo-backup-alert@%n.service`) + `keevo-backup-alert@.service` + `backup-alert.sh` (journald + webhook) + `backup-db.sh` strict mode (Docker down, stale lock, `BACKUP_REQUIRE_S3`, `TimeoutStartSec=2h`) + success heartbeat + CI test `keevo/backend/scripts/tests/backup-alert-test.sh` · 📋 runbook §11.3 (alert test) and §11.5 (heartbeat monitor) — exécution serveur : 10-8
- [x] [Deferred-from: CR 10-4, 2026-06-19] S3 backups lack least-privilege IAM/versioning/Object-Lock [backup-db.sh / S3 config] — attacker on box can delete backups — 📋 runbook `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §12 (IAM policy without Delete, versioning + Object Lock GOVERNANCE 30 d) + lifecycle update in `BACKUP_RESTORE.md` §8 — exécution serveur : 10-8
- [x] [Deferred-from: CR 10-4, 2026-06-19] Restore --no-owner --no-acl loses grants [DEPLOYMENT_CHECKLIST §7] — document the re-grant step — 📋 runbook `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` §13 (+ pointers in `BACKUP_RESTORE.md` §5 and §7)
- [x] [Deferred-from: CR 10-4, 2026-06-19] DEPLOYMENT_CHECKLIST §7 in root docs/ instead of keevo/backend/docs/ [docs/DEPLOYMENT_CHECKLIST.md vs keevo/backend/docs/DEPLOYMENT_CHECKLIST.md] — two checklists exist; pick one location — ✅ single checklist `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md`; root `docs/DEPLOYMENT_CHECKLIST.md` is a one-line pointer; cross-references fixed

**Implementation Notes:**
- Le routage TLS touche la stack **prod** (`traefik/dynamic.yml`) : à appliquer et valider lors du 1er bring-up staging réel, jamais à l'aveugle.
- Backups : `keevo/backend/scripts/keevo-backup.service`, `backup-db.sh`, `DEPLOYMENT_CHECKLIST` (choisir un seul emplacement).
- Staging : `.github/workflows/deploy-staging.yml`.

## Epic 11 : Stabilisation du Socle & Dette (Phase 1 allégée)

> Servir les tenants réels (< 100) de façon fiable et diagnosticable. Observabilité **de base** + correctifs consensuels des audits. **Redis/HA différés** (non requis sous ~500 tenants).

### Story 11.1 : Logs structurés JSON corrélés `tenantId`/`traceId`

As a **exploitant**,
I want **des logs JSON structurés portant `tenantId`, `userId` et un identifiant de corrélation**,
So that **je peux diagnostiquer un incident par tenant (MOD-NFR4)**.

**Acceptance Criteria:**

**Given** une requête authentifiée
**When** elle traverse l'app
**Then** chaque ligne de log est en JSON avec `tenantId`, `traceId`, `level`, `logger`, `message`
**And** aucune donnée sensible (mot de passe, token) n'apparaît dans les logs

**Implementation Notes:**
- Logback encoder JSON (`logstash-logback-encoder`) ; MDC alimenté dans `JwtAuthFilter` (après résolution du tenant) + nettoyé en `finally`.
- Vérifier le scrubbing des secrets ; niveau `INFO` prod (`WARN` root déjà en place).

### Story 11.2 : Tracing OpenTelemetry + métriques Micrometer/Prometheus (dont métier)

As a **exploitant**,
I want **des traces distribuées et des métriques techniques + métier exposées**,
So that **je mesure latence, erreurs et santé business en temps réel (MOD-NFR4)**.

**Acceptance Criteria:**

**Given** l'app en marche
**When** j'interroge `/actuator/prometheus`
**Then** j'obtiens les métriques HTTP/JVM/Hikari + métriques métier (ex. `keevo_sales_total{tenant}`, `keevo_sync_lag_seconds`, `keevo_errors_total{domainCode}`)
**And** les traces OTel sont exportables (OTLP) avec `traceId` partagé avec les logs (11.1)

**Implementation Notes:**
- `micrometer-registry-prometheus` + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`. (Spring Modulith fournira aussi des métriques/traces par module via `spring-modulith-observability` — à activer en Epic 12.)
- Compteurs métier via `MeterRegistry` dans les listeners d'events existants.
- Grafana/Prometheus auto-hébergés (léger) ou Grafana Cloud (à trancher selon budget).

### Story 11.3 : Rendre l'audit trail append-only au niveau base

As a **responsable conformité**,
I want **que la table d'audit ne puisse être ni modifiée ni supprimée, même par accès SQL direct**,
So that **l'immutabilité (NFR13/MOD-NFR6) est garantie au niveau DB, pas seulement applicatif**.

**Acceptance Criteria:**

**Given** la table `audit_log`
**When** un `UPDATE` ou `DELETE` est tenté (même par le rôle applicatif)
**Then** l'opération est refusée (trigger `RAISE EXCEPTION` ou révocation de privilèges)

**Given** l'application
**When** elle écrit un événement d'audit
**Then** l'`INSERT` réussit normalement

**Implementation Notes:**
- Migration Flyway : `REVOKE UPDATE, DELETE ON audit_log` au rôle applicatif **et** trigger `BEFORE UPDATE OR DELETE` → `RAISE EXCEPTION`.
- Appliquer dans `public` **et** au provisioning des schémas tenant (si `audit_log` est par tenant — vérifier l'emplacement réel).
- Test Testcontainers : tentative UPDATE/DELETE → exception.

### Story 11.4 : Correctifs de contrôle d'accès (RBAC & exposition)

As a **responsable sécurité**,
I want **les trous RBAC et d'exposition relevés par les audits corrigés**,
So that **aucun acteur ne dépasse son périmètre (A01/A05 OWASP)**.

**Acceptance Criteria:**

**Given** un EMPLOYEE
**When** il appelle l'annulation d'une vente brouillon (`PendingSaleController.cancelSale`)
**Then** il reçoit `403 FORBIDDEN` (annulation = OWNER-only ; validation reste autorisée)

**Given** un utilisateur authentifié
**When** il tente de supprimer un device token qui ne lui appartient pas
**Then** l'opération est refusée (vérification d'ownership)

**Given** le profil `prod`
**When** une origine non autorisée appelle l'API
**Then** le CORS la rejette (plus de `localhost:3000` hardcodé en prod ; allow-list par env)

**Given** un accès `JdbcTemplate` à une table tenant
**When** le nom de schéma est résolu
**Then** il est validé par regex `^kv_[a-z0-9]+$` avant interpolation

**Implementation Notes:**
- `@PreAuthorize("hasRole('OWNER')")` sur `cancelSale` (deferred-work P2) ; ownership check sur `DeviceTokenController.deleteToken` (filtrer par `userId` du JWT).
- `SecurityConfig.corsConfigurationSource` : retirer `localhost:3000` en prod (conditionner au profil) ; allow-list via `DASHBOARD_ORIGIN`.
- Helper `TenantSchema.validate(schema)` appliqué partout où `JdbcTemplate` interpole un schéma.

### Story 11.5 : Correctifs performance & justesse (N+1, bug d'index)

As a **développeur plateforme**,
I want **les N+1 et le bug d'index relevés par les audits corrigés**,
So that **les performances et l'exactitude des données tiennent à l'échelle (MOD-NFR4, Performance)**.

**Acceptance Criteria:**

**Given** la liste des tenants (admin)
**When** elle est chargée
**Then** elle ne déclenche pas une requête `schema_name` par ligne (N+1 éliminé via jointure/`Map` préchargée)

**Given** la liste des ventes brouillons
**When** elle est chargée
**Then** les items sont récupérés via `JOIN FETCH` (pas de N+1 lazy)

**Given** un payload de sync `day_closure`
**When** il est construit
**Then** `totalTransactions` mappe la bonne colonne (plus l'alias `totalSales`)

**Implementation Notes:**
- `AdminTenantService.enrichWithCrossSchemaMetrics` : précharger `schema_name` en un `Map<UUID,String>` ou colonne dans la requête principale.
- `SaleSpringRepository.findByStoreIdAndStatus` : `@Query` avec `JOIN FETCH DISTINCT` (analogue à `findByIdWithItems`).
- `DayClosureDeltaProvider` : corriger l'index SQL (ou étendre la requête avec `total_transactions`).

### Story 11.6 : Révocation de session < 5 min (FR68 / NFR12) sans Redis

As a **propriétaire**,
I want **qu'un employé désactivé perde l'accès en moins de 5 minutes**,
So that **la sécurité opérationnelle est garantie sans dépendre de l'expiration du JWT (NFR12)**.

**Acceptance Criteria:**

**Given** un employé avec un access token valide
**When** le propriétaire le désactive
**Then** sa prochaine requête (≤ 5 min) est rejetée `401`

**Implementation Notes:**
- Table `public.revoked_tokens` (jti, expiry) **ou** champ `tokens_valid_after` sur `user_tenant_memberships` (invalide tout token émis avant la désactivation) — solution sans Redis, suffisante < 500 tenants.
- `JwtAuthFilter` vérifie le `jti`/`iat` contre cette source ; cache court (≤ 1 min) pour limiter le coût DB.
- Purge des entrées expirées (job). (Migration vers Redis = différée, derrière une interface `TokenRevocationPort`.)
- Note : la désactivation employé existe déjà (`EmployeeController` + `JwtAuthFilter` STORE_REASSIGNED) ; cette story **ajoute la garantie de délai** manquante.

---

## Epic 12 : Découplage en Bounded Contexts (Phase 2)

> Transformer le couplage implicite (imports directs : `sync→catalog` 29, `inventory→catalog` 22, etc.) en **API publiées + events**. Prérequis absolu de l'activation/désactivation. **Base : Spring Modulith 1.x** (compatible Spring Boot 3.5) pour la vérification de modules et le registre d'events. Approche **strangler** : on découple module par module, l'app reste fonctionnelle en continu (MOD-NFR9).

### Story 12.1 : Déclarer les modules applicatifs Spring Modulith

As a **architecte**,
I want **chaque bounded context déclaré comme un module Spring Modulith avec ses dépendances explicites**,
So that **les frontières deviennent un artefact vérifiable et documenté (MOD-NFR3)**.

**Acceptance Criteria:**

**Given** les packages `com.keevo.{catalog,commerce,inventory,store,reporting,messaging,sync,subscription,identity,admin}`
**When** on ajoute un `package-info.java` `@ApplicationModule` par bounded context
**Then** `ApplicationModules.of(KeevoApplication.class)` les détecte tous comme modules nommés
**And** `Documenter.writeDocumentation()` génère le diagramme C4/PlantUML des modules et de leurs dépendances

**Implementation Notes:**
- `@ApplicationModule(allowedDependencies = {...})` au départ permissif (refléter le réel) pour ne pas casser la build.
- `core`/`shared` déclaré comme module ouvert (`type = OPEN`) consommable par tous.
- Générer la doc dans `target/modulith-docs` (artefact CI). *(2026-10-29 : chemin réel `target/spring-modulith-docs`, Documenter par défaut — cf. spec-12-1)*
- Réf : Spring Modulith [Application Modules](https://docs.spring.io/spring-modulith/reference/fundamentals.html).

> **Stories 12.2a → 12.2e** : la définition des API publiques est découpée **par module**, du plus dépendu au moins (chaque sous-story = une session dev). Patron commun : créer `{module}/api/` (named interface Spring Modulith — interfaces/ports + DTO + events uniquement, jamais d'entité JPA ni de service interne) ; marquer le reste `internal` ; remplacer chaque dépendance directe cassée par (a) un appel via `{autre}.api` ou (b) un event (12.3) ; **les 1429 tests backend restent verts à chaque étape** (filet strangler, MOD-NFR9).

### Story 12.2a : API publique du module `catalog` (le plus dépendu)

As a **architecte**, I want **exposer une `api` propre pour `catalog` et couper les imports directs de ses internals**, So that **les modules qui en dépendent (inventory, commerce, sync, reporting…) passent par un contrat stable (MOD-NFR3, MOD-NFR8)**.

**Acceptance Criteria:**
**Given** les consommateurs de `catalog` (29 imports depuis `sync`, 22 depuis `inventory`, 13 depuis `commerce`…)
**When** la story est faite
**Then** ils n'importent plus que `catalog.api` (interfaces/DTO/events) ; aucun accès à `catalog.internal`
**And** `ApplicationModules.verify()` ne reporte plus de violation entrante vers les internals de `catalog`

**Implementation Notes:** prioritaire car le plus couplé ; créer `catalog/api` (ProductLookup, CategoryLookup, events) ; basculer les consommateurs ; tests verts.

**Items (routed 2026-10-29 from 12.5):**
- [ ] [Deferred-from: 12.5 realignment, 2026-10-29] `ProductController` uses crm use cases + DTO directly (catalog → crm, 5 non-exposed violations) [catalog/product/adapter/in/web/ProductController.java] — go through a crm `api` port

- [ ] [Deferred-from: review 12-1/12-5, 2026-10-29] crm public API (`com.keevo.crm.api`, incl. future `ClientLookupPort`) and its consumers `sync` handlers (`ClientRepository`), `ProductController` [crm/, sync/, catalog/product] — crm was split out of catalog in 12.5 and has no API story of its own; published with the catalog API

### Story 12.2b : API publique du module `inventory`

As a **architecte**, I want **une `api` pour `inventory` (stock, mouvements, seuils)**, So that **`sync`, `reporting`, `commerce` y accèdent via contrat (MOD-NFR3)**.
**Acceptance Criteria:** **Given** les consommateurs d'`inventory` **When** la story est faite **Then** ils passent par `inventory.api` ; `verify()` propre côté `inventory`. *(Dépend du ré-alignement 12.5 si fait avant — sinon coordonner.)*

**Items (routed 2026-10-29 from 12.5):**
- [ ] [Deferred-from: 12.5 realignment, 2026-10-29] `ImportCsvProductsUseCase` seeds initial stock via `StockOperationService`/`MovementType` (catalog → inventory back-edge, closes catalog ↔ inventory cycles) [catalog/product/application/usecase/ImportCsvProductsUseCase.java] — inventory `api` port or a `ProductImported` event consumed by inventory

- [ ] [Deferred-from: review 12-1/12-5, 2026-10-29] Consumers of inventory internals kept by the move: `messaging` listeners (`StockAlertNotificationListener`, `TransferCreatedNotificationListener`, `OwnerAndDestinationEmployeeStrategy`), `sync` handlers (`inventory.stock.application.usecase.*`), `commerce` sale services (`StockOperationService`, `MovementType`) [messaging/, sync/, commerce/sale] — switch to the inventory `api`/events

### Story 12.2c : API publique du module `commerce`

As a **architecte**, I want **une `api` pour `commerce` (ventes, clôture)**, So that **`sync`, `messaging`, `reporting` y accèdent via contrat**.
**Acceptance Criteria:** **Given** les consommateurs de `commerce` **When** la story est faite **Then** accès via `commerce.api` (+ events au catalogue partagé) ; `verify()` propre côté `commerce`.

- [ ] [Deferred-from: review 12-2a/12-2b, 2026-10-29] `ValidateSaleService` / `SaleValidationCascadeService` check availability on the no-variant level but record the movement on the variant — pre-existing; pass `item.getVariantId()` and pin with tests [commerce/sale/application/service]

### Story 12.2d : API publiques `identity` + `store` + `subscription`

As a **architecte**, I want **des `api` pour les modules transverses très dépendus (`identity` 21+14 imports, `store`, `subscription`)**, So that **leurs consommateurs passent par contrat (MOD-NFR3)**.
**Acceptance Criteria:** **Given** `reporting→identity`, `messaging→identity`, `subscription→identity`, `catalog→store/subscription` **When** la story est faite **Then** tous via `{module}.api` ; `verify()` propre sur ces 3 modules.

*(2026-10-29: the `shared → crm/inventory` item first routed here moved to Story 12.2f.)*

- [ ] [Deferred-from: review 12-2a/12-2b, 2026-10-29] One tenant convention for published ports (explicit `TenantId` vs ambient `TenantContext`) [catalog/api, crm/api, inventory/api]

### Story 12.2e : API publiques `reporting`/`messaging`/`sync` + activation `verify()` BLOQUANT

*(2026-10-29, décision déléguée — spec-12-2e) : le passage de `verify()` en BLOQUANT est déplacé à la fin de 12.6, après 12.2f (extraction du core) et 12.3/12.4 — impossible tant que 107 cycles et les violations `shared → X` subsistent. 12.2e = API publiques messaging/reporting/feedback + convention tenant unique.*


As a **architecte**, I want **finaliser les dernières `api` et passer `verify()` en build bloquant**, So that **toute régression de frontière casse désormais la CI (MOD-NFR3, MOD-NFR8)**.
**Acceptance Criteria:**
**Given** tous les modules dotés de leur `api`
**When** on passe `allowedDependencies` en strict + `verify()` en mode `fail`
**Then** la build est **verte** et **échoue** si un nouvel import cross-module hors `api`/events est introduit
**And** le diagramme de modules (Documenter) est à jour

**Implementation Notes:** clôture d'Epic 12 côté frontières ; combiner avec les règles ArchUnit complémentaires (12.6).

- [ ] [Deferred-from: review 12-2c/12-2d, 2026-10-29] identity depends on the whole `reporting` module and its internals (`DailyReportFormatter`, `EndOfDayReportBuilder`) — route through a reporting `api` [identity/package-info.java]
- [ ] [Deferred-from: review 12-2c/12-2d, 2026-10-29] commerce/catalog/crm/inventory ports rely on the ambient tenant — align with the explicit `tenantSchema` + `TenantContext.requireCurrent` convention of identity/store/subscription [commerce/api, catalog/api, crm/api, inventory/api]

### Story 12.2f : Extraire le `core` — `shared` ne dépend plus d'aucun module métier

*(Story créée 2026-10-29 par le run autonome de la refonte — clé seule, story file à créer par bmad-create-story.)*

Scope: `shared` (future `core`) still references ~400 internal types of business modules: JPA entities for Product/Stock*/Supplier/Client/Transfer in `shared.infrastructure.persistence.entity`, identity Spring repositories, `AuditEventListener` consuming every module's events, enums such as `ProductStatus`, `TransferStatus`, `InventoryScope`. Move each entity/repository back to its owning module (`adapter/out/persistence`), turn the audit listener into per-module contributions or consume events via the event catalogue (12.3), and extract the shared kernel (`Money`, `TenantId`, `ActorId`, `DomainEvent`) into `core`.

- [ ] [Deferred-from: review 12-1/12-5, 2026-10-29, re-routed from 12.2d] `shared` depends on crm/inventory internals (`StockMovementJpaEntity`/`StockTransferJpaEntity` → `MovementType`/`TransferStatus`, `AuditEventListener` → crm/inventory events, Product/Stock/Supplier/Client JPA entities) [shared/infrastructure/persistence/entity, shared/infrastructure/web/AuditEventListener.java]
- [ ] [Deferred-from: 12.2a/12.2b implementation, 2026-10-29] Remaining `shared → catalog` (8, `ProductStatus` in `ProductJpaEntity`) and `shared → inventory` (8, `TransferStatus` ×7, `InventoryScope`)

**AC (interim):** Given the architecture report, when regenerated, then no violation has `shared` as source, and `shared`'s `allowedDependencies` is empty (it depends on no business module).

- [ ] [Deferred-from: review 12-2c/12-2d, 2026-10-29] identity ↔ subscription cycle (identity checks plan limits and records plan-change events; subscription reads tenants and `PlanType`) — split tenant vs plan ownership [identity/auth, subscription/plan]
- [ ] [Deferred-from: review 12-2c/12-2d, 2026-10-29] `StoreCountAdapter` / `UserCountAdapter` (subscription) read store/identity tables over JDBC — move counts to the owning modules as `subscription.api` SPI implementations, like `ProductCountAdapter`/`EmployeeCountAdapter` [subscription/plan/adapter/out]

- [ ] [Deferred-from: review 12-2e, 2026-10-29] `messaging.api.NotificationPort` keeps a historical `tenantId` and no `requireCurrent` guard — align with the published-port tenant convention [messaging/api/NotificationPort.java]

### Story 12.3 : Migrer les events vers le registre Spring Modulith + catalogue partagé

As a **développeur plateforme**,
I want **les événements inter-modules publiés via le registre d'events persistant de Spring Modulith**,
So that **la livraison est fiable/atomique (outbox) et les modules sont découplés (MOD-NFR5, MOD-FR8)**.

**Acceptance Criteria:**

**Given** une mutation métier qui publie un event (ex. `SaleCompletedEvent`)
**When** la transaction commit
**Then** l'event est persisté dans `event_publication` (PUBLISHED) puis traité par les listeners en transaction séparée
**And** si un listener échoue, la publication reste incomplète et est **rejouée au redémarrage**

**Given** un consommateur (ex. audit, notifications)
**When** il écoute un event d'un autre module
**Then** il utilise `@ApplicationModuleListener` et le contrat d'event vit dans `core/event/catalog` (pas d'import de l'`api` du producteur)

**Implementation Notes:**
- Ajouter `spring-modulith-starter-jdbc` (registre via JDBC, table `event_publication`) + `spring.modulith.events.republish-outstanding-events-on-restart: true` + `completion-mode: UPDATE` (inspection) ou `ARCHIVE`.
- **Schéma de la table `event_publication` géré par Flyway** (`spring.modulith.events.jdbc.schema-initialization.enabled: false` + migration dédiée) — cohérent avec Epic 10.
- Remplacer les `ApplicationEventPublisher` directs + listeners `@EventListener` synchrones par `@ApplicationModuleListener` (= `@Async` + `REQUIRES_NEW` + `@TransactionalEventListener`).
- **Idempotence des handlers obligatoire** (rejeu possible) — clé d'idempotence par `(eventId, listenerId)`.
- Réf : [Spring Modulith — Working with Events](https://docs.spring.io/spring-modulith/reference/events.html).
- ⚠️ Cette story **remplace** la section §7 « Outbox maison » de `spec-socle-modulaire.md`.

**Items (routed 2026-10-28 from refonte-journal-incoherences.md):**
- [ ] [Deferred-from: journal A3, 2026-10-28] Bump spring-modulith-bom 1.4.0 → latest 1.4.x patch [keevo/backend/pom.xml:36] — 1.4.x is the Boot 3.5 line; do not move to 2.x (Boot 4)
- [ ] [Deferred-from: journal A4, 2026-10-28] Choose `spring.modulith.events.completion-mode` (UPDATE + purge job vs ARCHIVE vs DELETE) [application.yml] — decide with 12.4 retention needs

- [ ] [Deferred-from: review 12-2e, 2026-10-29] Draft tracking: catalog calls `messaging.api.DraftValidationCommandPort` synchronously; messaging could consume `ProductCreatedProgressivelyEvent` / `ProductActivatedEvent` instead, removing the catalog → messaging edge (and a catalog ↔ messaging cycle) [catalog/product/application/usecase, messaging/notification]

### Story 12.4 : Intégrer le registre d'events avec le schema-per-tenant

As a **développeur plateforme**,
I want **que le registre d'events fonctionne correctement en multi-tenant schema-per-tenant**,
So that **chaque event est traité dans le bon contexte tenant sans fuite (MOD-NFR2, MOD-NFR10)**.

**Acceptance Criteria:**

**Given** un event publié dans le tenant `kv_000042`
**When** il est rejoué (au restart ou après échec)
**Then** le listener s'exécute avec `TenantContext = kv_000042` reconstitué depuis le `tenantId` porté par l'event
**And** aucune écriture ne se produit dans le mauvais schéma

**Given** la table `event_publication`
**When** on choisit son emplacement
**Then** elle vit dans le schéma `public` (registre global), l'isolation étant assurée par le `tenantId` de l'event + restauration du `TenantContext`

**Implementation Notes:**
- **Point d'intégration critique** (identifié en recherche) : le registre Spring Modulith est mono-DataSource ; placer `event_publication` en `public`.
- Tout `DomainEvent` du catalogue **doit** porter `tenantId` (déjà dans l'enveloppe standard).
- Wrapper de dispatch : avant d'invoquer le listener, `TenantContext.setCurrentTenant(event.tenantId())` ; `finally` clear. Vérifier la compatibilité avec `@Async` (le `ThreadLocal` ne traverse pas les threads — repositionner le contexte **dans** le listener via un aspect/décorateur sur `@ApplicationModuleListener`).
- Test multi-tenant : publier dans 2 tenants, forcer un échec+rejeu, vérifier l'absence de cross-tenant.
- Si l'intégration s'avère trop contrainte : repli documenté = conserver un dispatch maison tenant-aware (mais privilégier Modulith).

**Items (routed 2026-10-28 from refonte-journal-incoherences.md):**
- [ ] [Deferred-from: journal A2, 2026-10-28] Pin the registry to `public`: `spring.modulith.events.jdbc.schema=public`, table created by a Flyway public migration [application.yml, db/migration] — unqualified `event_publication` would follow the per-tenant search_path
- [ ] [Deferred-from: review 11-1/11-2, 2026-10-28] Propagate MDC (`traceId`, `tenantId`, `userId`) to `@Async` executors (e.g. `AsyncConfig.notificationExecutor`) with a context-propagating `TaskDecorator`, alongside the `TenantContext` restoration [shared/infrastructure/config/AsyncConfig.java] — async logs are not correlated today

- [ ] [Deferred-from: review 12-2a/12-2b, 2026-10-29] `MovementType` exported via `inventory :: events` (carried by `StockAdjustedEvent`) — API-level kinds in `core.event.catalog`; collapse the type-level `@NamedInterface("events")` workaround (journal A9)

### Story 12.5 : Ré-aligner les modules mal placés (inventory, payment, warehouse)

As a **architecte**,
I want **les transferts/stock/counting regroupés sous `inventory`, `payment` supprimé et `warehouse` clarifié**,
So that **la structure reflète les vraies frontières de modules (MOD-NFR8, décisions verrouillées)**.

**Acceptance Criteria:**

**Given** les transferts inter-boutiques actuellement dans `catalog/stock`
**When** le ré-alignement est fait
**Then** stock/transfer/counting vivent sous le module `inventory` avec une `api` propre, et `catalog` n'expose plus la logique de stock
**And** les placeholders vides `commerce/payment`, `inventory/stock`, `inventory/transfer`, `store/warehouse` sont supprimés
**And** le mode de paiement reste porté par l'agrégat `Sale` ; `warehouse` reste un type de `store`

**Implementation Notes:**
- Refactor de packages (déplacement) + mise à jour des imports vers les nouvelles `api`.
- Conserver les endpoints REST stables (pas de breaking change client) — seuls les packages internes bougent.
- `verify()` doit rester vert après déplacement.
- *(2026-10-29, décision déléguée — spec-12-5)* : le module `crm` (clients/fournisseurs) est aussi extrait de `catalog/contact` vers `com.keevo.crm.contact` (plan §5) ; les transferts restent dans `inventory.stock` (sous-découpage tranché en 12.2b).
- Migrations Flyway inchangées (tables identiques) ; pur refactor de code.

### Story 12.6 : Règles ArchUnit complémentaires (graphe `requires`, capabilities, schéma)

*(2026-10-29) : reçoit l'activation BLOQUANTE de `verify()` (cycles + non exposés), après 12.2f et 12.3/12.4 — cf. note 12.2e.*

As a **architecte**,
I want **des règles ArchUnit pour ce que Spring Modulith ne couvre pas**,
So that **les invariants spécifiques Keevo sont garantis en CI (MOD-NFR2/3)**.

**Acceptance Criteria:**

**Given** la CI
**When** les tests d'architecture s'exécutent
**Then** ils échouent si : (a) un `JdbcTemplate` interpole un schéma non validé par `^kv_[a-z0-9]+$` ; (b) `@Entity/@Repository` hors de `adapter/out/persistence` ; (c) un type HTTP/Spring-web dans `port/in` ou `application/service` ; (d) le graphe `requires` (lu des futurs manifests, Epic 13) contient un cycle

**Implementation Notes:**
- `archunit-junit5` ; règles `classes().that()...should()`.
- La règle (d) sera complétée quand les manifests existent (Epic 13) — créer le test, l'activer alors.
- Combiner avec `verify()` de 12.2 dans une suite « architecture » dédiée, bloquante.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR 10-6, 2026-06-19] ArchUnit/Modulith baseline -> ratchet [ArchitectureTest / ModulithVerificationTest] — assert known-violation set, fail on new ones — ✅ 12.6b: Modulith known set = 0, `verify()` blocking (`ApplicationModulesBaselineTest.verifyModuleBoundaries`); ArchUnit rules ratchet on `src/test/resources/architecture/*-allowlist.txt` (new violation or stale entry fails, `KeevoArchitectureRulesTest`)
- [x] [Deferred-from: CR 10-6, 2026-06-19] allowedDependencies target vs actual [package-info allowedDependencies] — align identity/messaging/catalog/store before enforcement — ✅ every module has an explicit list including `shared` (12.2x/12.6a: identity `{shared, store :: api}`, messaging `{commerce/identity/store :: api, shared}`, catalog `{crm/store/subscription :: api, shared}`, store `{shared}`); enforced by blocking `verify()` (12.6b)
- [x] [Deferred-from: CR 10-6, 2026-06-19] Wrap detectModulesAndGenerateDocumentation in try/catch [Modulith doc test] — invalid model must not break the warning-mode build — ✅ 12.6b: failure reported in `target/spring-modulith-docs/GENERATION-FAILED.txt`, no crash
- [x] [Deferred-from: CR 10-6, 2026-06-19] Refine ArchUnit rules (persistence pattern, DO_NOT_INCLUDE_TESTS) [ArchitectureTest] — - — ✅ 12.6b: `@Entity`/`@Embeddable`/`@MappedSuperclass`/`@Repository` + Spring Data `Repository` only under `..adapter.out.persistence..` (audit entity/repository moved to `shared.audit.adapter.out.persistence`; core-wide `JpaBaseEntity` allow-listed), all rules on main classes only (`DO_NOT_INCLUDE_TESTS`). AC (a) is enforced by the pair ArchUnit rule (a) (class level) + `TenantSchemaInterpolationGuardTest` (line-level source scan); AC (c) covers all of `..domain..` + `..application..`
- [x] [Deferred-from: CR 10-6, 2026-06-19] CI upload-artifact without if-no-files-found: warn [.github/workflows/backend-ci.yml:62,69] — missing reports fail the job — ✅ 12.6b: `if-no-files-found: warn` on both uploads
- [x] [Deferred-from: CR 10-6, 2026-06-19] Dependency scopes (spring-modulith-api provided, archunit via BOM) [pom.xml] — avoid version skew vs pinned 1.3.0 — ✅ 12.6b: pinned `archunit-junit5` 1.3.0 removed; `archunit` now comes transitively from `spring-modulith-core` 1.4.14 (its own pom fixes archunit 1.4.2 — the Modulith BOM does not manage archunit), resolved `archunit:1.4.2:runtime`; `provided` for `spring-modulith-api` rejected with reason: spring-modulith-starter-jdbc already brings it at compile scope (BOM version). Excluding modulith-core/archunit from the prod jar → 13.2

**AC (interim):** every routed item above is resolved or explicitly rejected with a reason, in addition to the epic's own ACs.

**Items (routed 2026-10-29 from 12.3):**
- [x] [Deferred-from: 12.3 implementation, 2026-10-29] identity ↔ messaging cycle (API-only: identity → `messaging.api.WhatsAppPort` for OTP/account deletion/test report; messaging → identity query ports) — core-owned notification port implemented by messaging, or recipient data carried by events [identity/*, messaging/notification] — must be gone before verify() becomes blocking — ✅ 12.6a: notification contracts in `shared.capability.notification` (implemented by messaging), report `✓ No module violations detected.` (0 cycles)

- [x] [Deferred-from: review 12-3/12-4, 2026-10-29] Four `@Async @EventListener` notification listeners fire before commit and are not durable (`DayClosureNotificationListener` push, `StockAlertNotificationListener`, `StockModificationNotificationListener`, `ProductCreationNotificationListener.onCsvImportCompleted`) — move to after-commit module listeners (timing change: no push for rolled-back actions) [messaging/notification/application/listener] — ✅ 12.6a: the four listeners are `@ApplicationModuleListener` (+ `@DeliverOnce` where sending), rollback IT `stockAlertListener_afterCommitOnly`

- [x] [Deferred-from: review 12-6a, 2026-10-29] Rule: publishers of events consumed only by after-commit module listeners (`DayClosedEvent`, `StockThresholdBreachedEvent`, `StockAdjustedEvent`, `CsvImportCompletedEvent`…) must publish inside a transaction — ArchUnit/registry test, else the notification is silently dropped [architecture tests] — 12-6b — ✅ 12.6b: rule (e) `moduleListenedEvents_publishedInsideATransaction` (event types = parameters of every `@ApplicationModuleListener`; + `@TransactionalEventListener` AFTER_* without fallbackExecution; 1 allow-listed: `SaleSyncHandler#apply(SyncOperation,UUID,String)`, runs in SyncPushService's TransactionTemplate)

### Story 12.7 : Rendre le moteur de synchronisation « module-aware »

As a **développeur plateforme**,
I want **que le moteur de sync offline ne traite que les opérations des modules actifs et que chaque module enregistre ses propres handlers**,
So that **l'offline-first reste cohérent quand on active/désactive/ajoute des modules (MOD-NFR9, MOD-NFR10 ; comble le gap G1)**.

**Acceptance Criteria:**

**Given** un tenant dont un module est **inactif**
**When** une opération de sync de ce module arrive (ex. `CREATE_INVOICE` alors que `invoicing` est off)
**Then** elle est **rejetée proprement** (réponse non bloquante + log + marquage côté client), sans corrompre le batch ni les autres opérations

**Given** un nouveau module (ex. `invoicing`)
**When** il est installé
**Then** il **enregistre ses `SyncOperationHandler`** via le contrat `KeevoModule` (le `SyncOperationHandlerRegistry` n'a plus de liste figée en dur)

**Given** un module actif
**When** ses opérations sont rejouées
**Then** le comportement est inchangé (non-régression des handlers existants : `CREATE_SALE`, `CREATE_PRODUCT`, etc.)

**Implementation Notes:**
- Aujourd'hui `SyncOperationHandlerRegistry` mappe en dur ~18 handlers (`commerce/sale`, `catalog/*`, `inventory/*`…). Le rendre **extensible** : les modules contribuent leurs handlers au registre à l'activation (cf. `KeevoModule.registerCapabilities`/équivalent sync).
- Garde au dispatch : `ModuleRegistry.isActive(tenant, moduleOf(operationType))` → sinon rejet `MODULE_NOT_ENTITLED`/`MODULE_INACTIVE` (le client purge/repush selon politique).
- Côté Flutter : la `sync_queue` ne doit pas boucler sur une op rejetée pour module inactif (marquer `failed`/`skipped`).
- Dépend de 13.2 (`ModuleRegistry`) — séquencer 12.7 **après** 13.2 ou stubber le registre.

---

## Epic 13 : Moteur de Modularité — le Socle (Phase 3)

> Construire le noyau qui permet **d'activer/désactiver un module par tenant** avec gestion des dépendances. Cœur de la plateforme « mini-apps ». Réf : `spec-socle-modulaire.md §1-10`.

- [x] [Deferred-from: review 13-2, 2026-10-29] Source of `moduleOf(operationType)`: manifest field (e.g. `syncOperations`, validated by the loader) or per-handler declaration — every existing handler mapped, unmapped op types fail the boot/test [sync] — ✅ 12.7: per-handler declaration `SyncOperationHandler.moduleId()` (required) on the 19 handlers; `SyncOperationHandlerRegistry` fails the boot naming the handler (module not installed / no type / type handled twice) — `SyncOperationHandlerRegistryTest`, mapping asserted on the real beans in `SyncModuleGuardPostgresIntegrationTest.handlerModuleMapping`

- [x] [Deferred-from: review 13-2, 2026-10-29] Guard placement: read `activeModules(schema)` once per push batch before the loop, check per operation before `processOperation`; a registry failure → that operation REJECTED (not a batch-wide 500); distinguish `MODULE_INACTIVE` vs `MODULE_READ_ONLY` once 13.5 exposes grace [SyncPushService] — ✅ 12.7: `SyncPushService` reads once per batch, guard after replay detection and before LWW/handler, `MODULE_INACTIVE`; registry failure → every non-replayed op REJECTED `INTERNAL_ERROR`, HTTP 200 — `SyncPushServiceModuleGuardTest` (9). `MODULE_READ_ONLY` stays with 13.5 (already routed there) — ✅ 13.5: READ_ONLY → REJECTED `MODULE_READ_ONLY` (held on the device like `MODULE_INACTIVE`), `SyncPushServiceModuleGuardTest`, `SyncModuleGuardPostgresIntegrationTest.crmReadOnly_*`

- [x] [Deferred-from: review 13-2, 2026-10-29] Full-context proof of the per-request registry cache through a real HTTP call (MockMvc sync push: N operations → 1 `tenant_modules` query) [shared.module cache] — ✅ 12.7: `SyncModuleGuardPostgresIntegrationTest` (MockMvc `POST /api/v1/sync/push`, real JWT chain, spy on `TenantModuleStatePort`: 2 and 3 operations → 1 read)

### Story 13.1 : Modèle de données du registre + manifest + seed au boot

As a **développeur plateforme**,
I want **les tables `module_definitions`/`tenant_modules` et le chargement des manifests `module.json` au démarrage**,
So that **la plateforme connaît les modules installés et leur état par tenant (MOD-FR1)**.

**Acceptance Criteria:**

**Given** des modules embarquant un `module.json` valide
**When** l'app démarre
**Then** `module_definitions` est seedé idempotemment (id, version, category, requires, enhances, is_free, manifest JSONB)

**Given** un `module.json` invalide (id inconnu dans requires, JSON malformé)
**When** l'app démarre
**Then** le boot échoue avec un message clair (fail-fast)

**Implementation Notes:**
- Migration Flyway `public` : `module_definitions`, `tenant_modules` (cf. spec §2.1/2.2, états `AVAILABLE|TRIAL|ACTIVE|EXPIRED|DISABLED`, `UNIQUE(tenant_id, module_id)`).
- `ModuleManifest` (record) + désérialisation Jackson + validation (Bean Validation + cohérence des ids/dépendances).
- Scan classpath des beans `KeevoModule` (ou ressources `module.json`) ; seed via `ApplicationRunner` idempotent.
- TDD : Testcontainers (seed idempotent, relance = no-op) + unit (manifest invalide → exception).

- [x] [Deferred-from: review 12-6b, 2026-10-29] `RequiresGraphAcyclicityTest` manifest loading: glob `classpath*:**/module.json` (or the chosen manifest path), assert unique non-blank `id`, `requires` must be an array — else the cycle check silently sees one manifest / loses edges [architecture tests] — enable the test with the manifests — ✅ 13.1: enabled (no `@Disabled`), reads the manifests with the production `ModuleManifestLoader` (glob `classpath*:keevo/modules/*/module.json`, the one the boot seed uses — no second parser): unique non-blank ids, `requires` a JSON array of known ids, acyclic; + all glob matches loaded and exactly 10 manifests, none outside `src/main/resources/keevo/modules/<id>/` and none in test resources (`noManifestOutsideTheModulesDirectory`); `com.keevo.architecture.**` 28 tests, 0 failures, 0 skipped

### Story 13.2 : `ModuleRegistry`

As a **développeur plateforme**,
I want **un registre interrogeable de l'état des modules (installés/actifs par tenant)**,
So that **tout composant peut savoir si un module est actif pour un tenant (MOD-FR1, MOD-NFR10)**.

**Acceptance Criteria:**

**Given** un tenant avec un sous-ensemble de modules actifs
**When** on appelle `isActive(tenant, moduleId)` / `activeModules(tenant)`
**Then** le résultat reflète `tenant_modules` (statut ∈ {TRIAL, ACTIVE})
**And** la lecture est mise en cache à l'échelle de la requête (perf)

**Implementation Notes:**
- Interface `ModuleRegistry` (spec §5.1) ; impl JDBC/JPA ; cache `@RequestScope` ou `ThreadLocal` invalidé en fin de requête.
- Pas de Redis (différé) — DB suffit < 100 tenants.

- [x] [Deferred-from: review 12-6b, 2026-10-29] `spring-modulith-core` + ArchUnit ship in the prod boot jar via `spring-modulith-starter-jdbc` but are unused at runtime — decide (exclusion vs `spring-modulith-runtime` for the registry), verify with `dependency:tree` + boot smoke [pom.xml] — ✅ 13.2: excluded from `spring-modulith-starter-jdbc` (the registry is the core's own `ModuleRegistry`, not `spring-modulith-runtime`); no runtime class references `modulith.core`, its only auto-config is a guard for `ApplicationModuleInitializer` beans (none). `dependency:tree` after: modulith-core/archunit only at test scope (via `spring-modulith-test`); packaged jar has neither in `BOOT-INF/lib` and boots on `keevo_dev` (health 200, 0 ERROR); `EventPublicationRegistryIntegrationTest` 9/9 green on a test classpath stripped of modulith-core/archunit/modulith-test

- [x] [Deferred-from: review 13-1, 2026-10-29] Rows of `module_definitions` whose manifest is no longer on the classpath (kept by the seed, WARN) must be treated as not installed by the registry (no activation, hidden from catalogue) [ModuleRegistry] — ✅ 13.2: `ModuleRegistry.installed()`/`definition()` come from the classpath manifests only (`ModuleCatalog`), and the effective state rule never activates a module that is not installed, even with an ACTIVE `tenant_modules` row (`ModuleRegistryPostgresIntegrationTest.orphanRow`: orphan `legacy` row + ACTIVE state → not active, not installed). Activation refusal itself = 13.8

- [x] [Deferred-from: review 13-1, 2026-10-29] Expose the validated manifest set once as a bean (e.g. `ModuleCatalog`) consumed by the seeder, registry and resolver — no re-scan of the classpath per caller [shared.module] — ✅ 13.2: `shared.module.application.ModuleCatalog` singleton (loaded + validated once through the context `ResourcePatternResolver`, invalid set fails the boot from its constructor); `ModuleCatalogSeeder` and `ModuleRegistryService` consume it (`ModuleCatalogSeederContextTest` still fails fast naming file + field; boot IT asserts the bean holds 10)

### Story 13.3 : `DependencyResolver` (requires, cascade, cycles, blocage)

As a **super-admin**,
I want **que l'activation résolve les dépendances dures et que la désactivation soit bloquée si d'autres modules en dépendent**,
So that **on n'atteint jamais un état incohérent (MOD-FR4, MOD-FR5, MOD-FR6)**.

**Acceptance Criteria:**

**Given** un module avec `requires: [catalog, inventory]` non actifs
**When** on planifie son activation
**Then** le resolver retourne un plan ordonné incluant `catalog` et `inventory` (cascade), après vérification d'entitlement de chacun

**Given** un module actif `B` requis par un module actif `A`
**When** on tente de désactiver `B`
**Then** l'opération est refusée en listant `A` comme dépendant

**Given** un graphe `requires` contenant un cycle
**When** l'app démarre
**Then** le boot échoue (détection de cycle)

**Implementation Notes:**
- Tri topologique (Kahn) sur le graphe `requires` ; détection de cycle au boot + à l'ajout.
- `enhances`/`needs` n'imposent **aucune** contrainte (opportuniste).
- TDD exhaustif : cascade multi-niveaux, cycle, blocage, requires déjà actifs.

- [x] [Deferred-from: review 13-2, 2026-10-29] `checkDeactivation` must compute dependents from `ModuleRegistry.activeModules` (free modules without a row are active), never from raw `tenant_modules` rows [DependencyResolver] — ✅ 13.3: `DependencyResolverService.checkDeactivation` filters the transitive `requires` dependents by `ModuleRegistry.activeModules(tenantSchema)` only (no DB access of its own); `DependencyResolverServiceTest.inactiveDependentsDoNotBlock`/`deactivateTransitive`, and `DependencyResolverBootIntegrationTest` on the real registry (tenant without rows for `catalog`/`inventory` → treated active; `pos` DISABLED row → not a dependent)

### Story 13.4 : `ModuleProvisioner` — Flyway par module dans le schéma tenant

As a **super-admin**,
I want **que l'activation d'un module crée ses tables dans le schéma du tenant via des migrations Flyway dédiées au module**,
So that **chaque module gère son schéma de façon versionnée et isolée (MOD-FR11, MOD-NFR1, MOD-NFR7)**.

**Acceptance Criteria:**

**Given** un module avec des migrations sous `db/modules/<id>/`
**When** il est activé pour `kv_000042`
**Then** Flyway applique ces migrations dans `kv_000042` avec un historique isolé `flyway_history_<id>`, et `tenant_modules.provisioned_version` est mis à jour

**Given** un module déjà provisionné
**When** on réactive
**Then** l'opération est idempotente (no-op si version à jour)

**Implementation Notes:**
- Flyway programmatique par module (spec §2.3) : `.schemas(schema).defaultSchema(schema).table("flyway_history_"+id).locations("classpath:db/modules/"+id).baselineOnMigrate(true).migrate()`.
- **Remplace** le hack runtime `TenantSchemaSyncService` (à retirer une fois tous les modules provisionnés ainsi).
- Idempotence + transaction par schéma ; à < 100 tenants, un endpoint « provisionner sur tous les tenants actifs » est un batch trivial.
- TDD Testcontainers : activer → tables présentes dans le bon schéma ; ré-activer → no-op ; isolation entre 2 schémas.

- [x] [Deferred-from: review 13-1, 2026-10-29] `TenantSchemaSyncService` copies other global public tables into every tenant schema (`shedlock`, `event_publication`, `event_listener_receipt`, `flyway_schema_history`, `user_tenant_memberships`, `refresh_tokens`, …) — audit which are global, extend `GLOBAL_ONLY_TABLES`, decide cleanup of existing empty copies [tenant provisioning] — ✅ 13.4: audit of every `public` table; `GLOBAL_ONLY_TABLES` + `shedlock`, `flyway_schema_history`, `event_publication`, `event_listener_receipt`, `refresh_tokens`, `password_reset_tokens`, `user_tenant_memberships`, `user_sync_state`, `feedback` (all only accessed as `public.*`, no tenant DDL); existing copies kept and counted at boot (`GlobalTableStrayCopiesReporter`, INFO, no schema names); `GlobalNotificationPostgresIntegrationTest` explicit list + stray-copy test. Cleanup decision routed → 13-10

### Story 13.5 : `EntitlementService` + gating déclaratif

As a **développeur plateforme**,
I want **un service d'entitlements et des annotations de gating sur les use cases/endpoints**,
So that **l'accès est refusé proprement si le module n'est pas souscrit (MOD-FR14)**.

**Acceptance Criteria:**

**Given** un tenant sans le module `invoicing`
**When** il appelle un endpoint annoté `@RequiresEntitlement("invoicing")`
**Then** il reçoit une erreur `MODULE_NOT_ENTITLED` (HTTP 402/403) gérée par `GlobalExceptionHandler`

**Given** un tenant avec `invoicing` en TRIAL/ACTIVE
**When** il appelle le même endpoint
**Then** l'accès est autorisé

**Implementation Notes:**
- `EntitlementService.isEnabled/assertEnabled/enabledModules` (source de vérité = `tenant_modules`).
- Aspects `@RequiresEntitlement` (+ `@RequiresPermission` généralisant le RBAC) ; mapping `domainCode=MODULE_NOT_ENTITLED` → HTTP dans `GlobalExceptionHandler`.
- TDD slice : 402/403 si non entitled, OK sinon ; combinable avec `@RequiresPermission`.

- [x] [Deferred-from: review 13-2, 2026-10-29] Single source of truth between `ModuleRegistry.isActive` and `EntitlementService`: extend `TenantModuleStatePort` with `expires_at`/`grace_until`/`tier`, EXPIRED in grace = read-only, keep the free-without-row rule shared; decide SUSPENDED / DELETION_PENDING tenants [entitlement] — ✅ 13.5: one computation `shared.module.application.ModuleStateRules` (`ModuleState` ENABLED/READ_ONLY/DISABLED, injected `Clock`, evaluated at every call) used by `ModuleRegistryService.moduleState(s)` and, through it, `ModuleEntitlementService`; `TenantModuleStatePort.findRows` returns status + `expires_at` + `grace_until` + `tier` (one query, request cache holds raw rows); free-without-row rule kept; read-only stays « active » for reads. SUSPENDED / DELETION_PENDING: not handled here, already refused at authentication (documented on `EntitlementService`) — `ModuleRegistryServiceTest`, `ModuleRegistryPostgresIntegrationTest.expiryColumnsRead`, `ModuleEntitlementServiceTest`

- [x] [Deferred-from: review 13-3, 2026-10-29] Replace `FreeModulesOnlyEligibility` (plain `@Component` — a second `ModuleActivationEligibility` bean breaks the context): delete it or make the 13.5 bean the only one, update `DependencyResolverBootIntegrationTest`; enrich `Eligibility` (reason, TRIAL vs ACTIVE, read-only grace) and define the contract for a throwing/null port [entitlement] — ✅ 13.5: `FreeModulesOnlyEligibility` deleted, single bean `EntitlementActivationEligibility`; `Eligibility(eligible, reason, initialStatus)` (free → ACTIVE, paid → NOT_ENTITLED; trial vs active and payments = 16.1, read-only grace is a runtime state, not an activation input); null / throwing port → not eligible `ELIGIBILITY_UNAVAILABLE` (`BlockReason.Code`) — `DependencyResolverServiceTest.nullOrThrowingEligibility`, `DependencyResolverBootIntegrationTest` (one bean)

### Story 13.6 : `CapabilityRegistry` (intégration synchrone optionnelle)

As a **développeur de module**,
I want **publier/résoudre des capacités optionnelles entre modules sans dépendance directe**,
So that **un module utilise une fonction d'un autre si présent, sinon dégrade (MOD-FR9, MOD-NFR10)**.

**Acceptance Criteria:**

**Given** le module CRM actif fournissant `ClientLookupPort`
**When** un autre module appelle `capabilities.find(ClientLookupPort.class, tenant)`
**Then** il obtient l'implémentation CRM

**Given** le module CRM **inactif** pour ce tenant
**When** le même appel est fait
**Then** il obtient `Optional.empty()` (mode dégradé, pas d'erreur)

**Implementation Notes:**
- `CapabilityRegistry.provide/find` (spec §6) ; résolution **tenant-aware** (ne retourne que si le fournisseur est actif pour ce tenant).
- Les interfaces `*Port` vivent dans l'`api` du fournisseur ; le consommateur dépend de cette `api` uniquement (autorisé par `verify()`).

- [x] [Deferred-from: review 13-1, 2026-10-29] Validate the `needs` entries format (`Port` / `Port?`) and that `provides` names are unique across manifests [ModuleManifestLoader] — ✅ 13.6: `ModuleManifestLoader.NEEDS_PATTERN` `^[A-Z][A-Za-z0-9]*Port\??$` per entry, `provides` unique across manifests, hard need (no `?`) provided within the consumer's transitive `requires` closure; the `CapabilityRegistry` cross-checks `provides` ↔ `@ProvidesCapability` beans at boot — `ModuleManifestLoaderTest.needsFormat/providesUnique/hardNeeds`, `CapabilityRegistryServiceTest`

### Story 13.7 : `ExtensionRegistry` (points d'extension / slots)

As a **développeur de module**,
I want **déclarer des points d'extension et y contribuer depuis d'autres modules**,
So that **les modules enrichissent les workflows/UI d'autres modules quand ils sont actifs (MOD-FR10)**.

**Acceptance Criteria:**

**Given** un module déclarant un slot `pos.checkout.post-sale-actions`
**When** un module actif y contribue
**Then** `contributions(slotId, tenant)` retourne uniquement les contributions des modules actifs pour ce tenant

**Implementation Notes:**
- `ExtensionRegistry.declare/contribute/contributions` (spec §8) ; backend pour les workflows ; le miroir Flutter (`ExtensionSlot`) est en Epic 15.

### Story 13.8 : `ModuleLifecycleService` + API super-admin activate/deactivate

As a **super-admin**,
I want **activer/désactiver un module pour un tenant via API, avec audit**,
So that **je gère le catalogue de chaque tenant sans redéploiement (MOD-FR2, MOD-FR3, MOD-FR16)**.

**Acceptance Criteria:**

**Given** un appel `POST /api/v1/admin/tenants/{id}/modules/{moduleId}/activate` (SUPER_ADMIN)
**When** il s'exécute
**Then** entitlement créé/maj → resolver (requires/cascade) → provisioning (13.4) → `onActivate` (seeds) → capacités/handlers/extensions enregistrés → cache invalidé → audit `MODULE_ACTIVATED`

**Given** `.../deactivate` sur un module sans dépendants actifs
**When** il s'exécute
**Then** routes/permissions/handlers retirés, statut `DISABLED`, `disabled_at` posé, **données conservées**, audit `MODULE_DEACTIVATED`

**Given** un module avec des dépendants actifs
**When** on tente de le désactiver
**Then** `409` listant les dépendants (13.3)

**Implementation Notes:**
- `ModuleLifecycleService` orchestre (spec §10) ; `ModuleAdminController` (SUPER_ADMIN only — décision V1).
- Endpoints complémentaires : `GET /admin/modules`, `GET /tenants/me/modules` (owner). Bundles = Epic 16.
- TDD E2E : activer invoicing → tables présentes ; désactiver → routes off + données conservées.

- [x] [Deferred-from: review 13-2, 2026-10-29] Deactivation must upsert a `DISABLED` row (+ `disabled_at`) — deleting the row re-activates a free module; evict the registry request cache (`ModuleRegistry.evict(schema)`) after every write; resolve the schema from the tenant UUID of the admin API [ModuleLifecycleService] — ✅ 13.8a: `ModuleLifecycleService.deactivate` → `JdbcTenantModuleLifecycleStore.upsertDisabled` (INSERT … ON CONFLICT, status DISABLED + `disabled_at`, never a DELETE; a free module without a row gets a DISABLED row — `ModuleLifecyclePostgresIntegrationTest.deactivateWithoutRow`); `ModuleRegistry.evict(schema)` after the lock, after every row write and after completion; the admin API takes the tenant UUID and resolves the live schema (`findTenant`, unknown/DELETED/platform → 404 `TENANT_NOT_FOUND`).

- [x] [Deferred-from: live test 12-7, 2026-10-29] **Blocking before any module can be DISABLED or expire in prod**: while a sale is held (`MODULE_INACTIVE` or `MODULE_READ_ONLY` — the latter can happen without any admin action as soon as a row has `expires_at`), pulls overwrite the local views — the held sale disappears from « Mes Ventes » and the stock shows the server value (19 instead of 18) until the module is back; the cashier may think the sale is lost and re-enter it. Show held operations in the lists (badge « En attente — module non activé ») and keep local-only rows/stock deltas through pulls [Flutter sync / sales list] — ✅ 13.8b: root cause = two online read paths, not the pull (held ops already protect their rows in `_mergePulledEntities`): « Mes Ventes » returned only the server list when online, and the remote-first stock reads (`MultiStoreStockRepositoryImpl`, `StockRepositoryImpl`) used `hasPendingOperations()` — which excludes held ops since 12.7 — so they wrote the server stock over the local decrement. New `SyncService.hasUnsyncedLocalChanges()` (pending + held) guards every server-read overwrite (stock reads, product `syncFromRemote`, sign-out warning); client/supplier/store `syncFromRemote` skip `unsyncedEntityIds()`; `SaleRepositoryImpl.getSalesHistory` adds the local sales whose `CREATE_SALE` is queued and missing from the server list. Badge `HeldOperationBadge` « En attente — module non activé » / « En attente — abonnement expiré » in « Mes Ventes », client, supplier, store and product lists. Tests: `rest_sync_service_held_pull_test.dart` (held sale survives a pull with stock 18 for both reasons; held client edit kept; held → applied → pull converges: 1 sale, 1 movement, server stock), `sale_repository_held_history_test.dart`, badge widget tests (sales history, contacts, `ProductCard`, `StoreCard`). Live Redmi check: orchestrator.

- [x] [Deferred-from: spec 13-4, 2026-10-29] Activation calls `ModuleProvisioner.provision(schema, id)` for each plan step **after** writing its `tenant_modules` row (a paid module without a row → `IllegalStateException`); map `ModuleProvisioningException` to a French error without technical detail; Flyway's own INFO logs name the tenant schema (`Migrating schema "kv_…"`) — lower `org.flywaydb` to WARN in prod or accept; expose `provisionAll` for the super-admin console (16.4) [ModuleLifecycleService] — ✅ 13.8a: each plan step: row upsert, then `provision(schema, id)` (`ModuleLifecycleServiceTest.cascade` order, PG IT `cascade`: tables + `provisioned_version` 1); any provisioner failure → 500 `MODULE_PROVISIONING_FAILED`, French message without technical detail, whole transaction rolled back (PG IT `provisioningFails`: dependency and target rows unchanged); `logging.level.org.flywaydb: WARN` in `application-prod.yml`; `provisionAll` endpoint → routed to 16.4 (spec 13.8a « Not here »).

- [x] [Deferred-from: review 13-3, 2026-10-29] Call `planActivation` (and honour `blocked()`) **before** writing any `tenant_modules` row (spec-socle §10 step 1/2 order swapped — writing the target row first makes the plan empty and skips the cascade), then write/provision every step in `moduleIds()` order and evict the registry cache; check `ModuleRegistry.isKnownTenant` first; per-tenant lock or re-check inside the transaction against concurrent activations; responses/audit show French display names, not module ids [ModuleLifecycleService] — ✅ 13.8a: `planActivation` runs before any write, `blocked()` → 409 `MODULE_ACTIVATION_BLOCKED` naming modules by display name; rows written/provisioned in `moduleIds()` order; tenant resolved first (live tenants only, 404 otherwise); per-tenant `TransactionLockPort` lock `module-lifecycle:<schema>` in the transaction (PG IT `concurrentActivations`: one call activates [base, top], the other is idempotent; lock-coverage test extended); responses and audit carry display names.

- [x] [Deferred-from: review 13-5, 2026-10-29] Super-admin activation must be able to grant a paid module (in V1 the super-admin activation after a Mobile Money payment IS the entitlement): override/extend `ModuleActivationEligibility` for that actor, write ACTIVE/TRIAL with `expires_at` + `grace_until` [ModuleLifecycleService] — ✅ 13.8a: for a SUPER_ADMIN actor the plan uses a lifecycle-scoped `GrantingEligibility` (every step eligible; not a bean — `EntitlementActivationEligibility` stays the only free-only bean for other callers, `ModuleLifecycleServiceTest.paidRefusedForOtherActors`); rows ACTIVE/TRIAL with `expires_at` and `grace_until = expires_at + 7 d` (PG IT `paidModule`).

- [x] [Deferred-from: spec 13-8a, 2026-10-30] (13.8b) The owner's history (`/api/v1/audit`) now receives `MODULE_ACTIVATED` / `MODULE_DEACTIVATED` entries (`entityType Module`, label = module display name, `valueAfter` keys `module`, `statut`, `finAbonnement`, `entreprise`); the Flutter audit catalogue has no label for them (shown as a humanized code) — add French labels « Module activé » / « Module désactivé » and the `Module` object type [Flutter audit] — French labels must be added in all three places kept equal by contract tests: server `AuditLabels` (`ACTIONS`, `ENTITY_TYPES`, `FIELDS`), `keevo/contracts/export-datasets.json`, and the app `AuditPresenter` maps; include the `donnees` key written by deactivation [review 13-8a] — ✅ 13.8b: `AuditLabels.ACTIONS` + `MODULE_ACTIVATED` « Module activé » / `MODULE_DEACTIVATED` « Module désactivé », `ENTITY_TYPES` + `Module` « Module », `FIELDS` + `module` « Module », `statut` « Statut », `finAbonnement` « Fin de l'abonnement », `entreprise` « Entreprise », `donnees` « Données »; `export-datasets.json` regenerated (`ExportContractFileTest -Dexport.contract.write=true`); app `auditActionLabels`, `auditEntityTypeLabels`, `auditFieldLabels`, `AuditPresenter.catalogue` (sentence « … a activé / désactivé le module X ») and `objectTypes` (« Modules »). Tests: `AuditChangesFormatterTest.moduleLifecycle`, `ExportContractFileTest` (2), app `local_export_contract_test.dart`, `audit_presenter_test.dart` « module lifecycle entries ».

### Story 13.9 : Convertir 2 modules existants au contrat `KeevoModule`

As a **architecte**,
I want **migrer CRM (fournisseur de capacité) et Reporting (consommateur d'events) vers le contrat de module**,
So that **le socle est validé sur du réel avant le module pilote (MOD-FR1, MOD-NFR8)**.

**Acceptance Criteria:**

**Given** le module CRM
**When** il est converti
**Then** il a un `module.json`, implémente `KeevoModule`, **fournit** `ClientLookupPort` via `registerCapabilities`, et s'active/désactive par tenant

**Given** le module Reporting
**When** il est converti
**Then** il **consomme** des events via `@ApplicationModuleListener` et s'active/désactive par tenant

**Implementation Notes:**
- Choisis car peu couplés et représentatifs (un *provides*, un *consumes*).
- Valide bout-en-bout : provisioning, gating, capability, events, lifecycle — avant Epic 14.

- [x] [Deferred-from: review 13-1, 2026-10-29] — done in 13.9 (2026-10-30): `KeevoModule` has no `manifest()` (JSON stays the only source, `ModuleRegistry.definition(id())`), and the `ModuleManifest` compact constructor enforces id pattern/`ID_MAX`, non-blank display name, non-null immutable lists — `ModuleManifest` canonical constructor is public — Java-built manifests (`KeevoModule.manifest()`) would bypass the loader's invariants; keep JSON as the single source or move validation into the record [shared.module]

- [x] [Deferred-from: spec 12-7, 2026-10-29] — routed → 14.3 (2026-10-30, 13.9): impossible while `sync → crm :: api` exists; 14.3 removes that edge — Relocate each `SyncOperationHandler` from `sync` into its owning module (registered through the `KeevoModule` contract), keeping the declared `moduleId()` [sync handlers]

- [x] [Deferred-from: spec 13-4, 2026-10-29] — done in 13.9 (2026-10-30): `db/modules/crm/V1__crm_baseline.sql` = reference DDL, never executed on a schema that already has `clients` (`KeevoModule.existingSchemaBaseline()` → Flyway baselines at 1 without executing — review decision); removal of the crm DDL from `TenantSchemaProvisioner` routed → 16.5 (FK ordering) — Converting a module whose tables already exist in tenant schemas: `ModuleProvisioner` baselines at version **0** (a tenant schema is never empty — Flyway's default baseline 1 would skip every V1), so a converted module's `V1` runs on tenants that already have its tables — write it idempotent (`CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, guarded constraints) or baseline existing tenants explicitly; then remove the module's tables from `TenantSchemaProvisioner` / `REQUIRED_TENANT_TABLES_DDL` [ModuleProvisioner / db/modules]

- [x] [Deferred-from: review 13-4, 2026-10-29] — done in 13.9 (2026-10-30): `ID_MAX` 48 checked at load; `validateOnMigrate` on / `outOfOrder` off set explicitly, repair procedure in the 13.9 spec; `core` ships no migration (no version to track); stop mirroring the crm tables in `TenantSchemaSyncService` → routed to 16.5 (review decision: one writer until the full conversion) — Module migration policy: cap manifest ids so `flyway_history_<id>` ≤ 63 chars (ID_MAX 48, checked at load); `core` provisioned version tracking; repair path for an edited applied script (`validateOnMigrate`) and `outOfOrder` hotfixes; when a module is converted stop `TenantSchemaSyncService` from mirroring its tables [ModuleProvisioner / loader]

### Story 13.10 : Appliquer le gating entitlement à TOUS les endpoints existants (classement Free/Premium)

As a **plateforme**,
I want **que chaque endpoint/use case existant porte le bon `@RequiresEntitlement`/`@RequiresPermission` selon le classement Free/Premium**,
So that **le classement validé est réellement enforce, pas seulement défini (MOD-FR14 ; comble le gap G2)**.

**Acceptance Criteria:**

**Given** un tenant **Free**
**When** il appelle un endpoint **Premium** (créer une 2ᵉ boutique au-delà du plafond, transfert inter-boutiques, inventaire assisté, rapport hebdo/avancé, profitabilité, export, config rapports)
**Then** il reçoit `MODULE_NOT_ENTITLED` (ou `PLAN_LIMIT_EXCEEDED` pour les plafonds) — vérifié en **temps réel**

**Given** un tenant avec le module/Premium actif
**When** il appelle le même endpoint
**Then** l'accès est autorisé

**Given** la suite de tests
**When** elle s'exécute
**Then** chaque mapping endpoint↔entitlement est couvert (table de correspondance testée)

**Implementation Notes:**
- Passe systématique sur les ~34 contrôleurs / ~114 endpoints : annoter selon `plan-refonte-modulaire.md §6.1`.
- Free conserve : catalogue (plafond), POS/vente/clôture, stock de base, CRM basique, rapport EOD **fixe**. Premium : multi-store/transferts, counting, rapports avancés, profitabilité, exports, config rapports, alertes/notifs avancées.
- S'appuie sur `EntitlementService` (13.5) + `PlanLimitGuard` (refonte 16.8) ; produire une **table de correspondance endpoint→entitlement** (doc + test).
- Dépend de 13.5 ; à faire après la conversion des modules (13.9).

- [x] [Deferred-from: 13-10a, 2026-10-30] 13.10b (app): XLSX/PDF exports now answer 403 `MODULE_NOT_ENTITLED` (+ `details.feature`) instead of `PLAN_FEATURE_REQUIRED` — `export_exception.dart` `isPlanGate` must accept it (upgrade sheet kept); every premium entry point of `docs/entitlements/endpoints.md` (transfers, multi-store overview / availability, counting, leaderboard, weekly trigger, profitability, report preferences / test report) hidden or shows the French message for a Free tenant; sync ops `STOCK_TRANSFER` / inventory counting held as `MODULE_INACTIVE` [app 403] — also (review 13-10a): the dashboard « stock bas » tile and the catalogue button open `/stock/overview?showLowOnly=true` (now `multi-store`) — send Free tenants to a free local low-stock list instead of hiding it — **done 13.10b (2026-10-30)**: typed `ModuleEntitlementException` + `ModuleEntitlementInterceptor` (no logout / refresh / retry), French messages « Cette fonctionnalité fait partie de l'offre Premium » / « Abonnement expiré : ce module est en lecture seule » + « Passer à Premium » (owner) in `AppErrorWidget` / `AppErrorInline` / `appErrorSnackBar`; premium screens (overview, counting, transfers, leaderboard, profitability, weekly / test report, report preferences, report resend) show it; `isPlanGate` accepts both codes; « stock bas » tile + catalogue button → local `/stock/low` list unless `multi-store` is readable (`GET /tenants/me/modules`, cached per session); held sync ops unchanged (12.7 / 13.8b). Hiding the entry points stays 15.1 / 15.8.

- [x] [Deferred-from: review 13-9, 2026-10-30] REST gating of the converted modules: `@RequiresEntitlement("crm")` on the crm (clients / suppliers) controllers and `@RequiresEntitlement("reporting")` on the report / dashboard / export controllers — 13.9 gated only the capability, the event listeners and the reporting schedulers [crm / reporting REST] — **done 13.10a (2026-10-30)**: `ClientController` / `SupplierController` → `crm`, `DashboardController` / `ReportController` / `ExportController` → `reporting` (GET READ, other verbs WRITE); `docs/entitlements/endpoints.md` + `EndpointEntitlementCoverageTest`; `PremiumGatingFullContextTest.crmDisabled_refused` (crm DISABLED → GET clients 403)

- [x] [Deferred-from: 13-5, 2026-10-29] `@RequiresPermission` (spec-socle §9.2) not built in 13.5: no permission model exists yet (roles OWNER/EMPLOYEE/SUPER_ADMIN only, checked by `CurrentActor` / `@PreAuthorize`). Proposed decision (13.10 spec confirms or revises): 13.10 introduces it as a thin annotation over the existing role checks (a `Permission` enum mapped to roles, enforced by Spring method security), combinable with `@RequiresEntitlement` — the entitlement aspect already runs after the method-security interceptors and before `@Transactional` (`EntitlementAspect` order 1000). Also: `@RequiresEntitlement` is not matched on interface-only annotations and not on self-invocation (Spring AOP) — annotate controller/use-case classes [entitlement / RBAC] — **routed → Story 14.1 (13.10a decision, 2026-10-30)**: not built in 13.10 — every existing endpoint keeps its role checks (`CurrentActor` / `@PreAuthorize`); the first module with its own permissions (Invoicing: `INVOICE_CREATE/VIEW/SEND`) introduces the annotation



---

## Epic 14 : Module Pilote Facturation (Phase 4)

> Livrer le **premier module vendable, autonome ET composable**, qui **prouve la vision** : facturation seule → +CRM → +POS (facture auto) → POS désactivé sans casse. C'est le **patron** de tout futur module.

- [x] [Deferred-from: spec 12-7, 2026-10-29] `GET /api/v1/sync/pull` must skip `DeltaEntityProvider`s of inactive modules (same `ModuleRegistry` rule as the push guard) [sync pull gating] — ✅ already done (13.10a review, item 4b): `SyncPushService.pull` reads `moduleStates` once and skips (empty list, delta done) every provider whose `DeltaEntityProvider.moduleId()` is DISABLED (READ_ONLY still pulls) — `sync/sync/application/service/SyncPushService.java`, `SyncPullServiceTest.story1310a_*`, `PremiumGatingFullContextTest.freeTenant_premiumDataNotLeaked`; ticked in 14.1 (2026-10-30). No invoicing delta provider yet (offline app flows → 15.5)

- [x] [Deferred-from: review 13-5, 2026-10-29] Gating rollout: slice test combining `@RequiresEntitlement` + `@RequiresPermission` with an asserted interceptor order; class-level default `WRITE` would refuse GET endpoints during grace — default READ for safe HTTP verbs or check READ/WRITE per verb in the endpoint mapping test; annotate HTTP/use-case entry points only (no tenant in schedulers/async listeners); confirm tenant-module endpoints are role-guarded against the super-admin (entitlement passes for `public`); Flutter `auth_interceptor` handles 403 `MODULE_NOT_ENTITLED`/`MODULE_READ_ONLY` [gating] — 13.10a (2026-10-30): READ/WRITE per HTTP verb enforced by `EndpointEntitlementCoverageTest` (method-level annotations, no class-level default), HTTP entry points only (schedulers / listeners gated through `ModuleRegistry` / `@ModuleScoped`); still open: `@RequiresPermission` slice + order → 14.1 (Flutter 403 handling done in 13.10b, 2026-10-30) — slice + order done in 14.1 (`PermissionGatingSliceTest`, 2026-10-30)

### Story 14.1 : Module Facturation autonome (domaine + provisioning + CRUD)

As a **commerçant**,
I want **gérer mes factures même sans aucun autre module (création/édition/listing manuels)**,
So that **je peux activer juste la facturation pour mon besoin (MOD-FR7, MOD-FR17)**.

**Acceptance Criteria:**

**Given** un tenant avec **uniquement** le module `invoicing` actif
**When** il crée une facture (lignes libres, montant, client en texte libre)
**Then** la facture est persistée dans le schéma tenant et listée, sans dépendre de POS/CRM/Catalog

**Given** le manifest
**When** on l'inspecte
**Then** `requires: ["core"]` uniquement ; `enhances: ["pos","crm"]` ; `needs: ["ClientLookupPort?"]` ; `permissions: [INVOICE_CREATE, INVOICE_VIEW, INVOICE_SEND]`

**Implementation Notes:**
- Nouveau module `com.keevo.invoicing` (structure hexagonale + `api/`) ; `module.json` (cf. spec §3).
- Agrégat `Invoice` (numéro, date, lignes, sous-total, total, statut DRAFT/ISSUED/PAID, client { id?, nom libre }).
- Tables via `db/modules/invoicing/V1__*.sql` (provisionnées par `ModuleProvisioner`, Epic 13.4) : `invoices`, `invoice_items`.
- Gating `@RequiresEntitlement("invoicing")` + `@RequiresPermission`.
- Numérotation : séquence par tenant (`invoice_number_seq` dans le schéma tenant) — gérer l'unicité/lacunes.
- TDD : domaine (calcul total) → use cases → REST → provisioning.

- [x] [Deferred-from: 13-10a, 2026-10-30] Introduce `@RequiresPermission` (spec-socle §9.2) with the first module that has its own permissions (`INVOICE_CREATE/VIEW/SEND`): a `Permission` model mapped to the existing roles, enforced by Spring method security, combinable with `@RequiresEntitlement` (entitlement aspect order 1000 runs after method security, before `@Transactional`); slice test asserting both and their order; add it to `EndpointEntitlementCoverageTest` / `docs/entitlements/endpoints.md` [RBAC] — ✅ 14.1 (2026-10-30): `shared.module.RequiresPermission` (all-of), enforced by an `AuthorizationManagerBeforeMethodInterceptor` (`PermissionMethodSecurityConfig`, order `PRE_AUTHORIZE + 1` < `EntitlementAspect` 1000) with `PermissionAuthorizationManager`; role mapping in `ModulePermissions` (OWNER = every manifest permission, EMPLOYEE = `KeevoModule.employeePermissions()`, SUPER_ADMIN none; boot-validated), values validated at boot by `RequiresPermissionValidator`; denial → 403 `FORBIDDEN`. Tests: `PermissionGatingSliceTest` (order role → permission → entitlement), `ModulePermissionsTest`, `RequiresPermissionValidatorTest`, `InvoicingFullContextTest`; `EndpointEntitlementCoverageTest` + `docs/entitlements/endpoints.md` now carry a Permission column (unknown permission fails the build)

### Story 14.2 : Génération PDF + envoi WhatsApp de la facture

As a **commerçant**,
I want **générer un PDF de ma facture et l'envoyer par WhatsApp**,
So that **je transmets une facture professionnelle à mon client (MOD-FR17, UX-DR5)**.

**Acceptance Criteria:**

**Given** une facture
**When** je demande son PDF
**Then** un PDF lisible (logo tenant, lignes, totaux, XAF) est généré

**Given** une facture et un numéro WhatsApp
**When** je l'envoie
**Then** le PDF/lien part via le `WhatsAppPort` existant, et l'événement `invoice.created`/`invoice.sent` est publié

**Implementation Notes:**
- PDF : template HTML (Thymeleaf) → `openhtmltopdf` (ou OpenPDF) ; stockage S3 (réutiliser l'infra images produits) ou génération à la volée.
- Réutiliser `WhatsAppPort` (Wassender/Twilio/NoOp) — **pas** de dépendance dure au module messaging si possible (via capability ou port core) ; sinon `requires` messaging à évaluer.
- Events publiés via `@ApplicationModuleListener`/registre (Epic 12.3).

### Story 14.3 : Intégration CRM optionnelle (capability `ClientLookupPort`)

As a **commerçant**,
I want **sélectionner un client depuis ma base CRM si le module CRM est actif, sinon saisir un nom libre**,
So that **la facturation s'enrichit automatiquement sans l'imposer (MOD-FR9)**.

**Acceptance Criteria:**

**Given** le module CRM actif
**When** je crée une facture
**Then** je peux choisir un client existant (résolu via `CapabilityRegistry.find(ClientLookupPort)`)

**Given** le module CRM inactif
**When** je crée une facture
**Then** je saisis le client en texte libre (mode dégradé, aucune erreur)

**Implementation Notes:**
- Consommer `ClientLookupPort` (fourni par CRM depuis la Story 13.6) via `CapabilityRegistry` (Optional).
- Aucun import du module CRM hors de son `api` (vérifié par `verify()`).

- [x] [Deferred-from: live test 12-7, 2026-10-29] `POST /api/v1/sales` with an unknown `clientId` → FK `sales_client_id_fkey` at commit → HTTP 500; resolve through the optional `ClientLookupPort` capability (commerce must not hard-depend on `crm`) → clean domain error; align `SaleSyncHandler` (today `crm.api.ClientQueryPort`) on the same capability [commerce / sync] — ✅ 14.3: `RecordSaleService` checks the client through `CapabilityRegistry.find(ClientLookupPort)` (REST and sync alike, after the idempotency guard, before the stock check): crm on + unknown → `CLIENT_NOT_FOUND` 404 / sync REJECTED, nothing written; `SaleSyncHandler` no longer uses `ClientQueryPort` — `SaleClientCheckFullContextTest`, `RecordSaleServiceTest.story143_*`, `SyncModuleGuardPostgresIntegrationTest.crmOn_saleWithUnknownClient_rejectedClientNotFound`

- [x] [Deferred-from: review 13-6, 2026-10-29] Migrating consumers to `ClientLookupPort`: commerce `allowedDependencies` += `crm :: api` (interface only); declare `needs: ["ClientLookupPort?"]` in the pos manifest; decide the `clientId` policy when the capability is absent (`sales_client_id_fkey` is itself a commerce→crm schema link — reject vs drop, and whether `clients`/the FK exist when crm was never provisioned); client picker needs a search method (extend the port + bump crm) or stays on gated crm REST; consumers must not swallow provider exceptions; first write capability must call `assertWritable` (READ_ONLY provider) [commerce / sync / crm] — ✅ 14.3: commerce and invoicing `allowedDependencies` += `crm :: api` (capability interface only, resolved per call, never injected); pos manifest `needs: ["ClientLookupPort?"]` (1.2.0); **policy when crm is off: drop** — the sale is recorded without its client (WARN log; money and stock are never refused because an optional module is off), the invoice keeps the typed text; the port gained `find` (summary, archived included) and `search` (non-archived, name/phone, sorted, limit 1–50), crm 1.2.0; provider exceptions propagate; still read-only (no `assertWritable` needed); no DDL / FK change

- [x] [Deferred-from: spec 13-9, 2026-10-30] Relocate each `SyncOperationHandler` from `sync` into its owning module, registered through the `KeevoModule` contract (13.9), keeping the declared `moduleId()` — possible once this story removes the `sync → crm :: api` edge (e.g. `CREATE_CLIENT`/`UPDATE_CLIENT` handlers move to crm and contribute themselves) [sync handlers / KeevoModule] — ✅ 14.3: the sync SPI (`SyncOperation`, `SyncOperationResult`, `SyncOperationStatus`, `SyncOperationHandler`, `AbstractSyncOperationHandler`) moved to `shared.application.port.sync`; the four client/supplier handlers moved to `crm.contact.adapter.in.sync` (still `@Component`, `moduleId()="crm"`, discovered by `SyncOperationHandlerRegistry`), `sync` lost `crm :: api`. **Decision:** the other 15 handlers stay in `sync` — their `X :: api` edges are legitimate (sync orchestrates, its targets do not depend on sync), moving them adds no value; item closed

### Story 14.4 : Bridge POS → Facturation (facture auto sur vente)

As a **commerçant**,
I want **qu'une facture soit générée automatiquement à chaque vente quand POS et Facturation sont tous deux actifs**,
So that **les deux mini-apps se lient sans que j'aie rien à configurer (MOD-FR8)**.

**Acceptance Criteria:**

**Given** POS et Facturation actifs
**When** une vente est complétée (`sale.completed`)
**Then** une facture correspondante est créée automatiquement (lignes mappées depuis la vente), idempotente (pas de doublon au rejeu)

**Given** Facturation actif mais POS inactif
**When** rien ne publie `sale.completed`
**Then** aucune facture auto n'est créée ; la facturation manuelle continue

**Given** POS désactivé alors que Facturation reste actif
**When** on désactive POS
**Then** le handler `sale.completed` cesse d'être invoqué, les factures existantes sont conservées, la facturation manuelle continue

**Implementation Notes:**
- [x] `@ApplicationModuleListener void on(SaleCompletedEvent)` dans `invoicing` (event du catalogue partagé `core/event/catalog`, pas d'import de POS). — ✅ 14.4: `invoicing.invoice.adapter.in.event.SaleInvoicingListener` (`@ApplicationModuleListener` + `@ModuleScoped("invoicing")`), delegating to `InvoiceSaleUseCase` (draft → issue → pay in one `REQUIRES_NEW` transaction, same numbering and events as the manual path, under the sale's actor). `SaleCompletedEvent` gained `clientId`, `paymentMode` and typed `lines` (product name, applied TTC unit price, quantity), filled by its 3 publishers; events stored before 14.4 deserialize with null fields (no invoice, WARN). Manifests: pos 1.3.0 `publishes: ["sale.completed"]`, invoicing 1.1.0 `consumes: ["sale.completed"]` — `SaleInvoicingFullContextTest`, `RegistryEventSerializationTest.legacySaleCompletedPayloadDeserializes`
- [x] **Idempotence** : clé `(eventId)` ou `(saleId)` → upsert ; le registre Modulith peut rejouer. — ✅ 14.4: key = sale id, `invoices.source_sale_id` + unique index (`db/modules/invoicing/V3__invoice_source_sale.sql`, lazy, additive); existing invoice → no-op; a concurrent replay fails on the unique index before the counter and rolls back entirely (gapless), caught as « already done »; exposed as `saleId` on the invoice response — `SaleInvoicingFullContextTest.replaySequential` / `replayConcurrent`
- [x] Le handler ne s'exécute que si `invoicing` actif (gating via `ModuleRegistry` au dispatch). — ✅ 14.4: `@ModuleScoped` skips DISABLED (FREE plan included), the listener skips READ_ONLY (INFO) — no invoice and no table created; provisioning (`ensureProvisioned`) only for an ENABLED module — `SaleInvoicingFullContextTest.invoicingDisabled` / `freeTenant` / `invoicingReadOnly`
- [x] Si le mapping vente→facture grossit (taxes, remises, multi-devises futures) → extraire un **bridge dédié** `bridge-pos-invoicing` (décision D3) ; sinon garder le listener simple. — ✅ 14.4: kept in `invoicing` — the mapping is a pure `SaleInvoiceMapper` (CRM client or « Client comptoir », discount spread over the lines HALF_UP with the remainder on the largest line, non-divisible line → quantity 1 « produit (× q) », prices always TTC so the invoice total equals the sale total, « Espèces » / « Mobile Money »); no dedicated bridge needed

### Story 14.5 : Point d'extension checkout POS → action « Facturer »

As a **caissier**,
I want **un bouton « Facturer » dans le checkout POS quand le module Facturation est actif**,
So that **je déclenche une facture depuis la vente (MOD-FR10, UX-DR3)** *(rendu UI en Epic 15)*.

**Acceptance Criteria:**

**Given** POS déclare le slot `pos.checkout.post-sale-actions`
**When** Facturation est actif
**Then** une contribution « Facturer » est disponible dans ce slot (backend : `ExtensionRegistry.contributions`)

**Given** Facturation inactif
**When** on lit le slot
**Then** aucune contribution Facturation n'apparaît

**Implementation Notes:**
- POS déclare `pos.checkout.post-sale-actions` dans son manifest (`extensionPoints.declares`, fait en 13.7) ; Facturation contribue par manifest `fills` + bean `@ContributesExtension(module = "invoicing", point = ...)` (chemin déclaratif de 13.7 — pas de second chemin programmatique avant 13.9).
- Le rendu Flutter du slot est en Story 15.3.

- [ ] [Deferred-from: review 13-7, 2026-10-29] « Facturer » visibility: permission of the cashier and per-sale context (already auto-invoiced, 14.6 scenario 3) — client-side filter or an optional `permission()` on `Extension`; a `@Bean`-method `List<Extension>` parameter outside `shared.module` silently gets an empty list (documented gap) [invoicing / pos]

### Story 14.6 : Test d'acceptation de la vision (4 scénarios)

As a **product owner (Toor)**,
I want **un test automatisé qui valide les 4 combinaisons d'activation**,
So that **la promesse « mini-apps connectées » est prouvée et non régressable (MOD-FR7/8/9/10)**.

**Acceptance Criteria:**

**Given/When/Then** — Scénario 1 (invoicing seul) : factures manuelles OK, client texte libre, aucune route/slot POS.
**Given/When/Then** — Scénario 2 (+crm) : sélecteur client CRM disponible.
**Given/When/Then** — Scénario 3 (+pos) : `sale.completed` → facture auto ; slot « Facturer » présent.
**Given/When/Then** — Scénario 4 (pos désactivé) : pas de facture auto, facturation manuelle continue, données conservées.

**Implementation Notes:**
- Test d'intégration `@ApplicationModuleTest` (Spring Modulith) + `AssertablePublishedEvents` pour vérifier la publication/consommation.
- Activer/désactiver les modules par tenant dans le test (via `ModuleLifecycleService`).

### Story 14.7 : Avoirs (notes de crédit) sur factures

As a **commerçant**, I want **corriger ou annuler une facture déjà payée par un avoir numéroté**, So that **ma facturation reste conforme (factures émises jamais modifiées ni supprimées, numérotation continue)**.

**Scope:** an `invoicing` credit note referencing an ISSUED/PAID invoice (full or partial lines), its own gapless series (`AV-<yyyy>-<000001>`), negative totals mirroring the VAT treatment of the referenced lines, audit + events; PAID invoices become cancellable only through a credit note.

- [ ] [Deferred-from: spec 14-1, 2026-10-30] A PAID invoice cannot be cancelled (409 `INVOICE_INVALID_TRANSITION`); correction needs a credit note [invoicing / `Invoice` transitions]

- [ ] [Deferred-from: spec 14-4, 2026-10-30] Cancelling a completed sale that was invoiced automatically (`SaleCancelledEvent` after `sale.completed`): its invoice is PAID and stays as is — issue a full credit note for the invoice whose `source_sale_id` is the sale (idempotent on the sale) [invoicing / pos bridge]

- [ ] [Deferred-from: spec 14-4, 2026-10-30] Correcting a completed sale that was invoiced automatically (`SaleCorrectedEvent`): the PAID invoice keeps the original lines — issue a credit note for the difference (or full credit + new invoice), idempotent on the correction [invoicing / pos bridge]

**Interim AC:** Given a PAID invoice, when the owner issues a credit note for it, then a numbered credit note is created, the invoice keeps its number and content, and totals net to the credited amount.

---

## Epic 15 : Frontend Modulaire (Phase 5)

> Côté client, le marchand ne voit/charge que ses modules (mini-apps). Inclut la fiabilisation du dashboard admin (tests/CI) et la complétion d'Epic 9. Stack : Flutter (Riverpod, go_router, Melos) + Next.js 16.

### Story 15.1 : `entitlementsProvider` Flutter (modules actifs)

As a **app marchand**,
I want **connaître la liste des modules actifs du tenant (claim JWT + API)**,
So that **l'UI s'adapte aux modules souscrits (MOD-FR15, MOD-FR18)**.

**Acceptance Criteria:**

**Given** un login
**When** le JWT est reçu
**Then** son claim `modules` alimente `entitlementsProvider` ; un appel `GET /tenants/me/modules` le confirme/raffraîchit (source de vérité backend)

**Given** un module désactivé côté backend
**When** le token est rafraîchi
**Then** le module disparaît de `entitlementsProvider`

**Implementation Notes:**
- Lire le claim `modules` (Epic 16.3) ; fallback `GET /tenants/me/modules`.
- Offline : respecter le dernier état signé (cohérent gate 7 jours).

- [ ] [Deferred-from: review 13-10a, 2026-10-30] When a module becomes ENABLED again for the tenant, the app must re-pull that module's entities from scratch (the 13.10a pull skips DISABLED providers but advances the cursor, so rows changed meanwhile are never re-sent); hide premium export datasets the tenant cannot use (`GET /exports/datasets` is not filtered) [Flutter entitlements / sync]

### Story 15.2 : Routing dynamique go_router selon les modules actifs

As a **app marchand**,
I want **que seules les routes des modules actifs soient montées**,
So that **un module désactivé n'est pas accessible (MOD-FR18, MOD-FR14 frontend)**.

**Acceptance Criteria:**

**Given** un module inactif
**When** l'utilisateur tente d'accéder à sa route (deep link inclus)
**Then** il est redirigé (route absente/guardée)

**Given** la navigation principale
**When** elle s'affiche
**Then** seuls les modules actifs apparaissent (cf. UX-DR4)

**Implementation Notes:**
- Registre de routes par module ; `go_router` `redirect`/`routes` conditionnés par `entitlementsProvider`.
- Guard : `module inactif` → home ou écran « module non activé » (CTA upgrade).

- [ ] [Deferred-from: review 13-1, 2026-10-29] Validate manifest `routes` format (leading `/`, go_router syntax) when routing consumes them [ModuleManifestLoader]

### Story 15.3 : `ExtensionSlot` + lazy loading

As a **développeur frontend**,
I want **un widget `ExtensionSlot(pointId)` rendant les contributions des modules actifs, avec chargement différé**,
So that **les modules enrichissent l'UI d'autres modules et l'app reste légère (MOD-FR10, UX-DR3)**.

**Acceptance Criteria:**

**Given** Facturation actif
**When** le checkout POS affiche `ExtensionSlot('pos.checkout.post-sale-actions')`
**Then** le bouton « Facturer » apparaît et déclenche le flux facture

**Given** Facturation inactif
**When** le même slot est rendu
**Then** rien ne s'affiche

**Implementation Notes:**
- `ExtensionSlot` lit les contributions enregistrées par les modules actifs ; `deferred as` pour les modules lourds (PDF).
- Cohérent avec le backend `ExtensionRegistry` (13.7) — le frontend a son propre registre alimenté au boot.

> **Stories 15.4a → 15.4c** : la mise en place du monorepo + extraction est découpée. La migration Riverpod est faite **tôt** (transversale) avant l'extraction.

- [ ] [Deferred-from: review 13-7, 2026-10-29] Decide who owns slot contributions: server-driven (`GET` endpoint over `ExtensionRegistry.contributions`, DTO from the immutable views incl. module id) vs a Flutter-side registry fed by active modules (spec-socle §11) — avoid defining « Facturer » label/action twice [Flutter ExtensionSlot]

### Story 15.4a : Workspace Melos + package `keevo_core`

As a **développeur frontend**, I want **un monorepo Melos avec le noyau partagé extrait en package `keevo_core`**, So that **les futurs modules s'appuient sur un core isolé (MOD-FR18, dette)**.
**Acceptance Criteria:**
**Given** le repo Flutter **When** Melos est configuré **Then** `melos bootstrap` fonctionne et `packages/keevo_core` (theme, router, di, sync, network) compile/teste indépendamment ; l'app `keevo_app` le consomme.
**Implementation Notes:** `melos.yaml` ; déplacer `lib/core/` → `packages/keevo_core` ; pas de changement fonctionnel (refactor structurel) ; tests verts.

### Story 15.4b : Migration Riverpod 2 → 3 (transversale)

As a **développeur frontend**, I want **migrer tout l'état de Riverpod 2 vers 3 et supprimer le `StateNotifier` legacy**, So that **la base est cohérente avant l'extraction des modules (dette relevée par les audits)**.
**Acceptance Criteria:**
**Given** la base de code **When** la migration est faite **Then** plus aucun `StateNotifier` legacy ; tout en `@riverpod`/`Notifier`/`AsyncNotifier` (Riverpod 3) ; **tous les tests Flutter verts**.
**Implementation Notes:** s'appuyer sur le guide de migration officiel Riverpod 3 ; faire **avant** 15.4c pour éviter une double migration dans les packages.

### Story 15.4c : Extraction du module pilote en package `keevo_invoicing`

As a **développeur frontend**, I want **extraire la facturation en package Dart indépendant**, So that **le patron « module = package » est prouvé et le lazy loading possible (MOD-FR18)**.
**Acceptance Criteria:**
**Given** le workspace **When** l'extraction est faite **Then** `packages/keevo_invoicing` compile/teste seul, dépend uniquement de `keevo_core`, enregistre ses routes/slots via le registre (15.2/15.3), et est chargé `deferred`.
**Implementation Notes:** patron réutilisable pour extraire les autres features ensuite (incrémental, post-pilote).

### Story 15.5 : UI du module Facturation

As a **commerçant**,
I want **une interface pour lister, créer, prévisualiser et envoyer mes factures**,
So that **j'utilise la facturation depuis l'app (UX-DR5)**.

**Acceptance Criteria:**

**Given** le module Facturation actif
**When** j'ouvre l'écran Facturation
**Then** je vois la liste, je crée une facture (sélecteur client CRM si actif, sinon texte libre), j'aperçois le PDF, je l'envoie par WhatsApp

**Implementation Notes:**
- Package `keevo_invoicing` (15.4) ; écrans liste/création/détail ; intégration `entitlementsProvider`.
- Sélecteur client conditionné par la présence de la capability CRM côté backend (14.3).

### Deferred items routed 2026-10-30
- [ ] [Deferred-from: spec 14-1, 2026-10-30] Offline-first invoices: sync operations (`CREATE_INVOICE`, `UPDATE_INVOICE`, `ISSUE_INVOICE`, `PAY_INVOICE`, `CANCEL_INVOICE`) with `moduleId() = "invoicing"` and an `invoices` `DeltaEntityProvider` (+ `SYNC_KEYSET_INDEXES` entry); numbering stays server-side (issue needs the server counter, offline issue = held until sync) [sync / invoicing]
- [ ] [Deferred-from: review 14-1, 2026-10-30] Invoice list needs search (client name, number) and issue-date range; payment date selectable (back-dated payment received earlier) instead of `paidAt = now` [`InvoiceController` list / `PayInvoiceRequestDto`]
- [ ] [Deferred-from: spec 14-1, 2026-10-30] Settings screen for the seller fiscal profile (legal name, address, NIU, RCCM, VAT-registered, rate, prices incl. VAT, payment details); show `complianceWarnings` before issuing [app]

### Story 15.6 : Écran « Mes modules » (owner)

As a **propriétaire**,
I want **voir mes modules actifs, leur statut et expiration, avec un CTA upgrade**,
So that **je gère mon abonnement modulaire (UX-DR1, MOD-FR20)**.

**Acceptance Criteria:**

**Given** un owner
**When** il ouvre « Mes modules »
**Then** il voit modules actifs/disponibles, statut (TRIAL/ACTIVE/EXPIRED), date d'expiration, et un CTA (qui, en V1, indique de contacter le support — activation manuelle Mobile Money)

**Implementation Notes:**
- `GET /tenants/me/modules` ; pas d'auto-activation en V1 (super-admin only) — le CTA initie une demande.

### Story 15.7 : Dashboard Next.js — CI/tests + complétion Epic 9

As a **super-admin**,
I want **un dashboard testé et complété (revenus, analytics, santé système)**,
So that **je pilote la plateforme et la monétisation (FR79/80/83, dette dashboard)**.

**Acceptance Criteria:**

**Given** le dépôt dashboard
**When** la CI s'exécute
**Then** lint + tests (composants clés) tournent et bloquent en cas d'échec (comble le « 0 test »)

**Given** le dashboard
**When** Toor l'ouvre
**Then** les écrans Revenus (MRR/churn, 9-3), Analytics plateforme (9-4) et Santé système (9-5) sont fonctionnels

**Implementation Notes:**
- Ajouter Vitest/RTL + workflow GitHub Actions dashboard.
- Brancher les API admin existantes ; compléter celles manquantes (revenus/analytics/santé) côté backend si besoin.
- La **console Modules** (activer/désactiver) est en Epic 16.4.

**Items (routed 2026-10-11 from deferred-work):**
- [ ] [Deferred-from: legacy CR 9-1, 2026-05-04] TenantsPage 'use client' directly on route page [keevo/dashboard/app/tenants/page.tsx] — spec wanted Server shell + client component

**AC (interim):** every routed item above is resolved or explicitly rejected with a reason, in addition to the epic's own ACs.

### Story 15.8 : Mapper TOUTES les features Flutter aux modules + gating frontend

As a **marchand**,
I want **que l'app ne me montre/ouvre que les features de mes modules actifs**,
So that **un compte Free ne voit pas d'écrans Premium et l'expérience est cohérente avec mon abonnement (MOD-FR18 ; comble les gaps G3/G14)**.

**Acceptance Criteria:**

**Given** les 18 features Flutter existantes (`pos, catalog, products, contact, inventory, stores, team, reports, dashboard, profitability, notifications, settings, sync_indicator, audit, auth, onboarding, identity, debug`)
**When** la story est faite
**Then** chacune est **rattachée à un module** (table de correspondance feature→module) et son accès (route + entrée de nav + slots) est conditionné par `entitlementsProvider`

**Given** un tenant **Free**
**When** il navigue
**Then** les features Premium (multi-boutiques/transferts, inventaire assisté, profitabilité, rapports avancés, exports…) sont **masquées ou bloquées** avec CTA upgrade (cohérent §6.1)

**Given** la feature `debug`
**When** build release
**Then** elle est exclue

**Implementation Notes:**
- Étend 15.2 (routing dynamique) : enquêter chaque feature, produire le mapping feature→module, appliquer guards + nav conditionnelle.
- **Drift (G14)** : le client conserve ses 21 tables locales (inoffensif si module inactif) ; le gating est UI/route/sync (12.7), pas au niveau du schéma local. Documenter ce choix.
- Les features restent en dossiers `features/` (gating) jusqu'à extraction incrémentale en packages (post-pilote, après 15.4c).

---

## Epic 16 : Licences, Migration & Go-Live (Phase 6)

> Rendre la **commercialisation modulaire effective** : à-la-carte + bundles, console super-admin, conformité RGPD, migration des tenants existants, déploiement progressif.

- [ ] [Deferred-from: review 13-8b, 2026-10-30] **Blocking before disabling `pos`/`crm` (or letting them expire) in prod**: while operations are held — (a) inventory session reads are server-first (`InventorySessionRepositoryImpl.getActiveByStoreId`: an online device with a held session sees « aucun inventaire en cours »; `getCountingProducts` shows the server's expected stock); (b) held stock ops (adjust/entry/transfer) have no badge in their lists; (c) the owner dashboard and day summary read server totals only and a day closed while a sale is held misses that sale when it later applies with an `occurredAt` inside the closed day [Flutter sync / reports]

- [ ] [Deferred-from: review 13-10a, 2026-10-30] The leaderboard is gated `advanced-reports` but shown inside the profitability feature in the app — align the feature→module mapping [Flutter gating]

- [ ] [Deferred-from: review 13-10b, 2026-10-30] Hide premium entry points for tenants without the module (Inventaire, transferts, vue multi-boutiques in settings, catalogue « Vue stock multi-boutiques » tooltip); upgrade action on Audit/Product/Contact feature exceptions; persisted entitlements so an offline cold-start Premium owner keeps premium entries; XLSX refused for a premium dataset (Premium tenant with a DISABLED row) must not say « Excel est réservé » [Flutter gating]

### Story 16.1 : Cycle de vie des licences (trial hybride, grâce lecture seule, temps réel)

As a **plateforme**,
I want **gérer le cycle de vie des modules par tenant avec un blocage temps réel et une grâce en lecture seule**,
So that **le blocage est déterministe, humain et favorise la conversion (MOD-FR13)**.

**Acceptance Criteria:**

**Given** un module `ACTIVE`/`TRIAL` dont `expires_at` vient de passer
**When** une **écriture** est tentée (avant même le passage du cron)
**Then** elle est refusée en **temps réel** (`MODULE_READ_ONLY`), car `EntitlementService` recalcule l'état depuis `expires_at`/`grace_until` à chaque requête

**Given** un module en grâce (`EXPIRED`, `grace_until > now`, fenêtre 7 j)
**When** le tenant **consulte** ses données du module
**Then** la lecture passe (lecture seule) et un CTA upgrade est exposé

**Given** un module dont `grace_until` est dépassé (7 j)
**When** le cron (ou un accès) matérialise l'état
**Then** il passe `DISABLED` : routes/UI/handlers retirés, **données conservées à VIE**

**Given** un tenant qui réactive un module `DISABLED`
**When** l'activation a lieu
**Then** toutes ses données précédentes réapparaissent intactes

**Given** un nouveau tenant à l'inscription
**When** son compte est créé
**Then** tous les modules payants sont en **trial 6 mois** ; un module réactivé plus tard obtient un **trial 14 j**

**Implementation Notes:**
- `EntitlementService.isEnabled/isReadOnly/assertWritable` recalculent l'état en temps réel (spec §9.1) — **ne dépendent pas** du cron.
- Cron (réutiliser/refondre `SubscriptionExpiryService`) avec **`@SchedulerLock` (ShedLock)** : matérialise `EXPIRED→DISABLED` + déclenche notifications WhatsApp/push ; **n'est plus la source de vérité du blocage** (corrige le trou « premium gratuit jusqu'à 02:00 » identifié à l'audit).
- Transitions sur `tenant_modules` (`grace_until = expires_at + 7j`) ; trial hybride (6 mois global à l'inscription, 14 j par module).
- Le **Core n'entre jamais** dans ce cycle (jamais auto-désactivé).

- [ ] [Deferred-from: review 13-5, 2026-10-29] Every write of an expiring row sets `grace_until` (= `expires_at` + 7 d) — with `grace_until` null the state rules go straight to DISABLED; renewal of a READ_ONLY module: `planActivation` treats a READ_ONLY target/dependency as already active (spec 13.5 decision), so renewal must go through the lifecycle, not the plan [licences]

- [ ] [Deferred-from: spec 13-8a, 2026-10-30] A **free** module that `requires` a **paid** one is active by the free rule (no row) even while its paid dependency is not: the registry does not check hard dependencies, so such a module can be ENABLED with its dependency DISABLED (seen in 13.8a tests: `lcbuyer` requires `lcpaid`), and the paid dependency then cannot be deactivated (it has an active dependent). No shipped module is paid today — decide before the first paid module ships (e.g. effective state = DISABLED when a `requires` is DISABLED, or forbid free→paid `requires` at manifest load) [ModuleStateRules / ModuleManifestLoader] — also: a READ_ONLY paid dependency is not renewed by `planActivation` (counted as active), and an already-active paid dependency can expire before its dependent [review 13-8a]

### Story 16.2 : À-la-carte + Bundles

As a **super-admin**,
I want **activer un module unitairement ou appliquer un bundle (ensemble de modules)**,
So that **le client peut choisir juste une partie ou un pack (MOD-FR12)**.

**Acceptance Criteria:**

**Given** un bundle (ex. « Pro » = {pos, inventory, invoicing, crm})
**When** `POST /admin/tenants/{id}/bundles/{bundleId}`
**Then** tous les modules du bundle sont activés en un lot (avec résolution des requires), via le même moteur que l'activation unitaire

**Given** un module unitaire
**When** on l'active seul
**Then** ça fonctionne identiquement (le bundle n'est qu'une liste d'IDs)

**Implementation Notes:**
- `bundle_definitions` (ou config) = `{ bundleId → [moduleId...] }` ; application = boucle d'activation (Epic 13.8).
- Moteur **100 % à-la-carte** sous le capot (décision D1).

- [ ] [Deferred-from: review 13-3, 2026-10-29] Bundles need one plan for a set of modules (`planActivation(schema, Set<String>)` over the union of closures, one topological order) instead of planning module by module [DependencyResolver]

### Story 16.3 : Claim JWT `modules` + gating offline

As a **plateforme**,
I want **embarquer les modules actifs dans le JWT à la sélection de tenant et au refresh**,
So that **le frontend gâte hors-ligne, le backend restant source de vérité (MOD-FR15)**.

**Acceptance Criteria:**

**Given** un `select-tenant`/refresh
**When** le token est émis
**Then** il contient `modules: [...]` (modules TRIAL/ACTIVE)

**Given** un changement d'entitlement
**When** le token est rafraîchi
**Then** le claim reflète le nouvel état ; le backend revalide toujours en DB

**Implementation Notes:**
- Étendre la génération JWT (au `select-tenant`, Story 1.7 existante) ; backend continue d'appliquer `@RequiresEntitlement` (défense).

- [ ] [Deferred-from: review 13-5, 2026-10-29] Claim shape must carry read-only modules (`{id: state}` or a separate list) built from `ModuleRegistry.moduleStates` — `enabledModules` mixes ENABLED and READ_ONLY [JWT]

### Story 16.4 : Console super-admin « Modules »

As a **super-admin (Toor)**,
I want **une interface pour activer/désactiver des modules et appliquer des bundles par tenant**,
So that **je gère la commercialisation après paiement Mobile Money (MOD-FR16, UX-DR2, FR78)**.

**Acceptance Criteria:**

**Given** un tenant
**When** Toor coche/décoche des modules ou applique un bundle
**Then** l'état est appliqué (via API Epic 13.8/16.2), avec retour des dépendances/blocages, et historique d'audit
**And** Toor voit le statut/expiration par module

**Implementation Notes:**
- Écran Next.js (réutilise patterns du dashboard) ; relie aux endpoints admin modules/bundles.
- V1 : activation **super-admin only** (décision verrouillée) — débloque la monétisation FR78.

- [ ] [Deferred-from: review 13-4, 2026-10-29] `provisionAll` cost on hundreds of tenants (fresh Flyway per tenant, classpath scans, history queries) — cache discovery per module, run as an async job with progress when exposed in the console [ModuleProvisioner] — measured in 13.9 (dev, 331 tenants, crm only): first run 114 s, a no-op run **72 s** (~0.2 s/tenant). The boot pass was removed in the 13.9 review (no provisioning at deploy): existing tenants get `flyway_history_crm` only when `provisionAll` is run from the console — skip tenants whose `provisioned_version` already equals the module's highest shipped version (keep a full validate on demand), run as an async job

- [ ] [Deferred-from: review 13-9, 2026-10-30] Paid-module upgrades for tenants that already have the module active (new migrations shipped after activation: who runs them, when), and a forced deactivation path when a module's `onDeactivate` hook keeps failing (super-admin override, audited) [ModuleLifecycle / console] — merged with: [Deferred-from: spec 13-8a, 2026-10-30] Expose `ModuleProvisioner.provisionAll(moduleId)` to the console (not done in 13.8a); the lifecycle `GET /admin/tenants/{id}/modules` already returns `provisionedVersion` per module [ModuleAdminController]


- [ ] [Deferred-from: review 13-10a, 2026-10-30] Deactivating a free module (e.g. `inventory`, `messaging`) on a Premium/trial tenant is refused because rule-enabled paid modules count as dependents — the console must explain it and offer the cascade (deactivate dependents first) [console]

### Story 16.5 : Migration des tenants existants (backfill `tenant_modules`)

As a **plateforme**,
I want **initialiser `tenant_modules` pour tous les tenants existants de façon idempotente**,
So that **la bascule au modèle modulaire ne casse aucun tenant (MOD-FR19, MOD-NFR9/12)**.

**Acceptance Criteria:**

**Given** les tenants existants (< 100)
**When** le backfill s'exécute
**Then** chacun reçoit les modules « Core » + ceux correspondant à son plan actuel (PREMIUM_TRIAL/PREMIUM → modules premium), en statut cohérent
**And** le script est idempotent et a été validé en **dry-run sur copie de prod**

**Given** un tenant actuellement en **PREMIUM_TRIAL** (6 mois, non expiré)
**When** le backfill s'exécute
**Then** son **temps de trial restant** est reporté sur les modules payants (trial aligné sur la date d'expiration existante) — ni perte de jours, ni double-octroi (gap G4)

**Given** un tenant déjà rétrogradé en **FREE**
**When** le backfill s'exécute
**Then** seuls les modules du profil Free sont activés (Core), données existantes intactes

**Implementation Notes:**
- Mapping `plan courant → modules` : `PREMIUM`/`PREMIUM_TRIAL` → tous modules payants (trial avec `expires_at` = celui de la souscription actuelle pour les trials en cours) ; `FREE` → Core uniquement.
- Provisioning des tables des modules activés (Epic 13.4) pour chaque tenant ; idempotent.
- Dry-run obligatoire sur staging (Epic 10.5) ; rollback documenté.

- [ ] [Deferred-from: spec 13-4, 2026-10-29] Remove `TenantSchemaSyncService` (runtime `public → tenant` copy) once every module is provisioned by `ModuleProvisioner` (after every module is converted — today only CRM/Reporting are planned in 13.9: this item also covers converting the remaining tenant DDL of catalog/inventory/commerce/sync/identity/store/… to `db/modules/<id>`, or recording a user decision to keep the sync as a permanent shim): drop the mirroring, keep only what `TenantSchemaMigrationRunner` still needs, update the security filter caller [tenant provisioning]

- [ ] [Deferred-from: review 13-4, 2026-10-29] `provisionAll` on a free module materialises explicit ACTIVE/FREE rows for every tenant — take them into account in the backfill and in any later free→paid reclassification (16.8) [tenant_modules]

- [ ] [Deferred-from: spec 13-9, 2026-10-30] Remove the crm DDL (`clients`, `suppliers`, `product_suppliers`) from `TenantSchemaProvisioner` now that `db/modules/crm/V1` owns it — **FK ordering**: `sales.client_id REFERENCES clients(id)` is created by the core DDL, so either provision crm (module migrations) inside `TenantSchemaProvisioner.createTables` before `DDL_SALES`, or move that FK out of the core DDL into a commerce/crm migration (cf. the 14.3 `clientId` policy); same for `REQUIRED_TENANT_TABLES_DDL`/sync keyset indexes `idx_clients_sync_keyset`/`idx_suppliers_sync_keyset` [tenant provisioning / crm]

- [ ] [Deferred-from: spec 13-9, 2026-10-30] Drifted early tenants (created by the old `LIKE public INCLUDING ALL` copy — 157/331 dev schemas, likely the oldest prod ones) lack the crm indexes `idx_clients_name`/`idx_clients_archived`/`idx_suppliers_name`/`idx_product_suppliers_supplier`, the column defaults (`id`, `archived`, `created_at`, `updated_at`) and the `product_suppliers` FKs. crm is baselined at 1 without executing V1 (13.9 review), so nothing is repaired — decide with a dry-run on a prod copy whether a crm `V2` repairs them (`CREATE INDEX CONCURRENTLY` outside Flyway's transaction, `SET DEFAULT` metadata only, FKs `NOT VALID` + `VALIDATE` after an orphan report). Dev note: on keevo_dev V1 was applied once before the review (157 schemas already repaired, histories `0`+`1:SQL`) [crm migrations]

- [ ] [Deferred-from: review 13-9, 2026-10-30] Stop `TenantSchemaSyncService` mirroring `clients`/`suppliers`/`product_suppliers` (and every other converted module's tables) once the module migrations are the single writer — together with the full conversion above [tenant provisioning]

- [ ] [Deferred-from: review 13-10a, 2026-10-30] Backfill parity with the 13.10a transition rule: PREMIUM/PREMIUM_TRIAL → paid module rows with the subscription's `expires_at` and `grace_until` = +7 d; status ≠ ACTIVE and no-subscription tenants → no paid rows; after the backfill the rule must give the same states (test on a prod copy) [backfill]

### Story 16.6 : Conformité RGPD — suppression compte/données (FR91)

As a **utilisateur**,
I want **demander la suppression complète de mon compte et de mes données**,
So that **mes droits RGPD sont respectés (MOD-FR20, FR91)**.

**Acceptance Criteria:**

**Given** une demande de suppression de compte
**When** elle est traitée
**Then** les données du tenant sont purgées (`DROP SCHEMA kv_xxx` propre au schema-per-tenant) + identité `public` anonymisée/supprimée, avec trace d'audit de la demande

**Given** un module simplement **désactivé** (non suppression de compte)
**When** le temps passe
**Then** ses données **ne sont JAMAIS purgées** (conservation à vie) et réapparaissent à la réactivation

**Implementation Notes:**
- **Seule** la suppression de compte RGPD purge (décision validée — pas de purge à 90 j). Suppression = `DROP SCHEMA` (propre, complet).
- Aucun job de purge des modules `DISABLED` : leurs tables/données restent dans le schéma tenant indéfiniment.

### Story 16.7 : Go-live progressif (canary) + runbook

As a **exploitant**,
I want **déployer la refonte en canary (10 % des tenants) avec un runbook**,
So that **le risque de mise en production est maîtrisé (MOD-NFR12, fiabilité)**.

**Acceptance Criteria:**

**Given** la refonte validée en staging
**When** on déploie
**Then** un sous-ensemble de tenants (10 %) bascule d'abord, monitoré (Epic 11.2) ; en cas d'anomalie, rollback documenté
**And** après stabilisation, rollout complet

**Implementation Notes:**
- Stratégie canary (par tenant ou par instance) ; runbook (rollback Flyway, restauration backup).
- HA/Redis restent différés tant que la charge ne le justifie pas (< 500 tenants).

- [ ] [Deferred-from: spec 13-4, 2026-10-29] **Decision for the user:** stray copies of global-only tables in tenant schemas (dev: 331 schemas × `shedlock`, `flyway_schema_history`, `event_publication`, `event_listener_receipt`, `refresh_tokens`, `password_reset_tokens`, `user_tenant_memberships`, `user_sync_state`, `feedback`) are unused but kept (an update must not alter prod data). Choose: keep forever, or a one-shot reviewed cleanup (`DROP TABLE IF EXISTS` per tenant, empty-table check first, prod backup). Counts are logged at boot by `GlobalTableStrayCopiesReporter` [tenant schemas]

- [ ] [Deferred-from: review 13-10a, 2026-10-30] **Go-live gate**: the backend enforcement of 13.10a must not reach production before a minimum app version that understands `MODULE_INACTIVE`/`MODULE_READ_ONLY`/`MODULE_NOT_ENTITLED` is forced (V1 apps treat unknown sync reasons as terminal: counting sessions abandoned, transfers quarantined; the export upgrade sheet keys on `PLAN_FEATURE_REQUIRED`) — or ship a compatibility path; communicate to Free tenants which features become Premium (transfers, counting, advanced reports, profitability, PDF/Excel exports, alerts/owner pushes, report configuration) [release]
- [ ] [Deferred-from: spec 14-2, 2026-10-30] **Go-live config (invoices / company logo):** set `KEEVO_INVOICE_LINK_SECRET` (or `KEEVO_EXPORT_LINK_SECRET`, ≥ 32 chars, stable) — otherwise WhatsApp invoice links (30 days) die at each restart (WARN `INVOICE_LINK_SECRET_MISSING`); set `KEEVO_PUBLIC_BASE_URL=https://keevo.ad2s.cm` (else links are built from forwarded headers); `KEEVO_COMPANY_LOGO_DIR` on a persistent volume (logos lost on redeploy otherwise) [deploy config]
- [ ] [Deferred-from: live test 14-2, 2026-10-30] Pre-existing: a request with an unsupported `Content-Type` (e.g. JSON body on a multipart endpoint) answers 500 `INTERNAL_ERROR` instead of 415 — map `HttpMediaTypeNotSupportedException` to 415 with a French message in `GlobalExceptionHandler` [shared web]

### Story 16.8 : Profils de plan & plafonds configurables (super-admin)

As a **super-admin (Toor)**,
I want **éditer les plafonds (boutiques/produits/employés) et la composition des profils Free/Premium sans redéploiement**,
So that **je peux ajuster l'offre commerciale dynamiquement (MOD-FR13b, décision validée)**.

**Acceptance Criteria:**

**Given** les profils de plan
**When** ils sont initialisés
**Then** une config persistée (`plan_profiles` + `profile_modules`) existe avec les défauts validés : **Free = 1 boutique / 500 produits / 2 employés** + modules Free (core, catalog, pos, inventory de base, crm basique, rapport EOD fixe)

**Given** un super-admin
**When** il modifie un plafond Free (ex. 1 → 2 boutiques) ou ajoute un module à un profil
**Then** le changement prend effet **sans redéploiement** et `PlanLimitGuard` applique la nouvelle valeur à la prochaine vérification

**Given** le code existant
**When** la migration est faite
**Then** `PlanLimitGuard` lit `plan_profiles` (plus l'enum `PlanType`), qui n'est plus la source de vérité des limites

**Implementation Notes:**
- Migration Flyway `public` : `plan_profiles`, `profile_modules` ; seed des défauts validés (cf. `plan-refonte-modulaire.md §6.1/6.2`).
- Refactor `PlanLimitGuard` + `SubscriptionExpiryService`/downgrade pour lire les limites depuis la config (retirer les constantes de l'enum `PlanType` ou les y garder comme fallback de seed uniquement).
- UI : section « Profils & plafonds » dans la console super-admin (Epic 15.7 / 16.4).
- TDD : changer un plafond en base → la création au-delà est refusée/autorisée en conséquence sans redémarrage.

---


**Items (routed 2026-10-29 from review 12-2c/12-2d):**
- [ ] [Deferred-from: review 12-2c/12-2d, 2026-10-29] `ProductCountPort` returns 0 for an invalid schema / missing table, so the product limit fails open (same JDBC code as before 12.2d) — fail closed when limits become configurable [catalog ProductCountAdapter]

### Story 16.9 : Facturation électronique DGI (taxation en temps réel, art. L 8 sexies CGI)

As a **commerçant assujetti**, I want **que mes factures Keevo soient transmises à la DGI selon le dispositif homologué**, So that **je reste conforme au régime de taxation en temps réel (loi de finances 2026) et à la facture électronique normalisée**.

**Scope:** follow the DGI technical specifications once published (homologated electronic devices, instant transmission of invoicing data, validation identifier / QR code on the invoice); decide homologation of Keevo vs integration with an accredited provider; until then Keevo invoices must never claim « normalisée DGI ».

- [ ] [Deferred-from: spec 14-1, 2026-10-30] Legal research 2026-10-30: LF 2026 creates real-time taxation (art. L 8 sexies CGI: tax collected at the transaction through homologated devices, invoicing data sent instantly to the DGI); FEN e-invoicing reported mandatory from 2026-01-01 (secondary source, specs not published) — Keevo invoices are commercial invoices only [invoicing / compliance]

**Interim AC:** Given the published DGI specifications, when an invoice is issued by a concerned tenant, then it is transmitted and carries the DGI identifier; otherwise no « normalisée » mention appears.

## Epic 17 : Complétion Fonctionnelle & Gating Produit (Phase 7)

> **Objectif :** refermer les FR produit non planifiés + livrer les features Premium décidées, **gâtées** par le moteur d'entitlements. Dépend d'Epic 13 (gating) et du classement §6.1. Rend l'app **fonctionnellement complète** sur les 3 surfaces. (Comble les gaps G5-G13.)
> **Note :** 17.1-17.4 reprennent et **remplacent** les stories backlog d'origine (4-5, 4-6, 8-2, 8-4) — désormais planifiées dans la refonte.

- [ ] [Deferred-from: review 13-10a, 2026-10-30] With `report-config` DISABLED the end-of-day report must use the fixed default (hour/channel) — stored `report_preferences` set during a trial must be ignored; retire/replace the 13.10a transition rule (plan → paid modules without row) by `profile_modules` [plan profiles]

### Story 17.1 : Annulation / correction de vente (FR90)
As a **propriétaire**, I want **annuler ou corriger une vente avec justification, stock réajusté**, So that **les erreurs de caisse sont gérables (FR90)**.
**Acceptance Criteria:** **Given** une vente **When** OWNER l'annule/corrige avec justification **Then** le stock est réajusté (mouvement inverse audité), la vente passe `CANCELLED`/`CORRECTED`, sync OK. EMPLOYEE non autorisé (403). **Notes:** module `commerce` ; handler sync `CANCEL_SALE` existe déjà — étendre pour correction ; remplace backlog 4-5.

### Story 17.2 : Leaderboard vendeurs + historique global (FR44)
As a **propriétaire**, I want **un classement des vendeurs par performance**, So that **je motive/évalue l'équipe (FR44)**.
**Acceptance Criteria:** **Given** des ventes par employé **When** OWNER ouvre le leaderboard **Then** classement (CA/nb ventes, période) ; **Premium** (gâté). **Notes:** module `reporting` ; données existantes ; remplace backlog 4-6.
**Items routés depuis v1s-14-2 (V1 owner-only, `GET /api/v1/reporting/leaderboard` + onglet Rapports > Équipe) :**
- [ ] Réglage owner « Afficher le leaderboard aux employés » (Story 4.6 / UX16) : OFF par défaut, confirmation à l'activation, classement anonymisé côté employé une fois activé. Exclu de la V1 (UX16 V1 = owner-only, endpoint EMPLOYEE → 403) ; à reprendre ici avec le gating Premium.

### Story 17.3 : Suivi d'activité des employés (FR67)
As a **propriétaire**, I want **voir l'activité de mes employés (dernière connexion, ventes, actions)**, So that **je supervise l'équipe (FR67)**.
**Acceptance Criteria:** **Given** des employés **When** OWNER ouvre l'activité **Then** dernière connexion + ventes + actions clés (depuis l'audit trail) ; **Premium**. **Notes:** module `identity/employee` + lecture `audit_log` ; remplace backlog 8-2.

### Story 17.4 : Feedback / signalement in-app (FR92)
As a **utilisateur**, I want **envoyer un feedback ou signaler un problème depuis l'app**, So that **la boucle produit existe (FR92)**.
**Acceptance Criteria:** **Given** un utilisateur **When** il soumet un feedback **Then** stocké + visible côté super-admin ; **Free** (rétention adoption). **Notes:** petit module `feedback` (Core) ; remplace backlog 8-4.

### Story 17.5 : Santé système + alertes proactives + push global (FR81/82/83)
As a **super-admin (Toor)**, I want **la santé plateforme, des alertes proactives (tenant J+5 sans sync, abonnement expirant) et des notifications push globales**, So that **je pilote l'exploitation (FR81/82/83)**.
**Acceptance Criteria:** **Given** la plateforme **When** Toor ouvre la santé système **Then** statut serveurs/sync/erreurs (depuis l'observabilité Epic 11) ; **And** alertes WhatsApp/push auto (tenant sans sync J+5, expiry <7j) ; **And** push global à tous les tenants. **Notes:** `admin` + `messaging` + dashboard ; remplace backlog 9-5.

### Story 17.6 : Exports PDF / Excel (FR55) — Premium
As a **propriétaire Premium**, I want **exporter mes rapports en PDF/Excel**, So that **je partage/archive (FR55)**.
**Acceptance Criteria:** **Given** un rapport **When** Premium exporte **Then** fichier PDF/Excel généré ; **gâté Premium** (Free → CTA). **Notes:** module `reporting` ; réutilise la lib PDF de 14.2.

### Story 17.7 : Notification owner sur changement de prix par un employé — Premium
As a **propriétaire Premium**, I want **être notifié (WhatsApp/push) quand un employé modifie le prix de vente d'un produit**, So that **je garde le contrôle des marges (décision validée)**.
**Acceptance Criteria:** **Given** Premium actif **When** un employé modifie un prix de vente **Then** notif owner (event `PRICE_OVERRIDDEN`/`PRODUCT_UPDATED` → listener) ; **Free → pas de notif** (gâté). **Notes:** module `messaging` ; l'event existe (`SalePriceOverriddenEvent`) — ajouter le listener + gating.

### Story 17.8 : Gating « configuration des rapports » (Free fixe / Premium configurable — FR60)
As a **plateforme**, I want **que la config des rapports (toggle, heures, types, fréquence) soit réservée au Premium ; le Free a un comportement fixe**, So that **le classement est respecté (décision validée)**.
**Acceptance Criteria:** **Given** un tenant **Free** **When** il tente de configurer ses rapports **Then** refus/lecture seule (rapport EOD fixe : heure par défaut) ; **Given Premium** **Then** configuration autorisée. **Notes:** `reporting`/`messaging` + `@RequiresEntitlement` ; UI Flutter conditionnée.

### Story 17.9 : Régression E2E multi-surface (mobile + backend + dashboard)
As a **équipe**, I want **une suite de régression de bout en bout couvrant les parcours critiques après modularisation**, So that **l'app reste fonctionnelle sur les 3 surfaces (gap G13)**.
**Acceptance Criteria:** **Given** la refonte déployée en staging **When** la suite E2E tourne **Then** parcours critiques verts : inscription→onboarding, vente offline→sync, transfert, inventaire, rapport WhatsApp, activation/désactivation module, facturation auto, gating Free/Premium. **Notes:** étend les scripts E2E Python existants + tests dashboard (15.7) ; exécutée avant le go-live (16.7).

---

## Récapitulatif

| Épic | Stories | Couvre |
|---|---|---|
| 10 — Préparation & Garde-fous | 10.1 → 10.7 (7) | MOD-NFR1, 3, 11, 12 |
| 11 — Stabilisation Socle & Dette | 11.1 → 11.6 (6) | MOD-NFR4, 6 + dette + FR68 |
| 12 — Découplage Bounded Contexts | 12.1, 12.2a-e, 12.3-12.7 (11) | MOD-NFR2, 3, 5, 8, 9, 10 |
| 13 — Moteur de Modularité (Socle) | 13.1 → 13.10 (10) | MOD-FR1-6, 9-11, 14 ; NFR7, 10 |
| 14 — Module Pilote Facturation | 14.1 → 14.6 (6) | MOD-FR7-10, 17 ; UX-DR3, 5 |
| 15 — Frontend Modulaire | 15.1-15.3, 15.4a-c, 15.5-15.8 (10) | MOD-FR14, 15, 18 ; UX-DR1, 3, 4 |
| 16 — Licences, Migration & Go-Live | 16.1 → 16.8 (8) | MOD-FR12, 13, 13b, 15, 16, 19, 20 ; UX-DR2 ; FR78/79 |
| 17 — Complétion Fonctionnelle & Gating | 17.1 → 17.9 (9) | FR44, 55, 60, 67, 81-83, 90, 92 + notif prix + régression E2E |
| **Total** | **67 stories** | Toutes les exigences MOD-FR/MOD-NFR/UX-DR + FR produit résiduels |

