# Story 6.3: Calcul des Écarts & Rapport d'Inventaire

Status: done

<!-- Validation optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a proprietor (Simon),
I want to see a complete gap analysis report before committing any stock changes,
So that I can review discrepancies, identify potential theft or errors, and make an informed decision.

---

## ⚠️ CRITICAL ARCHITECTURAL CONTEXT — Building on Stories 6.1, 6.2, 6.2a

> **This is Story 6.3 of Epic 6 — Inventaire Assisté.**
> Stories 6.1, 6.2, and 6.2a are **done**. This story adds the gap analysis report, WhatsApp sharing, and report export.
>
> **Drift schema**: currently at **v21** (Story 6.2a). If schema changes are needed for gap report caching, bump to **v22**. Otherwise stays at v21 — the report is generated on-the-fly from existing `inventory_counts`, `products`, and `stock_levels` tables.
>
> **Backend test count**: 1007+ tests GREEN (104 inventory-specific). This story MUST preserve that baseline.
>
> **Key constraint**: Story 6.3 does **NOT** apply stock adjustments. It generates a read-only gap report for review. The "Appliquer les ajustements" button is Story 6.4's scope. Story 6.3's "Valider l'inventaire" button navigates to the **gap report page**, where the user can review, export, and share — the final "Appliquer" button on that page is a Story 6.4 placeholder.
>
> **The "Valider l'inventaire" button in InventoryCountingPage currently shows a SnackBar "6.3 — Rapport d'inventaire à venir". This story REPLACES that SnackBar with actual navigation to the `InventoryGapReportPage`.**

### Current State — What Stories 6.1 + 6.2 + 6.2a Created

```
BACKEND (inventory/counting/ module — COMPLETE from 6.1/6.2/6.2a):
  Domain: InventorySession (State pattern), InventoryCount, InventoryProductRow,
          InventoryScope, InventorySessionStatus, InventoryScopeResolver (Strategy),
          FullScopeResolver, PartialScopeResolver, ScopeResolverRegistry (Factory),
          QuickAddProductResult
  Events: InventorySessionCreatedEvent, InventorySessionCancelledEvent,
          InventoryCountSavedEvent
  Ports in: Create/Cancel/GetActive/List sessions, GetCountingProducts,
            SaveInventoryCount, GetSessionCounts, QuickAddProduct
  Ports out: InventorySessionRepository, InventoryCountRepository
  Services: Create/Cancel/GetActive/List session services,
            GetCountingProducts/SaveInventoryCount/GetSessionCounts services,
            QuickAddProductService (Facade)
  REST: POST/GET/POST /api/v1/inventory/sessions (create/active/cancel/list)
        GET/POST/PUT/GET /api/v1/inventory/sessions/{id}/products|counts
        POST /api/v1/inventory/sessions/{id}/quick-add
  JPA: InventorySessionJpaEntity, InventoryCountJpaEntity + adapters
  Sync: InventorySessionSyncHandler, InventoryCountSyncHandler,
        InventorySessionDeltaProvider, InventoryCountDeltaProvider
  DDL: inventory_sessions, inventory_counts tables + indexes

FLUTTER (features/inventory/ — COMPLETE from 6.1/6.2/6.2a):
  Drift v21: InventorySessions, InventoryCounts tables
  Domain: InventorySessionModel, InventoryCountModel, InventoryProductRowModel,
          InventoryScope enum
  Repos: InventorySessionRepository, InventoryCountRepository (Backend-First)
  Providers: activeSessionProvider, sessionHistoryProvider, countingProductsProvider,
             inventoryCountingNotifier, countingFilterProvider, searchProvider,
             saveCountNotifier, quickAddProductNotifier
  UI: InventoryLaunchPage, InventoryCountingPage, InventoryRow (UX32),
      InventoryConfigBottomSheet, ActiveSessionBanner, SessionHistoryCard,
      QuickAddProductSheet
  Router: /inventory → InventoryLaunchPage
          /inventory/counting/:sessionId → InventoryCountingPage
```

### What Story 6.3 Adds

```
┌──────────────────────────────────────────────────────────────────────
│ STORY 6.3 — GAP ANALYSIS REPORT & INVENTORY REPORT                  │
│                                                                      │
│ BACKEND (inventory/counting/ module extension):                      │
│                                                                      │
│   Domain models:                                                     │
│     InventoryGapReport (value object — full report aggregate)       │
│     InventoryGapSummary (record — totals: counted, gaps, values)    │
│     InventoryGapRow (record — one product gap with XAF value)       │
│                                                                      │
│   Domain service:                                                    │
│     InventoryReportTextFormatter — WhatsApp message builder         │
│       (GoF Strategy: same emoji-rich format as day-close reports)   │
│                                                                      │
│   Ports:                                                             │
│     in/  → GenerateGapReportUseCase (+ Query)                       │
│     in/  → GetInventoryReportHistoryUseCase (+ Query)               │
│                                                                      │
│   Application services:                                              │
│     GenerateGapReportService (GoF Builder — constructs report)      │
│     GetInventoryReportHistoryService                                │
│                                                                      │
│   Adapter REST:                                                      │
│     GET  /api/v1/inventory/sessions/{id}/gap-report                 │
│     GET  /api/v1/inventory/sessions/{id}/gap-report/text            │
│                                                                      │
│   Adapter DTO:                                                       │
│     InventoryGapReportResponseDto                                   │
│     InventoryGapRowDto                                              │
│                                                                      │
│ FLUTTER:                                                             │
│   Domain: InventoryGapReportModel (Freezed), InventoryGapRowModel   │
│   Data: Local gap report generator (from Drift data)                │
│   Providers: gapReportProvider, gapReportTextProvider                │
│   Pages: InventoryGapReportPage (THE report page)                   │
│   Widgets: GapReportSummaryHeader, GapSectionList,                  │
│            ConcordantCollapseSection, GapRowTile                    │
│   WhatsApp share: url_launcher deeplink                             │
│   Export: text file via share_plus (Free), PDF placeholder (Payant) │
│   Router: /inventory/gap-report/:sessionId → InventoryGapReportPage │
│   Wire: InventoryCountingPage "Valider" → navigates to gap report   │
└──────────────────────────────────────────────────────────────────────
```

---

## GoF Pattern Analysis (MANDATORY)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Report export format: text (Plan Free) vs PDF (Plan Payant — Growth feature, placeholder only). WhatsApp message format must match the existing day-close emoji-rich pattern. The report can be generated pre-validation or post-validation. Future: Excel export, comparison between sessions, anomaly detection algorithms. |
| What might change in the future? | Additional export formats (Excel, CSV). Anomaly detection (flagging suspected theft patterns). Comparison with previous inventory sessions. Custom report templates. PDF styling and branding for paid plans. Multi-currency support. |
| Which GoF patterns apply? | **Builder** — `InventoryGapReportBuilder` constructs the report step by step: loads counts → separates into concordant/surplus/shortage → calculates XAF values → builds summary → assembles final report. **Strategy** — `InventoryReportTextFormatter` generates the WhatsApp message text following the same emoji-rich pattern as `ManualReportStrategy`/`AutoReportStrategy` from Story 4.4. Also `ReportExportStrategy` interface for text vs PDF (extensible). **Observer** — `InventoryReportGeneratedEvent` → `AuditEventListener` for audit trail. **Template Method** — The report generation follows a fixed sequence of steps (load → compute → build → format), extensible for future report types. |
| How does it enable Open/Closed? | Adding a new export format = implement new `ReportExportStrategy`. Adding a new report section (e.g., anomaly detection) = extend `InventoryGapReportBuilder` without modifying existing sections. Adding a new text format = new `InventoryReportTextFormatter` implementation. |
| Where is the pattern applied? | **Builder** → `inventory/counting/domain/service/InventoryGapReportBuilder.java`. **Strategy** → `inventory/counting/domain/service/InventoryReportTextFormatter.java` (WhatsApp text). **Observer** → `InventoryReportGeneratedEvent` in `inventory/counting/domain/event/`. **Template Method** → `GenerateGapReportService.java` fixed steps. |

---

## Acceptance Criteria

### AC1 — Gap report generated from counted data on "Valider l'inventaire"

- **Given** Simon is on the `InventoryCountingPage` with all products counted (progress = 100%)
- **When** he taps "Valider l'inventaire"
- **Then** the app navigates to `/inventory/gap-report/{sessionId}` displaying the `InventoryGapReportPage`
- **And** the gap report is generated **locally** from Drift data (`inventory_counts` + `products` + `stock_levels`):
  - **Summary header** at the top:
    - Total produits comptés: count of all `InventoryCount` records with `physical != null`
    - Produits avec écarts: count of records where `physical != theoretical`
    - Valeur totale des manquants: `Σ(|écart| × product.price)` for all negative gaps, displayed in XAF (e.g., "47 500 FCFA")
    - Valeur totale des surplus: same formula for positive gaps
  - **Section "Concordants"** (collapsed by default): count-only badge "✅ 142 concordants" — expandable to show full list
  - **Section "Surplus" ⚠️**: list of products with `écart > 0` — showing: name, variant, theoretical, physical, `+delta`, XAF value
  - **Section "Manquants" 🔴**: list of products with `écart < 0` — sorted by XAF value (largest loss first), showing: name, variant, theoretical, physical, `−delta`, XAF value
- **And** each row in the surplus/shortage sections reuses the `InventoryRow` layout pattern (photo/initials + name + theoretical + physical + écart badge) in **read-only mode** (no TextFormField, values displayed as text)
- **And** the report is also fetched from the backend (`GET /api/v1/inventory/sessions/{sessionId}/gap-report`) when online — serving as a cross-check and enabling multi-device access

### AC2 — Product detail on row tap

- **Given** Simon is viewing the gap report
- **When** he taps on any product row in the surplus or shortage section
- **Then** a bottom sheet appears showing product detail:
  - Photo (full-width if available, initials avatar if not)
  - Product name + SKU
  - Category name
  - Variant label (if applicable)
  - Theoretical stock ("Keevo") vs Physical count ("Réel") vs Écart
  - XAF value of the gap: `|écart| × unitPrice`
  - Link to stock movement history for this product (navigates to existing stock history if available, or shows "Historique non disponible" placeholder)
- **And** the bottom sheet is dismissible by swiping down
- **And** the detail loads fully offline from local Drift data

