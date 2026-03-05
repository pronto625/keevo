# Epic 5: Moteur de Synchronisation Offline-First

> ⚠️ **DÉCISION ARCHITECTURALE BLOQUANTE — À CONFIRMER AVANT DE CRÉER STORY 5-1**
>
> **La stratégie de schéma PostgreSQL pour la synchronisation est irréversible.**
> Démarrer l'Epic 5 sans avoir tranché ce point entraînerait une migration de données catastrophique sur tous les tenants en production.
>
> **Question :** Est-ce que la séparation complète schema-per-tenant (`kv_xxxxxx` pour TOUTES les tables) est non-négociable ?
>
> - **OUI → Path A** : Custom REST delta-based (push/pull API maison). PowerSync exclu définitivement. Isolation PostgreSQL complète garantie.
> - **NON → Path B** : Hybride — tables syncées dans `public` + `tenant_id`, tables sensibles dans `kv_xxxxxx`. PowerSync envisageable Phase 2.
>
> **Position actuelle de Toor (2026-03-04) :** séparation schéma complète requise → **Path A pressenti**. À confirmer explicitement avant story 5-1.

L'application fonctionne 100% opérationnellement hors-ligne pendant 7 jours, synchronise automatiquement toutes les données au retour de la connectivité en moins de 60 secondes, résout les conflits multi-device intelligemment (delta-based pour le stock, last-write-wins pour le reste), garantit zéro perte de données, et suspend l'accès écriture après 7 jours sans sync pour forcer une reconnexion.

**FRs couverts :** FR69–FR71, FR73–FR74, FR76

---

## Story 5.1: Push Sync — Envoi par Lot des Opérations en File

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

## Story 5.2: Pull Sync — Téléchargement Delta & Fusion Locale

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

## Story 5.3: Résolution de Conflits — Delta Stock & Last-Write-Wins

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

## Story 5.4: Limite 7 Jours Hors-ligne & Gate de Déverrouillage

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

## Story 5.5: Monitoring Sync, Récupération d'Erreurs & Cohérence Multi-Device

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
