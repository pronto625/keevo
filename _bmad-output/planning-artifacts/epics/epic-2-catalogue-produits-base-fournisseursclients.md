# Epic 2: Catalogue Produits & Base Fournisseurs/Clients

Simon peut gérer son catalogue complet — créer, modifier, archiver ses produits avec photos, configurer les prix avec calcul automatique des marges, surveiller ses seuils de stock, importer en masse via CSV, et gérer sa base clients et fournisseurs, le tout disponible offline et synchronisé automatiquement.

**FRs couverts :** FR21–FR29, FR87, FR89

---

## Story 2.1: CRUD Produits — Création, Édition & Archivage

As a proprietor (Simon),
I want to create, edit, and archive products with photos, categories, SKU references, and variants,
So that my catalogue is always up to date and every product is easy to find and identify.

**Acceptance Criteria:**

**Given** Simon is in the Catalogue module
**When** he taps the "Ajouter un produit" FAB
**Then** a product creation form appears with fields: Nom (required), Description (optional), Référence SKU (auto-generated as `KEV-{6 alphanumeric}`, editable), Catégorie (dropdown from tenant categories), Photo, Variantes (toggle)
**And** the form validates inline — name field shows error immediately if left empty on blur
**And** the form is functional offline — no network required to open or fill it

**Given** Simon fills in the product name and taps "Enregistrer"
**When** the form is submitted
**Then** the product is created locally in the Drift `products` table with a UUID v4 `id`, `tenantId`, `name`, `sku`, `categoryId`, `createdAt`, `updatedAt`, `archived: false`, `status: ACTIVE` (enum: `ACTIVE | DRAFT`)
**And** products created via this catalogue form always have `status: ACTIVE` — only products created on-the-fly during a POS sale (Story 2.4) are initialised with `status: DRAFT`
**And** the operation is queued in `sync_queue` if offline, or synced immediately if online
**And** a success SnackBar "Produit créé" appears
**And** the new product appears immediately in the product list without requiring a refresh

**Given** Simon wants to add a photo
**When** he taps the photo field
**Then** a bottom sheet offers two options: "Prendre une photo" (camera) and "Choisir dans la galerie" (gallery)
**And** the selected image is compressed to ≤ 500 KB before storage (JPEG quality 80)
**And** the image is stored locally and queued for upload to S3 on sync
**And** if no photo is provided, a placeholder with the product's initials is shown

**Given** Simon's sector is "Vêtements & Shopping"
**When** he toggles the "Variantes" option on a product
**Then** he can add variant axes: Taille (XS, S, M, L, XL, XXL — editable list) and/or Couleur (color picker with hex value)
**And** each variant combination creates a distinct `stock_level` row (e.g., product `Robe` × taille `M` × couleur `Rouge`)
**And** the product card in the list shows "X variantes" badge

**Given** Simon wants to edit an existing product
**When** he taps a product card and then the edit icon
**Then** the same form opens pre-populated with all existing values
**And** changes are saved locally on "Enregistrer" with `updatedAt` refreshed
**And** an audit event `ProductUpdatedEvent` is emitted with `valueBefore` and `valueAfter`

**Given** Simon wants to archive a product (no soft delete — data preservation rule)
**When** he long-presses a product card and selects "Archiver"
**Then** a confirmation dialog appears: "Archiver ce produit ? Il ne sera plus visible dans le POS mais son historique est conservé."
**And** on confirmation: `archived: true` is set, the product disappears from all POS product lists
**And** the product remains visible in Catalogue > Archivés
**And** an audit event `ProductArchivedEvent` is emitted
**And** no product is ever permanently deleted — only archived

**Given** Simon searches for a product
**When** he types in the Catalogue search bar
**Then** results filter in real-time (debounce 300ms) matching on name, SKU, or category
**And** the search works fully offline against local Drift data

---

## Story 2.2: Moteur de Prix & Calcul de Marge

As a proprietor (Simon),
I want to set purchase price, transport costs, and selling price with automatic margin calculation,
So that I always know my profitability per product and can price intelligently.

**Acceptance Criteria:**

**Given** Simon is creating or editing a product
**When** he fills in the pricing section
**Then** three fields are available: "Prix d'achat" (XAF integer, required), "Coût de transport" (XAF integer, optional, default 0), "Prix de vente catalogue" (XAF integer, required)
**And** all monetary inputs show a "FCFA" suffix label and reject decimal input (integers only)
**And** the `Money` value object is enforced both on the client (Freezed DTO validation) and on the server (domain model constructor)