### AC3 — "Télécharger le rapport" (OWNER only)

- **Given** Simon (OWNER) is on the gap report page
- **When** he taps "Télécharger le rapport"
- **Then** on **Plan Free**: a formatted text summary is generated and shared via `share_plus` (system share sheet — user chooses destination: files, email, notes, etc.):
  ```
  Rapport d'inventaire — Boutique Centrale
  Date : 27 mars 2026 — 16h45
  Réalisé par : Simon

  Résumé :
  ✅ Concordants : 142 produits
  ⚠️ Surplus : 3 produits (+15 000 FCFA)
  🔴 Manquants : 7 produits (−47 500 FCFA)

  Détail des manquants :
  • Robe M Rouge : Keevo 10 → Réel 5 (−5) — −25 000 FCFA
  • Jeans L : Keevo 8 → Réel 6 (−2) — −12 000 FCFA
  ...

  Détail des surplus :
  • T-Shirt XL Blanc : Keevo 5 → Réel 8 (+3) — +9 000 FCFA
  ...
  ```
- **And** on **Plan Payant**: placeholder `SnackBar("Export PDF disponible avec le plan Premium")` — PDF generation is a Growth feature deferred
- **And** the "Télécharger" button is **only visible to OWNER** (check role from auth provider). EMPLOYEE does not see this button
- **And** the button shows a `CircularProgressIndicator` during text generation (instant but consistent pattern)

### AC4 — "Partager sur WhatsApp" (emoji-rich compact report)

- **Given** Simon is on the gap report page
- **When** he taps "Partager sur WhatsApp"
- **Then** the app generates an emoji-rich compact text message matching the day-close report style:
  ```
  📋 Rapport d'inventaire — Boutique Centrale
  📅 27 mars 2026 — 16h45
  👤 Simon

  ✅ Concordants : 142 produits
  ⚠️ Surplus : 3 produits (+15 000 FCFA)
  🔴 Manquants : 7 produits (−47 500 FCFA)

  Top manques :
  • Robe M Rouge : −5 unités (−25 000 FCFA)
  • Jeans L : −2 unités (−12 000 FCFA)
  • Polo S Vert : −1 unité (−5 500 FCFA)
  ```
- **And** opens `url_launcher` with `https://wa.me/?text={urlEncodedMessage}` — the user selects the WhatsApp contact to send to
- **And** the WhatsApp button is visible to **both OWNER and EMPLOYEE** (sharing the report is not restricted)
- **And** the report can be shared **even if the inventory is not yet validated** (pre-validation review sharing) — this is explicitly required by the epic AC
- **And** the same formatted text is also generated by the backend (`GET /api/v1/inventory/sessions/{id}/gap-report/text`) for potential server-side WhatsApp sending (future — via `WhatsAppPort`)

### AC5 — Past inventory reports viewable offline

- **Given** Simon navigates to Stock > Inventaire (the `InventoryLaunchPage`)
- **When** he views past sessions with status `VALIDATED`
- **Then** each session card in the history list now shows additional report data:
  - Total gaps count
  - Total shortage value (XAF)
  - A "Voir rapport" action (only for VALIDATED sessions)
- **And** tapping "Voir rapport" on a VALIDATED session navigates to `/inventory/gap-report/{sessionId}` which generates the report from local Drift data (same as AC1)
- **And** past reports are fully viewable **offline** from local Drift data
- **And** for `CANCELLED` sessions, the "Voir rapport" action is not shown

### AC6 — "Appliquer les ajustements" button (Story 6.4 placeholder)

- **Given** Simon is on the gap report page for an `IN_PROGRESS` session
- **When** the page loads and there are products with gaps (`écart ≠ 0`)
- **Then** a prominent button "Appliquer les ajustements" is visible at the bottom of the page
- **And** this button is **disabled** with tooltip "Disponible dans une prochaine mise à jour" — Story 6.4 will implement the actual adjustment logic
- **And** the button is only visible for `IN_PROGRESS` sessions (not for `VALIDATED` or `CANCELLED` sessions — for VALIDATED, the adjustments have already been applied)

---

## Technical Requirements

### Backend — Spring Boot (Hexagonal Architecture, `inventory/counting/` module extension)

**Package**: `com.keevo.inventory.counting` — all new classes in this existing module.

No DDL changes required — uses existing `inventory_sessions`, `inventory_counts`, and `products` tables.

#### New Domain Models

```
domain/
├── model/
│   ├── InventoryGapReport.java       # Value object — complete report aggregate
│   ├── InventoryGapSummary.java      # Record — aggregate totals
│   └── InventoryGapRow.java          # Record — one product gap with XAF value
├── service/
│   ├── InventoryGapReportBuilder.java     # GoF Builder — constructs report
│   └── InventoryReportTextFormatter.java  # Strategy — WhatsApp text builder
├── event/
│   └── InventoryReportGeneratedEvent.java # Observer — audit
└── port/
    └── in/
        ├── GenerateGapReportQuery.java
        └── GenerateGapReportUseCase.java

application/service/
└── GenerateGapReportService.java     # Template Method — fixed steps

adapter/
├── in/rest/
│   ├── InventoryReportController.java  # NEW controller for report endpoints
│   └── dto/
│       ├── InventoryGapReportResponseDto.java
│       └── InventoryGapRowDto.java
```

**`InventoryGapReport.java`** (value object — complete report):
```java
public class InventoryGapReport {
    private final UUID sessionId;
    private final UUID storeId;
    private final String storeName;
    private final InventoryScope scope;
    private final InventoryGapSummary summary;
    private final List<InventoryGapRow> concordantRows;   // écart == 0
    private final List<InventoryGapRow> surplusRows;      // écart > 0
    private final List<InventoryGapRow> shortageRows;     // écart < 0, sorted by |value| desc
    private final UUID generatedBy;
    private final Instant generatedAt;

    // Constructor, getters
}
```

**`InventoryGapSummary.java`** (record):
```java
public record InventoryGapSummary(
    int totalCounted,           // products with physical != null
    int totalConcordant,        // products with écart == 0
    int totalSurplus,           // products with écart > 0
    int totalShortage,          // products with écart < 0
    long totalSurplusValueXaf,  // Σ(écart × unitPrice) for surplus
    long totalShortageValueXaf  // Σ(|écart| × unitPrice) for shortage (positive number)
) {}
```

**`InventoryGapRow.java`** (record):
```java
public record InventoryGapRow(
    UUID productId,
    String productName,
    String sku,
    String photoUrl,
    String categoryName,
    UUID variantId,
    String variantLabel,
    int theoretical,
    int physical,
    int ecart,                  // physical - theoretical
    int unitPriceXaf,           // product.price (selling price in XAF)
    long gapValueXaf            // |écart| × unitPriceXaf (always positive)
) {
    public boolean isSurplus() { return ecart > 0; }
    public boolean isShortage() { return ecart < 0; }
    public boolean isConcordant() { return ecart == 0; }
}
```

**`InventoryGapReportBuilder.java`** (GoF Builder):
```java
public class InventoryGapReportBuilder {

    private UUID sessionId;
    private UUID storeId;
    private String storeName;
    private InventoryScope scope;
    private UUID generatedBy;
    private final List<InventoryGapRow> allRows = new ArrayList<>();

    public InventoryGapReportBuilder sessionId(UUID id) { ... return this; }
    public InventoryGapReportBuilder storeId(UUID id) { ... return this; }
    public InventoryGapReportBuilder storeName(String name) { ... return this; }
    public InventoryGapReportBuilder scope(InventoryScope s) { ... return this; }
    public InventoryGapReportBuilder generatedBy(UUID actor) { ... return this; }

    public InventoryGapReportBuilder addRow(InventoryCount count, int unitPriceXaf) {
        int ecart = count.getEcart(); // reuse existing domain method (physical - theoretical)
        long gapValue = (long) Math.abs(ecart) * unitPriceXaf;
        allRows.add(new InventoryGapRow(
            count.getProductId(), count.getProductName(), null /*sku enriched later*/,
            null /*photoUrl enriched later*/, null /*categoryName enriched later*/,
            count.getVariantId(), count.getVariantLabel(),
            count.getTheoretical(), count.getPhysical(), ecart,
            unitPriceXaf, gapValue
        ));
        return this;
    }

    public InventoryGapReportBuilder enrichProductDetails(Map<UUID, ProductDetails> details) {
        // Enrich sku, photoUrl, and categoryName from product data
        ...
        return this;
    }

    public InventoryGapReport build() {
        List<InventoryGapRow> concordant = allRows.stream()
            .filter(InventoryGapRow::isConcordant).toList();
        List<InventoryGapRow> surplus = allRows.stream()
            .filter(InventoryGapRow::isSurplus)
            .sorted(Comparator.comparingLong(InventoryGapRow::gapValueXaf).reversed())
            .toList();
        List<InventoryGapRow> shortage = allRows.stream()
            .filter(InventoryGapRow::isShortage)
            .sorted(Comparator.comparingLong(InventoryGapRow::gapValueXaf).reversed())
            .toList();

        InventoryGapSummary summary = new InventoryGapSummary(
            allRows.size(),
            concordant.size(),
            surplus.size(),
            shortage.size(),
            surplus.stream().mapToLong(InventoryGapRow::gapValueXaf).sum(),
            shortage.stream().mapToLong(InventoryGapRow::gapValueXaf).sum()
        );

        return new InventoryGapReport(sessionId, storeId, storeName, scope,
            summary, concordant, surplus, shortage, generatedBy, Instant.now());
    }
}
```

