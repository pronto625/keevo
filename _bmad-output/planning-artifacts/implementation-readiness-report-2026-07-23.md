---
stepsCompleted:
  - step-01-document-discovery
  - step-02-prd-analysis
  - step-03-epic-coverage-validation
  - step-04-ux-alignment
  - step-05-epic-quality-review
  - step-06-final-assessment
documentsInScope:
  prd: planning-artifacts/prd.md
  architecture: planning-artifacts/architecture.md
  ux: planning-artifacts/ux-design-specification.md
  epics:
    format: sharded
    index: planning-artifacts/epics/index.md
    files:
      - planning-artifacts/epics/epic-1-foundation-infrastructure-authentication.md
      - planning-artifacts/epics/epic-2-catalogue-produits-base-fournisseursclients.md
      - planning-artifacts/epics/epic-3-gestion-multi-boutiques-stock-quipe.md
      - planning-artifacts/epics/epic-4-point-de-vente-pos.md
      - planning-artifacts/epics/epic-5-moteur-de-synchronisation-offline-first.md
      - planning-artifacts/epics/epic-6-inventaire-assist.md
      - planning-artifacts/epics/epic-7-rapports-dashboard-communication-whatsapp.md
      - planning-artifacts/epics/epic-8-alertes-notifications-gestion-oprationnelle.md
      - planning-artifacts/epics/epic-9-super-admin-dashboard-toor.md
      - planning-artifacts/epics/epic-list.md
      - planning-artifacts/epics/overview.md
      - planning-artifacts/epics/requirements-inventory.md
      - planning-artifacts/epics/epics-remediation-audit.md
  excluded:
    - planning-artifacts/archive/epics.md (archived, superseded)
notes: >
  User request originated from a specific ask to verify v1-stabilization fixes
  (stories v1s-12-* through v1s-15-*) are consistent between docs and code.
  User confirmed running the full Implementation Readiness workflow
  (PRD/Architecture/UX/Epics alignment) rather than a narrow v1-stabilization-only check.
  Additional implementation-artifacts relevant to the v1-stabilization angle:
  implementation-artifacts/v1-stabilization-stories.md,
  implementation-artifacts/v1s-12-1..v1s-15-5 story files,
  implementation-artifacts/sprint-status.yaml, implementation-artifacts/deferred-work.md.
---

# Implementation Readiness Assessment Report

**Date:** 2026-07-23
**Project:** Keevo

## Document Discovery

### PRD
- Whole document: `planning-artifacts/prd.md`

### Architecture
- Whole document: `planning-artifacts/architecture.md`

### UX Design
- Whole document: `planning-artifacts/ux-design-specification.md`

### Epics & Stories
- Sharded format: `planning-artifacts/epics/` (index.md + 9 epic files + supporting docs)
- Archived (excluded): `planning-artifacts/archive/epics.md`

### No critical duplicates found.

## PRD Analysis

### Functional Requirements

**Authentification & Onboarding:** FR1–FR7
**Provisioning & Initialisation Tenant:** FR8–FR15b
**Gestion des Souscriptions:** FR16–FR20
**Gestion des Produits:** FR21–FR29
**Gestion Multi-Boutiques & Warehouse:** FR30–FR36
**Point de Vente — POS:** FR37–FR44
**Inventaire Automatisé:** FR45–FR49
**Rapports & Dashboard:** FR50–FR56
**Communication WhatsApp:** FR57–FR60
**Alertes & Notifications:** FR61–FR64
**Gestion des Utilisateurs:** FR65–FR68
**Synchronisation Offline-First:** FR69–FR76
**Super Admin Dashboard:** FR77–FR83
**Sécurité & Audit:** FR84–FR86
**Gestion Clients & Fournisseurs:** FR87–FR89
**Gestion des Erreurs & Lifecycle:** FR90–FR93

Total FRs: 94 (FR1–FR93 + FR15b)

### Non-Functional Requirements

**Performance:** NFR1–NFR6
**Sécurité:** NFR7–NFR13
**Scalabilité:** NFR14–NFR17
**Fiabilité & Disponibilité:** NFR18–NFR22
**Accessibilité & Utilisabilité:** NFR23–NFR27
**Intégration:** NFR28–NFR31
**Compatibilité:** NFR32–NFR34

