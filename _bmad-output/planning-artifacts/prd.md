---
stepsCompleted:
  - step-01-init
  - step-02-discovery
  - step-02b-vision
  - step-02c-executive-summary
  - step-03-success
  - step-04-journeys
  - step-05-domain
  - step-06-innovation
  - step-07-project-type
  - step-08-scoping
  - step-09-functional
  - step-10-nonfunctional
  - step-11-polish
  - step-12-complete
inputDocuments:
  - _bmad-output/planning-artifacts/product-brief-AI-2026-02-13.md
  - _bmad-output/planning-artifacts/research/market-Keevo-research-2026-02-26.md
  - _bmad-output/planning-artifacts/research/technical-stack-technique-keevo-research-2026-02-26.md
documentCounts:
  briefCount: 1
  researchCount: 2
  brainstormingCount: 0
  projectDocsCount: 0
workflowType: 'prd'
classification:
  projectType: Mobile/Desktop App (SaaS B2B)
  domain: Retail / B2B Commerce
  complexity: Medium
  projectContext: Greenfield
---

# Product Requirements Document - Keevo

**Author:** Toor
**Date:** 2026-02-26T04:18:06+01:00

## Executive Summary

Keevo est la future infrastructure du commerce local africain, débutant comme une application mobile et desktop de gestion d'inventaire offline-first. Le produit transforme les opérations de survie au jour le jour des petits commerçants informels et des grossistes multi-boutiques en une croissance maîtrisée. En éliminant le chaos des réconciliations manuelles et l'absence cruciale de visibilité sur les stocks croisés, Keevo restaure le contrôle financier et opérationnel, établissant une fondation de confiance pour construire à terme la première plateforme e-commerce basée sur les données agrégées du marché informel.

### What Makes This Special

Keevo se différencie par sa philosophie *"Augmenter, Ne pas Remplacer" (Augment, Don't Replace)*. Le produit s'intègre de manière invisible aux flux de travail existants — délivrant notamment des rapports automatisés directement via WhatsApp — supprimant ainsi toute résistance à l'adoption face aux ERP traditionnels. Construit sur un moteur de synchronisation "offline-first" (garantissant 7 jours d'opérations sans connectivité internet), et conçu avec une interface dépourvue d'encombrement comptable, Keevo résout le besoin immédiat de visibilité et de prévention des pertes, transformant une corvée quotidienne en un outil de pilotage instantané.

## Project Classification

- **Project Type:** Mobile/Desktop App (SaaS B2B)
- **Domain:** Retail / B2B Commerce
- **Complexity:** Medium
- **Project Context:** Greenfield

## Success Criteria

### User Success

**Pour Simon (Propriétaire multi-boutiques) :**
- Réduction du temps consacré à l'inventaire hebdomadaire de 6-8 heures à moins de 2 heures.
- Élimination des processus de clôture journalière manuels (de 30-45 minutes à un envoi quasi instantané via WhatsApp).
- Réduction de 50% des ventes perdues causées par des ruptures de stock grâce à la visibilité cross-boutique en temps réel.

**Pour Loïc (Employé vendeur) :**
- Temps de recherche de la disponibilité d'un produit inter-boutiques réduit de 5-15 minutes (appels téléphoniques aléatoires) à moins de 30 secondes via l'application.

### Business Success

- **Adoption initiale (Mois +3) :** Atteindre 100 propriétaires actifs gérant 100 boutiques avec plus de 1 000 transactions enregistrées quotidiennement.
- **Rétention (Mois +1) :** Plus de 80% des utilisateurs restent actifs après 30 jours, avec un taux d'abandon (churn) inférieur à 5% par mois.
- **Acquisition :** 30% des nouvelles acquisitions au Mois 3 doivent provenir du bouche-à-oreille (preuve de la validation du produit par le marché).

### Technical Success

- **Résilience hors-ligne (Offline-First) :** L'application doit rester 100% fonctionnelle hors-ligne pendant 7 jours, avec une synchronisation complète des données (sans perte) en moins de 60 secondes une fois la connexion rétablie.
- **Accessibilité multiplateforme :** Les composants essentiels doivent tourner de manière fluide sur des smartphones Android d'entrée de gamme, ainsi que sur l'application Desktop (Linux/Windows).

### Measurable Outcomes