**`InventoryReportTextFormatter.java`** (GoF Strategy — WhatsApp text):
```java
public class InventoryReportTextFormatter {

    private static final ZoneId WAT_ZONE = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter FR_DATE =
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter TIME_FORMAT =
        DateTimeFormatter.ofPattern("HH'h'mm");

    /**
     * Generates emoji-rich compact WhatsApp text — same style as day-close reports.
     * Max 5 "Top manques" entries to keep message compact.
     */
    public String formatWhatsApp(InventoryGapReport report, String actorName) {
        StringBuilder sb = new StringBuilder();
        String date = report.getGeneratedAt().atZone(WAT_ZONE).format(FR_DATE);
        String time = report.getGeneratedAt().atZone(WAT_ZONE).format(TIME_FORMAT);

        sb.append("📋 Rapport d'inventaire — ").append(report.getStoreName()).append("\n");
        sb.append("📅 ").append(date).append(" — ").append(time).append("\n");
        sb.append("👤 ").append(actorName).append("\n\n");

        sb.append("✅ Concordants : ").append(report.getSummary().totalConcordant())
          .append(" produits\n");

        if (report.getSummary().totalSurplus() > 0) {
            sb.append("⚠️ Surplus : ").append(report.getSummary().totalSurplus())
              .append(" produits (+").append(formatXaf(report.getSummary().totalSurplusValueXaf()))
              .append(")\n");
        }

        if (report.getSummary().totalShortage() > 0) {
            sb.append("🔴 Manquants : ").append(report.getSummary().totalShortage())
              .append(" produits (−").append(formatXaf(report.getSummary().totalShortageValueXaf()))
              .append(")\n");
        }

        // Top 5 shortages
        if (!report.getShortageRows().isEmpty()) {
            sb.append("\nTop manques :\n");
            report.getShortageRows().stream().limit(5).forEach(row -> {
                sb.append("• ").append(row.productName());
                if (row.variantLabel() != null) sb.append(" ").append(row.variantLabel());
                sb.append(" : ").append(row.ecart()).append(" unité")
                  .append(Math.abs(row.ecart()) > 1 ? "s" : "")
                  .append(" (−").append(formatXaf(row.gapValueXaf())).append(")\n");
            });
        }

        return sb.toString();
    }

    /**
     * Generates detailed text export (for "Télécharger" on Free plan).
     */
    public String formatDetailedText(InventoryGapReport report, String actorName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rapport d'inventaire — ").append(report.getStoreName()).append("\n");
        sb.append("Date : ...").append("\n");
        sb.append("Réalisé par : ").append(actorName).append("\n\n");
        // Full detailed format with all sections...
        return sb.toString();
    }

    private String formatXaf(long amount) {
        return String.format("%,d FCFA", amount).replace(',', ' ');
    }
}
```

**`GenerateGapReportQuery.java`** (pure Java Record):
```java
public record GenerateGapReportQuery(UUID sessionId, UUID actorId) {}
```

**`GenerateGapReportUseCase.java`**:
```java
public interface GenerateGapReportUseCase {
    InventoryGapReport execute(GenerateGapReportQuery query);
}
```

**`GenerateGapReportService.java`** (Template Method — fixed steps):
```java
@Service
@Transactional(readOnly = true)
public class GenerateGapReportService implements GenerateGapReportUseCase {

    private final InventorySessionRepository sessionRepo;
    private final InventoryCountRepository countRepo;
    private final ProductRepository productRepo;
    private final StoreRepository storeRepo;

    @Override
    public InventoryGapReport execute(GenerateGapReportQuery query) {
        // Step 1: Load session
        InventorySession session = sessionRepo.findById(query.sessionId())
            .orElseThrow(() -> new DomainException(ErrorCode.INVENTORY_SESSION_NOT_FOUND));

        // Step 2: Load all counts for the session
        List<InventoryCount> counts = countRepo.findBySessionId(session.getId());

        // Step 3: Load product details for price enrichment
        List<UUID> productIds = counts.stream()
            .map(InventoryCount::getProductId).distinct().toList();
        Map<UUID, Product> productsMap = productRepo.findAllByIds(productIds).stream()
            .collect(Collectors.toMap(Product::getId, Function.identity()));

        // Step 4: Get store name
        String storeName = storeRepo.findById(session.getStoreId())
            .map(Store::name).orElse("Boutique inconnue");

        // Step 5: Build report using Builder pattern
        InventoryGapReportBuilder builder = new InventoryGapReportBuilder()
            .sessionId(session.getId())
            .storeId(session.getStoreId())
            .storeName(storeName)
            .scope(session.getScope())
            .generatedBy(query.actorId());

        for (InventoryCount count : counts) {
            if (!count.isCounted()) continue; // skip uncounted (should not happen at 100%)
            Product product = productsMap.get(count.getProductId());
            int unitPrice = product != null ? product.getPriceValue() : 0;
            builder.addRow(count, unitPrice);
        }

        // Step 5b: Enrich with product details (sku, photoUrl)
        Map<UUID, ProductDetails> details = productsMap.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                e -> new ProductDetails(e.getValue().getSku(), e.getValue().getPhotoUrl(),
                    e.getValue().getCategoryName())));
        builder.enrichProductDetails(details);

        return builder.build();
    }

    record ProductDetails(String sku, String photoUrl, String categoryName) {}
}
```

> ⚠️ **ProductRepository.findAllByIds()** was added in Story 6.2. It already exists. Do NOT re-create.

> ⚠️ **StoreRepository.findById()** already exists from Story 3.1. REUSE.

> ⚠️ **Product.getPriceValue()** returns the selling price as int (XAF). Already exists from Story 2.1/2.2. REUSE.

**REST Endpoints** — `InventoryReportController.java` (NEW controller, separate from count/session):
```java
@RestController
@RequestMapping("/api/v1/inventory/sessions/{sessionId}")
@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")
@Tag(name = "Inventory Report", description = "Inventory gap report generation and export")
public class InventoryReportController {

    private final GenerateGapReportUseCase generateGapReportUseCase;
    private final InventoryReportTextFormatter textFormatter;
    private final JwtTokenProvider jwtTokenProvider;

    @Operation(summary = "Generate gap analysis report for an inventory session")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Gap report generated"),
        @ApiResponse(responseCode = "404", description = "Session not found")
    })
    @GetMapping("/gap-report")
    public ResponseEntity<ApiResponseWrapper<InventoryGapReportResponseDto>> getGapReport(
            @PathVariable UUID sessionId) {
        UUID actorId = extractActorId();
        InventoryGapReport report = generateGapReportUseCase.execute(
            new GenerateGapReportQuery(sessionId, actorId));
        return ResponseEntity.ok(ApiResponseWrapper.ok(
            InventoryGapReportResponseDto.fromDomain(report)));
    }

    @Operation(summary = "Get WhatsApp-formatted text report")
    @GetMapping("/gap-report/text")
    public ResponseEntity<ApiResponseWrapper<String>> getGapReportText(
            @PathVariable UUID sessionId) {
        UUID actorId = extractActorId();
        InventoryGapReport report = generateGapReportUseCase.execute(
            new GenerateGapReportQuery(sessionId, actorId));
        String actorName = extractActorName(); // from JWT claims
        String text = textFormatter.formatWhatsApp(report, actorName);
        return ResponseEntity.ok(ApiResponseWrapper.ok(text));
    }

    private UUID extractActorId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(auth.getName());
    }

    private String extractActorName() {
        // Extract from JWT or return default
        return "Utilisateur";
    }
}
```

**`InventoryGapReportResponseDto.java`**:
```java
public record InventoryGapReportResponseDto(
    UUID sessionId,
    UUID storeId,
    String storeName,
    String scope,
    InventoryGapSummaryDto summary,
    List<InventoryGapRowDto> concordantRows,
    List<InventoryGapRowDto> surplusRows,
    List<InventoryGapRowDto> shortageRows,
    Instant generatedAt
) {
    public static InventoryGapReportResponseDto fromDomain(InventoryGapReport report) { ... }

    public record InventoryGapSummaryDto(
        int totalCounted, int totalConcordant, int totalSurplus, int totalShortage,
        long totalSurplusValueXaf, long totalShortageValueXaf
    ) {}
}
```

**`InventoryGapRowDto.java`**:
```java
public record InventoryGapRowDto(
    UUID productId, String productName, String sku, String photoUrl,
    String categoryName, UUID variantId, String variantLabel,
    int theoretical, int physical, int ecart,
    int unitPriceXaf, long gapValueXaf
) {
    public static InventoryGapRowDto fromDomain(InventoryGapRow row) { ... }
}
```

**Event** — `InventoryReportGeneratedEvent.java`:
```java
public record InventoryReportGeneratedEvent(
    UUID sessionId, UUID actorId, String tenantId, Instant occurredAt
) {}
```

Register in `AuditEventListener`:
```java
@EventListener
public void on(InventoryReportGeneratedEvent event) {
    auditPort.record("INVENTORY_REPORT_GENERATED", "InventorySession",
        event.sessionId(), event.actorId(), event.tenantId(), event.occurredAt());
}
```

#### Test Strategy (Backend)

| Test class | Type | Count | Verifications |
|---|---|---|---|
| `InventoryGapReportBuilderTest` | Unit | 6 | Empty report, all concordant, mixed gaps, shortage sorted by value desc, surplus XAF calculated, summary totals correct |
| `InventoryReportTextFormatterTest` | Unit | 4 | WhatsApp format matches day-close emoji style, top 5 shortages only, XAF formatting with spaces, empty shortages handled |
| `GenerateGapReportServiceTest` | Unit | 5 | Happy path with mixed gaps, session not found, empty counts, uncounted products skipped, product price=0 edge case |
| `InventoryReportControllerTest` | @WebMvcTest | 4 | GET gap-report 200, GET gap-report/text 200, 404 session not found, RBAC OWNER+EMPLOYEE access |
| `InventoryGapRowTest` | Unit | 3 | isSurplus/isShortage/isConcordant predicates |

**Total new backend tests**: ~22

---

### Frontend — Flutter (Clean Architecture + Riverpod)

**No Drift schema change** — report is generated on-the-fly from existing `inventory_counts`, `products`, and `stock_levels` Drift tables.

#### New Domain Models

**`inventory_gap_report_model.dart`** (Freezed):
```dart
@freezed
class InventoryGapReportModel with _$InventoryGapReportModel {
  const InventoryGapReportModel._();
  const factory InventoryGapReportModel({
    required String sessionId,
    required String storeId,
    required String storeName,
    required String scope,
    required InventoryGapSummaryModel summary,
    required List<InventoryGapRowModel> concordantRows,
    required List<InventoryGapRowModel> surplusRows,
    required List<InventoryGapRowModel> shortageRows,
    required DateTime generatedAt,
  }) = _InventoryGapReportModel;

  factory InventoryGapReportModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryGapReportModelFromJson(json);
}

@freezed
class InventoryGapSummaryModel with _$InventoryGapSummaryModel {
  const factory InventoryGapSummaryModel({
    required int totalCounted,
    required int totalConcordant,
    required int totalSurplus,
    required int totalShortage,
    required int totalSurplusValueXaf,
    required int totalShortageValueXaf,
  }) = _InventoryGapSummaryModel;

  factory InventoryGapSummaryModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryGapSummaryModelFromJson(json);
}
```