Total NFRs: 34

### Additional Requirements / Constraints

- Devise unique XAF en V1 (FR86)
- API versioning `/api/v1/` obligatoire (NFR31)
- Architecture i18n prête pour l'anglais mais interface FR uniquement en V1 (NFR26)
- Plan Free : 1 boutique / 500 produits / 3 employés (FR16)
- Premium Trial : 6 mois offerts à l'inscription (FR15)
- Développeur solo, MVP 3-4 mois (contrainte ressources)
- Isolation multi-tenant : schema-per-tenant PostgreSQL (contrainte architecturale forte, NFR10)

### PRD Completeness Assessment

Le PRD est complet et bien structuré : Executive Summary, Success Criteria, Scope (MVP/Growth/Vision), 4 User Journeys détaillés, Innovation, spécificités Mobile/Desktop/SaaS B2B (plateformes, offline strategy, tenant model, RBAC, notifications, intégrations, billing), scoping phasé, puis 94 FRs et 34 NFRs numérotées avec granularité fine. Aucune section standard manquante. Le document est cohérent avec les stories de stabilisation v1 observées dans le code (JWT, RBAC, sync offline, souscriptions, audit).

## Epic Coverage Validation

### Coverage Matrix

Le document `epics/requirements-inventory.md` contient une **FR Coverage Map** exhaustive mappant chaque FR à un epic (et souvent une story). Vérification programmatique de la couverture complète (FR1–FR93 + FR15b) contre cette carte :

- **FRs manquants dans les epics : aucun**
- **FRs présents dans les epics mais absents du PRD : aucun**

Le mapping suit également des mises à jour tracées (ex : FR16 "mise à jour 2026-03-06" reflétant un changement de limites du plan gratuit — 1 boutique / 3 employés au lieu de 3 boutiques / 5 employés — cohérent avec le PRD actuel).

Répartition par epic (extrait) :
- Epic 1 (Foundation/Auth) : FR1–FR20, FR72, FR75, FR84–FR86, FR93
- Epic 2 (Catalogue/Fournisseurs) : FR21–FR29, FR87, FR89
- Epic 3 (Multi-boutiques/Équipe) : FR30–FR36, FR65, FR66
- Epic 4 (POS) : FR37–FR44, FR88, FR90
- Epic 5 (Sync Offline) : FR69–FR71, FR73, FR74, FR76
- Epic 6 (Inventaire) : FR45–FR49, FR59 (partiel)
- Epic 7 (Rapports/WhatsApp) : FR50–FR58, FR60, FR63
- Epic 8 (Alertes/Ops) : FR61, FR62, FR64, FR67, FR68, FR91, FR92
- Epic 9 (Super Admin) : FR77–FR83

### Missing Requirements

Aucune. Couverture à 100%.

### Coverage Statistics

- Total PRD FRs: 94 (FR1–FR93 + FR15b)
- FRs covered in epics: 94
- Coverage percentage: **100%**

## UX Alignment Assessment

### UX Document Status

**Found** — `ux-design-specification.md` (935 lignes), complet et détaillé (design system, 24 user flows couvrant FR1-FR93, 12 composants custom, patterns de cohérence).

### UX ↔ PRD Alignment

- Les 3 personas (Simon, Loïc, Toor) et leurs Success Criteria dans le PRD sont fidèlement traduits en flows UX (24 flows, table de mapping FR explicite par flow).
- Le modèle RBAC (Owner/Employee/Super Admin) du PRD est cohérent avec la double-persona UX et les règles de visibilité (ex : leaderboard caché employé, UX16).
- Le modèle offline-first (7 jours, sync <60s) du PRD est reflété dans le principe UX "Offline = Normal" et le composant Sync Indicator (4 états).
- Le modèle de souscription (Free / Premium Trial / Premium) est cohérent entre PRD FR16-20 et Flow 13 UX.
- Aucune divergence UX ↔ PRD significative détectée.

### UX ↔ Architecture Alignment

