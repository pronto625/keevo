# Spec — Notification automatique du OWNER sur actions employé (produit + stock manuel)

> **Statut :** SPÉCIFICATION AVANT-CODE (à valider avant implémentation). Aucun code modifié.
> **Date :** 2026-07-20 · **Auteur :** audit + décisions produit (session 2026-07-20)
> **Alignement :**BMAD — étend Story 2.1, Story 2.3, Story 2.4 AC9, et le `requirements-inventory.md` (FR21 + nouvelle FR).

---

## 1. Contexte & motivation

Le rôle **EMPLOYEE** a déjà (dans le code) le droit de créer un produit et de modifier le stock manuellement (`@PreAuthorize hasAnyRole('OWNER','EMPLOYEE')` sur `ProductController` et `StockController`). Mais :

1. **FR21** documente la création/édition de produit comme un droit du **propriétaire** uniquement → écart doc/code.
2. Aucune notification au OWNER n'est émise quand un **employé** crée un produit **ACTIVE** depuis le form catalogue (la Story 2.4 AC9 ne couvre que le DRAFT POS).
3. Aucune notification au OWNER n'est émise quand un **employé** modifie le stock manuellement (entrée/ajustement) — la Story 2.3 AC5/AC6 émet seulement `StockAdjustedEvent` (audit) + `StockThresholdBreachedEvent` (alerte stock bas si sous seuil).

**Objectif :** le OWNER est **directement notifié (push FCM + WhatsApp)** quand un employé (i) crée un produit, (ii) modifie manuellement le stock (entrée ou ajustement). Mise en place d'un **batching consolidé** pour éviter le spam.

---

## 2. Décisions produit (validées 2026-07-20)

| # | Décision | Choix retenu |
|---|---|---|
| D1 | Statut du produit créé par employé (form catalogue) | **ACTIVE directement** → la notif est une **information** au owner, **pas** une demande de validation |
| D2 | Périmètre « modif stock manuelle » | **STOCK_ENTRY + ADJUSTMENT** — exclut les ventes (décrément auto POS) et les transferts inter-boutiques |
| D3 | Canaux de notification | **Push (FCM) + WhatsApp** au owner (cohérent avec FR61 alertes stock) |
| D4 | Anti-spam | **Batching consolidé** — 1 notif récap agrège toutes les actions d'un même employé d'un même type sur une fenêtre |

---

## 3. Règle A — Employé crée un produit → notif owner

### A.1 Périmètre
- Déclencheur : **EMPLOYEE** crée un produit via `POST /api/v1/products` (form catalogue) → produit `status = ACTIVE` (D1).
- Également valable pour la création **DRAFT on-the-fly** (POS, déjà couverte par Story 2.4 AC9 — la présente spec **unifie** les deux chemins sous le même listener).
- **Skip** si `actorRole == OWNER` (le owner ne se notifie pas lui-même — règle déjà établie en AC9).

