# Epic 4: Point de Vente (POS)

Loïc peut enregistrer une vente en ≤3 taps avec feedback < 200ms, vérifier la disponibilité cross-boutique, accepter les paiements cash ou Mobile Money, appliquer des réductions, vendre des produits qui n'existent pas encore (brouillons validés par Simon), clôturer sa journée en 1 tap avec résumé automatique. Simon peut valider les ventes brouillons, corriger ou annuler toute vente avec justification obligatoire et réajustement automatique du stock.

**FRs couverts :** FR37–FR44, FR88, FR90

---

## Story 4.1: Enregistrement de Vente & Flux POS Core

As an employee (Loïc),
I want to record a sale in 3 taps maximum with immediate feedback,
So that I can serve customers rapidly without slowing down the queue or returning to paper.

**Acceptance Criteria:**

**Given** Loïc opens the app
**When** the app loads
**Then** the POS screen is the default landing screen — zero navigation required to start a sale
**And** a search bar is displayed at the top with a fuzzy product search placeholder
**And** a grid of frequently used and recently sold products is shown below (up to 12 cards, ordered by sales frequency)
**And** the `CartPill` component is hidden at the bottom (appears only when cart has ≥ 1 item)

**Given** Loïc types in the search bar
**When** he enters at least 2 characters
**Then** fuzzy search results appear in under 500ms against the local Drift `products` table
**And** results show: product photo (or initials placeholder), name, price in XAF, stock level badge
**And** out-of-stock products (quantity = 0) appear at the bottom with a grey "⚠️ Rupture" overlay and a "Vérifier autres boutiques" action

**Given** Loïc taps a product card
**When** the product is added to the cart
**Then** the product card glides into the CartPill with a subtle animation (duration < 200ms)
**And** a light haptic vibration is triggered
**And** the card flashes `#D0EBFF` (selected state) for 150ms then reverts to normal
**And** the CartPill appears (or updates) showing: nb of items, total in XAF, "Encaisser" button
**And** the cart total updates in real-time

**Given** Loïc has items in the cart
**When** he taps the CartPill to expand it
**Then** a bottom sheet shows all cart items: product name + variant, quantity (+/- controls), applied unit price (tappable to edit — Story 4.2), subtotal per line
**And** the grand total is displayed prominently at the bottom
**And** he can swipe left on any item to delete it from the cart

**Given** Loïc taps "Encaisser"
**When** the payment screen appears
**Then** two payment mode buttons are displayed: "💵 Espèces" and "📱 Mobile Money"
**And** a client selector field is shown (optional — autocomplete from client directory, Story 2.5)
**And** for Espèces: a "Montant reçu" field appears, and the system calculates and displays change: `Monnaie rendue = montant_reçu - total` in XAF
**And** for Mobile Money: a reference number field appears (optional, for record-keeping)

**Given** Loïc confirms the payment
**When** he taps "Valider la vente"
**Then** the sale is recorded in the local Drift `sales` table with: `id` (UUID), `tenantId`, `storeId`, `actorId`, `clientId` (nullable), `paymentMode` (CASH / MOBILE_MONEY), `totalAmount` (XAF integer), `status: COMPLETED`, `occurredAt`
**And** each cart item is recorded in `sale_items` with: `productId`, `variantId`, `quantity`, `appliedUnitPrice`, `subtotal`
**And** stock levels are decremented in Drift for each sold product/variant in the assigned store
**And** a `SaleCompletedEvent` is emitted — the audit log captures the full sale payload
**And** the operation is queued in `sync_queue` if offline
**And** the success screen appears: "✅ Vente enregistrée — [total] FCFA" with an animated lime-green confirmation (< 200ms)
**And** after 1.5 seconds, the POS screen resets to empty — ready for the next sale
**And** the entire flow from first tap to success confirmation takes ≤ 5 seconds for a 1-product sale

**Given** a product has no photo
**When** it is displayed on the POS grid or in cart
**Then** a coloured circle with the product's initials (first 2 letters, uppercase) is shown as placeholder
**And** the colour is deterministically derived from the product name (consistent across sessions)

---

## Story 4.2: Réductions & Prix Modifiable en Panier

As an employee (Loïc),
I want to modify the price of an item in the cart and apply discounts without touching the catalogue price,
So that I can handle negotiations, promotions, and special prices for loyal customers in real time.

**Acceptance Criteria:**