- La structure Flutter Feature-first (Architecture) supporte directement la Component Strategy UX (dossiers `features/pos/`, `features/inventory/`, etc. correspondant aux composants custom POS Card, Inventory Row...).
- Le pattern Backend-First-When-Online / Local-First-When-Offline (Architecture) supporte le principe UX "Offline = Normal" et le Sync Indicator.
- Le RBAC via `@RequiresRole(OWNER)` (Decorator) supporte la double-expérience Owner/Employee de l'UX.
- Le flow d'authentification "Two-Step Login" (Architecture, Multi-Tenant Identity Model) correspond au Flow 21 UX (connexion multi-device).
- Riverpod + `AsyncValue` (Architecture) supporte les exigences de feedback instantané UX (<200ms).

### ⚠️ Alignment Issues (PRD ↔ Architecture — pas UX)

**1. Incohérence majeure : Sync Engine "PowerSync" (PRD) vs "Custom REST delta-based" (Architecture)**

Le PRD mentionne explicitement **PowerSync** comme moteur de synchronisation à plusieurs endroits :
- Ligne 88 (MVP scope) : *"Synchronisation offline-first performante via PowerSync et architecture multi-tenant sur Spring Boot"*
- Ligne 207 : *"Sync Engine : PowerSync en mode delta-based"*
- Ligne 238 (Integration Map) : *"PowerSync | Synchronisation offline-first | MVP"*

Or, `architecture.md` documente une **décision explicite et irréversible de Toor (2026-03-04)** : *"Full PostgreSQL schema separation per tenant is non-negotiable. If this holds → Path A is locked, PowerSync is excluded permanently."* L'architecture retenue est un **moteur REST delta-based custom** (confirmé dans "Technology Decisions Summary" : *"Sync engine | Custom REST delta-based (MVP)"*).

- **Impact :** Le PRD n'a pas été mis à jour après cette décision d'architecture — il reste la seule source documentaire qui affirme encore l'usage de PowerSync.
- **Bonne nouvelle :** Les epics (`requirements-inventory.md` ARCH19) reflètent déjà correctement l'architecture custom REST — donc l'implémentation et les epics sont alignés entre eux, seul le PRD est resté obsolète sur ce point précis.
- **Recommandation :** Mettre à jour le PRD (Executive Summary MVP scope, section "Offline Strategy", "Integration Map") pour remplacer "PowerSync" par "moteur de synchronisation REST delta-based custom".

**2. Évolution du modèle d'identité multi-tenant non répercutée dans le PRD**

Le PRD (section "Tenant Model") décrit un modèle simple : *"tenant_id porté dans le JWT"* (couplage 1:1 utilisateur↔tenant implicite). L'architecture documente une évolution du 2026-03-07 (Story 1.7, "correct course") vers un modèle N:N (`user_tenant_memberships`) avec authentification en deux étapes, pour supporter un utilisateur appartenant à plusieurs tenants. C'est une évolution technique justifiée et documentée comme telle dans l'architecture, mais le PRD n'a pas été mis à jour en conséquence — divergence mineure de terminologie, sans impact fonctionnel sur le MVP (le flow reste transparent pour 99% des utilisateurs V1 mono-tenant).

### Warnings

Aucun warning UX manquant — la documentation UX est complète et n'a pas de gap architectural (pas de composant UX non supporté par l'architecture).

## Epic Quality Review

### Contexte

Deux générations d'epics coexistent :
1. **Epics 1–9** (`epics/epic-1..9-*.md`) — epics produit originaux, orientés valeur utilisateur (Simon/Loïc/Toor), structure standard story-by-story.
2. **Epics 12–16** (`epics-remediation-audit.md`) — epics de **stabilisation V1 post-audit** (2026-07-20), correspondant directement aux stories `v1s-12-*` à `v1s-16-*` mentionnées par l'utilisateur.

### A. User Value Focus Check (Epics 1-9)

Les epics 1-9 sont tous formulés en termes de valeur utilisateur ("Simon peut...", "Loïc peut..."), pas de jalons techniques. ✅ Conforme.

### B. Epics 12-16 — Nature spécifique (hors gabarit standard)

