---
stepsCompleted:
  - step-01-validate-prerequisites
  - step-02-design-epics
  - step-03-create-stories
  - step-04-final-validation
validationStatus: PASSED
totalEpics: 9
totalStories: 46
totalFRsCovered: 93
inputDocuments:
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/ux-design-specification.md
---

# Keevo - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Keevo, decomposing the requirements from the PRD, UX Design, and Architecture into implementable stories.

## Requirements Inventory

### Functional Requirements

**Auth & Onboarding (FR1–FR7)**
- FR1: Un propriétaire peut créer un compte via son numéro WhatsApp (pas d'email obligatoire)
- FR2: Le système peut provisionner automatiquement un tenant isolé (schéma PostgreSQL `KV-XXXXXX`) à l'inscription
- FR3: Un utilisateur peut se connecter via JWT sur mobile et desktop
- FR4: Un propriétaire peut choisir son secteur d'activité à l'inscription (Vêtements, Électronique, Librairie, Électroménager)
- FR5: Le système peut appliquer un template sectoriel pré-configuré (catégories, champs, organisation) selon le secteur choisi
- FR6: Un propriétaire peut compléter le wizard d'onboarding (nom, secteur, boutiques) en moins de 5 minutes
- FR7: Le système peut afficher des tutoriels vidéo intégrés en français (2 min par module clé)

**Provisioning & Initialisation Tenant (FR8–FR15)**
- FR8: Le système peut créer automatiquement le compte utilisateur-propriétaire (admin du tenant) avec le rôle "Owner" à l'inscription
- FR9: Le système peut créer automatiquement les tables du schéma tenant dans PostgreSQL (produits, stocks, ventes, boutiques, utilisateurs, etc.)
- FR10: Le système peut initialiser les rôles par défaut du tenant (Owner, Employee) avec leurs permissions respectives
- FR11: Le système peut pré-configurer les catégories produits par défaut selon le template sectoriel choisi
- FR12: Le système peut initialiser les préférences de notification par défaut (heure du rapport end-of-day, alertes stock activées)
- FR13: Le système peut créer la première boutique par défaut du tenant (saisie dans le wizard)
- FR14: Le système peut générer le code unique tenant (`KV-XXXXXX`) et l'associer au schéma créé
- FR15: Le système peut initialiser le statut de souscription par défaut (Plan Free, limites actives)

**Gestion des Souscriptions (FR16–FR20)**
- FR16: Le système peut appliquer les limites du plan gratuit : max 3 boutiques, 500 produits, 5 employés
- FR17: Le système peut afficher un message clair avec CTA upgrade lorsqu'une limite est atteinte
- FR18: Le système peut suspendre automatiquement un tenant en lecture seule à l'expiration de son abonnement payant
- FR19: Le système peut conserver les données d'un tenant suspendu (lecture seule, pas de suppression)
- FR20: Un propriétaire peut consulter son statut de souscription (Actif / Expiré / Suspendu)

**Gestion des Produits (FR21–FR29)**
- FR21: Un propriétaire peut créer, modifier et archiver des produits (nom, description, photo, référence)
- FR22: Un propriétaire peut définir des variantes produit (taille, couleur)
- FR23: Un propriétaire peut saisir le prix de vente, le prix d'achat et les coûts de transport par produit
- FR24: Le système peut calculer automatiquement la marge nette par produit (prix vente − prix achat − transport)
- FR25: Un propriétaire peut organiser les produits par catégories flexibles
- FR26: Un propriétaire peut configurer un seuil de stock minimum par produit (alerte)
- FR27: Un propriétaire peut consulter l'historique complet des mouvements de stock (entrées, sorties, transferts, ajustements)
- FR28: Un propriétaire peut importer des produits via fichier CSV
- FR29: Un employé peut consulter le catalogue produits de sa boutique

**Gestion Multi-Boutiques & Warehouse (FR30–FR36)**
- FR30: Un propriétaire peut créer et gérer plusieurs boutiques + un warehouse principal
- FR31: Un propriétaire peut visualiser les niveaux de stock de toutes ses boutiques en temps réel sur une vue centralisée
- FR32: Un propriétaire peut initier un transfert de stock entre boutiques avec traçabilité complète
- FR33: Un employé peut vérifier la disponibilité d'un produit dans toutes les boutiques du tenant
- FR34: Un propriétaire peut utiliser le warehouse comme point d'origine et de distribution des stocks
- FR35: Un propriétaire peut assigner un employé à une boutique spécifique
- FR36: Un employé est limité aux opérations de la boutique à laquelle il est assigné

**Point de Vente — POS (FR37–FR44)**
- FR37: Un employé peut enregistrer une vente en sélectionnant des produits et en entrant les quantités
- FR38: Un employé peut enregistrer le mode de paiement d'une vente (Cash ou Mobile Money)
- FR39: Un employé peut appliquer des réductions sur une vente
- FR40: Un employé peut vérifier instantanément le stock cross-boutique depuis l'interface POS
- FR41: Un employé peut clôturer sa journée de vente en un clic
- FR42: Le système peut calculer automatiquement le récapitulatif des ventes de la journée
- FR43: Un employé peut consulter l'historique de ses propres ventes
- FR44: Un propriétaire peut consulter le classement des vendeurs (leaderboard) par performance

**Inventaire Automatisé (FR45–FR49)**
- FR45: Un propriétaire peut lancer un inventaire complet ou partiel assisté par formulaire guidé
- FR46: Le système peut afficher le stock théorique (Keevo) à côté du stock physique saisi
- FR47: Le système peut calculer automatiquement les écarts entre stock théorique et physique
- FR48: Un propriétaire peut valider et ajuster le stock post-inventaire en un clic
- FR49: Le système peut générer un rapport d'inventaire détaillé avec les écarts identifiés

**Rapports & Dashboard (FR50–FR56)**
- FR50: Un propriétaire peut consulter un dashboard temps réel : stock global, ventes du jour, alertes actives, top produits
- FR51: Le système peut générer un rapport end-of-day à heure fixe configurable OU sur clôture manuelle
- FR52: Le système peut générer un rapport hebdomadaire automatique (dimanche soir)
- FR53: Les rapports incluent : CA, bénéfices, marges, top/flop produits, ventes par boutique, ventes par employé
- FR54: Un propriétaire peut consulter la rentabilité par produit (prix achat + transport + prix vente = marge nette)
- FR55: Un propriétaire peut exporter des rapports en PDF et Excel (Plan Payant)
- FR56: Un propriétaire peut consulter les performances comparatives entre boutiques

**Communication WhatsApp (FR57–FR60)**
- FR57: Le système peut envoyer automatiquement le rapport end-of-day au propriétaire via WhatsApp
- FR58: Le système peut envoyer un rapport hebdomadaire via WhatsApp + SMS
- FR59: Le système peut envoyer un rapport d'inventaire sur demande via WhatsApp
- FR60: Un propriétaire peut configurer les types de rapports et la fréquence d'envoi

**Alertes & Notifications (FR61–FR64)**
- FR61: Le système peut déclencher des alertes de stock critique via push notification + WhatsApp (seuil configurable)
- FR62: Le système peut envoyer des notifications d'évolution des ventes (pics, baisses) en temps réel
- FR63: Le système peut afficher un message motivationnel quotidien au propriétaire au démarrage de l'app
- FR64: Le système peut envoyer des alertes de changement de statut colis (Plan Payant)

**Gestion des Utilisateurs (FR65–FR68)**
- FR65: Un propriétaire peut inviter des employés par numéro WhatsApp ou lien unique
- FR66: Un propriétaire peut gérer les rôles de ses utilisateurs (Propriétaire / Employé)
- FR67: Un propriétaire peut consulter l'activité de ses employés (dernière connexion, ventes effectuées)
- FR68: Un propriétaire peut désactiver un employé et révoquer ses sessions actives à distance

**Synchronisation Offline-First (FR69–FR76)**
- FR69: L'application peut fonctionner de manière 100% opérationnelle hors-ligne pendant 7 jours maximum
- FR70: Le système peut synchroniser automatiquement les données au retour de la connectivité (<60 secondes)
- FR71: Le système peut résoudre les conflits multi-device via synchronisation delta-based
- FR72: L'application peut stocker toutes les données critiques localement sur le device
- FR73: Le système peut garantir zéro perte de données après synchronisation
- FR74: Le système peut suspendre l'accès après 7 jours sans connexion (sync obligatoire pour débloquer)
- FR75: L'application peut afficher un indicateur visible du statut de connexion et de la dernière synchronisation
- FR76: Le système peut synchroniser les données en temps réel entre tous les appareils d'un même utilisateur

**Super Admin Dashboard (FR77–FR83)**
- FR77: Le super admin peut visualiser la liste de tous les tenants avec statut, secteur, plan, date d'inscription, dernière sync
- FR78: Le super admin peut activer/désactiver manuellement un compte payant et consulter l'historique des paiements par tenant
- FR79: Le super admin peut consulter le dashboard revenus : MRR, tenants payants vs free, taux de conversion, churn mensuel
- FR80: Le super admin peut consulter les analytics plateforme : volume total de ventes, boutiques actives, taille base produits agrégée, distribution géographique, courbes de croissance
- FR81: Le système peut envoyer des alertes proactives WhatsApp/email (tenant J+5 sans sync, abonnement expirant dans <7 jours)
- FR82: Le super admin peut envoyer des notifications globales push à tous les tenants
- FR83: Le super admin peut consulter la santé du système : statut serveurs, tenants en retard de sync, logs d'erreurs critiques

**Sécurité & Audit (FR84–FR86)**
- FR84: Le système peut maintenir un journal d'audit complet de toutes les modifications (stock, prix, ventes, transferts) avec identité, horodatage et valeurs avant/après
- FR85: L'application peut chiffrer les données stockées localement sur le device (chiffrement au repos)
- FR86: Le système utilise le Franc CFA (XAF) comme devise unique en V1 pour toutes les opérations financières

**Gestion Clients & Fournisseurs (FR87–FR89)**
- FR87: Un propriétaire peut créer et gérer une base de clients avec nom et numéro de téléphone
- FR88: Un employé peut associer un client à une vente
- FR89: Un propriétaire peut créer et gérer une base de fournisseurs (nom, pays, contact) et associer des produits à leurs fournisseurs

**Gestion des Erreurs & Lifecycle (FR90–FR93)**
- FR90: Un propriétaire peut annuler ou corriger une vente avec justification obligatoire (stock automatiquement réajusté)
- FR91: Un propriétaire peut demander la suppression complète de son compte et de toutes ses données
- FR92: Un utilisateur peut soumettre un feedback ou signaler un problème directement depuis l'application
- FR93: Le système peut appliquer des limites de débit par tenant pour protéger les performances de la plateforme

### NonFunctional Requirements

**Performance**
- NFR1: Toute action utilisateur (enregistrement vente, consultation stock) doit se compléter en <2 secondes sur l'interface locale (offline)
- NFR2: La synchronisation complète au retour en ligne doit se compléter en <60 secondes pour jusqu'à 7 jours de données accumulées
- NFR3: Le dashboard propriétaire doit se charger en <3 secondes avec jusqu'à 500 produits et 30 jours d'historique
- NFR4: L'application doit rester fluide (>30 FPS) sur des appareils Android avec 2 Go de RAM et processeur entrée de gamme
- NFR5: La recherche produit doit retourner des résultats en <500ms sur un catalogue de 500 produits
- NFR6: L'application mobile ne doit pas dépasser 100 Mo d'espace de stockage (hors données utilisateur)

**Sécurité**
- NFR7: Toutes les données en transit doivent être chiffrées via TLS 1.2+ (HTTPS)
- NFR8: Les données stockées localement (SQLite) doivent être chiffrées au repos (SQLCipher ou équivalent)
- NFR9: Les tokens JWT doivent expirer après 24h avec mécanisme de refresh token
- NFR10: L'isolation des données entre tenants doit être garantie à 100% — aucune fuite de données cross-tenant
- NFR11: Les mots de passe doivent être stockés avec hachage bcrypt (coût ≥12)
- NFR12: Les sessions révoquées (employé désactivé) doivent être invalidées en <5 minutes
- NFR13: Le journal d'audit doit être immuable — aucune modification/suppression possible par les utilisateurs

**Scalabilité**
- NFR14: Le système doit supporter 100 tenants actifs simultanés en Phase 1 (M+3) sans dégradation de performance
- NFR15: L'architecture doit permettre une croissance à 1 000+ tenants (Phase 2) avec uniquement une augmentation linéaire des ressources serveur
- NFR16: La base de données doit supporter jusqu'à 50 000 produits agrégés sur la plateforme
- NFR17: Le système doit supporter des pics de charge saisonniers (rentrée scolaire, fêtes) avec un facteur 3x du trafic normal

**Fiabilité & Disponibilité**
- NFR18: Le backend cloud doit maintenir un uptime de ≥99.5% (max ~43h de downtime/an)
- NFR19: L'application offline doit fonctionner avec 0% de downtime côté client pendant les 7 jours garantis
- NFR20: La synchronisation doit garantir zéro perte de données — intégrité ACID sur les opérations de sync
- NFR21: Le système doit supporter des reprises automatiques en cas d'échec de synchronisation (retry avec backoff exponentiel)
- NFR22: Les sauvegardes de base de données doivent être effectuées au minimum une fois par jour avec rétention de 30 jours

**Accessibilité & Utilisabilité**
- NFR23: L'interface doit être utilisable par des personnes avec un niveau de tech literacy de 4/10 (typique des commerçants cibles)
- NFR24: Toutes les fonctionnalités critiques doivent être accessibles en ≤3 taps/clics depuis l'écran principal
- NFR25: L'application doit supporter les tailles de police adaptatives pour les utilisateurs avec des difficultés visuelles
- NFR26: Toute l'interface doit être en français (V1), avec architecture i18n prête pour l'anglais (V2)
- NFR27: L'onboarding complet (inscription → première vente) ne doit pas dépasser 10 minutes

**Intégration**
- NFR28: L'intégration WhatsApp Business API doit supporter un débit de ≥100 messages/heure par tenant
- NFR29: La synchronisation delta-based doit fonctionner de manière transparente avec une latence <5 secondes en conditions réseau normales
- NFR30: Les intégrations externes (WhatsApp, sync engine, AWS) doivent avoir des mécanismes de fallback gracieux en cas d'indisponibilité
- NFR31: L'API backend doit suivre les standards RESTful avec versioning (`/api/v1/`) pour permettre l'évolution sans casser les clients existants

**Compatibilité**
- NFR32: L'application mobile doit supporter Android 8+ (API 26) et iOS 14+
- NFR33: L'application desktop doit supporter Windows 10+, Linux (Ubuntu 20.04+, Debian 11+), et macOS 12+ (Growth)
- NFR34: L'application doit fonctionner correctement sur des écrans de 5" (mobile) à 27" (desktop)

### Additional Requirements

**From Architecture — Starter Templates & Project Initialization**
- ARCH1: L'Epic 1 Story 1 doit initialiser le projet Flutter avec `flutter create --org com.keevo --project-name keevo --platforms android,ios,linux,windows --empty ./` (Flutter 3.41)
- ARCH2: Le backend Spring Boot doit être initialisé via Spring Initializr (Spring Boot 3.5.x, Java 21) avec dépendances : Web, Data JPA, Security, Actuator, PostgreSQL, Flyway, Validation, Lombok
- ARCH3: Le desktop utilise le même codebase Flutter (pas de starter séparé) — parité mobile/desktop intégrée
- ARCH4: Structure monorepo `keevo/` avec `backend/` (Spring Boot) et `app/` (Flutter) + `docker-compose.yml` (PostgreSQL dev) + `.github/workflows/` (backend-ci.yml, flutter-ci.yml)

**From Architecture — Hexagonal Architecture & Patterns**
- ARCH5: Toute implémentation doit suivre l'architecture hexagonale (Ports & Adapters) — backend et frontend
- ARCH6: Avant chaque feature, analyser et documenter le design pattern GoF approprié (obligatoire, non négociable)
  - Patterns mappés Keevo : Strategy (sync, WhatsApp), Factory (TenantFactory), Observer (Audit), Decorator (RBAC), Template Method (Reports), Chain of Responsibility (notifications), Builder (SyncPullQueryBuilder), Adapter+Façade (services externes)
- ARCH7: Le backend suit la décomposition Domain → Module → Layer (10 domaines : identity, catalog, commerce, inventory, store, reporting, messaging, sync, subscription, admin)
- ARCH8: Le frontend suit Feature-first + Clean Architecture avec Riverpod 3.0 (+ riverpod_generator) comme state management
- ARCH9: go_router doit être utilisé pour le routing Flutter
- ARCH10: La couche `domain/` Flutter et backend ne doit avoir ZÉRO dépendance vers un framework externe (Flutter, Drift, Spring)

**From Architecture — TDD (NON-NÉGOCIABLE)**
- ARCH11: Toute feature doit suivre strictement TDD : RED (test échoue) → GREEN (code minimal) → REFACTOR
- ARCH12: Backend testing stack : JUnit 5 + Mockito (unit), `@WebMvcTest` MockMvc (REST), `@DataJpaTest` Testcontainers PostgreSQL (repository), tests d'isolation multi-tenant custom
- ARCH13: Frontend testing stack : `test` + `mocktail` (unit/domain), `riverpod_test` (providers), `flutter_test` WidgetTester (widgets), `integration_test` (E2E flows)
- ARCH14: Séquence TDD par feature : domain model → use case → repository → controller/provider → widget → ALL GREEN = DONE

**From Architecture — Infrastructure & Deployment**
- ARCH15: AWS héberge le backend (RDS PostgreSQL, EC2/ECS, S3)
- ARCH16: GitHub Actions assure la CI/CD (backend-ci.yml, flutter-ci.yml)
- ARCH17: CloudWatch + Spring Actuator pour le monitoring
- ARCH18: Chaque tenant dispose d'un schéma PostgreSQL isolé `kv_xxxxxx` (schema-per-tenant, Flyway programmatic par tenant, ThreadLocal pour résolution)
- ARCH19: Sync offline-first via moteur REST delta-based custom (MVP) : table Drift `sync_queue`, `POST /api/v1/sync/push` (batch), `GET /api/v1/sync/pull?since={timestamp}`, conflits stock = delta-based, autres = last-write-wins, retry backoff exponentiel (2s, 4s, 8s… max 5min)

**From Architecture — Security Implementation**
- ARCH20: Auth par login/password → JWT RS256 (bibliothèque jjwt)
- ARCH21: Stockage tokens côté client via flutter_secure_storage
- ARCH22: Rate limiting : filtre Spring Boot à 100 req/min/tenant
- ARCH23: WhatsApp via abstraction `WhatsAppPort` (provider TBD, Wassender ou Africa's Talking)

**From Architecture — Naming & Format Conventions**
- ARCH24: DB PostgreSQL : tables `snake_case` pluriel, colonnes `snake_case`, PK `id` (UUID v4), FK `{entity}_id`, index `idx_{table}_{columns}`, schéma tenant `kv_{6digits}`
- ARCH25: API REST : endpoints pluriel `kebab-case` `/api/v1/products`, params `camelCase`, headers `X-Keevo-{Name}`, JSON `camelCase`
- ARCH26: Java : classes `PascalCase`, interfaces ports (nom du contrat), implémentations `{Provider}{Name}`, DTOs `{Action}{Entity}Request/Response`, exceptions `{Entity}{Problem}Exception`, constants `SCREAMING_SNAKE_CASE`
- ARCH27: Dart : fichiers `snake_case`, classes `PascalCase`, variables `camelCase`, providers `{name}Provider` via `@riverpod`, Drift tables `PascalCase` pluriel
- ARCH28: Format API succès : `{ "data": { ... }, "meta": { "page": 1, "total": 42 } }` ; erreur : `{ "error", "code", "domainCode", "details", "timestamp" }`
- ARCH29: Monnaie : entier XAF sans décimales. Dates : ISO 8601 UTC en JSON, locale `fr` pour affichage. IDs : UUID v4. Booléens : `true`/`false`.
- ARCH30: Domain events : `{Entity}{PastTense}Event` → `{ entityId, tenantId, userId, occurredAt, data }` → `{Domain}EventListener`
- ARCH31: Riverpod state : `@riverpod` annotation, state immutable (Freezed ou Dart 3 records), `AsyncValue` pour toutes opérations async

**From UX — Design System & Tokens**
- UX1: Design system : Material Design 3 (Material You) — composants natifs Flutter + tokens custom Keevo
- UX2: Palette "Indigo Sky" : Primaire `#3B5BDB`, Primaire clair `#D0EBFF`, Accent CTA `#FF6B6B`, Succès `#51CF66`, Alerte `#FCC419`, Danger `#FA5252`, Surface dark `#0D1B2A`. Gradient `#3B5BDB → #4DABF7`
- UX3: Typographie : Inter (Google Fonts), poids 300-700. Display 32sp/Bold (CA matinal), Headline 24sp, Title 20sp, Body 16sp, Label 12sp. Minimum interactif : 14sp
- UX4: Spacing grille 8dp — `xs` 4dp, `sm` 8dp, `md` 16dp, `lg` 24dp, `xl` 32dp. Touch targets 48x48dp minimum espacés 8dp
- UX5: Breakpoints adaptatifs : < 600dp Compact (1 col, Bottom NavBar), 600-840dp Medium (2 col), > 840dp Expanded (3 col, NavigationRail). `LayoutBuilder` + `AdaptiveLayout`
- UX6: Navigation : 4 onglets uniquement — 🛒 Vendre / 📦 Stock / 📊 Rapports / ⚙️ Plus. Bottom NavBar sur mobile, NavigationRail sur desktop

**From UX — Design & Interaction Requirements**
- UX7: Touch targets larges (fonctionnent au doigt ET à la souris) — design responsive-first
- UX8: L'onboarding doit permettre de vendre avec 0 produit pré-enregistré ("Vends d'abord, organise après") — création produit à la volée pendant une vente (créé en brouillon)
- UX9: La recherche produit doit être fuzzy (<500ms, SQLite local) avec résultat "Aucun résultat → Créer" pour création à la volée
- UX10: Le **prix dans le panier POS est modifiable** (réduction/promo/négociation) sans modifier le prix catalogue du produit
- UX11: Écran de démarrage propriétaire = résumé matinal ultra-compact (CA hier, alertes, tendance + sparkline 7j) en 3 secondes
- UX12: Interface POS = ≤3 taps pour enregistrer une vente. Temps cible : ≤5s (1 produit), ≤15s (3 produits). Feedback confirmation < 200ms
- UX13: Clôture journalière = 1 tap → résumé flash → envoi WhatsApp auto. Fallback automatique à 20h (rapport auto-généré mentionné "⏰ Rapport auto-généré")
- UX14: Templates sectoriels visuellement identitaires (emoji + icônes secteur) sélectionnables à l'onboarding en grille visuelle
- UX15: Indicateur de connectivité / statut de synchronisation toujours visible (Sync Indicator dans AppBar) — 4 états : Online synced | Online syncing | Offline OK (<7j) | Offline critique (>5j)
- UX16: Leaderboard employé caché en V1 côté employé (visible propriétaire uniquement), activable par toggle
- UX17: Rapport WhatsApp end-of-day = format emoji-rich, compact, lisible en 5 secondes (pas de PDF, pas de lien)
- UX18: Zéro jargon comptable — vocabulaire adapté au niveau tech literacy 4/10. Messages d'erreur humains, jamais techniques
- UX19: Changement de mot de passe obligatoire à la première connexion employé (Flow 2 et 21)
- UX20: Mode sombre natif `ThemeMode.system` (palette navy `#0D1B2A`) — suit les préférences système automatiquement
- UX21: Glassmorphism subtil sur composants premium (hero card, POS cards) : `BackdropFilter` Flutter, blur 10-20, border opacity 30%
- UX22: Micro-animations de succès après chaque vente/clôture (animation lime vert + haptique légère). Message "Bonne soirée Loïc ! 🌙" à la clôture
- UX23: Performance Flutter entry-level : `ListView.builder` (jamais `children`), images lazy loading + cache + WebP, `repaintBoundary` sur composants animés

**From UX — 12 Composants Custom Keevo (requis)**
- UX24: **POS Product Card** — photo + nom + prix XAF + bouton (+), états : défaut / sélectionné (`#D0EBFF`) / rupture (grisé + ⚠️)
- UX25: **Cart Pill** — panier flottant : nb articles + total + "Encaisser", masqué si vide, expand on tap
- UX26: **Morning Summary Hero Card** — CA display 32sp + tendance % + sparkline 7j + glassmorphism
- UX27: **Shop Status Card** — nom boutique + CA + indicateur status (vert/orange/rouge) + tendance %
- UX28: **Metric Badge Card** — icône + nombre + label, variantes bleu/orange/vert
- UX29: **Sync Indicator** — dot coloré + texte état, position AppBar trailing
- UX30: **Day Close Button** — "Clôturer la journée" + icône lune, états : disponible / déjà clôturé / rappel (badge)
- UX31: **Shop Type Selector** — grille icons 4 templates onboarding avec animation ripple
- UX32: **Inventory Row** — nom + stock théorique + champ saisie réel + badge écart (✅ / ⚠️)
- UX33: **WhatsApp Report Preview** — aperçu formaté + bouton "Envoyer sur WhatsApp"
- UX34: **Stock Movement Card** — type (entrée vert / sortie rouge / transfert bleu / ajustement orange) + quantité + date + acteur
- UX35: **Alert Action Card** — produit + stock actuel + seuil + boutique + actions : Commander / Transférer / Ignorer

### FR Coverage Map

FR1: Epic 1 - Inscription via numéro WhatsApp
FR2: Epic 1 - Provisioning tenant isolé PostgreSQL
FR3: Epic 1 - Connexion JWT mobile et desktop
FR4: Epic 1 - Choix secteur d'activité à l'inscription
FR5: Epic 1 - Application template sectoriel
FR6: Epic 1 - Wizard d'onboarding en <5 minutes
FR7: Epic 1 - Tutoriels vidéo intégrés en français
FR8: Epic 1 - Création compte utilisateur-propriétaire (Owner)
FR9: Epic 1 - Création tables schéma tenant PostgreSQL
FR10: Epic 1 - Initialisation rôles par défaut (Owner, Employee)
FR11: Epic 1 - Pré-configuration catégories par template sectoriel
FR12: Epic 1 - Initialisation préférences notification par défaut
FR13: Epic 1 - Création première boutique par défaut
FR14: Epic 1 - Génération code unique tenant KV-XXXXXX
FR15: Epic 1 - Initialisation statut souscription (Plan Free)
FR16: Epic 1 - Application limites plan gratuit (3 boutiques, 500 produits, 5 employés)
FR17: Epic 1 - Message upgrade CTA quand limite atteinte
FR18: Epic 1 - Suspension automatique tenant expiré (lecture seule)
FR19: Epic 1 - Conservation données tenant suspendu
FR20: Epic 1 - Consultation statut souscription
FR21: Epic 2 - CRUD produits (nom, description, photo, référence)
FR22: Epic 2 - Définition variantes produit (taille, couleur)
FR23: Epic 2 - Saisie prix vente, achat et transport
FR24: Epic 2 - Calcul automatique marge nette
FR25: Epic 2 - Organisation produits par catégories flexibles
FR26: Epic 2 - Configuration seuil stock minimum par produit
FR27: Epic 2 - Historique complet mouvements de stock
FR28: Epic 2 - Import produits via CSV
FR29: Epic 2 - Consultation catalogue par employé
FR30: Epic 3 - Création et gestion boutiques + warehouse
FR31: Epic 3 - Vue centralisée stocks multi-boutiques temps réel
FR32: Epic 3 - Transfert stock inter-boutiques avec traçabilité
FR33: Epic 3 - Vérification dispo cross-boutique employé
FR34: Epic 3 - Warehouse comme point origine/distribution
FR35: Epic 3 - Assignation employé à boutique
FR36: Epic 3 - Restriction opérations employé à sa boutique
FR65: Epic 3 - Invitation employés par WhatsApp ou lien unique
FR66: Epic 3 - Gestion rôles utilisateurs (Propriétaire / Employé)
FR37: Epic 4 - Enregistrement vente (produits + quantités)
FR38: Epic 4 - Mode paiement (Cash ou Mobile Money)
FR39: Epic 4 - Application réductions sur une vente
FR40: Epic 4 - Vérification stock cross-boutique depuis POS
FR41: Epic 4 - Clôture journalière en 1 clic
FR42: Epic 4 - Calcul auto récapitulatif ventes du jour
FR43: Epic 4 - Historique ventes employé
FR44: Epic 4 - Leaderboard vendeurs par performance
FR45: Epic 6 - Lancement inventaire complet ou partiel
FR46: Epic 6 - Affichage stock théorique vs physique
FR47: Epic 6 - Calcul automatique écarts
FR48: Epic 6 - Validation et ajustement stock post-inventaire
FR49: Epic 6 - Rapport inventaire avec écarts
FR50: Epic 7 - Dashboard temps réel (stock, ventes, alertes, top produits)
FR51: Epic 7 - Rapport end-of-day configurable
FR52: Epic 7 - Rapport hebdomadaire automatique
FR53: Epic 7 - Contenu rapports (CA, bénéfices, marges, top/flop, boutiques, employés)
FR54: Epic 7 - Rentabilité par produit
FR55: Epic 7 - Export PDF/Excel (Plan Payant)
FR56: Epic 7 - Performances comparatives inter-boutiques
FR57: Epic 7 - Envoi rapport end-of-day auto via WhatsApp
FR58: Epic 7 - Rapport hebdomadaire via WhatsApp + SMS
FR59: Epic 7 - Rapport inventaire sur demande via WhatsApp
FR60: Epic 7 - Configuration types et fréquence rapports
FR61: Epic 8 - Alertes stock critique (push + WhatsApp)
FR62: Epic 8 - Notifications évolution ventes (pics, baisses)
FR63: Epic 7 - Message motivationnel quotidien au démarrage
FR64: Epic 8 - Alertes changement statut colis (Plan Payant)
FR67: Epic 8 - Consultation activité employés
FR68: Epic 8 - Désactivation employé + révocation sessions
FR69: Epic 5 - Opération 100% hors-ligne 7 jours
FR70: Epic 5 - Synchronisation auto au retour connectivité (<60s)
FR71: Epic 5 - Résolution conflits multi-device delta-based
FR72: Epic 1 - Stockage données critiques localement
FR73: Epic 5 - Garantie zéro perte de données
FR74: Epic 5 - Suspension accès après 7 jours sans connexion
FR75: Epic 1 - Indicateur visible statut connexion et dernière sync
FR76: Epic 5 - Sync temps réel entre appareils du même utilisateur
FR77: Epic 9 - Liste complète tenants avec statuts
FR78: Epic 9 - Activation/désactivation manuelle compte payant
FR79: Epic 9 - Dashboard revenus (MRR, conversion, churn)
FR80: Epic 9 - Analytics plateforme (ventes, boutiques, produits agrégés)
FR81: Epic 9 - Alertes proactives (J+5 sans sync, abonnement expirant)
FR82: Epic 9 - Notifications push globales
FR83: Epic 9 - Santé système (serveurs, sync, logs)
FR84: Epic 1 - Journal d'audit immuable (toutes modifications)
FR85: Epic 1 - Chiffrement données locales (SQLCipher)
FR86: Epic 1 - Devise unique XAF
FR87: Epic 2 - Gestion base clients
FR88: Epic 4 - Association client à une vente
FR89: Epic 2 - Gestion base fournisseurs
FR90: Epic 4 - Annulation/correction vente avec justification
FR91: Epic 8 - Suppression compte et données
FR92: Epic 8 - Feedback et signalement problème
FR93: Epic 1 - Rate limiting par tenant

## Epic List

### Epic 1: Foundation, Infrastructure & Authentication
Simon peut s'inscrire avec son numéro WhatsApp, obtenir son tenant isolé automatiquement, se connecter sur mobile et desktop, et voir l'état de sa connexion/sync. L'architecture monorepo Flutter + Spring Boot est initialisée avec les fondations hexagonales, multi-tenant, RBAC, subscription model, sécurité et audit.
**FRs couverts :** FR1–FR20, FR72, FR75, FR84–FR86, FR93

### Epic 2: Catalogue Produits
Simon peut construire et gérer son catalogue complet : produits, variantes, prix d'achat/vente/transport, marges automatiques, catégories, seuils d'alerte, import CSV, et sa base clients/fournisseurs.
**FRs couverts :** FR21–FR29, FR87, FR89

### Epic 3: Gestion Multi-Boutiques, Stock & Équipe
Simon peut créer et gérer plusieurs boutiques + warehouse, consulter les stocks de toutes ses boutiques en vue centralisée, transférer du stock entre boutiques, inviter ses employés, gérer leurs rôles et les assigner à leurs boutiques.
**FRs couverts :** FR30–FR36, FR65–FR66

### Epic 4: Point de Vente (POS)
Loïc peut enregistrer une vente en ≤3 taps, vérifier la dispo cross-boutique, accepter les paiements (cash/MoMo), appliquer des réductions, clôturer sa journée en 1 tap. Simon peut corriger ou annuler les ventes.
**FRs couverts :** FR37–FR44, FR88, FR90

### Epic 5: Moteur de Synchronisation Offline-First
L'app fonctionne 100% hors-ligne pendant 7 jours, synchronise automatiquement au retour en ligne (<60s), résout les conflits multi-device via delta-based sync, et garantit zéro perte de données.
**FRs couverts :** FR69–FR71, FR73–FR74, FR76

### Epic 6: Inventaire Assisté
Simon (et Loïc) peuvent lancer un inventaire guidé, comparer stock théorique vs physique, calculer les écarts automatiquement, et ajuster le stock en 1 clic.
**FRs couverts :** FR45–FR49

### Epic 7: Rapports, Dashboard & Communication WhatsApp
Simon reçoit automatiquement ses rapports end-of-day et hebdomadaires sur WhatsApp, consulte son dashboard matinal, suit la rentabilité par produit, et configure ses préférences.
**FRs couverts :** FR50–FR60, FR63

### Epic 8: Alertes, Notifications & Gestion Opérationnelle
Simon peut recevoir des alertes de stock critique et de tendances de ventes via push et WhatsApp, consulter l'activité de ses employés, désactiver et révoquer leurs sessions à distance, et les utilisateurs peuvent soumettre un feedback ou supprimer leur compte.
**FRs couverts :** FR61–FR62, FR64, FR67–FR68, FR91–FR92

### Epic 9: Super Admin Dashboard (Plateforme Keevo)
Toor peut piloter toute la plateforme : liste tenants, activation manuelle des payants, dashboard revenus, analytics plateforme, santé système, alertes proactives, et notifications push globales.
**FRs couverts :** FR77–FR83

---

## Epic 1: Foundation, Infrastructure & Authentication

Simon peut s'inscrire avec son numéro WhatsApp, obtenir son tenant isolé automatiquement, se connecter sur mobile et desktop, compléter son onboarding sectoriel en moins de 5 minutes, et voir en permanence l'état de sa connexion et de sa synchronisation. L'architecture hexagonale monorepo Flutter + Spring Boot est initialisée avec toutes les fondations techniques : multi-tenant, RBAC, subscription model, sécurité, audit et scaffold CI/CD.

### Story 1.1: Project Initialization & Architecture Scaffold

As a developer (Toor),
I want the full monorepo scaffold initialized with hexagonal architecture, CI/CD, and all technical foundations,
So that every subsequent story has a clean, consistent structure to build upon with zero rework.

**Acceptance Criteria:**

**Given** a fresh empty repository `keevo/`
**When** the project initialization story is completed
**Then** the monorepo structure exists with `backend/` (Spring Boot), `app/` (Flutter), `docker-compose.yml` (PostgreSQL + dev services), `.gitignore`, and `README.md`

**Given** the Flutter app is initialized
**When** running `flutter create --org com.keevo --project-name keevo --platforms android,ios,linux,windows --empty ./` inside `app/`
**Then** the project compiles and runs on Android, iOS, Linux, and Windows with zero errors
**And** the feature-first folder structure exists: `lib/core/` (di, network, storage, sync, auth, theme, router) and `lib/features/` (auth, pos, products, inventory, stores, reports, settings, onboarding)
**And** `pubspec.yaml` includes: `flutter_riverpod`, `riverpod_generator`, `go_router`, `drift`, `sqlite3_flutter_libs`, `flutter_secure_storage`, `freezed`, `build_runner`
**And** Material 3 `ThemeData` is configured with Indigo Sky palette tokens (`colorPrimary: #3B5BDB`, `colorSuccess: #51CF66`, `colorWarning: #FCC419`, `colorError: #FA5252`, dark surface `#0D1B2A`) in `lib/core/theme/`
**And** `ThemeMode.system` is set — dark mode follows system preferences automatically
**And** `go_router` is configured in `lib/core/router/` with placeholder routes for all features
**And** Inter font (Google Fonts) is configured with weights 300–700
**And** all domain layers (`domain/`, `data/`, `presentation/`) exist per feature with `.gitkeep` files

**Given** the Spring Boot backend is initialized
**When** Spring Initializr generates the project (Spring Boot 3.5.x, Java 21, Maven)
**Then** `backend/pom.xml` includes: Web, Data JPA, Security, Actuator, PostgreSQL, Flyway, Validation, Lombok
**And** the hexagonal domain structure exists for all 10 domains: `identity/`, `catalog/`, `commerce/`, `inventory/`, `store/`, `reporting/`, `messaging/`, `sync/`, `subscription/`, `admin/`
**And** each domain contains the sub-structure: `domain/model/`, `domain/port/in/`, `domain/port/out/`, `application/service/`, `adapter/in/rest/`, `adapter/out/persistence/`
**And** `shared/` contains: `infrastructure/security/` (stub), `infrastructure/persistence/` (TenantContext stub, MultiTenantConnectionProvider interface), `infrastructure/web/` (GlobalExceptionHandler stub), `domain/exception/DomainException.java`, `domain/model/Money.java` (XAF value object)
**And** `application.yml`, `application-dev.yml`, `application-prod.yml` exist with placeholder configuration
**And** `db/migration/tenant/` folder exists for Flyway per-tenant migrations

**Given** the sync infrastructure stubs are needed by all subsequent features
**When** the sync scaffold is created
**Then** `lib/core/sync/sync_service.dart` interface exists with methods `push()`, `pull()`, `queueOperation()`
**And** a Drift table `SyncQueue` exists with columns: `id` (UUID), `operation` (string), `payload` (JSON), `createdAt`, `synced` (bool)
**And** `sync/sync/domain/port/in/SyncUseCase.java` interface exists in backend with methods `push()` and `pull()`
**And** stub REST endpoints exist: `POST /api/v1/sync/push` and `GET /api/v1/sync/pull` returning `501 Not Implemented`

**Given** CI/CD pipelines are needed from day one
**When** GitHub Actions workflows are created
**Then** `.github/workflows/backend-ci.yml` runs `mvn test` on every push to `main` and `develop`
**And** `.github/workflows/flutter-ci.yml` runs `flutter test` and `flutter analyze` on every push
**And** both pipelines pass on the initial scaffold (zero tests, zero lint errors)

**Given** `docker-compose.yml` is needed for local development
**When** running `docker-compose up`
**Then** a PostgreSQL 16 instance starts on port 5432 with database `keevo_dev`, user `keevo`, password configurable via `.env`
**And** `backend/.env.example` documents all required environment variables

---

### Story 1.2: User Registration & Automated Tenant Provisioning

As a proprietor (Simon),
I want to create my account with my phone number and have my isolated workspace provisioned automatically,
So that I can start using Keevo immediately without any manual configuration.

**Acceptance Criteria:**

**Given** Simon opens Keevo for the first time
**When** he submits his phone number and password on the registration screen
**Then** the system creates his user account with role `OWNER`
**And** a unique tenant code `KV-XXXXXX` (6 alphanumeric characters, uppercase) is generated and stored
**And** a dedicated PostgreSQL schema `kv_xxxxxx` is created automatically via `TenantFactory`
**And** Flyway executes all tenant migrations on the new schema (tables: `products`, `stock_levels`, `sales`, `sale_items`, `stores`, `warehouses`, `users`, `roles`, `audit_log`, `sync_queue`, `notifications`, `subscriptions`)
**And** the `OWNER` and `EMPLOYEE` roles are initialized with their default permissions
**And** a default store named after the business (entered in onboarding) is created
**And** the subscription is initialized as `Plan Free` with limits: 3 stores, 500 products, 5 employees
**And** the JWT token for the new user contains `tenantId`, `userId`, `role` claims
**And** the response returns HTTP 201 with the tenant code and JWT token

**Given** two different proprietors register simultaneously
**When** both registrations complete
**Then** each has a completely isolated PostgreSQL schema — no shared tables, no cross-tenant data possible
**And** querying one tenant's schema returns zero results from the other tenant's data

**Given** Simon tries to register with a phone number already in use
**When** he submits the registration form
**Then** the system returns an error `{ "domainCode": "USER_ALREADY_EXISTS" }` with HTTP 409
**And** no schema or tenant is created

**Given** a registration fails mid-way (e.g., schema creation error)
**When** the error occurs
**Then** the entire provisioning is rolled back — no partial tenant exists
**And** the user account is not created

**Given** the Flutter app receives the successful registration response
**When** the JWT is received
**Then** the token is stored securely in `flutter_secure_storage` (never in SharedPreferences or plaintext)
**And** the app navigates to the onboarding wizard (Story 1.4)

---

### Story 1.3: JWT Authentication & Session Management

As a user (Simon or Loïc),
I want to log in securely with my phone number and password on any device,
So that I can access my workspace from mobile and desktop with my data protected.

**Acceptance Criteria:**

**Given** Simon has a registered account
**When** he submits correct credentials on the login screen
**Then** the system returns a JWT access token (RS256, expires 24h) and a refresh token (expires 30 days)
**And** the JWT payload contains: `userId`, `tenantId`, `role`, `iat`, `exp`
**And** all tokens are stored via `flutter_secure_storage`
**And** the `TenantJwtFilter` on the backend resolves `tenantId` from the JWT and sets `TenantContext` via `ThreadLocal` before every request

**Given** Simon's JWT has expired
**When** he makes any API request
**Then** the backend returns HTTP 401 with `{ "domainCode": "TOKEN_EXPIRED" }`
**And** the Flutter app automatically attempts a token refresh using the refresh token
**And** if refresh succeeds, the original request is retried transparently
**And** if refresh fails, the user is redirected to the login screen

**Given** Simon tries to log in with an incorrect password
**When** he submits the form
**Then** the system returns HTTP 401 with `{ "domainCode": "INVALID_CREDENTIALS" }`
**And** no token is issued
**And** after 5 consecutive failed attempts, the account is locked for 15 minutes

**Given** passwords are stored in the database
**When** a user account is created or password changed
**Then** the password is hashed with bcrypt at cost factor ≥ 12
**And** the plaintext password is never logged or stored anywhere

**Given** all API traffic between Flutter and backend
**When** any request is made
**Then** all data is transmitted over HTTPS (TLS 1.2+)
**And** the HTTP client in Flutter rejects connections without valid TLS certificates

**Given** Simon logs in on a second device (desktop)
**When** both sessions are active
**Then** both devices can operate independently with their own valid tokens

**Given** a `TenantJwtFilter` interceptor is active on all protected endpoints
**When** a request arrives without a valid JWT
**Then** the backend returns HTTP 401 immediately, before reaching any business logic
**And** the tenant schema is never touched

---

### Story 1.4: Onboarding Wizard — Sector Templates & Shop Setup

As a new proprietor (Simon),
I want to complete my initial setup in under 5 minutes by choosing my business type and naming my shop,
So that Keevo is immediately personalized for my sector with the right product categories ready to use.

**Acceptance Criteria:**

**Given** Simon has just registered and his tenant is provisioned
**When** the onboarding wizard launches
**Then** Screen 1 shows a visual grid of sector tiles with emoji icons:
  - 👗 Vêtements & Shopping
  - 📱 Électronique & Smartphones
  - 📚 Librairie & Fournitures Scolaires
  - 🏠 Électroménager & Cuisine
  - 🍎 Alimentation
  - 💊 Pharmacie
  - 🔧 Quincaillerie
  - ➕ Autre (custom)
**And** each tile has a ripple animation on tap
**And** selecting a sector highlights it with `#D0EBFF` background

**Given** Simon selects "👗 Vêtements & Shopping"
**When** he taps the tile and proceeds
**Then** the sector template is applied: default categories are created in his tenant schema (e.g., Hauts, Pantalons, Robes, Chaussures, Accessoires)
**And** the UI language, icons, and product field suggestions reflect the clothing sector
**And** this selection is stored in the tenant preferences

**Given** Simon is on Screen 2 (shop name)
**When** he types the name of his first store and taps "Terminer"
**Then** the store is created in his tenant schema with the provided name
**And** the onboarding completes in under 5 minutes total (measured from registration confirmation)
**And** the app navigates directly to the POS screen — ready to sell immediately

**Given** Simon skips adding products during onboarding
**When** he arrives on the POS screen for the first time
**Then** the app is fully functional with zero pre-loaded products
**And** a non-blocking empty state with illustration and CTA "Ajouter votre premier produit" is shown on the products screen
**And** Simon can create a product on-the-fly during his first sale (progressive adoption)

**Given** integrated French video tutorials are available
**When** Simon completes onboarding
**Then** a dismissible tooltip offers a 2-minute tutorial for each key module (POS, Stock, Rapports)
**And** tutorials are accessible at any time from Paramètres > Aide
**And** tutorials can be skipped without blocking any functionality

**Given** notification preferences need a default value
**When** tenant provisioning completes
**Then** end-of-day report time defaults to 20:00 local time
**And** stock alert notifications default to enabled
**And** Simon can change these in Paramètres at any time

---

### Story 1.5: Local Data Storage, Encryption & Connectivity Indicator

As a user (Simon or Loïc),
I want all my data stored securely on my device and to always know my connection status,
So that I can work with confidence whether online or offline, knowing my data is safe.

**Acceptance Criteria:**

**Given** the app is installed on a device
**When** Drift initializes the local database for the first time
**Then** the SQLite database is encrypted with SQLCipher (encryption at rest)
**And** the encryption key is generated on first launch and stored in `flutter_secure_storage`
**And** the database file is inaccessible without the key — opening it with a standard SQLite browser returns an error

**Given** all critical data entities
**When** they are stored locally via Drift
**Then** the following tables exist in the local database: `products`, `stock_levels`, `sales`, `sale_items`, `stores`, `sync_queue`, `users`, `categories`
**And** all monetary values are stored as integers (XAF, no decimals) — the `Money` value object enforces this rule
**And** all IDs are UUID v4 strings

**Given** the app is running on any screen
**When** the network state changes
**Then** the `SyncIndicator` component is always visible in the AppBar trailing position
**And** it displays one of four states:
  - 🟢 "En ligne" — connected and synced
  - 🔵 "Synchronisation..." — actively syncing (animated)
  - 🟡 "Hors-ligne — Jour X/7" — offline with days counter
  - 🔴 "Hors-ligne critique — Jour 6/7" — offline, warning threshold reached (day 5+)
**And** the indicator updates within 3 seconds of a network state change
**And** tapping the indicator shows a bottom sheet with last sync timestamp and a "Synchroniser maintenant" button (disabled if offline)

**Given** the app is offline
**When** any data-writing operation is performed (sale, stock adjustment, transfer)
**Then** the operation is queued in the local `sync_queue` Drift table with: `id` (UUID), `operation` type, `payload` (JSON), `createdAt`, `synced: false`
**And** the UI confirms the operation succeeded locally with the standard success SnackBar
**And** the user receives no error or warning about being offline — the operation feels identical to online mode

**Given** the app size constraints
**When** the Flutter app is built in release mode
**Then** the APK/IPA size does not exceed 100 MB (excluding user data)
**And** the app runs at ≥ 30 FPS on a device with 2 GB RAM (tested on Samsung Galaxy A14 equivalent)

---

### Story 1.6: Subscription Model, Plan Limits & Account Lifecycle

As a proprietor (Simon),
I want to understand my plan limits clearly and manage my account lifecycle,
So that I know what I can do on the free plan and have a clear path to upgrade when I'm ready.

**Acceptance Criteria:**

**Given** Simon is on the Free plan
**When** he attempts to create a 4th store (limit: 3)
**Then** a bottom sheet appears with the message "Vous avez atteint la limite de 3 boutiques sur votre plan gratuit"
**And** a prominent CTA button "Passer au plan Premium" is shown
**And** the store is NOT created
**And** the same enforcement applies for products (limit: 500) and employees (limit: 5)

**Given** plan limits are enforced
**When** the backend receives a request to create an entity that would exceed the limit
**Then** the backend returns HTTP 403 with `{ "domainCode": "PLAN_LIMIT_EXCEEDED", "details": { "limit": 3, "current": 3, "entity": "stores" } }`
**And** limits are checked server-side on every creation request — they cannot be bypassed from the client

**Given** Simon's paid subscription expires
**When** the expiration date passes
**Then** the system automatically sets the tenant status to `SUSPENDED`
**And** all write operations (sales, stock changes, new products) return HTTP 403
**And** read operations (view stock, view history) continue to work
**And** Simon's data is fully preserved — nothing is deleted
**And** a banner is shown at the top of the app: "Votre abonnement a expiré. Vos données sont conservées."

**Given** Simon's account is suspended
**When** Toor (Super Admin) manually activates his paid plan
**Then** the tenant status changes to `ACTIVE` within 5 minutes
**And** all write operations are restored immediately
**And** Simon receives a WhatsApp notification confirming reactivation

**Given** Simon navigates to Paramètres > Souscription
**When** the screen loads
**Then** he sees his current plan (Free / Premium), status (Actif / Expiré / Suspendu), expiry date (if Premium), and usage (e.g., "2/3 boutiques, 87/500 produits, 3/5 employés")

**Given** the rate limiting rule
**When** a tenant makes more than 100 API requests in one minute
**Then** subsequent requests return HTTP 429 with `{ "domainCode": "RATE_LIMIT_EXCEEDED" }`
**And** the limit resets after 60 seconds
**And** other tenants are completely unaffected

---

### Story 1.7: Immutable Audit Trail & Security Domain Foundation

As a proprietor (Simon),
I want every modification to my data to be permanently recorded with full traceability,
So that I can trust my data is accurate, disputes are resolvable, and my employees are protected by total transparency.

**Acceptance Criteria:**

**Given** any write operation occurs in the system (stock change, price update, sale, transfer, user modification)
**When** the operation is committed to the database
**Then** a domain event `{Entity}{PastTense}Event` is emitted (e.g., `StockAdjustedEvent`, `SaleCompletedEvent`, `ProductPriceUpdatedEvent`)
**And** the `AuditEventListener` captures the event and writes an immutable entry to the `audit_log` table with: `id` (UUID), `tenantId`, `userId`, `entityType`, `entityId`, `action`, `valueBefore` (JSON), `valueAfter` (JSON), `occurredAt` (ISO 8601 UTC)
**And** this happens synchronously within the same transaction — if the main operation fails, no audit entry is written

**Given** an audit entry has been written
**When** any user (Owner, Employee, or Super Admin) attempts to update or delete it via any API endpoint
**Then** the backend returns HTTP 403 with `{ "domainCode": "AUDIT_IMMUTABLE" }`
**And** no modification is possible — the audit log is append-only at the database level (row-level security or trigger)

**Given** Simon views the stock movement history for a product (FR27)
**When** the history screen loads
**Then** he sees all audit entries for that product: entry type (Vente, Transfert, Ajustement, Entrée stock), quantity change, actor name, timestamp, and before/after values
**And** entries are displayed in reverse chronological order

**Given** tenant data isolation is a non-negotiable security requirement
**When** a request arrives with a valid JWT for tenant `kv_000001`
**Then** the `TenantContext` is set to `kv_000001` via `ThreadLocal` before any repository call
**And** all JPA queries automatically use the `kv_000001` schema (via `MultiTenantConnectionProvider`)
**And** it is architecturally impossible for a query from tenant `kv_000001` to read or write data from `kv_000002`
**And** an automated integration test verifies this isolation: two tenants with identical product IDs — querying one never returns data from the other

**Given** the `GlobalExceptionHandler` is active
**When** any unhandled exception or domain exception occurs
**Then** the response always follows the standard error format: `{ "error": "...", "code": "HTTP_STATUS", "domainCode": "DOMAIN_SPECIFIC_CODE", "details": { ... }, "timestamp": "ISO8601" }`
**And** stack traces are never exposed in API responses (only logged server-side)
**And** user-facing error messages are in French, human-readable, with no technical jargon

---

## Epic 2: Catalogue Produits & Base Fournisseurs/Clients

Simon peut gérer son catalogue complet — créer, modifier, archiver ses produits avec photos, configurer les prix avec calcul automatique des marges, surveiller ses seuils de stock, importer en masse via CSV, et gérer sa base clients et fournisseurs, le tout disponible offline et synchronisé automatiquement.

**FRs couverts :** FR21–FR29, FR87, FR89

---

### Story 2.1: CRUD Produits — Création, Édition & Archivage

As a proprietor (Simon),
I want to create, edit, and archive products with photos, categories, SKU references, and variants,
So that my catalogue is always up to date and every product is easy to find and identify.

**Acceptance Criteria:**

**Given** Simon is in the Catalogue module
**When** he taps the "Ajouter un produit" FAB
**Then** a product creation form appears with fields: Nom (required), Description (optional), Référence SKU (auto-generated as `KEV-{6 alphanumeric}`, editable), Catégorie (dropdown from tenant categories), Photo, Variantes (toggle)
**And** the form validates inline — name field shows error immediately if left empty on blur
**And** the form is functional offline — no network required to open or fill it

**Given** Simon fills in the product name and taps "Enregistrer"
**When** the form is submitted
**Then** the product is created locally in the Drift `products` table with a UUID v4 `id`, `tenantId`, `name`, `sku`, `categoryId`, `createdAt`, `updatedAt`, `archived: false`
**And** the operation is queued in `sync_queue` if offline, or synced immediately if online
**And** a success SnackBar "Produit créé" appears
**And** the new product appears immediately in the product list without requiring a refresh

**Given** Simon wants to add a photo
**When** he taps the photo field
**Then** a bottom sheet offers two options: "Prendre une photo" (camera) and "Choisir dans la galerie" (gallery)
**And** the selected image is compressed to ≤ 500 KB before storage (JPEG quality 80)
**And** the image is stored locally and queued for upload to S3 on sync
**And** if no photo is provided, a placeholder with the product's initials is shown

**Given** Simon's sector is "Vêtements & Shopping"
**When** he toggles the "Variantes" option on a product
**Then** he can add variant axes: Taille (XS, S, M, L, XL, XXL — editable list) and/or Couleur (color picker with hex value)
**And** each variant combination creates a distinct `stock_level` row (e.g., product `Robe` × taille `M` × couleur `Rouge`)
**And** the product card in the list shows "X variantes" badge

**Given** Simon wants to edit an existing product
**When** he taps a product card and then the edit icon
**Then** the same form opens pre-populated with all existing values
**And** changes are saved locally on "Enregistrer" with `updatedAt` refreshed
**And** an audit event `ProductUpdatedEvent` is emitted with `valueBefore` and `valueAfter`

**Given** Simon wants to archive a product (no soft delete — data preservation rule)
**When** he long-presses a product card and selects "Archiver"
**Then** a confirmation dialog appears: "Archiver ce produit ? Il ne sera plus visible dans le POS mais son historique est conservé."
**And** on confirmation: `archived: true` is set, the product disappears from all POS product lists
**And** the product remains visible in Catalogue > Archivés
**And** an audit event `ProductArchivedEvent` is emitted
**And** no product is ever permanently deleted — only archived

**Given** Simon searches for a product
**When** he types in the Catalogue search bar
**Then** results filter in real-time (debounce 300ms) matching on name, SKU, or category
**And** the search works fully offline against local Drift data

---

### Story 2.2: Moteur de Prix & Calcul de Marge

As a proprietor (Simon),
I want to set purchase price, transport costs, and selling price with automatic margin calculation,
So that I always know my profitability per product and can price intelligently.

**Acceptance Criteria:**

**Given** Simon is creating or editing a product
**When** he fills in the pricing section
**Then** three fields are available: "Prix d'achat" (XAF integer, required), "Coût de transport" (XAF integer, optional, default 0), "Prix de vente catalogue" (XAF integer, required)
**And** all monetary inputs show a "FCFA" suffix label and reject decimal input (integers only)
**And** the `Money` value object is enforced both on the client (Freezed DTO validation) and on the server (domain model constructor)

**Given** Simon enters: Prix d'achat = 5000, Coût de transport = 500, Prix de vente = 8000
**When** any of the three values changes
**Then** the margin is calculated and displayed in real-time:
  - Coût total = 5000 + 500 = 5500 FCFA
  - Marge brute = 8000 - 5500 = 2500 FCFA
  - Marge % = (2500 / 5500) × 100 = 45,5%
**And** the margin indicator uses color coding: green (≥ 20%), orange (10–19%), red (< 10%)
**And** if the selling price is lower than the purchase cost, a warning banner appears: "⚠ Prix de vente inférieur au coût — vous vendez à perte"

**Given** the price-in-cart editable rule (UX10)
**When** Loïc (employee) processes a sale in the POS
**Then** he can modify the unit price of an item directly in the cart for that specific transaction
**And** this temporary override does NOT modify the catalogue price of the product
**And** the audit log records: `SalePriceOverriddenEvent` with `cataloguePrice`, `appliedPrice`, `actorId`, `saleId`
**And** Simon can see all price overrides in the sale history per transaction

**Given** a product has been created with a catalogue price
**When** Simon updates the catalogue price later
**Then** existing completed sales are NOT retroactively modified — their stored `appliedPrice` is preserved
**And** only future sales use the new catalogue price as the default

**Given** all monetary values in the system
**When** any calculation is performed (margin, total, change)
**Then** the result is always an integer (XAF — no centimes, no decimals, no floating point)
**And** rounding uses standard mathematical rounding (0.5 → 1) if intermediate calculation produces a decimal

---

### Story 2.3: Seuils de Stock & Historique des Mouvements

As a proprietor (Simon),
I want to set minimum stock thresholds per product and see the complete movement history,
So that I never run out of stock unexpectedly and can trace every change with full accountability.

**Acceptance Criteria:**

**Given** Simon is on a product's detail page
**When** he navigates to the "Stock" tab
**Then** he sees the current stock level (or per-variant breakdown), the configured alert threshold ("Seuil minimum"), and a "Modifier le seuil" button

**Given** Simon sets a minimum threshold of 10 units on a product
**When** the stock level drops to ≤ 10 (via sale, transfer, or manual adjustment)
**Then** the system emits a `StockThresholdBreachedEvent`
**And** a push notification is sent to Simon's device: "⚠ Stock bas : [Product Name] — il vous reste [current] unité(s) (seuil : [threshold])"
**And** the product card in Catalogue shows a red "Stock bas" badge
**And** the threshold check happens server-side on every stock-modifying operation — it cannot be bypassed client-side

**Given** any stock-modifying operation occurs (sale, stock entry, transfer between stores, manual adjustment)
**When** the operation is committed
**Then** a `stock_movement` audit entry is written with: `productId`, `variantId` (if applicable), `storeId`, `movementType` (SALE / STOCK_ENTRY / TRANSFER_IN / TRANSFER_OUT / ADJUSTMENT), `quantityBefore`, `quantityChange`, `quantityAfter`, `actorId`, `occurredAt`, `notes` (optional)

**Given** Simon navigates to the stock history for a specific product
**When** the history screen loads
**Then** all movements are displayed in reverse chronological order
**And** each entry shows: type icon (🛍 Vente / 📦 Entrée / 🔄 Transfert / ✏ Ajustement), product name + variant, quantity change (e.g., "−3"), quantity after, actor name, date/time
**And** Simon can filter by: movement type, date range (last 7 days default), store
**And** the history loads offline from local Drift data

**Given** Simon performs a manual stock adjustment (e.g., shrinkage, breakage)
**When** he taps "Ajuster le stock" and enters a new quantity with a reason
**Then** the adjustment is recorded as `ADJUSTMENT` in the history
**And** the reason is stored in the `notes` field
**And** the `AuditEventListener` emits a `StockAdjustedEvent` with before/after values

---

### Story 2.4: Import CSV & Création Progressive de Produits

As a proprietor (Simon),
I want to import my existing product catalogue from a CSV file and create products on-the-fly during a sale,
So that I can onboard my full inventory rapidly and never block a sale because a product isn't in the system yet.

**Acceptance Criteria:**

**Given** Simon navigates to Catalogue > Importer
**When** he uploads a CSV file
**Then** the system parses the file and displays a column-mapping screen: each CSV column is matched to a Keevo field (Nom, Prix d'achat, Prix de vente, Catégorie, SKU, Quantité initiale)
**And** unrecognized columns can be ignored or mapped manually
**And** the system previews the first 5 rows with the applied mapping before import

**Given** Simon confirms the column mapping and taps "Lancer l'import"
**When** the import runs
**Then** each row is validated independently: required fields (Nom, Prix de vente) must be non-empty, Prix values must be positive integers
**And** rows with errors are skipped and listed in a post-import error report: "Ligne 12 : Prix de vente manquant"
**And** valid rows are imported successfully — partial import is allowed (not all-or-nothing)
**And** a success summary is shown: "🎉 127 produits importés, 3 erreurs — voir le rapport"

**Given** the import exceeds the plan's product limit (e.g., Free plan = 500 products, Simon has 450, imports 100)
**When** the 500-product limit is reached during import
**Then** import stops at the limit and returns: "Limite atteinte : 50 produits importés sur 100. Passez au plan Premium pour continuer."
**And** the 50 successfully imported products are preserved
**And** the error report includes all rows that were skipped due to the limit

**Given** Simon is processing a sale in the POS and scans/enters a product name that doesn't exist
**When** no matching product is found in the Catalogue
**Then** a bottom sheet appears: "Produit introuvable — Créer '[searched name]' à la volée ?"
**And** tapping "Créer" opens a minimal product form pre-filled with the searched name: Prix de vente (required), Catégorie (required), Quantité (for this sale)
**And** on save, the product is created as a `draft` (visible in Catalogue with a "Brouillon" badge) and immediately added to the cart
**And** Simon can complete the product's details (photo, purchase price, description) later from the Catalogue
**And** a `ProductCreatedProgressivelyEvent` is emitted in the audit log

**Given** a CSV template is needed for Simon to prepare his data
**When** he taps "Télécharger le modèle CSV"
**Then** a pre-formatted CSV template is downloaded with headers: `nom`, `prix_achat`, `cout_transport`, `prix_vente`, `categorie`, `sku`, `quantite_initiale`, `seuil_min`
**And** the template includes 3 example rows with sample data

---

### Story 2.5: Gestion Clients & Fournisseurs

As a proprietor (Simon),
I want to maintain a client directory and a supplier directory linked to my products,
So that I can track purchase history per client, contact my suppliers easily, and manage B2B relationships.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Clients
**When** he taps "Ajouter un client"
**Then** a form appears with: Nom (required), Téléphone (required, WhatsApp-compatible format), Email (optional), Notes (optional, free text)
**And** the client is created in the local `clients` Drift table with UUID, `tenantId`, `createdAt`
**And** the client appears in the searchable client list sorted by name

**Given** Simon has created clients
**When** he views the client directory
**Then** he can search by name or phone number (real-time filter, debounce 300ms)
**And** each client card shows: name, phone, total number of purchases (count of sales linked to this client), total amount spent (sum of linked sales in XAF)
**And** tapping a client opens their profile with the full purchase history list

**Given** Loïc processes a sale in the POS
**When** he selects a client for that sale (optional field)
**Then** the sale is linked to the client (`clientId` stored on the `sales` record)
**And** the client's purchase history and total spend are updated immediately in the local database
**And** anonymous sales (no client selected) are allowed — client is optional

**Given** Simon navigates to Paramètres > Fournisseurs
**When** he taps "Ajouter un fournisseur"
**Then** a form appears with: Nom (required), Téléphone (required), Email (optional), Produits fournis (multi-select from Catalogue)
**And** the supplier is saved in the `suppliers` Drift table

**Given** Simon links a supplier to a product
**When** he views the product's detail page
**Then** a "Fournisseur" field shows the linked supplier name with a tap-to-call shortcut
**And** from the supplier's profile, he can see all products sourced from that supplier

**Given** all client and supplier data
**When** the app goes offline and Simon creates or edits a client/supplier
**Then** the change is saved locally and queued in `sync_queue`
**And** on sync, the backend persists the change in the tenant's `clients` and `suppliers` tables
**And** no client or supplier is ever permanently deleted — only archived (`archived: true`)

---

## Epic 3: Gestion Multi-Boutiques, Stock & Équipe

Simon peut créer et gérer plusieurs boutiques et un warehouse principal, consulter les stocks de toutes ses boutiques en vue centralisée, transférer du stock entre boutiques avec traçabilité complète, inviter ses employés via WhatsApp, gérer leurs rôles et les assigner à leurs boutiques avec des droits limités.

**FRs couverts :** FR30–FR36, FR65–FR66

---

### Story 3.1: Création & Configuration des Boutiques et Warehouse

As a proprietor (Simon),
I want to create multiple stores and a central warehouse with full configuration,
So that I can structure my business across locations and use the warehouse as my primary distribution point.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Boutiques
**When** he taps "Ajouter une boutique"
**Then** a creation form appears with: Nom (required), Adresse (optional), Téléphone (optional), Type (Boutique / Warehouse — radio, default: Boutique)
**And** on save, the store is created in the tenant's `stores` table with: `id` (UUID), `tenantId`, `name`, `address`, `type` (STORE / WAREHOUSE), `active: true`, `createdAt`
**And** the store appears immediately in the store list
**And** the operation is queued in `sync_queue` if offline

**Given** the tenant is on the Free plan (limit: 3 stores)
**When** Simon attempts to create a 4th store
**Then** the creation is blocked: HTTP 403 `{ "domainCode": "PLAN_LIMIT_EXCEEDED", "details": { "entity": "stores", "limit": 3, "current": 3 } }`
**And** the Flutter app shows a bottom sheet with the current count and a CTA "Passer au plan Premium"

**Given** each tenant can have exactly one warehouse
**When** Simon tries to create a second store with type WAREHOUSE
**Then** the system returns HTTP 409 `{ "domainCode": "WAREHOUSE_ALREADY_EXISTS" }`
**And** a clear error message is shown: "Vous avez déjà un warehouse. Un seul warehouse est autorisé par compte."

**Given** Simon wants to edit a store
**When** he taps the store card and selects "Modifier"
**Then** the form opens pre-populated with existing values
**And** name, address, and phone can be modified
**And** store type (STORE / WAREHOUSE) cannot be changed after creation
**And** changes are saved with `updatedAt` refreshed and an `StoreUpdatedEvent` emitted

**Given** Simon wants to deactivate a store (no deletion — data preservation)
**When** he long-presses the store card and selects "Désactiver"
**Then** a confirmation dialog appears: "Désactiver [Store Name] ? Les données et l'historique sont conservés."
**And** `active: false` is set — the store disappears from POS store selectors and stock views
**And** employees assigned to that store lose access to it and are shown "Boutique désactivée"
**And** a `StoreDeactivatedEvent` is emitted in the audit log

**Given** Simon navigates to the warehouse (if it exists)
**When** he views the warehouse detail screen
**Then** he sees: stock levels of all products in the warehouse, total product count, list of recent stock entries
**And** he can initiate a transfer FROM the warehouse to any active store (see Story 3.3)

---

### Story 3.2: Vue Centralisée des Stocks Multi-Boutiques

As a proprietor (Simon),
I want a single consolidated view of my stock levels across all stores and the warehouse,
So that I can immediately spot where stock is critical, abundant, or imbalanced without navigating store by store.

**Acceptance Criteria:**

**Given** Simon navigates to Stock > Vue Globale
**When** the centralized stock screen loads
**Then** a list of all active stores + warehouse is shown as expandable cards
**And** each card shows: store name, total distinct product count, total stock value in XAF (sum of `unitPrice × quantity` per store), and a "stock bas" badge count (products below threshold)
**And** the data loads from local Drift cache — fully functional offline

**Given** Simon expands a store card
**When** the store stock detail unfolds
**Then** a paginated list of all products with stock in that store is shown: name, variant (if any), current quantity, threshold, status badge (✅ Normal / ⚠ Bas / 🔴 Critique)
**And** products below threshold are sorted to the top automatically

**Given** Simon wants to compare stock of one product across all stores
**When** he searches for a product in the stock search bar
**Then** results show the product once per store/warehouse with its quantity in each location
**And** the search is real-time against local Drift data (debounce 300ms, offline-capable)

**Given** the centralized stock view needs to stay accurate
**When** a sync completes (push or pull)
**Then** the centralized view refreshes automatically within 3 seconds
**And** any store that had stock changes since last view shows a subtle "Mis à jour" indicator

**Given** Simon is on desktop (expanded breakpoint)
**When** he views the centralized stock screen
**Then** stores are displayed in a responsive grid layout (2 columns on medium, 3 on expanded breakpoints)
**And** the same data is available as on mobile — no feature degradation on desktop

---

### Story 3.3: Transferts Inter-Boutiques avec Traçabilité Complète

As a proprietor (Simon),
I want to transfer stock between stores and from the warehouse to stores with full traceability,
So that I can rebalance inventory across locations and always know exactly where each unit went.

**Acceptance Criteria:**

**Given** Simon initiates a stock transfer
**When** he taps "Nouveau transfert" in the Stock module
**Then** a transfer form appears with: Source (dropdown of active stores + warehouse), Destination (dropdown — excludes Source), Product (searchable from Catalogue), Variant (if applicable), Quantity (positive integer)

**Given** Simon fills in the transfer form and taps "Confirmer"
**When** the transfer is submitted
**Then** the backend validates: source store has sufficient quantity (`currentQty ≥ requestedQty`)
**And** if validation passes: source stock is decremented by the transferred quantity, destination stock is incremented by the same quantity — atomically in a single transaction
**And** a `StockTransferredEvent` is emitted with: `sourceStoreId`, `destinationStoreId`, `productId`, `variantId`, `quantity`, `actorId`, `occurredAt`
**And** two `stock_movement` audit entries are created: one TRANSFER_OUT on source, one TRANSFER_IN on destination
**And** a success SnackBar: "Transfert effectué : [qty] × [product] → [destination store]"

**Given** Simon tries to transfer more units than available
**When** he submits the form with quantity exceeding source stock
**Then** the backend returns HTTP 422 `{ "domainCode": "INSUFFICIENT_STOCK", "details": { "available": 5, "requested": 10 } }`
**And** the Flutter form shows an inline error below the quantity field: "Stock insuffisant — disponible : 5 unités"

**Given** the app is offline when Simon confirms a transfer
**When** the form is submitted while offline
**Then** the transfer is validated locally against the Drift stock data
**And** the operation is queued in `sync_queue` with type `STOCK_TRANSFER`
**And** source and destination stock levels are updated locally immediately
**And** the transfer is visible in the transfer history with a "En attente de sync" badge
**And** on reconnection, the server re-validates and either commits or reports a conflict

**Given** Simon views the transfer history
**When** he navigates to Stock > Historique des transferts
**Then** all transfers are listed in reverse chronological order with: date, source → destination, product + variant, quantity, actor name, status (Completed / Pending Sync / Conflict)
**And** he can filter by date range, store, or product
**And** the history is available offline from local Drift data

---

### Story 3.4: Vérification de Disponibilité Cross-Boutique

As an employee (Loïc),
I want to check if a product is available in other stores of the network,
So that I can offer the customer an alternative location when my store is out of stock.

**Acceptance Criteria:**

**Given** Loïc is processing a sale in the POS and a product shows zero stock
**When** he taps "Vérifier dans les autres boutiques" on the out-of-stock product
**Then** a bottom sheet shows the stock level of that product across all stores in the tenant: store name, quantity, distance indicator (if location data available)
**And** stores with zero stock are greyed out
**And** this cross-store check works offline using local Drift data (last sync values)

**Given** Loïc views the cross-store availability sheet
**When** the network is available
**Then** the data is refreshed from the server before display (max 3s timeout — falls back to local data on timeout)
**And** a "Dernière mise à jour : [timestamp]" indicator is shown

**Given** an employee is assigned to only one store (Story 3.5)
**When** Loïc views the cross-store availability
**Then** he can SEE stock levels in other stores but CANNOT initiate a transfer himself
**And** the bottom sheet shows: "Contacter le propriétaire pour initier un transfert"
**And** a one-tap WhatsApp shortcut to Simon's number is provided

**Given** the cross-store check is available to both OWNER and EMPLOYEE roles
**When** Simon (OWNER) views it
**Then** he sees an additional "Initier un transfert" CTA button directly in the bottom sheet
**And** tapping it pre-fills the transfer form (Story 3.3) with source = store with highest stock and destination = current store

---

### Story 3.5: Invitation Employés, Rôles & Assignation Boutique

As a proprietor (Simon),
I want to invite my employees via WhatsApp or a unique link, assign them to specific stores, and manage their roles,
So that each employee has access only to their assigned store and I maintain full control over permissions.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Équipe
**When** he taps "Inviter un employé"
**Then** a form appears with: Prénom + Nom (required), Numéro WhatsApp (required), Boutique assignée (required — dropdown of active stores), Rôle (EMPLOYEE — currently the only non-owner role)
**And** on submit, the system generates a unique invitation token (UUID, expires in 48h)
**And** an invitation WhatsApp message is sent to the provided number via `WhatsAppPort` with: "Bonjour [Name], Simon vous invite à rejoindre [Business Name] sur Keevo. Cliquez ici pour créer votre accès : [invitation link]"
**And** if WhatsApp delivery fails, a fallback invitation link is shown to Simon for manual sharing

**Given** Free plan limit is 5 employees
**When** Simon attempts to invite a 6th employee
**Then** the invite is blocked: HTTP 403 `{ "domainCode": "PLAN_LIMIT_EXCEEDED", "details": { "entity": "employees", "limit": 5, "current": 5 } }`
**And** the Flutter app shows the upgrade bottom sheet

**Given** Loïc receives the invitation link and opens it
**When** he taps the link and the app opens (or web fallback)
**Then** he is prompted to set his password (minimum 8 characters, must include at least one number)
**And** this first login triggers a forced password change flow — Loïc cannot use the app until he sets his own password
**And** the invitation token is consumed (single-use — cannot be reused after first activation)
**And** Loïc's account is created with role `EMPLOYEE`, assigned to the specified store, status `ACTIVE`
**And** a `EmployeeInviteAcceptedEvent` is emitted in the audit log

**Given** Loïc's account is active and he logs in
**When** he accesses any feature
**Then** he is limited to operations on his assigned store only — his JWTs `storeId` claim restricts all backend queries
**And** stock views, POS, and inventory show ONLY data for his assigned store
**And** he cannot view reports, manage products catalogue, create stores, or invite other employees
**And** the navigation menu hides all OWNER-only sections automatically based on his role

**Given** Simon wants to reassign an employee to a different store
**When** he taps an employee's name and selects "Modifier l'assignation"
**Then** a dropdown shows all active stores and he can select a new one
**And** on save, the employee's `storeId` is updated
**And** next time Loïc makes any API request, the server validates the updated assignment via the refreshed JWT (forced re-login if token was issued before the change)
**And** a `EmployeeStoreReassignedEvent` is emitted

**Given** the invitation was sent but Loïc hasn't accepted after 48 hours
**When** Simon views the team list
**Then** the pending invitation shows a "Expiré" badge
**And** Simon can tap "Renvoyer l'invitation" to generate a new token and resend the WhatsApp message
**And** the expired token is invalidated immediately on regeneration

**Given** Simon needs to revoke an employee's access immediately
**When** he taps the employee and selects "Désactiver l'accès"
**Then** the employee's status is set to `INACTIVE`
**And** all active sessions for that employee are invalidated within 5 minutes (JWT blacklist or refresh token revocation)
**And** next API request from that employee returns HTTP 401 `{ "domainCode": "ACCOUNT_INACTIVE" }`
**And** a `EmployeeDeactivatedEvent` is emitted in the audit log

---

## Epic 4: Point de Vente (POS)

Loïc peut enregistrer une vente en ≤3 taps avec feedback < 200ms, vérifier la disponibilité cross-boutique, accepter les paiements cash ou Mobile Money, appliquer des réductions, clôturer sa journée en 1 tap avec résumé automatique. Simon peut corriger ou annuler toute vente avec justification obligatoire et réajustement automatique du stock.

**FRs couverts :** FR37–FR44, FR88, FR90

---

### Story 4.1: Enregistrement de Vente & Flux POS Core

As an employee (Loïc),
I want to record a sale in 3 taps maximum with immediate feedback,
So that I can serve customers rapidly without slowing down the queue or returning to paper.

**Acceptance Criteria:**

**Given** Loïc opens the app
**When** the app loads
**Then** the POS screen is the default landing screen — zero navigation required to start a sale
**And** a search bar is displayed at the top with a fuzzy product search placeholder
**And** a grid of frequently used and recently sold products is shown below (up to 12 cards, ordered by sales frequency)
**And** the `CartPill` component is hidden at the bottom (appears only when cart has ≥ 1 item)

**Given** Loïc types in the search bar
**When** he enters at least 2 characters
**Then** fuzzy search results appear in under 500ms against the local Drift `products` table
**And** results show: product photo (or initials placeholder), name, price in XAF, stock level badge
**And** out-of-stock products (quantity = 0) appear at the bottom with a grey "⚠️ Rupture" overlay and a "Vérifier autres boutiques" action

**Given** Loïc taps a product card
**When** the product is added to the cart
**Then** the product card glides into the CartPill with a subtle animation (duration < 200ms)
**And** a light haptic vibration is triggered
**And** the card flashes `#D0EBFF` (selected state) for 150ms then reverts to normal
**And** the CartPill appears (or updates) showing: nb of items, total in XAF, "Encaisser" button
**And** the cart total updates in real-time

**Given** Loïc has items in the cart
**When** he taps the CartPill to expand it
**Then** a bottom sheet shows all cart items: product name + variant, quantity (+/- controls), applied unit price (tappable to edit — Story 4.2), subtotal per line
**And** the grand total is displayed prominently at the bottom
**And** he can swipe left on any item to delete it from the cart

**Given** Loïc taps "Encaisser"
**When** the payment screen appears
**Then** two payment mode buttons are displayed: "💵 Espèces" and "📱 Mobile Money"
**And** a client selector field is shown (optional — autocomplete from client directory, Story 2.5)
**And** for Espèces: a "Montant reçu" field appears, and the system calculates and displays change: `Monnaie rendue = montant_reçu - total` in XAF
**And** for Mobile Money: a reference number field appears (optional, for record-keeping)

**Given** Loïc confirms the payment
**When** he taps "Valider la vente"
**Then** the sale is recorded in the local Drift `sales` table with: `id` (UUID), `tenantId`, `storeId`, `actorId`, `clientId` (nullable), `paymentMode` (CASH / MOBILE_MONEY), `totalAmount` (XAF integer), `status: COMPLETED`, `occurredAt`
**And** each cart item is recorded in `sale_items` with: `productId`, `variantId`, `quantity`, `appliedUnitPrice`, `subtotal`
**And** stock levels are decremented in Drift for each sold product/variant in the assigned store
**And** a `SaleCompletedEvent` is emitted — the audit log captures the full sale payload
**And** the operation is queued in `sync_queue` if offline
**And** the success screen appears: "✅ Vente enregistrée — [total] FCFA" with an animated lime-green confirmation (< 200ms)
**And** after 1.5 seconds, the POS screen resets to empty — ready for the next sale
**And** the entire flow from first tap to success confirmation takes ≤ 5 seconds for a 1-product sale

**Given** a product has no photo
**When** it is displayed on the POS grid or in cart
**Then** a coloured circle with the product's initials (first 2 letters, uppercase) is shown as placeholder
**And** the colour is deterministically derived from the product name (consistent across sessions)

---

### Story 4.2: Réductions & Prix Modifiable en Panier

As an employee (Loïc),
I want to modify the price of an item in the cart and apply discounts without touching the catalogue price,
So that I can handle negotiations, promotions, and special prices for loyal customers in real time.

**Acceptance Criteria:**

**Given** Loïc is viewing the expanded cart (Story 4.1)
**When** he taps the unit price of any cart item
**Then** an inline numeric editor appears directly in the cart row (no modal, no navigation)
**And** the field shows the current applied price pre-filled in XAF
**And** the keyboard is numeric — no decimal input allowed
**And** leaving the field or tapping the checkmark confirms the override

**Given** Loïc enters a custom price (e.g., 4500 instead of 5000)
**When** the price is confirmed
**Then** the cart item now shows `4 500 FCFA` as the applied price with a red accent label "Prix modifié"
**And** the catalogue price of the product in the `products` table is NOT modified
**And** the line subtotal and grand total update immediately
**And** a `SalePriceOverriddenEvent` will be emitted on sale completion with: `cataloguePrice`, `appliedPrice`, `delta`, `actorId`, `saleId`

**Given** Loïc wants to apply a percentage discount on the entire order
**When** he taps "Réduction" in the cart bottom sheet header
**Then** a small sheet appears offering two options: "% sur le total" and "Montant fixe"
**And** for % discount: a numeric field (0–100), the discounted total is previewed in real-time
**And** for fixed amount: a XAF integer field, validated that discount ≤ total
**And** on confirm, the discount is applied to the grand total — each `sale_item` retains its original `appliedUnitPrice`, and a `discountAmount` field on the `sales` record stores the total reduction

**Given** a discount is applied to the cart
**When** Loïc views the checkout screen
**Then** the total breakdown shows: Sous-total, Réduction (−X FCFA), **Total à payer** in bold
**And** the change calculation (Espèces mode) uses the final discounted total

**Given** Simon reviews completed sales in the history
**When** he views a sale that had a price override or discount
**Then** the sale detail shows: each item's catalogue price vs applied price, the discount line (if any), and the `SalePriceOverriddenEvent` entries in the audit log
**And** the audit entry clearly identifies Loïc as the actor and the delta amount

---

### Story 4.3: Clôture Journalière & Historique des Ventes

As an employee (Loïc),
I want to close my day in 1 tap and see my own sales history,
So that I can end my shift properly without any manual accounting and track my own performance.

**Acceptance Criteria:**

**Given** Loïc is working during the day
**When** he wants to close his day
**Then** a "🌙 Clôturer la journée" button (`DayCloseButton` component) is prominently visible on the POS screen (bottom trailing position)
**And** the button shows a notification badge with the count of sales made since last closure
**And** tapping it shows a flash summary bottom sheet (non-blocking, auto-dismisses after 5s): total sales count, total revenue in XAF, top product sold (name + qty), payment breakdown (Cash vs MoMo)

**Given** Loïc taps "Confirmer la clôture" on the flash summary
**When** the closure is confirmed
**Then** a `DayClosedEvent` is emitted with: `storeId`, `actorId`, `closedAt`, `totalSales`, `totalRevenue`, `paymentBreakdown`
**And** the daily report is generated (FR42) and sent automatically via `WhatsAppPort` to Simon's number
**And** the report format is emoji-rich, compact, French, readable in 5 seconds (no PDF, no link) — example:
```
📊 Clôture Boutique Centrale — 2 mars 2026
👤 Vendeur : Loïc
💰 CA : 125 000 FCFA
🛍 Ventes : 14
📦 Top produit : Robe M Rouge (×5)
💵 Cash : 90 000 | 📱 MoMo : 35 000
✅ Clôture manuelle à 18h32
```
**And** the app shows "Bonne soirée Loïc ! 🌙" on a success screen before returning to the POS
**And** the `DayCloseButton` transitions to a "Journée clôturée ✅" disabled state until the next calendar day

**Given** Loïc forgets to close his day manually
**When** 20:00 local time is reached without a `DayClosedEvent` for that store and actor
**Then** the system automatically generates the daily closure and sends the WhatsApp report
**And** the report adds the indicator: "⏰ Rapport auto-généré (clôture oubliée)"
**And** Simon is notified — Loïc is NOT penalized, no error is raised in the app
**And** the next morning, Loïc sees a subtle notification: "Votre journée du 2 mars a été clôturée automatiquement."

**Given** Loïc navigates to his sales history
**When** he opens POS > Mes Ventes
**Then** he sees all his own sales for the current day by default (filter: Today)
**And** each sale card shows: time, total, payment mode, client name (if linked), number of items
**And** he can filter by: date range (Today / This week / This month / Custom)
**And** tapping a sale opens its detail: all items with quantities and applied prices, discount if any, client
**And** Loïc can only see HIS OWN sales — he cannot view sales made by other employees
**And** the history loads offline from local Drift data

**Given** the daily summary calculation (FR42)
**When** the day-close is triggered (manual or auto)
**Then** the system aggregates all `sales` records for `storeId` + `date` into: total count, total revenue (sum of `totalAmount`), revenue by payment mode, average basket size, top 3 products by quantity sold
**And** this calculation runs locally in Drift and is confirmed server-side on sync

---

### Story 4.4: Annulation & Correction de Vente

As a proprietor (Simon),
I want to cancel or correct any sale with a mandatory justification,
So that errors are corrected with full accountability and stock is automatically restored to accurate levels.

**Acceptance Criteria:**

**Given** Simon navigates to any sale in the history (his view or store-wide)
**When** he views the sale detail screen
**Then** an "Annuler / Corriger" button is visible (OWNER role only — hidden for EMPLOYEE)
**And** tapping it displays two options: "Annuler entièrement" and "Corriger un article"

**Given** Simon selects "Annuler entièrement"
**When** he confirms the action
**Then** a mandatory justification field is required (minimum 10 characters, free text)
**And** on confirmation: the sale `status` changes to `CANCELLED`
**And** all stock levels for the sold products/variants are restored: `quantityAfter = quantityBefore + saleLine.quantity` per item
**And** a `SaleCancelledEvent` is emitted with: `saleId`, `actorId`, `justification`, `occurredAt`, full `sale_items` snapshot for stock restoration proof
**And** the restored stock movements appear in the product's movement history as `SALE_CANCELLED` type
**And** if the cancellation triggers stock levels above the threshold (previously "Bas"), the "Stock bas" badge is cleared automatically

**Given** Simon selects "Corriger un article" on a specific sale
**When** the correction mode opens
**Then** he sees all sale items with editable quantity fields
**And** decreasing a quantity restores the difference to stock; increasing a quantity decrements the difference from stock
**And** a mandatory justification field is required before saving
**And** on save: the `sale_items` record is updated, stock adjustments are applied atomically, a `SaleCorrectedEvent` is emitted with `before` and `after` snapshots
**And** the corrected sale shows a "Modifiée" badge in the history with the original total crossed out and the corrected total

**Given** the app is offline when Simon performs a cancellation
**When** the cancellation is confirmed
**Then** the cancellation is recorded locally: sale status updated in Drift, stock restored locally
**And** the operation is queued in `sync_queue` with type `SALE_CANCELLATION`
**And** on sync, the server applies the same stock restoration atomically
**And** the backend rejects the cancellation if the sale was already cancelled (idempotency check via `status`)

**Given** an EMPLOYEE role user views the sales history
**When** they see a cancelled or corrected sale
**Then** the "Annuler / Corriger" button is NOT shown — the action is OWNER-only
**And** they can see the "Annulée" or "Modifiée" badge and read the justification text (transparency for employees)

---

### Story 4.5: Leaderboard Vendeurs & Historique Global

As a proprietor (Simon),
I want to see my employees' sales performance ranked in a leaderboard and consult the full store sales history,
So that I can recognize top performers, identify coaching opportunities, and monitor overall business activity.

**Acceptance Criteria:**

**Given** Simon navigates to Rapports > Équipe
**When** the leaderboard screen loads
**Then** he sees all employees ranked by total revenue for the current day (default period)
**And** each row shows: rank badge (🥇🥈🥉 for top 3), employee name, number of sales, total revenue in XAF, average basket size
**And** Simon can change the period: Aujourd'hui / Cette semaine / Ce mois
**And** the leaderboard data is calculated from local Drift `sales` aggregated by `actorId`

**Given** the leaderboard feature gate (UX16)
**When** the leaderboard is in its default state (V1 — owner-only)
**Then** the leaderboard data is visible ONLY to Simon (OWNER role)
**And** Loïc (EMPLOYEE) cannot see the leaderboard, other employees' stats, or any comparative data
**And** the navigation item for "Équipe / Leaderboard" is not rendered in the employee navigation menu

**Given** Simon wants to motivate his team through healthy competition
**When** he navigates to Paramètres > Équipe > Options
**Then** a toggle "Afficher le leaderboard aux employés" is available (default: OFF)
**And** activating it shows a confirmation: "Les vendeurs pourront voir leur classement et celui de leurs collègues."
**And** when enabled, the leaderboard tab becomes visible to EMPLOYEE roles showing their own rank and the anonymized rankings of others

**Given** Simon views the global sales history for his store
**When** he navigates to POS > Historique boutique
**Then** he sees all sales for his store (all employees) in reverse chronological order
**And** each sale shows: time, employee name, total, payment mode, client (if linked), number of items
**And** he can filter by: employee, payment mode, date range, client
**And** he can search by sale amount range (e.g., "> 10 000 FCFA")
**And** the history loads offline from local Drift data
**And** tapping any sale opens the full sale detail with the "Annuler / Corriger" option (Story 4.4)

---

## Epic 5: Moteur de Synchronisation Offline-First

L'application fonctionne 100% opérationnellement hors-ligne pendant 7 jours, synchronise automatiquement toutes les données au retour de la connectivité en moins de 60 secondes, résout les conflits multi-device intelligemment (delta-based pour le stock, last-write-wins pour le reste), garantit zéro perte de données, et suspend l'accès écriture après 7 jours sans sync pour forcer une reconnexion.

**FRs couverts :** FR69–FR71, FR73–FR74, FR76

---

### Story 5.1: Push Sync — Envoi par Lot des Opérations en File

As a user (Simon or Loïc),
I want all operations performed offline to be automatically uploaded to the server when connectivity is restored,
So that my local data is always backed up and visible to Simon on other devices without any manual action.

**Acceptance Criteria:**

**Given** any write operation is performed while offline (sale, stock adjustment, transfer, product creation, etc.)
**When** the operation completes locally
**Then** a record is inserted into the Drift `sync_queue` table with: `id` (UUID), `operationType` (SALE_COMPLETE / STOCK_ADJUST / TRANSFER / PRODUCT_CREATE / etc.), `entityId`, `payload` (full JSON snapshot), `createdAt`, `synced: false`, `retryCount: 0`, `lastAttemptAt: null`
**And** the operation is invisible to the user — local UI feedback is identical to online mode

**Given** the app recovers network connectivity after being offline
**When** connectivity is detected (within 3 seconds via `connectivity_plus`)
**Then** the `SyncService.push()` method is triggered automatically — no user action required
**And** all unsynced records from `sync_queue` (`synced: false`) are batched into a single `POST /api/v1/sync/push` request
**And** the batch payload is: `{ "tenantId": "...", "deviceId": "...", "operations": [ { "operationType", "entityId", "payload", "clientTimestamp" } ] }`
**And** the `SyncIndicator` transitions to 🔵 "Synchronisation..." (animated) during the upload

**Given** the backend receives the push batch
**When** it processes each operation
**Then** each operation is validated (schema, business rules) and applied to the tenant schema in order of `clientTimestamp`
**And** the response returns per-operation results: `{ "operationId": "...", "status": "APPLIED" | "CONFLICT" | "REJECTED", "conflictData": { ... } }`
**And** operations with `status: APPLIED` are marked `synced: true` in the local `sync_queue`
**And** operations with `status: REJECTED` are marked with the rejection reason — a `SyncErrorEvent` is logged

**Given** a push request fails due to network error mid-sync
**When** the HTTP call times out or returns a 5xx error
**Then** the sync is retried with exponential backoff: first retry at 2s, then 4s, 8s, 16s… up to a maximum of 5 minutes between retries
**And** `retryCount` is incremented and `lastAttemptAt` is updated on each attempt
**And** after 10 consecutive failures spanning > 24 hours, a persistent warning banner is shown: "⚠ Synchronisation en échec depuis [duration]. Vérifiez votre connexion."
**And** the SyncIndicator stays 🔵 animated during retry attempts

**Given** Simon has the app open on two devices simultaneously (mobile + desktop)
**When** a push sync completes on one device
**Then** the `lastSyncAt` timestamp for that device is updated in the `user_sync_state` server-side table
**And** the other device detects the push completion via the next pull cycle (Story 5.2)

---

### Story 5.2: Pull Sync — Téléchargement Delta & Fusion Locale

As a user (Simon or Loïc),
I want my local data to be updated with all changes made on other devices or by other employees,
So that I always see the most recent state of stock, sales, and products without manual refresh.

**Acceptance Criteria:**

**Given** the app is online and a push sync has completed (Story 5.1)
**When** the `SyncService.pull()` is triggered (immediately after push, and then every 60 seconds while online)
**Then** the app sends `GET /api/v1/sync/pull?since={lastPullTimestamp}&tenantId={tenantId}`
**And** the server returns only records modified since `lastPullTimestamp` (delta — not full dataset)
**And** the delta response structure is: `{ "pulledAt": "ISO8601", "entities": { "products": [...], "stock_levels": [...], "sales": [...], "stores": [...], "users": [...] } }`
**And** only modified fields are included per entity (partial update payload)

**Given** the pull response arrives with new data
**When** the Flutter app merges the delta into Drift
**Then** each received entity is upserted into the corresponding Drift table using its `id` as the merge key
**And** if a local record has `synced: false` (pending push), the merge defers that entity until after push confirmation (push takes priority for unsynced local changes)
**And** `lastPullTimestamp` is updated to `pulledAt` value from the response — this becomes the `since` parameter for the next pull
**And** the UI reflects the new data immediately via Riverpod reactive state (no manual refresh needed)

**Given** the very first sync on a new device (e.g., Simon installs Keevo on a second desktop)
**When** he logs in and the initial pull runs
**Then** the server returns the full dataset for that tenant (no `since` parameter on first pull)
**And** all products, stock levels, stores, sales (last 30 days), and users are downloaded and stored in Drift
**And** a progress indicator is shown: "Chargement de vos données... [X%]"
**And** the app becomes fully usable only after the initial full sync completes (blocking-only for first install)

**Given** a pull response contains 0 new records
**When** the pull completes
**Then** `lastPullTimestamp` is updated to the current server time
**And** the SyncIndicator transitions to 🟢 "En ligne" state
**And** no UI changes are triggered (silent update)

**Given** the pull endpoint is unreachable (server down or 5xx)
**When** the pull fails
**Then** the app continues to function fully from local Drift data
**And** the SyncIndicator stays 🟡 or 🔴 depending on days since last successful sync
**And** the pull is retried on the same exponential backoff schedule as push (2s, 4s, 8s... max 5min)

---

### Story 5.3: Résolution de Conflits — Delta Stock & Last-Write-Wins

As a system,
I want to resolve data conflicts intelligently when multiple devices modify the same entity while offline,
So that stock levels are always mathematically accurate and no data is silently overwritten without a trace.

**Acceptance Criteria:**

**Given** two devices (Simon's mobile and Loïc's mobile) both sell the same product while offline simultaneously
**When** both push their sync batches to the server
**Then** the server applies the **delta-based strategy** for stock conflicts:
  - Device A sold 3 units: `delta_A = -3`
  - Device B sold 2 units: `delta_B = -2`
  - Server applies both deltas: `finalStock = baseStock + delta_A + delta_B = baseStock - 5`
  - Result: both sales are accepted, stock is mathematically correct
**And** no sale is rejected or overwritten — both transactions are preserved
**And** a `StockConflictResolvedEvent` is logged with: `productId`, `deviceA_delta`, `deviceB_delta`, `resolvedStock`, `strategy: DELTA_SUM`

**Given** a stock delta resolution results in a negative stock value
**When** the server applies the combined deltas and `finalStock < 0`
**Then** the server returns `status: CONFLICT` for the operation that caused the negative value (chronologically last by `clientTimestamp`)
**And** the conflict response includes: `{ "conflictType": "STOCK_NEGATIVE", "availableStock": X, "requestedDelta": Y }`
**And** the Flutter app displays a non-blocking warning: "⚠ Conflit de stock : [Product] — [X] unités vendues, mais seulement [Y] disponibles au moment de la sync."
**And** the conflict is logged in the audit trail as `SyncConflictEvent` — Simon can review all conflicts in Paramètres > Synchronisation > Conflits

**Given** two devices modify a non-stock entity simultaneously (e.g., product name updated on two devices)
**When** both push their changes
**Then** the server applies the **last-write-wins strategy**: the operation with the higher `clientTimestamp` is applied
**And** the earlier operation is discarded silently
**And** a `SyncOverwrittenEvent` is logged (not shown to user by default, visible in audit for OWNER only): `entityType`, `entityId`, `winnerTimestamp`, `loserTimestamp`, `discardedPayload`

**Given** the conflict resolution engine receives a push batch
**When** it processes each operation
**Then** the resolution strategy is selected automatically by `operationType`:
  - `SALE_COMPLETE`, `STOCK_ADJUST`, `TRANSFER` → **Delta-based** (stock quantities)
  - `PRODUCT_CREATE/UPDATE`, `CLIENT_CREATE/UPDATE`, `STORE_UPDATE` → **Last-write-wins** (non-financial entities)
  - `SALE_CANCEL`, `AUDIT_EVENT` → **Append-only** (never overwritten, always applied)
**And** the strategy selection is implemented via the `ConflictResolutionStrategy` interface (GoF Strategy pattern) with `DeltaStockResolver` and `LastWriteWinsResolver` implementations

---

### Story 5.4: Limite 7 Jours Hors-ligne & Gate de Déverrouillage

As the system,
I want to suspend write access after 7 days without synchronization and require a sync to unlock,
So that data integrity is maintained and the server always has an accurate recent state of every active tenant.

**Acceptance Criteria:**

**Given** the app tracks offline duration
**When** `daysSinceLastSync` is calculated on every app open
**Then** the `SyncIndicator` updates its state:
  - Days 1–4: 🟡 "Hors-ligne — Jour X/7" (no restriction on operations)
  - Day 5: 🟡 "Hors-ligne — Jour 5/7" + yellow persistent banner: "⚠ Synchronisation requise dans 2 jours"
  - Day 6: 🔴 "Hors-ligne critique — Jour 6/7" + red persistent banner: "🔴 Dernière chance : synchronisez demain ou certaines actions seront bloquées"
  - Day 7+: 🔴 "Accès limité — Sync requise"

**Given** `daysSinceLastSync >= 7`
**When** the user attempts any write operation (create sale, adjust stock, transfer, create product)
**Then** the operation is BLOCKED locally — Drift write is NOT performed
**And** a blocking modal appears: "⏸ Synchronisation requise" with the message: "Vous n'avez pas synchronisé depuis 7 jours. Connectez-vous à internet pour synchroniser et retrouver l'accès complet."
**And** a "Synchroniser maintenant" button is prominently shown
**And** READ operations (view stock, view history, view catalogue) continue to work — no read restrictions
**And** the SyncIndicator "Synchroniser maintenant" tap action is reinforced

**Given** the user connects to the internet while in the 7-day-blocked state
**When** connectivity is detected
**Then** the sync (push + pull) triggers automatically
**And** while sync is in progress, the blocking modal is replaced with a progress indicator: "Synchronisation en cours..."
**And** on successful sync completion: the write access gate is lifted immediately
**And** a confirmation SnackBar: "✅ Synchronisation réussie — accès complet restauré"
**And** the `daysSinceLastSync` counter resets to 0

**Given** the sync attempt fails while in blocked state (network error)
**When** the sync fails after retries
**Then** the blocking modal remains visible
**And** an error message is shown: "Impossible de synchroniser. Vérifiez votre connexion internet et réessayez."
**And** a "Réessayer" button triggers a new sync attempt immediately (bypasses the exponential backoff for user-initiated retries)

**Given** the 7-day limit logic
**When** it is implemented
**Then** `lastSyncAt` is persisted in `flutter_secure_storage` (not Drift — survives database resets)
**And** the check runs on every app open and every 30 minutes while the app is in the foreground
**And** the server also enforces this: any push request with `lastSyncAt` older than 7 days returns HTTP 423 `{ "domainCode": "SYNC_REQUIRED", "daysSinceLastSync": 8 }`

---

### Story 5.5: Monitoring Sync, Récupération d'Erreurs & Cohérence Multi-Device

As a proprietor (Simon),
I want visibility into the sync status across all my devices and the ability to diagnose and resolve sync issues,
So that I can trust my data is consistent and react quickly if something goes wrong.

**Acceptance Criteria:**

**Given** Simon taps the SyncIndicator in the AppBar
**When** the sync detail bottom sheet opens
**Then** it shows:
  - Last successful sync: date + time (e.g., "Dernière sync : aujourd'hui à 14h32")
  - Pending operations in queue: count (e.g., "3 opérations en attente d'envoi")
  - Active devices (from server-side `user_sync_state`): device name, platform, last seen
  - Any unresolved conflicts (count + link to conflict log)
**And** a "Synchroniser maintenant" button triggers an immediate push+pull cycle if online
**And** the button is disabled and shows "Hors-ligne" if no network is available

**Given** unsynced operations remain in the queue for > 1 hour while online
**When** this is detected
**Then** an automatic diagnostic check runs: verify server reachability (`GET /api/v1/health`), verify JWT validity, verify tenant status
**And** if the server is reachable but sync is still failing: a `SyncDiagnosticFailedEvent` is logged with details
**And** a notification is shown to Simon: "Vérification requise : des données n'ont pas pu être synchronisées."

**Given** Simon navigates to Paramètres > Synchronisation
**When** the sync settings screen loads
**Then** he sees:
  - Sync history: last 20 sync events (timestamp, type PUSH/PULL, operation count, success/failure status)
  - Conflict log: all `SyncConflictEvent` and `SyncOverwrittenEvent` entries with affected entity details
  - Pending queue: list of queued operations with type, entity, and queued time
**And** he can manually force a full sync from this screen
**And** he can view (read-only) the full payload of any conflicted or rejected operation for debugging

**Given** the system guarantees zero data loss (FR73)
**When** any sync operation is processed
**Then** no operation is silently dropped without a trace — every `REJECTED` or `CONFLICT` operation is logged in the server's `sync_error_log` table
**And** the Flutter app retains all operations in `sync_queue` (with `synced: false`) until the server has explicitly confirmed processing — even `CONFLICT` results mark the operation as `synced: true` (it was processed, even if not applied)
**And** the server stores the full payload of every rejected operation for 30 days for manual recovery by Toor (Super Admin) if needed

**Given** Simon uses the app on mobile AND desktop simultaneously (FR76)
**When** he records a sale on mobile
**Then** the sale appears on desktop within 60 seconds (pull cycle maximum latency)
**And** stock levels on desktop update to reflect the sale
**And** no duplicate sales appear — each `sale.id` (UUID) is idempotent on the server (re-sending the same UUID is a no-op after first application)

---

## Epic 6: Inventaire Assisté

Simon (et Loïc, si autorisé) peuvent lancer un inventaire guidé complet ou partiel, renseigner les quantités physiques réelles produit par produit, voir en temps réel les écarts avec le stock théorique Keevo, valider et appliquer les ajustements au stock en un seul tap, et recevoir un rapport d'inventaire détaillé.

**FRs couverts :** FR45–FR49

---

### Story 6.1: Lancement & Configuration d'une Session d'Inventaire

As a proprietor (Simon),
I want to start an inventory session for a specific store or the warehouse, choosing between full or partial scope,
So that I can count my actual stock without disrupting ongoing operations.

**Acceptance Criteria:**

**Given** Simon navigates to Stock > Inventaire
**When** he taps "Lancer un inventaire"
**Then** a configuration screen appears with:
  - Boutique cible (required — dropdown of active stores + warehouse)
  - Scope: "Inventaire complet" (all products) or "Inventaire partiel" (select categories)
  - Mode: "Compter maintenant" (immediate) or "Planifier" (date + time picker, future)
**And** for partial scope, a multi-select list of categories is shown (from tenant's configured categories)

**Given** Simon confirms the inventory configuration
**When** the session is created
**Then** an `InventorySession` record is created in Drift with: `id` (UUID), `tenantId`, `storeId`, `scope` (FULL / PARTIAL), `categoryIds` (if partial), `status: IN_PROGRESS`, `startedAt`, `startedBy` (actorId)
**And** the session is also queued in `sync_queue` to notify the server (so other devices know an inventory is active)
**And** the app navigates immediately to the inventory counting form (Story 6.2)

**Given** an inventory session is already IN_PROGRESS for a store
**When** another user tries to start a new session for the same store
**Then** the system shows a warning: "Un inventaire est déjà en cours pour [Store Name], commencé par [Actor] à [time]. Voulez-vous le reprendre ou l'annuler ?"
**And** the user can choose to resume the existing session or cancel it and start a new one
**And** only OWNER can cancel an in-progress session started by an EMPLOYEE

**Given** a session is created offline
**When** the app is offline during session creation
**Then** the session is created locally in Drift and queued in `sync_queue`
**And** the counting form works fully offline — no network needed to count stock
**And** the session and all counts are pushed on next sync

---

### Story 6.2: Formulaire Guidé de Saisie des Quantités Physiques

As a user (Simon or Loïc),
I want a guided form that shows me each product with its theoretical stock and lets me enter the physical count,
So that I can complete the inventory systematically without missing any product and without errors.

**Acceptance Criteria:**

**Given** Simon is in an active inventory session
**When** the counting form loads
**Then** all products in scope (filtered by store + category if partial) are displayed as `InventoryRow` components, sorted alphabetically by default
**And** each `InventoryRow` shows: product photo (or initials placeholder), product name, variant label (if applicable), theoretical stock from Drift `stock_levels` (labelled "Keevo : X"), a numeric input field for the physical count (labelled "Réel"), and an ecart badge (hidden until physical count is entered)

**Given** Simon taps the physical count field for a product
**When** he enters a number
**Then** the `InventoryRow` immediately calculates and displays the gap badge:
  - ✅ green "= 0" if physical = theoretical
  - ⚠️ orange "+X" if physical > theoretical (surplus)
  - 🔴 red "−X" if physical < theoretical (shortage)
**And** the gap value is calculated as: `écart = physique − théorique`
**And** the row background subtly highlights: white (match), amber-50 (surplus), red-50 (shortage)

**Given** the inventory has many products (e.g., 200 items)
**When** Simon scrolls through the list
**Then** the list uses `ListView.builder` (lazy rendering — never `children: [...]`)
**And** a progress bar at the top shows "X / Y produits comptés" updating as fields are filled
**And** a FAB "Filtrer" allows filtering by: Tous / Non comptés / Écarts seulement
**And** the "Non comptés" filter is the default after starting — Simon works through uncounted items first

**Given** Simon wants to navigate quickly to a specific product
**When** he taps the search icon in the inventory form
**Then** a search bar appears and filters the list in real-time against product name and SKU
**And** the search works offline against local Drift data

**Given** all products have been counted (progress = 100%)
**When** Simon reviews the completed form
**Then** a summary banner appears at the top: "Inventaire terminé — X produits concordants, Y écarts"
**And** a prominent "Valider l'inventaire" button becomes active (previously disabled)
**And** Simon can still edit any count before validating

**Given** Simon wants to pause and resume the inventory later
**When** he exits the counting form without validating
**Then** the session status remains `IN_PROGRESS` and all entered counts are saved locally
**And** re-entering the inventory module shows a "Reprendre l'inventaire en cours" banner
**And** all previously entered counts are pre-filled when he resumes

---

### Story 6.3: Calcul des Écarts & Rapport d'Inventaire

As a proprietor (Simon),
I want to see a complete gap analysis report before committing any stock changes,
So that I can review discrepancies, identify potential theft or errors, and make an informed decision.

**Acceptance Criteria:**

**Given** Simon taps "Valider l'inventaire"
**When** the validation screen loads
**Then** a full gap report is generated locally from the counted data and displayed:
  - Summary header: total products counted, total products with gaps, total value of shortages (sum of `|écart| × unitPrice` for negative gaps in XAF)
  - Concordant products section: count only (collapsed by default)
  - Surplus section: list of products with `écart > 0` — name, variant, theoretical, physical, +delta, XAF value
  - Shortage section: list of products with `écart < 0` — name, variant, theoretical, physical, −delta, XAF value — sorted by value (largest loss first)
**And** the `InventoryRow` (UX32) component is reused for each row in the report

**Given** the gap report is displayed
**When** Simon reviews it
**Then** he can tap any row to see the product detail (photo, category, movement history)
**And** a "Télécharger le rapport" button (OWNER only) exports the report as a formatted text summary (Plan Free) or PDF (Plan Payant)
**And** a "Partager sur WhatsApp" button sends the report via `WhatsAppPort` in the same emoji-rich compact text format as day-close reports:
```
📋 Rapport d'inventaire — Boutique Centrale
📅 2 mars 2026 — 16h45
👤 Simon

✅ Concordants : 142 produits
⚠️ Surplus : 3 produits (+15 000 FCFA)
🔴 Manquants : 7 produits (−47 500 FCFA)

Top manques :
• Robe M Rouge : −5 unités (−25 000 FCFA)
• Jeans L : −2 unités (−12 000 FCFA)
```
**And** the report can be sent even if the inventory is not yet validated (pre-validation review sharing)

**Given** Simon navigates to Stock > Historique inventaires (without validating the current session)
**When** he views past inventory reports
**Then** all past `VALIDATED` sessions are listed with: date, store, scope, product count, total gaps count, total shortage value
**And** each past report is fully viewable offline from local Drift data

---

### Story 6.4: Validation & Application des Ajustements au Stock

As a proprietor (Simon),
I want to apply all inventory gaps to the actual stock levels in one tap with full audit traceability,
So that Keevo's stock data matches physical reality after every inventory session.

**Acceptance Criteria:**

**Given** Simon is on the gap report validation screen
**When** he taps "Appliquer les ajustements"
**Then** a final confirmation dialog appears: "Appliquer [N] ajustements de stock ? Cette action est irréversible." with "Annuler" and "Confirmer"

**Given** Simon confirms the application
**When** the adjustments are processed
**Then** for each product with `écart ≠ 0`: the `stock_levels` record for that `productId + variantId + storeId` is updated to the physical count value
**And** a `StockAdjustedEvent` is emitted per product with: `source: INVENTORY`, `quantityBefore` (theoretical), `quantityAfter` (physical), `inventorySessionId`, `actorId`, `occurredAt`
**And** all adjustments are applied atomically — either all succeed or none are applied (transaction)
**And** the inventory session `status` is updated to `VALIDATED` and `completedAt` is set
**And** all operations are queued in `sync_queue` if offline

**Given** a stock adjustment during inventory validation crosses a product's threshold
**When** the adjustment sets stock below the configured minimum
**Then** a `StockThresholdBreachedEvent` is emitted for that product (same flow as Story 2.3)
**And** the push notification is sent to Simon

**Given** the inventory session is validated
**When** Simon views the stock levels for the inventoried store
**Then** all stock levels reflect the physical counts from the inventory
**And** the stock movement history (Story 2.3) shows each adjustment with type `ADJUSTMENT`, source `INVENTORY`, and the `inventorySessionId` as reference
**And** the `InventorySession` record is preserved in full — it cannot be deleted

**Given** products with zero gap (`écart = 0`)
**When** the validation runs
**Then** no `StockAdjustedEvent` is emitted for those products (no unnecessary audit noise)
**And** their stock levels are not touched

---

## Epic 7: Rapports, Dashboard & Communication WhatsApp

Simon consulte un dashboard matinal ultra-compact au démarrage de l'app, reçoit automatiquement ses rapports end-of-day et hebdomadaires sur WhatsApp, suit la rentabilité par produit, compare les performances entre boutiques, configure ses préférences de rapport, et reçoit un message motivationnel quotidien.

**FRs couverts :** FR50–FR60, FR63

---

### Story 7.1: Dashboard Matinal & Message Motivationnel

As a proprietor (Simon),
I want to see a powerful summary of my business the moment I open the app each morning,
So that I can make informed decisions in under 10 seconds without navigating through multiple screens.

**Acceptance Criteria:**

**Given** Simon opens the app as a proprietor (OWNER role)
**When** the app loads
**Then** the default landing screen is the Dashboard (not POS — POS is Loïc's default)
**And** the `MorningSummaryHeroCard` (UX26) is displayed at the top with: CA d'hier (Display 32sp, bold), tendance vs avant-hier (e.g., "↑ +12%"), sparkline des 7 derniers jours (mini chart), all with glassmorphism effect (`BackdropFilter`, blur 10–20, border opacity 30%)
**And** the hero card data loads from local Drift aggregations — available offline, under 3 seconds

**Given** the dashboard loads
**When** Simon views the full screen
**Then** below the hero card, he sees a row of `MetricBadgeCard` (UX28) components: Stock Global (total products across all stores), Alertes Actives (count of products below threshold), Ventes Aujourd'hui (count), CA Aujourd'hui (XAF)
**And** a "Top Produits" section shows the 5 best-selling products of the current week (name, qty sold, revenue)
**And** a `ShopStatusCard` (UX27) per active store: store name, today's CA, status indicator (green/orange/red based on sales trend), trend %
**And** the entire dashboard renders in < 3 seconds from local Drift data — no API call needed

**Given** it is the first app open of the day (after midnight)
**When** the dashboard loads
**Then** a motivational message is displayed in a dismissible card below the hero: one of a rotating set of French messages (e.g., "💪 Bonne journée Simon ! Hier vous avez fait 125 000 FCFA. Visez plus haut aujourd'hui !")
**And** the message changes daily (keyed by date) — same message all day even if app is reopened
**And** tapping the X dismisses it for the rest of the day; it reappears the next morning

**Given** Simon has multiple stores
**When** he taps a `ShopStatusCard`
**Then** he navigates to the store-specific sub-dashboard showing: today's sales list for that store, stock alerts for that store, top products for that store
**And** the sub-dashboard is also fully offline-capable

**Given** Simon is on desktop (expanded breakpoint ≥ 840dp)
**When** the dashboard loads
**Then** the layout uses a 3-column responsive grid: hero card full width, metrics row, then stores grid (2–3 per row)
**And** no information is hidden on desktop — the expanded layout shows more data than mobile without requiring scroll

---

### Story 7.2: Rapport End-of-Day — Génération & Envoi WhatsApp

As a proprietor (Simon),
I want to receive a concise, emoji-rich daily report on WhatsApp at the end of each business day,
So that I can review performance instantly on my phone without opening the app.

**Acceptance Criteria:**

**Given** Loïc closes his day manually (Story 4.3) or the automatic 20h fallback triggers
**When** the `DayClosedEvent` is processed
**Then** the system generates the end-of-day report using the `DailyReport` template (Template Method GoF pattern, extending `AbstractReport`)
**And** the report aggregates for that `storeId` + `date`: total sales count, total CA (XAF), CA by payment mode (Cash / MoMo), top 3 products by quantity, average basket size, employee breakdown (name + CA)
**And** the report is sent via `WhatsAppPort.send()` to Simon's WhatsApp number

**Given** the WhatsApp message is generated
**When** it is formatted
**Then** the format is emoji-rich, French, readable in ≤ 5 seconds, no link, no PDF:
```
📊 Rapport du jour — [Store Name]
📅 [Date] | ⏰ [Close time] ([Manuel/Auto])

💰 CA Total : [X] FCFA
🛍 Ventes : [N] | 🧺 Panier moyen : [X] FCFA
💵 Cash : [X] FCFA | 📱 MoMo : [X] FCFA

🏆 Top produits :
1. [Product] — [qty] vendu(s) — [X] FCFA
2. [Product] — [qty] vendu(s) — [X] FCFA
3. [Product] — [qty] vendu(s) — [X] FCFA

👤 Équipe :
• [Employee 1] : [N] ventes — [X] FCFA
• [Employee 2] : [N] ventes — [X] FCFA

📦 Alertes stock : [N] produit(s) en rupture
```
**And** if Simon has multiple stores and all have closed, a combined multi-store summary is appended

**Given** the WhatsApp delivery fails (WhatsApp port unavailable or rate limited)
**When** the send attempt fails
**Then** the report is queued for retry (max 3 attempts, 5-minute intervals)
**And** if all retries fail, the report is stored locally and a push notification is sent to Simon in-app: "Rapport WhatsApp non envoyé — consultez le rapport dans l'app"
**And** the report remains accessible in Rapports > Historique regardless of WhatsApp delivery status

**Given** Simon navigates to Rapports > Historique
**When** the screen loads
**Then** all past end-of-day reports are listed in reverse chronological order: date, store, CA, delivery status (WhatsApp ✅ / ❌ / En app)
**And** tapping any report shows the full formatted WhatsApp text as an in-app preview
**And** a "Renvoyer sur WhatsApp" action is available for failed reports (OWNER only)
**And** the history loads offline from local Drift data

---

### Story 7.3: Rapport Hebdomadaire Automatique

As a proprietor (Simon),
I want to receive a weekly summary every Sunday evening with my full week's performance,
So that I can review business trends, top performers, and profitability at a glance without any manual action.

**Acceptance Criteria:**

**Given** every Sunday at 20:00 local time
**When** the backend weekly scheduler triggers
**Then** the `WeeklyReport` (extends `AbstractReport` via Template Method) is generated for each active tenant with: date range (Monday–Sunday), total CA across all stores, CA by store, CA by employee, top 5 products for the week by revenue, top 5 products by quantity, week-over-week CA comparison (vs previous Sunday report)

**Given** the weekly report is generated
**When** it is sent
**Then** it is delivered via `WhatsAppPort` to Simon's WhatsApp number AND via SMS fallback if WhatsApp fails (using the same `WhatsAppPort` adapter with SMS fallback — Chain of Responsibility pattern)
**And** the format is the same emoji-rich compact style as the daily report, with a "📅 Rapport Hebdo" header
**And** the report is stored in Drift locally on the next pull sync

**Given** Simon has not closed any day during the week (no `DayClosedEvent`)
**When** the weekly report is generated
**Then** it still runs using the aggregated `sales` data from the server — it does not depend on day-close events
**And** the report header notes: "⏰ Rapport auto-généré (aucune clôture manuelle cette semaine)"

**Given** Simon configures a different weekly report day/time
**When** he navigates to Paramètres > Rapports > Hebdomadaire
**Then** he can change the day of week (default: Dimanche) and the time (default: 20:00)
**And** the setting is saved in tenant preferences and respected by the backend scheduler

---

### Story 7.4: Dashboard Rentabilité & Analyse par Produit/Boutique

As a proprietor (Simon),
I want to see profitability per product and comparative performance per store,
So that I can identify my best margins, cut underperforming products, and understand which store drives the most value.

**Acceptance Criteria:**

**Given** Simon navigates to Rapports > Rentabilité
**When** the screen loads
**Then** a product profitability list is shown: each product with: name, total units sold (selected period), total revenue, total cost (purchase price + transport × units sold), gross margin in XAF and %
**And** products are sorted by margin % descending by default
**And** Simon can sort by: Marge % / Marge XAF / CA / Unités vendues
**And** the margin % color coding matches the product form (green ≥ 20%, orange 10–19%, red < 10%)

**Given** Simon selects a period filter
**When** he changes the period (Aujourd'hui / 7 jours / 30 jours / Personnalisé)
**Then** all profitability data updates to reflect the selected range
**And** the filter is applied locally against Drift `sales` + `sale_items` + `products` data — no API call needed
**And** the screen renders in < 3 seconds for up to 500 products

**Given** Simon taps a product in the rentabilité list
**When** the product detail profitability screen opens
**Then** he sees: total units sold per period, applied price range (min / max / average — detects price overrides), margin trend (sparkline 7 days), top store by sales volume for this product

**Given** Simon navigates to Rapports > Boutiques
**When** the comparative store performance screen loads
**Then** a ranked list of stores is shown by CA (current period): store name, CA, number of sales, average basket, top product
**And** a delta badge shows week-over-week change per store: "+8%" in green or "−3%" in red
**And** Simon can filter by period and switch between CA / Sales count / Avg basket as the ranking metric

**Given** Simon is on Plan Payant (FR55)
**When** he taps "Exporter" on any report screen
**Then** a PDF is generated client-side (or server-side) with the full report data, formatted cleanly
**And** an Excel (.xlsx) export is available for raw data tables (sales list, product list with margins)
**And** the export is shared via the native Flutter share sheet (save to device / send via app)
**And** for Plan Free users, the export button shows a lock icon and "Disponible sur Plan Premium" tooltip

---

### Story 7.5: Configuration des Rapports & Préférences WhatsApp

As a proprietor (Simon),
I want to configure which reports I receive, when, and via which channel,
So that I get exactly the information I need without being overwhelmed by notifications.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Rapports
**When** the configuration screen loads
**Then** he sees toggles and settings for each report type:
  - **Rapport end-of-day** : Activé/Désactivé, heure d'envoi auto (default: 20:00), canal (WhatsApp / In-app uniquement)
  - **Rapport hebdomadaire** : Activé/Désactivé, jour (default: Dimanche), heure (default: 20:00), canal
  - **Rapport d'inventaire** : Activé/Désactivé (envoi auto post-inventaire), canal
  - **Alertes stock** : Activé/Désactivé (configured per-product in Epic 2), canal (Push / WhatsApp / Les deux)

**Given** Simon disables the WhatsApp daily report
**When** the setting is saved
**Then** day-close events still trigger local report generation and storage
**And** no WhatsApp message is sent — only in-app notification
**And** Simon can still view all reports in Rapports > Historique

**Given** Simon changes the end-of-day auto-trigger time from 20:00 to 22:00
**When** the setting is saved via `PUT /api/v1/tenants/{tenantId}/preferences`
**Then** the backend scheduler updates the cron job for that tenant
**And** the change takes effect from the next day
**And** a confirmation SnackBar: "Heure de clôture automatique mise à jour : 22h00"

**Given** Simon wants to test his WhatsApp report config
**When** he taps "Envoyer un rapport test"
**Then** a test report is immediately generated with today's data and sent via `WhatsAppPort`
**And** a success confirmation: "Rapport test envoyé sur WhatsApp ✅" or an error message if delivery failed
**And** the test report is NOT saved in the report history (clearly labelled "TEST" in the message)

---

## Epic 8: Alertes, Notifications & Gestion Opérationnelle

Simon reçoit des alertes proactives de stock critique et de tendances de ventes via push et WhatsApp, consulte l'activité de ses employés, désactive et révoque leurs sessions à distance. Les utilisateurs peuvent soumettre un feedback ou demander la suppression complète de leur compte.

**FRs couverts :** FR61–FR62, FR64, FR67–FR68, FR91–FR92

---

### Story 8.1: Alertes Stock Critique & Tendances de Ventes

As a proprietor (Simon),
I want to receive proactive alerts when stock reaches critical levels and when unusual sales trends are detected,
So that I can act before running out of stock and spot opportunities or problems before they escalate.

**Acceptance Criteria:**

**Given** a stock-modifying operation reduces a product's quantity to ≤ its configured threshold
**When** the `StockThresholdBreachedEvent` is emitted (from any stock write: sale, transfer, adjustment)
**Then** a push notification is sent to all of Simon's registered devices: "⚠️ Stock bas — [Product Name] : [current qty] unité(s) restante(s) (seuil : [threshold]) — Boutique [Store Name]"
**And** simultaneously, a WhatsApp message is sent via `WhatsAppPort` to Simon's number with the same content
**And** the notification link deep-links to the product's stock detail screen when tapped
**And** if both push and WhatsApp fail, an in-app badge appears on the Stock module icon in the navigation bar

**Given** simultaneous threshold breaches for multiple products
**When** more than 3 breaches occur within a 60-second window
**Then** a single batched notification is sent instead of individual ones: "⚠️ [N] produits en stock bas — Boutique [Store Name]. Consultez l'onglet Stock."
**And** the individual product details are listed in a single consolidated WhatsApp message
**And** duplicate alerts for the same product are suppressed for 4 hours after the first notification (cooldown per product per store)

**Given** the system detects a sales trend anomaly (FR62)
**When** the hourly sales momentum for a store drops > 40% compared to the same hour on previous 3 days (calculated server-side)
**Then** a push notification is sent: "📉 Baisse des ventes détectée — [Store Name] : activité inhabituelle depuis [X]h"
**And** when a positive spike is detected (> 60% above average hourly rate)
**Then** a push notification is sent: "🚀 Pic de ventes — [Store Name] : [N] ventes en [X] minutes — continuez sur cette lancée !"
**And** trend notifications have a 2-hour cooldown per store to avoid spam
**And** Simon can toggle trend notifications ON/OFF in Paramètres > Rapports (Story 7.5)

**Given** Simon views his notification history
**When** he navigates to the notifications bell icon in the AppBar
**Then** all notifications from the last 30 days are listed in reverse chronological order: type icon, message, store, timestamp, read/unread status
**And** unread notifications show a bold style; tapping marks them as read
**And** the unread count badge on the bell icon updates in real-time via Riverpod state

**Given** FR64 — alertes de statut colis (Plan Payant, Growth phase)
**When** a Plan Free user opens the alerts configuration screen
**Then** the "Alertes de livraison" section is visible but locked with a "Plan Premium" badge
**And** no shipping API calls are made for Plan Free tenants

---

### Story 8.2: Suivi d'Activité Employés

As a proprietor (Simon),
I want to monitor my employees' activity — their last login, sales volume, and connection status — without intruding on their work,
So that I can identify disengagement, coaching needs, or suspicious inactivity remotely.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Équipe
**When** the team management screen loads
**Then** each employee card shows:
  - Name + assigned store
  - Last login: relative time (e.g., "Il y a 2 heures" / "Hier à 9h15" / "Jamais connecté")
  - Today's sales: count + CA in XAF
  - This week's sales: count + CA
  - Active session indicator: green dot if currently active (last API call < 5 minutes ago)
**And** the data loads from local Drift cache (updated on last pull sync)

**Given** Simon taps an employee card
**When** the employee detail screen opens
**Then** he sees a full activity timeline for the last 7 days: per-day sales count, CA, connection time (first login of day), close time (last activity), and day-close status (✅ manuel / ⏰ auto / ❌ non clôturé)
**And** the total for the period is summarized at the top: total sales, total CA, average daily CA, average basket

**Given** an employee has not logged in for > 48 hours
**When** Simon views the team list
**Then** the employee card shows an orange warning badge: "Inactif depuis [N] jours"
**And** a one-tap "Envoyer un rappel WhatsApp" shortcut is available: sends a templated message via `WhatsAppPort`: "Bonjour [Name] ! N'oubliez pas d'ouvrir Keevo aujourd'hui pour suivre vos ventes 😊"

**Given** Simon is on desktop
**When** he views the team activity screen
**Then** the employee cards are displayed in a 2–3 column grid (responsive breakpoints)
**And** a summary row at the top shows the team aggregates: total team CA today, best performer name + CA, team average basket

---

### Story 8.3: Désactivation Employé & Révocation de Sessions

As a proprietor (Simon),
I want to immediately deactivate an employee and revoke all their active sessions remotely,
So that I can respond instantly to terminations or security incidents without physical access to their device.

**Acceptance Criteria:**

**Given** Simon is on the employee detail screen
**When** he taps "Désactiver l'accès"
**Then** a confirmation dialog appears: "Désactiver [Name] ? Toutes ses sessions actives seront révoquées immédiatement. Cette action peut être annulée."
**And** on confirmation: the employee's `status` is set to `INACTIVE` in the backend
**And** all refresh tokens for that employee are invalidated server-side (added to JWT blacklist / refresh token revocation table)
**And** the next API request from any of the employee's devices returns HTTP 401 `{ "domainCode": "ACCOUNT_INACTIVE" }`
**And** this revocation happens within 5 minutes of Simon's confirmation (NFR12)
**And** an `EmployeeDeactivatedEvent` is emitted in the audit log with `actorId` (Simon), `targetUserId`, `occurredAt`

**Given** Loïc's device is actively using the app when his account is deactivated
**When** his JWT expires or he makes a new API call
**Then** he is redirected to the login screen with the message: "Votre accès a été suspendu. Contactez votre propriétaire pour plus d'informations."
**And** no technical details or error codes are shown to Loïc — only the human-readable message

**Given** Simon wants to re-enable a previously deactivated employee
**When** he taps "Réactiver l'accès" on the inactive employee's card
**Then** the employee's `status` is set to `ACTIVE`
**And** the employee can log in again with their existing credentials (no re-invitation required)
**And** an `EmployeeReactivatedEvent` is emitted in the audit log
**And** a confirmation SnackBar: "[Name] peut à nouveau se connecter à Keevo"

**Given** the JWT blacklist implementation
**When** a revoked refresh token is submitted to `POST /api/v1/auth/refresh`
**Then** the server returns HTTP 401 `{ "domainCode": "TOKEN_REVOKED" }`
**And** the token is NOT re-issued regardless of expiry date
**And** the blacklist entry persists for 30 days (after which the token would have expired anyway)

---

### Story 8.4: Feedback Utilisateur & Signalement de Problème

As any user (Simon or Loïc),
I want to submit feedback or report a problem directly from the app with minimal friction,
So that issues are captured instantly in context without requiring me to find an email or leave the app.

**Acceptance Criteria:**

**Given** any user navigates to Paramètres > Aide > Envoyer un feedback
**When** the feedback form opens
**Then** a form appears with:
  - Type selector: "💡 Suggestion" / "🐛 Signaler un problème" / "👍 J'adore Keevo"
  - Description (required, free text, min 10 characters, max 500)
  - Screen context (auto-populated: current route name + app version + platform)
  - Optional: screenshot attachment (camera or gallery, compressed ≤ 500 KB)
**And** the form is available offline — feedback is queued in `sync_queue` and sent on next sync

**Given** the user submits the feedback form
**When** the submission is processed
**Then** the feedback is sent to `POST /api/v1/feedback` with: `type`, `description`, `tenantId`, `userId`, `appVersion`, `platform`, `screenContext`, `submittedAt`
**And** a success SnackBar: "Merci pour votre retour ! Nous l'examinerons rapidement 🙏"
**And** the form resets to empty after successful submission

**Given** a user reports a problem (type: "🐛 Signaler un problème")
**When** the submission is received by the backend
**Then** the feedback is stored in the `feedback` table (shared admin schema) with `priority: NORMAL`
**And** if the description contains keywords: "bloqué", "erreur critique", "données perdues", "ne fonctionne pas" → `priority: HIGH`
**And** HIGH priority items are flagged in the Super Admin dashboard (Epic 9) for Toor's attention

**Given** the user navigates from a specific screen before submitting feedback
**When** the feedback form auto-populates the screen context
**Then** `screenContext` is set to the current go_router route name (e.g., `/pos`, `/inventory/session/123`)
**And** Toor can filter feedback by screen context in the admin dashboard to identify problematic areas

---

### Story 8.5: Suppression de Compte & Données (RGPD)

As a proprietor (Simon),
I want to request the complete deletion of my account and all associated data,
So that I can leave the platform with full assurance that no personal or business data is retained.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Mon Compte > Supprimer mon compte
**When** the deletion request screen loads
**Then** a clear warning is displayed: "⚠️ Cette action est permanente et irréversible. Toutes vos données — produits, ventes, stock, employés, rapports — seront définitivement supprimées. Vous avez 30 jours pour annuler cette demande."
**And** the warning lists exactly what will be deleted: tenant schema (all tables), user accounts (owner + all employees), WhatsApp history, uploaded photos (S3)

**Given** Simon reads the warning and chooses to proceed
**When** he taps "Demander la suppression"
**Then** a confirmation requires him to type his phone number to confirm intent (not just tap "OK")
**And** on confirmation, an `AccountDeletionRequestedEvent` is created with `requestedAt` and `scheduledDeletionAt` (= `requestedAt` + 30 days)
**And** the tenant status is set to `DELETION_PENDING` — all write operations are blocked, reads remain available during the 30-day grace period
**And** a WhatsApp confirmation is sent: "Votre demande de suppression a été enregistrée. Toutes vos données seront supprimées le [date]. Pour annuler, accédez à Paramètres > Mon Compte dans l'app."

**Given** the 30-day grace period is active
**When** Simon changes his mind and opens the app
**Then** a prominent banner shows: "Suppression programmée le [date] — [N] jours restants. [Annuler la suppression]"
**And** tapping "Annuler la suppression" reverts the tenant status to `ACTIVE` and cancels the scheduled deletion
**And** an `AccountDeletionCancelledEvent` is emitted in the audit log

**Given** the 30-day grace period expires
**When** the backend scheduler runs the deletion job
**Then** the tenant PostgreSQL schema `kv_xxxxxx` is dropped entirely (CASCADE)
**And** all user accounts associated with the tenant are deleted from the shared user table
**And** all S3 objects with the tenant prefix are deleted
**And** the WhatsApp opt-out record is preserved (RGPD — proof of valid consent withdrawal)
**And** a final WhatsApp message is sent: "Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo."
**And** Toor (Super Admin) is notified of the deletion in the admin dashboard

**Given** an employee (Loïc) navigates to Paramètres > Mon Compte > Supprimer mon compte
**When** the screen loads
**Then** a message informs him: "Votre compte est lié à [Business Name]. Pour supprimer vos données, demandez à votre propriétaire de vous retirer de l'équipe."
**And** employees cannot initiate full tenant deletion — only the OWNER role can

---

## Epic 9: Super Admin Dashboard (Toor)

Toor pilote l'ensemble de la plateforme Keevo depuis un tableau de bord dédié : gestion des tenants, activation des plans payants, suivi des revenus et analytics, surveillance de la santé système, alertes proactives, et diffusion de notifications push globales.

**FRs couverts :** FR77–FR83

---

### Story 9.1: Gestion des Tenants

As Toor (super admin),
I want to view, search, and manage all registered tenants from a central dashboard,
So that I can monitor platform adoption, handle support requests, and take operational actions on any tenant.

**Acceptance Criteria:**

**Given** Toor logs in to the Super Admin dashboard (separate web interface, same backend, role `SUPER_ADMIN`)
**When** he navigates to the Tenants section
**Then** a paginated table lists all tenants with columns: Tenant ID, Business Name, Owner Phone, Plan (FREE/PAID), Status (ACTIVE / DELETION_PENDING / SUSPENDED), Registration Date, Last Activity Date, Store Count, Employee Count
**And** pagination is 25 rows per page with total count displayed: "Affichage 1–25 sur [N] tenants"
**And** the table loads in < 2 seconds for up to 10 000 tenants (server-side pagination + indexed queries)

**Given** Toor wants to find a specific tenant
**When** he uses the search bar
**Then** he can search by: business name (partial match), owner phone (exact), tenant ID (exact)
**And** results update within 300ms of typing (debounced)
**And** active filters are shown as chips above the table with individual × clear buttons

**Given** Toor applies filters
**When** he uses the filter panel
**Then** he can filter by: Plan (FREE / PAID / ALL), Status (ACTIVE / DELETION_PENDING / SUSPENDED / ALL), Registration date range (date picker), Last activity range
**And** filters compose (AND logic): only tenants matching all active filters are shown

**Given** Toor clicks on a tenant row
**When** the tenant detail panel opens (right-side drawer)
**Then** he sees: full tenant profile, owner contact, store list with CA per store, employee list with last login, and a complete audit log for the last 30 days (all `*Event` records for this tenant)
**And** the audit log is paginated: 50 events per page, most recent first

**Given** a tenant status is `DELETION_PENDING`
**When** Toor views the tenant list
**Then** the row is highlighted in orange with a countdown: "Suppression dans [N] jours"
**And** Toor can force-cancel the deletion or force-execute it immediately from the detail panel (with admin confirmation dialog)

---

### Story 9.2: Activation Plan Payant

As Toor (super admin),
I want to manually activate or deactivate the paid plan for any tenant,
So that I can handle off-platform payments (mobile money, cash, invoices) and unlock premium features for paying clients.

**Acceptance Criteria:**

**Given** Toor views a tenant detail panel
**When** the tenant is on Plan FREE
**Then** a "Passer au Plan Payant" button is visible with a date picker for the expiry date
**And** on confirmation: the tenant's `plan` is set to `PAID`, `planExpiresAt` is set to the chosen date, and a `PlanActivatedEvent` is emitted with `actorId` (Toor), `tenantId`, `activatedAt`, `expiresAt`
**And** the tenant immediately gains access to all Plan Payant features (FR64 shipping alerts, advanced reports)
**And** a WhatsApp notification is sent to the owner: "🎉 Votre compte Keevo Premium est activé jusqu'au [date]. Profitez de toutes les fonctionnalités !"

**Given** a tenant's paid plan approaches expiry
**When** `planExpiresAt` is within 7 days
**Then** an alert appears in Toor's admin dashboard: "⚠️ [Business Name] — plan expire dans [N] jours"
**And** the tenant owner receives a WhatsApp reminder 7 days and 1 day before expiry: "⏰ Votre abonnement Keevo Premium expire le [date]. Contactez-nous pour renouveler."

**Given** a paid plan expires (`planExpiresAt` < now)
**When** the expiry job runs (daily at 01:00 UTC)
**Then** the tenant's `plan` is automatically reverted to `FREE`
**And** Plan Payant features are gated again (FR64, advanced reports)
**And** a `PlanExpiredEvent` is emitted and flagged in Toor's alerts

**Given** Toor needs to suspend a tenant (fraud, abuse, non-payment dispute)
**When** he sets the tenant status to `SUSPENDED`
**Then** all API calls from that tenant return HTTP 403 `{ "domainCode": "TENANT_SUSPENDED" }`
**And** the Flutter app shows a full-screen blocking message: "Votre compte est suspendu. Contactez le support Keevo."
**And** a `TenantSuspendedEvent` is emitted with reason (free text, required)

---

### Story 9.3: Dashboard Revenus

As Toor (super admin),
I want to visualize the platform's financial performance metrics in one screen,
So that I can track revenue growth, identify churn, and make informed business decisions.

**Acceptance Criteria:**

**Given** Toor navigates to the Revenus section of the admin dashboard
**When** the page loads
**Then** a summary card row displays: MRR (Monthly Recurring Revenue in XAF), ARR (MRR × 12), Active Paid Tenants, Churn Rate current month (%), New Paid Tenants this month
**And** all values reflect the state as of the last admin page load (no auto-refresh needed — manual refresh button available)

**Given** Toor views the revenue chart
**When** the 12-month MRR trend chart is displayed
**Then** a bar chart shows MRR per calendar month for the last 12 months
**And** hovering a bar shows: month label, MRR total, number of paying tenants, number of new conversions, number of churned tenants
**And** a trend indicator next to the current MRR card shows: ▲ +[X]% vs last month (green) or ▼ −[X]% vs last month (red)

**Given** Toor wants to analyze churn
**When** he views the Churn section
**Then** a table lists all tenants whose plan reverted from PAID to FREE in the selected month, with: business name, plan end date, tenure (how long they were paid), last CA before churn
**And** the data can be exported as CSV: "Exporter CSV" button downloads `keevo-churn-YYYY-MM.csv`

**Given** Toor selects a date range filter
**When** he applies Start Date / End Date
**Then** all revenue metrics and charts update to reflect the selected period
**And** the date range defaults to: current calendar month

---

### Story 9.4: Analytics Plateforme

As Toor (super admin),
I want to monitor platform usage metrics — active users, retention, and feature adoption — to prioritize development and validate product-market fit.

**Acceptance Criteria:**

**Given** Toor navigates to the Analytics section
**When** the page loads
**Then** a KPI card row shows: DAU (Distinct Active Users today), MAU (last 30 days), DAU/MAU ratio (engagement index), Total Tenants, Total Stores, Total Sales Processed (count, all time), Total GMV (Gross Merchandise Value in XAF, all time)
**And** "Active" is defined as: a user who made ≥ 1 authenticated API call within the period

**Given** Toor views user retention
**When** he opens the Rétention tab
**Then** a weekly cohort retention table is displayed: each row = a signup week cohort, columns = Week 1, Week 2, ..., Week 8 retention percentages
**And** cells are colour-coded: green (> 60%), yellow (30–60%), red (< 30%)

**Given** Toor wants to see top performing stores
**When** he opens the Top Boutiques tab
**Then** a table lists the top 20 stores by GMV for the selected period with: store name, tenant name, CA, transaction count, average basket, period-over-period growth %
**And** the table is sortable by any column header (click to sort ASC, click again DESC)

**Given** Toor wants to monitor feature adoption
**When** he views the Adoption section
**Then** a horizontal bar chart shows: % of active tenants using each major feature in the last 30 days — POS, Inventory Sessions, Stock Transfers, WhatsApp Reports, Alerts, Offline Sync
**And** this data helps Toor prioritize roadmap decisions

---

### Story 9.5: Santé Système, Alertes & Notifications Push Globales

As Toor (super admin),
I want to monitor the health of the Keevo platform in real-time and send global announcements to all users,
So that I can detect incidents before users report them and communicate planned maintenance or new features proactively.

**Acceptance Criteria:**

**Given** Toor navigates to the Santé Système section
**When** the page loads
**Then** a status card grid displays: API Uptime (last 30 days %), Current API Latency (p50, p95, p99 in ms), Error Rate (last 1h, % of 5xx), Active DB Connections, Sync Queue Depth (total unprocessed sync records across all tenants), Last Flyway migration status (✅ / ❌)
**And** all metrics are sourced from Spring Actuator (`/actuator/health`, `/actuator/metrics`) and displayed without caching (live on page load)

**Given** any health metric breaches a threshold
**When** the admin page is loaded and a threshold is exceeded (API error rate > 2%, p99 > 2s, queue depth > 1000)
**Then** the affected card turns red with an alert icon
**And** a sticky alert banner appears at the top of all admin pages: "🔴 Incident en cours — [metric name] dépasse le seuil. Vérifiez les logs CloudWatch."

**Given** Toor's alerts panel (FR82)
**When** he opens the Alertes section
**Then** a unified feed shows all platform-level alerts in reverse chronological order: HIGH priority feedback items (Story 8.4), tenants in DELETION_PENDING, expired paid plans still showing as PAID (data integrity check), failed WhatsApp delivery batches (> 10 failures in 1h), sync conflicts unresolved > 24h per tenant
**And** each alert has: severity badge (HIGH/MEDIUM/LOW), description, timestamp, and a "Marquer comme traité" action

**Given** Toor wants to send a global notification to all users (FR83)
**When** he navigates to Notifications > Diffusion Globale
**Then** a form allows: Title (max 80 chars), Body (max 200 chars), Target (ALL / PAID_ONLY / FREE_ONLY / SPECIFIC_TENANT), Schedule (Now / Scheduled date-time picker)
**And** a live preview shows how the notification will appear on Android and iOS
**And** on confirmation: the notification is queued and sent via Firebase Cloud Messaging to all matching device tokens
**And** a `GlobalPushSentEvent` is emitted with: `actorId` (Toor), `targetSegment`, `recipientCount`, `sentAt`, `title`

**Given** Toor submits a global notification
**When** the send is confirmed
**Then** a delivery status card appears: "Envoi en cours — [N] appareils ciblés"
**And** within 5 minutes the card updates to: "✅ Envoyé — [N] livrés / [M] échecs"
**And** the notification appears in each targeted user's in-app notification history (Story 8.1)
**And** the global notification history is preserved in the admin panel for 90 days: date, title, target segment, delivered count, failure count
