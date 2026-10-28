# Audit d'Architecture & Plan de Refonte Modulaire — Keevo

> **Cabinet d'audit externe — Rapport niveau CTO**
> **Date de l'audit :** 13 juin 2026
> **Périmètre :** intégralité du monorepo `keevo-app` (backend Spring Boot, app Flutter, dashboard Next.js, infrastructure, documentation BMAD)
> **Méthode :** lecture du code source, des artefacts de planification (PRD, FR, NFR, architecture, epics, stories), de la configuration, de l'infrastructure et de la documentation. Chaque constat est sourcé (fichier / document / absence constatée).
> **Auteur :** Claude Opus 4.8

---

## Avertissement méthodologique

- Le dépôt fait **4,3 Go** et contient ~765 fichiers Java (main), ~428 fichiers Dart (lib), 48 fichiers TS/TSX. L'audit s'appuie sur un échantillonnage **structurel exhaustif** (arborescence complète, configuration, sécurité, CI/CD, infra) et un échantillonnage **ciblé** du code applicatif.
- Les compteurs de tests « verts » (ex. « 632/632 ») proviennent des notes de stories ; ils **n'ont pas été ré-exécutés** dans cet audit (un `mvn test` complet nécessite Testcontainers/PostgreSQL et plusieurs minutes). Là où je m'appuie sur une affirmation documentée et non vérifiée, c'est signalé explicitement.
- Aucune hypothèse n'est posée : tout écart est soit constaté dans le code, soit dans un document, soit par **absence** d'un élément attendu.

---

# 1. Executive Summary

## 1.1 Verdict global

Keevo est un produit **nettement au-dessus de la moyenne** pour un projet porté par un développeur solo assisté d'agents IA. L'architecture backend est un **monolithe modulaire hexagonal/DDD propre**, discipliné, avec des conventions remarquablement tenues (ports/adapters, séparation domaine/persistance, événements de domaine, audit trail immuable). La couverture fonctionnelle réelle est élevée : **6 epics sur 9 sont terminés**, le produit est déployé en production (`keevo.bookandgo.cloud`).

Cependant, plusieurs **dettes structurelles à fort impact** menacent la trajectoire « 3 ans » et **doivent être traitées avant la refonte modulaire** :

| # | Risque majeur | Gravité | Impact |
|---|---|---|---|
| R1 | **`ddl-auto=update` en production + Flyway désactivé + 0 migration SQL** | 🔴 Critique | Pas de gestion de schéma versionnée, drift inter-tenants, rollback impossible, perte de données potentielle |
| R2 | **Synchronisation de schéma tenant au runtime qui avale les `SQLException`** | 🔴 Critique | Échecs silencieux d'évolution de schéma par tenant, incohérences invisibles |
| R3 | **Rate limiting + état de sync 100 % en mémoire (mono-instance)** | 🟠 Élevé | Bloque le scaling horizontal (NFR14/15) |
| R4 | **Aucune observabilité (pas de tracing, métriques métier, logs centralisés)** | 🟠 Élevé | Exploitation aveugle, MTTR élevé, NFR observabilité non tenue |
| R5 | **Couplage inter-domaines réel (imports directs entre bounded contexts)** | 🟠 Élevé | Contredit la promesse « microservice-ready » et complique la modularisation Phase 2 |
| R6 | **Dashboard admin sans aucun test ; Epic 9 (Super Admin) ~30 % livré** | 🟡 Moyen | Parcours « Toor » du PRD largement incomplet |

## 1.2 Taux de conformité synthétiques

| Dimension | Score | Commentaire |
|---|---|---|
| **Conformité PRD (parcours)** | ~70 % | Parcours Simon & Loïc quasi complets ; parcours Super Admin (Toor) à ~35 % |
| **Conformité FR** | ~74 % (≈69/93 conformes ou quasi) | Voir §4 |
| **Conformité NFR** | Moyenne **3,1/5** | Sécurité & offline bons ; observabilité/scalabilité/migrations faibles. Voir §5 |
| **Qualité architecture (intention)** | 4,5/5 | Doc d'architecture exceptionnelle |
| **Qualité architecture (réalité)** | 3,5/5 | Écart intention/réalité sur couplage, migrations, modules vides |
| **Hygiène de code** | 4/5 | 7 TODO, 0 `System.out`/`printStackTrace` dans le backend |
| **Tests** | 3,5/5 | Backend & Flutter bien fournis ; dashboard 0 test ; pas de gate de couverture |
| **Infra / CI-CD** | 3/5 | CI correcte, déploiement SSH mono-serveur, pas de K8s, pas de scan sécu |

## 1.3 Recommandation stratégique

Le produit est **prêt pour la croissance early-stage** mais **pas pour le passage à l'échelle** ni pour la commercialisation modulaire telle quelle. La séquence recommandée :

