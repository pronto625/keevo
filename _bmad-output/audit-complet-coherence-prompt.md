# Audit Complet de Cohérence — Stories, Backend & Interface Utilisateur — Projet Keevo

Je veux que tu réalises un **audit complet de l'application Keevo**, en repartant de zéro, sans supposer que tout est correct.

L'objectif **n'est PAS de modifier le code**, mais de vérifier que toutes les fonctionnalités déjà développées sont réellement présentes, cohérentes et correctement reliées entre elles.

---

## Contexte du Projet

**Keevo** est une application mobile/desktop **Flutter** (offline-first) de gestion d'inventaire et de ventes pour commerçants au Cameroun, avec :
- **Backend** : Spring Boot 3.5.x, Java 21, Maven — architecture hexagonale multi-tenant (schéma PostgreSQL par tenant via Flyway)
- **Frontend mobile/desktop** : Flutter 3.x, Riverpod, GoRouter, Drift + SQLCipher (local encrypté), Material 3
- **Dashboard Super Admin** : Next.js 16, React 19, TypeScript, Tailwind 4, TanStack React Query, Recharts
- **Base de données** : PostgreSQL 16 (Docker Compose), schéma-per-tenant ThreadLocal, Flyway migrations
- **Sync** : REST delta-based (push/pull), 7 jours offline max, queue locale Drift, SQLCipher chiffré
- **Notifications** : Firebase Cloud Messaging (FCM) + WhatsApp (Wassender / Twilio failover)
- **Authentification** : JWT 24h + refresh token, two-step login (sélection tenant), BCrypt ≥12

### Structure du projet
```
keevo/
├── backend/          → Spring Boot (src/main/java/com/keevo/)
│   ├── admin/        → catalogue platform, monitoring sync, tenants
│   ├── catalog/      → products, categories, contacts, variants, stock
│   ├── commerce/     → POS, sales, payment
│   ├── identity/     → auth, employee, onboarding, user
│   ├── inventory/    → counting, stock, transfer
│   ├── messaging/    → notifications, WhatsApp
│   ├── reporting/    → dashboard, profitability, reports
│   ├── shared/       → security, persistence, web (exceptions), domain
│   ├── store/        → stores, warehouses
│   ├── subscription/ → plans
│   └── sync/         → delta providers, sync engine
├── app/              → Flutter (lib/)
│   ├── core/         → di, network, storage, sync, auth, theme, router, scaffold, notification, services, widget
│   └── features/     → audit, auth, catalog, contact, dashboard, debug, identity, inventory, notifications, onboarding, pos, profitability, reports, settings, stores, sync_indicator, team
└── dashboard/        → Next.js (src/app/) → login, monitoring, tenants
```

### Documents de référence
- `_bmad-output/planning-artifacts/prd.md` — 94 FR, 34 NFR, 14 domaines
- `_bmad-output/planning-artifacts/architecture.md` — spine architecturale, décisions techniques
- `_bmad-output/planning-artifacts/ux-design-specification.md` — personas (Simon propriétaire, Loïc employé, Toor super admin), principes UX, core loop
- `_bmad-output/planning-artifacts/epics/` — 9 Epics + index
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — statut de TOUTES les stories
- `_bmad-output/implementation-artifacts/deferred-work.md` — dette technique documentée
- `_bmad-output/planning-artifacts/implementation-readiness-report-2026-07-23.md` — dernier rapport d'audit complet

### Stories par Epic
| Epic | Stories |
|------|---------|
| **Epic 1** — Foundation & Auth | 1-1 à 1-8, UI-1, HF-1, HF-2 |
| **Epic 2** — Catalogue Produits | 2-1 à 2-5 |
| **Epic 3** — Multi-Boutiques | 3-1 à 3-5 |
| **Epic 4** — POS | 4-1 à 4-4 |
| **Epic 5** — Sync Offline-First | 5-1 à 5-6 |
| **Epic 6** — Inventaire | 6-1 à 6-4, 6-2a |
| **Epic 7** — Rapports & Dashboard | 7-1 à 7-6 |
| **Epic 8** — Alertes & Notifications | 8-0, 8-1, 8-6 |
| **Epic 9** — Super Admin Dashboard | 9-1, 9-6 |
| **Epic 10** — Infrastructure & Ops | 10-1 à 10-6 |
| **V1 Stabilization** | v1s-12-1 à v1s-15-5 |
| **Spécifications additionnelles** | spec-edition-complete-employe, spec-mot-de-passe-oublie, spec-notif-owner-actions-employe, spec-hf2-ghost-badge-sales-scoping |

