# Keevo — Spécification du Socle Modulaire (SDK)

> **Document technique prêt à implémenter** · 15 juin 2026 · Claude Opus 4.8
> **Cadre :** décisions validées — schema-per-tenant conservé (< 100 tenants), entitlements **à-la-carte hybride**, liaison **events/capabilities** (bridges réservés à l'orchestration).
> **Cible :** Phase 3 de la roadmap (`plan-refonte-modulaire.md`). Prérequis : Phase 0 (Flyway actif) + Phase 2 (modules avec API publiées).

---

## 0. Objectif du socle

Fournir le **noyau (`core`) sur lequel se greffe n'importe quel module-mini-app**, capable de :
1. **découvrir** les modules installés (manifest) ;
2. **activer/désactiver** un module **par tenant** sans redéploiement (avec provisioning des tables) ;
3. **résoudre les dépendances** (dures) et **brancher les intégrations optionnelles** (events/capabilities) ;
4. **gater** l'accès selon la **licence** (entitlement) et les **permissions** ;
5. garantir qu'un module **ignore les autres** (couplage = zéro import direct, vérifié par ArchUnit).

---

## 1. Vue d'ensemble des composants

```
com.keevo.core/
├── module/
│   ├── KeevoModule.java            (contrat implémenté par chaque module)
│   ├── ModuleManifest.java         (modèle du module.json)
│   ├── ModuleRegistry.java         (catalogue + état par tenant)
│   ├── DependencyResolver.java     (requires, cycles, cascade)
│   ├── ModuleProvisioner.java      (Flyway par module dans le schéma tenant)
│   └── ModuleLifecycleService.java (orchestre install/activate/deactivate)
├── capability/
│   ├── CapabilityRegistry.java     (provides / needs — Optional)
│   └── Capability.java             (marqueur)
├── extension/
│   ├── ExtensionRegistry.java      (slots déclarés / remplis)
│   └── ExtensionPoint.java
├── event/
│   ├── DomainEvent.java            (enveloppe standard ; publication via Spring ApplicationEventPublisher)
│   ├── TenantEventContextDecorator.java  (restaure TenantContext avant @ApplicationModuleListener async)
│   └── catalog/                    (contrats d'events inter-modules partagés)
│   (registre/outbox = Spring Modulith Event Publication Registry — table event_publication, voir §7)
├── entitlement/
│   ├── EntitlementService.java     (isEnabled / isReadOnly / assertWritable — temps réel)
│   ├── RequiresEntitlement.java    (@annotation + aspect)
│   └── RequiresPermission.java     (@annotation + aspect)
└── admin/
    └── ModuleAdminController.java   (API console super-admin)
```

Tables `public` ajoutées (migrations Flyway Phase 0/3) : `module_definitions`, `tenant_modules`, `plan_profiles`, `profile_modules`, et `event_publication` (registre Spring Modulith — voir §7).

---

## 2. Modèle de données

### 2.1 `public.module_definitions` (catalogue global, seedé au boot)

| Colonne | Type | Note |
|---|---|---|
| `id` | VARCHAR(50) PK | ex. `"invoicing"` |
| `version` | VARCHAR(20) | semver du module installé |
| `display_name` | VARCHAR(100) | « Facturation » |
| `category` | VARCHAR(20) | `core` \| `business` \| `premium` \| `platform` |
| `requires` | JSONB | `["core","catalog"]` |
| `enhances` | JSONB | `["pos","crm"]` |
| `is_free` | BOOLEAN | gratuit (Core) vs payant |
| `manifest` | JSONB | manifest complet (cache) |
| `installed_at` | TIMESTAMPTZ | |

> Seedé idempotemment au démarrage à partir des `module.json` détectés sur le classpath.

### 2.2 `public.tenant_modules` (état par tenant — cœur de l'à-la-carte)

| Colonne | Type | Note |
|---|---|---|
| `id` | UUID PK | |
| `tenant_id` | UUID | FK logique vers `public.tenants` |
| `module_id` | VARCHAR(50) | FK logique vers `module_definitions` |
| `status` | VARCHAR(20) | `AVAILABLE`\|`TRIAL`\|`ACTIVE`\|`EXPIRED`\|`DISABLED` |
| `tier` | VARCHAR(20) | `FREE`\|`STANDARD`\|`PREMIUM`\|`ENTERPRISE` |
| `activated_at` | TIMESTAMPTZ | |
| `expires_at` | TIMESTAMPTZ | trial/abonnement |
| `provisioned_version` | VARCHAR(20) | version des tables déjà migrées dans le schéma |
| `grace_until` | TIMESTAMPTZ | fin de la grâce lecture seule (= `expires_at` + 7 j) |
| `disabled_at` | TIMESTAMPTZ | horodatage du masquage — **données conservées à vie**, pas de purge |
| | | **UNIQUE(tenant_id, module_id)** |

**Machine d'état** (décisions validées 15/06/2026) :
```
AVAILABLE → TRIAL → ACTIVE → EXPIRED (grâce 7 j : LECTURE SEULE + CTA) → DISABLED (masqué)
                          ↘ ACTIVE (renouvellement)        DISABLED → (réactivable) ACTIVE
```
- **Trial hybride** : à l'inscription, tous les modules payants en **trial 6 mois** (préserve le « aha moment ») ; tout module réactivé ensuite a un **trial 14 j**.
- **EXPIRED** : écritures bloquées, **lecture seule** pendant 7 j (`grace_until`), CTA upgrade.
- **DISABLED** : routes/UI/handlers retirés ; **tables et données conservées à VIE** (réapparaissent à la réactivation). Seule la suppression de compte RGPD (FR91) purge.
- **Le Core (gratuit) n'entre jamais dans ce cycle** — il n'est jamais auto-désactivé.

### 2.2bis `public.plan_profiles` & `public.profile_modules` (plafonds configurables — super-admin)

> Les plafonds et la composition des profils **ne vivent plus dans l'enum `PlanType`** mais en base, éditables par le super-admin (décision validée).

| `plan_profiles` | Type | Note |
|---|---|---|
| `code` | VARCHAR(30) PK | `FREE`, `PREMIUM`, … |
| `max_stores` | INT | défaut FREE = **1** |
| `max_products` | INT | défaut FREE = **500** |
| `max_employees` | INT | défaut FREE = **2** |

`profile_modules(profile_code, module_id)` = modules inclus par profil (ex. FREE = core/catalog/pos/inventory-base/crm-basique).
`PlanLimitGuard` lit `plan_profiles` (plus l'enum) → changement de plafond **sans redéploiement**. Modifiable via la console super-admin.

### 2.3 Provisioning des tables d'un module (schema-per-tenant)

À l'activation, les tables du module sont créées **dans le schéma `kv_xxxxxx` du tenant** via **Flyway programmatique dédié au module** :

```java
Flyway.configure()
    .dataSource(dataSource)
    .schemas(tenantSchema)                 // ex. "kv_000042"
    .defaultSchema(tenantSchema)
    .table("flyway_history_" + moduleId)   // historique ISOLÉ par module
    .locations("classpath:db/modules/" + moduleId)  // V1__*.sql, V2__*.sql du module
    .baselineOnMigrate(true)
    .load()
    .migrate();
```

- Chaque module embarque ses migrations sous `resources/db/modules/<moduleId>/`.
- Historique **isolé par module** (`flyway_history_<moduleId>`) → activation/évolution indépendantes, pas de collision de versions entre modules.
- À < 100 tenants, provisionner un module sur toute la base = boucle batch triviale.
- **Désactivation** = on **ne supprime pas** les tables (rétention) ; on retire routes/permissions/handlers.
- **Note d'implémentation (Story 13.4) :** le Flyway de module utilise `.baselineVersion("0")` en plus de la configuration ci-dessus. Un schéma tenant n'est jamais vide (tables du cœur) : avec la baseline par défaut de Flyway (`1`), le premier `migrate` poserait une baseline à 1 et sauterait silencieusement le `V1` du module. Avec `0`, tous les scripts à partir de `V1` s'appliquent ; la ligne de baseline est ignorée pour `provisioned_version`. Conséquence pour la conversion d'un module existant (13.9) : son `V1` s'exécute sur des tenants qui ont déjà ses tables → il doit être idempotent.

---

## 3. Le manifest `module.json`

Embarqué dans chaque module (`resources/module.json`), validé au boot.

```jsonc
{
  "id": "invoicing",
  "version": "1.0.0",
  "displayName": "Facturation",
  "category": "premium",
  "requires": ["core"],
  "enhances": ["pos", "crm"],
  "publishes": ["invoice.created", "invoice.paid"],
  "consumes":  ["sale.completed"],
  "provides":  ["InvoiceLookupPort"],
  "needs":     ["ClientLookupPort?"],          // '?' = optionnelle
  "extensionPoints": {
    "declares": [],
    "fills": ["pos.checkout.post-sale-actions"]
  },
  "permissions": ["INVOICE_CREATE", "INVOICE_VIEW", "INVOICE_SEND"],
  "routes": ["/invoicing", "/invoicing/:id"],
  "entitlement": "module.invoicing",
  "free": false
}
```

Modèle Java : `ModuleManifest` (record) désérialisé via Jackson, validé (ids connus, pas de cycle dans `requires`).

---

## 4. Contrat de module

### 4.1 `KeevoModule`

```java
public interface KeevoModule {
    ModuleManifest manifest();

    /** 1ère installation (globale) — enregistre la définition. Idempotent. */
    void onInstall(ModuleInstallContext ctx);

    /** Activation POUR UN TENANT — provisioning + seeds. Idempotent. */
    void onActivate(ModuleTenantContext ctx);

    /** Désactivation POUR UN TENANT — archive, retire routes/perms. Données conservées. */
    void onDeactivate(ModuleTenantContext ctx);

    /** Publie les capacités offertes (provides). */
    default void registerCapabilities(CapabilityRegistry reg) {}

    /** Events consommés : déclarés via @ApplicationModuleListener (auto-enregistrés par Spring
     *  Modulith) — pas de registration manuelle. Le manifest les liste pour la doc/validation. */

    /** Remplit les points d'extension d'autres modules (fills). */
    default void registerExtensions(ExtensionRegistry reg) {}
}
```

### 4.2 Contexts

```java
public record ModuleInstallContext(DataSource dataSource) {}

public record ModuleTenantContext(
    TenantId tenantId,
    String tenantSchema,        // "kv_000042"
    DataSource dataSource,
    ActorId actor               // qui déclenche (super-admin ou owner)
) {}
```

> **Règle de pureté** (héritée de l'archi existante) : ces contexts ne portent **aucun** type HTTP/Spring-web. `ActorId` injecté explicitement.

---

## 5. ModuleRegistry & DependencyResolver

### 5.1 `ModuleRegistry`

```java
public interface ModuleRegistry {
    Collection<ModuleManifest> installed();                 // tous les modules sur le classpath
    Optional<KeevoModule> module(String moduleId);
    Set<String> activeModules(TenantId tenant);             // lit tenant_modules
    boolean isActive(TenantId tenant, String moduleId);
}
```

- Au démarrage : scanne le classpath (`KeevoModule` beans Spring), valide les manifests, seed `module_definitions`.
- `activeModules(tenant)` est **mis en cache courte durée** (par requête) — à < 100 tenants, lecture DB acceptable.

### 5.2 `DependencyResolver`

```java
public interface DependencyResolver {
    /** Ordre topologique d'activation incluant les requires manquants. */
    ActivationPlan planActivation(TenantId tenant, String moduleId);

    /** Refuse si un module actif a 'requires' vers celui-ci. */
    DeactivationCheck checkDeactivation(TenantId tenant, String moduleId);
}
```

Règles :
- **Activation** : calcule la fermeture transitive de `requires` ; remonte les modules manquants (`AVAILABLE`) → propose activation en cascade (avec vérif entitlement sur chacun).
- **Cycle** : le graphe `requires` doit être **acyclique** — vérifié au boot (sinon refus de démarrage) + à chaque ajout de module.
- **Désactivation** : si `∃ M actif : moduleId ∈ M.requires` → **bloqué** (renvoie la liste des dépendants) ; sinon OK, on éteint aussi les bridges touchant `moduleId`.
- `enhances`/`needs` ne créent **jamais** de contrainte d'activation (intégration purement opportuniste).

---

## 6. Capabilities (intégration synchrone optionnelle)

```java
public interface CapabilityRegistry {
    <T extends Capability> void provide(Class<T> type, TenantScoped<T> impl);
    <T extends Capability> Optional<T> find(Class<T> type, TenantId tenant);
}
```

- Un module **publie** sa capacité à l'activation : CRM → `provide(ClientLookupPort.class, ...)`.
- Un consommateur **résout sans connaître le fournisseur** :
  ```java
  String client = capabilities.find(ClientLookupPort.class, tenant)
        .map(c -> c.resolveName(clientId))
        .orElse(freeTextClientName);   // mode dégradé si CRM absent
  ```
- La résolution est **tenant-aware** : `find` ne retourne la capacité que si le module fournisseur est **actif pour ce tenant**.
- Les interfaces `*Port` (capabilities) vivent dans `{module}/api/` du **fournisseur** ; le consommateur dépend de cette `api` **uniquement** (autorisé par ArchUnit), jamais de l'`internal`.

---

## 7. Événements inter-modules — **Spring Modulith Event Publication Registry** (décision validée)

> **⚠️ Cette section remplace l'« Outbox maison » (`EventBus`/`OutboxEntry`/`OutboxRelay`/table `event_outbox`) initialement spécifiée.** On adopte **Spring Modulith 1.x** (compatible Spring Boot 3.5 / Spring 6), qui fournit l'outbox transactionnel et la livraison fiable nativement. Implémenté en Story 12.3.

### 7.1 Publication & consommation

```java
// Producteur (ex. POS) — publication standard Spring
applicationEventPublisher.publishEvent(new SaleCompletedEvent(eventId, tenantId, actorId, occurredAt, ...));

// Consommateur (ex. invoicing) — @ApplicationModuleListener
//   = @Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener
@ApplicationModuleListener
void on(SaleCompletedEvent event) { /* idempotent */ }

public interface DomainEvent {   // enveloppe standard, contrats dans core/event/catalog
    UUID eventId(); TenantId tenantId(); ActorId actorId(); Instant occurredAt(); String type();
}
```

### 7.2 Registre d'événements (outbox transactionnel, fourni par Modulith)

- Dépendance : **`spring-modulith-starter-jdbc`**. Table **`event_publication`** (statuts `PUBLISHED→PROCESSING→COMPLETED/FAILED`), schéma géré par **Flyway** (`spring.modulith.events.jdbc.schema-initialization.enabled=false` + migration dédiée, cohérent Epic 10).
- **Atomicité** : la publication est inscrite dans la même transaction que la mutation (outbox) → pas d'event perdu si commit, pas d'event fantôme si rollback.
- **Fiabilité** : `spring.modulith.events.republish-outstanding-events-on-restart=true` (rejeu au redémarrage) + **staleness monitor** (`spring.modulith.events.staleness.*`) + API de resubmission (`IncompleteEventPublications`/`FailedEventPublications`). Remplace `OutboxRelay`/ShedLock pour les events.
- **Complétion** : `completion-mode=UPDATE` (inspection) ou `ARCHIVE`.

### 7.3 Multi-tenant (schema-per-tenant) — point d'intégration (Story 12.4)

- `event_publication` vit en **`public`** (registre global) ; l'isolation passe par le `tenantId` porté par l'event.
- **Restauration du `TenantContext`** : avant l'invocation d'un `@ApplicationModuleListener` (qui s'exécute en `@Async`, hors du `ThreadLocal` d'origine), un décorateur repositionne `TenantContext.setCurrentTenant(event.tenantId())` puis le nettoie en `finally`. **Handlers idempotents obligatoires** (rejeu possible) — clé `(eventId, listenerId)`.

### 7.4 Catalogue d'events partagé

Les contrats d'events **inter-modules** vivent dans `core/event/catalog/` (records légers) pour qu'un consommateur (`invoicing`) n'importe pas l'`api` du producteur (`pos`). Ex. `SaleCompletedEvent` dans le catalogue ; POS le publie, Facturation s'y abonne — **zéro dépendance entre les deux**.

### 7.5 Gating par module

Un `@ApplicationModuleListener` ne doit agir que si **son module est actif pour le tenant** : garde en début de handler via `ModuleRegistry.isActive(tenantId, moduleId)` (ou `EntitlementService`). Sinon, no-op.

### 7.6 Broker-ready (différé)

Externalisation future via `@Externalized` + `spring-modulith-events-kafka`/`-amqp` **sans changer les producteurs** — activé seulement si le scaling l'exige.

---

## 8. Extension Points (slots UI/workflow)

```java
public interface ExtensionRegistry {
    void declare(String pointId);                          // POS déclare "pos.checkout.post-sale-actions"
    void contribute(String pointId, Extension contribution); // Facturation y branche "Facturer"
    List<Extension> contributions(String pointId, TenantId tenant); // actifs uniquement
}
```

- Backend : surtout pour des actions workflow.
- Frontend (Flutter) : miroir — un widget `ExtensionSlot(pointId)` rend les contributions des modules actifs (cf. §11).

---

## 9. Entitlements & gating

### 9.1 `EntitlementService` (source de vérité = DB)

```java
public interface EntitlementService {
    // TRIAL/ACTIVE, OU EXPIRED dont grace_until > now (lecture seule). Vérifie expires_at < now EN TEMPS RÉEL.
    boolean isEnabled(TenantId tenant, String moduleId);
    boolean isReadOnly(TenantId tenant, String moduleId);   // EXPIRED en grâce → écritures bloquées, lecture OK
    void assertWritable(TenantId tenant, String moduleId);   // sinon DomainException(MODULE_NOT_ENTITLED / MODULE_READ_ONLY)
    Set<String> enabledModules(TenantId tenant);
}
```

> **Enforcement TEMPS RÉEL (décision validée)** : `isEnabled`/`isReadOnly` recalculent l'état à partir de `expires_at`/`grace_until` à **chaque appel** — ils ne dépendent **pas** du cron nocturne. Le cron (avec `@SchedulerLock`) ne fait que matérialiser le statut + déclencher les notifications. Plus de fenêtre « premium gratuit jusqu'à 02:00 ».
> **Garde lecture seule** : pendant la grâce (EXPIRED, `grace_until > now`), les **lectures** du module passent, les **écritures** sont refusées (`MODULE_READ_ONLY`). Après `grace_until` → DISABLED (tout gâté, données conservées à vie).

### 9.2 Gating déclaratif (aspects)

```java
@RequiresEntitlement("invoicing")          // sur use case / controller
@RequiresPermission({Permission.INVOICE_CREATE})
public InvoiceResponse createInvoice(CreateInvoiceCommand cmd, ActorId actor) { ... }
```

- `@RequiresEntitlement` → aspect qui appelle `EntitlementService.assertEnabled` (HTTP 402/403 + `domainCode=MODULE_NOT_ENTITLED`).
- `@RequiresPermission` → généralise le RBAC existant (`@EnableMethodSecurity`).

### 9.3 Miroir JWT (gating offline & UX)

- Au `select-tenant`, embarquer un claim `modules: ["catalog","pos","invoicing"]` (modules actifs).
- **Backend = source de vérité** (revalide en DB) ; **JWT = fast-path + offline** (Flutter masque les modules non listés).
- Sur changement d'entitlement (activation/désactivation) → le prochain refresh token recharge le claim ; offline, l'app respecte le dernier état signé (cohérent avec la gate 7 jours existante).

---

## 10. Cycle de vie — séquence d'activation (API super-admin)

```
POST /api/v1/admin/tenants/{tenantId}/modules/{moduleId}/activate   (SUPER_ADMIN)
  1. EntitlementService : créer/maj tenant_modules (TRIAL ou ACTIVE selon paiement)
  2. DependencyResolver.planActivation → activer requires manquants (cascade, vérif entitlement)
  3. Pour chaque module du plan, par tenant :
       ModuleProvisioner.migrate(tenantSchema, moduleId)   // Flyway tables du module
       module.onActivate(ctx)                              // seeds
       module.registerCapabilities / EventHandlers / Extensions
  4. Invalider le cache activeModules(tenant)
  5. Audit : MODULE_ACTIVATED
Réponse : { activated:[...], status, provisionedVersion }
```

Désactivation symétrique : `…/deactivate` → `checkDeactivation` (bloque si dépendants) → `onDeactivate` (retire routes/perms/handlers, éteint bridges) → `status=DISABLED`, `disabled_at=now` → audit. **Tables et données conservées à VIE** (réapparaissent à la réactivation). **Aucune purge** sauf suppression de compte RGPD (FR91, `DROP SCHEMA`).

Endpoints complémentaires :
- `GET /api/v1/tenants/me/modules` (owner) — modules actifs + statut/expiration (alimente l'UI).
- `GET /api/v1/admin/modules` — catalogue.
- `POST /api/v1/admin/tenants/{id}/bundles/{bundleId}` — applique un bundle = activation en lot.

---

## 11. Miroir Frontend (Flutter) — résumé

```
core/module_registry/
  module_registry.dart        // lit le claim JWT 'modules' + GET /tenants/me/modules
  entitlements_provider.dart  // Riverpod : Set<String> activeModules
  module_route.dart           // chaque module enregistre ses routes
  extension_slot.dart         // widget rendant les contributions actives
```

- **Routing dynamique** : `go_router` ne monte que les routes des modules actifs.
- **Slots UI** : `ExtensionSlot('pos.checkout.post-sale-actions')` rend « Facturer » seulement si `invoicing` actif.
- **Lazy loading** : `deferred as` pour modules lourds.
- (Packagisation Melos = Phase 5 ; en Phase 3-4 on peut commencer avec des dossiers `features/` gardés par entitlement avant l'extraction en packages.)

---

## 12. Exemple de bout en bout — Facturation (validation de la vision)

| Modules actifs | Comportement (garanti par le socle) |
|---|---|
| `invoicing` seul | CRUD factures manuelles, client en texte libre, PDF + WhatsApp. `find(ClientLookupPort)` → empty → mode dégradé. Aucune route/slot POS. |
| `+ crm` | `find(ClientLookupPort)` → présent → sélecteur de client CRM. |
| `+ pos` | le registre Spring Modulith livre `sale.completed` au `@ApplicationModuleListener` de `invoicing` (actif) → facture auto. Slot `pos.checkout.post-sale-actions` rempli → bouton « Facturer ». |
| `pos` désactivé | Resolver vérifie : `invoicing` n'a pas `requires:pos` → désactivation OK ; le handler `sale.completed` n'est plus appelé ; facturation manuelle continue ; données conservées. |

→ **Test d'acceptation Phase 4** = ces 4 lignes automatisées.

---

## 13. Garde-fous du couplage — Spring Modulith `verify()` **+** ArchUnit

**Spring Modulith `ApplicationModules.of(KeevoApplication.class).verify()`** couvre nativement (règles 1-3) :
1. Aucune classe `*.<module>.internal..*` importée hors de son module.
2. Un module ne dépend que de ses `allowedDependencies` (core, `<autre>.api`, `core.event.catalog`).
3. Aucun import direct entre deux modules hors de leurs `api`.

**ArchUnit complète** ce que Modulith ne couvre pas (règles spécifiques Keevo) :
4. `@Entity`/`@Repository` uniquement dans `adapter/out/persistence`.
5. Le graphe `requires` (lu des manifests) est **acyclique** (complété quand les manifests existent — Epic 13).
6. Aucun type HTTP/Spring-web dans `port/in` ni `application/service`.
7. Tout `JdbcTemplate` interpolant un schéma le valide par `^kv_[a-z0-9]+$`.

Les deux suites tournent en CI et font **échouer le build** dès la fin de Phase 2 (Story 12.2e).

---

## 14. Stratégie de test du socle

| Composant | Test |
|---|---|
| `DependencyResolver` | unit : cascade, cycle, blocage désactivation |
| `ModuleProvisioner` | intégration Testcontainers : Flyway crée bien les tables dans `kv_xxx`, idempotent |
| `EntitlementService` + aspects | slice : 402/403 si non entitled, OK si TRIAL/ACTIVE |
| Events (registre Spring Modulith) | `@ApplicationModuleTest` + `AssertablePublishedEvents` : atomicité (rollback → aucun event), rejeu, gating par module actif, restauration TenantContext |
| `CapabilityRegistry` | unit : `Optional.empty` si fournisseur inactif, résolution tenant-aware |
| Lifecycle activate/deactivate | E2E : activer invoicing → tables présentes → désactiver → routes off, données conservées |
| ArchUnit | CI bloquant |

---

## 15. Plan d'implémentation du socle (Phase 3, ~4-6 sem)

1. **S1** — Tables `module_definitions`/`tenant_modules` (Flyway public) + `ModuleManifest` + scan/seed au boot + `ModuleRegistry`.
2. **S2** — `DependencyResolver` (+ tests cycles/cascade) + `ModuleProvisioner` (Flyway par module, Testcontainers).
3. **S3** — `EntitlementService` + `@RequiresEntitlement`/`@RequiresPermission` + claim JWT `modules`.
4. **S4** — Spring Modulith : `spring-modulith-starter-jdbc` (registre `event_publication` via Flyway) + `@ApplicationModuleListener` + restauration `TenantContext` (décorateur) + catalogue d'events partagé `core/event/catalog`.
5. **S5** — `CapabilityRegistry` + `ExtensionRegistry` + `ModuleLifecycleService` + `ModuleAdminController` (API activate/deactivate/bundle).
6. **S6** — Convertir **2 modules existants** (proposé : `crm` qui *provides* `ClientLookupPort`, et `reporting` consommateur d'events) au contrat `KeevoModule` → valide le socle avant le pilote Facturation (Phase 4).

---

## 16. Décisions validées (15 juin 2026)

1. **Trial hybride** : à l'inscription, **tous les modules payants en trial 6 mois** ; tout module réactivé ensuite a un **trial 14 j**.
2. **Activation** : **super-admin only** en V1 (cohérent avec l'activation Mobile Money manuelle actuelle). Self-service owner = post-V1.
3. **Placeholders** : **supprimer `payment`** du découpage (le mode de paiement reste porté par l'agrégat `Sale`) ; **garder `warehouse` comme type de `store`** (pas de module dédié).
4. **Events inter-modules** : **catalogue partagé `core/event/catalog`** (contrats légers) — le consommateur ne dépend jamais de l'`api` du producteur.

---

*Fin de la spécification — `spec-socle-modulaire.md`*
