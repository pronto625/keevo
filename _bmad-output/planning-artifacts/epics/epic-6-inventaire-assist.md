# Epic 6: Inventaire Assisté

Simon (et Loïc, si autorisé) peuvent lancer un inventaire guidé complet ou partiel, renseigner les quantités physiques réelles produit par produit, voir en temps réel les écarts avec le stock théorique Keevo, valider et appliquer les ajustements au stock en un seul tap, et recevoir un rapport d'inventaire détaillé.

**FRs couverts :** FR45–FR49

---

## Story 6.1: Lancement & Configuration d'une Session d'Inventaire

As a proprietor (Simon),
I want to start an inventory session for a specific store or the warehouse, choosing between full or partial scope,
So that I can count my actual stock without disrupting ongoing operations.

**Acceptance Criteria:**

**Given** Simon navigates to Stock > Inventaire
**When** he taps "Lancer un inventaire"
**Then** a configuration screen appears with:
  - Boutique cible (required — dropdown of active stores + warehouse)
  - Scope: "Inventaire complet" (all products) or "Inventaire partiel" (select categories)
  - Mode: "Compter maintenant" (immediate) or "Planifier" (date + time picker, future)
**And** for partial scope, a multi-select list of categories is shown (from tenant's configured categories)

**Given** Simon confirms the inventory configuration
**When** the session is created
**Then** an `InventorySession` record is created in Drift with: `id` (UUID), `tenantId`, `storeId`, `scope` (FULL / PARTIAL), `categoryIds` (if partial), `status: IN_PROGRESS`, `startedAt`, `startedBy` (actorId)
**And** the session is also queued in `sync_queue` to notify the server (so other devices know an inventory is active)
**And** the app navigates immediately to the inventory counting form (Story 6.2)

**Given** an inventory session is already IN_PROGRESS for a store
**When** another user tries to start a new session for the same store
**Then** the system shows a warning: "Un inventaire est déjà en cours pour [Store Name], commencé par [Actor] à [time]. Voulez-vous le reprendre ou l'annuler ?"
**And** the user can choose to resume the existing session or cancel it and start a new one
**And** only OWNER can cancel an in-progress session started by an EMPLOYEE

**Given** a session is created offline
**When** the app is offline during session creation
**Then** the session is created locally in Drift and queued in `sync_queue`
**And** the counting form works fully offline — no network needed to count stock
**And** the session and all counts are pushed on next sync

---

## Story 6.2: Formulaire Guidé de Saisie des Quantités Physiques

As a user (Simon or Loïc),
I want a guided form that shows me each product with its theoretical stock and lets me enter the physical count,
So that I can complete the inventory systematically without missing any product and without errors.

**Acceptance Criteria:**

**Given** Simon is in an active inventory session
**When** the counting form loads
**Then** all products in scope (filtered by store + category if partial) are displayed as `InventoryRow` components, sorted alphabetically by default
**And** each `InventoryRow` shows: product photo (or initials placeholder), product name, variant label (if applicable), theoretical stock from Drift `stock_levels` (labelled "Keevo : X"), a numeric input field for the physical count (labelled "Réel"), and an ecart badge (hidden until physical count is entered)

**Given** Simon taps the physical count field for a product
**When** he enters a number
**Then** the `InventoryRow` immediately calculates and displays the gap badge:
  - ✅ green "= 0" if physical = theoretical
  - ⚠️ orange "+X" if physical > theoretical (surplus)
  - 🔴 red "−X" if physical < theoretical (shortage)
**And** the gap value is calculated as: `écart = physique − théorique`
**And** the row background subtly highlights: white (match), amber-50 (surplus), red-50 (shortage)

**Given** the inventory has many products (e.g., 200 items)
**When** Simon scrolls through the list
**Then** the list uses `ListView.builder` (lazy rendering — never `children: [...]`)
**And** a progress bar at the top shows "X / Y produits comptés" updating as fields are filled
**And** a FAB "Filtrer" allows filtering by: Tous / Non comptés / Écarts seulement
**And** the "Non comptés" filter is the default after starting — Simon works through uncounted items first

**Given** Simon wants to navigate quickly to a specific product
**When** he taps the search icon in the inventory form
**Then** a search bar appears and filters the list in real-time against product name and SKU
**And** the search works offline against local Drift data

**Given** all products have been counted (progress = 100%)
**When** Simon reviews the completed form
**Then** a summary banner appears at the top: "Inventaire terminé — X produits concordants, Y écarts"
**And** a prominent "Valider l'inventaire" button becomes active (previously disabled)
**And** Simon can still edit any count before validating

**Given** Simon wants to pause and resume the inventory later
**When** he exits the counting form without validating
**Then** the session status remains `IN_PROGRESS` and all entered counts are saved locally
**And** re-entering the inventory module shows a "Reprendre l'inventaire en cours" banner
**And** all previously entered counts are pre-filled when he resumes

---

## Story 6.3: Calcul des Écarts & Rapport d'Inventaire

As a proprietor (Simon),
I want to see a complete gap analysis report before committing any stock changes,
So that I can review discrepancies, identify potential theft or errors, and make an informed decision.

**Acceptance Criteria:**

**Given** Simon taps "Valider l'inventaire"
**When** the validation screen loads
**Then** a full gap report is generated locally from the counted data and displayed:
  - Summary header: total products counted, total products with gaps, total value of shortages (sum of `|écart| × unitPrice` for negative gaps in XAF)
  - Concordant products section: count only (collapsed by default)
  - Surplus section: list of products with `écart > 0` — name, variant, theoretical, physical, +delta, XAF value
  - Shortage section: list of products with `écart < 0` — name, variant, theoretical, physical, −delta, XAF value — sorted by value (largest loss first)
**And** the `InventoryRow` (UX32) component is reused for each row in the report

**Given** the gap report is displayed
**When** Simon reviews it
**Then** he can tap any row to see the product detail (photo, category, movement history)
**And** a "Télécharger le rapport" button (OWNER only) exports the report as a formatted text summary (Plan Free) or PDF (Plan Payant)
**And** a "Partager sur WhatsApp" button sends the report via `WhatsAppPort` in the same emoji-rich compact text format as day-close reports:
```
📋 Rapport d'inventaire — Boutique Centrale
📅 2 mars 2026 — 16h45
👤 Simon

✅ Concordants : 142 produits
⚠️ Surplus : 3 produits (+15 000 FCFA)
🔴 Manquants : 7 produits (−47 500 FCFA)

Top manques :
• Robe M Rouge : −5 unités (−25 000 FCFA)
• Jeans L : −2 unités (−12 000 FCFA)
```
**And** the report can be sent even if the inventory is not yet validated (pre-validation review sharing)

**Given** Simon navigates to Stock > Historique inventaires (without validating the current session)
**When** he views past inventory reports
**Then** all past `VALIDATED` sessions are listed with: date, store, scope, product count, total gaps count, total shortage value
**And** each past report is fully viewable offline from local Drift data

---

## Story 6.4: Validation & Application des Ajustements au Stock

As a proprietor (Simon),
I want to apply all inventory gaps to the actual stock levels in one tap with full audit traceability,
So that Keevo's stock data matches physical reality after every inventory session.

**Acceptance Criteria:**

**Given** Simon is on the gap report validation screen
**When** he taps "Appliquer les ajustements"
**Then** a final confirmation dialog appears: "Appliquer [N] ajustements de stock ? Cette action est irréversible." with "Annuler" and "Confirmer"

**Given** Simon confirms the application
**When** the adjustments are processed
**Then** for each product with `écart ≠ 0`: the `stock_levels` record for that `productId + variantId + storeId` is updated to the physical count value
**And** a `StockAdjustedEvent` is emitted per product with: `source: INVENTORY`, `quantityBefore` (theoretical), `quantityAfter` (physical), `inventorySessionId`, `actorId`, `occurredAt`
**And** all adjustments are applied atomically — either all succeed or none are applied (transaction)
**And** the inventory session `status` is updated to `VALIDATED` and `completedAt` is set
**And** all operations are queued in `sync_queue` if offline

**Given** a stock adjustment during inventory validation crosses a product's threshold
**When** the adjustment sets stock below the configured minimum
**Then** a `StockThresholdBreachedEvent` is emitted for that product (same flow as Story 2.3)
**And** the push notification is sent to Simon

**Given** the inventory session is validated
**When** Simon views the stock levels for the inventoried store
**Then** all stock levels reflect the physical counts from the inventory
**And** the stock movement history (Story 2.3) shows each adjustment with type `ADJUSTMENT`, source `INVENTORY`, and the `inventorySessionId` as reference
**And** the `InventorySession` record is preserved in full — it cannot be deleted

**Given** products with zero gap (`écart = 0`)
**When** the validation runs
**Then** no `StockAdjustedEvent` is emitted for those products (no unnecessary audit noise)
**And** their stock levels are not touched

---