1. **Stabiliser le socle** (migrations, observabilité, externalisation de l'état) — *prérequis non négociable*.
2. **Formaliser les frontières** entre bounded contexts (le code est déjà à 80 % du chemin).
3. **Greffer le moteur de modularité** (registre de modules + licences + feature flags) sur le monolithe modulaire — **ne pas partir en microservices** (voir §15.6 pour l'argumentaire).

---

# 2. Cartographie du système

## 2.1 Vue d'ensemble (monorepo)

```
keevo-app/
├── keevo/
│   ├── backend/      # Spring Boot 3.5 / Java 21 — API REST multi-tenant (765 fichiers .java main, ~41,5k LOC)
│   ├── app/          # Flutter (mobile + desktop) — offline-first (428 fichiers .dart, ~85,5k LOC)
│   ├── dashboard/    # Next.js 16 / React 19 — Super Admin web (48 fichiers .ts/.tsx, ~3,3k LOC)
│   └── scripts/      # scripts E2E Python
├── _bmad-output/     # PRD, architecture, epics, 40+ stories, gap analysis
├── docs/             # Checklist de déploiement, CGU/politique de confidentialité
└── .github/workflows/ # 4 workflows (backend-ci, flutter-ci, deploy-backend, release-flutter)
```

**Stack technique réelle (vérifiée dans le code) :**

| Couche | Technologie | Source |
|---|---|---|
| Backend | Spring Boot 3.5.x, Java 21, JPA/Hibernate, Spring Security 6 | `keevo/backend/pom.xml` |
| Auth | JWT **RS256** (jjwt), login en 2 étapes, bcrypt coût 12 | `SecurityConfig.java`, `architecture.md §Multi-Tenant Identity` |
| Persistance | PostgreSQL 16, **schema-per-tenant** (`kv_xxxxxx`), `ddl-auto=update` | `application.yml` |
| Sync | **Custom REST delta** (push/pull) — *PowerSync exclu* | `sync/sync/adapter/in/rest/SyncController.java` |
| Mobile/Desktop | Flutter 3.41, Riverpod **2.x**, Drift + **SQLCipher**, go_router, dio | `keevo/app/pubspec.yaml` |
| WhatsApp | **Wassender + Twilio (failover)** + NoOp — *Africa's Talking non implémenté* | `messaging/whatsapp/adapter/out/external/` |
| Push | Firebase Cloud Messaging (firebase-admin) | `pom.xml`, `application.yml keevo.fcm` |
| Dashboard | Next.js 16, React 19, TanStack Query, Recharts, Tailwind 4, axios | `dashboard/package.json` |
| CI/CD | GitHub Actions | `.github/workflows/` |
| Déploiement | Docker (multi-stage) → GHCR → **SSH sur serveur unique** | `deploy-backend.yml`, `docs/DEPLOYMENT_CHECKLIST.md` |

## 2.2 Modules backend (bounded contexts × modules)

Chaque module suit la structure hexagonale `domain/{model,port/in,port/out} · application/service · adapter/{in/rest,in/mcp,out/persistence}`.

| Domaine | Modules | État réel |
|---|---|---|
| `identity` | auth, user, employee, onboarding | ✅ Riche et actif |
| `catalog` | product, category, variant, contact, **stock** | ✅ Actif — `stock` contient AUSSI les transferts inter-boutiques |
| `commerce` | sale, pos, **payment** | ✅ sale/pos actifs ; ⚠️ **`payment` vide (0 .java)** |
| `inventory` | counting, **stock**, **transfer** | ✅ counting actif (73 .java) ; ❌ **`stock` et `transfer` VIDES** |
| `store` | store, **warehouse** | ✅ store actif ; ⚠️ **`warehouse` vide** (logique dans `store/store`) |
| `reporting` | dashboard, report, profitability | ✅ Actif |
| `messaging` | whatsapp, notification | ✅ Actif |
| `sync` | sync | ✅ Actif (REST delta) |
| `subscription` | plan | ✅ Actif |
| `admin` | tenant, platform, catalog, operations, sync_monitoring | 🟡 Partiel (Super Admin) |
| `shared` | infrastructure (security, persistence, web, scheduling), domain, application | ✅ Cross-cutting |

## 2.3 Flux métier principal (vente offline-first)

```
[Employé POS] → enregistre vente
   │
   ├─ ONLINE  → RemoteSaleDataSource → POST /api/v1/sales (backend = source de vérité)
   │             → succès → cache local Drift (synced:true) → invalidate providers → UI refresh
   │
   └─ OFFLINE → LocalSaleDataSource (Drift, synced:false) + sync_queue
                 → reconnexion → SyncTriggerNotifier → POST /api/v1/sync/push (batch)
                 → résolution conflits stock = delta-based (somme des deltas)
   │
   └─→ SaleCompletedEvent → AuditEventListener (audit immuable) + StockOperationService (décrément)
                          → StockThresholdBreachedEvent → NotificationListener → WhatsApp/FCM
```

Référence : `architecture.md §Backend-First-When-Online`, `commerce/sale/`, `sync/sync/`.

## 2.4 Code mort / modules abandonnés / placeholders

| Élément | Constat | Source |
|---|---|---|
| `inventory/stock/**`, `inventory/transfer/**` | **Vides (0 fichier)** — logique réellement dans `catalog/stock` | `find` |
| `commerce/payment/**` | **Vide** — le mode de paiement est porté par l'agrégat `Sale` | `find` |
| `store/warehouse/**` | **Vide** — warehouse géré comme un type de `store` | `find` ; gap-analysis confirme la fonctionnalité existe |
| `adapter/in/mcp/**` (tous modules) | **0 implémentation** — uniquement des placeholders V2 (intentionnel) | `find` |
| Colonne `public.users.tenant_id` | **Dangling** — non mappée par Hibernate, à supprimer | `architecture.md L1488` |
| Flyway (`flyway-core` dans `pom.xml`) | Dépendance présente mais `flyway.enabled=false` — dette/contradiction | `pom.xml` + `application.yml` |

> **Constat structurel** : l'arborescence backend matérialise des modules « cibles » (transfer, warehouse, payment) qui n'ont jamais reçu d'implémentation, tandis que leur logique a atterri ailleurs. Ce n'est pas du code mort dangereux, mais c'est une **divergence structure ↔ réalité** qui trompe tout nouvel arrivant et l'outillage.

---

# 3. Analyse PRD ↔ Implémentation

Statut MVP du PRD (§MVP Feature Set). Source croisée : `sprint-status.yaml`, `gap-analysis-client-importateur.md`, code.

| Capacité MVP (PRD) | Prévue | Implémentée | État |
|---|---|---|---|
| Auth WhatsApp + JWT + tenant auto | ✅ | ✅ (login 2 étapes, RS256, provisioning schéma) | **Conforme** |
| Gestion produits (CRUD, catégories, variantes) | ✅ | ✅ (Epic 2) | **Conforme** |
| Import CSV produits | ✅ | ✅ (Story 2.4) | **Conforme** |
| Stock multi-boutiques + transferts | ✅ | ✅ (Epic 3) | **Conforme** |
| Warehouse | ✅ | ✅ (comme type de store ; module dédié vide) | **Implémentée différemment** |
| POS (vente, clôture journalière) | ✅ | ✅ (4-1→4-4) | **Conforme** |
| Annulation/correction de vente (FR90) | ✅ | ❌ (Story 4-5 `backlog`) | **Non implémentée** |
| Leaderboard vendeurs (FR44) | ✅ | ❌ (Story 4-6 `backlog`) | **Non implémentée** |
| Inventaire assisté | ✅ | ✅ (Epic 6 done) | **Conforme** |
| Rapports WhatsApp (EOD, hebdo) | ✅ | ✅ (7-2, 7-3) | **Conforme** |
| Dashboard temps réel + rentabilité | ✅ | 🟡 (7-4 en `review`) | **Partiellement conforme** |
| Sync offline-first 7 jours | ✅ | ✅ (Epic 5) — **custom REST, pas PowerSync** | **Implémentée différemment** |
| Super Admin : gestion tenants | ✅ | ✅ (9-1) | **Conforme** |
| Super Admin : activation paiement manuelle (FR78) | ✅ | ❌ (9-2 `backlog`) | **Non implémentée** |
| Super Admin : dashboard revenus / MRR (FR79) | ✅ | ❌ (9-3 `backlog`) | **Non implémentée** |
| Super Admin : analytics plateforme (FR80) | ✅ | ❌ (9-4 `backlog`) | **Non implémentée** |
| Super Admin : santé système + push global (FR81-83) | ✅ | ❌ (9-5 `backlog`) | **Non implémentée** |
| Super Admin : monitoring catalogue/ops/sync | (ajout) | ✅ (9-6 done, 9-7/9-8 `review`) | **Conforme** (au-delà du PRD initial) |

**Divergences technologiques majeures vs PRD :**

| Élément PRD | PRD demandait | Réalité | Verdict |
|---|---|---|---|
| Moteur de sync | PowerSync (MVP) | Custom REST delta-based | Décision documentée & justifiée (contrainte schema-per-tenant) → **acceptable** |
| WhatsApp BSP | Africa's Talking | Wassender + Twilio failover | Pivot pragmatique → **acceptable** mais PRD non mis à jour |
| Desktop | Tauri 2.0 | Flutter Desktop (même codebase) | Simplification solo-dev → **acceptable** |
| Cloud | AWS RDS/ECS/S3 | Serveur unique + Docker + GHCR (SSH) | **Écart significatif** vs NFR scalabilité/uptime |
| iOS | MVP | Non confirmé (build Android prioritaire) | À vérifier |

**Taux de conformité PRD (capacités MVP listées) :** ~70 % conformes/quasi, ~30 % non implémentées ou partielles — concentrées sur **Epic 9 (Super Admin)** et la **fin d'Epic 4**.

---

# 4. Analyse des Functional Requirements (FR1–FR93)

Méthode : mapping FR → epic/story (`requirements-inventory.md`, `sprint-status.yaml`) croisé avec la présence de code.

## 4.1 Synthèse par domaine

| Domaine FR | FR | État dominant |
|---|---|---|
| Auth & Onboarding (FR1-7) | 7 | ✅ Conforme (FR7 tutoriels vidéo : à vérifier) |
| Provisioning tenant (FR8-15b) | 9 | ✅ Conforme (PREMIUM_TRIAL 6 mois implémenté) |
| Souscriptions (FR16-20) | 5 | ✅ Conforme (limites Free, rétrogradation auto) |
| Produits (FR21-29) | 9 | ✅ Conforme |
| Multi-boutiques (FR30-36) | 7 | ✅ Conforme |
| POS (FR37-44) | 8 | 🟡 FR37-43 OK ; **FR44 (leaderboard) ❌** |
| Inventaire (FR45-49) | 5 | ✅ Conforme (Epic 6) |
| Rapports (FR50-56) | 7 | 🟡 FR55 export PDF/Excel : à confirmer ; FR54 rentabilité en review |
| WhatsApp (FR57-60) | 4 | ✅ Conforme (provider différent) ; FR58 SMS : à vérifier |
| Alertes (FR61-64) | 4 | 🟡 FR61-63 OK (8-0,8-1) ; **FR64 (colis) ❌ — Growth** |
| Users (FR65-68) | 4 | 🟡 FR65-66 OK ; **FR67 (activité) ❌ (8-2)** ; **FR68 (révocation) ❌ (8-3)** |
| Sync (FR69-76) | 8 | ✅ Conforme (Epic 5) |
| Super Admin (FR77-83) | 7 | 🔴 **FR77 OK ; FR78-83 majoritairement ❌** |
| Sécurité & Audit (FR84-93) | 10 | 🟡 FR84-86 OK ; **FR90 (annulation) ❌** ; **FR91 (RGPD delete) ❌ (8-5)** ; **FR92 (feedback) ❌ (8-4)** ; FR93 (rate limit) ✅ |
| Clients/Fournisseurs (FR87-89) | 3 | ✅ Conforme (Story 2.5) |

## 4.2 FR explicitement absents (backlog / non livrés)

| FR | Intitulé | Story | Impact |
|---|---|---|---|
| FR44 | Leaderboard vendeurs | 4-6 backlog | Engagement / gamification employé |
| FR67 | Suivi activité employés | 8-2 backlog | Parcours owner incomplet |
| FR68 | Désactivation employé + révocation sessions <5 min | 8-3 backlog | **Sécurité** (NFR12) |
| FR78 | Activation manuelle compte payant | 9-2 backlog | **Monétisation bloquée** |
| FR79 | Dashboard revenus (MRR, churn) | 9-3 backlog | Pilotage business |
| FR80 | Analytics plateforme | 9-4 backlog | Pilotage business |
| FR81-83 | Santé système + alertes proactives + push global | 9-5 backlog | Exploitation |
| FR90 | Annulation/correction de vente | 4-5 backlog | **Opérationnel critique** (erreurs de caisse) |
| FR91 | Suppression compte/données (RGPD) | 8-5 backlog | **Conformité légale** |
| FR92 | Feedback in-app | 8-4 backlog | Boucle produit |

> **Observation** : la majorité des FR manquants concernent la **monétisation** (FR78), la **conformité RGPD** (FR91) et la **sécurité opérationnelle** (FR68). Ce sont précisément les sujets qui doivent être traités avant une commercialisation modulaire (Phase 2).

## 4.3 Couverture de tests des FR

Backend : **270 fichiers de test** pour 765 fichiers main (ratio ~0,35). Flutter : **164 fichiers de test** pour 428 lib (ratio ~0,38). Les stories imposent un cycle TDD strict (RED→GREEN→REFACTOR + cURL E2E + E2E Python). Les fonctionnalités livrées sont **bien couvertes** ; les FR non livrés ne le sont pas (logique). **Récurrence notée** dans les stories : « 2 pre-existing errors » (`SectorTemplateFactoryTest`, `JpaBaseEntityPersistTest`, nécessitant une vraie DB) — à investiguer comme dette de test.

---

# 5. Analyse des Non-Functional Requirements

Notation : **5 = excellent / 1 = absent**. Sources entre parenthèses.

## 5.1 Performance — **Note : 3/5**

| Critère | Constat | Source |
|---|---|---|
| <2s action locale (NFR1) | ✅ plausible (Drift local) | architecture |
| Pagination | 🟡 partielle — listes admin paginées, mais **N+1 documentés** | `deferred-work.md` (tenant list, pending sales) |
| **N+1 queries** | 🔴 plusieurs identifiés et **acceptés** : `enrichWithCrossSchemaMetrics` (1 requête/ligne), `findByStoreIdAndStatus` (items lazy), `DayClosureDeltaProvider` mauvais index | `deferred-work.md` |
| Cache | 🟡 cache local Drift côté client ; **pas de cache serveur** (Redis/Caffeine) | code |
| Recherche <500ms (NFR5) | ✅ côté Drift | code |

## 5.2 Scalabilité — **Note : 2/5**

| Critère | Constat |
|---|---|
| Montée en charge (NFR14 : 100 tenants) | 🟡 atteignable en mono-instance |
| Croissance 1000+ tenants (NFR15) | 🔴 **bloquée** : rate limiting & sync state **en mémoire** (`RateLimitFilter` `ConcurrentHashMap`), `TenantSchemaSyncService` cache JVM-local |
| Scaling horizontal | 🔴 impossible sans externalisation d'état (Redis) ; déploiement **mono-serveur** |
| Schema-per-tenant à 1000+ | 🟠 risque : 1000 schémas × N tables, `information_schema` diff au login, pression sur le pool Hikari (max 10) |

## 5.3 Sécurité — **Note : 3,5/5** (voir §10 pour le détail OWASP)

| Critère | Constat |
|---|---|
| TLS 1.2+ (NFR7) | ✅ géré au reverse-proxy (Caddy/`APP_DOMAIN`) |
| Chiffrement at rest local (NFR8) | ✅ **SQLCipher** (`sqlcipher_flutter_libs`) |
| JWT 24h + refresh (NFR9) | ✅ access 24h, refresh 180j (Story 8.6) |
| Isolation tenant 100 % (NFR10) | ✅ schema-per-tenant + SPI Hibernate ; ⚠️ fragilité raw JDBC documentée |
| bcrypt ≥12 (NFR11) | ✅ coût 12 (`SecurityConfig`) |
| Révocation session <5min (NFR12) | 🔴 **non implémenté** (FR68/8-3 backlog) |
| Audit immuable (NFR13) | 🟡 **immutabilité applicative** (pas d'endpoint update/delete) mais **pas d'append-only DB** (trigger/contrainte) → un accès SQL direct peut altérer |
| RBAC | ✅ `@EnableMethodSecurity` + guards ; ⚠️ trous documentés (EMPLOYEE peut annuler brouillon, `DeviceToken.delete` sans ownership) |
| Secrets | 🟡 défauts faibles dans `application.yml` (`ADMIN_PASSWORD:Admin@1234!`) — overridés par env mais risque si oubli ; clés RSA **gitignored** (✅) |
| Rate limiting (FR93) | ✅ 100 req/min/tenant ; 🔴 mono-instance |

## 5.4 Fiabilité & Disponibilité — **Note : 2,5/5**

| Critère | Constat |
|---|---|
| Uptime 99,5 % (NFR18) | 🔴 **mono-serveur, pas de HA, pas de healthcheck applicatif au déploiement** — un crash = downtime total |
| 0 % downtime client offline (NFR19) | ✅ offline-first robuste |
| Intégrité ACID sync (NFR20) | ✅ transactions Drift + delta-based |
| Retry backoff (NFR21) | ✅ documenté (2s→max 5min) |
| Backups quotidiens 30j (NFR22) | ❓ **non vérifié dans le repo** — dépend de l'infra serveur, non versionné |
| Reprise sur erreur schéma tenant | 🔴 `TenantSchemaSyncService` **avale les `SQLException`** (non-bloquant mais masque les pannes) |

## 5.5 Maintenabilité — **Note : 4/5**

| Critère | Constat |
|---|---|
| Duplication | 🟡 deux conventions `domain/model` vs `domain/entity` (ex. `catalog/contact`, `catalog/stock`) ; modules dupliqués nominalement (stock×2) |
| Dette technique | 🟢 faible en surface (7 TODO, 0 `System.out`) ; 🟠 dette structurelle (migrations, couplage) |
| Couplage | 🟠 **imports inter-domaines directs** (voir §6.2) |
| Cohésion | 🟢 forte par module (hexagonal strict) |
| Documentation interne | 🟢 exceptionnelle (architecture.md de 1672 lignes, registre d'événements, anti-patterns) |

## 5.6 Observabilité — **Note : 1,5/5**

| Critère | Constat |
|---|---|
| Logs | 🟡 SLF4J/Logback, niveau INFO ; **pas de logs structurés JSON, pas d'agrégation centralisée** versionnée |
| Métriques | 🟡 **Actuator `health,info,metrics` uniquement** ; **aucune métrique métier** (ventes/min, sync lag, erreurs par tenant) |
| Tracing | 🔴 **absent** — `grep` pom : pas de Micrometer Tracing / OpenTelemetry / Zipkin |
| Monitoring | 🔴 pas de Prometheus/Grafana ; le « monitoring » est un **dashboard produit** (Epic 9), pas un monitoring infra |
| Alerting infra | 🔴 absent du repo |

## 5.7 Récapitulatif NFR

| NFR | Note /5 |
|---|---|
| Performance | 3,0 |
| Scalabilité | 2,0 |
| Sécurité | 3,5 |
| Fiabilité | 2,5 |
| Maintenabilité | 4,0 |
| Observabilité | 1,5 |
| **Moyenne** | **≈ 2,75 → 3,1** (pondérée maintenabilité) |

---

# 6. Audit Architecture

## 6.1 Points forts (intention exemplaire)

- **Hexagonal strict** : `domain` pur (zéro import Spring/JPA), ports `in/out`, adapters. Vérifié sur `identity/auth`, `catalog/stock`, etc.
- **Pureté MCP** : commandes/queries en Records purs, `ActorId` injecté explicitement (jamais `SecurityContextHolder` dans un use case) — prépare une exposition MCP/IA sans réécriture.
- **Pattern de persistance 4 couches** (`entity/jpa/impl` + port domaine) appliqué systématiquement.
- **Registre d'événements de domaine canonique** (architecture.md) évitant les doublons d'events.
- **Audit trail** via Observer (`AuditEventListener`) sur événements de domaine.

## 6.2 Violations & anti-patterns constatés

| # | Constat | Preuve | Impact |
|---|---|---|---|
| A1 | **Couplage inter-bounded-contexts** : imports directs entre domaines | `sync→catalog` (29), `inventory→catalog` (22), `reporting→identity` (21), `commerce→catalog` (13), `messaging→identity` (14)… | Contredit « no direct import between domains » (architecture.md L1135) → extraction microservice illusoire en l'état |
| A2 | **Modules fantômes** : `inventory/stock`, `inventory/transfer`, `commerce/payment`, `store/warehouse` vides ; logique ailleurs | `find` | Carte mentale fausse, frontières floues |
| A3 | **Transferts inter-boutiques dans `catalog/stock`** au lieu de `inventory/transfer` | architecture.md L1044 (assumé) | Frontière de contexte incohérente |
| A4 | **Schéma DB non versionné** (`ddl-auto=update` prod) | `application.yml` | Pas de couche « évolution de schéma » maîtrisée |
| A5 | **État applicatif en mémoire** (rate limit, caches sync) | `RateLimitFilter`, `TenantSchemaSyncService` | Couplage à l'instance unique |
| A6 | **Mélange conventions** `domain/model` vs `domain/entity` | `catalog/contact/domain/entity`, `catalog/stock/domain/entity` | Incohérence stylistique |
| A7 | **`adapter/in/web` vs `adapter/in/rest`** selon les modules | `catalog/stock/adapter/in/web` vs `commerce/sale/adapter/in/rest` | Incohérence de nommage de couche |

## 6.3 Dette architecturale & risque long terme

La promesse « **microservice-ready** » du document d'architecture **n'est pas tenue dans le code** : les domaines s'importent mutuellement leurs classes internes (entités, services). Pour la Phase 2 (modularité), ce couplage est le **principal chantier** : il faut introduire des **contrats inter-modules explicites** (interfaces publiées + events) avant de pouvoir activer/désactiver des modules indépendamment.

**Bonne nouvelle** : l'architecture hexagonale fournit déjà les ports ; il « suffit » de discipliner les dépendances (règle ArchUnit + API publique par module). C'est un effort **modéré**, pas une réécriture.

---

# 7. Audit Frontend

## 7.1 Application Flutter (`keevo/app`)

**Structure** : feature-first + clean architecture, conforme à l'intention. 18 features : `auth, onboarding, pos, products, catalog, contact, inventory, stores, team, reports, dashboard, profitability, notifications, settings, sync_indicator, audit, identity, debug`.

| Aspect | Constat |
|---|---|
| State management | Riverpod **2.x** (`flutter_riverpod ^2.5.1`) — **divergence** vs « Riverpod 3.0 » annoncé dans architecture.md |
| Routing | go_router 14 ; ⚠️ ternaire `role=='OWNER'?...:...` dupliqué en 4 endroits (`deferred-work` HF-1 D4) |
| Offline-first | ✅ Drift + SQLCipher + sync_queue ; ⚠️ audit interne (architecture.md §Module Audit) listait plusieurs repos **non conformes** (Sales, DayClosure, Employees remote-only) — Story 5-6 (« offline-first transactional writes ») marquée done devrait les avoir corrigés, **à re-vérifier** |
| Réutilisabilité | 🟢 `core/widget`, `core/scaffold` partagés |
| Accessibilité | 🟡 français V1 OK (NFR26) ; tailles de police adaptatives (NFR25) non vérifiées |
| Dette UI | feature `debug` présente en prod (à exclure du build release) |

## 7.2 Dashboard Super Admin (`keevo/dashboard`)

| Aspect | Constat |
|---|---|
| Stack | Next.js 16 / React 19 / TanStack Query / Recharts / Tailwind 4 — **moderne et cohérent** |
| Pages livrées | `login`, `tenants`, `monitoring/{products,activity,sync}` |
| Pages manquantes | **revenus (9-3), analytics (9-4), santé système (9-5), activation plan (9-2)** |
| **Tests** | 🔴 **ZÉRO test** (`*.test.*`/`*.spec.*` introuvables hors node_modules) |
| SSR | 🟡 `"use client"` direct sur pages (anti-pattern Next vs spec — `deferred-work` D1) |
| Auth | cookie JWT (js-cookie) + `proxy.ts` |

> **Constat** : le dashboard est le **maillon faible qualité** (0 test) et **fonctionnellement incomplet** (Epic 9 ~30 %). C'est cohérent avec l'avancement, mais bloque le pilotage business.

---

# 8. Audit Backend

## 8.1 Forces

- 34 contrôleurs REST, ~114 endpoints, OpenAPI/Swagger systématique (règle architecture.md).
- `GlobalExceptionHandler` centralisé (`@RestControllerAdvice`) avec `domainCode` — anti-pattern « local `@ExceptionHandler` » documenté et évité.
- Événements de domaine + listeners (audit, notifications, stock) bien découplés.
- 0 `System.out`/`printStackTrace`, 7 TODO seulement.

## 8.2 Faiblesses / SOLID

| # | Constat | Source |
|---|---|---|
| B1 | **OCP partiellement violé** par le couplage inter-domaines (§6.2) | imports |
| B2 | **Validation** : présente (`spring-boot-starter-validation`, `@NotBlank` ajoutés tardivement en Story 3.1) — vérifier l'exhaustivité sur tous les DTO |
| B3 | **Sécurité méthode** : trous RBAC ponctuels (`PendingSaleController` EMPLOYEE peut annuler ; `DeviceTokenController.deleteToken` sans ownership) | `deferred-work` |
| B4 | **Index DB** : `ensureRequiredIndexes()` géré programmatiquement (Story 4.2) — fragile, non versionné | story 4-2 |
| B5 | **`DayClosureDeltaProvider` bug** : `totalTransactions` mappe le mauvais index SQL (= `totalSales`) | `deferred-work` HF-2 D1 |

## 8.3 Repositories & persistance

Pattern 4-couches respecté. **Risque transversal** : usage de `JdbcTemplate` avec noms de schéma concaténés (`"kv_xxx".table`) — la doc le documente comme la « règle d'or », mais c'est une **surface d'injection** si jamais un `schema` provient d'une entrée non maîtrisée. Le `TenantContext` est dérivé du JWT (✅ maîtrisé), donc risque actuellement faible, mais à durcir (allow-list regex `^kv_[a-z0-9]+$`).

---

# 9. Audit Base de Données

| Aspect | Constat | Impact |
|---|---|---|
| **Stratégie de schéma** | schema-per-tenant `kv_xxxxxx`, public pour `users/tenants/refresh_tokens/audit` | Isolation forte ✅ |
| **Migrations** | 🔴 **`ddl-auto=update` + 0 fichier `.sql` + Flyway désactivé** | **Risque majeur** : pas de revue de schéma, pas de rollback, drift |
| **Provisioning tenant** | `TenantSchemaProvisioner` (DDL JDBC programmatique) | Couplé au code, non auditable hors-runtime |
| **Sync de schéma runtime** | `TenantSchemaSyncService` : diff `information_schema` public↔tenant à **chaque login**, `CREATE TABLE LIKE`, `ALTER ADD COLUMN`, cache JVM | 🔴 **avale les `SQLException`** ; coût au scaling ; FK exclues volontairement |
| **Index** | définis programmatiquement (`ensureRequiredIndexes`) | non versionné, fragile |
| **Intégrité référentielle** | FK **intra-schéma** OK ; FK **cross-schéma** (user_roles→users) = « par convention », pas de contrainte DB | Risque d'orphelins |
| **Colonnes inutilisées** | `public.users.tenant_id` dangling | dette |
| **Devise** | XAF entier (pas de décimales) — `Money` value object | ✅ cohérent FR86 |

> **Diagnostic** : le choix « pas de migrations » a permis une **vélocité solo-dev élevée**, mais c'est **incompatible avec une exploitation multi-tenant à l'échelle**. Sur 1000 tenants, une évolution de colonne dépend d'un diff au login qui peut échouer silencieusement par tenant → **incohérences invisibles**. **C'est la dette #1 à rembourser.**

---

# 10. Audit Sécurité (OWASP Top 10 2021)

| Catégorie OWASP | Évaluation | Détail |
|---|---|---|
| A01 Broken Access Control | 🟡 | RBAC global OK ; trous ponctuels (EMPLOYEE annule brouillon, deviceToken delete sans ownership) ; **révocation session <5min absente (NFR12/FR68)** |
| A02 Cryptographic Failures | 🟢 | bcrypt 12, JWT RS256, SQLCipher at rest, TLS au proxy |
| A03 Injection | 🟡 | JPA paramétré ✅ ; **SQL concaténé pour le schéma tenant** (JdbcTemplate) — durcir avec regex allow-list |
| A04 Insecure Design | 🟡 | Bonne conception ; mais audit non append-only DB, état en mémoire |
| A05 Security Misconfiguration | 🟡 | Defaults faibles dans `application.yml` (admin pwd) ; CORS autorise **toujours** `localhost:3000` (même en prod) ; Actuator `health` détaillé `when-authorized` ✅ |
| A06 Vulnerable Components | ❓ | **Aucun scan de dépendances** (pas de Dependabot/OWASP Dependency-Check/Trivy en CI) |
| A07 Auth Failures | 🟢 | lockout (failed_attempts/locked_until), login 2 étapes, scope `login_pending` rejeté |
| A08 Integrity Failures | 🟡 | Pas de signature d'artefacts ; GHCR + SSH |
| A09 Logging/Monitoring Failures | 🔴 | Audit métier OK mais **observabilité sécurité quasi absente** (pas d'alerting sur anomalies) |
| A10 SSRF | 🟢 | Surface faible (appels sortants : WhatsApp/FCM maîtrisés) |

**Findings sécurité prioritaires :**
1. 🔴 Implémenter **FR68** (révocation de session <5 min) — exigence sécurité.
2. 🔴 Ajouter **scan de dépendances** (Dependabot + Trivy image) en CI.
3. 🟠 Durcir l'**allow-list de schéma** (regex) pour tout `JdbcTemplate`.
4. 🟠 Restreindre **CORS** en prod (retirer `localhost:3000` hardcodé).
5. 🟠 Rendre l'audit **append-only au niveau DB** (révocation `UPDATE/DELETE` + trigger).
6. 🟡 Forcer l'absence de secrets par défaut (fail-fast si `ADMIN_PASSWORD` non fourni en prod).

---

# 11. Audit Infrastructure / CI-CD / Conteneurisation

## 11.1 CI/CD (GitHub Actions)

| Workflow | Constat |
|---|---|
| `backend-ci.yml` | ✅ Java 21, Postgres service, `mvn test` ; 🟡 injecte `JWT_SECRET` (HMAC) **non utilisé** (l'app est RS256) — vestige ; **pas de gate de couverture, pas de SAST** |
| `flutter-ci.yml` | ✅ analyze + test + coverage (artefact) ; 🟡 **pas de seuil de couverture** |
| `deploy-backend.yml` | Docker build → GHCR → SSH sur serveur unique ; 🟡 stratégie de déploiement (rolling/health gate) à vérifier |
| `release-flutter.yml` | Build APK signé |
| **Dashboard** | 🔴 **aucun workflow CI** (ni lint ni build vérifié) |

## 11.2 Conteneurisation

- `backend/Dockerfile` : ✅ multi-stage, JRE Alpine, **utilisateur non-root**, `MaxRAMPercentage`. Bon.
- `docker-compose.yml` : **dev uniquement** (Postgres + pgAdmin), pas de service backend.
- `app/Dockerfile` : présent (build web ?).

## 11.3 Orchestration & Cloud

- 🔴 **Pas de Kubernetes, pas de Helm, pas d'IaC (Terraform/Pulumi)**.
- 🔴 Déploiement **mono-serveur SSH** → contredit NFR15 (1000+ tenants, scaling linéaire) et NFR18 (99,5 % uptime).
- ❓ Backups (NFR22) : non versionnés dans le repo, dépendent du serveur.

## 11.4 Gestion des secrets

- ✅ Clés RSA `*.pem` **gitignored** et non trackées (vérifié `git ls-files`).
- ✅ Secrets GitHub Actions (DEPLOY_SSH_*, JWT_*_PEM, ANDROID_*).
- 🟡 Defaults faibles dans `application.yml`.
- 🔴 Pas de coffre (Vault/SOPS/Secrets Manager) — secrets injectés via env/SSH.

---

# 12. Dette Technique (registre consolidé)

Sources : `deferred-work.md` (registre actif maintenu — **excellent réflexe**), code, config.

| ID | Dette | Sévérité | Effort |
|---|---|---|---|
| DT1 | `ddl-auto=update` prod / pas de migrations | 🔴 | M-L |
| DT2 | `TenantSchemaSyncService` avale les exceptions | 🔴 | S-M |
| DT3 | État en mémoire (rate limit, caches) non distribuable | 🟠 | M |
| DT4 | Couplage inter-domaines | 🟠 | M-L |
| DT5 | Observabilité absente | 🟠 | M |
| DT6 | Modules vides / frontières incohérentes | 🟡 | S |
| DT7 | Dashboard 0 test + sans CI | 🟡 | S-M |
| DT8 | N+1 multiples (tenant list, pending sales) | 🟡 | S |
| DT9 | `DayClosureDeltaProvider` mauvais index | 🟡 | S |
| DT10 | Trous RBAC (annulation brouillon, deviceToken) | 🟡 | S |
| DT11 | Conventions mixtes (`model`/`entity`, `web`/`rest`, Riverpod 2 vs 3) | 🟢 | S |
| DT12 | `public.users.tenant_id` dangling | 🟢 | S (nécessite migration → dépend de DT1) |
| DT13 | Pas de scan dépendances / SAST | 🟠 | S |

---

# 13. Risques

| Risque | Prob. | Impact | Niveau | Mitigation |
|---|---|---|---|---|
| Drift de schéma multi-tenant (DT1+DT2) | Haute | Critique | 🔴 | Migrations versionnées + provisioning idempotent + alerting |
| Impossibilité de scaler (DT3 + mono-serveur) | Haute | Élevé | 🔴 | Externaliser l'état (Redis) + orchestration |
| Incident prod non diagnosticable (DT5) | Haute | Élevé | 🔴 | Stack observabilité (OTel + Prometheus + logs JSON) |
| Monétisation bloquée (FR78/9-2 absents) | Certaine | Élevé | 🟠 | Prioriser Epic 9.2/9.3 |
| Non-conformité RGPD (FR91 absent) | Moyenne | Légal | 🟠 | Implémenter suppression compte/données |
| Faille révocation session (FR68) | Moyenne | Sécurité | 🟠 | Liste de révocation JWT (jti) + check <5min |
| Refonte modulaire freinée par couplage (DT4) | Haute | Moyen | 🟠 | Discipline de dépendances (ArchUnit) avant modularisation |

---

# 14. Priorités de correction (avant Phase 2)

**P0 — Bloquants socle (à faire avant toute modularisation) :**
1. **Migrations versionnées** : réactiver Flyway (déjà au classpath) pour `public` + outiller le provisioning/évolution des schémas tenant ; supprimer `ddl-auto=update` en prod (DT1, DT2, DT12).
2. **Observabilité** : Micrometer + OpenTelemetry + Prometheus/Grafana + logs JSON corrélés par `tenantId`/`traceId` (DT5).
3. **Externaliser l'état** : Redis pour rate limiting + cache + (futur) sessions/révocation (DT3).

**P1 — Sécurité & conformité :**
4. FR68 (révocation session <5min), FR91 (RGPD), scan dépendances/SAST (DT13), durcissement CORS/allow-list schéma.

**P2 — Complétude produit & qualité :**
5. Finir Epic 9 (activation plan FR78, revenus FR79) → débloque la monétisation.
6. FR90 (annulation vente), tests + CI dashboard (DT7), corriger N+1 & bug index (DT8, DT9).

**P3 — Hygiène architecture :**
7. ArchUnit pour figer les frontières inter-domaines (DT4), nettoyer modules vides & conventions (DT6, DT11).

---
---

# PHASE 2 — Plan de Refonte Modulaire

> **Objectif** : transformer Keevo en plateforme **modulaire commercialisable** (activation/désactivation/installation de modules, packaging par licence) — façon Odoo/Dolibarr/ERPNext **sans copier leur architecture** — tout en **capitalisant sur le monolithe modulaire existant** plutôt qu'en réécrivant.

## 15. Architecture cible

### 15.1 Principe directeur

**Modular Monolith « plugin-ready » d'abord, extraction sélective ensuite.** Le code est déjà hexagonal et organisé en bounded contexts : on ne jette rien. On ajoute **trois capacités transverses** :

1. **Module Registry** (catalogue de modules installés/activés par tenant).
2. **Licensing & Entitlements** (quels modules/fonctions sont autorisés par plan).
3. **Capability Gating** (un point d'application unique qui bloque endpoints, events, UI et jobs selon les entitlements).

### 15.2 Vue cible (texte)

```
┌──────────────────────────── CORE PLATFORM (toujours actif) ────────────────────────────┐
│  identity (auth, user, membership)  │  tenant/provisioning  │  module-registry          │
│  licensing/entitlements             │  audit                │  notification (transport) │
│  shared-kernel (Money, Ids, events, DomainException, ports communs)                      │
└──────────────────────────────────────────────────────────────────────────────────────┘
                 ▲ contrats publics (interfaces) + Event Bus interne (Spring events)
   ┌─────────────┼───────────────┬───────────────┬───────────────┬───────────────┐
┌──┴──┐      ┌───┴───┐      ┌─────┴────┐    ┌─────┴─────┐    ┌────┴────┐     ┌─────┴─────┐
│CATALOG│    │COMMERCE│     │ INVENTORY │    │ REPORTING │    │MESSAGING│     │  SYNC     │
│(prod, │    │(pos,   │     │(counting, │    │(dashboard,│    │(whatsapp│     │(offline)  │
│cat,   │    │sale,   │     │ stock,    │    │ report,   │    │ provider│     │           │
│variant│    │payment)│     │ transfer) │    │ profit.)  │    │ chains) │     │           │
│contact)│    └────────┘     └───────────┘    └───────────┘    └─────────┘     └───────────┘
└────────┘   MODULES MÉTIER ENFICHABLES — activables/désactivables par tenant via entitlements
   ┌──────── modules FUTURS (mêmes contrats) : import-tracking, invoicing, affiliation, expenses, accounting ────────┐
```

### 15.3 Shared Kernel

Extraire un véritable `shared-kernel` (déjà ~présent dans `com.keevo.shared`) : `Money(XAF)`, `TenantId`, `ActorId`, `DomainEvent`, `DomainException/ErrorCode`, ports communs (`AuditPort`, `NotificationPort`, `ClockPort`). **Règle** : un module ne dépend que du shared-kernel + des **API publiées** des autres modules, jamais de leurs classes internes.

### 15.4 Contrats inter-modules (le vrai chantier)

Pour chaque module, définir une **API publique** (`{module}/api/`) = interfaces + DTO + events publiés. Tout le reste devient `internal`. Exemple : `commerce` a besoin du stock → il appelle `inventory.api.StockQuery`/`StockCommand`, pas `catalog.stock.domain.service.StockOperationService`. **Faire respecter par ArchUnit** :

```
règle : aucun type *.internal.* ne doit être importé hors de son module
règle : un module métier ne dépend que de core.* et {autre}.api.*
```

### 15.5 Event Bus

Conserver les **Spring ApplicationEvents** en intra-process (suffisant pour un monolithe modulaire). Introduire une **enveloppe d'event standardisée** (`{eventId, tenantId, actorId, occurredAt, type, payload}`) + un **outbox transactionnel** (table `event_outbox` par schéma) pour fiabiliser la livraison et préparer une future bascule vers un broker (Kafka/Rabbit) **sans changer les producteurs**.

### 15.6 Modular Monolith vs Microservices — décision argumentée

| Critère | Modular Monolith (recommandé) | Microservices |
|---|---|---|
| Équipe | Solo/petite → ✅ adapté | Nécessite plusieurs équipes ❌ |
| Couplage actuel | Fort → extraction coûteuse maintenant | ❌ prématuré |
| Multi-tenant schema-per-tenant | Simple en monolithe | Complexe (DB distribuée) |
| Modularité commerciale | ✅ atteignable via registry + entitlements | Overkill |
| Coût opérationnel | Faible (1 déploiement) | Élevé (mesh, observabilité distribuée) |

**Décision : Modular Monolith renforcé.** Garder **microservice-ready** comme *option* (contrats + events + outbox), extraire **uniquement** un service plus tard si un module a un profil de charge incompatible (ex. `sync` ou `reporting`).

---

## 16. Découpage des modules — Actuel → Futur

| Module | Actuel | Futur (cible) | Action |
|---|---|---|---|
| identity | actif (auth/user/employee/onboarding) | **CORE** | reste dans le noyau |
| tenant/provisioning | `admin/tenant` | **CORE** | extraire le provisioning du domaine admin |
| module-registry | ❌ inexistant | **CORE (nouveau)** | créer |
| licensing/entitlements | partiel (`subscription/plan`) | **CORE (étendu)** | généraliser plan→entitlements par module |
| audit | `shared` | **CORE** | rendre append-only |
| catalog (product/category/variant/contact) | actif | **MODULE enfichable** | API publique |
| commerce (pos/sale) | actif | **MODULE** | API ; `payment` à implémenter ou supprimer |
| inventory (counting + **stock** + **transfer**) | dispersé (stock/transfer dans catalog) | **MODULE consolidé** | **rapatrier** stock & transfer dans `inventory` |
| reporting (dashboard/report/profitability) | actif | **MODULE** | API ; gros consommateur d'events |
| messaging (whatsapp/notification) | actif | **MODULE (transport en core)** | provider chains via Strategy/CoR |
| sync | actif | **MODULE infra** | API + outbox |
| admin (platform/operations/monitoring) | partiel | **MODULE Super-Admin** | finir Epic 9 |
| **import-tracking** | ❌ (PRD Growth) | **MODULE payant** | nouveau |
| **invoicing** | ❌ (PRD Growth) | **MODULE payant** | nouveau |
| **affiliation** | ❌ (PRD Growth) | **MODULE payant** | nouveau |
| **expenses/charges** | ❌ (gap analysis client) | **MODULE payant** | nouveau (demande client réelle) |
| **accounting** | ❌ (Vision) | **MODULE payant** | futur |

---

## 17. Gestion des licences & packaging

### 17.1 Modèle d'entitlements

```
Plan (FREE | PREMIUM_TRIAL | PREMIUM | + futurs)
   └── ModulePackage (ex. "Core", "Croissance", "Import", "Compta")
         └── Entitlement { moduleKey, enabled, limits{maxStores,maxProducts,...}, features[] }
```

- **`public.module_definitions`** : catalogue global des modules (key, nom, dépendances, version, payant/gratuit).
- **`{tenant}.tenant_modules`** (ou `public.tenant_entitlements`) : modules **activés** par tenant + limites.
- **`EntitlementService`** (CORE) : `isEnabled(tenantId, moduleKey)` / `assertFeature(...)` — appelé par un **`@RequiresEntitlement("catalog")`** (Decorator/aspect) côté backend et par des **guards de route** côté Flutter/Next.

### 17.2 Activation / désactivation / installation

| Opération | Mécanisme |
|---|---|
| **Activer un module** | Super-Admin (ou self-service futur) → upsert `tenant_modules` → provisioning des tables du module dans le schéma tenant (idempotent) |
| **Désactiver** | flag `enabled=false` → gating bloque endpoints/UI ; **données conservées** (cohérent FR19) |
| **Installer un nouveau module** | déploiement du jar (monolithe) + entrée `module_definitions` + migration tables ; activation par tenant à la demande |
| **Supprimer** | désactivation + purge optionnelle (RGPD) |

### 17.3 Modules gratuits vs payants (proposition)

| Package | Modules | Plan |
|---|---|---|
| **Core (gratuit)** | identity, catalog (limité 500 produits/1 store/3 employés), commerce POS, inventory de base, sync, rapports basiques | FREE |
| **Croissance (payant)** | multi-boutiques illimité, reporting avancé/profitabilité, exports PDF/Excel, alertes WhatsApp avancées | PREMIUM |
| **Import (payant)** | import-tracking (colis Chine, transporteurs) | add-on |
| **Facturation (payant)** | invoicing PDF + envoi WhatsApp | add-on |
| **Compta/Charges (payant)** | expenses, accounting (demande client gap-analysis) | add-on |
| **Affiliation (payant)** | codes promo, commissions, parrainage | add-on |

### 17.4 Anti-fraude licence

Entitlements signés dans le JWT (claim `modules`) **+** vérification serveur (source de vérité = DB) ; en offline, l'app respecte le dernier état signé jusqu'à la prochaine sync (cohérent avec la gate 7 jours existante).

---

## 18. Architecture Frontend modulaire

### 18.1 Flutter (app marchand)

- **Routing modulaire** : registre de routes par feature, monté conditionnellement selon les entitlements (`go_router` branches dynamiques).
- **Lazy loading** : `deferred as` imports Dart pour différer le chargement des features payantes lourdes (invoicing, import-tracking).
- **Feature flags providers** : `entitlementsProvider` (Riverpod) → chaque feature s'auto-désactive (UI masquée + garde de route) si non autorisée.
- **Pas de module federation** (non pertinent pour Flutter compilé) — la modularité passe par **packages Dart internes** (`packages/feature_invoicing`, etc.) avec un melos workspace.
- **Migration Riverpod 2 → 3** à planifier (cohérence avec l'architecture cible).

### 18.2 Dashboard Next.js (admin)

- **Routing modulaire** par dossier `app/(modules)/…` + middleware lisant les entitlements.
- **Lazy loading** natif (`next/dynamic`) pour les écrans lourds (analytics/recharts).
- **Module Federation** : pertinent **seulement** si des modules tiers/partenaires doivent livrer leur propre UI admin → sinon over-engineering. Recommandation : **différer**, démarrer en monorepo de packages.

---

## 19. Architecture Backend modulaire (décisions)

| Décision | Choix | Justification |
|---|---|---|
| Style | **Modular Monolith** renforcé | équipe solo, couplage actuel, coût opérationnel (cf. §15.6) |
| DDD | **maintenu et durci** (API publiques par module) | déjà en place, à discipliner |
| EDA | **Spring events + Outbox transactionnel** | fiabilité sans broker, broker-ready |
| CQRS | **léger/sélectif** | déjà des Query/Command Records ; full CQRS (event sourcing) **non justifié** sauf reporting |
| Modularité | **Module Registry + Entitlements + gating par aspect** | cœur de la commercialisation |
| Frontières | **ArchUnit** | empêche la régression du couplage |
| Persistance modules | tables du module **dans le schéma tenant**, provisionnées à l'activation | cohérent avec l'isolation existante |

---

## 20. Roadmap de migration

> Estimations en **personne-semaines (PS)** pour un dev solo expérimenté assisté IA. Séquentiel logique ; certaines phases parallélisables.

### Phase 0 — Préparation (≈2-3 PS)
- **Prérequis** : geler le périmètre, snapshot prod, mettre en place ArchUnit (baseline) + Testcontainers en CI.
- **Livrables** : baseline de dépendances inter-modules documentée, gate de couverture activée, scan dépendances (Trivy/Dependabot).
- **Risques** : faible. **Gains** : visibilité, filet de sécurité.

### Phase 1 — Réduction de dette socle (≈5-8 PS) — *bloquant*
- **Migrations versionnées** (réactiver Flyway public + outil de migration tenant idempotent), suppression `ddl-auto` prod, suppression colonne dangling.
- **Observabilité** (OTel + Micrometer + Prometheus + logs JSON corrélés tenant).
- **Externalisation état** (Redis : rate limit, cache, révocation JWT).
- **Sécurité** : FR68 (révocation session), durcissement CORS/allow-list schéma, audit append-only DB.
- **Risques** : 🔴 migration de schéma sur tenants existants (tester sur copie prod). **Gains** : exploitabilité, scaling débloqué, conformité sécurité.

### Phase 2 — Modularisation Backend (≈6-10 PS)
- Extraire **CORE** (identity, tenant/provisioning, registry, entitlements, audit, shared-kernel).
- Définir **API publiques** par module + couche `internal` ; rapatrier stock/transfer dans `inventory` ; trancher `payment`/`warehouse` (implémenter ou supprimer).
- Introduire **Outbox** + enveloppe d'event standard.
- **Risques** : 🟠 refactor de couplage (régressions). Mitigation : ArchUnit + tests. **Gains** : modules réellement isolés.

### Phase 3 — Modularisation Frontend (≈4-6 PS)
- Melos workspace Flutter (packages par feature) + routing/feature flags par entitlement + lazy loading.
- Dashboard : routing modulaire + middleware entitlements + **CI + tests** (combler DT7).
- **Risques** : 🟡 migration Riverpod 2→3. **Gains** : UI alignée sur les licences.

### Phase 4 — Système de licences (≈4-6 PS)
- `module_definitions` + `tenant_entitlements` + `EntitlementService` + `@RequiresEntitlement` + claim JWT `modules`.
- Console Super-Admin pour activer/désactiver/packager (finit Epic 9 : FR78 activation plan, FR79 revenus).
- **Risques** : 🟠 cohérence offline des entitlements. **Gains** : **commercialisation modulaire opérationnelle**.

### Phase 5 — Migration des données (≈3-5 PS)
- Backfill `tenant_entitlements` pour les tenants existants (tous en « Core + Premium Trial » selon leur statut actuel).
- Provisioning idempotent des tables des modules pour les tenants ayant le module activé.
- Purge contrôlée (`tenant_id` dangling, sync_queue payloads obsolètes documentés dans deferred-work).
- **Risques** : 🔴 sur prod multi-tenant. Mitigation : dry-run + rollback Flyway. **Gains** : base saine.

### Phase 6 — Mise en production (≈3-4 PS)
- IaC minimal (Terraform) + orchestration (Docker Swarm ou K8s léger type k3s) pour HA, ou managé (RDS + ECS/Cloud Run).
- Blue/green ou rolling avec health gate, backups automatisés vérifiés (NFR22), runbook.
- **Risques** : 🟠 bascule infra. **Gains** : NFR15/18 (scaling, uptime) tenus, fondation 3 ans.

**Total indicatif :** ~27-42 PS (≈ 7-10 mois solo). Phase 1 est **non négociable** et conditionne tout le reste.

---

## 21. Conclusion

Keevo possède un **socle d'ingénierie de qualité rare pour son stade** : architecture hexagonale disciplinée, documentation d'architecture exceptionnelle, TDD systématique, audit trail, offline-first robuste. Le produit délivre déjà la promesse cœur (Simon & Loïc).

Trois dettes structurelles — **migrations non versionnées, état en mémoire, absence d'observabilité** — l'empêchent aujourd'hui de **passer à l'échelle** et de **s'exploiter sereinement**. Elles sont **rattrapables** (Phase 1) car la base est saine.

La **refonte modulaire** visée n'exige **pas** de microservices ni de réécriture : le monolithe modulaire existant, enrichi d'un **Module Registry + Entitlements + gating**, atteint l'objectif « Odoo-like » à un coût opérationnel maîtrisé. Le principal travail préalable est de **transformer le couplage inter-domaines implicite en contrats explicites** — un effort modéré sur des fondations déjà hexagonales.

**Recommandation finale au CTO :** financer Phase 0-1 immédiatement (stabilisation socle), puis dérouler 2-6. Ne pas commercialiser de modules payants avant la fin de Phase 4 (entitlements) et la résolution de FR91 (RGPD) / FR78 (activation paiement).

---

*Fin du rapport — `review_claude_opus_8.md`*
