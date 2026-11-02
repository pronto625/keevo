---
title: '17-7 Owner notification when an employee changes a selling price (catalogue edit or price applied at the till) — alerts module'
type: 'feature'
created: '2026-11-02'
status: 'done'
baseline_revision: '060f1c2e95a1939dbf0d3f11c7c6f42ecef36d42'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-17-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** An employee can change a selling price two ways — edit the product's catalogue price (`PATCH /api/v1/products/{id}` or sync `UPDATE_PRODUCT`; only DRAFT products are owner-only) or sell an item at a price different from the catalogue (`SalePriceOverriddenEvent`, REST and sync `CREATE_SALE`). Today both are only audited; the owner is never told (Story 17.7, FR "price change notification", Premium per §6.1 → paid module `alerts`).

**Approach:** One owner-notification listener in `messaging` for both events, `@ModuleScoped("alerts")` (Free → nothing, Premium/trial/à-la-carte → notified), following the `StockModificationNotificationListener` pattern: only EMPLOYEE actors, push to owners + WhatsApp to the primary owner, batched per (tenant, employee) window so a sale with several items or an offline sync burst gives one recap. The catalogue path needs a new event carrying old/new price and the actor's role, published by `UpdateProductUseCase` when the price actually changes.

## Boundaries & Constraints

**Always:**
- Notify only when the actor is an EMPLOYEE of the tenant; the owner's own changes never notify. Catalogue path: role from the use case (`dto.actorRole()`). Till path: the event carries a user id (`actorId` = JWT principal = platform user id) → resolve with `EmployeeQueryPort.findByUserId(tenantSchema, actorId)`; present → employee (name = first + last name), absent → owner/unknown → skip.
- Catalogue path: notify only when the new price differs from the previous one (`dto.price() != null && != existing price`); other field edits never notify. Variants are out of scope (product price only).
- Till path: one event per overridden item already exists (`RecordSaleService` ~line 124, catalogue price ≠ applied price); a sale-level discount is not a price change.
- Gating: `@ModuleScoped("alerts")` on each handler method (same as the other owner alerts, 13.10a). The listeners are `@ApplicationModuleListener` (async, after commit): a rolled-back sale/update never notifies.
- Batching: reuse the existing window setting `keevo.notification.employee-action-window-min` (default 5); one notification per window: single message for 1 change, recap for N ≥ 2. Same non-durable in-memory window semantics as `StockModificationNotificationListener` (documented, accepted).
- Messages in French, amounts formatted like the other owner notifications (« 1 500 FCFA »), never an id on screen:
  - catalogue: title « 💰 Prix modifié par <Nom> », body « <Nom> a changé le prix de « <Produit> » : 1 500 → 1 200 FCFA. »
  - till: title « 💰 Prix modifié à la caisse », body « <Nom> a vendu « <Produit> » à 1 200 FCFA au lieu de 1 500 FCFA. »
  - recap: title « 💰 Prix modifiés par <Nom> », body « <Nom> a modifié <N> prix (<k> au catalogue, <m> à la caisse). »
- Push payload: types `EMPLOYEE_PRICE_CHANGED` / `EMPLOYEE_PRICE_CHANGED_BATCH`; route `/products/<productId>/edit` for a single change (existing app route), none extra for the recap (use the route the stock recap uses); data map with productId, productName, oldPrice, newPrice, source (`CATALOGUE` | `SALE`), actorId, tenantId.
- Best effort: a push or WhatsApp failure is logged (WARN), never propagated; WhatsApp only if `OwnerRecipientResolver` gives a phone.
- API compatibility: no REST/sync contract change; the new event is internal.

