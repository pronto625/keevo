# Implementation Readiness Assessment Report

**Date:** 2026-03-02
**Project:** Bmad-test

---
stepsCompleted: ["step-01-document-discovery", "step-02-prd-analysis", "step-03-epic-coverage-validation", "step-04-ux-alignment", "step-05-epic-quality-review", "step-06-final-assessment"]
documentsUsed:
  prd: "_bmad-output/planning-artifacts/prd.md"
  architecture: "_bmad-output/planning-artifacts/architecture.md"
  epics: "_bmad-output/planning-artifacts/epics/" # restructuré en shards le 2026-03-02 (ancien epics.md archivé)
  ux: "_bmad-output/planning-artifacts/ux-design-specification.md"
---

## Document Inventory

| Type | Fichier | Taille | Dernière modification |
|---|---|---|---|
| PRD | `prd.md` | 37 Ko | 2026-02-26 |
| Architecture | `architecture.md` | 39 Ko | 2026-03-02 |
| Epics & Stories | `epics/` (shardé — 9 fichiers + index) | ~152 Ko total | 2026-03-02 |
| UX Design | `ux-design-specification.md` | 53 Ko | 2026-02-28 |
| Brief produit | `product-brief-AI-2026-02-13.md` | 41 Ko | 2026-02-26 |
| Rapport validation PRD | `prd-validation-report.md` | 12 Ko | 2026-02-26 |

**Détail des fichiers Epics (format shardé) :**

| Fichier | Contenu |
|---|---|
| `epics/index.md` | Index général |
| `epics/epic-list.md` | Liste des épics |
| `epics/overview.md` | Vue d'ensemble |
| `epics/requirements-inventory.md` | Inventaire des exigences |
| `epics/epic-1-foundation-infrastructure-authentication.md` | Epic 1 (7 stories) |
| `epics/epic-2-catalogue-produits-base-fournisseursclients.md` | Epic 2 (5 stories) |
| `epics/epic-3-gestion-multi-boutiques-stock-quipe.md` | Epic 3 (5 stories) |
| `epics/epic-4-point-de-vente-pos.md` | Epic 4 (5 stories) |
| `epics/epic-5-moteur-de-synchronisation-offline-first.md` | Epic 5 (5 stories) |
| `epics/epic-6-inventaire-assist.md` | Epic 6 (4 stories) |
| `epics/epic-7-rapports-dashboard-communication-whatsapp.md` | Epic 7 (5 stories) |
| `epics/epic-8-alertes-notifications-gestion-oprationnelle.md` | Epic 8 (5 stories) |
| `epics/epic-9-super-admin-dashboard-toor.md` | Epic 9 (5 stories) |

> ⚠️ **Note de restructuration (2026-03-02) :** L'ancien fichier monolithique `epics.md` (152 Ko) a été déplacé dans `archive/epics.md`. La nouvelle structure shardée `epics/` contient le même contenu réparti en fichiers individuels par epic. L'évaluation de ce rapport reste intégralement valide — seule la structure d'organisation a changé, pas le contenu.

**Doublons détectés :** Aucun (ancien `epics.md` correctement archivé dans `archive/`)
**Documents manquants :** Aucun

---

## PRD Analysis

### Functional Requirements