**Given** Loïc is viewing the expanded cart (Story 4.1)
**When** he taps the unit price of any cart item
**Then** an inline numeric editor appears directly in the cart row (no modal, no navigation)
**And** the field shows the current applied price pre-filled in XAF
**And** the keyboard is numeric — no decimal input allowed
**And** leaving the field or tapping the checkmark confirms the override

**Given** Loïc enters a custom price (e.g., 4500 instead of 5000)
**When** the price is confirmed
**Then** the cart item now shows `4 500 FCFA` as the applied price with a red accent label "Prix modifié"
**And** the catalogue price of the product in the `products` table is NOT modified
**And** the line subtotal and grand total update immediately
**And** a `SalePriceOverriddenEvent` will be emitted on sale completion with: `cataloguePrice`, `appliedPrice`, `delta`, `actorId`, `saleId`

**Given** Loïc wants to apply a percentage discount on the entire order
**When** he taps "Réduction" in the cart bottom sheet header
**Then** a small sheet appears offering two options: "% sur le total" and "Montant fixe"
**And** for % discount: a numeric field (0–100), the discounted total is previewed in real-time
**And** for fixed amount: a XAF integer field, validated that discount ≤ total
**And** on confirm, the discount is applied to the grand total — each `sale_item` retains its original `appliedUnitPrice`, and a `discountAmount` field on the `sales` record stores the total reduction

**Given** a discount is applied to the cart
**When** Loïc views the checkout screen
**Then** the total breakdown shows: Sous-total, Réduction (−X FCFA), **Total à payer** in bold
**And** the change calculation (Espèces mode) uses the final discounted total

**Given** Simon reviews completed sales in the history
**When** he views a sale that had a price override or discount
**Then** the sale detail shows: each item's catalogue price vs applied price, the discount line (if any), and the `SalePriceOverriddenEvent` entries in the audit log
**And** the audit entry clearly identifies Loïc as the actor and the delta amount

---

## Story 4.3: Vente Brouillon — Produits Draft & Validation Admin

As an employee (Loïc),
I want to record a sale with products that don't exist yet in the catalogue, saved as a pending sale,
So that I can serve customers from day 0 without waiting for the full catalogue setup, and Simon can validate the sale once the products are confirmed.

**Acceptance Criteria:**

**Given** Loïc searches for a product in the POS and no matching product is found
**When** the search results list is empty
**Then** a CTA appears: "Produit introuvable — Créer '[searched name]' à la volée ?"
**And** tapping the CTA opens the existing `CreateDraftProductBottomSheet` pre-filled with the searched name
**And** the created DRAFT product is immediately added to the cart with `stockQuantity: 0` (the product doesn't physically exist in stock yet — it's day 0)
**And** the DRAFT product is visible in POS search for subsequent sales

**Given** the cart contains at least one product with `status: DRAFT`
**When** Loïc views the CartPill or the expanded CartBottomSheet
**Then** the "Encaisser" button label changes to "🔶 Vente brouillon"
**And** each DRAFT product row in the cart shows a "🔶 Brouillon" badge next to the product name
**And** the CartPill shows an orange tint/border instead of the default style
**And** a small informational text appears at the top of the CartBottomSheet: "Cette vente contient des produits en brouillon. Elle sera validée quand l'admin les confirmera."

**Given** Loïc confirms payment on a cart containing at least one DRAFT product
**When** he taps "Vente brouillon" (the modified Encaisser button)
**Then** the sale is recorded in the local Drift `sales` table with `status: PENDING_VALIDATION` (new enum value)
**And** each cart item is recorded normally in `sale_items`
**And** stock levels are NOT decremented for ANY item in the sale (not just DRAFT ones — the entire sale is pending, stock impact is deferred until validation)
**And** NO `StockMovement` records are created at this point
**And** a `SalePendingValidationEvent` is emitted with: `saleId`, `storeId`, `actorId`, `draftProductIds[]`, `totalAmount`, `occurredAt`
**And** the operation is queued in `sync_queue` for backend sync
**And** the success screen shows: "🔶 Vente en attente — [total] FCFA" with an amber confirmation (not lime-green)
**And** after 1.5s the POS resets to empty

**Given** Loïc confirms payment on a cart with ONLY `status: ACTIVE` products
**When** he taps "Encaisser"
**Then** the existing flow from Story 4.1 applies unchanged — `status: COMPLETED`, stock decremented immediately

