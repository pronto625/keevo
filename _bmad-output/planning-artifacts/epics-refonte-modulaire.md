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

### Epic 11 : Stabilisation du Socle & Dette (Phase 1 allégée)
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

- [ ] [Review][Decision] **TLS staging — ACME HTTP-01 sur :8080 ne sera jamais validé** — **DÉCISION (Toor) : router via le Traefik prod.** Le Traefik prod (:80/:443) gère le FQDN + ACME staging puis proxy vers `keevo_staging_backend`. ⚠️ Implémentation = réécriture cross-stack du pipeline **prod** (non testable en review, à fort enjeu) → **non appliquée à l'aveugle**, consignée comme tâche deploy-time. Étapes concrètes :
  1. **Réseau partagé** : créer un réseau Docker externe (ex. `keevo_edge`) ; y attacher le `traefik` prod ET le `keevo_staging_backend`.
  2. **Prod `traefik/dynamic.yml`** : ajouter un router `staging` (`Host(\`staging.api.keevo.cm\`)`, entrypoint `websecure`, `certResolver: letsencrypt`) + un service pointant `http://keevo_staging_backend:4500`.
  3. **Staging `deploy-staging.yml`** : retirer le Traefik staging + l'ACME + le mapping `:8443` (plus de TLS local) ; attacher le backend staging au réseau `keevo_edge` ; ne plus exposer publiquement le backend (accès uniquement via Traefik prod).
  4. **DNS** : `staging.api.keevo.cm` → IP du VPS (déjà :80/:443 prod).
  À appliquer + valider lors du 1er bring-up staging réel (touche la prod).

**Items (routed 2026-10-11 from deferred-work):**
- [ ] [Deferred-from: CR 10-5, 2026-06-19] Staging TLS via prod Traefik (owner decision) [traefik/dynamic.yml, deploy-staging.yml] — shared keevo_edge network + staging router; prerequisite for valid staging HTTPS
- [ ] [Deferred-from: CR 10-5, 2026-06-19] Staging reuses prod JWT/Firebase/DB secrets [deploy-staging.yml] — staging compromise could forge prod tokens; dedicated keys
- [ ] [Deferred-from: CR 10-5, 2026-06-19] workflow_dispatch without branch guard [.github/workflows/deploy-staging.yml:8] — check github.ref
- [ ] [Deferred-from: CR 10-5, 2026-06-19] Unconditional firebase bind-mount + no .env guard [deploy-staging.yml:231] — conditional mount + [ -f .env ] || exit 1
- [ ] [Deferred-from: CR 10-5, 2026-06-19] cancel-in-progress can cut a staging deploy mid-way [deploy-staging.yml:13] — disable cancellation on mutating steps
- [ ] [Deferred-from: CR 10-4, 2026-06-19] Backup systemd unit hardcodes /home/ubuntu [keevo/backend/scripts/keevo-backup.service:8-9] — template the path at deploy
- [ ] [Deferred-from: CR 10-4, 2026-06-19] .env.backup not created/validated by deploy [keevo-backup.service, deploy-backend.yml] — EnvironmentFile=- + deploy-time WARN
- [ ] [Deferred-from: CR 10-4, 2026-06-19] No alerting on backup failure [keevo-backup.service] — OnFailure= unit (email/webhook)
- [ ] [Deferred-from: CR 10-4, 2026-06-19] S3 backups lack least-privilege IAM/versioning/Object-Lock [backup-db.sh / S3 config] — attacker on box can delete backups
- [ ] [Deferred-from: CR 10-4, 2026-06-19] Restore --no-owner --no-acl loses grants [DEPLOYMENT_CHECKLIST §7] — document the re-grant step
- [ ] [Deferred-from: CR 10-4, 2026-06-19] DEPLOYMENT_CHECKLIST §7 in root docs/ instead of keevo/backend/docs/ [docs/DEPLOYMENT_CHECKLIST.md vs keevo/backend/docs/DEPLOYMENT_CHECKLIST.md] — two checklists exist; pick one location

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
- Générer la doc dans `target/modulith-docs` (artefact CI).
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

### Story 12.2b : API publique du module `inventory`

As a **architecte**, I want **une `api` pour `inventory` (stock, mouvements, seuils)**, So that **`sync`, `reporting`, `commerce` y accèdent via contrat (MOD-NFR3)**.
**Acceptance Criteria:** **Given** les consommateurs d'`inventory` **When** la story est faite **Then** ils passent par `inventory.api` ; `verify()` propre côté `inventory`. *(Dépend du ré-alignement 12.5 si fait avant — sinon coordonner.)*

### Story 12.2c : API publique du module `commerce`

As a **architecte**, I want **une `api` pour `commerce` (ventes, clôture)**, So that **`sync`, `messaging`, `reporting` y accèdent via contrat**.
**Acceptance Criteria:** **Given** les consommateurs de `commerce` **When** la story est faite **Then** accès via `commerce.api` (+ events au catalogue partagé) ; `verify()` propre côté `commerce`.