**`inventory_gap_row_model.dart`** (Freezed):
```dart
@freezed
class InventoryGapRowModel with _$InventoryGapRowModel {
  const InventoryGapRowModel._();
  const factory InventoryGapRowModel({
    required String productId,
    required String productName,
    String? sku,
    String? photoUrl,
    String? categoryName,
    String? variantId,
    String? variantLabel,
    required int theoretical,
    required int physical,
    required int ecart,
    required int unitPriceXaf,
    required int gapValueXaf,
  }) = _InventoryGapRowModel;

  bool get isSurplus => ecart > 0;
  bool get isShortage => ecart < 0;
  bool get isConcordant => ecart == 0;

  factory InventoryGapRowModel.fromJson(Map<String, dynamic> json) =>
      _$InventoryGapRowModelFromJson(json);
}
```

#### Feature Structure (new files)

```
lib/features/inventory/
├── domain/
│   └── model/
│       ├── inventory_gap_report_model.dart    # Freezed — full report
│       └── inventory_gap_row_model.dart       # Freezed — single gap row
├── data/
│   └── datasource/
│       └── local_gap_report_datasource.dart   # Generates report from Drift data
├── presentation/
│   ├── provider/
│   │   └── gap_report_provider.dart           # Riverpod providers
│   ├── page/
│   │   └── inventory_gap_report_page.dart     # THE report page
│   └── widget/
│       ├── gap_report_summary_header.dart     # Summary totals card
│       ├── gap_section_list.dart              # Surplus/shortage section list
│       ├── concordant_collapse_section.dart   # Collapsed concordant count
│       ├── gap_row_tile.dart                  # Read-only row (reuses InventoryRow pattern)
│       └── product_detail_bottom_sheet.dart   # Product detail on tap
```

#### Local Gap Report Generator (Offline-First)

**`local_gap_report_datasource.dart`**:
```dart
class LocalGapReportDataSource {
  final AppDatabase _db;

  /// Generates the gap report locally from Drift inventory_counts + products tables.
  /// Works 100% offline.
  Future<InventoryGapReportModel> generate(String sessionId) async {
    // 1. Load session from InventorySessions table
    final session = await (_db.select(_db.inventorySessions)
      ..where((t) => t.id.equals(sessionId))).getSingleOrNull();
    if (session == null) throw Exception('Session not found');

    // 2. Load all counts for this session
    final counts = await (_db.select(_db.inventoryCounts)
      ..where((t) => t.sessionId.equals(sessionId))).get();

    // 3. Load product details (for price + photo + sku + category)
    final productIds = counts.map((c) => c.productId).toSet().toList();
    final products = await (_db.select(_db.products)
      ..where((t) => t.id.isIn(productIds))).get();
    final productsMap = {for (var p in products) p.id: p};

    // 4. Load store name
    final store = await (_db.select(_db.stores)
      ..where((t) => t.id.equals(session.storeId))).getSingleOrNull();
    final storeName = store?.name ?? 'Boutique inconnue';

    // 5. Build report rows
    final allRows = <InventoryGapRowModel>[];
    for (final count in counts) {
      if (count.physical == null) continue;
      final product = productsMap[count.productId];
      final unitPrice = product?.price ?? 0;
      final ecart = count.physical! - count.theoretical;
      allRows.add(InventoryGapRowModel(
        productId: count.productId,
        productName: count.productName,
        sku: product?.sku,
        photoUrl: product?.photoUrl,
        variantId: count.variantId,
        variantLabel: count.variantLabel,
        theoretical: count.theoretical,
        physical: count.physical!,
        ecart: ecart,
        unitPriceXaf: unitPrice,
        gapValueXaf: ecart.abs() * unitPrice,
      ));
    }

    // 6. Split into sections
    final concordant = allRows.where((r) => r.isConcordant).toList();
    final surplus = allRows.where((r) => r.isSurplus).toList()
      ..sort((a, b) => b.gapValueXaf.compareTo(a.gapValueXaf));
    final shortage = allRows.where((r) => r.isShortage).toList()
      ..sort((a, b) => b.gapValueXaf.compareTo(a.gapValueXaf));

    return InventoryGapReportModel(
      sessionId: sessionId,
      storeId: session.storeId,
      storeName: storeName,
      scope: session.scope,
      summary: InventoryGapSummaryModel(
        totalCounted: allRows.length,
        totalConcordant: concordant.length,
        totalSurplus: surplus.length,
        totalShortage: shortage.length,
        totalSurplusValueXaf: surplus.fold(0, (sum, r) => sum + r.gapValueXaf),
        totalShortageValueXaf: shortage.fold(0, (sum, r) => sum + r.gapValueXaf),
      ),
      concordantRows: concordant,
      surplusRows: surplus,
      shortageRows: shortage,
      generatedAt: DateTime.now(),
    );
  }
}
```

#### Providers

```dart
// lib/features/inventory/presentation/provider/gap_report_provider.dart

/// Generates gap report locally (Drift) for immediate offline display.
/// Also fetches from backend when online for cross-check.
@riverpod
Future<InventoryGapReportModel> gapReport(Ref ref, String sessionId) async {
  // Generate locally first (instant, offline)
  final localDs = ref.read(localGapReportDataSourceProvider);
  final report = await localDs.generate(sessionId);

  // Also fetch from backend when online (fire-and-forget, for audit)
  try {
    final dio = ref.read(authenticatedDioProvider);
    await dio.get('/api/v1/inventory/sessions/$sessionId/gap-report');
    // Backend logs InventoryReportGeneratedEvent → audit trail
  } catch (_) {
    // Offline or error — local report sufficient
  }

  return report;
}

/// Generates emoji-rich WhatsApp text locally (same formatter as backend).
@riverpod
String gapReportWhatsAppText(Ref ref, InventoryGapReportModel report, String actorName) {
  return _formatWhatsApp(report, actorName);
}

/// Generates detailed text for export/download.
@riverpod
String gapReportDetailedText(Ref ref, InventoryGapReportModel report, String actorName) {
  return _formatDetailedText(report, actorName);
}
```

#### `InventoryGapReportPage` — Main Report Page

```dart
class InventoryGapReportPage extends ConsumerWidget {
  final String sessionId;
  const InventoryGapReportPage({required this.sessionId, super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reportAsync = ref.watch(gapReportProvider(sessionId));
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Rapport d\'inventaire'),
        actions: [
          // WhatsApp share button (AC4 — both OWNER and EMPLOYEE)
          IconButton(
            icon: const Icon(Icons.share),
            tooltip: 'Partager sur WhatsApp',
            onPressed: () => _shareWhatsApp(context, ref),
          ),
          // Download button (AC3 — OWNER only)
          if (ref.read(currentUserRoleProvider) == 'OWNER')
            IconButton(
              icon: const Icon(Icons.download),
              tooltip: 'Télécharger le rapport',
              onPressed: () => _downloadReport(context, ref),
            ),
        ],
      ),
      body: reportAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, st) => Center(child: Text('Erreur: $e')),
        data: (report) => _buildReportBody(context, ref, report, theme),
      ),
    );
  }

  Widget _buildReportBody(BuildContext ctx, WidgetRef ref,
      InventoryGapReportModel report, ThemeData theme) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // 1. Summary header (AC1)
        GapReportSummaryHeader(summary: report.summary),
        const SizedBox(height: 16),

        // 2. Concordant section — collapsed (AC1)
        ConcordantCollapseSection(count: report.summary.totalConcordant),
        const SizedBox(height: 16),

        // 3. Shortage section — sorted by value desc (AC1)
        if (report.shortageRows.isNotEmpty)
          GapSectionList(
            title: '🔴 Manquants',
            rows: report.shortageRows,
            titleColor: const Color(0xFFFA5252),
            onRowTap: (row) => _showProductDetail(ctx, row),
          ),
        const SizedBox(height: 16),

        // 4. Surplus section (AC1)
        if (report.surplusRows.isNotEmpty)
          GapSectionList(
            title: '⚠️ Surplus',
            rows: report.surplusRows,
            titleColor: const Color(0xFFFCC419),
            onRowTap: (row) => _showProductDetail(ctx, row),
          ),
        const SizedBox(height: 24),

        // 5. "Appliquer les ajustements" placeholder (AC6)
        if (_isSessionInProgress(ref))
          FilledButton.icon(
            onPressed: null, // disabled — Story 6.4
            icon: const Icon(Icons.check_circle_outline),
            label: const Text('Appliquer les ajustements'),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(48),
            ),
          ),
        if (_isSessionInProgress(ref))
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(
              'Disponible dans une prochaine mise à jour',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
          ),
      ],
    );
  }
}
```

#### WhatsApp Text Formatter (Flutter-side — mirrors backend)

```dart
/// Formats the inventory gap report as emoji-rich WhatsApp text.
/// Mirrors InventoryReportTextFormatter.java on the backend.
String formatWhatsAppReport(InventoryGapReportModel report, String actorName) {
  final buf = StringBuffer();
  final now = report.generatedAt;
  // French date formatting
  final date = '${now.day} ${_frenchMonth(now.month)} ${now.year}';
  final time = '${now.hour}h${now.minute.toString().padLeft(2, '0')}';

  buf.writeln('📋 Rapport d\'inventaire — ${report.storeName}');
  buf.writeln('📅 $date — $time');
  buf.writeln('👤 $actorName');
  buf.writeln();
  buf.writeln('✅ Concordants : ${report.summary.totalConcordant} produits');

  if (report.summary.totalSurplus > 0) {
    buf.writeln('⚠️ Surplus : ${report.summary.totalSurplus} produits '
        '(+${_formatXaf(report.summary.totalSurplusValueXaf)})');
  }
  if (report.summary.totalShortage > 0) {
    buf.writeln('🔴 Manquants : ${report.summary.totalShortage} produits '
        '(−${_formatXaf(report.summary.totalShortageValueXaf)})');
  }

  if (report.shortageRows.isNotEmpty) {
    buf.writeln();
    buf.writeln('Top manques :');
    for (final row in report.shortageRows.take(5)) {
      final label = row.variantLabel != null
          ? '${row.productName} ${row.variantLabel}'
          : row.productName;
      final units = row.ecart.abs() > 1 ? 'unités' : 'unité';
      buf.writeln('• $label : ${row.ecart} $units (−${_formatXaf(row.gapValueXaf)})');
    }
  }

  return buf.toString();
}

String _formatXaf(int amount) {
  // Format with space as thousands separator
  final str = amount.toString();
  final buf = StringBuffer();
  for (var i = 0; i < str.length; i++) {
    if (i > 0 && (str.length - i) % 3 == 0) buf.write(' ');
    buf.write(str[i]);
  }
  return '${buf.toString()} FCFA';
}

String _frenchMonth(int month) {
  const months = ['', 'janvier', 'février', 'mars', 'avril', 'mai', 'juin',
      'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
  return months[month];
}
```