**Given** the backend receives a sale with `status: PENDING_VALIDATION`
**When** `POST /api/v1/sales` is called
**Then** the backend accepts the sale with `status = PENDING_VALIDATION`
**And** stock is NOT decremented server-side
**And** `SaleStatus.PENDING_VALIDATION` is a valid enum value in `SaleStatus.java`
**And** the DDL constraint on `sales.status` is updated: `CHECK (status IN ('COMPLETED', 'CANCELLED', 'PENDING_VALIDATION'))`
**And** `TenantSchemaSyncService` migrates the CHECK constraint for existing tenants

**Given** Simon (OWNER) navigates to the POS area
**When** there are sales with `status: PENDING_VALIDATION`
**Then** a badge on the POS navigation shows the count of pending sales (amber, same style as draft products badge)
**And** Simon can access "Ventes en attente" from the POS menu (OWNER-only — hidden for EMPLOYEE)
**And** the pending sales list shows: date, employee name, total, number of items, number of DRAFT products still pending
**And** tapping a pending sale opens its detail showing all items with a badge indicating which products are DRAFT vs ACTIVE
**And** each DRAFT product row has a direct link "Valider ce produit →" that navigates to `/products/{id}/edit`
**And** `GET /api/v1/sales/pending` returns all `PENDING_VALIDATION` sales for the tenant (OWNER-only, HTTP 403 for EMPLOYEE)

**Given** Simon promotes a DRAFT product to ACTIVE via `PATCH /api/v1/products/{id}` (existing Story 2.4 AC7)
**When** the promotion succeeds
**Then** `UpdateProductUseCase` calls `SaleValidationCascadeService.onProductActivated(productId)`
**And** the cascade service queries all `PENDING_VALIDATION` sales containing this `productId`
**And** for each such sale: if ALL products referenced in `sale_items` now have `status: ACTIVE` → the sale transitions to `COMPLETED`
**And** on transition to `COMPLETED`: stock is decremented for ALL items in the sale (via `StockOperationService`, movement type `SALE`), and a `SaleCompletedEvent` is emitted
**And** a `SaleAutoValidatedEvent` is emitted with: `saleId`, `triggerProductId`, `actorId` (the OWNER who validated the product), `occurredAt`
**And** the transition is atomic (single `@Transactional`)
**And** if NOT all products are ACTIVE yet → the sale stays `PENDING_VALIDATION` (no partial activation)

**Given** Simon promotes a DRAFT product to ACTIVE but the product had `stockQuantity = 0`
**When** the cascade would validate a pending sale
**Then** the validation is BLOCKED — the sale stays `PENDING_VALIDATION`
**And** a notification is sent to Simon: "⚠ Vente #{saleId short} en attente : le produit '{productName}' n'a pas de stock. Saisissez une entrée de stock avant validation."
**And** Simon must perform a stock entry (`STOCK_ENTRY` movement) for the product BEFORE the cascade can complete
**And** the cascade re-checks on every stock-modifying operation (stock entry, transfer-in) for products linked to `PENDING_VALIDATION` sales

**Given** Simon wants to force-validate a pending sale even if some products are still DRAFT
**When** he taps "Valider manuellement" on a `PENDING_VALIDATION` sale detail
**Then** a mandatory justification field is required (minimum 10 characters)
**And** on confirmation: the sale transitions to `COMPLETED` regardless of product statuses
**And** stock is decremented for all items — if a product has insufficient stock, stock goes to 0 (not negative) and a `StockForcedZeroEvent` is emitted
**And** a `SaleManuallyValidatedEvent` is emitted with: `saleId`, `actorId`, `justification`, `forcedProducts[]` (list of products that were still DRAFT or had insufficient stock)
**And** this endpoint is `POST /api/v1/sales/{id}/validate` — OWNER-only (HTTP 403 for EMPLOYEE)

**Given** Simon wants to cancel a pending sale
**When** he taps "Annuler" on a `PENDING_VALIDATION` sale detail
**Then** a mandatory justification field is required (minimum 10 characters)
**And** on confirmation: the sale transitions to `CANCELLED`
**And** NO stock restoration is needed (stock was never decremented)
**And** a `SaleCancelledEvent` is emitted with: `saleId`, `actorId`, `justification`, `occurredAt`
**And** this action is available for both `PENDING_VALIDATION` and `COMPLETED` sales (Story 4.5 covers COMPLETED cancellation with stock restoration)