---

## Mission

Parcours intégralement le projet. Analyse minutieusement :

- toutes les stories BMAD (dans `_bmad-output/implementation-artifacts/`)
- les exigences fonctionnelles du PRD (94 FRs)
- les documents d'architecture et d'UX design
- le backend Spring Boot (Java 21, hexagonal, multi-tenant)
- le frontend Flutter (Riverpod, GoRouter, Drift/SQLCipher)
- le dashboard Next.js Super Admin
- les modèles de données (entités JPA + tables Drift)
- les API REST (`/api/v1/...`)
- les états de l'application (loading, empty, error, offline, unauthorized, expired)
- les écrans Flutter, composants UI, navigation GoRouter
- les workflows complets utilisateur

Tu dois agir comme un **QA Architect**, un **Product Owner**, un **Software Architect** et un **Senior Full Stack Engineer (Flutter + Spring Boot + Next.js)** réunis.

---

# Important

Tu ne dois **rien corriger**. Tu ne dois écrire **aucun code**. Tu ne dois proposer **aucun refactoring**. Tu dois uniquement réaliser un audit. Je veux d'abord comprendre tout ce qui est incohérent avant de commencer les corrections.

---

# Ce que tu dois vérifier

## 1. Couverture des Stories BMAD

Pour chaque story listée dans `sprint-status.yaml` :

- est-elle réellement implémentée dans le code ?
- est-elle complètement implémentée (tous les Acceptance Criteria) ?
- est-elle seulement partiellement développée ?
- certains AC sont-ils absents du code ?
- certaines User Tasks sont-elles oubliées ?
- certaines règles métier sont-elles manquantes ?
- les stories marquées `done` le sont-elles vraiment ?

Pour chaque story, indique précisément : son état réel, ce qui manque, où cela manque (fichier/ligne), pourquoi la story n'est pas complètement couverte.

**Stories à auditer en priorité** : Toutes celles marquées `done` dans `sprint-status.yaml`.

---

## 2. Vérification Backend ↔ Frontend (Spring Boot ↔ Flutter)

Pour chaque fonctionnalité, vérifie que :

- le backend expose bien les APIs attendues (contrôleurs `@RestController` dans `adapter/in/rest/`)
- le frontend Flutter les consomme réellement (via `ApiService`, datasources, repositories)
- les modèles correspondent : entités JPA ↔ modèles Freezed Flutter ↔ DTOs JSON
- les enums correspondent (ex: `PlanType`, `EmployeeStatus`, `SaleStatus`, `ProductStatus`, `SectorType`)
- les validations correspondent (Bean Validation ↔ validation Flutter)
- les erreurs sont correctement gérées (`GlobalExceptionHandler` ↔ `ApiService` + intercepteurs)
- les réponses API sont bien utilisées (pas de champs ignorés)

Détecte notamment :
- **API backend existante mais jamais appelée** par le Flutter
- **Écran Flutter utilisant des données mockées ou locales** alors qu'une API existe
- **Endpoint jamais relié** à un écran
- **Écran non connecté** au backend (données purement locales sans sync)
- **Champ backend jamais affiché** dans l'UI Flutter
- **Champ UI Flutter jamais envoyé** au backend
- **Données perdues entre les couches** (backend → JSON → Flutter → Drift)

---

## 3. Audit complet de l'Interface Utilisateur Flutter

Reparcours entièrement l'application Flutter. Écran après écran. Workflow après workflow.

Vérifie notamment les features Flutter :
- `auth/` — login, register, two-step (tenant picker), forgot password, reset password, password change
- `onboarding/` — terms, sector selection, shop name, wizard
- `pos/` — POS page, checkout, pending sales, sale success, sales history, sale detail
- `catalog/` — products list, product form, categories, CSV import
- `contact/` — clients list/form, suppliers list/form
- `inventory/` — global stock overview, inventory counting, gap report, launch, transfer history
- `stores/` — stores list
- `team/` — team page, create employee
- `dashboard/` — dashboard, store dashboard
- `reports/` — report history, report detail, owner reports
- `profitability/` — product profitability detail
- `settings/` — account, settings, subscription, report preferences
- `notifications/` — notifications page
- `audit/` — audit page
- `sync_indicator/` — sync conflict log, sync settings

