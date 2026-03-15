# Requirements Inventory

## Functional Requirements

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
- FR15: Le système peut initialiser le statut de souscription en **Plan Premium Trial (6 mois offerts)** à chaque nouveau tenant
- FR15b: Le système peut basculer automatiquement un tenant de `PREMIUM_TRIAL` vers `FREE` à expiration des 6 mois sans paiement

**Gestion des Souscriptions (FR16–FR20)**
- FR16: Le système peut appliquer les limites du plan gratuit : **max 1 boutique**, 500 produits, **3 employés** — *mise à jour 2026-03-06 (ancienne valeur: 3 boutiques, 5 employés)*
- FR17: Le système peut afficher un message clair avec CTA upgrade lorsqu'une limite est atteinte
- FR18: Le système peut rétrograder automatiquement un tenant vers le plan `FREE` à l'expiration de son abonnement `PREMIUM` ou `PREMIUM_TRIAL` (pas de suspension complète — données conservées, accès limité au plan Free)
- FR19: Le système peut conserver toutes les données lors d'une rétrogradation vers le plan Free
- FR20: Un propriétaire peut consulter son statut de souscription (Plan Free / Premium Trial / Premium Actif — avec date d'expiration du trial ou de l'abonnement payant)

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
- FR32: Un propriétaire peut initier un transfert de stock entre boutiques avec traçabilité complète — **flux deux-étapes** : Envoi (source décrémentée, statut IN_TRANSIT) puis Réception (destination incrémentée, statut COMPLETED)
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

## NonFunctional Requirements

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

## Additional Requirements

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
- ARCH18: Chaque tenant dispose d'un schéma PostgreSQL isolé `kv_xxxxxx` (schema-per-tenant, provisionnement via `TenantSchemaProvisioner` en DDL JDBC programmatique — pas de SQL files ni Flyway, ThreadLocal `TenantContext` pour résolution du schéma courant)
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

## FR Coverage Map

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
FR15: Epic 1 - Initialisation souscription en Plan Premium Trial (6 mois) — *mise à jour 2026-03-06*
FR15b: Epic 1 - Bascule automatique PREMIUM_TRIAL → FREE à expiration
FR16: Epic 1 - Application limites plan gratuit (**1 boutique**, 500 produits, **3 employés**) — *mise à jour 2026-03-06*
FR17: Epic 1 - Message upgrade CTA quand limite atteinte
FR18: Epic 1 - Rétrogradation automatique vers FREE à expiration (pas de suspension complète)
FR19: Epic 1 - Conservation données lors de rétrogradation vers plan Free
FR20: Epic 1 - Consultation statut souscription (Free / Premium Trial / Premium)
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
FR59: Epic 6 (Story 6.3 — envoi WhatsApp depuis rapport inventaire) + Epic 7 (Story 7.5 — configuration)
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