**Given** a sale has been `PENDING_VALIDATION` for more than 48 hours
**When** the reminder check runs (daily at 09:00 local time)
**Then** a notification is sent to Simon: "🔶 Rappel : {count} vente(s) en attente de validation depuis plus de 48h"
**And** the notification includes a deep link to the pending sales list
**And** after 7 days: a stronger notification: "⚠ {count} vente(s) en attente depuis 7 jours — validez ou annulez-les"
**And** the sales are NEVER auto-cancelled — only Simon can decide

**Given** the day-close flow (Story 4.4) runs
**When** generating the daily summary
**Then** `PENDING_VALIDATION` sales are EXCLUDED from the revenue calculation and sale count
**And** the day-close report includes a separate line: "🔶 {count} vente(s) en attente de validation — {total} FCFA (non comptabilisé)"

**Given** all operations happen offline
**When** Loïc creates a draft product + pending sale offline, and Simon validates the product offline
**Then** the cascade validation executes locally in Drift: sale status updated, stock decremented locally
**And** all operations are queued in `sync_queue` with their respective types
**And** on sync: backend applies the same sequence atomically

**Given** an audit review of pending sales
**When** any status transition occurs
**Then** `SalePendingValidationEvent` (creation), `SaleAutoValidatedEvent` (cascade), `SaleManuallyValidatedEvent` (forced), `SaleCancelledEvent` (annulation) are all recorded in the immutable audit log
**And** each event includes full `actorId`, `justification` (if applicable), `occurredAt`, and relevant product/sale IDs

---

## Story 4.4: Clôture Journalière & Historique des Ventes

As an employee (Loïc),
I want to close my day in 1 tap and see my own sales history,
So that I can end my shift properly without any manual accounting and track my own performance.

**Acceptance Criteria:**

**Given** Loïc is working during the day
**When** he wants to close his day
**Then** a "🌙 Clôturer la journée" button (`DayCloseButton` component) is prominently visible on the POS screen (bottom trailing position)
**And** the button shows a notification badge with the count of sales made since last closure
**And** tapping it shows a flash summary bottom sheet (non-blocking, auto-dismisses after 5s): total sales count, total revenue in XAF, top product sold (name + qty), payment breakdown (Cash vs MoMo)

**Given** Loïc taps "Confirmer la clôture" on the flash summary
**When** the closure is confirmed
**Then** a `DayClosedEvent` is emitted with: `storeId`, `actorId`, `closedAt`, `totalSales`, `totalRevenue`, `paymentBreakdown`
**And** the daily report is generated (FR42) and sent automatically via `WhatsAppPort` to Simon's number
**And** the report format is emoji-rich, compact, French, readable in 5 seconds (no PDF, no link) — example:
```
📊 Clôture Boutique Centrale — 2 mars 2026
👤 Vendeur : Loïc
💰 CA : 125 000 FCFA
🛍 Ventes : 14
📦 Top produit : Robe M Rouge (×5)
💵 Cash : 90 000 | 📱 MoMo : 35 000
✅ Clôture manuelle à 18h32
```
**And** the app shows "Bonne soirée Loïc ! 🌙" on a success screen before returning to the POS
**And** the `DayCloseButton` transitions to a "Journée clôturée ✅" disabled state until the next calendar day

**Given** Loïc forgets to close his day manually
**When** 20:00 local time is reached without a `DayClosedEvent` for that store and actor
**Then** the system automatically generates the daily closure and sends the WhatsApp report
**And** the report adds the indicator: "⏰ Rapport auto-généré (clôture oubliée)"
**And** Simon is notified — Loïc is NOT penalized, no error is raised in the app
**And** the next morning, Loïc sees a subtle notification: "Votre journée du 2 mars a été clôturée automatiquement."

**Given** Loïc navigates to his sales history
**When** he opens POS > Mes Ventes
**Then** he sees all his own sales for the current day by default (filter: Today)
**And** each sale card shows: time, total, payment mode, client name (if linked), number of items
**And** he can filter by: date range (Today / This week / This month / Custom)
**And** tapping a sale opens its detail: all items with quantities and applied prices, discount if any, client
**And** Loïc can only see HIS OWN sales — he cannot view sales made by other employees
**And** the history loads offline from local Drift data

**Given** the daily summary calculation (FR42)
**When** the day-close is triggered (manual or auto)
**Then** the system aggregates all `sales` records for `storeId` + `date` into: total count, total revenue (sum of `totalAmount`), revenue by payment mode, average basket size, top 3 products by quantity sold
**And** this calculation runs locally in Drift and is confirmed server-side on sync

---

## Story 4.5: Annulation & Correction de Vente

