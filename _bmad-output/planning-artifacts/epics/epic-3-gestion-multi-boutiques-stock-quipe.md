# Epic 3: Gestion Multi-Boutiques, Stock & Équipe

Simon peut créer et gérer plusieurs boutiques et un warehouse principal, consulter les stocks de toutes ses boutiques en vue centralisée, transférer du stock entre boutiques avec traçabilité complète, inviter ses employés via WhatsApp, gérer leurs rôles et les assigner à leurs boutiques avec des droits limités.

**FRs couverts :** FR30–FR36, FR65–FR66

---

## Story 3.1: Création & Configuration des Boutiques et Warehouse

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

## Story 3.2: Vue Centralisée des Stocks Multi-Boutiques

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

## Story 3.3: Transferts Inter-Boutiques avec Traçabilité Complète

As a proprietor (Simon),
I want to transfer stock between stores and from the warehouse to stores with full traceability,
So that I can rebalance inventory across locations and always know exactly where each unit went.

**Acceptance Criteria:**

**Given** Simon initiates a stock transfer
**When** he taps "Nouveau transfert" in the Stock module
**Then** a transfer form appears with: Source (dropdown of active stores + warehouse), Destination (dropdown — excludes Source), Product (searchable from Catalogue), Variant (if applicable), Quantity (positive integer)

> ⚠️ **Two-step transfer flow (as implemented):** A transfer goes through two distinct steps — Envoi (Step 1) and Réception (Step 2). This reflects physical reality: stock leaves the source before it arrives at the destination. The destination manager must explicitly receive the stock to confirm arrival.

**Step 1 — Envoi :**

**Given** Simon fills in the transfer form and taps "Confirmer"
**When** the transfer is submitted
**Then** the backend validates: source store has sufficient quantity (`currentQty ≥ requestedQty`)
**And** if validation passes, atomically:
  - source stock is **decremented** by the transferred quantity
  - a `TRANSFER_OUT` movement is created on the source store
  - a `stock_transfers` record is created with `status = IN_TRANSIT`
**And** a success SnackBar: "[qty] × [product] → [destination store]"
**And** the transfer appears in the history list of the destination store with badge 🚚 En transit
**And** a "Réceptionner le stock" button is visible on the transfer tile (destination store only)

**Step 2 — Réception (AC7) :**

**Given** the destination store manager sees a transfer tile with status IN_TRANSIT
**When** he taps "Réceptionner le stock"
**Then** the backend validates that the transfer status is still IN_TRANSIT
**And** if valid, atomically:
  - destination stock is **incremented** by the transferred quantity
  - a `TRANSFER_IN` movement is created on the destination store
  - the `stock_transfers` record is updated to `status = COMPLETED`
**And** a success SnackBar: "Stock réceptionné : [qty]× [product]"
**And** all stock/catalog views refresh immediately (cache invalidation cascade)
**If** transfer was already COMPLETED (concurrent session): silent refresh, no error shown

**Given** Simon tries to transfer more units than available
**When** he submits the form with quantity exceeding source stock
**Then** the backend returns HTTP 422 `{ "domainCode": "INSUFFICIENT_STOCK", "details": { "available": 5, "requested": 10 } }`
**And** the Flutter form shows an inline error below the quantity field: "Stock insuffisant — disponible : 5 unités"

**Given** the app is offline when Simon confirms a transfer
**When** the form is submitted while offline
**Then** the transfer is validated locally against the Drift stock data
**And** the operation is queued in `sync_queue` with type `STOCK_TRANSFER`
**And** source stock level is decremented locally immediately (destination credited on Step 2)
**And** a `stock_transfers` local record is created with `status = PENDING_SYNC`
**And** the transfer is visible in the transfer history with badge ⏳ En attente de sync
**And** on reconnection, the server re-validates and either commits or reports a conflict
**Note:** Réception (Step 2) requires an active network connection — it is blocked offline

**Status values as implemented:**

| Status | Badge | When |
|--------|-------|------|
| `IN_TRANSIT` | 🚚 En transit | After Step 1 (backend) |
| `COMPLETED` | ✅ Effectué | After Step 2 (backend) |
| `PENDING_SYNC` | ⏳ En attente | Offline Step 1, awaiting sync |
| `CONFLICT` | ❌ Conflit | Sync conflict (Epic 5) |

**Given** Simon views the transfer history
**When** he navigates to Stock > Historique des transferts
**Then** all transfers are listed in reverse chronological order with: date, source → destination, product + variant, quantity, status badge, and a "Réceptionner" button on IN_TRANSIT tiles
**And** if a store is active (activeStoreId set), only transfers where that store is the **destination** are shown
**And** he can filter by status chip (Tous / En transit / Effectués / En attente / Conflits)
**And** the history is available offline from local Drift data

---

## Story 3.4: Vérification de Disponibilité Cross-Boutique

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

## Story 3.5: Création Employés, Rôles & Assignation Boutique

As a proprietor (Simon),
I want to create employee accounts directly by entering their personal information, so that each employee receives a temporary generated password, can log in immediately, and is forced to set their own password on first login following our existing two-step login flow.

**Acceptance Criteria:**

**AC1 — Owner creates an employee account**