Les Epics 12 ("Sécurité & Hardening"), 13 ("Concurrence & Intégrité données"), 15 ("Discipline de tests & dette technique") sont **intrinsèquement techniques** — normalement un "red flag" du gabarit standard create-epics-and-stories. Cependant, ce n'est **pas une violation** dans ce contexte : ce sont des epics de remédiation post-audit explicitement scopées ainsi (`AUDIT_CONFORMITE_BMAD.md`), avec une justification métier claire (garantir NFR10 isolation tenant, NFR12 revocation session, FR32/37/48 intégrité concurrente) — la valeur utilisateur est indirecte mais réelle (sécurité et fiabilité de la plateforme). Ce document assume et documente explicitement cette nature ("Non-cumul : ces épics s'ajoutent aux Épics 1–11").

### C. Story Quality — Epics 12-16

**Points forts :**
- Chaque story a un format AC rigoureux Given/When/Then, avec fichiers et lignes de code précis référencés (ex: Story 12.1 → `.github/workflows/deploy-backend.yml:112`).
- Traçabilité complète : chaque story référence son finding d'audit (S1-S12, B-CRIT/HIGH, F-CRIT/HIGH, X-CRIT/HIGH), ses FR/NFR/ARCH associés, et les tests TDD nommés à l'avance.
- Stratégie "Refonte absorption" documentée par story (cherry-pick vs ré-implémentation native) — bonne anticipation des deux pistes de dev en parallèle (`deploy` vs `refractoring`).
- Note "Contrepartie mobile (Flutter)" quand pertinent — bonne discipline de parité backend/frontend.

**Dépendances (Dependency Analysis) :**
- Story 14.12 (forgot-password) déclare explicitement `Depends on: Story 12.2` (revokeAllSessions) — dépendance en arrière (12→14), donc pas une dépendance avant, conforme aux règles (pas de forward dependency).
- Story 13.4 et 16.6 se référencent mutuellement ("ties 13.4") — couplage documenté et intentionnel (même sous-système sync_queue), pas une violation.
- Aucune dépendance circulaire ou "forward" détectée dans les 44 stories des Epics 12-16.

**Checklist de conformité (Epics 12-16) :**
- [x] Traçabilité FR/NFR/ARCH maintenue
- [x] Critères d'acceptation clairs et testables
- [x] Pas de dépendances en avant
- [x] Stories de taille appropriée (bien scopées, une story = un problème)
- [~] Valeur utilisateur : indirecte mais justifiée (nature remédiation)

### 🟡 Minor Concerns