**Given** Simon enters: Prix d'achat = 5000, Coût de transport = 500, Prix de vente = 8000
**When** any of the three values changes
**Then** the margin is calculated and displayed in real-time:
  - Coût total = 5000 + 500 = 5500 FCFA
  - Marge brute = 8000 - 5500 = 2500 FCFA
  - Marge % = (2500 / 5500) × 100 = 45,5%
**And** the margin indicator uses color coding: green (≥ 20%), orange (10–19%), red (< 10%)
**And** if the selling price is lower than the purchase cost, a warning banner appears: "⚠ Prix de vente inférieur au coût — vous vendez à perte"

**Given** the price-in-cart editable rule (UX10)
**When** Loïc (employee) processes a sale in the POS
**Then** he can modify the unit price of an item directly in the cart for that specific transaction
**And** this temporary override does NOT modify the catalogue price of the product
**And** the audit log records: `SalePriceOverriddenEvent` with `cataloguePrice`, `appliedPrice`, `actorId`, `saleId`
**And** Simon can see all price overrides in the sale history per transaction

**Given** a product has been created with a catalogue price
**When** Simon updates the catalogue price later
**Then** existing completed sales are NOT retroactively modified — their stored `appliedPrice` is preserved
**And** only future sales use the new catalogue price as the default

**Given** all monetary values in the system
**When** any calculation is performed (margin, total, change)
**Then** the result is always an integer (XAF — no centimes, no decimals, no floating point)
**And** rounding uses standard mathematical rounding (0.5 → 1) if intermediate calculation produces a decimal

---

## Story 2.3: Seuils de Stock & Historique des Mouvements

As a proprietor (Simon),
I want to set minimum stock thresholds per product and see the complete movement history,
So that I never run out of stock unexpectedly and can trace every change with full accountability.

**Acceptance Criteria:**

**Given** Simon is on a product's detail page
**When** he navigates to the "Stock" tab
**Then** he sees the current stock level (or per-variant breakdown), the configured alert threshold ("Seuil minimum"), and a "Modifier le seuil" button

**Given** Simon sets a minimum threshold of 10 units on a product
**When** the stock level drops to ≤ 10 (via sale, transfer, or manual adjustment)
**Then** the system emits a `StockThresholdBreachedEvent`
**And** a push notification is sent to Simon's device: "⚠ Stock bas : [Product Name] — il vous reste [current] unité(s) (seuil : [threshold])"
**And** the product card in Catalogue shows a red "Stock bas" badge
**And** the threshold check happens server-side on every stock-modifying operation — it cannot be bypassed client-side

**Given** any stock-modifying operation occurs (sale, stock entry, transfer between stores, manual adjustment)
**When** the operation is committed
**Then** a `stock_movement` audit entry is written with: `productId`, `variantId` (if applicable), `storeId`, `movementType` (SALE / STOCK_ENTRY / TRANSFER_IN / TRANSFER_OUT / ADJUSTMENT), `quantityBefore`, `quantityChange`, `quantityAfter`, `actorId`, `occurredAt`, `notes` (optional)

**Given** Simon navigates to the stock history for a specific product
**When** the history screen loads
**Then** all movements are displayed in reverse chronological order
**And** each entry shows: type icon (🛍 Vente / 📦 Entrée / 🔄 Transfert / ✏ Ajustement), product name + variant, quantity change (e.g., "−3"), quantity after, actor name, date/time
**And** Simon can filter by: movement type, date range (last 7 days default), store
**And** the history loads offline from local Drift data

**Given** Simon performs a manual stock adjustment (e.g., shrinkage, breakage)
**When** he taps "Ajuster le stock" and enters a new quantity with a reason
**Then** the adjustment is recorded as `ADJUSTMENT` in the history
**And** the reason is stored in the `notes` field
**And** the `AuditEventListener` emits a `StockAdjustedEvent` with before/after values

---

## Story 2.4: Import CSV & Création Progressive de Produits

As a proprietor (Simon),
I want to import my existing product catalogue from a CSV file and create products on-the-fly during a sale,
So that I can onboard my full inventory rapidly and never block a sale because a product isn't in the system yet.

**Acceptance Criteria:**