### A.2 Event émis
- Le use case `CreateProductUseCase` publie **déjà** `ProductCreatedEvent` (ACTIVE) ; `CreateDraftProductUseCase` publie `ProductCreatedProgressivelyEvent` (DRAFT).
- ⚠ **Alignement requis** : `ProductCreatedEvent` doit porter `actorRole` + `actorName` (aujourd'hui il manque ces champs — `ProductCreatedProgressivelyEvent` les a déjà). Le listener en a besoin pour (a) skip OWNER et (b) personnaliser le body (« {actorName} a créé… »).
- Listener : **`ProductCreationNotificationListener`** (renommer/étendre `DraftProductNotificationListener` actuel) — catch **les deux** events, dispatch unifié.

### A.3 Payload de notification (single — cas non-batché, 1 produit)
- `type: "EMPLOYEE_PRODUCT_CREATED"`
- `title: "🛍️ Produit créé par {actorName}"`
- `body: "{actorName} a ajouté « {productName} » au catalogue ({storeName})."`
- `deepLink: "/products/{productId}/edit"`
- `metadata: Map.of("productId","{productId}","productName","{productName}","actorId","{actorId}","storeName","{storeName}","tenantId","{tenantId}")`
- `channel: BOTH` (PUSH + WHATSAPP — D3)

### A.4 Batching (D4) — mécanisme unifié §5
- Si l'employé crée N produits dans la fenêtre de batching → **1 seule notif récap** :
  - `type: "EMPLOYEE_PRODUCT_CREATED_BATCH"`
  - `body: "{actorName} a créé {N} produits dans le catalogue ({storeName})."`
  - `metadata: Map.of("count","{N}","actorId","{actorId}","storeName","{storeName}","tenantId","{tenantId}")`
  - `deepLink: "/products"` (liste filtrable)
- **Exception déjà spécifiée** : l'import CSV reste régi par `CsvImportCompletedEvent` + son batching dédié (AC9) — ne **pas** doubler la notif.

---

## 4. Règle B — Employé modifie le stock manuellement → notif owner

### B.1 Périmètre (D2)
- Déclencheurs : **STOCK_ENTRY** (`POST /products/{id}/stock/entry`) et **ADJUSTMENT** (`POST /products/{id}/stock/adjust`) **par un EMPLOYEE**.
- **Exclus** : décrément auto d'une vente POS, transferts inter-boutiques, ajustements d'inventaire validés (FR48 — couvert par un autre flux).
- **Skip** si `actorRole == OWNER`.

### B.2 Event émis
- Les deux opérations publient **déjà** `StockAdjustedEvent` (cf. Story 2.3 AC5/AC6). Le champ `movementType` (`STOCK_ENTRY` | `ADJUSTMENT`) distingue les deux.
- ⚠ **Alignement requis** : `StockAdjustedEvent` doit porter `actorRole` + `actorName` (+ `storeName`) — aujourd'hui absent (champs : productId, variantId, storeId, movementType, quantityBefore, quantityChange, quantityAfter, actorId, notes, tenantId, occurredAt). Le listener en a besoin pour skip OWNER + personnalisation + résolution du nom de boutique.
- Listener : **`StockModificationNotificationListener`** (nouveau, dans `messaging/notification/application/listener/`).

### B.3 Payload de notification (single — cas non-batché, 1 opération)
- `type: "EMPLOYEE_STOCK_MODIFIED"`
- `title: "📦 Stock modifié par {actorName}"`
- `body:` selon `movementType` :
  - `STOCK_ENTRY` → `"{actorName} a ajouté {quantityChange} unité(s) à « {productName} » ({storeName})."`
  - `ADJUSTMENT` → `"{actorName} a ajusté « {productName} » ({storeName}) : {quantityBefore} → {quantityAfter}."`
- `deepLink: "/products/{productId}"`
- `metadata: Map.of("productId","{productId}","productName","{productName}","actorId","{actorId}","storeName","{storeName}","movementType","{movementType}","tenantId","{tenantId}")`
- `channel: BOTH` (PUSH + WHATSAPP — D3)

### B.4 Batching (D4)
- Si l'employé enchaîne plusieurs entry/adjust dans la fenêtre → **1 notif récap** :
  - `type: "EMPLOYEE_STOCK_MODIFIED_BATCH"`
  - `body: "{actorName} a effectué {N} modification(s) de stock ({storeName})."`
  - `metadata: Map.of("count","{N}","actorId","{actorId}","storeName","{storeName}","tenantId","{tenantId}")`
  - `deepLink: "/stock/history"` (ou l'écran d'historique mouvements)

---

## 5. Batching consolidé (mécanisme commun à A et B)

### 5.1 Principe
- Pour chaque **(tenantId, actorId, type d'action)**, une **fenêtre glissante** de **`EMPLOYEE_ACTION_NOTIFY_WINDOW_MIN` minutes** (proposition : 5 min, configurable via `keevo.notification.employee-action-window-min`).
- Pendant la fenêtre, les events sont **agrégeés** (compteur + liste de productId) ; le premier event déclenche un timer à l'expiration de la fenêtre.
- À l'expiration : émission d'**1 notif récap** (payload `*_BATCH` §A.4 / §B.4) au lieu de N notifs.
- Si **1 seul event** tombe dans la fenêtre (cas fréquent), la notif récap = 1 ligne au singulier (le body reste naturel).

### 5.2 Implémentation de référence
- Réutiliser le pattern existant de `StockAlertNotificationListener` (`ConcurrentHashMap<BatchWindow>` par JVM). ⚠ Note : **non multi-instance** (cf. audit) — acceptable MVP mono-instance, à déporter sur Redis en Phase 2.
- La fenêtre doit être **par employé** (pas globale au tenant) pour ne pas agréger les actions de 2 employés différents.

### 5.3 Comportement best-effort
- Si push/WhatsApp échoue → **log + swallow**, **pas de rollback** de l'action métier (cohérent AC9). La file `sync_queue` / l'écriture stock/produit ne doit jamais échouer à cause d'une notif.

### 5.4 Canal WhatsApp
- `NotificationPort.notifyOwners(tenantId, payload)` doit, pour `channel=BOTH`, appeler en plus `WhatsAppPort.sendReport(...)` (ou un `sendNotification(...)`) au numéro du owner. Résolution du numéro owner = owner du tenant (via `TenantRepository` / `users` du schéma tenant). Best-effort.
- FCM : `FcmNotificationAdapter` (Epic 8.1) — typage via `type`.

---

## 6. Tests TDD requis (ARCH11 / ARCH14 — RED d'abord)

### 6.1 `ProductCreationNotificationListenerTest`
- `shouldNotifyOwnerWhenEmployeeCreatesActiveProduct()`
- `shouldNotifyOwnerWhenEmployeeCreatesDraftProduct()` (regression AC9)
- `shouldSkipNotificationWhenOwnerCreatesProduct()`
- `shouldSendBatchedRecapWhenEmployeeCreatesMultipleProductsInWindow()`
- `shouldSendSingleNotificationWhenEmployeeCreatesOneProduct()`
- `shouldNotRollbackProductCreationWhenNotificationFails()`
- `shouldNotDoubleNotifyOnCsvImport()` (le batch CSV reste sur `CsvImportCompletedEvent`)

### 6.2 `StockModificationNotificationListenerTest`
- `shouldNotifyOwnerWhenEmployeeEntersStock()` (STOCK_ENTRY)
- `shouldNotifyOwnerWhenEmployeeAdjustsStock()` (ADJUSTMENT)
- `shouldSkipNotificationWhenOwnerModifiesStock()`
- `shouldSkipSaleDecrementEvents()` (ventes POS — pas notifiées)
- `shouldSendBatchedRecapWhenEmployeeModifiesMultipleStocksInWindow()`
- `shouldNotRollbackStockModificationWhenNotificationFails()`

---

## 7. Mapping des artefacts BMAD à mettre à jour (doc-only)

### 7.1 `requirements-inventory.md`
- **FR21 (éditer)** : « Un **propriétaire ou un employé** peut créer, modifier et archiver des produits (nom, description, photo, référence). »
- **Nouvelle FR (proposition FR65b — à sloter dans la famille FR61–64 notifications)** :
  > **FR65b** : Le système notifie immédiatement le propriétaire (push FCM + WhatsApp) lorsqu'un **employé** crée un produit (ACTIVE) ou modifie manuellement le stock (entrée ou ajustement). Un **batching consolidé** agrège les actions d'un même employé sur une fenêtre configurable pour éviter le spam. L'owner ne se notifie pas lui-même.

### 7.2 Story 2.1 (`2-1-crud-produits-...`)
- **Nouvel AC — AC8 : Owner alert on employee product creation (ACTIVE)**
  - Given un EMPLOYEE crée un produit via `POST /api/v1/products` (status ACTIVE)
  - When `CreateProductUseCase` complète
  - Then le backend émet `ProductCreatedEvent` (portant `actorRole`, `actorName`, `storeName`)
  - And `ProductCreationNotificationListener` catch l'event ; si `actorRole==EMPLOYEE` → `NotificationPort.notifyOwners(...)` (payload §A.3, channel BOTH)
  - And si `actorRole==OWNER` → **pas de notif**
  - And batching §5 sur fenêtre (si plusieurs créations rapprochées → récap §A.4)
  - And best-effort (pas de rollback)
  - TDD : tests §6.1

### 7.3 Story 2.3 (`2-3-seuils-de-stock-...`)
- **Nouvel AC — AC7 : Owner alert on employee manual stock modification**
  - Given un EMPLOYEE fait un STOCK_ENTRY ou ADJUSTMENT via `POST /products/{id}/stock/{entry|adjust}`
  - When `StockOperationService.recordOperation` complète et publie `StockAdjustedEvent` (portant `actorRole`, `actorName`, `storeName`)
  - Then `StockModificationNotificationListener` catch l'event ; si `actorRole==EMPLOYEE` → `notifyOwners(...)` (payload §B.3, channel BOTH)
  - And si `actorRole==OWNER` → pas de notif
  - And les **vents POS** (SALE) et **transferts** ne déclenchent **pas** ce listener (scope D2)
  - And batching §5 sur fenêtre (si plusieurs modifs rapprochées → récap §B.4)
  - And best-effort
  - TDD : tests §6.2
- **Édition AC5/AC6** : préciser que `StockAdjustedEvent` porte désormais `actorRole`/`actorName`/`storeName`.

### 7.4 Story 2.4 AC9
- **Élargir** le scope de `DraftProductNotificationListener` : il devient `ProductCreationNotificationListener` et gère aussi `ProductCreatedEvent` (ACTIVE) en plus de `ProductCreatedProgressivelyEvent` (DRAFT) + `CsvImportCompletedEvent`. Cross-référence la présente spec.
- Les payloads AC9 (DRAFT) restent inchangés — la présente spec ajoute le cas ACTIVE (§A.3).

---

## 8. Champs d'event à ajouter (alignement code ↔ doc)

| Event | Champs actuels | Champs à ajouter | Justification |
|---|---|---|---|
| `ProductCreatedEvent` | (productId, productName, actorId, tenantId, occurredAt) | `actorRole`, `actorName`, `storeName` | skip OWNER + personnalisation body + résolution boutique |
| `StockAdjustedEvent` | productId, variantId, storeId, movementType, quantityBefore, quantityChange, quantityAfter, actorId, notes, tenantId, occurredAt | `actorRole`, `actorName`, `storeName` | idem |

> Alternative documentée (si on ne veut pas gonfler les events) : un `ActorResolver` port (`resolveActorRole(actorId, tenantId)`) appelé par le listener. **Recommandation : ajouter les champs à l'event** (cohérent avec `ProductCreatedProgressivelyEvent` qui les a déjà, et évite une requête DB supplémentaire par event).

---

## 9. Hors-scope (explicitement exclu)

- Transferts inter-boutiques initiés par employé → **pas notifiés** ici (D2). Si voulu plus tard, spec séparée.
- Ajustements d'inventaire validés (FR48) → flux dédié, hors scope.
- Ventes POS (décrément auto) → jamais notifiés par ces listeners.
- Bell/feedback global (Epic 8.1) → inchangé.
- Révocation de session OWNER (NFR12) → traitée ailleurs (audit B-HIGH-5), non couplée à cette spec.

---

## 10. Checklist de validation (avant de coder)

- [ ] Valider D1–D4 (fait — 2026-07-20)
- [ ] Approuver le texte des FR21 (édité) + nouvelle FR65b
- [ ] Approuver les nouveaux AC8 (Story 2.1) et AC7 (Story 2.3)
- [ ] Approuver l'élargissement du listener (2.4 AC9)
- [ ] Approuver l'ajout des champs `actorRole`/`actorName`/`storeName` aux 2 events
- [ ] Approuver la fenêtre de batching par défaut (5 min) + config key
- [ ] Décider : nouvelle story dédiée (`notif-owner-actions-employe`) vs édition in-place des stories 2.1/2.3

---

## 11. Note d'alignement audit

Cette spec répond partiellement aux findings d'audit :
- **FR36 / B-HIGH-10** : le scope serveur employé reste à corriger séparément (la présente spec ne change PAS le RBAC — l'employé garde son storeId JWT pour les ventes, mais les entry/adjust restent à scopifier en parallèle).
- **Story 2.4 AC9 (catalog agent F3)** : la spec **restaure** le listener per-draft retiré + l'étend à ACTIVE → corrige le gap AC9 documenté mais non implémenté.
- Pas d'impact sur les findings concurrence (B-CRIT-1/2/3) ni sécurité (S1–S7) — traités ailleurs.