Cherche :
- écran inchangé malgré plusieurs stories réalisées
- fonctionnalité backend invisible dans l'UI
- bouton absent, action oubliée, menu manquant
- écran incomplet, dialogue absent, popup oubliée
- navigation cassée, écran inaccessible
- élément UI jamais utilisé, composant non branché
- mauvais état d'affichage, mauvais wording
- workflow interrompu

---

## 4. Vérification des Flux Fonctionnels Complets

Teste mentalement **tous** les parcours utilisateur :

1. **Onboarding complet** : inscription WhatsApp → validation OTP → sélection secteur → template → nom boutique → POST tenant → wizard → dashboard
2. **Authentification two-step** : login → JWT → sélection tenant (si multi-tenant) → dashboard
3. **Mot de passe oublié** : demande OTP WhatsApp → validation → reset password
4. **POS — vente complète** : scan/search product → add to cart → apply discount → checkout → payment mode → success → day closure
5. **POS — vente brouillon (draft)** : création produit inline → vente avec draft → promotion auto → validation
6. **Catalogue** : CRUD produit + variants → calcul marge → seuils stock → import CSV → catégories → archivage
7. **Multi-boutiques** : création boutique → vue centralisée stocks → transfert inter-boutiques → employé assignation
8. **Inventaire** : lancement session → formulaire guidé → ajout rapide produit → calcul écarts → validation ajustements
9. **Sync offline-first** : mode hors-ligne → queue locale → reconnexion → push/pull delta → résolution conflits → gate 7 jours
10. **Rapports** : dashboard matinal → message motivationnel → end-of-day WhatsApp → rapport hebdomadaire → profitability
11. **Notifications** : FCM push → alertes stock critique → tendances ventes → actions owner sur employé
12. **Paramètres** : profil → abonnement (Free/Premium/Trial) → préférences rapports → changement mot de passe → session revoke
13. **Gestion équipe** : invitation employé WhatsApp → rôles → activité tracking → révocation
14. **Super Admin Dashboard** : gestion tenants → activation compte → monitoring → analytics

Pour chaque parcours : est-il complet ? Existe-t-il des impasses ? Une story casse-t-elle un ancien workflow ?

---

## 5. Vérification des Connexions (Traçabilité complète)

Assure-toi que tout est bien relié, dans les deux sens :

```
Flutter UI (présentation)
    ↓
Riverpod Provider / Notifier
    ↓
Repository / UseCase (domaine Flutter)
    ↓
DataSource (remote: ApiService/Dio, local: Drift)
    ↓
HTTP /api/v1/...
    ↓
Spring Boot @RestController
    ↓
@Service / @UseCase
    ↓
Port/Adapter (hexagonal)
    ↓
JPA Repository
    ↓
PostgreSQL (schema kv_XXXXXX)
```

Détecte : appels morts, composants inutilisés, services jamais appelés, méthodes jamais exécutées, routes GoRouter non atteignables, navigation non utilisée.

---

## 6. Vérification de Cohérence Fonctionnelle

Cherche toutes les incohérences :
- deux écrans Flutter faisant la même chose
- deux règles métier contradictoires (backend vs frontend)
- workflow incohérent
- logique métier différente entre backend et frontend
- comportement différent selon l'écran
- champs obligatoires dans une vue mais pas dans une autre
- états impossibles, transitions oubliées
- Plan limits (Free: 1 store, 500 produits, 3 employés) réellement enforceés ?

---

## 7. Vérification Visuelle et UX

Même si le code existe, vérifie que l'expérience utilisateur est cohérente :
- évolution backend invisible dans le Flutter
- écran qui aurait dû évoluer après une story
- écran encore "ancien" (placeholder, mock)
- composants non mis à jour
- design incohérent avec les nouvelles stories
- Material 3 Indigo Sky palette (`#3B5BDB`, `#51CF66`, `#FCC419`, `#FA5252`, `#0D1B2A`) respectée ?
- Inter font (weights 300–700) correctly applied?
- Dark mode supporté ?

---

## 8. Vérification des États d'Écran