**Never:** no notification for OWNER actors, Free tenants (module DISABLED), DRAFT product promotion, sale-level discounts, stock or name changes; no new table or migration; no change to the audit trail; no Flutter work; no user-configurable toggle (out of scope — `report-config`/preferences untouched).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Employee edits catalogue price, Premium | PATCH price 1500→1200 by EMPLOYEE | after the window: push to owners + WhatsApp « … : 1 500 → 1 200 FCFA. » | — |
| Same via sync | `UPDATE_PRODUCT` with new price by EMPLOYEE | same notification | — |
| Owner edits price | PATCH by OWNER | nothing | — |
| Employee edits name only / same price | PATCH without price change | nothing | — |
| Employee sells at another price, Premium | sale item applied 1200, catalogue 1500 | « … a vendu « Riz » à 1 200 FCFA au lieu de 1 500 FCFA. » | — |
| Owner sells at another price | sale by owner user | nothing | — |
| Several changes in one window | 3 overridden items in one sale + 1 catalogue edit by the same employee | one recap « … a modifié 4 prix (1 au catalogue, 3 à la caisse). » | — |
| Free tenant | `alerts` DISABLED | nothing (listener not invoked) | — |
| No owner phone | resolver empty | push only | DEBUG log |
| Push fails | notifier throws | WhatsApp still sent | WARN |
| Rolled-back sale | transaction rolls back | nothing | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/event/catalog/product/SalePriceOverriddenEvent.java` -- till-path event (productId, saleId, productName, cataloguePrice, appliedPrice, actorId = user id, tenantId, occurredAt); unchanged.
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/RecordSaleService.java:124-138` -- publishes it per overridden item (REST and sync `SaleSyncHandler` both go through `RecordSaleUseCase`).
- `keevo/backend/src/main/java/com/keevo/catalog/product/application/usecase/UpdateProductUseCase.java:120-200` -- price resolution (`dto.price() != null ? … : existing.getPriceValue()`, ~line 162), role in `dto.actorRole()`, publishes `ProductUpdatedEvent` (~line 194); add the new event publication next to it when the price changed.
- `keevo/backend/src/main/java/com/keevo/shared/event/catalog/product/` -- add `ProductPriceChangedEvent` record (productId, productName, oldPrice, newPrice, actorId, actorRole, tenantId, occurredAt) implementing `DomainEvent`, documented like its siblings.
- `keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/StockModificationNotificationListener.java` -- pattern to copy: batching window map + `@Scheduled` sweep, test-only `Clock` constructor, `sendNotification` push + WhatsApp, `@ModuleScoped("alerts")` + `@ApplicationModuleListener`.
- `keevo/backend/src/main/java/com/keevo/identity/api/EmployeeQueryPort.java` -- `findByUserId(tenantSchema, userId)` → `EmployeeView(firstName, lastName, …)`; `TenantContext` must be the tenant when called (see how other async listeners set/restore it, e.g. `EndOfDayReportListener`).
- `keevo/backend/src/main/java/com/keevo/identity/api/OwnerRecipientResolver.java`, `shared/capability/notification/PushNotifier.java`, `WhatsAppSender`, `NotificationPayload` -- sending.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java` -- read-only; audit stays as is.
- Module-scoping tests: `shared/module/AlertsModuleScopedFullContextTest` (asserts annotation placement on the alerts listeners — extend it for the new methods); `architecture` package (Modulith/ArchUnit) must stay green (messaging may depend on `identity.api` and `shared.event`).
- Event registry / serialization: any test that enumerates `DomainEvent` types or Modulith externalized events (grep `SalePriceOverriddenEvent` in `src/test`) must include the new event.

## Tasks & Acceptance

**Execution:**
- `shared/event/catalog/product/ProductPriceChangedEvent.java` -- new record -- catalogue path needs old/new price + role.
- `catalog/product/application/usecase/UpdateProductUseCase.java` -- publish it after save when the price changed (any role; the listener filters) -- one source for both REST and sync.
- `messaging/notification/application/listener/PriceChangeNotificationListener.java` -- new listener: two `@ModuleScoped("alerts") @ApplicationModuleListener` handlers, employee filter, batching window, French messages, push + WhatsApp -- the feature.
- Tests: unit test of the listener with a controllable `Clock` covering every matrix row (single catalogue, single till, recap counts, owner skipped, unknown user skipped, unchanged price not published, push failure, no phone); `UpdateProductUseCaseTest` (event published only on a real price change, carries role); extend `AlertsModuleScopedFullContextTest` (Free → listener not invoked, trial → invoked) for the new handlers.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- 17.7 row of the Epic 17 table → done with evidence.

**Acceptance Criteria:**
- Given a Premium (or trial) tenant, when an employee sells an item at a price different from the catalogue through sync, then within one window the owner receives one push and one WhatsApp message naming the employee, the product and both prices.
- Given a Free tenant, when an employee changes a price either way, then no notification is sent and the audit trail is unchanged.

## Verification

Run from `keevo/backend`, ONE package at a time (check `free -m`, one Maven build at a time): `mvn -q test -Dtest='com/keevo/<pkg>/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` for `messaging`, `catalog`, `commerce`, `sync`, `shared`, `architecture` — all rc=0, 0 failures.