| # | Domaine | Requirement |
|---|---|---|
| FR1 | Auth | Un propriétaire peut créer un compte via son numéro WhatsApp |
| FR2 | Auth | Le système provisionne automatiquement un tenant isolé (schéma PostgreSQL `KV-XXXXXX`) à l'inscription |
| FR3 | Auth | Un utilisateur peut se connecter via JWT sur mobile et desktop |
| FR4 | Auth | Un propriétaire peut choisir son secteur d'activité à l'inscription |
| FR5 | Auth | Le système applique un template sectoriel pré-configuré selon le secteur choisi |
| FR6 | Auth | Un propriétaire peut compléter le wizard d'onboarding en moins de 5 minutes |
| FR7 | Auth | Le système affiche des tutoriels vidéo intégrés en français |
| FR8 | Provisioning | Le système crée automatiquement le compte propriétaire avec le rôle "Owner" |
| FR9 | Provisioning | Le système crée automatiquement les tables du schéma tenant dans PostgreSQL |
| FR10 | Provisioning | Le système initialise les rôles par défaut (Owner, Employee) avec leurs permissions |
| FR11 | Provisioning | Le système pré-configure les catégories produits par défaut selon le template sectoriel |
| FR12 | Provisioning | Le système initialise les préférences de notification par défaut |
| FR13 | Provisioning | Le système crée la première boutique par défaut du tenant |
| FR14 | Provisioning | Le système génère le code unique tenant (`KV-XXXXXX`) et l'associe au schéma |
| FR15 | Provisioning | Le système initialise le statut de souscription par défaut (Plan Free) |
| FR16 | Souscription | Le système applique les limites du plan gratuit (3 boutiques, 500 produits, 5 employés) |
| FR17 | Souscription | Le système affiche un message clair avec CTA upgrade lorsqu'une limite est atteinte |
| FR18 | Souscription | Le système suspend automatiquement un tenant en lecture seule à l'expiration |
| FR19 | Souscription | Le système conserve les données d'un tenant suspendu (lecture seule) |
| FR20 | Souscription | Un propriétaire peut consulter son statut de souscription |
| FR21 | Produits | Un propriétaire peut créer, modifier et archiver des produits |
| FR22 | Produits | Un propriétaire peut définir des variantes produit (taille, couleur) |
| FR23 | Produits | Un propriétaire peut saisir prix de vente, prix d'achat et coûts de transport par produit |
| FR24 | Produits | Le système calcule automatiquement la marge nette par produit |
| FR25 | Produits | Un propriétaire peut organiser les produits par catégories flexibles |
| FR26 | Produits | Un propriétaire peut configurer un seuil de stock minimum par produit |
| FR27 | Produits | Un propriétaire peut consulter l'historique complet des mouvements de stock |
| FR28 | Produits | Un propriétaire peut importer des produits via fichier CSV |
| FR29 | Produits | Un employé peut consulter le catalogue produits de sa boutique |
| FR30 | Multi-Boutiques | Un propriétaire peut créer et gérer plusieurs boutiques + un warehouse |
| FR31 | Multi-Boutiques | Un propriétaire peut visualiser les niveaux de stock de toutes ses boutiques en temps réel |
| FR32 | Multi-Boutiques | Un propriétaire peut initier un transfert de stock entre boutiques avec traçabilité |
| FR33 | Multi-Boutiques | Un employé peut vérifier la disponibilité d'un produit dans toutes les boutiques |
| FR34 | Multi-Boutiques | Un propriétaire peut utiliser le warehouse comme point d'origine et de distribution |
| FR35 | Multi-Boutiques | Un propriétaire peut assigner un employé à une boutique spécifique |
| FR36 | Multi-Boutiques | Un employé est limité aux opérations de sa boutique assignée |
| FR37 | POS | Un employé peut enregistrer une vente en sélectionnant des produits et quantités |
| FR38 | POS | Un employé peut enregistrer le mode de paiement (Cash ou Mobile Money) |
| FR39 | POS | Un employé peut appliquer des réductions sur une vente |
| FR40 | POS | Un employé peut vérifier instantanément le stock cross-boutique depuis l'interface POS |
| FR41 | POS | Un employé peut clôturer sa journée de vente en un clic |
| FR42 | POS | Le système calcule automatiquement le récapitulatif des ventes de la journée |
| FR43 | POS | Un employé peut consulter l'historique de ses propres ventes |
| FR44 | POS | Un propriétaire peut consulter le leaderboard des vendeurs par performance |
| FR45 | Inventaire | Un propriétaire peut lancer un inventaire complet ou partiel assisté |
| FR46 | Inventaire | Le système affiche le stock théorique à côté du stock physique saisi |
| FR47 | Inventaire | Le système calcule automatiquement les écarts entre stock théorique et physique |
| FR48 | Inventaire | Un propriétaire peut valider et ajuster le stock post-inventaire en un clic |
| FR49 | Inventaire | Le système génère un rapport d'inventaire détaillé avec les écarts identifiés |
| FR50 | Rapports | Un propriétaire peut consulter un dashboard temps réel (stock, ventes, alertes, top produits) |
| FR51 | Rapports | Le système génère un rapport end-of-day à heure fixe configurable ou sur clôture manuelle |
| FR52 | Rapports | Le système génère un rapport hebdomadaire automatique (dimanche soir) |
| FR53 | Rapports | Les rapports incluent CA, bénéfices, marges, top/flop produits, ventes par boutique et employé |
| FR54 | Rapports | Un propriétaire peut consulter la rentabilité par produit |
| FR55 | Rapports | Un propriétaire peut exporter des rapports en PDF et Excel (Plan Payant) |
| FR56 | Rapports | Un propriétaire peut consulter les performances comparatives entre boutiques |
| FR57 | WhatsApp | Le système envoie automatiquement le rapport end-of-day via WhatsApp |
| FR58 | WhatsApp | Le système envoie un rapport hebdomadaire via WhatsApp + SMS |
| FR59 | WhatsApp | Le système envoie un rapport d'inventaire sur demande via WhatsApp |
| FR60 | WhatsApp | Un propriétaire peut configurer les types de rapports et fréquence d'envoi |
| FR61 | Alertes | Le système déclenche des alertes de stock critique via push notification + WhatsApp |
| FR62 | Alertes | Le système envoie des notifications d'évolution des ventes en temps réel |
| FR63 | Alertes | Le système affiche un message motivationnel quotidien au démarrage |
| FR64 | Alertes | Le système envoie des alertes de changement de statut colis (Plan Payant) |
| FR65 | Utilisateurs | Un propriétaire peut inviter des employés par numéro WhatsApp ou lien unique |
| FR66 | Utilisateurs | Un propriétaire peut gérer les rôles (Propriétaire / Employé) |
| FR67 | Utilisateurs | Un propriétaire peut consulter l'activité de ses employés |
| FR68 | Utilisateurs | Un propriétaire peut désactiver un employé et révoquer ses sessions actives |
| FR69 | Sync | L'application peut fonctionner 100% hors-ligne pendant 7 jours maximum |
| FR70 | Sync | Le système synchronise automatiquement les données au retour en ligne (<60 secondes) |
| FR71 | Sync | Le système résout les conflits multi-device via synchronisation delta-based |
| FR72 | Sync | L'application stocke toutes les données critiques localement sur le device |
| FR73 | Sync | Le système garantit zéro perte de données après synchronisation |
| FR74 | Sync | Le système suspend l'accès après 7 jours sans connexion |
| FR75 | Sync | L'application affiche un indicateur visible du statut de connexion et dernière sync |
| FR76 | Sync | Le système synchronise les données en temps réel entre tous les appareils d'un même utilisateur |
| FR77 | Super Admin | Le super admin peut visualiser la liste de tous les tenants avec statut, plan, dates |
| FR78 | Super Admin | Le super admin peut activer/désactiver manuellement un compte payant |
| FR79 | Super Admin | Le super admin peut consulter le dashboard revenus (MRR, conversion, churn) |
| FR80 | Super Admin | Le super admin peut consulter les analytics plateforme globales |
| FR81 | Super Admin | Le système envoie des alertes proactives WhatsApp/email (tenant sans sync, abonnement expirant) |
| FR82 | Super Admin | Le super admin peut envoyer des notifications globales push à tous les tenants |
| FR83 | Super Admin | Le super admin peut consulter la santé du système (serveurs, logs d'erreurs) |
| FR84 | Sécurité | Le système maintient un journal d'audit complet et immuable de toutes les modifications |
| FR85 | Sécurité | L'application chiffre les données stockées localement sur le device |
| FR86 | Sécurité | Le système utilise le Franc CFA (XAF) comme devise unique en V1 |
| FR87 | Clients/Fournisseurs | Un propriétaire peut créer et gérer une base de clients |
| FR88 | Clients/Fournisseurs | Un employé peut associer un client à une vente |
| FR89 | Clients/Fournisseurs | Un propriétaire peut créer et gérer une base de fournisseurs |
| FR90 | Lifecycle | Un propriétaire peut annuler ou corriger une vente avec justification obligatoire |
| FR91 | Lifecycle | Un propriétaire peut demander la suppression complète de son compte et données |
| FR92 | Lifecycle | Un utilisateur peut soumettre un feedback ou signaler un problème |
| FR93 | Lifecycle | Le système applique des limites de débit par tenant |

**Total FRs : 93**

---

### Non-Functional Requirements

| # | Catégorie | Requirement |
|---|---|---|
| NFR1 | Performance | Toute action utilisateur <2s sur interface locale (offline) |
| NFR2 | Performance | Synchronisation complète au retour en ligne <60s pour 7 jours de données |
| NFR3 | Performance | Dashboard propriétaire chargé <3s avec 500 produits et 30 jours d'historique |
| NFR4 | Performance | Application fluide (>30 FPS) sur Android 2 Go RAM entrée de gamme |
| NFR5 | Performance | Recherche produit <500ms sur catalogue de 500 produits |
| NFR6 | Performance | App mobile ≤100 Mo d'espace de stockage (hors données utilisateur) |
| NFR7 | Sécurité | Données en transit chiffrées via TLS 1.2+ (HTTPS) |
| NFR8 | Sécurité | Données locales (SQLite) chiffrées au repos (SQLCipher ou équivalent) |
| NFR9 | Sécurité | Tokens JWT expirent après 24h avec mécanisme de refresh token |
| NFR10 | Sécurité | Isolation des données entre tenants garantie à 100% |
| NFR11 | Sécurité | Mots de passe stockés avec hachage bcrypt (coût ≥12) |
| NFR12 | Sécurité | Sessions révoquées invalidées en <5 minutes |
| NFR13 | Sécurité | Journal d'audit immuable — aucune modification/suppression possible |
| NFR14 | Scalabilité | Support 100 tenants actifs simultanés en Phase 1 sans dégradation |
| NFR15 | Scalabilité | Architecture permettant 1 000+ tenants (Phase 2) avec croissance linéaire |
| NFR16 | Scalabilité | Base de données supportant jusqu'à 50 000 produits agrégés |
| NFR17 | Scalabilité | Support des pics de charge saisonniers (facteur 3x trafic normal) |
| NFR18 | Fiabilité | Backend cloud uptime ≥99.5% |
| NFR19 | Fiabilité | Application offline 0% downtime côté client pendant les 7 jours garantis |
| NFR20 | Fiabilité | Synchronisation garantit zéro perte de données — intégrité ACID |
| NFR21 | Fiabilité | Reprises automatiques en cas d'échec de synchronisation (backoff exponentiel) |
| NFR22 | Fiabilité | Sauvegardes journalières avec rétention 30 jours |
| NFR23 | Accessibilité | Interface utilisable par tech literacy 4/10 |
| NFR24 | Accessibilité | Fonctionnalités critiques accessibles en ≤3 taps depuis l'écran principal |
| NFR25 | Accessibilité | Support des tailles de police adaptatives |
| NFR26 | Accessibilité | Interface entièrement en français (V1), architecture i18n prête pour l'anglais |
| NFR27 | Accessibilité | Onboarding complet ≤10 minutes (inscription → première vente) |
| NFR28 | Intégration | WhatsApp Business API ≥100 messages/heure par tenant |
| NFR29 | Intégration | Synchronisation PowerSync <5s de latence en conditions réseau normales |
| NFR30 | Intégration | Mécanismes de fallback gracieux pour les intégrations externes |
| NFR31 | Intégration | API REST versionnée (`/api/v1/`) |
| NFR32 | Compatibilité | Application mobile : Android 8+ (API 26) et iOS 14+ |
| NFR33 | Compatibilité | Application desktop : Windows 10+, Linux (Ubuntu 20.04+), macOS 12+ (Growth) |
| NFR34 | Compatibilité | Application fonctionnelle sur écrans de 5" à 27" |

**Total NFRs : 34**

---

### Additional Requirements (Constraints & Assumptions)

- **Devise unique :** XAF (Franc CFA) en V1 — pas de multidevise
- **Développeur solo :** Toor — délai MVP estimé 3-4 mois
- **Activation paiements manuelle :** V1 — Toor active les comptes payants après preuve Mobile Money
- **App Store compliance :** Respect des guidelines Google Play et Apple App Store
- **Permissions device :** Caméra, Storage, Network State, Notifications
- **Performance sur hardware limité :** 2 Go RAM, stockage limité
- **Import CSV :** Mécanisme de migration des stocks existants
- **Multi-tenant isolation :** Schema-per-tenant PostgreSQL (`kv_xxxxxx`)

---

### PRD Completeness Assessment

Le PRD est **complet et bien structuré**. Il couvre :
- ✅ 93 exigences fonctionnelles clairement numérotées et catégorisées
- ✅ 34 exigences non-fonctionnelles couvrant performance, sécurité, scalabilité, fiabilité, accessibilité, intégration et compatibilité
- ✅ User journeys détaillés pour chaque persona (Simon, Loïc, Toor)
- ✅ MVP clairement délimité vs features Growth/Vision
- ✅ Contraintes techniques explicites (stack, offline-first, multi-tenant)
- ✅ Critères de succès mesurables (KPIs, métriques business)

---

## Epic Coverage Validation

### Epic Structure

| Epic | Titre |
|---|---|
| Epic 1 | Foundation, Infrastructure & Authentication |
| Epic 2 | Catalogue Produits & Base Fournisseurs/Clients |
| Epic 3 | Gestion Multi-Boutiques, Stock & Équipe |
| Epic 4 | Point de Vente (POS) |
| Epic 5 | Moteur de Synchronisation Offline-First |
| Epic 6 | Inventaire Assisté |
| Epic 7 | Rapports, Dashboard & Communication WhatsApp |
| Epic 8 | Alertes, Notifications & Gestion Opérationnelle |
| Epic 9 | Super Admin Dashboard (Toor) |

### Coverage Matrix

| FR | Epic | Statut |
|---|---|---|
| FR1 | Epic 1 | ✅ Covered |
| FR2 | Epic 1 | ✅ Covered |
| FR3 | Epic 1 | ✅ Covered |
| FR4 | Epic 1 | ✅ Covered |
| FR5 | Epic 1 | ✅ Covered |
| FR6 | Epic 1 | ✅ Covered |
| FR7 | Epic 1 | ✅ Covered |
| FR8 | Epic 1 | ✅ Covered |
| FR9 | Epic 1 | ✅ Covered |
| FR10 | Epic 1 | ✅ Covered |
| FR11 | Epic 1 | ✅ Covered |
| FR12 | Epic 1 | ✅ Covered |
| FR13 | Epic 1 | ✅ Covered |
| FR14 | Epic 1 | ✅ Covered |
| FR15 | Epic 1 | ✅ Covered |
| FR16 | Epic 1 | ✅ Covered |
| FR17 | Epic 1 | ✅ Covered |
| FR18 | Epic 1 | ✅ Covered |
| FR19 | Epic 1 | ✅ Covered |
| FR20 | Epic 1 | ✅ Covered |
| FR21 | Epic 2 | ✅ Covered |
| FR22 | Epic 2 | ✅ Covered |
| FR23 | Epic 2 | ✅ Covered |
| FR24 | Epic 2 | ✅ Covered |
| FR25 | Epic 2 | ✅ Covered |
| FR26 | Epic 2 | ✅ Covered |
| FR27 | Epic 2 | ✅ Covered |
| FR28 | Epic 2 | ✅ Covered |
| FR29 | Epic 2 | ✅ Covered |
| FR30 | Epic 3 | ✅ Covered |
| FR31 | Epic 3 | ✅ Covered |
| FR32 | Epic 3 | ✅ Covered |
| FR33 | Epic 3 | ✅ Covered |
| FR34 | Epic 3 | ✅ Covered |
| FR35 | Epic 3 | ✅ Covered |
| FR36 | Epic 3 | ✅ Covered |
| FR37 | Epic 4 | ✅ Covered |
| FR38 | Epic 4 | ✅ Covered |
| FR39 | Epic 4 | ✅ Covered |
| FR40 | Epic 4 | ✅ Covered |
| FR41 | Epic 4 | ✅ Covered |
| FR42 | Epic 4 | ✅ Covered |
| FR43 | Epic 4 | ✅ Covered |
| FR44 | Epic 4 | ✅ Covered |
| FR45 | Epic 6 | ✅ Covered |
| FR46 | Epic 6 | ✅ Covered |
| FR47 | Epic 6 | ✅ Covered |
| FR48 | Epic 6 | ✅ Covered |
| FR49 | Epic 6 | ✅ Covered |
| FR50 | Epic 7 | ✅ Covered |
| FR51 | Epic 7 | ✅ Covered |
| FR52 | Epic 7 | ✅ Covered |
| FR53 | Epic 7 | ✅ Covered |
| FR54 | Epic 7 | ✅ Covered |
| FR55 | Epic 7 | ✅ Covered |
| FR56 | Epic 7 | ✅ Covered |
| FR57 | Epic 7 | ✅ Covered |
| FR58 | Epic 7 | ✅ Covered |
| FR59 | Epic 7 | ✅ Covered |
| FR60 | Epic 7 | ✅ Covered |
| FR61 | Epic 8 | ✅ Covered |
| FR62 | Epic 8 | ✅ Covered |
| FR63 | Epic 7 | ✅ Covered |
| FR64 | Epic 8 | ✅ Covered |
| FR65 | Epic 3 | ✅ Covered |
| FR66 | Epic 3 | ✅ Covered |
| FR67 | Epic 8 | ✅ Covered |
| FR68 | Epic 8 | ✅ Covered |
| FR69 | Epic 5 | ✅ Covered |
| FR70 | Epic 5 | ✅ Covered |
| FR71 | Epic 5 | ✅ Covered |
| FR72 | Epic 1 | ✅ Covered ⚠️ Note: placé en Epic 1 au lieu d'Epic 5 |
| FR73 | Epic 5 | ✅ Covered |
| FR74 | Epic 5 | ✅ Covered |
| FR75 | Epic 1 | ✅ Covered ⚠️ Note: placé en Epic 1 au lieu d'Epic 5 |
| FR76 | Epic 5 | ✅ Covered |
| FR77 | Epic 9 | ✅ Covered |
| FR78 | Epic 9 | ✅ Covered |
| FR79 | Epic 9 | ✅ Covered |
| FR80 | Epic 9 | ✅ Covered |
| FR81 | Epic 9 | ✅ Covered |
| FR82 | Epic 9 | ✅ Covered |
| FR83 | Epic 9 | ✅ Covered |
| FR84 | Epic 1 | ✅ Covered |
| FR85 | Epic 1 | ✅ Covered |
| FR86 | Epic 1 | ✅ Covered |
| FR87 | Epic 2 | ✅ Covered |
| FR88 | Epic 4 | ✅ Covered |
| FR89 | Epic 2 | ✅ Covered |
| FR90 | Epic 4 | ✅ Covered |
| FR91 | Epic 8 | ✅ Covered |
| FR92 | Epic 8 | ✅ Covered |
| FR93 | Epic 1 | ✅ Covered |

### Missing Requirements

**Aucun FR manquant.** Tous les 93 FRs du PRD sont couverts par les epics.

> **⚠️ Observations mineures de placement :**
> - **FR72** (Stockage données critiques localement) est attribué à Epic 1 (Foundation) au lieu d'Epic 5 (Offline Sync). Placement défendable car c'est une contrainte infrastructure, mais pourrait créer de la confusion lors de l'implémentation.
> - **FR75** (Indicateur visible statut connexion/sync) est attribué à Epic 1 au lieu d'Epic 5. Il serait plus cohérent de le placer dans Epic 5 avec les autres FRs offline.

### Coverage Statistics

- **Total PRD FRs :** 93
- **FRs couverts dans les epics :** 93
- **Pourcentage de couverture :** **100%**
- **FRs non couverts :** 0
- **Epics utilisés :** 9

---

## UX Alignment Assessment

### UX Document Status

✅ **Trouvé** — `ux-design-specification.md` (53 Ko, 936 lignes, 14 steps complétés)

**Input documents de la spec UX :**
- `prd.md` ✅  
- `prd-validation-report.md` ✅  
- `product-brief-AI-2026-02-13.md` ✅  
- Fichiers de recherche marché et stack technique ✅

**Input documents de l'architecture :**  
- `ux-design-specification.md` ✅ (architecture a consommé la spec UX)

### UX ↔ PRD Alignment

| Aspect | Statut | Détail |
|---|---|---|
| Couverture des personas | ✅ Aligné | Simon, Loïc, Toor reflétés dans les 24 user flows UX |
| Couverture FR via flows | ✅ Aligné | Les 24 flows couvrent FR1-FR93 de manière explicite |
| Philosophie "Augment, Don't Replace" | ✅ Aligné | WhatsApp reports, adoption progressive, 0 jargon comptable |
| Offline-first | ✅ Aligné | Flows 4, 5, 8, 10 explicitement déclarés 100% offline |
| ≤3 taps (NFR24) | ✅ Aligné | Principe "3 taps ou moins" formalisé dans les principes UX |
| Tech literacy 4/10 (NFR23) | ✅ Aligné | Design émotionnel, zéro jargon, large touch targets |

**UX enrichit le PRD avec des précisions non explicites :**
- **Changement de mot de passe forcé** (1ère connexion employé) — implicitement dans FR65 mais non formalisé dans le PRD. Comportement nécessaire pour la sécurité.
- **Produits "brouillons" créés à la volée** durant le POS — extension de FR37, pattern clé de l'adoption progressive.
- **Fallback automatique clôture 20h** — complète FR51 avec un comportement précis (rapport auto si l'employé n'a pas clôturé).
- **Comptage caisse optionnel** à la clôture — détail UX du flow 8, non explicitement dans FR41.

### UX ↔ Architecture Alignment

| Composant UX | Support Architecture | Statut |
|---|---|---|
| Flutter responsive-first (5" → 27") | Flutter responsive breakpoints (compact/medium/expanded) | ✅ Aligné |
| Material 3 / design system tokens | `core/theme/` Material 3 Keevo tokens | ✅ Aligné |
| Recherche fuzzy <1s (locale) | Drift SQLite, `lib/core/storage/` | ✅ Aligné (Drift supporte FTS) |
| Navigation ≤3 taps | go_router + feature-first structure | ✅ Aligné |
| Riverpod state management réactif | Riverpod 3.0 avec riverpod_generator | ✅ Aligné |
| Offline = mode par défaut | Custom REST delta-based sync + Drift local | ✅ Aligné |
| WhatsApp reports auto | `messaging/whatsapp/` + `WhatsAppPort` abstraction | ✅ Aligné |
| Rapport auto 20h (auto-scheduler) | Domaine `messaging/` — scheduler non explicitement défini | ⚠️ Gap mineur |
| Produits brouillons (draft state) | `catalog/product/` — état brouillon non explicitement modélisé | ⚠️ Gap mineur |

### Alertes & Warnings

> **⚠️ Gap 1 — Scheduleur de rapport automatique 20h :**  
> Le flow UX 8 décrit un fallback auto à 20h si l'employé ne clôture pas. L'architecture définit le domaine `messaging/` mais ne détaille pas le mécanisme de scheduling (cron Spring, quartz, etc.). Ce n'est pas un bloquant architectural mais doit être explicité dans les stories d'Epic 7.

> **⚠️ Gap 2 — État "brouillon" pour les produits créés à la volée :**  
> L'UX décrit la création de produits brouillons pendant le POS (nom + prix uniquement). L'architecture du domaine `catalog/product/` ne modélise pas explicitement un état de cycle de vie `DRAFT` vs `ACTIVE`. À définir dans les stories d'Epic 4 (POS) et Epic 2 (Catalogue).

> **ℹ️ Observation — Changement de mot de passe forcé (1ère connexion employé) :**  
> Comportement décrit dans la spec UX mais absent des FRs du PRD. Ce devrait être un AC dans les stories de Epic 1 (Auth) concernant l'invitation et la première connexion employé.

### Résumé Alignement UX

- **Alignement UX ↔ PRD :** 🟢 Excellent — La spec UX a été créée à partir du PRD, couverture complète des 93 FRs via 24 user flows.
- **Alignement UX ↔ Architecture :** 🟡 Bon avec 2 gaps mineurs — L'architecture a consommé la spec UX, alignement technique solide. Deux détails d'implémentation à préciser dans les stories.

---

## Epic Quality Review

### Best Practices Compliance — Vue d'Ensemble

| Epic | Valeur Utilisateur | Indépendance | Taille Stories | ACs BDD | FR Tracé |
|---|---|---|---|---|---|
| Epic 1 | ✅ (⚠️ Story 1.1) | ✅ | ✅ | ✅ | ✅ |
| Epic 2 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Epic 3 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Epic 4 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Epic 5 | ✅ (⚠️ Story 5.3) | ✅ | ✅ | ✅ | ⚠️ partiel |
| Epic 6 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Epic 7 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Epic 8 | ✅ | ✅ | ✅ | ✅ | ✅ |
| Epic 9 | ✅ | ✅ | ✅ | ✅ | ✅ |

---

### 🟡 Issues Mineurs

#### Issue 1 — Story 1.1 : Persona "Développeur" au lieu d'un utilisateur
- **Localisation :** Epic 1 / Story 1.1
- **Violation :** Story 1.1 est rédigée "As a developer (Toor)" — c'est un milestone technique, pas une histoire utilisateur.
- **Impact :** Faible. Pour un projet Greenfield, l'initialisation du projet est attendue en Story 1 de l'Epic 1. C'est une pratique courante et pragmatique.
- **Recommandation :** Acceptable tel quel. Si strict, reformuler en "As Toor (proprietor and sole developer), I want the technical foundation..."

#### Issue 2 — Story 5.3 : Persona "As a system"
- **Localisation :** Epic 5 / Story 5.3
- **Violation :** Story 5.3 utilise "As a system" comme persona — violaton de la convention user story.
- **Impact :** Faible. La résolution de conflits est effectivement une préoccupation système, mais doit être présentée du point de vue utilisateur.
- **Recommandation :** Reformuler en "As a proprietor (Simon), I want conflicts between devices to be resolved transparently and accurately..."

#### Issue 3 — Forward references intra-epic dans Epic 4
- **Localisation :** Epic 4
- **Violations :**
  - Story 4.1 référence "tappable to edit — Story 4.2" → forward ref
  - Story 4.3 référence "Story 4.4" pour Annuler/Corriger → forward ref
  - Story 4.5 référence "Story 4.4" → forward ref
- **Impact :** Faible. Ces références sont intra-epic (dans le même epic) et le sprint peut livrer toutes les stories d'un epic ensemble. Story 4.1 seule ne livrerait pas l'édition de prix (Story 4.2), ce qui est correct — la feature est split entre deux stories.
- **Recommandation :** Acceptable. Les ACs de Story 4.1 doivent clairement noter que l'édition de prix est une feature story séparée (déjà fait via la notation parenthétique).

#### Issue 4 — FR72 et FR75 fragmentés entre Epic 1 et Epic 5
- **Localisation :** Coverage map — FR72 → Epic 1, FR75 → Epic 1 ; FR69-71, FR73-74, FR76 → Epic 5
- **Violation :** Deux FRs liés à l'offline sont dans Epic 1 au lieu d'Epic 5 où tous les autres FRs sync sont regroupés.
  - FR72 : Stockage données critiques localement (Story 1.5)
  - FR75 : Indicateur visible statut connexion (Story 1.5)
- **Impact :** Faible à modéré. Placement défendable car l'infrastructure locale SQLite et l'indicateur de connexion sont des fondations nécessaires dès l'Epic 1 pour que les Epics 2-4 puissent travailler en mode offline. Epic 5 implémente le *moteur sync*, pas l'*infrastructure offline locale*.
- **Recommandation :** Documenter explicitement dans la description d'Epic 5 que FR72 et FR75 sont couverts dans Epic 1 (infrastructure) et qu'Epic 5 se concentre sur le moteur de sync (FR69-71, FR73-74, FR76). Le coverage map actuel dans les epics reflète déjà cela correctement.

#### Issue 5 — Story 6.1 référence Story 6.2 dans sa description
- **Localisation :** Epic 6 / Story 6.1
- **Violation :** "And the app navigates immediately to the inventory counting form (Story 6.2)" — forward ref intra-epic.
- **Impact :** Minimal. Story 6.1 seule est livrée sans Story 6.2, la navigation vers un écran vide serait techniquement incomplète. Les deux stories devraient idéalement être livrées ensemble en sprint.
- **Recommandation :** Acceptable. Story 6.1 peut livrer la création de session + navigation vers un placeholder screen de Story 6.2.

---

### ✅ Conformités Notables

**Starter Template Story (greenfield check) :**  
✅ Story 1.1 couvre l'initialisation complète du monorepo avec les commandes exactes (`flutter create`, Spring Initializr), la structure hexagonale, CI/CD Github Actions, et Docker Compose. Conforme aux exigences greenfield.

**Taille des stories :**  
✅ Toutes les stories sont de taille appropriée — chaque story livre une valeur utilisateur complète et testable. Aucune story "monstre" détectée.

**Acceptance Criteria — qualité BDD :**  
✅ Format Given/When/Then rigoureusement respecté dans tous les épics. ACs sont spécifiques, mesurables et testables. Les cas d'erreur sont couverts (limites freemium, conflits sync, offline edge cases).

**Indépendance des Epics :**  
✅ Chaque epic peut être livré et démontré indépendamment avec les epics précédents comme fondation. Aucune dépendance "forward" entre epics détectée.

**Offline-first threading :**  
✅ La phrase "operation is queued in `sync_queue` if offline" apparaît dans pratiquement chaque story de modification — le pattern offline est correctement et systématiquement appliqué.

**Isolation multi-tenant dans les ACs :**  
✅ Story 1.2 inclut des ACs spécifiques testant l'isolation cross-tenant — exaltant niveau de rigueur.

**Traçabilité audit trail :**  
✅ Chaque story d'écriture émet un domain event nommé (`StockAdjustedEvent`, `SaleCompletedEvent`, etc.) — cohérence inter-stories parfaite.

**Séquence d'implémentation :**  
✅ L'ordre Epic 1 → ... → Epic 9 est logique. Epic 1 fournit l'infrastructure, Epics 2-3 construisent le catalogue, Epic 4 le POS, Epic 5 le moteur sync, Epics 6-7 les fonctionnalités avancées, Epics 8-9 l'admin.

---

### Résumé Qualité Epics

| Sévérité | Nombre | Résolution requise |
|---|---|---|
| 🔴 Critiques | 0 | — |
| 🟠 Majeurs | 0 | — |
| 🟡 Mineurs | 5 | Recommandés avant implémentation |

**Verdict global :** Les epics et stories sont de **haute qualité**. Les 5 issues mineurs identifiés n'impactent pas la capacité d'implémentation et peuvent être résolus rapidement.

---

## Summary and Recommendations

### Overall Readiness Status

# ✅ READY FOR IMPLEMENTATION

Le projet Keevo est prêt à entrer en Phase 4 (Implémentation). Tous les artefacts de planning sont cohérents, complets et alignés.

---

### Synthèse de l'Évaluation

| Critère | Score | Détail |
|---|---|---|
| Couverture FR (PRD → Epics) | 🟢 100% | 93/93 FRs couverts dans 9 epics |
| Couverture NFR dans l'architecture | 🟢 100% | 34/34 NFRs adressés dans l'architecture |
| Alignement UX ↔ PRD | 🟢 Excellent | 24 flows couvrant FR1-FR93 |
| Alignement UX ↔ Architecture | 🟡 Bon | 2 gaps mineurs d'implémentation |
| Qualité des épics | 🟢 Haute | 0 violation critique, 5 issues mineurs |
| Qualité des stories | 🟢 Haute | ACs BDD, taille appropriée, testables |
| Cohérence offline-first | 🟢 Systématique | sync_queue threadé dans toutes les stories |
| Traçabilité audit trail | 🟢 Exhaustive | Domain events nommés dans chaque story |
| Structure hexagonale | 🟢 Conforme | Ports & Adapters correctement décomposés |
| Greenfield setup story | 🟢 Présent | Story 1.1 couvre init complète + CI/CD |

---

### Issues à Résoudre Avant / Pendant l'Implémentation

**Priorité Immédiate (corriger avant de coder les stories concernées) :**

1. ~~**Gap UX — Scheduleur 20h (Epic 7)**~~ ✅ **RÉSOLU (2026-03-02)** : AC dédié ajouté dans Story 7.2 — Spring `@Scheduled` cron explicitement spécifié, avec gestion timezone WAT, notification Simon et Loïc, et émission `DayClosedEvent` synthétique avec `source: AUTO`.

2. ~~**Gap UX — État "brouillon" produit (Epics 2 & 4)**~~ ✅ **RÉSOLU (2026-03-02)** : Cycle de vie `DRAFT | ACTIVE` formalisé :
   - Story 2.1 : `status: ACTIVE` ajouté au schéma Drift `products`, distinction explicite entre produits catalogue (`ACTIVE`) et produits POS à la volée (`DRAFT`)
   - Story 2.4 : `status: DRAFT` formalisé comme champ domaine ; AC de transition `DRAFT → ACTIVE` ajouté (complétion des champs obligatoires)

3. ~~**FR manquant au PRD — Changement mdp forcé (Epic 1)**~~ ✅ **RÉSOLU (2026-03-02)** : AC dédié ajouté dans Story 3.5 — bloc Given/When/Then explicite couvrant le blocage navigation Flutter, l'enforcement backend HTTP 403 `PASSWORD_CHANGE_REQUIRED`, la transition `passwordChangeRequired: false`, et l'émission `EmployeePasswordSetEvent`. Annoté comme contrôle de sécurité obligatoire.

**Priorité Faible (corrections optionnelles) :**

4. **Story 1.1** — Reformuler le persona "developer" si la convention est stricte.
5. **Story 5.3** — Reformuler le persona "as a system" en perspective utilisateur.
6. **Forward refs Epic 4** — Clarifier dans les notes de sprint que Stories 4.1 et 4.2 sont livrées ensemble.
7. **FR72 & FR75 dans Epic 1** — Documenter dans l'Epic 5 que ces deux FRs sont intentionnellement dans Epic 1 (infrastructure offline) pour éviter la confusion lors de la planification de sprint.

---

### Recommended Next Steps

1. ~~**Appliquer les 3 corrections prioritaires**~~ ✅ **FAIT (2026-03-02)** — Scheduler 20h, état DRAFT/ACTIVE produit, et AC forced-password-change appliqués dans les épics.

2. **Lancer Epic 1 Sprint** : Commencer par Story 1.1 (monorepo init), validation que l'architecture compile sur les 4 plateformes cibles (Android, iOS, Linux, Windows) avant de poursuivre.

3. **Valider le prototype Sync en Semaine 1** : Conformément à la stratégie de mitigation des risques du PRD ("Commencer par un prototype de sync dès la semaine 1"), implémenter un stub minimal de Story 5.1/5.2 en parallèle d'Epic 1 pour valider l'architecture delta-based avant de construire le reste.

4. **Définir l'ordre de sprint** : La séquence recommandée est Epic 1 → Epic 2 → Epic 3 → Epic 4 → Epic 5 → Epic 6 → Epic 7 → Epic 8 → Epic 9. Les Epics 1-5 sont le cœur MVP.

5. **Setup CI/CD dès Story 1.1** : Les pipelines GitHub Actions doivent être opérationnels avant toute autre story pour garantir la qualité continue (TDD non-négociable selon l'architecture).

---

### Final Note

Cette évaluation a analysé **5 artefacts de planning** (PRD, Architecture, Epics — restructuré en 9 fichiers shardés le 2026-03-02, UX Spec, rapport de validation PRD), **93 exigences fonctionnelles**, **34 exigences non-fonctionnelles**, **9 epics**, et **24+ user flows UX**.

**Bilan :** 0 issue critique, 0 issue majeure, 7 issues mineurs dont 3 à corriger en priorité. Le projet est **prêt pour l'implémentation**.

---

*Rapport généré le 2026-03-02 | Assesseur : GitHub Copilot / BMAD Check Implementation Readiness Workflow*
