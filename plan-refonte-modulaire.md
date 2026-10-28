# Keevo — Plan de Refonte Modulaire « Mini-Apps Connectées »

> **Document de planification** consolidant les deux audits (`review_claude_opus_8.md` + `review_qwen_6.md`)
> **Date :** 15 juin 2026 · **Auteur :** Claude Opus 4.8
> **Objet :** définir le socle modulaire sur lequel greffer/retirer librement des modules-applications, leur cycle de vie, la gestion des dépendances, et la roadmap de migration.

---

## 0. Comment lire ce document

1. **§1** — synthèse « le meilleur des deux audits » + corrections factuelles (les deux rapports se contredisent sur plusieurs points ; ils sont tranchés ici par vérification du code).
2. **§2** — la **vision cible** traduite en architecture : modules autonomes qui se composent.
3. **§3** — le **cœur de la refonte** : modèle de dépendances (dures / souples) + pattern « bridge » qui permet *facturation seule* OU *facturation + vente liées*.
4. **§4-§7** — le contrat de module (SDK), le catalogue de modules, les licences, le frontend.
5. **§8** — la roadmap de migration (réconciliée, exécutable solo).
6. **§9** — décisions à valider avant de démarrer.

---

# 1. Synthèse des deux audits (le meilleur de chacun + faits tranchés)

## 1.1 Ce qu'on garde de chaque rapport