- Numérotation non séquentielle : Story 14.9 puis 14.12 puis 14.11 puis 14.10 dans le document (ordre d'apparition ≠ ordre numérique) — purement cosmétique, ne bloque pas l'exécution mais nuit à la lisibilité.
- Le fichier `epics-remediation-audit.md` vit en dehors de la structure shardée standard `epics/index.md` (pas listé dans la table des matières `index.md`) — actif et utilisé, mais un peu déconnecté du reste de la structure documentaire.

### Aucune violation critique ou majeure détectée.

## Vérification Code ↔ Documentation (stories de stabilisation v1s-*)

> Cette section va au-delà du gabarit standard du workflow (qui s'arrête à la cohérence PRD/Architecture/UX/Epics) pour répondre directement à la demande : vérifier que le code correspond à ce que documentent les stories `v1s-12-*` à `v1s-15-*` livrées dans les 5 derniers commits (`ccfad6b`, `612539b`, `50cecea`, `7fe8a0a`, `5e21e82`). Vérification par lecture directe du code source, pas seulement des rapports de code-review.

### ✅ Confirmé conforme

| Story | Claim documenté | Vérification code |
|---|---|---|
| v1s-15-5 | `UserSyncStateDdlInitializer` supprimé | ✅ Fichier absent du repo |
| v1s-15-5 | `com/keevo/common` et `lib/features/products` (scaffolding vide) supprimés | ✅ Dossiers absents |
| v1s-14-12 | Forgot-password OTP WhatsApp — endpoints, migration, UI | ✅ `V6__password_reset_tokens.sql`, `ForgotPasswordRequestDto`, `forgot_password_page.dart`, tests présents |
| v1s-14-11 | Backend (3 endpoints, events, audit) 100% | ✅ Tous les fichiers listés (`UpdateEmployeeService`, `ChangeEmployeeRoleService`, `SetEmployeePasswordService`, etc.) existent |

### 🟠 Écart "done" vs complétude réelle — Story v1s-14-11

Le commit `50cecea` et `sprint-status.yaml` marquent **v1s-14-11 "done"**. Or l'AC6 de la story (`epics-remediation-audit.md` L466-483 et le fichier story lui-même) exige un **formulaire Flutter d'édition employé** — celui-ci n'existe pas :
```
find lib -iname "*employee_edit*" -o -iname "*edit_employee*"  → aucun résultat
```
Le fichier story `v1s-14-11-employee-full-edit.md` **documente lui-même** ce gap ("Flutter (model/datasource/repository/providers done, UI pages pending)", "Defer F5 — UI pages AC6 pending"). La documentation est donc honnête et cohérente avec le code — **mais** le statut "done" masque le fait que la fonctionnalité n'est **pas utilisable par le Owner en pratique** (aucun bouton "Modifier les informations" n'existe dans l'app). Le backend est prêt ; il manque `edit_employee_page.dart` + l'entrée de menu dans `EmployeeCard`.

**Recommandation :** soit rouvrir v1s-14-11 à `review` jusqu'à l'UI livrée, soit créer explicitement une story de suivi pour l'AC6 avant de considérer FR66 (édition complète) comme livré au niveau produit.

### 🔴 CRITIQUE — Faille RBAC confirmée toujours active : `/api/v1/sync/push` contourne le hardening de Story 12.6

**Documentation :** `deferred-work.md` (ligne 157) et `sprint-status.yaml` (v1s-12-6) documentent **déjà** ce gap comme "CRITIQUE", "priorité release-blocker", avec la recommandation explicite : *"story de suivi immédiate"*.

**Vérification code — toujours présent, non corrigé :**
- `CancelSaleSyncHandler.java`, `ValidateSaleSyncHandler.java`, `StockAdjustSyncHandler.java`, `TransferSyncHandler.java` (`sync/sync/application/handler/`) appellent directement les use cases métier (`cancelPendingSaleUseCase`, `validateSaleUseCase`, `adjustStockUseCase`, `transferStockUseCase`) avec seulement `actorId`/`tenantId` — **aucun contrôle de rôle, aucun scope `storeId`**.
- Les contrôleurs REST correspondants (`PendingSaleController`, `StockTransferController`, etc.) ont bien été durcis en `hasRole('OWNER')` par Story 12.6 — mais ces 4 handlers de sync **ne passent pas par ces contrôleurs**.
- **Conséquence pratique confirmée :** un EMPLOYEE peut, via le canal offline (`/api/v1/sync/push`), annuler/valider des ventes de n'importe quelle boutique et initier des transferts de stock hors de sa boutique assignée — exactement ce que Story 12.6 (AC2/AC5/AC7, FR36) visait à empêcher au niveau REST. Le contournement fonctionne car la synchronisation offline ne vérifie que l'authentification (tenant), pas le rôle.

**Statut :** ce n'est **pas** un défaut de documentation — le gap est honnêtement et précisément documenté depuis la review de v1s-12-6. C'est un **gap d'implémentation non résolu** malgré sa priorité déclarée "release-blocker". Aucune story de suivi n'existe encore dans le backlog (`epics-remediation-audit.md` / `sprint-status.yaml`) pour le fermer.

**Recommandation :** créer une story de suivi immédiate (ex. `v1s-12-8`) qui fait porter le rôle/scope de l'acteur dans `SyncOperation` (ou consulte la membership tenant) et applique les mêmes gardes que les controllers REST dans ces 4 handlers, avant tout déploiement en production avec des comptes EMPLOYEE actifs.

### 🟡 Documentation d'architecture partiellement obsolète — "Complete Module Audit"

`architecture.md` contient une table "COMPLETE MODULE AUDIT — Current State vs Target State" qui liste des modules non-conformes au pattern Backend-First-When-Online. Vérification ponctuelle :
- **Toujours vrai :** `ProductRepositoryImpl.archive()`/`unarchive()` restent local-first (commentaire code : *"Offline-first (Story 5.6)"*) — la table architecture est correcte sur ce point, gap non résolu.
- **Devenu faux (obsolète) :** la table affirme *"Employees | EmployeeRepositoryImpl | Remote-only (no local DS) — Fails completely when offline"* — or le code actuel possède bien un `LocalEmployeeDataSource` injecté et utilisé (`_local.upsert(...)`, `_local.upsertAll(...)`). Ce module a été corrigé depuis la rédaction de cette table, mais l'architecture n'a pas été mise à jour en conséquence.

**Recommandation :** rafraîchir la table d'audit d'`architecture.md` avant de l'utiliser comme check-list de refonte — certaines entrées ont été résolues sans mise à jour du document source.

## Summary and Recommendations

### Overall Readiness Status

**NEEDS WORK** — pas bloquant pour un usage continu de la V1, mais une action release-blocker est requise avant d'exposer des comptes EMPLOYEE en production sans supervision stricte.

### Critical Issues Requiring Immediate Action

1. 🔴 **RBAC bypass via `/api/v1/sync/push`** (4 handlers sans contrôle de rôle) — annule une partie du hardening de sécurité de Story 12.6. Documenté depuis le 2026-07-21, toujours non corrigé. **Priorité release-blocker confirmée.**

### Issues à traiter à court terme (non-bloquantes)

2. 🟠 Story v1s-14-11 marquée "done" alors que l'UI Flutter d'édition employé (AC6) est absente — FR66 (édition complète) non livrable au produit tant que l'UI manque.
3. 🟡 PRD mentionne encore "PowerSync" comme moteur de sync (lignes 88/207/238) alors que l'architecture a définitivement tranché pour un moteur REST delta-based custom (décision Toor 2026-03-04) — mettre à jour le PRD.
4. 🟡 `architecture.md` — table "Complete Module Audit" partiellement obsolète (au moins l'entrée Employees) ; à rafraîchir avant de s'y fier pour prioriser la refonte.
5. 🟡 PRD — section "Tenant Model" décrit encore un couplage 1:1 user↔tenant simple, alors que l'architecture a évolué vers un modèle N:N (`user_tenant_memberships`, two-step login) depuis Story 1.7 (2026-03-07).

### Points forts confirmés

- Couverture FR/NFR 100% (94/94) entre PRD et epics, aucune lacune.
- Traçabilité exceptionnelle des stories de stabilisation (chaque story référence son finding d'audit, ses tests TDD, son statut de code-review, ses décisions et ses defers) — la discipline documentaire de l'équipe est largement au-dessus de la moyenne, ce qui a permis de retrouver et vérifier la faille RBAC ci-dessus en quelques minutes plutôt que de la découvrir en production.
- Les corrections vérifiées ponctuellement (v1s-15-5 dead code, v1s-14-12 forgot-password) correspondent exactement à ce que les docs annoncent.

### Recommended Next Steps

1. Créer et prioriser immédiatement une story de fermeture du gap RBAC sync (`/api/v1/sync/push`) — c'est le seul point réellement bloquant pour la production.
2. Décider explicitement : rouvrir v1s-14-11 (UI manquante) ou documenter formellement que FR66-edit est "backend-only" en V1.
3. Passe de toilettage documentaire PRD (PowerSync → custom REST, tenant model N:N) — non urgent, cosmétique, mais évite la confusion pour un futur contributeur lisant uniquement le PRD.
4. Rafraîchir la table "Complete Module Audit" d'`architecture.md` avant de l'utiliser pour prioriser le travail de refonte modulaire.

### Final Note

Cette évaluation a couvert la cohérence PRD ↔ Architecture ↔ UX ↔ Epics (100% de couverture FR/NFR, une poignée d'incohérences documentaires mineures) et une vérification ciblée code ↔ docs sur les stories de stabilisation les plus récentes. Le point le plus important n'est pas un problème de documentation — la documentation est franche et précise sur ses propres lacunes — mais un **gap d'implémentation de sécurité déjà identifié et non encore fermé**. Traiter le point 1 avant toute mise en production élargie à des comptes EMPLOYEE.

Rapport complet : `_bmad-output/planning-artifacts/implementation-readiness-report-2026-07-23.md`