- Le "Aha! Moment" doit être atteint par 60% des utilisateurs avant la semaine 4 (marqué par l'accomplissement de leur premier inventaire automatisé ou rapport WhatsApp envoyé).
- Taux de conversion de 25% du modèle gratuit au modèle payant après un an de fonctionnement.

## Product Scope

### MVP - Minimum Viable Product

- Gestion basique des stocks multi-boutiques et vue d'ensemble en temps réel.
- Interface Point of Sale (POS) adaptée aux employés.
- Synchronisation offline-first performante via PowerSync et architecture multi-tenant sur Spring Boot.
- Envoi automatisé des rapports journaliers et d'inventaire via WhatsApp.
- Rôles basiques : Propriétaire et Employé.
- **Super Admin Dashboard (Keevo Platform Management) :** gestion des tenants, activation manuelle des comptes payants, dashboard revenus, analytics plateforme, support & alertes proactives, santé du système.

### Growth Features (Post-MVP)

- **Tracking colis importés :** suivi manuel configurable (Commandé → En Transit → Réceptionné → Partiellement reçu) ET intégration APIs transporteurs (DHL, FedEx, 17TRACK) pour le tracking automatique en temps réel.
- **Module Facturation complet :** génération automatique de factures PDF, gestion du cycle de facturation, et envoi direct des factures aux clients via WhatsApp.
- Rôles avancés (gérant de boutique, comptable).
- **Système d'affiliation/influenceurs :** codes promos, suivi des commissions, dashboard dédié pour les influenceurs, et mécanisme de parrainage entre commerçants.

### Vision (Future)

- Recommandations pilotées par l'Intelligence Artificielle pour les réassorts et l'optimisation des prix, en exploitant les données agrégées.
- Évolution vers une plateforme marketplace E-commerce B2B centralisant l'approvisionnement des commerçants africains.

## User Journeys

### Journey 1: Simon — Le Grossiste qui Reprend le Contrôle

**Scène d'ouverture :** Simon, 28 ans, grossiste vêtements à Douala, gère un warehouse et 3 boutiques. Chaque soir, il passe 45 minutes à éplucher les messages WhatsApp de ses employés pour reconstituer les ventes du jour. Le week-end, 6 à 8 heures sont sacrifiées à l'inventaire. Pire encore : un client appelle pour une commande en gros — Simon doit passer 3 appels à ses gérants pour savoir s'il a le stock. Souvent, il n'obtient pas de réponse. Le client va voir ailleurs.

**Action montante :** Un autre commerçant du marché lui parle de Keevo. Simon télécharge l'app gratuite, crée son compte en 5 minutes avec son numéro WhatsApp, ajoute ses 3 boutiques et commence à enregistrer quelques produits. Pas besoin de tout rentrer d'un coup — Keevo permet l'adoption progressive.

**Climax :** Semaine 2 — Un client appelle pour une commande de 50 polos. Simon ouvre Keevo : en 10 secondes, il voit que Boutique A en a 20, Boutique B en a 35. Il confirme immédiatement la commande et organise le transfert. Sans Keevo, cette vente aurait été perdue. Semaine 4 — Premier inventaire weekend avec Keevo : 1h30 au lieu de 8h. C'est le déclic irréversible.

**Résolution :** Mois 2 — Simon part en Chine pour un approvisionnement. Depuis Shanghai, il ouvre Keevo sur son téléphone et voit toutes ses ventes en temps réel, tous ses stocks, tous ses employés actifs. Il contrôle son business depuis l'autre bout du monde. Il passe au plan payant et recommande Keevo à 3 autres commerçants. Keevo est devenu un réflexe matinal, comme WhatsApp.

### Journey 2: Loïc — L'Employé qui Devient un Héros

**Scène d'ouverture :** Loïc, 22 ans, vendeur permanent dans la boutique de Simon à Akwa. Chaque soir, il passe 30-45 minutes à calculer manuellement les ventes du jour et à envoyer le bilan sur WhatsApp. Les week-ends d'inventaire sont une corvée épuisante. Quand un client demande un produit en rupture, Loïc appelle ses collègues dans les autres boutiques — souvent sans réponse. Le client part frustré. Simon soupçonne parfois des erreurs ou des vols, ce qui stresse Loïc.

**Action montante :** Simon installe Keevo et forme Loïc en 15 minutes. L'interface POS est simple : sélectionner le produit, entrer la quantité, le client paie. Jour 1-3, Loïc est lent. Jour 4, il prend le rythme. L'interface est plus intuitive que ce qu'il imaginait.

**Climax :** Un client demande des baskets Nike taille 42. Loïc n'en a plus. Avant, il aurait dit "désolé". Maintenant, il ouvre Keevo, vérifie instantanément : "Oui madame, on en a 3 paires dans notre boutique de Bonanjo, je vous y envoie !". Le client part ravi. Le soir, au lieu de 45 minutes de calculs, Loïc appuie sur "Clôturer journée" — le rapport part automatiquement sur WhatsApp à Simon. C'est terminé en 2 minutes.

**Résolution :** Loïc ne peut plus imaginer travailler sans Keevo. Tout est tracé automatiquement — plus de soupçons du patron. Il montre fièrement l'app à ses amis. Son rêve : quand il ouvrira sa propre boutique un jour, la première chose qu'il installera sera Keevo.

### Journey 3: Toor (Super Admin) — Le Pilote de la Plateforme

**Scène d'ouverture :** Toor est le fondateur et administrateur unique de Keevo en V1. Il doit avoir une vue d'ensemble sur toute la plateforme : combien de commerçants se sont inscrits, qui utilise le plan gratuit vs payant, quels tenants n'ont pas synchronisé depuis plusieurs jours et risquent la suspension.

**Action montante :** Toor accède au Super Admin Dashboard. Il voit en un coup d'œil : 87 tenants actifs, 12 payants, MRR de 480 000 XAF. Un tenant (KV-A3F29X) n'a pas synchronisé depuis 5 jours — une alerte WhatsApp automatique a été envoyée. Un autre tenant a expiré son abonnement hier — le compte est suspendu en lecture seule. Un commerçant a payé via Mobile Money — Toor active manuellement son compte payant.

**Climax :** Toor remarque que la base de données produits agrégée vient de franchir les 50 000 références uniques. C'est le seuil qu'il s'était fixé pour commencer à envisager la future marketplace. Les courbes de croissance des tenants et des ventes sont en hausse — la rétention M+1 atteint 82%. Le product-market fit est confirmé.

**Résolution :** Toor lance une notification globale push vers tous les tenants pour annoncer une nouvelle fonctionnalité. Il exporte les analytics pour préparer un deck investisseurs. La plateforme est saine, les données s'agrègent, la vision à long terme se construit.

### Journey 4: Simon — Parcours d'Erreur (Edge Case Offline)

**Scène d'ouverture :** Simon est en déplacement à Bafoussam (zone avec connexion instable). Son employé à Douala enregistre 15 ventes pendant la journée, complètement hors-ligne. Simultanément, l'employé de l'autre boutique vend les mêmes produits. Les deux devices fonctionnent sans problème grâce au mode offline.

**Action montante :** Après 2 jours hors-ligne, l'employé de Bafoussam retrouve la connexion 4G. Keevo lance automatiquement la synchronisation. Les deltas de stock (ventes de chaque device) sont transmis au serveur — pas de conflit car Keevo envoie les variations de quantité (delta-based), pas les valeurs absolues.

**Climax :** Le stock central se met à jour correctement : les 15 ventes de Douala ET les 8 ventes de Bafoussam sont comptabilisées. Aucune donnée perdue. Le rapport consolidé de fin de journée arrive sur WhatsApp avec les bons chiffres.

**Résolution :** Simon vérifie les chiffres — tout est cohérent. Il peut prendre confiance dans le système même quand la connexion est intermittente. C'est exactement la promesse offline-first qui fait la différence avec les concurrents.

### Journey Requirements Summary

| Parcours | Capacités Révélées |
|----------|--------------------|  
| Simon (Success Path) | Vue multi-boutiques temps réel, transferts inter-boutiques, rapports WhatsApp automatisés, inventaire automatisé, POS, onboarding progressif |
| Loïc (Employé) | Interface POS ultra-simple, vérification cross-boutique, clôture journalière en 1 clic, traçabilité complète des ventes par employé |
| Toor (Super Admin) | Dashboard admin, gestion tenants, activation manuelle paiements, analytics globales, alertes proactives, notifications push |
| Simon (Edge Case Offline) | Sync offline-first 7 jours, résolution de conflits delta-based, sync automatique au retour en ligne, intégrité des données garantie |

## Innovation & Novel Patterns

### Detected Innovation Areas

1. **"Augment, Don't Replace" — Paradigme d'intégration invisible**
   Keevo ne demande pas aux utilisateurs de changer de comportement. Il s'insère dans les flux existants (WhatsApp, habitudes quotidiennes) plutôt que de les remplacer — une approche radicalement différente des ERP/POS traditionnels qui exigent une transformation complète des habitudes.

2. **Data Flywheel → Marketplace — Modèle de plateforme à effet de réseau**
   L'agrégation progressive des données produits, prix et ventes de milliers de commerçants crée un actif data inégalable. Cette intelligence commerciale alimente la future marketplace e-commerce — un modèle stratégique type "Razor & Blades inversé" où l'outil gratuit construit la donnée monétisable.

3. **Offline-First Architecture Conçue pour l'Afrique**
   Là où les concurrents traitent le mode hors-ligne comme un fallback, Keevo le conçoit comme le mode par défaut. La synchronisation delta-based (envoi des variations, pas des valeurs absolues) résout le problème classique des conflits multi-device en environnement à connectivité intermittente.

### Market Context & Competitive Landscape

Le marché du SaaS B2B en Afrique subsaharienne est dominé par des solutions "importées" (Odoo, Sage) ou des POS locaux calqués sur les modèles occidentaux. Aucun acteur ne combine simultanément : offline-first architectural, intégration WhatsApp native, et stratégie de construction d'une base de données commerciale agrégée. Keevo occupe un espace blanc.

### Validation Approach

- **Phase 1 (M+1-3) :** Valider l'adoption sans friction — le taux de rétention M+1 >80% prouvera que la philosophie "Augment" fonctionne.
- **Phase 2 (M+3-12) :** Valider la valeur data — suivre la croissance de la base de données produits agrégée comme KPI stratégique.
- **Phase 3 (M+12+) :** Valider la marketplace — le taux de conversion vers le commerce en ligne validera le modèle complet.

### Risk Mitigation

| Risque | Mitigation |
|--------|------------|
| L'effet de réseau data ne se matérialise pas assez vite | Le produit core (gestion de stock) crée de la valeur indépendamment de la marketplace future |
| Les opérateurs Mobile Money lancent leurs propres outils | L'avantage offline-first et la richesse des données accumulées créent un moat difficile à répliquer |
| L'adoption stagne après les early adopters | Le modèle freemium + bouche-à-oreille réduit les barrières. Le système d'affiliation/influenceurs (V2) accélère la diffusion virale |

## Mobile/Desktop App & SaaS B2B — Specific Requirements

### Project-Type Overview

Keevo est une application hybride Mobile (Flutter) + Desktop (Tauri 2.0) avec un backend SaaS multi-tenant (Spring Boot / PostgreSQL). L'architecture est "offline-first by design" — le mode connecté est un bonus, pas un prérequis.

### Platform Requirements

| Plateforme | Support | Priorité |
|------------|---------|----------|
| Android (Mobile) | Flutter — smartphones entrée de gamme (Android 8+) | **MVP** |
| iOS (Mobile) | Flutter — via même codebase | **MVP** |
| Linux (Desktop) | Tauri 2.0 | **MVP** |
| Windows (Desktop) | Tauri 2.0 | **MVP** |
| macOS (Desktop) | Tauri 2.0 | Growth |

### Offline Strategy

- **Mode par défaut :** Toutes les opérations critiques (ventes, inventaire, transferts) fonctionnent 100% hors-ligne.
- **Base locale :** SQLite via Drift (Flutter) / tauri-plugin-sql (Desktop).
- **Sync Engine :** PowerSync en mode delta-based pour la résolution de conflits.
- **Durée garantie :** 7 jours d'opérations hors-ligne sans perte de données.
- **Sync auto :** Synchronisation automatique dès que la connectivité est rétablie (<60 secondes).

### Tenant Model (Multi-tenancy)

- **Isolation :** Schema-per-tenant sur PostgreSQL (schéma `kv_xxxxxx` par propriétaire).
- **Provisioning :** Création automatique du schéma au signup, tenant_id porté dans le JWT.
- **PowerSync Sync Rules :** Tables partagées avec filtre `tenant_id` pour l'isolation des données sync.
- **Suspension :** Passage en lecture seule à l'expiration de l'abonnement payant.

### RBAC Matrix (Permission Levels)

| Rôle | Permissions |
|------|-------------|
| **Super Admin (Toor)** | Gestion plateforme complète, tous tenants, analytics globales, activation paiements |
| **Owner (Propriétaire)** | Toutes boutiques du tenant, CRUD produits, rapports, gestion employés, inventaire |
| **Employee (Employé)** | POS (ventes), consultation stock cross-boutique, clôture journalière — périmètre restreint à la boutique assignée |
| **Shop Manager** *(Growth)* | Droits Owner limités à une boutique spécifique |

### Notification Model

- **WhatsApp (principale) :** Rapports journaliers automatisés, alertes de stock, factures clients — via Africa's Talking BSP.
- **Push Notifications :** Alertes critiques (sync échouée, stock critique) via Firebase Cloud Messaging.
- **In-App :** Notifications internes pour les transferts inter-boutiques et les mises à jour système.

### Integration Map

| Service | Usage | Priorité |
|---------|-------|----------|
| WhatsApp Business API (Africa's Talking) | Rapports, alertes, factures | **MVP** |
| PowerSync | Synchronisation offline-first | **MVP** |
| AWS RDS (PostgreSQL) | Base cloud multi-tenant | **MVP** |
| AWS S3 | Stockage fichiers (images produits, PDF) | **MVP** |
| DHL / FedEx / 17TRACK | Tracking colis | Growth |
| Mobile Money (Orange/MTN) | Paiements abonnements | Growth |

### Billing Model

- **Freemium :** Gratuit — max 3 boutiques, 500 produits, 5 employés, fonctionnalités core complètes.
- **Premium :** Payant — multi-boutiques, rapports avancés, support prioritaire.
- **Activation manuelle :** V1 — Toor active manuellement les comptes payants après preuve de paiement Mobile Money.
- **Paiement automatique :** Growth — intégration directe Mobile Money.

### Implementation Considerations

- **App Store :** L'app doit respecter les guidelines Google Play et Apple App Store (permissions, privacy policy, in-app purchase rules si applicable).
- **Device Permissions :** Caméra (scan codes-barres futur), Storage (cache offline), Network State (détection connectivité), Notifications.
- **Performance :** L'app doit rester fluide sur des devices avec 2 Go de RAM et stockage limité.
- **Data Migration :** Prévoir un mécanisme d'import CSV pour la migration des stocks existants.

## Project Scoping & Phased Development

### MVP Strategy & Philosophy

**MVP Approach :** MVP "Problem-Solving" — Démontrer que Keevo résout le problème immédiat (visibilité stock multi-boutiques + rapports automatisés) de manière suffisamment convaincante pour que les utilisateurs ne puissent plus revenir en arrière.

**Resource Requirements :** Développeur solo full-stack (Toor) avec expertise Flutter, Spring Boot, et infrastructure AWS. Délai estimé MVP : 3-4 mois.

### MVP Feature Set (Phase 1)

**Core User Journeys Supported :**
- ✅ Simon — Le Grossiste qui Reprend le Contrôle (parcours complet)
- ✅ Loïc — L'Employé qui Devient un Héros (parcours complet)
- ✅ Toor — Le Pilote de la Plateforme (parcours Super Admin)
- ✅ Simon — Edge Case Offline (résilience sync)

**Must-Have Capabilities :**

| Module | Fonctionnalité | Justification |
|--------|---------------|---------------|
| Auth & Onboarding | Inscription WhatsApp, Login JWT, création tenant auto | Sans cela, personne n'entre |
| Gestion Produits | CRUD produits, catégories, variantes (taille/couleur) | Cœur du moteur de données |
| Gestion Stock | Vue multi-boutiques, transferts inter-boutiques, alertes stock bas | Différenciateur #1 — "la visibilité" |
| POS | Interface employé, enregistrement ventes, clôture journalière | Journey Loïc — usage quotidien |
| Rapports WhatsApp | Rapport fin de journée auto, rapport inventaire | Différenciateur #2 — "Augment, Don't Replace" |
| Sync Offline-First | PowerSync, delta-based sync, 7 jours offline | Différenciateur #3 — fiabilité Afrique |
| Super Admin Dashboard | Gestion tenants, activation paiements, analytics, alertes système | Journey Toor — pilotage plateforme |

### Post-MVP Features

**Phase 2 — Growth (M+4 à M+9) :**

| Feature | Valeur Ajoutée |
|---------|---------------|
| Tracking Colis (Manuel + APIs) | Suivi imports Chine — besoin fréquent des grossistes |
| Module Facturation Complet | Factures PDF + envoi WhatsApp clients — professionnalisation |
| Système Affiliation/Influenceurs | Codes promo, commissions, parrainage — accélérateur virale |
| Rôles avancés (Shop Manager) | Délégation fine du contrôle par boutique |
| Paiement Mobile Money automatique | Conversion freemium → payant sans friction |

**Phase 3 — Expansion (M+10+) :**

| Feature | Valeur Ajoutée |
|---------|---------------|
| IA Recommandations | Réassorts optimisés, pricing intelligent basé sur les données agrégées |
| Marketplace B2B | Plateforme e-commerce reliant fournisseurs et commerçants |
| macOS Desktop | Couverture plateforme étendue |
| Comptabilité avancée | Rapprochement bancaire, bilans, exports comptables |

### Risk Mitigation Strategy

**Risques Techniques :**
- *Risque :* La synchronisation offline-first delta-based est le composant le plus complexe.
- *Mitigation :* PowerSync est une solution éprouvée. Commencer par un prototype de sync dès la semaine 1 pour valider l'architecture avant de construire le reste.

**Risques Marché :**
- *Risque :* Le bouche-à-oreille ne démarre pas assez vite.
- *Mitigation :* Cibler 5-10 commerçants pilotes à Douala (contacts personnels) pour un onboarding accompagné. Leur succès visible dans le marché local déclenchera l'effet viral.

**Risques Ressources :**
- *Risque :* Développeur solo — vélocité limitée.
- *Mitigation :* Le MVP est volontairement un "lean MVP" avec des processus manuels acceptés (activation paiements par Toor, support WhatsApp direct). Les automatisations viendront en Phase 2.

## Functional Requirements

### Authentification & Onboarding

- **FR1:** Un propriétaire peut créer un compte via son numéro WhatsApp (pas d'email obligatoire)
- **FR2:** Le système peut provisionner automatiquement un tenant isolé (schéma PostgreSQL `KV-XXXXXX`) à l'inscription
- **FR3:** Un utilisateur peut se connecter via JWT sur mobile et desktop
- **FR4:** Un propriétaire peut choisir son secteur d'activité à l'inscription (Vêtements, Électronique, Librairie, Électroménager)
- **FR5:** Le système peut appliquer un template sectoriel pré-configuré (catégories, champs, organisation) selon le secteur choisi
- **FR6:** Un propriétaire peut compléter le wizard d'onboarding (nom, secteur, boutiques) en moins de 5 minutes
- **FR7:** Le système peut afficher des tutoriels vidéo intégrés en français (2 min par module clé)

### Provisioning & Initialisation Tenant

- **FR8:** Le système peut créer automatiquement le compte utilisateur-propriétaire (admin du tenant) avec le rôle "Owner" à l'inscription
- **FR9:** Le système peut créer automatiquement les tables du schéma tenant dans PostgreSQL (produits, stocks, ventes, boutiques, utilisateurs, etc.)
- **FR10:** Le système peut initialiser les rôles par défaut du tenant (Owner, Employee) avec leurs permissions respectives
- **FR11:** Le système peut pré-configurer les catégories produits par défaut selon le template sectoriel choisi
- **FR12:** Le système peut initialiser les préférences de notification par défaut (heure du rapport end-of-day, alertes stock activées)
- **FR13:** Le système peut créer la première boutique par défaut du tenant (saisie dans le wizard)
- **FR14:** Le système peut générer le code unique tenant (`KV-XXXXXX`) et l'associer au schéma créé
- **FR15:** Le système peut initialiser le statut de souscription par défaut (Plan Free, limites actives)

### Gestion des Souscriptions

- **FR16:** Le système peut appliquer les limites du plan gratuit : max 3 boutiques, 500 produits, 5 employés
- **FR17:** Le système peut afficher un message clair avec CTA upgrade lorsqu'une limite est atteinte
- **FR18:** Le système peut suspendre automatiquement un tenant en lecture seule à l'expiration de son abonnement payant
- **FR19:** Le système peut conserver les données d'un tenant suspendu (lecture seule, pas de suppression)
- **FR20:** Un propriétaire peut consulter son statut de souscription (Actif / Expiré / Suspendu)

### Gestion des Produits

- **FR21:** Un propriétaire peut créer, modifier et archiver des produits (nom, description, photo, référence)
- **FR22:** Un propriétaire peut définir des variantes produit (taille, couleur)
- **FR23:** Un propriétaire peut saisir le prix de vente, le prix d'achat et les coûts de transport par produit
- **FR24:** Le système peut calculer automatiquement la marge nette par produit (prix vente − prix achat − transport)
- **FR25:** Un propriétaire peut organiser les produits par catégories flexibles
- **FR26:** Un propriétaire peut configurer un seuil de stock minimum par produit (alerte)
- **FR27:** Un propriétaire peut consulter l'historique complet des mouvements de stock (entrées, sorties, transferts, ajustements)
- **FR28:** Un propriétaire peut importer des produits via fichier CSV
- **FR29:** Un employé peut consulter le catalogue produits de sa boutique

### Gestion Multi-Boutiques & Warehouse

- **FR30:** Un propriétaire peut créer et gérer plusieurs boutiques + un warehouse principal
- **FR31:** Un propriétaire peut visualiser les niveaux de stock de toutes ses boutiques en temps réel sur une vue centralisée
- **FR32:** Un propriétaire peut initier un transfert de stock entre boutiques avec traçabilité complète
- **FR33:** Un employé peut vérifier la disponibilité d'un produit dans toutes les boutiques du tenant
- **FR34:** Un propriétaire peut utiliser le warehouse comme point d'origine et de distribution des stocks
- **FR35:** Un propriétaire peut assigner un employé à une boutique spécifique
- **FR36:** Un employé est limité aux opérations de la boutique à laquelle il est assigné

### Point de Vente — POS

- **FR37:** Un employé peut enregistrer une vente en sélectionnant des produits et en entrant les quantités
- **FR38:** Un employé peut enregistrer le mode de paiement d'une vente (Cash ou Mobile Money)
- **FR39:** Un employé peut appliquer des réductions sur une vente
- **FR40:** Un employé peut vérifier instantanément le stock cross-boutique depuis l'interface POS
- **FR41:** Un employé peut clôturer sa journée de vente en un clic
- **FR42:** Le système peut calculer automatiquement le récapitulatif des ventes de la journée
- **FR43:** Un employé peut consulter l'historique de ses propres ventes
- **FR44:** Un propriétaire peut consulter le classement des vendeurs (leaderboard) par performance

### Inventaire Automatisé

- **FR45:** Un propriétaire peut lancer un inventaire complet ou partiel assisté par formulaire guidé
- **FR46:** Le système peut afficher le stock théorique (Keevo) à côté du stock physique saisi
- **FR47:** Le système peut calculer automatiquement les écarts entre stock théorique et physique
- **FR48:** Un propriétaire peut valider et ajuster le stock post-inventaire en un clic
- **FR49:** Le système peut générer un rapport d'inventaire détaillé avec les écarts identifiés

### Rapports & Dashboard

- **FR50:** Un propriétaire peut consulter un dashboard temps réel : stock global, ventes du jour, alertes actives, top produits
- **FR51:** Le système peut générer un rapport end-of-day à heure fixe configurable OU sur clôture manuelle
- **FR52:** Le système peut générer un rapport hebdomadaire automatique (dimanche soir)
- **FR53:** Les rapports incluent : CA, bénéfices, marges, top/flop produits, ventes par boutique, ventes par employé
- **FR54:** Un propriétaire peut consulter la rentabilité par produit (prix achat + transport + prix vente = marge nette)
- **FR55:** Un propriétaire peut exporter des rapports en PDF et Excel *(Plan Payant)*
- **FR56:** Un propriétaire peut consulter les performances comparatives entre boutiques

### Communication WhatsApp

- **FR57:** Le système peut envoyer automatiquement le rapport end-of-day au propriétaire via WhatsApp
- **FR58:** Le système peut envoyer un rapport hebdomadaire via WhatsApp + SMS
- **FR59:** Le système peut envoyer un rapport d'inventaire sur demande via WhatsApp
- **FR60:** Un propriétaire peut configurer les types de rapports et la fréquence d'envoi

### Alertes & Notifications

- **FR61:** Le système peut déclencher des alertes de stock critique via push notification + WhatsApp (seuil configurable)
- **FR62:** Le système peut envoyer des notifications d'évolution des ventes (pics, baisses) en temps réel
- **FR63:** Le système peut afficher un message motivationnel quotidien au propriétaire au démarrage de l'app
- **FR64:** Le système peut envoyer des alertes de changement de statut colis *(Plan Payant)*

### Gestion des Utilisateurs

- **FR65:** Un propriétaire peut inviter des employés par numéro WhatsApp ou lien unique
- **FR66:** Un propriétaire peut gérer les rôles de ses utilisateurs (Propriétaire / Employé)
- **FR67:** Un propriétaire peut consulter l'activité de ses employés (dernière connexion, ventes effectuées)
- **FR68:** Un propriétaire peut désactiver un employé et révoquer ses sessions actives à distance

### Synchronisation Offline-First

- **FR69:** L'application peut fonctionner de manière 100% opérationnelle hors-ligne pendant 7 jours maximum
- **FR70:** Le système peut synchroniser automatiquement les données au retour de la connectivité (<60 secondes)
- **FR71:** Le système peut résoudre les conflits multi-device via synchronisation delta-based
- **FR72:** L'application peut stocker toutes les données critiques localement sur le device
- **FR73:** Le système peut garantir zéro perte de données après synchronisation
- **FR74:** Le système peut suspendre l'accès après 7 jours sans connexion (sync obligatoire pour débloquer)
- **FR75:** L'application peut afficher un indicateur visible du statut de connexion et de la dernière synchronisation
- **FR76:** Le système peut synchroniser les données en temps réel entre tous les appareils d'un même utilisateur

### Super Admin Dashboard

- **FR77:** Le super admin peut visualiser la liste de tous les tenants avec statut, secteur, plan, date d'inscription, dernière sync
- **FR78:** Le super admin peut activer/désactiver manuellement un compte payant et consulter l'historique des paiements par tenant
- **FR79:** Le super admin peut consulter le dashboard revenus : MRR, tenants payants vs free, taux de conversion, churn mensuel
- **FR80:** Le super admin peut consulter les analytics plateforme : volume total de ventes, boutiques actives, taille base produits agrégée, distribution géographique, courbes de croissance
- **FR81:** Le système peut envoyer des alertes proactives WhatsApp/email (tenant J+5 sans sync, abonnement expirant dans <7 jours)
- **FR82:** Le super admin peut envoyer des notifications globales push à tous les tenants
- **FR83:** Le super admin peut consulter la santé du système : statut serveurs, tenants en retard de sync, logs d'erreurs critiques

### Sécurité & Audit

- **FR84:** Le système peut maintenir un journal d'audit complet de toutes les modifications (stock, prix, ventes, transferts) avec identité, horodatage et valeurs avant/après
- **FR85:** L'application peut chiffrer les données stockées localement sur le device (chiffrement au repos)
- **FR86:** Le système utilise le Franc CFA (XAF) comme devise unique en V1 pour toutes les opérations financières

### Gestion Clients & Fournisseurs

- **FR87:** Un propriétaire peut créer et gérer une base de clients avec nom et numéro de téléphone
- **FR88:** Un employé peut associer un client à une vente
- **FR89:** Un propriétaire peut créer et gérer une base de fournisseurs (nom, pays, contact) et associer des produits à leurs fournisseurs

### Gestion des Erreurs & Lifecycle

- **FR90:** Un propriétaire peut annuler ou corriger une vente avec justification obligatoire (stock automatiquement réajusté)
- **FR91:** Un propriétaire peut demander la suppression complète de son compte et de toutes ses données
- **FR92:** Un utilisateur peut soumettre un feedback ou signaler un problème directement depuis l'application
- **FR93:** Le système peut appliquer des limites de débit par tenant pour protéger les performances de la plateforme

## Non-Functional Requirements

### Performance

- **NFR1:** Toute action utilisateur (enregistrement vente, consultation stock) doit se compléter en <2 secondes sur l'interface locale (offline)
- **NFR2:** La synchronisation complète au retour en ligne doit se compléter en <60 secondes pour jusqu'à 7 jours de données accumulées
- **NFR3:** Le dashboard propriétaire doit se charger en <3 secondes avec jusqu'à 500 produits et 30 jours d'historique
- **NFR4:** L'application doit rester fluide (>30 FPS) sur des appareils Android avec 2 Go de RAM et processeur entrée de gamme
- **NFR5:** La recherche produit doit retourner des résultats en <500ms sur un catalogue de 500 produits
- **NFR6:** L'application mobile ne doit pas dépasser 100 Mo d'espace de stockage (hors données utilisateur)

### Sécurité

- **NFR7:** Toutes les données en transit doivent être chiffrées via TLS 1.2+ (HTTPS)
- **NFR8:** Les données stockées localement (SQLite) doivent être chiffrées au repos (SQLCipher ou équivalent)
- **NFR9:** Les tokens JWT doivent expirer après 24h avec mécanisme de refresh token
- **NFR10:** L'isolation des données entre tenants doit être garantie à 100% — aucune fuite de données cross-tenant
- **NFR11:** Les mots de passe doivent être stockés avec hachage bcrypt (coût ≥12)
- **NFR12:** Les sessions révoquées (employé désactivé) doivent être invalidées en <5 minutes
- **NFR13:** Le journal d'audit doit être immuable — aucune modification/suppression possible par les utilisateurs

### Scalabilité

- **NFR14:** Le système doit supporter 100 tenants actifs simultanés en Phase 1 (M+3) sans dégradation de performance
- **NFR15:** L'architecture doit permettre une croissance à 1 000+ tenants (Phase 2) avec uniquement une augmentation linéaire des ressources serveur
- **NFR16:** La base de données doit supporter jusqu'à 50 000 produits agrégés sur la plateforme
- **NFR17:** Le système doit supporter des pics de charge saisonniers (rentrée scolaire, fêtes) avec un facteur 3x du trafic normal

### Fiabilité & Disponibilité

- **NFR18:** Le backend cloud doit maintenir un uptime de ≥99.5% (max ~43h de downtime/an)
- **NFR19:** L'application offline doit fonctionner avec 0% de downtime côté client pendant les 7 jours garantis
- **NFR20:** La synchronisation doit garantir zéro perte de données — intégrité ACID sur les opérations de sync
- **NFR21:** Le système doit supporter des reprises automatiques en cas d'échec de synchronisation (retry avec backoff exponentiel)
- **NFR22:** Les sauvegardes de base de données doivent être effectuées au minimum une fois par jour avec rétention de 30 jours

### Accessibilité & Utilisabilité

- **NFR23:** L'interface doit être utilisable par des personnes avec un niveau de tech literacy de 4/10 (typique des commerçants cibles)
- **NFR24:** Toutes les fonctionnalités critiques doivent être accessibles en ≤3 taps/clics depuis l'écran principal
- **NFR25:** L'application doit supporter les tailles de police adaptatives pour les utilisateurs avec des difficultés visuelles
- **NFR26:** Toute l'interface doit être en français (V1), avec architecture i18n prête pour l'anglais (V2)
- **NFR27:** L'onboarding complet (inscription → première vente) ne doit pas dépasser 10 minutes

### Intégration

- **NFR28:** L'intégration WhatsApp Business API (Africa's Talking) doit supporter un débit de ≥100 messages/heure par tenant
- **NFR29:** La synchronisation PowerSync doit fonctionner de manière transparente avec une latence <5 secondes en conditions réseau normales
- **NFR30:** Les intégrations externes (WhatsApp, PowerSync, AWS) doivent avoir des mécanismes de fallback gracieux en cas d'indisponibilité
- **NFR31:** L'API backend doit suivre les standards RESTful avec versioning (`/api/v1/`) pour permettre l'évolution sans casser les clients existants

### Compatibilité

- **NFR32:** L'application mobile doit supporter Android 8+ (API 26) et iOS 14+
- **NFR33:** L'application desktop doit supporter Windows 10+, Linux (Ubuntu 20.04+, Debian 11+), et macOS 12+ (Growth)
- **NFR34:** L'application doit fonctionner correctement sur des écrans de 5" (mobile) à 27" (desktop)