#### Router Update

```dart
// In app_router.dart — add after /inventory/counting/:sessionId
GoRoute(
  path: '/inventory/gap-report/:sessionId',
  builder: (context, state) {
    final sessionId = state.pathParameters['sessionId']!;
    return InventoryGapReportPage(sessionId: sessionId);
  },
),
```

#### Existing Code Modifications

**`inventory_counting_page.dart`** — Replace SnackBar placeholder with navigation:
```dart
// BEFORE (current):
onValidate: progress >= 1.0
    ? () {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('6.3 — Rapport d\'inventaire à venir')),
        );
      }
    : null,

// AFTER:
onValidate: progress >= 1.0
    ? () => context.push('/inventory/gap-report/${widget.sessionId}')
    : null,
```

**`session_history_card.dart`** — Add report data for VALIDATED sessions (AC5):
- Show gap count + shortage value on VALIDATED session cards
- Add "Voir rapport" action button that navigates to gap report page

---

## UX / Design Requirements

> **Source**: UX Design Specification — Flow 10 (Inventaire Assisté), UX9 (Inventory Row), Component Strategy P3
> **Colors**: Same tokens as Stories 6.1/6.2

### InventoryGapReportPage Layout

**AppBar:**
- Title: "Rapport d'inventaire" (standard inner page, no gradient)
- Leading: back arrow (returns to counting page or launch page depending on entry point)
- Actions: WhatsApp share icon + Download icon (OWNER only)