Pour chaque écran Flutter, vérifie les états :
- **Loading** (shimmer/skeleton ou spinner)
- **Empty** (illustration + message + CTA)
- **Error** (message + retry)
- **Success** (données affichées)
- **Offline** (indicateur connectivité + queue sync)
- **Unauthorized** (redirection login)
- **Expired Session** (refresh token silencieux)
- **Retry** (bouton réessayer)
- **Refresh** (pull-to-refresh)

---

## 9. Vérification de la Navigation GoRouter

Vérifie dans `app/lib/core/router/app_router.dart` :
- tous les liens, toutes les routes
- tous les boutons de navigation
- tous les retours (back navigation)
- les deep links (notifications, reset password)
- les accès conditionnels (auth guard, role-based: OWNER vs EMPLOYEE)
- les redirections (onboarding → wizard → dashboard, expired token → login)
- la bottom navigation bar (MainShell) : 5 tabs OWNER, 4 tabs EMPLOYEE

---

## 10. Vérification du Dashboard Super Admin (Next.js)

Vérifie que le dashboard Next.js (`keevo/dashboard/`) :
- est bien connecté aux APIs backend (`/api/v1/admin/...`)
- affiche correctement les tenants, le monitoring, les analytics
- gère l'authentification admin séparée
- n'a pas d'écrans cassés ou de données mockées

---

## 11. Vérification Finale — Deuxième Passe sur le Deferred Work

Le fichier `deferred-work.md` contient de la dette technique **documentée mais non corrigée**. Pour chaque entrée :
- vérifie si elle a été corrigée depuis (certaines entrées datent de juin-juillet 2026)
- identifie celles qui sont devenues critiques
- distingue la dette "acceptable V1" de la dette "bloquante"

---

# Format Attendu

Pour chaque anomalie, indique systématiquement :

| Champ | Description |
|-------|-------------|
| **ID Story** | Identifiant de la story concernée (ex: `4-2`, `v1s-14-12`) |
| **Gravité** | 🔴 Critique / 🟠 Importante / 🟡 Moyenne / 🔵 Mineure |
| **Module** | Backend / Flutter / Dashboard / Sync / Auth / etc. |
| **Écran** (si UI) | Chemin de la page Flutter |
| **Backend concerné** | Controller/Service/Repository |
| **Frontend concerné** | Provider/Page/Widget |
| **Description** | Ce qui est incohérent |
| **Pourquoi** | La racine du problème |
| **Impact fonctionnel** | Ce que l'utilisateur ne peut pas faire |
| **Fichiers** | Liste des fichiers avec lignes si pertinent |
| **Preuves** | Extrait de code, contrat API, capture logique |
| **Recommandation** | Sans implémentation — juste ce qu'il faudrait faire |

---

# Catégories de Gravité

### 🔴 Critiques
Fonctionnalités cassées, stories non respectées, flux bloquants, incohérences majeures, régressions.

### 🟠 Importantes
Fonctionnalités incomplètes, connexions manquantes, UI non alignée avec le backend, AC non couverts.

### 🟡 Moyennes
Incohérences d'interface, éléments oubliés, problèmes de logique, états manquants.

### 🔵 Mineures
Petites incohérences visuelles, wording, détails UX, deferred work non critique.

---

# Contraintes

- **Ne te base jamais uniquement sur la documentation.** Analyse le code source réel.
- Vérifie les implémentations ligne par ligne lorsque nécessaire.
- Compare systématiquement la documentation, le backend et le frontend.
- Ne fais aucune hypothèse sans preuve dans le code.
- Si tu n'es pas certain d'un point, indique-le explicitement.
- **Ne modifie aucun fichier. Ne génère aucun patch. Ne propose aucune correction automatique.**
- Commence par lire `sprint-status.yaml` pour avoir la liste exhaustive des stories et leur statut.
- Lis `deferred-work.md` en entier pour ne pas signaler comme anomalies des problèmes déjà documentés.
- Lis `implementation-readiness-report-2026-07-23.md` pour comprendre le dernier état d'audit connu.
- Utilise `grep_search` et `semantic_search` massivement pour vérifier les connexions backend↔frontend.

Ton rôle est uniquement de produire un **rapport d'audit exhaustif** afin que nous décidions ensuite, ensemble, des corrections à apporter.