**Given** Simon navigates to Catalogue > Importer
**When** he uploads a CSV file
**Then** the system parses the file and displays a column-mapping screen: each CSV column is matched to a Keevo field (Nom, Prix d'achat, Prix de vente, Catégorie, SKU, Quantité initiale)
**And** unrecognized columns can be ignored or mapped manually
**And** the system previews the first 5 rows with the applied mapping before import

**Given** Simon confirms the column mapping and taps "Lancer l'import"
**When** the import runs
**Then** each row is validated independently: required fields (Nom, Prix de vente) must be non-empty, Prix values must be positive integers
**And** rows with errors are skipped and listed in a post-import error report: "Ligne 12 : Prix de vente manquant"
**And** valid rows are imported successfully — partial import is allowed (not all-or-nothing)
**And** a success summary is shown: "🎉 127 produits importés, 3 erreurs — voir le rapport"

**Given** the import exceeds the plan's product limit (e.g., Free plan = 500 products, Simon has 450, imports 100)
**When** the 500-product limit is reached during import
**Then** import stops at the limit and returns: "Limite atteinte : 50 produits importés sur 100. Passez au plan Premium pour continuer."
**And** the 50 successfully imported products are preserved
**And** the error report includes all rows that were skipped due to the limit

**Given** Simon is processing a sale in the POS and scans/enters a product name that doesn't exist
**When** no matching product is found in the Catalogue
**Then** a bottom sheet appears: "Produit introuvable — Créer '[searched name]' à la volée ?"
**And** tapping "Créer" opens a minimal product form pre-filled with the searched name: Prix de vente (required), Catégorie (required), Quantité (for this sale)
**And** on save, the product is created with `status: DRAFT` (visible in Catalogue with a "Brouillon" badge) and immediately added to the cart
**And** `DRAFT` products are visible in the POS search and can be sold, but are flagged in the Catalogue as incomplete — Simon must complete the missing fields (photo, purchase price, description) to transition them to `status: ACTIVE`
**And** Simon can complete the product's details later from the Catalogue; saving all required fields automatically sets `status: ACTIVE` and removes the "Brouillon" badge
**And** a `ProductCreatedProgressivelyEvent` is emitted in the audit log with `initialStatus: DRAFT`

**Given** a CSV template is needed for Simon to prepare his data
**When** he taps "Télécharger le modèle CSV"
**Then** a pre-formatted CSV template is downloaded with headers: `nom`, `prix_achat`, `cout_transport`, `prix_vente`, `categorie`, `sku`, `quantite_initiale`, `seuil_min`
**And** the template includes 3 example rows with sample data

---

## Story 2.5: Gestion Clients & Fournisseurs

As a proprietor (Simon),
I want to maintain a client directory and a supplier directory linked to my products,
So that I can track purchase history per client, contact my suppliers easily, and manage B2B relationships.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Clients
**When** he taps "Ajouter un client"
**Then** a form appears with: Nom (required), Téléphone (required, WhatsApp-compatible format), Email (optional), Notes (optional, free text)
**And** the client is created in the local `clients` Drift table with UUID, `tenantId`, `createdAt`
**And** the client appears in the searchable client list sorted by name

**Given** Simon has created clients
**When** he views the client directory
**Then** he can search by name or phone number (real-time filter, debounce 300ms)
**And** each client card shows: name, phone, total number of purchases (count of sales linked to this client), total amount spent (sum of linked sales in XAF)
**And** tapping a client opens their profile with the full purchase history list

**Given** Loïc processes a sale in the POS
**When** he selects a client for that sale (optional field)
**Then** the sale is linked to the client (`clientId` stored on the `sales` record)
**And** the client's purchase history and total spend are updated immediately in the local database
**And** anonymous sales (no client selected) are allowed — client is optional

**Given** Simon navigates to Paramètres > Fournisseurs
**When** he taps "Ajouter un fournisseur"
**Then** a form appears with: Nom (required), Téléphone (required), Email (optional), Produits fournis (multi-select from Catalogue)
**And** the supplier is saved in the `suppliers` Drift table

**Given** Simon links a supplier to a product
**When** he views the product's detail page
**Then** a "Fournisseur" field shows the linked supplier name with a tap-to-call shortcut
**And** from the supplier's profile, he can see all products sourced from that supplier

**Given** all client and supplier data
**When** the app goes offline and Simon creates or edits a client/supplier
**Then** the change is saved locally and queued in `sync_queue`
**And** on sync, the backend persists the change in the tenant's `clients` and `suppliers` tables
**And** no client or supplier is ever permanently deleted — only archived (`archived: true`)

---