### Story 12.2d : API publiques `identity` + `store` + `subscription`

As a **architecte**, I want **des `api` pour les modules transverses très dépendus (`identity` 21+14 imports, `store`, `subscription`)**, So that **leurs consommateurs passent par contrat (MOD-NFR3)**.
**Acceptance Criteria:** **Given** `reporting→identity`, `messaging→identity`, `subscription→identity`, `catalog→store/subscription` **When** la story est faite **Then** tous via `{module}.api` ; `verify()` propre sur ces 3 modules.

### Story 12.2e : API publiques `reporting`/`messaging`/`sync` + activation `verify()` BLOQUANT

As a **architecte**, I want **finaliser les dernières `api` et passer `verify()` en build bloquant**, So that **toute régression de frontière casse désormais la CI (MOD-NFR3, MOD-NFR8)**.
**Acceptance Criteria:**
**Given** tous les modules dotés de leur `api`
**When** on passe `allowedDependencies` en strict + `verify()` en mode `fail`
**Then** la build est **verte** et **échoue** si un nouvel import cross-module hors `api`/events est introduit
**And** le diagramme de modules (Documenter) est à jour

**Implementation Notes:** clôture d'Epic 12 côté frontières ; combiner avec les règles ArchUnit complémentaires (12.6).

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
- Migrations Flyway inchangées (tables identiques) ; pur refactor de code.

### Story 12.6 : Règles ArchUnit complémentaires (graphe `requires`, capabilities, schéma)

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
- [ ] [Deferred-from: CR 10-6, 2026-06-19] ArchUnit/Modulith baseline -> ratchet [ArchitectureTest / ModulithVerificationTest] — assert known-violation set, fail on new ones
- [ ] [Deferred-from: CR 10-6, 2026-06-19] allowedDependencies target vs actual [package-info allowedDependencies] — align identity/messaging/catalog/store before enforcement
- [ ] [Deferred-from: CR 10-6, 2026-06-19] Wrap detectModulesAndGenerateDocumentation in try/catch [Modulith doc test] — invalid model must not break the warning-mode build
- [ ] [Deferred-from: CR 10-6, 2026-06-19] Refine ArchUnit rules (persistence pattern, DO_NOT_INCLUDE_TESTS) [ArchitectureTest] — -
- [ ] [Deferred-from: CR 10-6, 2026-06-19] CI upload-artifact without if-no-files-found: warn [.github/workflows/backend-ci.yml:62,69] — missing reports fail the job
- [ ] [Deferred-from: CR 10-6, 2026-06-19] Dependency scopes (spring-modulith-api provided, archunit via BOM) [pom.xml] — avoid version skew vs pinned 1.3.0

**AC (interim):** every routed item above is resolved or explicitly rejected with a reason, in addition to the epic's own ACs.

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

---

## Epic 14 : Module Pilote Facturation (Phase 4)

> Livrer le **premier module vendable, autonome ET composable**, qui **prouve la vision** : facturation seule → +CRM → +POS (facture auto) → POS désactivé sans casse. C'est le **patron** de tout futur module.

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
- Consommer `ClientLookupPort` (fourni par CRM en Story 13.9) via `CapabilityRegistry` (Optional).
- Aucun import du module CRM hors de son `api` (vérifié par `verify()`).

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
- `@ApplicationModuleListener void on(SaleCompletedEvent)` dans `invoicing` (event du catalogue partagé `core/event/catalog`, pas d'import de POS).
- **Idempotence** : clé `(eventId)` ou `(saleId)` → upsert ; le registre Modulith peut rejouer.
- Le handler ne s'exécute que si `invoicing` actif (gating via `ModuleRegistry` au dispatch).
- Si le mapping vente→facture grossit (taxes, remises, multi-devises futures) → extraire un **bridge dédié** `bridge-pos-invoicing` (décision D3) ; sinon garder le listener simple.

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
- POS `declare("pos.checkout.post-sale-actions")` (Story 13.7) ; Facturation `contribute(...)` dans `registerExtensions`.
- Le rendu Flutter du slot est en Story 15.3.

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

## Epic 17 : Complétion Fonctionnelle & Gating Produit (Phase 7)

> **Objectif :** refermer les FR produit non planifiés + livrer les features Premium décidées, **gâtées** par le moteur d'entitlements. Dépend d'Epic 13 (gating) et du classement §6.1. Rend l'app **fonctionnellement complète** sur les 3 surfaces. (Comble les gaps G5-G13.)
> **Note :** 17.1-17.4 reprennent et **remplacent** les stories backlog d'origine (4-5, 4-6, 8-2, 8-4) — désormais planifiées dans la refonte.

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