## Spec Change Log

## Review Triage Log

### 2026-11-02 — Review pass
- verdicts: 31 findings — high 0, medium 2, low 18, false 10, maybe-false 1
- findings:
  - (intent) `[low]` `[reject]` tests stay at unit/event-injection surface, not REST/sync → push — the producers are unchanged and already tested (`ProductSyncHandlerTest` asserts the EMPLOYEE role, `ProductCommandAdapterTest`); the full-context test proves gating on the real context; an end-to-end push assertion would need a fake push sender in the full context for no new branch.
  - (intent) `[false]` `[reject]` sync role pass-through unchecked — checked by the verification-gap layer: `ProductSyncHandlerTest:395,452` assert "EMPLOYEE".
  - (intent) `[false]` `[reject]` sale `actorId` may not be the platform user id — `CurrentActor.actorId()` returns the JWT principal (user id) and `SaleSyncHandler.apply(operation, actorId, …)` receives the same; `findByUserId` is the right lookup.
  - (intent) `[low]` `[reject]` rolled-back sale untested — `@ApplicationModuleListener` is after-commit by construction (Spring Modulith), same as every other module listener; no branch of this story to test.
  - (intent) `[low]` `[reject]` recap payload without product fields — a recap spans several products; spec lists the data map for the single notification.
  - (intent) `[low]` `[patch]` catalogue path notifies « Un employé » when no employee record, till path skips — inherent: the catalogue event carries the role, the till event does not; behaviour pinned by a new test.
  - (intent) `[low]` `[reject]` inactive employee still treated as employee — a deactivated employee's queued offline sales can sync after deactivation; they are still an employee's price changes and should reach the owner.
  - (intent) `[low]` `[reject]` « 0 au catalogue » kept in the recap — literal spec template, readable French.
  - (blind) `[medium]` `[patch]` employee lookup failure swallowed → publication completed, never retried — lookup failures now propagate (publication stays incomplete, resubmitted); send failures stay best-effort.
  - (blind) `[low]` `[patch]` stack traces dropped in WARN logs — exception passed to the logger.
  - (blind) `[low]` `[patch]` till notification drops `saleId` — `saleId` added to the single till payload data.
  - (blind) `[low]` `[reject]` recap content/link not price-specific — spec decision (recap text and stock-recap route); listing products would make WhatsApp recaps long for offline bursts.
  - (blind) `[false]` `[reject]` employee edits a DRAFT product price → notification — `UpdateProductUseCase` refuses any non-owner edit of a DRAFT (`FORBIDDEN`, line ~131) before the event.
  - (blind) `[low]` `[patch]` two paths decide "is an employee" differently / inactive employee — same root as the intent rows (pinned by test; inactive kept on purpose).
  - (blind) `[low]` `[reject]` null `actorId` on the catalogue path merges windows — `actorId` comes from `CurrentActor.actorId()` (throws when absent) or the sync actor; never null on the producer paths.
  - (blind) `[low]` `[reject]` `Map.of` NPE on null tenantId — the producer reads the tenant from `TenantContext` (validated schema); a null tenant cannot reach the listener.
  - (blind) `[low]` `[reject]` no flush on shutdown — accepted non-durable window semantics, identical to the stock-modification alerts; a shutdown flush would send partial windows mid-deploy.
  - (blind) `[low]` `[patch]` two use-case tests without `TenantContext` mock — mocked and `tenantId` asserted.
  - (blind) `[false]` `[reject]` price change in the same request as a `trackStock` change uses a re-fetched old price — `trackStock` never changes the price; the old price is the stored one either way.
  - (blind) `[false]` `[reject]` sync `ProductCommandAdapter.update` role pass-through untested — covered by `ProductCommandAdapterTest` / `ProductSyncHandlerTest` (verification-gap layer).
  - (blind) `[low]` `[reject]` no listener tests for two tenants same user — the window key includes the tenant (unit-tested keying per employee); same construct as the stock listener.
  - (blind) `[false]` `[reject]` mobile section / `m17-7` key missing — added at finalization (planning files are outside the reviewed diff).
  - (gap) `[low]` `[patch]` catalogue unknown-employee fallback not pinned — test added.
  - (gap) `[maybe-false]` `[reject]` override on a PENDING_VALIDATION sale never notifies — pre-existing (`RecordSaleService` publishes overrides only for non-pending sales); a pending sale is validated by the owner, who sees its prices at validation; if real it would be low.
  - (gap) `[false]` `[reject]` `UpdateProductUseCaseTest` gap — listed by the layer as checked and fine.
  - (gap) `[false]` `[reject]` role pass-through gap — listed as checked and fine.
  - (gap) `[false]` `[reject]` gating gap — listed as checked and fine (`never()` on the Free path).
  - (edge) `[medium]` `[patch]` lookup failure swallowed — same as the blind row.
  - (edge) `[low]` `[reject]` tenant drops to Free between accumulate and sweep → one last notification — window ≤ 5 min, the change happened while the module was active; same as the stock alerts.
  - (edge) `[low]` `[reject]` same product 1500→1200→1500 in one window counted twice — each edit is a real employee action the owner may want to know about.
  - (edge) `[false]` `[reject]` recap mixes tenants — key is tenant + actor.