As a proprietor (Simon),
I want to cancel or correct any sale with a mandatory justification,
So that errors are corrected with full accountability and stock is automatically restored to accurate levels.

**Acceptance Criteria:**

**Given** Simon navigates to any sale in the history (his view or store-wide)
**When** he views the sale detail screen
**Then** an "Annuler / Corriger" button is visible (OWNER role only — hidden for EMPLOYEE)
**And** tapping it displays two options: "Annuler entièrement" and "Corriger un article"

**Given** Simon selects "Annuler entièrement"
**When** he confirms the action
**Then** a mandatory justification field is required (minimum 10 characters, free text)
**And** on confirmation: the sale `status` changes to `CANCELLED`
**And** all stock levels for the sold products/variants are restored: `quantityAfter = quantityBefore + saleLine.quantity` per item
**And** a `SaleCancelledEvent` is emitted with: `saleId`, `actorId`, `justification`, `occurredAt`, full `sale_items` snapshot for stock restoration proof
**And** the restored stock movements appear in the product's movement history as `SALE_CANCELLED` type
**And** if the cancellation triggers stock levels above the threshold (previously "Bas"), the "Stock bas" badge is cleared automatically

**Given** Simon selects "Corriger un article" on a specific sale
**When** the correction mode opens
**Then** he sees all sale items with editable quantity fields
**And** decreasing a quantity restores the difference to stock; increasing a quantity decrements the difference from stock
**And** a mandatory justification field is required before saving
**And** on save: the `sale_items` record is updated, stock adjustments are applied atomically, a `SaleCorrectedEvent` is emitted with `before` and `after` snapshots
**And** the corrected sale shows a "Modifiée" badge in the history with the original total crossed out and the corrected total

**Given** the app is offline when Simon performs a cancellation
**When** the cancellation is confirmed
**Then** the cancellation is recorded locally: sale status updated in Drift, stock restored locally
**And** the operation is queued in `sync_queue` with type `SALE_CANCELLATION`
**And** on sync, the server applies the same stock restoration atomically
**And** the backend rejects the cancellation if the sale was already cancelled (idempotency check via `status`)

**Given** an EMPLOYEE role user views the sales history
**When** they see a cancelled or corrected sale
**Then** the "Annuler / Corriger" button is NOT shown — the action is OWNER-only
**And** they can see the "Annulée" or "Modifiée" badge and read the justification text (transparency for employees)

---

## Story 4.6: Leaderboard Vendeurs & Historique Global

As a proprietor (Simon),
I want to see my employees' sales performance ranked in a leaderboard and consult the full store sales history,
So that I can recognize top performers, identify coaching opportunities, and monitor overall business activity.

**Acceptance Criteria:**

**Given** Simon navigates to Rapports > Équipe
**When** the leaderboard screen loads
**Then** he sees all employees ranked by total revenue for the current day (default period)
**And** each row shows: rank badge (🥇🥈🥉 for top 3), employee name, number of sales, total revenue in XAF, average basket size
**And** Simon can change the period: Aujourd'hui / Cette semaine / Ce mois
**And** the leaderboard data is calculated from local Drift `sales` aggregated by `actorId`

**Given** the leaderboard feature gate (UX16)
**When** the leaderboard is in its default state (V1 — owner-only)
**Then** the leaderboard data is visible ONLY to Simon (OWNER role)
**And** Loïc (EMPLOYEE) cannot see the leaderboard, other employees' stats, or any comparative data
**And** the navigation item for "Équipe / Leaderboard" is not rendered in the employee navigation menu

**Given** Simon wants to motivate his team through healthy competition
**When** he navigates to Paramètres > Équipe > Options
**Then** a toggle "Afficher le leaderboard aux employés" is available (default: OFF)
**And** activating it shows a confirmation: "Les vendeurs pourront voir leur classement et celui de leurs collègues."
**And** when enabled, the leaderboard tab becomes visible to EMPLOYEE roles showing their own rank and the anonymized rankings of others

**Given** Simon views the global sales history for his store
**When** he navigates to POS > Historique boutique
**Then** he sees all sales for his store (all employees) in reverse chronological order
**And** each sale shows: time, employee name, total, payment mode, client (if linked), number of items
**And** he can filter by: employee, payment mode, date range, client
**And** he can search by sale amount range (e.g., "> 10 000 FCFA")
**And** the history loads offline from local Drift data
**And** tapping any sale opens the full sale detail with the "Annuler / Corriger" option (Story 4.5)

---