| Apport | Source | Statut |
|---|---|---|
| Tableau de conformité **FR par FR** (93 FR, fichiers cités) | Qwen | ✅ Repris (corrigé) |
| **Squelettes de code** plugin (`KeevoModule`, `EventBus`, `Permission`, `ModuleLicense`, lifecycle) | Qwen | ✅ Repris et étendu (§3-4) |
| **Packages commerciaux chiffrés** (Free/Standard/Premium/Enterprise, XAF) | Qwen | ✅ Repris + ajout à-la-carte (§6) |
| Détection **plateformes manquantes** (iOS/desktop) & Firebase JSON sur disque | Qwen | ✅ Confirmé par vérif |
| **Mesure réelle du couplage inter-domaines** (imports) | Claude | ✅ Repris (chantier #1) |
| Emphase **découplage-d'abord + API publiées + ArchUnit + Outbox** | Claude | ✅ Cœur de la méthode |
| Rigueur **vérifié vs documenté**, risques R1-R6, migrations DT1/DT2 | Claude | ✅ Repris |
| Décision **Modular Monolith** (pas microservices) | Les deux | ✅ Consensus |

## 1.2 Contradictions entre les deux rapports — tranchées par le code

| Sujet | Qwen disait | Claude disait | **Réalité vérifiée** |
|---|---|---|---|
| Tests Flutter | « <5 %, quasi inexistants » | « solides (164 fichiers) » | **792 fonctions de test / 164 fichiers → solides.** Qwen se contredisait (annonçait aussi « 300+ GREEN»). |
| FR78 activation paiement | ✅ Conforme | ❌ Backlog | **Backend FAIT** (`AdminSubscriptionController`, `ActivatePlanService`) ; **dashboard admin (9-2) partiel.** |
| FR68 désactivation/révocation employé | ✅ Conforme | ❌ Backlog | **Largement fait** (`EmployeeController` deactivate/reactivate + `JwtAuthFilter` STORE_REASSIGNED). Story 8-3 nominalement backlog mais couvert par HF-2/3-5. |
| Firebase credentials | 🔴 « CRITIQUE, dans le workspace » | (non relevé) | **Sur disque mais gitignored/non-tracké** → risque local modéré, à externaliser (Vault/Secret Manager). |
| Plateformes | ❌ iOS/Linux/Windows absents | « à vérifier » | **Confirmé : seuls `android/` + `web/` existent.** Divergence PRD-MVP réelle. |
| Endpoints / contrôleurs | 31 / ~80 | 34 / ~114 | **34 contrôleurs, ~114 `@*Mapping`** (Claude plus exact). |
| Tests backend | « 1200+ » | « 270 fichiers » | **1429 `@Test` / 270 fichiers** — les deux vrais (méthodes vs fichiers). |

## 1.3 Constat consolidé (point de départ de la refonte)

- **Socle technique sain** : hexagonal/DDD discipliné, multi-tenant schema-per-tenant, offline-first robuste, audit immuable, tests substantiels (backend + Flutter). ✅
- **3 dettes bloquantes** (consensus) : **(R1)** `ddl-auto=update` + Flyway off + 0 migration ; **(R2)** état applicatif en mémoire (rate-limit, caches) → pas de scaling horizontal ; **(R3)** observabilité quasi nulle.
- **Couplage inter-bounded-contexts réel** (imports directs : `sync→catalog` 29, `inventory→catalog` 22, `reporting→identity` 21…) → **principal obstacle à la modularité**. C'est LE chantier de la refonte.
- **Modules nominaux vides / mal placés** : `inventory/stock`, `inventory/transfer`, `commerce/payment`, `store/warehouse` vides ; transferts logés dans `catalog/stock`. À ré-aligner avec les vraies frontières de modules.
- **Aucun système de modules/licences/feature-flags** aujourd'hui → tout est compilé et actif pour tous.

> **Conclusion** : la base est bonne mais **monolithiquement couplée**. La refonte ne réécrit pas le métier ; elle **transforme le couplage implicite en composition explicite** et ajoute un **socle de modularité**.

---

# 2. Vision cible traduite en architecture

## 2.1 Ce que tu demandes, reformulé en exigences techniques

| Ta phrase | Exigence technique |
|---|---|
| « tout découpé en module, activable/désactivable » | **Registre de modules** + cycle de vie activate/deactivate par tenant, sans redéploiement |
| « vente sans facturation » | Modules **indépendants** : aucune dépendance dure non justifiée |
| « facturation à la volée, seule » | Chaque module doit fonctionner **standalone** (mode dégradé autonome) |
| « ensuite ajouter la vente, et lier les deux » | **Intégration optionnelle (bridge)** qui s'active *si et seulement si* les deux modules sont présents |
| « gérer les dépendances entre modules » | Graphe de dépendances **dures vs souples** + résolution à l'activation |
| « socle pour greffer n'importe quel nouveau module » | **SDK de module** (contrat stable) + points d'extension |
| « mini-apps connectées, ajouter/retirer au choix » | Chaque module = mini-app **isolée** (UI + données + permissions + routes) reliée par **events + capacités**, pas par appels directs |

## 2.2 Le principe directeur : **autonomie + composition**

> Un module est une **mini-application complète et autonome** (son domaine, ses tables, ses écrans, ses permissions, sa licence). Il **ne connaît pas** les autres modules. Il **publie** des événements et **expose/consomme** des *capacités* via le socle. Quand deux modules complémentaires sont présents, un **pont (bridge)** optionnel les relie — sans les coupler en dur.

```
            ┌──────────────────────── SOCLE (Core Platform) — toujours actif ───────────────────────┐
            │  Module Registry · Dependency Resolver · Entitlement/License · Event Bus + Outbox      │
            │  Capability Registry (services optionnels) · Extension Points · Identity · Tenant · Audit │
            └───────────────────────────────────────────────────────────────────────────────────────┘
                 ▲ contrat KeevoModule (manifest)        ▲ events            ▲ capacités optionnelles
      ┌──────────┴───────┐   ┌──────────┴───────┐   ┌────┴─────────┐   ┌─────┴──────────┐
   ┌──┴───────────────┐ ┌───┴──────────────┐ ┌────┴───────────┐ ┌────┴────────────┐ ┌───┴───────────┐
   │ MINI-APP Catalog │ │ MINI-APP Ventes  │ │ MINI-APP Stock │ │ MINI-APP Factur.│ │ MINI-APP CRM  │
   │ (produits/prix)  │ │ (POS/clôture)    │ │ (inventaire)   │ │ (factures)      │ │ (clients/four)│
   └──────────────────┘ └──────────────────┘ └────────────────┘ └─────────────────┘ └───────────────┘
            └── bridge: Ventes×Stock ──┘     └── bridge: Ventes×Facturation ──┘  └ bridge: Factur×CRM ┘
                                               (s'active SI les deux présents)
```

---

# 3. Cœur de la refonte — Modèle de dépendances entre modules

C'est la partie qui rend ta vision possible. **Trois types de relations**, et un seul d'entre eux crée un vrai couplage.

## 3.1 Les trois types de relation

| Type | Sémantique | Exemple | Effet à l'activation |
|---|---|---|---|
| **`requires` (dure)** | A **ne peut pas** tourner sans B | POS **requires** Catalog (il faut des produits) | Activer A **exige** B ; désactiver B **bloqué** si A actif (ou cascade avec confirmation) |
| **`enhances` (souple / optionnelle)** | A tourne **seul** ; si B présent, fonctions bonus | Facturation **enhances** POS (facture auto depuis une vente) | Aucune contrainte ; le **bridge** s'allume seulement si les deux sont actifs |
| **`extends` (point d'extension)** | A expose un *slot* ; B le remplit | POS expose `post-sale-actions` ; Facturation y branche « générer facture » | Découverte dynamique ; rien si l'autre absent |

> **Règle d'or** : un module **ne déclare jamais une dépendance dure vers un module métier optionnel**. Si une fonction n'est « bonus que quand X est là », c'est un `enhances`, pas un `requires`.

## 3.2 L'exemple Facturation (ton scénario exact)

**Module `facturation` — manifest :**
```jsonc
{
  "id": "invoicing",
  "displayName": "Facturation",
  "requires": ["core"],                 // SEULE dépendance dure = le socle
  "enhances": ["pos", "crm"],           // intégrations OPTIONNELLES
  "publishes": ["invoice.created", "invoice.paid"],
  "consumes":  ["sale.completed"],      // écouté UNIQUEMENT si POS présent
  "provides":  ["InvoiceLookupPort"],   // capacité offerte aux autres
  "needs":     ["ClientLookupPort?"],   // capacité optionnelle (le '?' = facultative)
  "extensionPoints.fills": ["pos.checkout.post-sale-actions"],
  "entitlement": "module.invoicing"
}
```

**Comportement selon ce qui est activé :**

| Modules actifs | Comportement de Facturation |
|---|---|
| `facturation` **seule** | Création **manuelle** de factures, client saisi en texte libre, PDF + envoi WhatsApp. **100 % autonome.** |
| `facturation` + `crm` | Le sélecteur de client lit la **base CRM** (capacité `ClientLookupPort` résolue). |
| `facturation` + `pos` | À chaque `sale.completed`, **facture générée automatiquement** (le bridge écoute l'event) ; bouton « Facturer » dans le checkout (point d'extension rempli). |
| les trois | Vente → facture auto **avec** le client CRM rattaché. |

→ **Personne n'active POS pour facturer. On peut commencer par la facturation, ajouter la vente plus tard, et la liaison se fait toute seule.** C'est exactement ta demande.

## 3.3 Le mécanisme technique de l'intégration optionnelle

Trois leviers du socle, par ordre de découplage croissant :

1. **Event Bus (pub/sub) + Outbox** — le producteur ne connaît pas les consommateurs.
   `POS` publie `sale.completed` ; `Facturation` s'y abonne. Si POS absent → personne ne publie → aucun effet. **Aucun import entre POS et Facturation.**

2. **Capability Registry (service lookup tolérant à l'absence)** — pour les besoins synchrones.
   ```java
   Optional<ClientLookupPort> crm = capabilities.find(ClientLookupPort.class);
   String client = crm.map(c -> c.resolve(clientId)).orElse(freeTextClient);
   ```
   Le CRM **publie** sa capacité au boot ; les autres la **demandent** sans le connaître. Absent → `Optional.empty()` → mode dégradé.

3. **Extension Points (slots Odoo-like)** — pour l'UI et les workflows.
   Un module déclare des *slots* (`pos.checkout.post-sale-actions`) ; les autres y **enregistrent** des contributions. Le rendu n'affiche que ce qui est présent.

4. **Bridges explicites (recommandé pour la logique de liaison non triviale)** — un mini-module de colle `bridge-pos-invoicing`, **auto-activé** par le resolver quand `pos` ET `invoicing` sont actifs. Il contient *toute* la logique de couplage (mapping vente→facture). Avantage : les modules `pos` et `invoicing` restent **purs** ; le couplage vit dans un artefact dédié, testable et désactivable.

## 3.4 Résolution de dépendances (Dependency Resolver du socle)

À l'activation/désactivation, le socle exécute :
- **Activation de A** : vérifier que tous les `requires` sont actifs (sinon proposer de les activer en cascade) → vérifier l'**entitlement** (licence) → exécuter `onActivate` (créer tables, seeds, routes, permissions) → activer les **bridges** dont les deux extrémités sont désormais présentes.
- **Désactivation de B** : si des modules actifs ont `requires: B` → **bloquer** (ou cascade avec confirmation) ; désactiver les bridges qui touchent B ; `onDeactivate` (archiver données, retirer routes/permissions). **Aucune suppression** de données (rétention, cf. §6.3).
- **Détection de cycles** : le graphe `requires` doit rester **acyclique** (vérifié au démarrage par ArchUnit + au runtime par le resolver).

---

# 4. Le contrat de module (SDK « socle pour greffer »)

## 4.1 Manifest — `module.json` (un par module, packagé avec le jar/package)

```jsonc
{
  "id": "pos",
  "version": "1.0.0",
  "displayName": "Point de Vente",
  "category": "business",                 // core | business | premium | platform
  "requires": ["core", "catalog", "inventory"],
  "enhances": ["crm", "invoicing", "reporting"],
  "publishes": ["sale.completed", "sale.cancelled", "day.closed"],
  "consumes":  ["product.archived"],
  "provides":  ["SaleLookupPort"],
  "needs":     ["ClientLookupPort?"],
  "extensionPoints.declares": ["pos.checkout.post-sale-actions"],
  "permissions": ["SALE_CREATE", "SALE_VALIDATE", "DAY_CLOSE"],
  "routes": ["/pos", "/pos/checkout", "/pos/sales-history"],
  "tables": ["sales", "sale_items", "day_closures"],   // provisionnées à l'activation
  "entitlement": "module.pos"
}
```

## 4.2 Interface backend (Java) — étend la proposition Qwen

```java
public interface KeevoModule {
    ModuleManifest manifest();
    void onInstall(ModuleContext ctx);     // 1ère fois : migrations Flyway du module
    void onActivate(TenantContext tenant); // par tenant : seeds, enregistrement routes/perms/capacités
    void onDeactivate(TenantContext tenant); // archive, retire routes/perms
    void onUninstall(ModuleContext ctx);   // purge globale (rare)
    void registerCapabilities(CapabilityRegistry reg); // provides
    void registerEventHandlers(EventBus bus);          // consumes
    void registerExtensions(ExtensionRegistry reg);    // fills slots
}
```

## 4.3 Briques du socle à construire (n'existent pas aujourd'hui)

| Brique | Rôle |
|---|---|
| `ModuleRegistry` | catalogue des modules installés + état (installed/active) par tenant |
| `DependencyResolver` | calcule l'ordre d'activation, vérifie requires, détecte cycles |
| `EntitlementService` | `isEnabled(tenant, moduleId)` / `assertFeature(...)` (source de vérité = DB, miroir dans le JWT) |
| `EventBus` + `event_outbox` | publication transactionnelle fiable, broker-ready |
| `CapabilityRegistry` | publication/résolution tolérante des ports optionnels |
| `ExtensionRegistry` | slots UI/workflow |
| `@RequiresEntitlement` + `@RequiresPermission` | gating déclaratif (aspect) sur use cases/endpoints |
| `ModuleProvisioner` | exécute les migrations Flyway **par module** dans le schéma tenant à l'activation |

## 4.4 Persistance des modules (cohérent avec le multi-tenant existant)

- Chaque module **possède ses tables** ; elles sont **provisionnées à l'activation** dans le schéma `kv_xxxxxx` du tenant (via migrations Flyway **du module**, pas DDL hardcodé).
- **Catalogue global** (`public`) :
  - `module_definitions(id, version, category, dependencies_json, ...)`
  - `tenant_modules(tenant_id, module_id, status, activated_at, expires_at, ...)`
- Un module **ne lit jamais les tables d'un autre module** : il passe par sa **capacité** ou ses **events**. (Règle ArchUnit.)

---

# 5. Catalogue de modules & graphe de dépendances

Découpage cible (réaligne les modules vides/mal placés repérés à l'audit).

| Module | Type | `requires` (dures) | `enhances` (souples) | État actuel |
|---|---|---|---|---|
| **core** (identity, tenant, registry, entitlement, event-bus, audit) | core | — | — | à extraire de l'existant |
| **catalog** (produits, catégories, variantes, prix) | business | core | reporting | existant |
| **crm** (clients, fournisseurs) | business | core | pos, invoicing | existant (`catalog/contact`) |
| **inventory** (stock, mouvements, seuils) | business | core, catalog | reporting | existant (`catalog/stock`) à rapatrier |
| **transfers** (inter-boutiques) | business | core, inventory, store | — | existant dans `catalog/stock` à isoler |
| **counting** (inventaire physique) | business | core, inventory | — | existant (`inventory/counting`) |
| **store** (multi-boutiques, warehouse) | business | core | — | existant ; vider `store/warehouse` placeholder |
| **pos** (vente, panier, clôture) | business | core, catalog, inventory | crm, invoicing, reporting | existant (`commerce/sale`) |
| **invoicing** (facturation) | **premium** | core | pos, crm | **à créer** |
| **reporting** (dashboard, rapports, rentabilité) | business | core | pos, inventory, catalog | existant ; profitability = premium |
| **messaging** (WhatsApp + notifications) | business | core | reporting, pos, inventory | existant |
| **sync** (offline) | core/infra | core | (tous, via events) | existant |
| **import-tracking** (colis Chine) | premium | core | catalog, inventory | à créer (PRD Growth) |
| **expenses/charges** | premium | core | reporting | à créer (demande client gap-analysis) |
| **affiliation** | premium | core | — | à créer (PRD Growth) |
| **admin** (super-admin plateforme) | platform | core | tous (monitoring) | partiel (Epic 9) |

**Lecture clé pour ta vision** : seuls `pos` et `inventory`/`transfers`/`counting` ont des dépendances **dures** (on ne vend pas sans produit ni stock). **Facturation, CRM, reporting, messaging, expenses, affiliation, import-tracking sont activables seuls.**

---

# 6. Licences & packaging (« choisir juste une partie »)

## 6.1 Classement Free vs Premium (validé 15/06/2026)

> Deux couches : **Core** (gratuit, jamais bloqué, plafonds configurables) et **modules payants** (à-la-carte + bundles). Le **moteur reste 100 % à-la-carte** ; un *bundle* = liste d'IDs de modules pré-cochée.

| Capacité / Module | Free | Premium |
|---|---|---|
| Core technique (auth, tenant, sync, audit) | ✅ | ✅ |
| Catalogue produits | ✅ (plafond, défaut **500**) | ✅ illimité |
| POS / vente / clôture journalière | ✅ | ✅ |
| Stock de base + seuils d'alerte | ✅ | ✅ |
| **Boutiques** | ✅ **1** (défaut) | ✅ illimité |
| **Employés** | ✅ **2** (défaut) | ✅ illimité |
| CRM clients/fournisseurs (basique : associer un client à une vente) | ✅ | ✅ |
| Rapport WhatsApp **fin de journée** | ✅ **comportement FIXE** (heure par défaut, non configurable) | ✅ |
| **Configuration des rapports** (activer/désactiver, ajuster heures, types, fréquence — FR60) | ❌ | ✅ |
| **Multi-boutiques + transferts + vue centralisée** | ❌ | ✅ |
| **Inventaire assisté** (counting) | ❌ | ✅ |
| **Rapports avancés** (hebdo, tendances) | ❌ | ✅ |
| **Alertes auto** (stock critique) + **notifications owner** (ex. employé modifie le prix de vente) | ❌ | ✅ |
| **Profitabilité** | ❌ | ✅ |
| **Exports PDF/Excel** | ❌ | ✅ |
| **Facturation** · **Charges** · **Import-tracking** · **Affiliation** | ❌ | ✅ (à-la-carte) |

**Bundles indicatifs** (= ensembles de modules) : *Croissance* (multi-store + reporting avancé + WhatsApp config + alertes), *Pro* (Croissance + invoicing + expenses + counting + transfers), *Enterprise* (tout + API + support). Prix XAF à valider commercialement.

## 6.2 Plafonds & profils **configurables** (super-admin) — décision validée

> Les plafonds (boutiques/produits/employés) et la composition des profils **ne sont plus codés en dur dans l'enum `PlanType`**. Ils vivent dans une **config persistée, éditable par le super-admin**.

- Tables : `plan_profiles(code, max_stores, max_products, max_employees, …)` + `profile_modules(profile_code, module_id)` (= modules inclus par profil).
- `PlanLimitGuard` lit ces valeurs (plus l'enum) → un changement de plafond se fait **sans redéploiement**.
- Défauts Free : **1 boutique / 500 produits / 2 employés** (modifiables).

## 6.3 Mécanique de licence (entitlements) + trial hybride

- `EntitlementService` = **source de vérité en DB** (`tenant_modules`), **miroir signé dans le JWT** (claim `modules`) pour le gating offline.
- **Enforcement temps réel** : `isEnabled()` vérifie `expires_at < now` à **chaque requête** ; le cron nocturne devient nettoyage/notification (plus la source de vérité, + `@SchedulerLock`).
- **Trial hybride** : à l'inscription, **tous les modules payants en trial 6 mois** (préserve le « aha moment ») ; tout module réactivé ensuite a un **trial 14 j**.
- Gating à **4 niveaux** : endpoint backend (`@RequiresEntitlement`), event handler (ignoré si module off), route frontend (guard), UI (slot masqué).

## 6.4 Cycle de vie d'un module & rétention (décisions validées)

Cycle par tenant : `AVAILABLE → TRIAL → ACTIVE → EXPIRED(grâce 7 j, lecture seule) → DISABLED(masqué)`.

- **À l'expiration** : écritures bloquées immédiatement ; **lecture seule + CTA upgrade pendant 7 j** ; ensuite module **masqué** (routes/UI retirées).
- **Le Core (gratuit) n'est JAMAIS bloqué automatiquement** — seuls les plafonds Free s'appliquent. `SUSPENDED` reste réservé au lockout manuel anti-fraude (super-admin).
- **Données conservées à VIE** : ni le masquage ni la rétention ne suppriment quoi que ce soit. À la **réactivation**, le tenant retrouve toutes ses données. **Seule la suppression de compte RGPD (FR91) purge** (`DROP SCHEMA`).
- **Réactiver** : restaure routes/UI + accès aux données existantes.

---

# 7. Frontend modulaire (mini-apps côté client)

## 7.1 Flutter (app marchand) — packages + activation dynamique

```
app/
├── core/                       # socle : module_registry, router dynamique, di, theme, sync
├── modules/                    # 1 package Dart par mini-app
│   ├── keevo_catalog/
│   ├── keevo_pos/
│   ├── keevo_inventory/
│   ├── keevo_invoicing/        # fonctionne seul ; bridge si pos présent
│   ├── keevo_crm/
│   └── keevo_reports/
├── bridges/                    # colle optionnelle (pos×invoicing, invoicing×crm)
└── main.dart                   # bootstrap : lit entitlements → enregistre modules actifs
```

- **Monorepo Melos** (packages Dart internes).
- **Routing dynamique** : chaque module enregistre ses routes ; le router ne monte que les modules actifs (lus depuis `entitlementsProvider`).
- **Lazy loading** (`deferred as`) pour les modules lourds (invoicing PDF, import-tracking).
- **Slots UI** : ex. le checkout POS expose un slot ; `keevo_invoicing` y injecte « Facturer » seulement s'il est actif.
- **Pré-requis** : migration **Riverpod 2 → 3** et unification `StateNotifier`/`AsyncNotifier` (dette relevée par les deux audits).

## 7.2 Dashboard Next.js (admin) — et combler ses 2 trous

- Routing modulaire `app/(modules)/…` + middleware d'entitlements ; `next/dynamic` pour les écrans lourds (recharts).
- **Module Federation : non** pour le MVP (over-engineering) — packages monorepo suffisent.
- **Combler** : ajouter une **CI dashboard** + **tests** (0 aujourd'hui) et finir les écrans Epic 9 (revenus 9-3, analytics 9-4, santé 9-5, activation 9-2 dont le **backend existe déjà**).

---

# 8. Roadmap de migration (réconciliée & exécutable solo)

> Principe : **stabiliser le socle → découpler → greffer le moteur de modularité → prouver avec un module pilote → généraliser**. On ne réécrit pas le métier. Estimations en semaines solo plein temps (fourchette des deux audits).

> ### ⚙️ Re-priorisation validée (< 100 tenants — modularité d'abord, scaling plus tard)
> À ce stade (base réelle mais < 100 tenants, scaling non urgent), l'ordre est ajusté :
> - **Phase 1 allégée** : on garde **observabilité de base** (traces + logs structurés, utiles avec de vrais users) + fixes de dette pas chers (bug RBAC, audit append-only, N+1). **On reporte Redis / HA / scaling horizontal** (inutile sous ~500 tenants).
> - **Différé jusqu'à traction/charge** : Redis (externalisation d'état), haute dispo, canary, plateformes **iOS / desktop**.
> - **Chemin critique « socle modulaire + 1er module greffé prouvé » : ~16-22 semaines** (Phases 0 → 1 allégée → 2 → 3 → 4), au lieu de 29-40 pour le programme complet.
> La numérotation des phases ci-dessous reste la référence ; seul l'effort de la Phase 1 est réduit et certains items sont marqués *différé*.

### Phase 0 — Préparation & garde-fous (≈2 sem)
- Réactiver **Flyway** (déjà au classpath) ; baseline migration du schéma `public` ; **retirer `ddl-auto=update`** en prod (R1).
- **ArchUnit** : geler le graphe de dépendances actuel (baseline) + règle « pas d'import cross-module hors `*.api`/events ».
- **Externaliser secrets** (Firebase JSON, admin password) ; **fail-fast** si secret de prod manquant.
- Backups PostgreSQL automatisés + vérifiés (NFR22) ; staging.
- *Gain* : production-ready, filet de sécurité. *Risque* : faible.

### Phase 1 — Stabilisation socle & dette (≈4-6 sem) — **bloquant**
- **Observabilité** : OpenTelemetry + Micrometer + Prometheus/Grafana + logs JSON corrélés `tenantId/traceId` (R4).
- **Externaliser l'état** : Redis (rate-limit, caches, futur blacklist JWT) → débloque le scaling horizontal (R3).
- Corriger la dette consensuelle : N+1 (tenant list, pending sales), bug `DayClosureDeltaProvider`, RBAC (EMPLOYEE annule brouillon), CORS prod, allow-list schéma (`^kv_[a-z0-9]+$`), audit append-only DB.
- *Gain* : exploitable & scalable. *Risque* : migration schéma tenants existants (tester sur copie prod).

### Phase 2 — Découplage en bounded contexts propres (≈6-8 sem) — **le vrai chantier**
- Extraire le **core** (identity, tenant, provisioning, audit, shared-kernel : `Money`, `TenantId`, `ActorId`, `DomainEvent`).
- Pour chaque domaine : définir une **API publique** (`{module}/api/` : interfaces + DTO + events) ; tout le reste → `internal`. **Faire respecter par ArchUnit.**
- **Ré-aligner les modules** : rapatrier stock/transfer/counting sous `inventory` ; trancher `payment`/`warehouse` (implémenter ou supprimer les placeholders).
- Introduire **EventBus + Outbox** (enveloppe standard) en remplacement des `ApplicationEvent` directs.
- *Gain* : modules réellement isolés (pré-requis absolu de l'activation/désactivation). *Risque* : régressions → couvertes par les 1429 tests + ArchUnit.

### Phase 3 — Moteur de modularité (le socle) (≈4-6 sem)
- Construire `ModuleRegistry`, `DependencyResolver`, `CapabilityRegistry`, `ExtensionRegistry`, `ModuleProvisioner`, `@RequiresEntitlement`.
- Tables `module_definitions` + `tenant_modules`.
- Convertir 2-3 modules existants au contrat `KeevoModule` (manifest + onActivate).
- *Gain* : activation/désactivation backend opérationnelle. *Risque* : moyen.

### Phase 4 — Module pilote « standalone + bridge » (≈3-4 sem) — **preuve de la vision**
- Implémenter **Facturation** comme **premier module greffé** : autonome (factures manuelles + PDF + WhatsApp) **+** `bridge-pos-invoicing` (facture auto sur `sale.completed`) **+** `bridge-invoicing-crm`.
- Valide de bout en bout : *facturation seule* → *ajout POS* → *liaison automatique*. C'est ton scénario, transformé en test d'acceptation.
- *Gain* : patron réutilisable pour tout futur module.

### Phase 5 — Frontend modulaire (≈6-8 sem)
- Melos + packages Flutter + routing/slots dynamiques + lazy loading ; migration Riverpod 3.
- Dashboard : routing modulaire + **CI/tests** + finir Epic 9.
- *Gain* : mini-apps côté client alignées sur les licences.

### Phase 6 — Licences, migration données & go-live (≈4-6 sem)
- `EntitlementService` complet + claim JWT `modules` + console super-admin (activer/désactiver/packager) → finit FR78/79 monétisation.
- **Backfill** `tenant_modules` des tenants existants (tous = Core + Premium Trial selon statut actuel) ; provisioning idempotent.
- Conformité **FR91 (RGPD)** ; déploiement **canary** (10 % tenants) + monitoring + runbook ; IaC minimal pour HA (R-uptime).
- *Gain* : plateforme modulaire **commercialisable**.

**Total indicatif :** ~29-40 semaines (≈ 7-10 mois solo) — convergent avec les deux audits. **Phases 0-1 non négociables avant tout le reste.**

### Modules complémentaires (post-pilote, mêmes patrons)
`expenses/charges` (demande client réelle), `import-tracking`, `affiliation`, `export PDF/Excel`, rôles avancés (Shop Manager), plus FR manquants (FR44 leaderboard, FR81/82 alertes/push global) — chacun greffé via le SDK sans toucher au cœur.

---

# 9. Décisions validées (15 juin 2026)

Les trois choix structurants ont été tranchés avec le fondateur :

1. **Granularité commerciale → Hybride, moteur à-la-carte.** Chaque module est activable individuellement (`tenant_modules`) ; un *bundle* n'est qu'une liste d'IDs de modules pré-cochée (raccourci de vente). Trial **par module** (14 j) par défaut. *(cf. §6.1)*
2. **Stratégie de schéma → Garder schema-per-tenant.** Contexte : **< 100 tenants** aujourd'hui. Provisioning d'un module = migrations **Flyway par module** dans le schéma `kv_xxxxxx` à l'activation (remplace le hack runtime `TenantSchemaSyncService`). À < 100 schémas, provisionner/backfiller est un batch trivial. **Réévaluation de la bascule `public + tenant_id + RLS` repoussée à ~500 tenants** (décision « one-way door » non urgente).
3. **Liaison entre modules → Modèle nuancé.** Events (pub/sub) + capabilities (`Optional`) **par défaut** = zéro couplage ; contrats d'events dans un **catalogue partagé léger** ; **bridge dédié réservé** aux orchestrations non triviales (ex. mapping vente→facture si la logique grossit).

---

## Annexe — Fichiers de référence

| Document | Chemin |
|---|---|
| Audit Claude | `review_claude_opus_8.md` |
| Audit Qwen | `review_qwen_6.md` |
| PRD / Architecture | `_bmad-output/planning-artifacts/{prd,architecture}.md` |
| Sprint status | `_bmad-output/implementation-artifacts/sprint-status.yaml` |
| Dette | `_bmad-output/deferred-work.md` |
| Gap client (charges/import) | `_bmad-output/gap-analysis-client-importateur.md` |

*Fin du plan — `plan-refonte-modulaire.md`*