**Given** Simon navigates to Paramètres > Équipe
**When** he taps "Ajouter un employé"
**Then** a form appears with: Prénom (required), Nom (required), Numéro de téléphone (required — same IntlPhoneField as registration), Boutique assignée (required — dropdown of active stores), Rôle (EMPLOYEE — currently the only non-owner role)
**And** on submit, the backend:
  - creates a `public.users` entry with the provided phone number and a system-generated temporary password (bcrypt cost 12)
  - inserts a `public.user_tenant_memberships` row: `(newUserId, tenantId, role='EMPLOYEE', is_active=true)`
  - inserts a `kv_xxx.employees` row with: `id` (UUID), `userId`, `storeId`, `firstName`, `lastName`, `status: ACTIVE`, `passwordChangeRequired: true`, `createdAt`
  - emits an `EmployeeCreatedEvent` in the audit log with `actorId` (Simon), `targetUserId`, `storeId`, `occurredAt`
**And** a success bottom sheet is shown to Simon displaying:
  - Employee name and assigned store
  - The generated temporary password in full (cleartext, one-time display only)
  - A "Copier le mot de passe" button and a warning: "Ce mot de passe ne sera plus affiché. Communiquez-le à l'employé par vos propres moyens."
**And** the temporary password is NEVER stored in plaintext after this display — only the bcrypt hash is persisted

**AC2 — Plan limit enforcement**

**Given** the Free plan limit is 5 employees
**When** Simon attempts to create a 6th employee
**Then** the creation is blocked: HTTP 403 `{ "domainCode": "PLAN_LIMIT_EXCEEDED", "details": { "entity": "employees", "limit": 5, "current": 5 } }`
**And** the Flutter app shows the upgrade bottom sheet

**AC3 — Employee first login: phone + temporary password**

**Given** Loïc has received his phone number and temporary password from Simon (via any out-of-band channel)
**When** he opens Keevo and enters his phone number and the temporary password on the login screen
**Then** the standard two-step login flow executes (Story 1.7):
  - Step 1: `POST /auth/login` verifies credentials → returns `loginToken` + `memberships` array
  - Step 2: Flutter auto-calls `POST /auth/select-tenant` (single membership → no picker shown)
**And** the final `accessToken` JWT contains: `userId`, `tenantId`, `role: EMPLOYEE`, `storeId`, `passwordChangeRequired: true`
**And** the Flutter app detects the `passwordChangeRequired: true` flag in the JWT or login response
**And** navigation is intercepted — Loïc is redirected to the **PasswordChangePage** before any other screen is accessible (no back navigation, no skip)

**AC4 — Forced password change on first login**

**Given** Loïc is on the PasswordChangePage (triggered by `passwordChangeRequired: true`)
**When** he submits a new password
**Then** the new password must meet minimum requirements: ≥ 8 characters, at least one number
**And** `POST /api/v1/auth/change-password` is called with `{ "currentPassword": "<temp>", "newPassword": "<chosen>" }`
**And** on success, the backend:
  - updates the bcrypt hash in `public.users`
  - sets `passwordChangeRequired: false` in `kv_xxx.employees`
  - emits `EmployeePasswordSetEvent` in the audit log with `actorId` (Loïc), `occurredAt`
**And** the backend enforces this server-side: all non-auth API requests return HTTP 403 `{ "domainCode": "PASSWORD_CHANGE_REQUIRED" }` while `passwordChangeRequired: true`
**And** upon successful password change, new tokens are issued (refreshed JWT without the `passwordChangeRequired` flag) and Loïc is redirected to the POS screen (his default landing)

> 🔒 **Security note:** The forced-change flow is a mandatory security control — the temporary credential generated by the owner must never remain the permanent credential. The backend enforces this independently of the Flutter guard.

**AC5 — Employee access restrictions**

**Given** Loïc's account is active and he has completed the password change
**When** he accesses any feature
**Then** he is limited to operations on his assigned store only — his JWT's `storeId` claim restricts all backend queries
**And** stock views, POS, and inventory show ONLY data for his assigned store
**And** he cannot view reports, manage the product catalogue, create stores, or create other employees
**And** the navigation menu hides all OWNER-only sections automatically based on his `role: EMPLOYEE` claim

**AC6 — Reassign employee to another store**

**Given** Simon wants to reassign an employee to a different store
**When** he taps the employee's name and selects "Modifier l'assignation"
**Then** a dropdown shows all active stores and he can select a new one
**And** on save, the employee's `storeId` is updated in `kv_xxx.employees`
**And** next API request from Loïc using a previously issued JWT is rejected with HTTP 401 `{ "domainCode": "STORE_REASSIGNED" }` — forcing a re-login to obtain a JWT with the updated `storeId`
**And** a `EmployeeStoreReassignedEvent` is emitted in the audit log

**AC7 — Revoke employee access**

**Given** Simon needs to revoke an employee's access immediately
**When** he taps the employee and selects "Désactiver l'accès"
**Then** the employee's status is set to `INACTIVE` in `kv_xxx.employees`
**And** the `user_tenant_memberships` row for that employee in this tenant is set to `is_active: false`
**And** all active sessions for that employee are invalidated within 5 minutes (refresh token revocation)
**And** the next API request from that employee returns HTTP 401 `{ "domainCode": "ACCOUNT_INACTIVE" }`
**And** a `EmployeeDeactivatedEvent` is emitted in the audit log

**AC8 — Employee list view**

**Given** Simon navigates to Paramètres > Équipe
**When** the team screen loads
**Then** all employees (ACTIVE and INACTIVE) are listed with: name, assigned store, status badge (Actif / Inactif), and a "Mot de passe non changé" badge if `passwordChangeRequired: true` is still set
**And** INACTIVE employees are shown at the bottom of the list
**And** Simon can tap any employee to view details, reassign store, or deactivate

---