**Body — ListView:**
1. **GapReportSummaryHeader** — Material Card with:
   - Title "Résumé" with `Icons.assessment` icon
   - Row of 4 stat badges (2×2 grid on compact, 4-column on expanded):
     - "X comptés" — neutral
     - "Y concordants" — green badge (#51CF66)
     - "Z surplus" — amber badge (#FCC419) with "+XX XXX FCFA"
     - "W manquants" — red badge (#FA5252) with "−XX XXX FCFA"
   - XAF values formatted with space as thousands separator (e.g., "47 500 FCFA")

2. **ConcordantCollapseSection** — `ExpansionTile`:
   - Leading: ✅ icon (green)
   - Title: "Concordants : X produits"
   - Collapsed by default
   - When expanded: simple list of product names with "= 0" badge
   - Background: `green.shade50` (#E8F5E9)

3. **GapSectionList "Manquants"** — Section header + list:
   - Header: "🔴 Manquants (W)" — red text
   - Each row: `GapRowTile` — reuses `InventoryRow` layout but **read-only**:
     - Photo/initials avatar (48dp)
     - Product name + variant chip
     - "Keevo : X → Réel : Y" text (instead of TextFormField)
     - Écart badge "−Z" (red)
     - XAF value: "−XX XXX FCFA" small text below badge
   - Rows sorted by `gapValueXaf` descending (largest loss first)
   - Tapping a row → `ProductDetailBottomSheet` (AC2)

4. **GapSectionList "Surplus"** — Same pattern:
   - Header: "⚠️ Surplus (Z)" — amber text
   - Écart badge "+Z" (amber)
   - XAF value: "+XX XXX FCFA"

5. **"Appliquer les ajustements"** — `FilledButton` at bottom:
   - Disabled, greyed out (Story 6.4 placeholder)
   - Subtitle text below: "Disponible dans une prochaine mise à jour"
   - Only visible for IN_PROGRESS sessions

### ProductDetailBottomSheet (AC2)

- Drag handle at top
- Full-width product photo (or large initials avatar if no photo)
- Product name (headline) + SKU badge
- Category name
- Variant chip (if applicable)
- Divider
- 3-column row: "Keevo: X" | "Réel: Y" | "Écart: ±Z"
- XAF gap value: "|écart| × prix = XX XXX FCFA"
- Optional: "Voir historique stock" button (navigates to stock movement history if available — from Story 2.3)

### Colors & Tokens

| Element | Color | Token |
|---|---|---|
| Summary badge concordant | `#51CF66` | `colorSuccess` |
| Summary badge surplus | `#FCC419` | `colorWarning` |
| Summary badge shortage | `#FA5252` | `colorError` |
| Concordant section bg | `#E8F5E9` | `green.shade50` |
| Surplus row bg | `#FFFDE7` | `amber.shade50` |
| Shortage row bg | `#FFEBEE` | `red.shade50` |
| XAF text | `colorScheme.onSurfaceVariant` | neutral |

### Responsive (UX5 breakpoints)

- **Compact** (<600dp): single-column ListView, summary badges stacked 2×2
- **Medium** (600-840dp): same layout, wider cards
- **Expanded** (>840dp): two-column layout — left: summary + actions, right: gap sections list

### Accessibility

- Touch targets ≥ 48dp spacing 8dp
- Semantics labels on all stat badges ("142 produits concordants", "7 produits manquants pour 47 500 FCFA")
- `ExpansionTile` announced as "section concordants, réduire/développer"
- French labels everywhere — no English in UI

---

## Tasks / Subtasks

> **⚠️ TDD STRICT — RED → GREEN → REFACTOR. Write the failing test FIRST, then implement. No exceptions.**

---

### BACKEND — Full TDD (Spring Boot, Hexagonal Architecture)

> **Module placement**: `inventory/counting/`
> Domain: `com.keevo.inventory.counting`
> All tests go in `src/test/java/com/keevo/inventory/counting/`

---

- [x] **Task 1 — Domain models: InventoryGapRow, InventoryGapSummary, InventoryGapReport** (AC1)

  - [x] 1.1 — Write `InventoryGapRowTest.java`:
    ```java
    @Test void isSurplus_whenEcartPositive_shouldReturnTrue() { ... }
    @Test void isShortage_whenEcartNegative_shouldReturnTrue() { ... }
    @Test void isConcordant_whenEcartZero_shouldReturnTrue() { ... }
    ```
  - [x] 1.2 — Create `InventoryGapRow.java` in `domain/model/`:
    ```java
    public record InventoryGapRow(
        UUID productId, String productName, String sku, String photoUrl,
        UUID variantId, String variantLabel,
        int theoretical, int physical, int ecart,
        int unitPriceXaf, long gapValueXaf
    ) {
        public boolean isSurplus() { return ecart > 0; }
        public boolean isShortage() { return ecart < 0; }
        public boolean isConcordant() { return ecart == 0; }
    }
    ```
  - [x] 1.3 — Create `InventoryGapSummary.java` in `domain/model/`:
    ```java
    public record InventoryGapSummary(
        int totalCounted, int totalConcordant, int totalSurplus, int totalShortage,
        long totalSurplusValueXaf, long totalShortageValueXaf
    ) {}
    ```
  - [x] 1.4 — Create `InventoryGapReport.java` in `domain/model/`:
    ```java
    public class InventoryGapReport {
        private final UUID sessionId;
        private final UUID storeId;
        private final String storeName;
        private final InventoryScope scope;
        private final InventoryGapSummary summary;
        private final List<InventoryGapRow> concordantRows;
        private final List<InventoryGapRow> surplusRows;
        private final List<InventoryGapRow> shortageRows;
        private final UUID generatedBy;
        private final Instant generatedAt;
        // Constructor, getters
    }
    ```
  - [x] 1.5 — Run tests → GREEN

---

- [x] **Task 2 — InventoryGapReportBuilder (GoF Builder)** (AC1)

  - [x] 2.1 — Write `InventoryGapReportBuilderTest.java`:
    ```java
    @Test void build_emptyReport_shouldReturnZeroSummary() { ... }
    @Test void build_allConcordant_shouldHaveZeroGaps() { ... }
    @Test void build_mixedGaps_shouldSeparateSections() { ... }
    @Test void build_shortageSortedByValueDesc() { ... }
    @Test void build_surplusXafCalculatedCorrectly() { ... }
    @Test void build_summaryTotalsCorrect() { ... }
    ```
  - [x] 2.2 — Create `InventoryGapReportBuilder.java` in `domain/service/`:
    - Methods: `sessionId()`, `storeId()`, `storeName()`, `scope()`, `generatedBy()`
    - `addRow(InventoryCount count, int unitPriceXaf)` — computes écart and gapValueXaf
    - `enrichProductDetails(Map<UUID, ProductDetails>)` — enriches sku, photoUrl
    - `build()` — splits into concordant/surplus/shortage, sorts, builds summary
  - [x] 2.3 — Run tests → GREEN

---

- [x] **Task 3 — InventoryReportTextFormatter (GoF Strategy)** (AC4)

  - [x] 3.1 — Write `InventoryReportTextFormatterTest.java`:
    ```java
    @Test void formatWhatsApp_shouldMatchEmojiFormat() { ... }
    @Test void formatWhatsApp_topFiveShortagesOnly() { ... }
    @Test void formatWhatsApp_xafFormattedWithSpaces() { ... }
    @Test void formatWhatsApp_emptyShortagesHandled() { ... }
    ```
  - [x] 3.2 — Create `InventoryReportTextFormatter.java` in `domain/service/`:
    - `formatWhatsApp(InventoryGapReport report, String actorName)` → emoji-rich text
    - `formatDetailedText(InventoryGapReport report, String actorName)` → full export text
    - `formatXaf(long amount)` — format with space separator ("47 500 FCFA")
    - WAT timezone (`Africa/Lagos`), French date format (same as day-close `ManualReportStrategy`)
  - [x] 3.3 — Run tests → GREEN

---

- [x] **Task 4 — Port in: GenerateGapReportUseCase + Query** (AC1)

  - [x] 4.1 — Create `GenerateGapReportQuery.java`:
    ```java
    public record GenerateGapReportQuery(UUID sessionId, UUID actorId) {}
    ```
  - [x] 4.2 — Create `GenerateGapReportUseCase.java`:
    ```java
    public interface GenerateGapReportUseCase {
        InventoryGapReport execute(GenerateGapReportQuery query);
    }
    ```

---

- [x] **Task 5 — GenerateGapReportService (Template Method)** (AC1)

  - [x] 5.1 — Write `GenerateGapReportServiceTest.java`:
    ```java
    @Test void execute_happyPath_shouldBuildCompleteReport() { ... }
    @Test void execute_sessionNotFound_shouldThrow() { ... }
    @Test void execute_emptyCounts_shouldReturnEmptyReport() { ... }
    @Test void execute_uncountedProductsSkipped() { ... }
    @Test void execute_productPriceZero_shouldCalculateZeroValue() { ... }
    ```
  - [x] 5.2 — Implement `GenerateGapReportService.java`:
    - Inject: `InventorySessionRepository`, `InventoryCountRepository`, `ProductRepository`, `StoreRepository`
    - Steps: load session → load counts → load products (for price) → get store name → build report via `InventoryGapReportBuilder`
    - `@Transactional(readOnly = true)` — read-only operation
    - **REUSE**: `ProductRepository.findAllByIds()` (from Story 6.2), `StoreRepository.findById()` (from Story 3.1)
  - [x] 5.3 — Run tests → GREEN

---

- [x] **Task 6 — Domain event: InventoryReportGeneratedEvent** (audit)

  - [x] 6.1 — Create `InventoryReportGeneratedEvent.java` in `domain/event/`:
    ```java
    public record InventoryReportGeneratedEvent(
        UUID sessionId, UUID actorId, String tenantId, Instant occurredAt
    ) {}
    ```
  - [x] 6.2 — Register in `AuditEventListener.java`:
    ```java
    @EventListener
    public void on(InventoryReportGeneratedEvent event) {
        auditPort.record("INVENTORY_REPORT_GENERATED", "InventorySession",
            event.sessionId(), event.actorId(), event.tenantId(), event.occurredAt());
    }
    ```

---

- [x] **Task 7 — DTO: InventoryGapReportResponseDto + InventoryGapRowDto** (AC1)

  - [x] 7.1 — Create `InventoryGapRowDto.java` in `adapter/in/rest/dto/`:
    ```java
    public record InventoryGapRowDto(
        UUID productId, String productName, String sku, String photoUrl,
        UUID variantId, String variantLabel,
        int theoretical, int physical, int ecart,
        int unitPriceXaf, long gapValueXaf
    ) {
        public static InventoryGapRowDto fromDomain(InventoryGapRow row) { ... }
    }
    ```
  - [x] 7.2 — Create `InventoryGapReportResponseDto.java` in `adapter/in/rest/dto/`:
    - Contains nested `InventoryGapSummaryDto` record
    - `fromDomain(InventoryGapReport report)` factory method

---

- [x] **Task 8 — InventoryReportController (REST endpoints)** (AC1, AC4)

  - [x] 8.1 — Write `InventoryReportControllerTest.java`:
    ```java
    @Test void getGapReport_shouldReturn200WithReport() { ... }
    @Test void getGapReport_sessionNotFound_shouldReturn404() { ... }
    @Test void getGapReportText_shouldReturn200WithWhatsAppText() { ... }
    @Test void getGapReport_rbac_ownerAndEmployeeAllowed() { ... }
    ```
  - [x] 8.2 — Create `InventoryReportController.java` in `adapter/in/rest/`:
    - `GET /api/v1/inventory/sessions/{sessionId}/gap-report` → JSON report
    - `GET /api/v1/inventory/sessions/{sessionId}/gap-report/text` → WhatsApp text string
    - Inject: `GenerateGapReportUseCase`, `InventoryReportTextFormatter`, `JwtTokenProvider`
    - Publish `InventoryReportGeneratedEvent` on each report generation
    - `@PreAuthorize("hasAnyRole('OWNER','EMPLOYEE')")` (both can view)
  - [x] 8.3 — Run tests → GREEN

---

- [x] **Task 9 — cURL E2E test script** (full flow)

  - [x] 9.1 — Create `scripts/curl-tests-story-6-3.sh`:
    ```bash
    # Step 1: Register + Login (phone auth + select tenant)
    # Step 2: Create store if needed
    # Step 3: Create products with prices (3-5 products, varying prices)
    # Step 4: Create stock levels for products in store
    # Step 5: Start inventory session (POST /inventory/sessions)
    # Step 6: Save inventory counts with mixed gaps:
    #   - Product A: theoretical=10, physical=10 (concordant)
    #   - Product B: theoretical=8, physical=5 (shortage −3)
    #   - Product C: theoretical=5, physical=8 (surplus +3)
    #   - Product D: theoretical=20, physical=15 (shortage −5)
    # Step 7: GET /inventory/sessions/{id}/gap-report → verify JSON:
    #   - summary.totalCounted == 4
    #   - summary.totalConcordant == 1
    #   - summary.totalShortage == 2
    #   - summary.totalSurplus == 1
    #   - shortageRows sorted by gapValueXaf desc
    #   - XAF values correct (écart × price)
    # Step 8: GET /inventory/sessions/{id}/gap-report/text → verify WhatsApp text:
    #   - Contains "📋 Rapport d'inventaire"
    #   - Contains "🔴 Manquants"
    #   - Contains "Top manques"
    #   - Contains correct XAF values
    # Step 9: Verify report works for both OWNER and EMPLOYEE
    # Step 10: Verify 404 for non-existent session
    ```
    Each step: print PASS/FAIL, cumulative assertion count, exit on first failure.

---

### FLUTTER — TDD + Widget Tests

> **Feature folder:** `lib/features/inventory/`
> Same Clean Architecture + Riverpod pattern as Stories 6.1/6.2/6.2a.

---

- [x] **Task 10 — Domain models: InventoryGapReportModel + InventoryGapRowModel** (AC1)

  - [x] 10.1 — Create `inventory_gap_row_model.dart` in `domain/model/` (Freezed):
    - Fields: productId, productName, sku, photoUrl, variantId, variantLabel, theoretical, physical, ecart, unitPriceXaf, gapValueXaf
    - Getters: `isSurplus`, `isShortage`, `isConcordant`
  - [x] 10.2 — Create `inventory_gap_report_model.dart` in `domain/model/` (Freezed):
    - Contains `InventoryGapReportModel` and `InventoryGapSummaryModel`
    - Fields mirroring backend DTO
  - [x] 10.3 — Run `build_runner` → verify generated files compile without errors

---

- [x] **Task 11 — LocalGapReportDataSource (offline generation)** (AC1, AC5)

  - [x] 11.1 — Create `local_gap_report_datasource.dart` in `data/datasource/`:
    - `generate(String sessionId)` → `Future<InventoryGapReportModel>`
    - Queries: `inventorySessions`, `inventoryCounts`, `products`, `stores` Drift tables
    - Splits rows into concordant/surplus/shortage, sorts shortage by gapValueXaf desc
    - Handles null price gracefully (treat as 0)
  - [x] 11.2 — Provider: `localGapReportDataSourceProvider` (simple Provider using AppDatabase)

---

- [x] **Task 12 — gap_report_provider.dart (Riverpod providers)** (AC1, AC4)

  - [x] 12.1 — Create `gap_report_provider.dart` in `presentation/provider/`:
    - `gapReportProvider(sessionId)` — AsyncNotifier, generates from local Drift, also hits backend for audit
    - `gapReportWhatsAppTextProvider(report, actorName)` — computed text
    - `gapReportDetailedTextProvider(report, actorName)` — computed text for export

---

- [x] **Task 13 — WhatsApp text formatter (Flutter-side)** (AC4)

  - [x] 13.1 — Create `inventory_report_text_formatter.dart` in `domain/service/` (or `presentation/utils/`):
    - `formatWhatsAppReport(InventoryGapReportModel report, String actorName)` → String
    - `formatDetailedTextReport(InventoryGapReportModel report, String actorName)` → String
    - `_formatXaf(int amount)` — thousands separator with space
    - `_frenchMonth(int month)` — French month names
    - Must match the backend `InventoryReportTextFormatter` output exactly

---

- [x] **Task 14 — GapReportSummaryHeader widget** (AC1)

  - [x] 14.1 — Create `gap_report_summary_header.dart` in `presentation/widget/`:
    - Material Card with `Icons.assessment` header
    - 4 stat badges (colored: neutral, green, amber, red)
    - XAF values formatted with space thousands separator
    - Responsive: 2×2 grid on compact, 4-column on expanded

---

- [x] **Task 15 — ConcordantCollapseSection widget** (AC1)

  - [x] 15.1 — Create `concordant_collapse_section.dart` in `presentation/widget/`:
    - `ExpansionTile` collapsed by default
    - Leading: ✅ green icon
    - Title: "Concordants : X produits"
    - When expanded: simple list of product names with "= 0" badge
    - Background: `green.shade50`

---

- [x] **Task 16 — GapRowTile widget + GapSectionList** (AC1, AC2)

  - [x] 16.1 — Create `gap_row_tile.dart` in `presentation/widget/`:
    - Same layout as `InventoryRow` but **read-only** (no TextFormField):
      - Photo/initials avatar (48dp)
      - Product name + variant chip
      - "Keevo : X → Réel : Y" text
      - Écart badge (colored)
      - XAF gap value text below badge
    - `onTap` callback for product detail (AC2)
  - [x] 16.2 — Create `gap_section_list.dart` in `presentation/widget/`:
    - Section header with icon + title + count
    - List of `GapRowTile` items

---

- [x] **Task 17 — ProductDetailBottomSheet** (AC2)

  - [x] 17.1 — Create `product_detail_bottom_sheet.dart` in `presentation/widget/`:
    - Full-width photo or large initials avatar
    - Product name + SKU + Category
    - Variant chip (if applicable)
    - Theoretical vs Physical vs Écart row
    - XAF gap value calculation
    - "Voir historique stock" button (placeholder or navigation to existing stock history)
    - Dismissible by swipe

---

- [x] **Task 18 — InventoryGapReportPage** (AC1, AC2, AC3, AC4, AC6)

  - [x] 18.1 — Create `inventory_gap_report_page.dart` in `presentation/page/`:
    - Uses `gapReportProvider(sessionId)` for data
    - AppBar: "Rapport d'inventaire" + WhatsApp share + Download (OWNER only)
    - Body: ListView with Summary + Concordant + Shortage + Surplus + Appliquer placeholder
    - WhatsApp share: generates text via formatter → `url_launcher` deeplink `https://wa.me/?text=...`
    - Download: `share_plus` with formatted text (Free plan), SnackBar for PDF (Payant plan placeholder)
    - "Appliquer les ajustements" button: disabled, Story 6.4 placeholder
    - Responsive layout with `LayoutBuilder`

---

- [x] **Task 19 — Router + Navigation wiring** (AC1, AC5)

  - [x] 19.1 — Add `/inventory/gap-report/:sessionId` route in `app_router.dart`
  - [x] 19.2 — Modify `inventory_counting_page.dart`: replace SnackBar placeholder with `context.push('/inventory/gap-report/${widget.sessionId}')`. **NOTE**: add `import 'package:go_router/go_router.dart';` — this page doesn't currently import go_router.
  - [x] 19.3 — Modify `session_history_card.dart`: add "Voir rapport" action for VALIDATED sessions, navigate to gap report page

---

- [x] **Task 20 — Widget tests (Flutter)** (AC1, AC2, AC3, AC4)

  - [x] 20.1 — Write `gap_report_summary_header_test.dart` (3 tests):
    - Renders all 4 stat badges
    - XAF values formatted correctly
    - Colors match design tokens
  - [x] 20.2 — Write `gap_row_tile_test.dart` (3 tests):
    - Renders product name + theoretical/physical/écart
    - Tap triggers onTap callback
    - Correct badge color (red for shortage, amber for surplus)
  - [x] 20.3 — Write `inventory_gap_report_page_test.dart` (4 tests):
    - Renders summary + sections when data loaded
    - WhatsApp button visible for both OWNER and EMPLOYEE
    - Download button visible only for OWNER
    - "Appliquer" button disabled with placeholder text
  - [x] 20.4 — Write `inventory_report_text_formatter_test.dart` (3 tests):
    - WhatsApp format matches expected emoji pattern
    - Top 5 shortages only in WhatsApp text
    - XAF format with space thousands separator
  - [x] 20.5 — Write `concordant_collapse_section_test.dart` (2 tests):
    - Renders collapsed by default
    - Expands on tap showing product names
  - [x] 20.6 — Write `product_detail_bottom_sheet_test.dart` (2 tests):
    - Renders product info, theoretical/physical/écart
    - Dismissible by swipe

**Total new Flutter tests**: ~17

---

## Dev Notes

### XAF Value Calculation
- **Formula**: `gapValueXaf = |écart| × product.price` (selling price, NOT buyPrice)
- **Product.getPrice()** returns a `Money` value object. Use `getPriceValue()` for the integer value (in XAF centime-free — XAF has no decimal).
- Edge case: `price = 0` (quick-added products have price=0) → gapValueXaf is 0. This is expected and should not cause errors.
- XAF formatting: use **space** as thousands separator, not comma/period. E.g., "47 500 FCFA" not "47,500 FCFA".

### Product Domain Model — photoUrl & categoryName
- **`Product.java` domain entity does NOT have `photoUrl`** — it is only on `ProductJpaEntity.photoUrl`. The `toDomain()` mapping in `ProductRepositoryAdapter` does not map `photoUrl`.
- **Options for enrichment in `GenerateGapReportService`**:
  1. **Recommended**: Add a `ProductRepository.findAllJpaByIds()` or a projection method returning photoUrl alongside product data. OR simply add `getPhotoUrl()` to the `Product` domain entity constructor + field.
  2. **Alternative**: Enrich photoUrl separately by querying `ProductJpaEntity` directly in the adapter layer (breaks hexagonal purity).
  3. **Simplest**: Add `photoUrl` field to the `Product` domain entity and update `toDomain()` in `ProductRepositoryAdapter` — this is a one-line change.
- **`Product.getCategoryId()` exists** but NOT `getCategoryName()`. The category name must be resolved via `CategoryRepository.findById(categoryId).name()` or by joining in the query. For the MVP, load categories separately and build a `Map<UUID, String>` (categoryId → name).
- **Flutter Drift**: the `products` table already has `photo_url` and `category_id` columns. Category name can be joined from the `categories` table.

### WhatsApp Share Pattern
- **Flutter-side**: use `url_launcher` with `https://wa.me/?text={urlEncodedText}` — same pattern as Story 3.4 (cross-store employee WhatsApp deeplink).
- **Backend-side**: `InventoryReportTextFormatter` generates the same text. Backend endpoint `GET /gap-report/text` returns this text for potential server-side sending via `WhatsAppPort` (future).
- The text format MUST match the day-close WhatsApp report style: emoji-prefixed lines, compact, readable in 5 seconds on a phone screen.

### Report Generation — Local vs Backend
- **Primary**: report generated **locally** from Drift data (instant, offline-first).
- **Secondary**: backend endpoint called when online for audit trail (`InventoryReportGeneratedEvent`).
- The Flutter provider generates the report from Drift, then fire-and-forget calls the backend.
- Past reports are NOT stored separately — they are regenerated on-demand from `inventory_counts` data.

### InventoryRow Reuse
- The `InventoryRow` widget (Story 6.2) supports a TextFormField for counting. For the gap report, we need a **read-only variant** that shows "Keevo : X → Réel : Y" as text instead of an input field.
- **Do NOT modify** the existing `InventoryRow` widget. Create a new `GapRowTile` widget that reuses the same visual layout (avatar + name + badge styling) but in read-only mode. This avoids regression risk.

### Existing Provider Dependencies
- `countingProductsProvider(sessionId)` — already loads products with counts. Can be reused for basic data if needed, but the gap report provider should generate its own sorted/grouped model.
- `currentUserRoleProvider` — already exists from auth feature (`core/di/providers.dart`). Returns `'OWNER'` or `'EMPLOYEE'`. Use `ref.watch(currentUserRoleProvider) == 'OWNER'` for OWNER-only visibility of download button.
- `authenticatedDioProvider` — already exists. REUSE for backend API calls.

### Sort Order
- **Shortage section**: sorted by `gapValueXaf` **descending** (largest monetary loss first). This is explicit in the epic AC.
- **Surplus section**: sorted by `gapValueXaf` **descending** (largest surplus value first).
- **Concordant section**: alphabetical by product name.

### "Appliquer les ajustements" Placeholder
- This button is visible but DISABLED. Do NOT implement any stock adjustment logic — that is Story 6.4's scope.
- The button has a tooltip/subtitle: "Disponible dans une prochaine mise à jour".
- Only visible for IN_PROGRESS sessions. For VALIDATED sessions (past reports), the button is hidden (adjustments already applied).

---

## Project Structure Notes

### Backend Structure (files to create/modify)

**New files:**
- `backend/src/main/java/com/keevo/inventory/counting/domain/model/InventoryGapRow.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/model/InventoryGapSummary.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/model/InventoryGapReport.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/service/InventoryGapReportBuilder.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/service/InventoryReportTextFormatter.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/event/InventoryReportGeneratedEvent.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/port/in/GenerateGapReportQuery.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/port/in/GenerateGapReportUseCase.java`
- `backend/src/main/java/com/keevo/inventory/counting/application/service/GenerateGapReportService.java`
- `backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventoryReportController.java`
- `backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventoryGapReportResponseDto.java`
- `backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventoryGapRowDto.java`
- `backend/src/test/java/com/keevo/inventory/counting/domain/model/InventoryGapRowTest.java`
- `backend/src/test/java/com/keevo/inventory/counting/domain/service/InventoryGapReportBuilderTest.java`
- `backend/src/test/java/com/keevo/inventory/counting/domain/service/InventoryReportTextFormatterTest.java`
- `backend/src/test/java/com/keevo/inventory/counting/application/service/GenerateGapReportServiceTest.java`
- `backend/src/test/java/com/keevo/inventory/counting/adapter/in/rest/InventoryReportControllerTest.java`
- `scripts/curl-tests-story-6-3.sh`

**Modified files:**
- `backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java` — add `InventoryReportGeneratedEvent` handler

### Flutter Structure (files to create/modify)

**New files:**
- `app/lib/features/inventory/domain/model/inventory_gap_report_model.dart`
- `app/lib/features/inventory/domain/model/inventory_gap_row_model.dart`
- `app/lib/features/inventory/domain/service/inventory_report_text_formatter.dart`
- `app/lib/features/inventory/data/datasource/local_gap_report_datasource.dart`
- `app/lib/features/inventory/presentation/provider/gap_report_provider.dart`
- `app/lib/features/inventory/presentation/page/inventory_gap_report_page.dart`
- `app/lib/features/inventory/presentation/widget/gap_report_summary_header.dart`
- `app/lib/features/inventory/presentation/widget/gap_section_list.dart`
- `app/lib/features/inventory/presentation/widget/concordant_collapse_section.dart`
- `app/lib/features/inventory/presentation/widget/gap_row_tile.dart`
- `app/lib/features/inventory/presentation/widget/product_detail_bottom_sheet.dart`
- `app/test/features/inventory/presentation/widget/gap_report_summary_header_test.dart`
- `app/test/features/inventory/presentation/widget/gap_row_tile_test.dart`
- `app/test/features/inventory/presentation/page/inventory_gap_report_page_test.dart`
- `app/test/features/inventory/domain/service/inventory_report_text_formatter_test.dart`
- `app/test/features/inventory/presentation/widget/concordant_collapse_section_test.dart`
- `app/test/features/inventory/presentation/widget/product_detail_bottom_sheet_test.dart`

**Modified files:**
- `app/lib/features/inventory/presentation/page/inventory_counting_page.dart` — replace SnackBar with navigation to gap report
- `app/lib/features/inventory/presentation/widget/session_history_card.dart` — add "Voir rapport" for VALIDATED sessions
- `app/lib/core/router/app_router.dart` — add `/inventory/gap-report/:sessionId` route

---

## References

- **Epic 6**: [epic-6-inventaire-assist.md](_bmad-output/planning-artifacts/epics/epic-6-inventaire-assist.md) — Story 6.3 AC definition
- **Story 6.1** (session foundation): [6-1-lancement-configuration-session-inventaire.md](_bmad-output/implementation-artifacts/6-1-lancement-configuration-session-inventaire.md)
- **Story 6.2** (counting form): [6-2-formulaire-guide-saisie-des-quantites-physiques.md](_bmad-output/implementation-artifacts/6-2-formulaire-guide-saisie-des-quantites-physiques.md)
- **Story 6.2a** (quick-add): [6-2a-ajout-rapide-produit-pendant-inventaire.md](_bmad-output/implementation-artifacts/6-2a-ajout-rapide-produit-pendant-inventaire.md)
- **Story 4.4** (day-close report pattern): [4-4-cloture-journaliere-historique-des-ventes.md](_bmad-output/implementation-artifacts/4-4-cloture-journaliere-historique-des-ventes.md) — WhatsApp report format reference (ManualReportStrategy, AutoReportStrategy)
- **Architecture**: [architecture.md](_bmad-output/planning-artifacts/architecture.md) — Hexagonal architecture, GoF patterns mandate
- **UX Design**: [ux-design-specification.md](_bmad-output/planning-artifacts/ux-design-specification.md) — Flow 10 (Inventaire Assisté), UX9 (Inventory Row), Component Strategy P3
- **PRD**: [prd.md](_bmad-output/planning-artifacts/prd.md) — FR47 (auto gap calculation), FR49 (detailed inventory report)

---

## Dev Agent Record

<!-- Filled by Dev Agent upon completion -->

### Agent Model Used

Claude Opus 4.6 (GitHub Copilot)

### Debug Log References

- Backend: 126/126 inventory tests GREEN (22 new from Story 6.3)
- Flutter: 17/17 new Story 6.3 tests GREEN (105 total inventory tests pass, 12 pre-existing failures unrelated to 6.3)
- build_runner: 332 outputs generated (Freezed + Riverpod codegen)

### Completion Notes List

- `share_plus` v10.1.4 uses `Share.share(text)` API, not `SharePlus.instance.share(ShareParams(...))` 
- `dioProvider` is the correct provider name (not `authenticatedDioProvider` as referenced in story spec)
- Product domain entity does NOT have `photoUrl` — handled on Flutter-side via Drift `products` table which has `photo_url` column
- Drift schema stays at v20 — no schema changes needed (report generated on-the-fly)
- Pre-existing test failures (12) in inventory suite: TransferHistoryPage, GlobalStockOverviewPage, InventoryCountingPage quick-add — all unrelated to Story 6.3

### Change Log

| Action | File | Description |
|---|---|---|
| CREATE | `backend/.../domain/model/InventoryGapRow.java` | Java record with isSurplus/isShortage/isConcordant |
| CREATE | `backend/.../domain/model/InventoryGapSummary.java` | Java record for report summary stats |
| CREATE | `backend/.../domain/model/InventoryGapReport.java` | Value object with immutable lists |
| CREATE | `backend/.../domain/service/InventoryGapReportBuilder.java` | GoF Builder pattern |
| CREATE | `backend/.../domain/service/InventoryReportTextFormatter.java` | WhatsApp + detailed text formatting |
| CREATE | `backend/.../domain/event/InventoryReportGeneratedEvent.java` | Audit event record |
| CREATE | `backend/.../domain/port/in/GenerateGapReportQuery.java` | Query record |
| CREATE | `backend/.../domain/port/in/GenerateGapReportUseCase.java` | Use case interface |
| CREATE | `backend/.../application/service/GenerateGapReportService.java` | Template Method pattern service |
| CREATE | `backend/.../adapter/in/rest/InventoryReportController.java` | REST endpoints for gap report |
| CREATE | `backend/.../adapter/in/rest/dto/InventoryGapRowDto.java` | DTO record |
| CREATE | `backend/.../adapter/in/rest/dto/InventoryGapReportResponseDto.java` | Response DTO with nested summary |
| CREATE | `backend/.../test/.../InventoryGapRowTest.java` | 3 tests |
| CREATE | `backend/.../test/.../InventoryGapReportBuilderTest.java` | 6 tests |
| CREATE | `backend/.../test/.../InventoryReportTextFormatterTest.java` | 4 tests |
| CREATE | `backend/.../test/.../GenerateGapReportServiceTest.java` | 5 tests |
| CREATE | `backend/.../test/.../InventoryReportControllerTest.java` | 4 tests |
| CREATE | `scripts/curl-tests-story-6-3.sh` | cURL E2E test script |
| MODIFY | `backend/.../AuditEventListener.java` | Added InventoryReportGeneratedEvent handler |
| CREATE | `app/.../domain/model/inventory_gap_row_model.dart` | Freezed model |
| CREATE | `app/.../domain/model/inventory_gap_report_model.dart` | Freezed model |
| CREATE | `app/.../domain/service/inventory_report_text_formatter.dart` | WhatsApp + detailed text formatting |
| CREATE | `app/.../data/datasource/local_gap_report_datasource.dart` | Drift-based offline report generation |
| CREATE | `app/.../presentation/provider/gap_report_provider.dart` | Riverpod providers |
| CREATE | `app/.../presentation/page/inventory_gap_report_page.dart` | Main report page |
| CREATE | `app/.../presentation/widget/gap_report_summary_header.dart` | Summary card with 4 stat badges |
| CREATE | `app/.../presentation/widget/concordant_collapse_section.dart` | Collapsible concordant section |
| CREATE | `app/.../presentation/widget/gap_row_tile.dart` | Read-only gap row tile |
| CREATE | `app/.../presentation/widget/gap_section_list.dart` | Section header + row list |
| CREATE | `app/.../presentation/widget/product_detail_bottom_sheet.dart` | Product detail bottom sheet |
| MODIFY | `app/.../core/router/app_router.dart` | Added /inventory/gap-report/:sessionId route |
| MODIFY | `app/.../presentation/page/inventory_counting_page.dart` | Replaced SnackBar with gap report navigation |
| MODIFY | `app/.../presentation/widget/session_history_card.dart` | Added onViewReport callback for VALIDATED sessions |
| MODIFY | `app/.../presentation/page/inventory_launch_page.dart` | Pass onViewReport to SessionHistoryCard |
| CREATE | `app/test/.../gap_report_summary_header_test.dart` | 3 widget tests |
| CREATE | `app/test/.../gap_row_tile_test.dart` | 3 widget tests |
| CREATE | `app/test/.../concordant_collapse_section_test.dart` | 2 widget tests |
| CREATE | `app/test/.../product_detail_bottom_sheet_test.dart` | 2 widget tests |
| CREATE | `app/test/.../inventory_gap_report_page_test.dart` | 4 page tests |
| CREATE | `app/test/.../inventory_report_text_formatter_test.dart` | 3 unit tests |

### File List

**Backend — New (17 files)**
- `backend/src/main/java/com/keevo/inventory/counting/domain/model/InventoryGapRow.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/model/InventoryGapSummary.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/model/InventoryGapReport.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/service/InventoryGapReportBuilder.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/service/InventoryReportTextFormatter.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/event/InventoryReportGeneratedEvent.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/port/in/GenerateGapReportQuery.java`
- `backend/src/main/java/com/keevo/inventory/counting/domain/port/in/GenerateGapReportUseCase.java`
- `backend/src/main/java/com/keevo/inventory/counting/application/service/GenerateGapReportService.java`
- `backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/InventoryReportController.java`
- `backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventoryGapRowDto.java`
- `backend/src/main/java/com/keevo/inventory/counting/adapter/in/rest/dto/InventoryGapReportResponseDto.java`
- `backend/src/test/java/com/keevo/inventory/counting/domain/model/InventoryGapRowTest.java`
- `backend/src/test/java/com/keevo/inventory/counting/domain/service/InventoryGapReportBuilderTest.java`
- `backend/src/test/java/com/keevo/inventory/counting/domain/service/InventoryReportTextFormatterTest.java`
- `backend/src/test/java/com/keevo/inventory/counting/application/service/GenerateGapReportServiceTest.java`
- `backend/src/test/java/com/keevo/inventory/counting/adapter/in/rest/InventoryReportControllerTest.java`

**Backend — Modified (1 file)**
- `backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java`

**Scripts (1 file)**
- `scripts/curl-tests-story-6-3.sh`

**Flutter — New (17 files)**
- `app/lib/features/inventory/domain/model/inventory_gap_row_model.dart` (+generated .freezed.dart, .g.dart)
- `app/lib/features/inventory/domain/model/inventory_gap_report_model.dart` (+generated .freezed.dart, .g.dart)
- `app/lib/features/inventory/domain/service/inventory_report_text_formatter.dart`
- `app/lib/features/inventory/data/datasource/local_gap_report_datasource.dart`
- `app/lib/features/inventory/presentation/provider/gap_report_provider.dart` (+generated .g.dart)
- `app/lib/features/inventory/presentation/page/inventory_gap_report_page.dart`
- `app/lib/features/inventory/presentation/widget/gap_report_summary_header.dart`
- `app/lib/features/inventory/presentation/widget/concordant_collapse_section.dart`
- `app/lib/features/inventory/presentation/widget/gap_row_tile.dart`
- `app/lib/features/inventory/presentation/widget/gap_section_list.dart`
- `app/lib/features/inventory/presentation/widget/product_detail_bottom_sheet.dart`
- `app/test/features/inventory/presentation/widget/gap_report_summary_header_test.dart`
- `app/test/features/inventory/presentation/widget/gap_row_tile_test.dart`
- `app/test/features/inventory/presentation/widget/concordant_collapse_section_test.dart`
- `app/test/features/inventory/presentation/widget/product_detail_bottom_sheet_test.dart`
- `app/test/features/inventory/presentation/page/inventory_gap_report_page_test.dart`
- `app/test/features/inventory/domain/service/inventory_report_text_formatter_test.dart`

**Flutter — Modified (4 files)**
- `app/lib/core/router/app_router.dart`
- `app/lib/features/inventory/presentation/page/inventory_counting_page.dart`
- `app/lib/features/inventory/presentation/widget/session_history_card.dart`
- `app/lib/features/inventory/presentation/page/inventory_launch_page.dart`