## Design Notes

A dedicated `ProductPriceChangedEvent` rather than parsing `ProductUpdatedEvent`'s JSON before/after: the audit JSON is best-effort (`"{}"` on error) and carries no role. Batching per (tenant, employee) mirrors the stock-modification alerts so an offline device syncing many sales does not flood the owner. Both sources share one window so the owner gets one recap per employee burst.

## Auto Run Result

**Summary:** the owner of a tenant with the paid module `alerts` (Premium, trial, à-la-carte) is notified by push (owners) and WhatsApp (primary owner) when an employee changes a selling price: in the catalogue (REST `PATCH /products/{id}` or sync `UPDATE_PRODUCT`, new `ProductPriceChangedEvent` published by `UpdateProductUseCase` on a real price change) or at the till (existing `SalePriceOverriddenEvent`, seller resolved as an employee by user id). Changes are batched per (tenant, employee) window (`keevo.notification.employee-action-window-min`, default 5): one message, or a recap « … a modifié N prix (k au catalogue, m à la caisse) ». Owners' own changes and Free tenants give nothing. Employee lookup failures propagate (publication resubmitted); send failures are best effort.

**Files changed:**
- `shared/event/catalog/product/ProductPriceChangedEvent.java` — new event.
- `catalog/product/application/usecase/UpdateProductUseCase.java` — publishes it when the price changes.
- `messaging/notification/application/listener/PriceChangeNotificationListener.java` — new listener (two `@ModuleScoped("alerts")` handlers, window sweep, French messages, push + WhatsApp, `saleId` in till payload).
- Tests: `PriceChangeNotificationListenerTest` (14), `UpdateProductUseCaseTest` (+3), `AlertsModuleScopedFullContextTest` (Free / trial / annotations), `SchedulerLockCoverageTest`, `RegistryEventSerializationTest` (round-trips for both events).
- `epics-refonte-modulaire.md` — 17.7 row done.

**Review:** 31 findings — 0 high, 2 medium (one root cause: lookup failure swallowed → patched), 18 low (6 patched, 12 rejected with reasons), 10 false, 1 maybe-false (rejected: pending-sale overrides, pre-existing, owner validates those sales). 0 deferred.

**Follow-up review recommended:** false — one medium entry patched, no high.

**Verification performed (2026-11-02):** one package at a time after the patches: messaging 192, catalog 306, commerce 263, sync 635, architecture 32, shared 1151 — all rc=0, 0 failures/errors. Live (local backend, WhatsApp noop, FCM off, window 0, KV-EW02A2 on trial): test employee created; employee PATCH 1500→1200 → push stub `EMPLOYEE_PRICE_CHANGED` « 💰 Prix modifié par Test Prix177 » + WhatsApp stub to the owner « Test Prix177 a changé le prix de « Riz test 177 » : 1 500 → 1 200 FCFA. »; owner PATCH → skipped (log « actor is not EMPLOYEE »); employee REST sale at 1 000 vs 1 300 → « Test Prix177 a vendu « Riz test 177 » à 1 000 FCFA au lieu de 1 300 FCFA. »; 0 ERROR. Test product archived and test employee deactivated afterwards. Redmi (Flutter test client): « En ligne », fresh `last_pull_at`.

**Residual risks:** windows are in memory (a restart before the sweep loses pending notifications — same as the stock-modification alerts); overrides on sales recorded as PENDING_VALIDATION are not notified (pre-existing: the event is only published for completed sales).

## Mobile (React Native — dépôt AI/bref)

> Destinataire : agent IA qui implémente l'app Expo / React Native sur une branche du dépôt `/home/toor/Project/FreeLance/AI/bref` (base `develop`). Conventions API : section « Mobile » de `spec-16-11-sessions-longues-et-sures.md` ; synchro : `spec-16-10-scope-employe-historique-transferts.md` ; erreurs : `spec-16-14-api-prete-client-mobile.md`. Aucun changement de contrat REST ni de synchro : seulement deux nouveaux types de notification push reçus par l'owner.

**Statut** : API livrée (commit `0e578d9d` ; tests `messaging/notification/application/listener/PriceChangeNotificationListenerTest.java`, `AlertsModuleScopedFullContextTest.java`) — mobile à faire.

**Clé de suivi mobile** : `m17-7-notif-changement-prix-employe`

### Ce qui est fait côté API

- Quand un **employé** change le prix d'un produit au catalogue (écran produit ou `UPDATE_PRODUCT` en synchro) ou vend un article à un prix différent du catalogue (vente en ligne ou `CREATE_SALE` en synchro), le serveur prévient l'owner : notification push à tous les owners et message WhatsApp au propriétaire principal.
- Seulement si le commerce a le module « Alertes » (essai, Premium ou à la carte) ; rien pour un compte Free ; rien quand c'est l'owner lui-même qui change le prix.
- Les changements d'un même employé sont regroupés sur 5 minutes : une seule notification, ou un récapitulatif s'il y en a plusieurs (une vente hors ligne synchronisée plus tard déclenche la notification au moment de la synchro).

### À utiliser

| Type (`type` du payload push) | Titre / texte (déjà en français, à afficher tel quel) | `route` | `data` |
|---|---|---|---|
| `EMPLOYEE_PRICE_CHANGED` (catalogue) | « 💰 Prix modifié par <Nom> » / « <Nom> a changé le prix de « <Produit> » : 1 500 → 1 200 FCFA. » | `/products/<productId>/edit` | `productId`, `productName`, `oldPrice`, `newPrice`, `source` = `CATALOGUE`, `actorId`, `tenantId` |
| `EMPLOYEE_PRICE_CHANGED` (caisse) | « 💰 Prix modifié à la caisse » / « <Nom> a vendu « <Produit> » à 1 200 FCFA au lieu de 1 500 FCFA. » | `/products/<productId>/edit` | idem avec `source` = `SALE` et `saleId` |
| `EMPLOYEE_PRICE_CHANGED_BATCH` | « 💰 Prix modifiés par <Nom> » / « <Nom> a modifié N prix (k au catalogue, m à la caisse). » | `/stock/overview` (même route que le récapitulatif de stock) | `count`, `catalogueCount`, `saleCount`, `actorId`, `tenantId` |

Source : `PriceChangeNotificationListener` (`keevo/backend/src/main/java/com/keevo/messaging/notification/application/listener/`). Envoi push : même canal que les autres notifications owner (`EMPLOYEE_STOCK_MODIFIED`, 14.10).

### À implémenter côté mobile

1. Reconnaître les deux types (owner seulement) dans le gestionnaire de notifications push et dans le centre de notifications : afficher titre et texte reçus, icône « prix ».
2. Au toucher : `EMPLOYEE_PRICE_CHANGED` → fiche du produit (`productId`) ; si le produit n'existe pas localement (archivé, pas encore synchronisé) → liste des produits avec « Produit introuvable » ; si `source` = `SALE` et qu'un écran de détail de vente existe, il peut ouvrir la vente (`saleId`) à la place. `EMPLOYEE_PRICE_CHANGED_BATCH` → écran indiqué par `route`.
3. Après réception, rafraîchir le catalogue au prochain pull (le nouveau prix arrive par la synchro habituelle ; ne pas modifier le prix localement à partir de la notification).
4. Type inconnu ou champ manquant → affichage générique du titre/texte, aucun plantage ; jamais d'identifiant à l'écran.
5. Rien à envoyer : l'app ne déclenche rien de nouveau, le serveur détecte les changements.

### Implémentation de référence

Gestion des notifications owner du client Flutter (types `EMPLOYEE_STOCK_MODIFIED*`) : `keevo/app/lib/features/notifications/` — même schéma à étendre (le client Flutter ne connaît pas encore `EMPLOYEE_PRICE_CHANGED*`).

### Critères d'acceptation mobile / scénarios de test

Préparation : backend local (`--keevo.whatsapp.provider=noop --keevo.fcm.enabled=false --keevo.notification.employee-action-window-min=0`, arrêt `fuser -k 4500/tcp`) ; les envois apparaissent dans le journal (`[NOTIFICATION STUB]`, `[WhatsApp Stub]`). Pour un vrai push, lancer avec FCM actif sur un environnement de test.

```bash
# owner AT, employé EAT (créé par POST /api/v1/employees, mot de passe changé), produit PID à 1500
curl -s -X PATCH $BASE/api/v1/products/$PID -H "Authorization: Bearer $EAT" -H 'Content-Type: application/json' -d '{"price":1200}'
# ≤ 30 s : « <Nom> a changé le prix de « … » : 1 500 → 1 200 FCFA. »
```

1. Given un owner en essai avec l'app ouverte, When un employé change le prix d'un produit, Then l'owner reçoit la notification « Prix modifié par <Nom> » et la toucher ouvre la fiche du produit.
2. Given un employé qui vend un article à un autre prix, When la vente est synchronisée, Then l'owner reçoit « … a vendu « … » à … FCFA au lieu de … FCFA. »
3. Given un employé qui change 3 prix en moins de 5 minutes, Then l'owner reçoit un seul récapitulatif « … a modifié 3 prix … ».
4. Given un compte Free, When un employé change un prix, Then aucune notification.
5. Given l'owner change lui-même un prix, Then aucune notification.
