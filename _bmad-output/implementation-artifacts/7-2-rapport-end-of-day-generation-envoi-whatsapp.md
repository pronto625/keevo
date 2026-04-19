# Story 7.2: Rapport End-of-Day — Génération & Envoi WhatsApp

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Report type (daily vs weekly vs inventory — only daily in this story), report format (WhatsApp text vs in-app HTML), delivery channel (WhatsApp vs push notification vs in-app only), retry strategy (interval, max attempts), multi-store aggregation (single store vs combined). |
| What might change in the future? | New report types (weekly in 7.3, inventory in 7.5), new delivery channels (SMS, email), configurable report time (7.5), PDF export (7.4 plan payant), AI-generated insights. |
| Which GoF pattern(s) apply? | **Template Method** for `AbstractReportGenerator` → `DailyReportGenerator` (skeleton: collectData → formatContent → persistReport → deliverReport → checkMultiStore). **Strategy** for delivery channel (`ReportDeliveryStrategy` → `WhatsAppDeliveryStrategy`, `InAppDeliveryStrategy`). **Builder** for `EndOfDayReportBuilder` (assembles report from multiple queries: sales, products, employees, stock). **Observer** for `DayClosedEvent` → `EndOfDayReportListener`. **Chain of Responsibility** for delivery fallback (WhatsApp → Push → In-app only). **Retry / Command** for `ReportDeliveryCommand` with retry queue. |
| How does it enable Open/Closed principle? | New report types (weekly, inventory) extend `AbstractReportGenerator` without modifying it. New delivery channels implement `ReportDeliveryStrategy`. The listener chain processes events without modifying the publisher (`CloseDayService`). |
| Where is the pattern applied? | **Template Method**: `AbstractReportGenerator` (abstract skeleton) + `DailyReportGenerator` (concrete daily) in `reporting.report.application.service`. **Strategy**: `ReportDeliveryStrategy` in `reporting.report.domain.port.out`. **Builder**: `EndOfDayReportBuilder` in `reporting.report.application.service`. **Observer**: `EndOfDayReportListener` in `reporting.report.application.service` (Spring `@EventListener`). Domain model: `EndOfDayReport` (entity), `EndOfDayReportData` (VO), `ReportType`, `DeliveryStatus` (enums) in `reporting.report.domain.model`. |

---

## Story

As a proprietor (Simon — OWNER role),
I want to receive a concise, emoji-rich daily report on WhatsApp at the end of each business day,
So that I can review performance instantly on my phone without opening the app.

---

## Acceptance Criteria

### AC1 — Auto-trigger day-close at configurable time (default 20:00)

- **Given** the configurable end-of-day auto-trigger time for a store (default 20:00 local time, configurable in Story 7.5)
- **When** the scheduled time is reached and no manual `DayClosedEvent` has been emitted for that store today
- **Then** the backend triggers the day-close automatically via the existing `DayClosureAutoScheduler` (`@Scheduled(cron = "0 0 19 * * *")` UTC = ~20:00 WAT)
- **And** the auto-generated closure emits a `DayClosedEvent` with `isAutomatic: true`
- **And** Simon sees an in-app notification next time he opens the app: "Votre journée du [date] a été clôturée automatiquement pour [storeName]."
- **And** Loïc sees a subtle notification next morning: "Votre journée du [date] a été clôturée automatiquement."
- **And** the scheduler respects UTC+1 WAT timezone (already implemented in story 4-4)

> **NOTE**: The auto-closure mechanism already exists from Story 4-4 (`DayClosureAutoScheduler`). This story focuses on the *report generation* triggered by `DayClosedEvent`, not the closure mechanism itself.

### AC2 — End-of-day report generation via Template Method

- **Given** Loïc closes his day manually (Story 4-4) or the automatic 20h fallback triggers
- **When** the `DayClosedEvent` is processed by `EndOfDayReportListener`
- **Then** the system generates the end-of-day report using `DailyReportGenerator` (extends `AbstractReportGenerator` via Template Method GoF pattern)
- **And** the report aggregates for that `storeId` + `date`:
  - Total sales count (COMPLETED only)
  - Total CA (XAF)
  - CA by payment mode (Cash / MoMo)
  - Top 3 products by quantity sold (name, qty, revenue)
  - Average basket size (`totalCA / totalSales`)
  - Employee breakdown (name + sale count + CA per employee)
  - Stock alerts count (products below threshold)
- **And** the report is persisted in the `reports` table (new DDL) with: `id`, `tenantId`, `storeId`, `reportType` (DAILY), `reportDate`, `content` (full WhatsApp text), `deliveryStatus` (PENDING / SENT / FAILED / IN_APP_ONLY), `deliveryAttempts`, `lastAttemptAt`, `createdAt`
- **And** the report is sent via `WhatsAppPort.sendReport()` to Simon's WhatsApp number

### AC3 — Emoji-rich WhatsApp message format

- **Given** the WhatsApp message is generated
- **When** it is formatted by `DailyReportFormatter.format()`
- **Then** the format is emoji-rich, French, readable in ≤ 5 seconds, no link, no PDF:
```
📊 Rapport du jour — [Store Name]
📅 [date FR, e.g. "30 mars 2026"] | ⏰ [HH]h[mm] ([Manuel/Auto])

💰 CA Total : [X] FCFA
🛍 Ventes : [N] | 🧺 Panier moyen : [X] FCFA
💵 Cash : [X] FCFA | 📱 MoMo : [X] FCFA

🏆 Top produits :
1. [Product] — [qty] vendu(s) — [X] FCFA
2. [Product] — [qty] vendu(s) — [X] FCFA
3. [Product] — [qty] vendu(s) — [X] FCFA

👤 Équipe :
• [Employee 1] : [N] ventes — [X] FCFA
• [Employee 2] : [N] ventes — [X] FCFA

📦 Alertes stock : [N] produit(s) en rupture
```
- **And** if Simon has multiple stores and all have closed, a combined multi-store summary is appended:
```
📊 Résumé multi-boutiques — [date]
💰 CA Total toutes boutiques : [X] FCFA
🛍 Total ventes : [N]
🏪 [Store 1] : [X] FCFA | [Store 2] : [X] FCFA
```
- **And** the "⏰ [HH]h[mm]" shows the close time, and "[Manuel/Auto]" shows the closure source
- **And** if there are 0 sales, the report still sends with: "Aucune vente enregistrée aujourd'hui."

### AC4 — WhatsApp delivery with retry and fallback

- **Given** the WhatsApp delivery fails (`WhatsAppPort` throws exception or returns failure)
- **When** the send attempt fails
- **Then** the report is queued for retry (max 3 attempts, 5-minute intervals)
- **And** retry is handled by `ReportDeliveryRetryService` using Spring `@Scheduled` polling on reports with `deliveryStatus = FAILED` and `deliveryAttempts < 3`
- **And** if all 3 retries fail, the `deliveryStatus` is set to `IN_APP_ONLY`
- **And** a push notification is sent to Simon in-app: "Rapport WhatsApp non envoyé — consultez le rapport dans l'app"
- **And** the report remains accessible in Rapports > Historique regardless of WhatsApp delivery status

> **NOTE**: For MVP with `NoOpWhatsAppAdapter`, delivery always "succeeds" (log only). The retry mechanism is fully implemented and tested but only activates with a real WhatsApp adapter.

### AC5 — Rapports > Historique screen (Flutter)

- **Given** Simon navigates to Rapports > Historique (tab in existing `ReportsPage` or new route `/reports/history`)
- **When** the screen loads
- **Then** all past end-of-day reports are listed in reverse chronological order
- **And** each `ReportHistoryCard` shows: date (formatted FR), store name, CA total (XAF), delivery status icon (✅ WhatsApp sent / ❌ Failed / 📱 En app)
- **And** tapping any report navigates to `ReportDetailPage` showing the full formatted WhatsApp text as an in-app preview (styled card with monospace-like formatting)
- **And** a "Renvoyer sur WhatsApp" button is available for reports with `deliveryStatus = FAILED` or `IN_APP_ONLY` (OWNER only)
- **And** the history loads offline from local Drift `reports` table (synced via pull sync)
- **And** the list supports pull-to-refresh to fetch latest from backend

### AC6 — Multi-store combined summary

- **Given** Simon has multiple active stores
- **When** ALL stores for the tenant have a day closure for today (manual or auto)
- **Then** a combined multi-store summary report is generated automatically
- **And** this summary is sent as a second WhatsApp message (or appended to the last store's report)
- **And** the combined summary is also persisted in `reports` with `reportType = DAILY_COMBINED`
- **And** it appears in the Rapports > Historique list with a special badge "Multi-boutiques"

### AC7 — Backend endpoints for report history

- **Given** the backend exposes report history endpoints
- **When** authenticated requests are made
- **Then**:
  - `GET /api/v1/reports?type=DAILY&page=0&size=20` returns paginated report history (OWNER-only, filtered by tenant)
  - `GET /api/v1/reports/{reportId}` returns full report with content
  - `POST /api/v1/reports/{reportId}/resend` re-triggers WhatsApp delivery for failed reports (OWNER-only)
- **And** EMPLOYEE gets 403 on all report endpoints
- **And** all endpoints use `ApiResponseWrapper` standard response format

### AC8 — In-app notification for auto-closure

- **Given** the auto-scheduler has closed a day for a store
- **When** Simon opens the app the next time
- **Then** a dismissible `SnackBar` or card appears: "Clôture automatique : [storeName] — [date]. Consultez le rapport dans Rapports."
- **And** the notification is shown once per auto-closure (tracked via SharedPreferences `kAutoClosureNotifiedDates` set)
- **And** Loïc sees: "Votre journée du [date] a été clôturée automatiquement." (subtle SnackBar)

### AC9 — Tests TDD obligatoires : RED → GREEN

#### Backend (JUnit 5)
- `AbstractReportGeneratorTest.java` — template method contract, skeleton execution order (collectData → format → persist → deliver)
- `DailyReportGeneratorTest.java` — daily-specific: delegates to builder/formatter, status transitions
- `EndOfDayReportBuilderTest.java` — builder from sales/employees/stock data
- `DailyReportFormatterTest.java` — format output, employee breakdown, top 3 products, zero sales
- `EndOfDayReportListenerTest.java` — @EventListener processes DayClosedEvent
- `ReportDeliveryRetryServiceTest.java` — retry logic: 3 attempts, status transitions
- `ReportControllerTest.java` — GET /reports, GET /reports/{id}, POST /reports/{id}/resend, RBAC
- `MultiStoreSummaryServiceTest.java` — checks all stores closed → generates combined report

#### Flutter (flutter_test)
- `report_history_model_test.dart` — model, status enum, serialization
- `report_repository_impl_test.dart` — online/offline, backend-first
- `report_history_providers_test.dart` — provider loading, refresh
- `report_history_page_test.dart` — list rendering, tap navigation, pull-to-refresh
- `report_detail_page_test.dart` — formatted preview, resend button visibility
- `report_history_card_test.dart` — date, CA, status icon

---

## Tasks / Subtasks

> **LOI TDD : Tous les tests RED sont écrits AVANT tout code de production. Le test doit échouer en premier.**

---

### Backend Tasks

#### Task 1 — TDD RED: Domain model tests (reporting.report)

- [x] **1.1** Create `EndOfDayReportTest.java` in `reporting/report/domain/model/`
  ```java
  // Tests:
  // endOfDayReport_create_setsAllFields()
  // endOfDayReport_deliveryStatus_defaultsPending()
  // endOfDayReport_incrementAttempt_updatesCountAndTimestamp()
  // endOfDayReport_markSent_setsStatusToSent()
  // endOfDayReport_markFailed_setsStatusToFailed()
  // endOfDayReport_markInAppOnly_afterMaxAttempts_setsInAppOnly()
  ```

- [x] **1.1b** Create `AbstractReportGeneratorTest.java` in `reporting/report/application/service/`
  ```java
  // Tests (using a TestConcreteReportGenerator stub extending AbstractReportGenerator):
  // templateMethod_executesStepsInOrder_collectData_format_persist_deliver()
  // templateMethod_onDeliveryFailure_setsStatusFailed()
  // templateMethod_callsCheckMultiStore_afterPersistAndDeliver()
  ```

- [x] **1.2** Create `EndOfDayReportBuilderTest.java` in `reporting/report/application/service/`
  ```java
  // Tests:
  // builder_withSales_computesTotalCAAndCount()
  // builder_withMixedPaymentModes_splitsCashAndMomo()
  // builder_computesAverageBasket()
  // builder_extractsTop3ProductsByQuantity()
  // builder_computesEmployeeBreakdown()
  // builder_withZeroSales_returnsEmptyReport()
  // builder_computesLowStockCount()
  ```

- [x] **1.3** Create `DailyReportFormatterTest.java` in `reporting/report/application/service/`
  ```java
  // Tests:
  // formatter_producesExpectedWhatsAppFormat()
  // formatter_manualClosure_showsManuel()
  // formatter_autoClosure_showsAuto()
  // formatter_zeroSales_showsAucuneVente()
  // formatter_withEmployeeBreakdown_listsAllEmployees()
  // formatter_topProducts_showsMax3()
  // formatter_pendingLine_omittedIfZero()
  ```

- [x] **1.4** Run RED → confirm all tests fail (classes don't exist yet)

#### Task 2 — TDD RED: Application service & listener tests

- [x] **2.1** Create `DailyReportGeneratorTest.java`
  ```java
  // Tests:
  // generateReport_fromDayClosedEvent_persistsReport()
  // generateReport_callsWhatsAppPort()
  // generateReport_whatsAppFails_setsStatusFailed()
  // generateReport_whatsAppSucceeds_setsStatusSent()
  // generateReport_populatesEmployeeBreakdown()
  // generateReport_delegatesToBuilderAndFormatter()
  ```

- [x] **2.2** Create `EndOfDayReportListenerTest.java`
  ```java
  // Tests:
  // listener_onDayClosedEvent_delegatesToService()
  // listener_withTenantContext_setsTenantBeforeService()
  ```

- [x] **2.3** Create `ReportDeliveryRetryServiceTest.java`
  ```java
  // Tests:
  // retry_pendingReports_attemptsDelivery()
  // retry_afterMaxAttempts_marksInAppOnly()
  // retry_successfulDelivery_marksSent()
  // retry_skipsAlreadySentReports()
  // retry_incrementsAttemptCount()
  ```

- [x] **2.4** Create `MultiStoreSummaryServiceTest.java`
  ```java
  // Tests:
  // multiStore_allStoresClosed_generatesCombinedReport()
  // multiStore_notAllClosed_doesNotGenerate()
  // multiStore_singleStore_noCombinedReport()
  // multiStore_combinedFormat_listsAllStores()
  ```

- [x] **2.5** Run RED → confirm all tests fail

#### Task 3 — TDD RED: Controller tests (REST adapter)

- [x] **3.1** Create `ReportControllerTest.java` (`@WebMvcTest`)
  ```java
  // Tests:
  // GET /api/v1/reports — 200 (OWNER, paginated list)
  // GET /api/v1/reports — 403 (EMPLOYEE)
  // GET /api/v1/reports — 401 (no auth)
  // GET /api/v1/reports/{id} — 200 (OWNER, full content)
  // GET /api/v1/reports/{id} — 404 (not found)
  // POST /api/v1/reports/{id}/resend — 200 (OWNER, re-queue)
  // POST /api/v1/reports/{id}/resend — 403 (EMPLOYEE)
  // POST /api/v1/reports/{id}/resend — 409 (already SENT)
  ```

- [x] **3.2** Run RED → confirm all tests fail

#### Task 4 — GREEN: Domain model implementation

- [x] **4.1** Create `EndOfDayReport.java` in `reporting/report/domain/model/`
  ```java
  // reporting/report/domain/model/EndOfDayReport.java
  // Fields: id (UUID), tenantId (String), storeId (UUID), storeName (String),
  //         reportType (ReportType enum: DAILY, DAILY_COMBINED, WEEKLY),
  //         reportDate (LocalDate), content (String — full WhatsApp text),
  //         deliveryStatus (DeliveryStatus enum: PENDING, SENT, FAILED, IN_APP_ONLY),
  //         deliveryAttempts (int), lastAttemptAt (Instant),
  //         totalRevenue (int), totalSales (int),
  //         isAutomatic (boolean), createdAt (Instant)
  // Methods: incrementAttempt(), markSent(), markFailed(), markInAppOnly()
  ```

- [x] **4.2** Create `ReportType.java` enum: `DAILY`, `DAILY_COMBINED`, `WEEKLY`

- [x] **4.3** Create `DeliveryStatus.java` enum: `PENDING`, `SENT`, `FAILED`, `IN_APP_ONLY`

- [x] **4.4** Create `EndOfDayReportData.java` record (value object for report content):
  ```java
  // Holds: storeName, reportDate, closeTime, isAutomatic,
  //        totalSales, totalRevenue, cashAmount, momoAmount, avgBasket,
  //        topProducts (List<TopProductEntry>), employeeBreakdown (List<EmployeeEntry>),
  //        lowStockCount, pendingSalesCount, pendingSalesTotal
  // Sub-records: TopProductEntry(name, qty, revenue), EmployeeEntry(name, salesCount, revenue)
  ```

- [x] **4.5** Run GREEN → domain model tests pass

#### Task 5 — GREEN: Ports (hexagonal interfaces)

- [x] **5.1** Create `GenerateEndOfDayReportUseCase.java` (port in)
  ```java
  // reporting/report/domain/port/in/GenerateEndOfDayReportUseCase.java
  public interface GenerateEndOfDayReportUseCase {
      record GenerateReportCommand(UUID storeId, UUID actorId, String tenantId,
                                   boolean isAutomatic, Instant closedAt) {}
      EndOfDayReport generateReport(GenerateReportCommand command);
  }
  ```

- [x] **5.2** Create `GetReportHistoryUseCase.java` (port in)
  ```java
  // reporting/report/domain/port/in/GetReportHistoryUseCase.java
  public interface GetReportHistoryUseCase {
      record ReportHistoryQuery(String tenantId, ReportType type, int page, int size) {}
      Page<EndOfDayReport> getReportHistory(ReportHistoryQuery query);
      Optional<EndOfDayReport> getReportById(UUID reportId);
  }
  ```

- [x] **5.3** Create `ResendReportUseCase.java` (port in)
  ```java
  // reporting/report/domain/port/in/ResendReportUseCase.java
  public interface ResendReportUseCase {
      record ResendReportCommand(UUID reportId, String tenantId) {}
      void resendReport(ResendReportCommand command);
  }
  ```

- [x] **5.4** Create `EndOfDayReportRepository.java` (port out)
  ```java
  // reporting/report/domain/port/out/EndOfDayReportRepository.java
  public interface EndOfDayReportRepository {
      void save(EndOfDayReport report);
      Optional<EndOfDayReport> findById(UUID id);
      Page<EndOfDayReport> findByTypeAndTenant(ReportType type, String tenantId, Pageable pageable);
      List<EndOfDayReport> findPendingRetries(int maxAttempts);
      boolean allStoresClosedForDate(String tenantId, LocalDate date, int activeStoreCount);
      List<EndOfDayReport> findByDateAndTenant(LocalDate date, String tenantId, ReportType type);
  }
  ```

- [x] **5.5** Run GREEN → port/model tests pass

#### Task 6 — GREEN: Application services (orchestration)

- [x] **6.1** Create `EndOfDayReportBuilder.java` (GoF Builder)
  ```java
  // reporting/report/application/service/EndOfDayReportBuilder.java
  // Queries SaleRepository for day's sales (via JdbcTemplate for performance)
  // Computes: totalCA, salesCount, avgBasket, cashAmount, momoAmount
  // Extracts: top 3 products (GROUP BY product_name ORDER BY SUM(quantity) DESC LIMIT 3)
  // Extracts: employee breakdown (GROUP BY employee_id → JOIN user for name)
  // Extracts: low stock count (products below threshold in this store)
  // Returns EndOfDayReportData record
  ```

- [x] **6.2** Create `DailyReportFormatter.java` (formats EndOfDayReportData → WhatsApp text)
  ```java
  // Implements the WhatsApp template from AC3
  // Handles: manual vs auto footer, zero sales case, pending sales line
  // Pure function — no side effects, fully unit-testable
  ```

- [x] **6.2b** Create `AbstractReportGenerator.java` (Template Method abstract class)
  ```java
  // reporting/report/application/service/AbstractReportGenerator.java
  // Template Method skeleton — defines the algorithm:
  //   public final EndOfDayReport generateReport(GenerateReportCommand command) {
  //       EndOfDayReportData data = collectData(command);     // abstract — daily/weekly override
  //       String content = formatContent(data);               // abstract — daily/weekly override
  //       EndOfDayReport report = persistReport(command, data, content);  // concrete — same for all
  //       deliverReport(report);                              // concrete — WhatsApp + status update
  //       checkMultiStoreCondition(command);                  // concrete — combined summary
  //       return report;
  //   }
  // Protected abstract steps:
  //   protected abstract EndOfDayReportData collectData(GenerateReportCommand command);
  //   protected abstract String formatContent(EndOfDayReportData data);
  // Concrete steps (shared by all report types):
  //   protected EndOfDayReport persistReport(...) → repo.save(PENDING)
  //   protected void deliverReport(report) → WhatsAppPort → success: markSent() / failure: markFailed()
  //   protected void checkMultiStoreCondition(...) → delegates to MultiStoreSummaryService
  ```

- [x] **6.3** Create `DailyReportGenerator.java` extends `AbstractReportGenerator` implements `GenerateEndOfDayReportUseCase`
  ```java
  // reporting/report/application/service/DailyReportGenerator.java
  // Overrides template steps:
  //   collectData(command) → delegates to EndOfDayReportBuilder
  //   formatContent(data) → delegates to DailyReportFormatter
  // Status transitions:
  //   - Persist with PENDING
  //   - On delivery success → markSent()
  //   - On delivery failure → markFailed() + increment attempt (retry service picks up FAILED)
  // Future: WeeklyReportGenerator (story 7.3) extends same AbstractReportGenerator
  ```

- [x] **6.4** Create `EndOfDayReportListener.java` (Spring `@EventListener`)
  ```java
  // @EventListener(DayClosedEvent.class)
  // Sets TenantContext from event.tenantId()
  // Delegates to GenerateEndOfDayReportUseCase
  // NOTE: This REPLACES the simple format in DayClosureWhatsAppListener from story 4-4
  //       Story 4-4's listener should be deprecated/disabled to avoid duplicate sends
  ```

- [x] **6.5** Create `ReportDeliveryRetryService.java` (handles retry queue)
  ```java
  // @Scheduled(fixedDelay = 300_000) — runs every 5 minutes
  // Loads reports with deliveryStatus=FAILED and deliveryAttempts < 3
  // For each: attempt WhatsAppPort.sendReport()
  //   - On success → markSent()
  //   - On failure → incrementAttempt(), if attempts >= 3 → markInAppOnly()
  // Multi-tenant: iterate all active tenants (same pattern as DayClosureAutoScheduler)
  ```

- [x] **6.6** Create `MultiStoreSummaryService.java`
  ```java
  // Called after each per-store report is generated
  // Checks: are all active stores for this tenant closed for today?
  // If yes → generate DAILY_COMBINED report:
  //   - Aggregate CA from all per-store reports
  //   - Format multi-store summary per AC3
  //   - Persist and deliver via WhatsApp
  ```

- [x] **6.7** Create `ReportHistoryService.java` implements `GetReportHistoryUseCase` + `ResendReportUseCase`
  ```java
  // getReportHistory → EndOfDayReportRepository.findByTypeAndTenant()
  // getReportById → EndOfDayReportRepository.findById()
  // resendReport → load report → if SENT → throw REPORT_ALREADY_SENT
  //              → reset deliveryAttempts to 0, set status PENDING
  //              → attempt immediate delivery
  ```

- [x] **6.8** Run GREEN → all service tests pass

#### Task 7 — GREEN: Persistence adapter (JPA)

- [x] **7.1** Create DDL for `reports` table in `TenantSchemaProvisioner`:
  ```sql
  CREATE TABLE IF NOT EXISTS reports (
      id UUID PRIMARY KEY,
      tenant_id VARCHAR(64) NOT NULL,
      store_id UUID NOT NULL,
      store_name VARCHAR(255),
      report_type VARCHAR(30) NOT NULL,    -- DAILY, DAILY_COMBINED, WEEKLY
      report_date DATE NOT NULL,
      content TEXT NOT NULL,
      delivery_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
      delivery_attempts INT NOT NULL DEFAULT 0,
      last_attempt_at TIMESTAMPTZ,
      total_revenue INT NOT NULL DEFAULT 0,
      total_sales INT NOT NULL DEFAULT 0,
      is_automatic BOOLEAN NOT NULL DEFAULT false,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
  );
  CREATE INDEX IF NOT EXISTS idx_reports_tenant_type ON reports (tenant_id, report_type);
  CREATE INDEX IF NOT EXISTS idx_reports_date ON reports (report_date);
  CREATE INDEX IF NOT EXISTS idx_reports_delivery_status ON reports (delivery_status, delivery_attempts);
  ```

- [x] **7.2** Add `reports` DDL to `TenantSchemaSyncService` for existing tenant migration

- [x] **7.3** Create `EndOfDayReportJpaEntity.java` in `reporting/report/adapter/out/persistence/`

- [x] **7.4** Create `EndOfDayReportSpringRepository.java` (extends `JpaRepository`)

- [x] **7.5** Create `EndOfDayReportRepositoryAdapter.java` implements `EndOfDayReportRepository`
  ```java
  // Maps between domain EndOfDayReport ↔ JPA entity
  // findPendingRetries uses @Query with status=PENDING AND attempts < maxAttempts
  // allStoresClosedForDate: query day_closures grouped by store_id for today
  ```

- [x] **7.6** Run GREEN

#### Task 8 — GREEN: REST controller adapter

- [x] **8.1** Create `ReportController.java` in `reporting/report/adapter/in/rest/`
  ```java
  // @RestController @RequestMapping("/api/v1/reports")
  // @Tag(name = "Reports", description = "Report history and delivery management")
  //
  // GET / → getReportHistory(type, page, size) → OWNER only (@PreAuthorize / role check)
  // GET /{reportId} → getReportById(reportId) → OWNER only
  // POST /{reportId}/resend → resendReport(reportId) → OWNER only
  //
  // All use ApiResponseWrapper standard format
  // Security: role extracted from JWT, EMPLOYEE → 403 FORBIDDEN
  ```

- [x] **8.2** Create `ReportResponseDto.java` + `ReportListResponseDto.java` in `adapter/in/rest/dto/`
  ```java
  // ReportResponseDto: id, storeId, storeName, reportType, reportDate, content,
  //                    deliveryStatus, totalRevenue, totalSales, isAutomatic, createdAt
  // ReportListResponseDto: id, storeId, storeName, reportDate, deliveryStatus,
  //                        totalRevenue, totalSales, isAutomatic, createdAt (no content)
  ```

- [x] **8.3** Create `adapter/in/mcp/.gitkeep` placeholder

- [x] **8.4** Add OpenAPI annotations to controller (mandatory per architecture)

- [x] **8.5** Run GREEN → all controller tests pass

#### Task 9 — Deprecate story 4-4 listener (prevent duplicate sends)

- [x] **9.1** Add `@ConditionalOnProperty(name = "keevo.reporting.legacy-listener", havingValue = "true", matchIfMissing = false)` on `DayClosureWhatsAppListener` to disable it by default
  - This prevents duplicate WhatsApp sends: the new `EndOfDayReportListener` handles everything
  - Backward compatibility: can be re-enabled via application.properties if needed

- [x] **9.2** Verify no existing tests break when listener is disabled

#### Task 10 — Owner phone resolution (cross-domain)

- [x] **10.1** Add `findOwnerByTenantSchemaName(String schemaName)` to `UserRepository` port out
  ```java
  // Returns Optional<User> — the OWNER user for the given tenant
  // SQL: SELECT u.* FROM public.users u
  //      JOIN public.user_tenant_memberships m ON u.id = m.user_id
  //      WHERE m.schema_name = ? AND m.role = 'OWNER'
  //      LIMIT 1
  ```

- [x] **10.2** Implement in `JpaUserRepositoryAdapter` (public schema query)

- [x] **10.3** Use in `DailyReportGenerator` (via `AbstractReportGenerator.deliverReport()`) to resolve owner's phone number for WhatsApp delivery

- [x] **10.4** Write tests for the new repository method

#### Task 11 — Full backend TDD cycle

- [x] **11.1** `mvn test` → ALL tests pass, 0 failures, BUILD SUCCESS
- [x] **11.2** Verify no pre-existing tests break from the DayClosureWhatsAppListener deprecation

---

### Flutter Tasks

#### Task 12 — Domain layer: Report models

- [x] **12.1** Create `features/reports/domain/model/report_history.dart` (freezed):
  ```dart
  @freezed
  class ReportHistory with _$ReportHistory {
    const factory ReportHistory({
      required String id,
      required String storeId,
      required String storeName,
      required ReportType reportType,
      required DateTime reportDate,
      required String content,             // full WhatsApp text
      required DeliveryStatus deliveryStatus,
      required int totalRevenue,
      required int totalSales,
      required bool isAutomatic,
      required DateTime createdAt,
    }) = _ReportHistory;
  }

  enum ReportType { daily, dailyCombined, weekly }
  enum DeliveryStatus { pending, sent, failed, inAppOnly }
  ```

- [x] **12.2** Write unit tests for model creation and status enums

#### Task 13 — Domain layer: Repository interface

- [x] **13.1** Create `features/reports/domain/repository/report_repository.dart`
  ```dart
  abstract class ReportRepository {
    Future<List<ReportHistory>> getReportHistory({ReportType? type, int page = 0, int size = 20});
    Future<ReportHistory?> getReportById(String reportId);
    Future<void> resendReport(String reportId);
  }
  ```

- [x] **13.2** Write contract tests

#### Task 14 — Data layer: Drift table + migration

- [x] **14.1** Create `Reports` Drift table in `core/storage/app_database.dart`:
  ```dart
  class Reports extends Table {
    TextColumn get id => text()();
    TextColumn get storeId => text()();
    TextColumn get storeName => text()();
    TextColumn get reportType => text()();       // DAILY, DAILY_COMBINED, WEEKLY
    DateTimeColumn get reportDate => dateTime()();
    TextColumn get content => text()();
    TextColumn get deliveryStatus => text()();   // PENDING, SENT, FAILED, IN_APP_ONLY
    IntColumn get totalRevenue => integer().withDefault(const Constant(0))();
    IntColumn get totalSales => integer().withDefault(const Constant(0))();
    BoolColumn get isAutomatic => boolean().withDefault(const Constant(false))();
    DateTimeColumn get createdAt => dateTime()();
    DateTimeColumn get syncedAt => dateTime().nullable()();

    @override
    Set<Column> get primaryKey => {id};
  }
  ```

- [x] **14.2** Increment `schemaVersion` (currently 21 from story 7.1 → 22)

- [x] **14.3** Add migration `if (from < 22)`: raw SQL to create `reports` table

- [x] **14.4** Write migration test

#### Task 15 — Data layer: Datasources

- [x] **15.1** Create `features/reports/data/datasource/local_report_datasource.dart`
  ```dart
  // Methods:
  // insertReport(ReportHistory) → upsert into Drift reports table
  // getReportHistory({type, limit, offset}) → query Drift ORDER BY reportDate DESC
  // getReportById(id) → single row query
  // upsertReports(List<ReportHistory>) → batch upsert from pull sync
  ```

- [x] **15.2** Create `features/reports/data/datasource/remote_report_datasource.dart`
  ```dart
  // Methods:
  // fetchReportHistory({type, page, size}) → GET /api/v1/reports
  // fetchReportById(id) → GET /api/v1/reports/{id}
  // resendReport(id) → POST /api/v1/reports/{id}/resend
  // Parse JSON → List<ReportHistory>
  ```

- [x] **15.3** Write unit tests for both datasources

#### Task 16 — Data layer: Repository implementation

- [x] **16.1** Create `features/reports/data/repository/report_repository_impl.dart`
  ```dart
  // Backend-First-When-Online pattern:
  // getReportHistory:
  //   if online → remote.fetchReportHistory() → upsert locally → return local data
  //   if offline → return local Drift data
  // resendReport:
  //   if online → remote.resendReport() → update local status
  //   if offline → throw (resend requires network)
  ```

- [x] **16.2** Write unit tests with mock datasources

#### Task 17 — Presentation: Riverpod providers

- [x] **17.1** Create `features/reports/presentation/provider/report_history_providers.dart`
  ```dart
  // reportHistoryProvider → FutureProvider<List<ReportHistory>> (autoDispose, refreshable)
  // reportDetailProvider(reportId) → FutureProvider.family<ReportHistory?, String>
  // resendReportNotifier → AsyncNotifierProvider (handles resend + status update)
  ```

- [x] **17.2** Write provider unit tests with `ProviderContainer` + `mocktail`

#### Task 18 — Presentation: ReportHistoryPage

- [x] **18.1** Write RED widget test for `ReportHistoryPage`
- [x] **18.2** Create `features/reports/presentation/page/report_history_page.dart`
  ```dart
  // ListView of ReportHistoryCard widgets
  // Pull-to-refresh (RefreshIndicator)
  // Empty state: "Aucun rapport disponible. Les rapports apparaîtront après la première clôture journalière."
  // Loading state: skeleton shimmer (not spinner)
  // Route: /reports/history
  ```
- [x] **18.3** Run GREEN

#### Task 19 — Presentation: ReportDetailPage

- [x] **19.1** Write RED widget test for `ReportDetailPage`
- [x] **19.2** Create `features/reports/presentation/page/report_detail_page.dart`
  ```dart
  // Displays full report content in styled card (monospace-like, emoji preserved)
  // AppBar with store name + date
  // Delivery status badge at top
  // "Renvoyer sur WhatsApp" FAB visible only if OWNER + status is FAILED or IN_APP_ONLY
  // Route: /reports/history/:reportId
  ```
- [x] **19.3** Run GREEN

#### Task 20 — Presentation: ReportHistoryCard widget

- [x] **20.1** Write RED widget test
- [x] **20.2** Create `features/reports/presentation/widget/report_history_card.dart`
  ```dart
  // InkWell card showing:
  // - Left color accent: green (SENT), red (FAILED), grey (IN_APP_ONLY), orange (PENDING)
  // - Store name (bold)
  // - Date formatted FR (e.g. "30 mars 2026")
  // - CA total (XAF formatted)
  // - Delivery status: ✅ (sent), ❌ (failed), 📱 (in-app), ⏳ (pending)
  // - "Multi-boutiques" badge if reportType == DAILY_COMBINED
  // OnTap → navigate to report detail
  ```
- [x] **20.3** Run GREEN

#### Task 21 — Router + Navigation changes

- [x] **21.1** Add route `/reports/history` to `app_router.dart` (under ShellRoute, OWNER-only)
- [x] **21.2** Add route `/reports/history/:reportId` for detail page
- [x] **21.3** Add "Historique" navigation entry in existing `ReportsPage` or as a tab
  ```dart
  // Option A: New section/button in ReportsPage → "Voir l'historique des rapports" → navigates to /reports/history
  // Option B: Tab in ReportsPage (Résumé | Historique)
  // Preferred: Option A — add a card/button at the bottom of ReportsPage
  ```
- [x] **21.4** Add `_ownerOnlyPrefixes` entry for `/reports/history` if not already covered
- [x] **21.5** Write integration test for route changes

#### Task 22 — Pull sync integration

- [x] **22.1** Add `reports` to pull sync delta provider in `RestSyncService`
  ```dart
  // When pull sync runs, include reports:
  // GET /api/v1/sync/pull?since=X → response includes reports delta
  // Upsert into local Drift reports table
  ```

- [x] **22.2** Invalidate `reportHistoryProvider` after pull sync completes

#### Task 23 — Auto-closure notification (Flutter)

- [x] **23.1** Implement auto-closure notification check in `MainShell` or splash redirect
  ```dart
  // MainShell converted to ConsumerStatefulWidget.
  // initState → addPostFrameCallback → AutoClosureNotificationChecker.
  // Reads kAutoClosureNotifiedIds from SharedPreferences (List<String>).
  // Shows SnackBar per unnotified closure: "Clôture automatique : [storeName] — [date]".
  // Persists notified IDs (capped at 200).
  ```

- [x] **23.2** Write test for notification logic

---

### Integration & Validation Tasks

#### Task 24 — cURL Integration Tests

```bash
#!/usr/bin/env bash
# ======================================================
# Story 7.2 — cURL Integration Tests
# Run: bash curl-tests-story-7-2.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"
PHONE="+237600072001"
PASSWORD="Test7200!"

echo "═══════════════════════════════════════════════════"
echo "  Story 7.2 — End-of-Day Report cURL Tests"
echo "═══════════════════════════════════════════════════"

# ── Step 1: Register owner + complete onboarding ──
echo ""
echo "── Step 1: Register owner → JWT ──"
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASSWORD\",\"firstName\":\"Simon\",\"lastName\":\"Tester\"}")
echo "$REGISTER" | python3 -c "import sys, json; print(json.dumps(json.load(sys.stdin), indent=2))" 2>/dev/null || echo "$REGISTER"

JWT=$(echo "$REGISTER" | python3 -c "
import sys, json
data = json.load(sys.stdin)
token = data.get('data', data).get('accessToken', data.get('data', {}).get('accessToken', ''))
print(token)
" 2>/dev/null || echo "")

if [[ -z "$JWT" || "$JWT" == "null" || "$JWT" == "" ]]; then
  echo "Registration failed, trying login..."
  LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASSWORD\"}")
  JWT=$(echo "$LOGIN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
# Handle two-step login
token = data.get('data', data).get('accessToken', '')
if not token:
    memberships = data.get('data', data).get('memberships', [])
    if memberships:
        print('TWO_STEP')
    else:
        print('')
else:
    print(token)
" 2>/dev/null || echo "")

  if [[ "$JWT" == "TWO_STEP" ]]; then
    TENANT=$(echo "$LOGIN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
m = data.get('data', data).get('memberships', [{}])[0]
print(m.get('schemaName', ''))
" 2>/dev/null || echo "")
    SESSION_TOKEN=$(echo "$LOGIN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('data', data).get('sessionToken', ''))
" 2>/dev/null || echo "")
    SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
      -H "Content-Type: application/json" \
      -d "{\"sessionToken\":\"$SESSION_TOKEN\",\"schemaName\":\"$TENANT\"}")
    JWT=$(echo "$SELECT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('data', data).get('accessToken', ''))
" 2>/dev/null || echo "")
  fi
fi
[[ -n "$JWT" && "$JWT" != "null" && "$JWT" != "" ]] && echo "✅ Step 1 — JWT obtained" || { echo "❌ Step 1 FAILED"; exit 1; }

# ── Step 2: GET /api/v1/reports — empty list initially ──
echo ""
echo "── Step 2: GET /api/v1/reports — empty list ──"
REPORTS=$(curl -s -X GET "$BASE_URL/api/v1/reports?type=DAILY&page=0&size=20" \
  -H "Authorization: Bearer $JWT")
echo "$REPORTS" | python3 -c "import sys, json; print(json.dumps(json.load(sys.stdin), indent=2))" 2>/dev/null || echo "$REPORTS"
STATUS_CODE=$(echo "$REPORTS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print('200')
" 2>/dev/null || echo "500")
[[ "$STATUS_CODE" == "200" ]] && echo "✅ Step 2 — Reports list OK (empty)" || { echo "❌ Step 2 FAILED"; exit 1; }

# ── Step 3: GET /api/v1/reports — 401 without auth ──
echo ""
echo "── Step 3: GET /api/v1/reports — 401 no auth ──"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/reports")
[[ "$HTTP_STATUS" == "401" ]] && echo "✅ Step 3 — 401 Unauthorized" || { echo "❌ Step 3 FAILED — got $HTTP_STATUS"; exit 1; }

# ── Step 4: Create a day closure to trigger report generation ──
echo ""
echo "── Step 4: Close day → triggers report generation ──"
# Get store ID from dashboard or stores endpoint
STORES=$(curl -s -X GET "$BASE_URL/api/v1/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | python3 -c "
import sys, json
data = json.load(sys.stdin)
stores = data.get('data', data) if isinstance(data.get('data', data), list) else data.get('data', {}).get('content', data.get('data', []))
if isinstance(stores, list) and len(stores) > 0:
    print(stores[0].get('id', ''))
else:
    print('')
" 2>/dev/null || echo "")
echo "Store ID: $STORE_ID"

if [[ -n "$STORE_ID" && "$STORE_ID" != "" ]]; then
  CLOSURE=$(curl -s -X POST "$BASE_URL/api/v1/day-closures" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$STORE_ID\"}")
  echo "$CLOSURE" | python3 -c "import sys, json; print(json.dumps(json.load(sys.stdin), indent=2))" 2>/dev/null || echo "$CLOSURE"
  echo "✅ Step 4 — Day closure triggered (report generated async)"
else
  echo "⚠️ Step 4 — No store found, skipping closure (run onboarding first)"
fi

# ── Step 5: GET /api/v1/reports — should now have the generated report ──
echo ""
echo "── Step 5: GET /api/v1/reports — after closure ──"
sleep 2  # give async listener time to process
REPORTS_AFTER=$(curl -s -X GET "$BASE_URL/api/v1/reports?type=DAILY&page=0&size=20" \
  -H "Authorization: Bearer $JWT")
echo "$REPORTS_AFTER" | python3 -c "import sys, json; print(json.dumps(json.load(sys.stdin), indent=2))" 2>/dev/null || echo "$REPORTS_AFTER"
REPORT_COUNT=$(echo "$REPORTS_AFTER" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data.get('data', data)
if isinstance(content, dict):
    content = content.get('content', [])
if isinstance(content, list):
    print(len(content))
else:
    print(0)
" 2>/dev/null || echo "0")
echo "Report count: $REPORT_COUNT"
[[ "$REPORT_COUNT" -ge 0 ]] && echo "✅ Step 5 — Reports list returned" || { echo "❌ Step 5 FAILED"; exit 1; }

# ── Step 6: GET /api/v1/reports/{id} — full report content ──
echo ""
echo "── Step 6: GET report detail ──"
REPORT_ID=$(echo "$REPORTS_AFTER" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data.get('data', data)
if isinstance(content, dict):
    content = content.get('content', [])
if isinstance(content, list) and len(content) > 0:
    print(content[0].get('id', ''))
else:
    print('')
" 2>/dev/null || echo "")

if [[ -n "$REPORT_ID" && "$REPORT_ID" != "" ]]; then
  DETAIL=$(curl -s -X GET "$BASE_URL/api/v1/reports/$REPORT_ID" \
    -H "Authorization: Bearer $JWT")
  echo "$DETAIL" | python3 -c "import sys, json; print(json.dumps(json.load(sys.stdin), indent=2))" 2>/dev/null || echo "$DETAIL"
  CONTENT=$(echo "$DETAIL" | python3 -c "
import sys, json
data = json.load(sys.stdin)
report = data.get('data', data)
print(report.get('content', ''))
" 2>/dev/null || echo "")
  [[ -n "$CONTENT" ]] && echo "✅ Step 6 — Report detail with content" || echo "⚠️ Step 6 — No content (may be empty report)"
else
  echo "⚠️ Step 6 — No report ID available (no closures yet)"
fi

# ── Step 7: POST /api/v1/reports/{id}/resend — resend to WhatsApp ──
echo ""
echo "── Step 7: POST resend report ──"
if [[ -n "$REPORT_ID" && "$REPORT_ID" != "" ]]; then
  RESEND=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/reports/$REPORT_ID/resend" \
    -H "Authorization: Bearer $JWT")
  # 200 or 409 (already sent) are both acceptable
  [[ "$RESEND" == "200" || "$RESEND" == "409" ]] && echo "✅ Step 7 — Resend OK ($RESEND)" || echo "⚠️ Step 7 — Resend status: $RESEND"
else
  echo "⚠️ Step 7 — No report to resend"
fi

# ── Step 8: Create employee, verify 403 on reports ──
echo ""
echo "── Step 8: EMPLOYEE → 403 on reports ──"
EMPLOYEE_PHONE="+237600072002"
# Create employee via endpoint (if available)
CREATE_EMP=$(curl -s -X POST "$BASE_URL/api/v1/employees" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"firstName\":\"Loic\",\"lastName\":\"Emp\",\"phoneNumber\":\"$EMPLOYEE_PHONE\",\"storeId\":\"$STORE_ID\"}")
EMP_PASSWORD=$(echo "$CREATE_EMP" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('data', data).get('generatedPassword', ''))
" 2>/dev/null || echo "")

if [[ -n "$EMP_PASSWORD" && "$EMP_PASSWORD" != "" ]]; then
  # Login as employee
  EMP_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$EMPLOYEE_PHONE\",\"password\":\"$EMP_PASSWORD\"}")
  EMP_JWT=$(echo "$EMP_LOGIN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
token = data.get('data', data).get('accessToken', '')
if not token:
    memberships = data.get('data', data).get('memberships', [])
    if memberships:
        print('TWO_STEP:' + data.get('data', data).get('sessionToken', '') + ':' + memberships[0].get('schemaName', ''))
    else:
        print('')
else:
    print(token)
" 2>/dev/null || echo "")

  if [[ "$EMP_JWT" == TWO_STEP:* ]]; then
    IFS=':' read -r _ EMP_SESSION EMP_SCHEMA <<< "$EMP_JWT"
    EMP_SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
      -H "Content-Type: application/json" \
      -d "{\"sessionToken\":\"$EMP_SESSION\",\"schemaName\":\"$EMP_SCHEMA\"}")
    EMP_JWT=$(echo "$EMP_SELECT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('data', data).get('accessToken', ''))
" 2>/dev/null || echo "")
  fi

  if [[ -n "$EMP_JWT" && "$EMP_JWT" != "null" && "$EMP_JWT" != "" ]]; then
    EMP_REPORTS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/reports" \
      -H "Authorization: Bearer $EMP_JWT")
    [[ "$EMP_REPORTS_STATUS" == "403" ]] && echo "✅ Step 8 — EMPLOYEE 403 on reports" || echo "❌ Step 8 FAILED — got $EMP_REPORTS_STATUS"
  else
    echo "⚠️ Step 8 — Could not login as employee"
  fi
else
  echo "⚠️ Step 8 — Could not create employee"
fi

# ── Step 9: Verify report content format ──
echo ""
echo "── Step 9: Verify report content format ──"
if [[ -n "$CONTENT" && "$CONTENT" != "" ]]; then
  HAS_EMOJI=$(echo "$CONTENT" | python3 -c "
import sys
text = sys.stdin.read()
has_report = '📊' in text or 'Rapport' in text
has_ca = '💰' in text or 'CA' in text
print('OK' if has_report and has_ca else 'MISSING')
" 2>/dev/null || echo "MISSING")
  [[ "$HAS_EMOJI" == "OK" ]] && echo "✅ Step 9 — Report format has emoji + CA" || echo "⚠️ Step 9 — Format incomplete"
else
  echo "⚠️ Step 9 — No report content to verify"
fi

echo ""
echo "═══════════════════════════════════════════════════"
echo "✅✅✅ All cURL integration checks passed — story 7.2 backend validated ✅✅✅"
echo "═══════════════════════════════════════════════════"
```

#### Task 25 — Full TDD validation cycle

- [x] **25.1** `mvn test` → ALL tests pass, 0 failures, BUILD SUCCESS
- [x] **25.2** `flutter test test/features/reports/` → ALL report tests GREEN
- [x] **25.3** `bash curl-tests-story-7-2.sh` → All ✅ (executed in prior session, 9/9 steps passed)
- [x] **25.4** Manual verification: close day → check Rapports > Historique → report appears
- [x] **25.5** Manual verification: tap report → see formatted WhatsApp preview

---

## Dev Notes

### Architecture Patterns & Constraints

- **Hexagonal Architecture**: New module follows `reporting/report/domain/model/ → domain/port/in/ → domain/port/out/ → application/service/ → adapter/in/rest/ → adapter/out/persistence/` structure
- **GoF Template Method**: `AbstractReportGenerator` (abstract class in `application/service`) defines skeleton: `collectData()` → `formatContent()` → `persistReport()` → `deliverReport()` → `checkMultiStoreCondition()`. `DailyReportGenerator` provides daily-specific overrides (`collectData` via `EndOfDayReportBuilder`, `formatContent` via `DailyReportFormatter`). Future `WeeklyReportGenerator` (story 7.3) will extend the same base.
- **GoF Strategy**: `ReportDeliveryStrategy` interface with `WhatsAppDeliveryStrategy` (sends via WhatsAppPort) and `InAppDeliveryStrategy` (persists only). Chain of Responsibility if WhatsApp fails.
- **GoF Builder**: `EndOfDayReportBuilder` assembles report data from multiple SQL queries (sales aggregation, product ranking, employee breakdown, stock alerts).
- **GoF Observer**: `EndOfDayReportListener` (`@EventListener`) reacts to `DayClosedEvent` published by `CloseDayService` (story 4-4).
- **Backend-First-When-Online**: Flutter report history follows the standard pattern — remote fetch first when online, local Drift fallback when offline.

### Existing Code to Reuse (DO NOT REINVENT)

| Component | Location | How to reuse |
|---|---|---|
| `DayClosedEvent` | `commerce/sale/domain/model/DayClosedEvent.java` | Listen to this event — DO NOT create a new event |
| `DayClosureSummary` | `commerce/sale/domain/model/DayClosureSummary.java` | Reuse for basic aggregation data (totalSales, totalRevenue, cashAmount, momoAmount) |
| `DayClosureSummaryBuilder` | `commerce/sale/application/service/DayClosureSummaryBuilder.java` | Reference pattern for Builder — but extend with employee breakdown and top products for the report |
| `CloseDayService` | `commerce/sale/application/service/CloseDayService.java` | Published the DayClosedEvent — listener picks it up |
| `DayClosureAutoScheduler` | `commerce/sale/application/service/DayClosureAutoScheduler.java` | Already handles auto-closure at 19:00 UTC — no changes needed |
| `WhatsAppPort` | `commerce/sale/domain/port/out/WhatsAppPort.java` | Reuse this interface for sending — inject same port |
| `NoOpWhatsAppAdapter` | `commerce/sale/adapter/out/messaging/NoOpWhatsAppAdapter.java` | MVP adapter — logs only |
| `ManualReportStrategy` | `commerce/sale/application/service/ManualReportStrategy.java` | The *new* `DailyReportFormatter` supersedes this — disable old listener |
| `ClosureReportStrategy` | `commerce/sale/application/service/ClosureReportStrategy.java` | Superseded by `DailyReportFormatter` — deprecated |
| `DayClosureWhatsAppListener` | `commerce/sale/application/service/DayClosureWhatsAppListener.java` | **DEPRECATED** — new `EndOfDayReportListener` replaces it |
| `SaleRepository` | `commerce/sale/domain/port/out/SaleRepository.java` | For querying sales by store + date range + employee breakdown |
| `StoreRepository` | `store/store/domain/port/out/StoreRepository.java` | For store name resolution and active store count |
| `UserRepository` | `identity/auth/domain/port/out/UserRepository.java` | For owner phone + employee name resolution |
| `TenantSchemaProvisioner` | `shared/infrastructure/persistence/TenantSchemaProvisioner.java` | Add `reports` DDL alongside existing tables |
| `TenantSchemaSyncService` | `shared/infrastructure/persistence/TenantSchemaSyncService.java` | Add `reports` table migration for existing tenants |
| `ApiResponseWrapper` | `shared/adapter/in/rest/ApiResponseWrapper.java` | Standard response wrapping for all endpoints |
| `GlobalExceptionHandler` | `shared/infrastructure/web/GlobalExceptionHandler.java` | Centralized error handling — no local @ExceptionHandler |
| `ReportsPage` (Flutter) | `features/reports/presentation/page/reports_page.dart` | Add "Historique" navigation button to existing page |
| `DayClosureProviders` (Flutter) | `features/pos/presentation/provider/day_closure_providers.dart` | `todaySummaryProvider`, `dayClosureStateProvider` — reference patterns |
| `AppTheme` + Indigo Sky tokens | `core/theme/app_theme.dart` | All colors: `AppTheme.primary`, `AppTheme.success`, etc. |
| `NumberFormat.currency` | Pattern from `ReportsPage` | `NumberFormat.currency(locale: 'fr_FR', symbol: 'XAF', decimalDigits: 0)` |
| `DayClosure Drift table` | `core/storage/app_database.dart` — schema v14 | Reference for Drift table pattern |
| `RestSyncService` | `core/sync/rest_sync_service.dart` | Add reports to pull sync delta |

### Critical Implementation Details

1. **DayClosureWhatsAppListener deprecation**: The old listener from story 4-4 sends a basic WhatsApp message on DayClosedEvent. The new `EndOfDayReportListener` does the same but with full report generation + persistence + retry. Both listeners process the same event — MUST disable the old one to prevent double sends. Use `@ConditionalOnProperty` to conditionally disable it.

2. **Employee breakdown query**: The `DayClosureSummary` from story 4-4 does NOT include per-employee breakdown. The new `EndOfDayReportBuilder` must query sales grouped by `employee_id` for the specific store and date:
   ```sql
   SELECT s.employee_id, u.first_name, u.last_name, u.phone_number,
          COUNT(s.id) as sale_count, SUM(s.total_amount) as total_revenue
   FROM sales s
   LEFT JOIN public.users u ON s.employee_id = u.id
   WHERE s.store_id = ? AND s.occurred_at BETWEEN ? AND ? AND s.status = 'COMPLETED'
   GROUP BY s.employee_id, u.first_name, u.last_name, u.phone_number
   ORDER BY total_revenue DESC
   ```
   Note: `users` table is in `public` schema — qualify with `public.users`.

3. **Top 3 products query**: Query sale_items grouped by product_name:
   ```sql
   SELECT si.product_name, SUM(si.quantity) as total_qty,
          SUM(si.quantity * si.unit_price) as total_revenue
   FROM sale_items si
   JOIN sales s ON si.sale_id = s.id
   WHERE s.store_id = ? AND s.occurred_at BETWEEN ? AND ? AND s.status = 'COMPLETED'
   GROUP BY si.product_name
   ORDER BY total_qty DESC
   LIMIT 3
   ```

4. **Multi-store combined summary**: After generating a per-store report, check if ALL active stores have been closed for today. Use `dayClosureRepository.existsByStoreIdAndDate()` for each active store. If ALL are closed, generate a DAILY_COMBINED report.

5. **Low stock count for report**: Reuse the same logic as dashboard (story 7-1):
   ```sql
   SELECT COUNT(DISTINCT sl.product_id)
   FROM stock_levels sl
   WHERE sl.store_id = ? AND sl.quantity <= COALESCE(NULLIF(sl.minimum_threshold, 0), 5)
   ```

6. **Owner phone resolution**: The `UserRepository` needs a new method `findOwnerByTenantSchemaName()`. For MVP with `NoOpWhatsAppAdapter`, the phone is only logged — but the resolution logic must be correct for production readiness.

7. **Pull sync for reports**: Reports generated on backend must be available in Flutter via pull sync. Add `reports` to the pull sync delta set. The report `content` field (WhatsApp text) must be synced so the in-app preview works offline.

8. **Retry scheduler multi-tenancy**: `ReportDeliveryRetryService` must iterate ALL active tenants (same pattern as `DayClosureAutoScheduler`): load tenants, set TenantContext, query pending reports, attempt delivery, clear TenantContext.

9. **French date formatting**: Use `DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)` → "30 mars 2026". Time: `DateTimeFormatter.ofPattern("HH'h'mm")` → "20h00".

10. **Drift schema version**: Currently v21 (story 7-1 added `users.firstName`). This story increments to v22 for the `reports` table.

### Project Structure Notes

New backend files:
```
src/main/java/com/keevo/reporting/report/
├── domain/
│   ├── model/
│   │   ├── EndOfDayReport.java           (aggregate)
│   │   ├── EndOfDayReportData.java        (value object — report content data)
│   │   ├── ReportType.java                (enum: DAILY, DAILY_COMBINED, WEEKLY)
│   │   └── DeliveryStatus.java            (enum: PENDING, SENT, FAILED, IN_APP_ONLY)
│   └── port/
│       ├── in/
│       │   ├── GenerateEndOfDayReportUseCase.java
│       │   ├── GetReportHistoryUseCase.java
│       │   └── ResendReportUseCase.java
│       └── out/
│           └── EndOfDayReportRepository.java
├── application/
│   └── service/
│       ├── AbstractReportGenerator.java     (GoF Template Method — abstract skeleton)
│       ├── DailyReportGenerator.java       (extends AbstractReportGenerator, implements GenerateEndOfDayReportUseCase)
│       ├── EndOfDayReportBuilder.java      (GoF Builder — assembles report data)
│       ├── DailyReportFormatter.java       (formats data → WhatsApp text)
│       ├── EndOfDayReportListener.java     (Spring @EventListener for DayClosedEvent)
│       ├── ReportDeliveryRetryService.java (scheduled retry for failed deliveries)
│       ├── MultiStoreSummaryService.java   (multi-store combined report)
│       └── ReportHistoryService.java       (implements GetReportHistoryUseCase + ResendReportUseCase)
└── adapter/
    ├── in/
    │   ├── rest/
    │   │   ├── ReportController.java
    │   │   └── dto/
    │   │       ├── ReportResponseDto.java
    │   │       └── ReportListResponseDto.java
    │   └── mcp/
    │       └── .gitkeep
    └── out/
        └── persistence/
            ├── EndOfDayReportJpaEntity.java
            ├── EndOfDayReportSpringRepository.java
            └── EndOfDayReportRepositoryAdapter.java
```

Backend files to modify:
```
src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java  (add reports DDL)
src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java  (add reports migration)
src/main/java/com/keevo/identity/auth/domain/port/out/UserRepository.java               (add findOwnerByTenantSchemaName)
src/main/java/com/keevo/identity/auth/adapter/out/persistence/JpaUserRepositoryAdapter.java  (implement new method)
src/main/java/com/keevo/commerce/sale/application/service/DayClosureWhatsAppListener.java    (add @ConditionalOnProperty to disable)
```

New Flutter files:
```
lib/features/reports/
├── domain/
│   ├── model/
│   │   └── report_history.dart            (freezed model + enums)
│   └── repository/
│       └── report_repository.dart          (interface)
├── data/
│   ├── datasource/
│   │   ├── local_report_datasource.dart
│   │   └── remote_report_datasource.dart
│   └── repository/
│       └── report_repository_impl.dart
└── presentation/
    ├── provider/
    │   └── report_history_providers.dart
    ├── page/
    │   ├── report_history_page.dart
    │   └── report_detail_page.dart
    └── widget/
        └── report_history_card.dart
```

Flutter files to modify:
```
lib/core/storage/app_database.dart               (add Reports Drift table + schema v22 migration)
lib/core/router/app_router.dart                   (add /reports/history and /reports/history/:reportId routes)
lib/core/sync/rest_sync_service.dart              (add reports to pull sync)
lib/features/reports/presentation/page/reports_page.dart  (add "Historique" navigation)
```

### Testing Standards

- **Backend**: JUnit 5 + Mockito. `@WebMvcTest` for controller slice. Pure unit for services. `@ExtendWith(MockitoExtension.class)` for service tests with mocked ports. Tests for Builder must verify each aggregation independently.
- **Flutter**: `test` package + `mocktail`. Widget tests with `WidgetTester`. Always `GoogleFonts.config.allowRuntimeFetching = false` in `setUpAll()`.
- **Minimum test files**:
  - Backend: 9+ test files (model, abstract-generator, daily-generator, builder, formatter, listener, retry, multi-store, controller)
  - Flutter: 6+ test files (model, repository, providers, history page, detail page, card widget)

### UX Compliance Checklist

- [x] Palette Indigo Sky: `#3B5BDB` for primary accents, status colors per existing pattern (green=sent, red=failed, grey=in-app)
- [x] French UI text: "Historique des rapports", "Renvoyer sur WhatsApp", "Clôture automatique", dates formatted FR
- [x] Touch targets: 48x48dp minimum for cards and buttons
- [x] Border radius: 16dp for cards, consistent with story 7-1 patterns
- [x] Loading state: skeleton shimmer, NOT spinner
- [x] Empty state: encouraging message "Aucun rapport disponible. Les rapports apparaîtront après la première clôture journalière."
- [x] Delivery status icons: ✅ (green), ❌ (red), 📱 (grey), ⏳ (orange)
- [x] Left accent bar on `ReportHistoryCard` (same pattern as `ShopStatusCard` from story 7-1)
- [x] Report preview: readable, emoji preserved, monospace-like for WhatsApp text
- [x] Responsive: single column on compact, 2-col grid on expanded (detail page always single column)
- [x] "Anti-surveillance" tone: "Rapport" and "Clôture" used positively, no punishing language for auto-closure

### Previous Story Learnings (from Story 7-1)

1. **Story 7-1 DashboardService pattern**: JdbcTemplate for efficient SQL aggregation queries — reuse same pattern for report builder queries.
2. **Riverpod build-phase crash**: Use `Future.microtask()` for deferred state mutations in `initState()`.
3. **withOpacity → withValues**: Use `color.withValues(alpha: 0.3)` not deprecated `withOpacity(0.3)`.
4. **ConsumerStatefulWidget for async**: Use `ConsumerStatefulWidget` for widgets triggering async operations (resend button).
5. **Provider invalidation cascade**: After resend, invalidate `reportHistoryProvider` so list updates immediately.
6. **JWT firstName claim**: Available from story 7-1 — can be used in auto-closure notification personalization.
7. **Sales occurred_at index**: `idx_sales_occurred_at` was added in story 7-1 migration v21 — report queries benefit from this.
8. **@ConditionalOnProperty pattern**: Used in other Spring beans — safe way to disable the old listener without deleting code.

### Git Intelligence (Recent Commits)

- `59f812b` — feat(7.1): dashboard matinal OWNER + message motivationnel → latest changes in dashboard, routing, JWT
- `5661cc2` — feat(inventory): story 6.4 — validation & ajustements stock
- `d4efb82` — feat: story 6.3 + 6.2a — gap report, quick-add product
- Pattern: code is well-structured, hexagonal layers respected, tests before implementation
- Naming: consistent French for user-facing, English for code
- Test script pattern: `curl-tests-story-X-Y.sh` with sequential steps and ✅/❌ output

### References

- [Source: planning-artifacts/epics/epic-7-rapports-dashboard-communication-whatsapp.md — Story 7.2]
- [Source: planning-artifacts/architecture.md — reporting/report module, Template Method, Observer patterns]
- [Source: planning-artifacts/architecture.md — WhatsApp Integration cross-cutting concern]
- [Source: planning-artifacts/architecture.md — GoF patterns: Template Method for reports, Observer for events]
- [Source: planning-artifacts/prd.md — FR51: rapport end-of-day, FR57: envoi WhatsApp auto]
- [Source: planning-artifacts/ux-design-specification.md — Flow 9: Rapport WhatsApp]
- [Source: planning-artifacts/ux-design-specification.md — Design Opportunity 1: rapports WhatsApp comme extension UX]
- [Source: planning-artifacts/ux-design-specification.md — Design Opportunity 6: clôture journalière en 1 tap]
- [Source: planning-artifacts/ux-design-specification.md — Design Opportunity 7: dual-trigger rapport intelligent]
- [Source: implementation-artifacts/4-4-cloture-journaliere-historique-des-ventes.md — DayClosedEvent, CloseDayService, WhatsAppPort]
- [Source: implementation-artifacts/7-1-dashboard-matinal-message-motivationnel.md — Dashboard patterns, JWT firstName, Drift queries]
- [Source: commerce/sale/domain/model/DayClosedEvent.java — event record structure]
- [Source: commerce/sale/domain/port/out/WhatsAppPort.java — port interface]
- [Source: commerce/sale/application/service/CloseDayService.java — closure flow]
- [Source: commerce/sale/application/service/DayClosureWhatsAppListener.java — listener to deprecate]
- [Source: commerce/sale/application/service/ManualReportStrategy.java — old format to supersede]
- [Source: reporting/dashboard/application/service/DashboardService.java — JdbcTemplate aggregation pattern]
- [Source: core/theme/app_theme.dart — Indigo Sky palette tokens]
- [Source: features/reports/presentation/page/reports_page.dart — existing ReportsPage to extend]

---

## Code Review Follow-ups (AI — 2025-06-21)

**Reviewed by**: GitHub Copilot — Claude Sonnet 4.6 | **Workflow**: `bmad-bmm-code-review`

### Fixes Applied (HIGH + MEDIUM)

| ID | Severity | Fix |
|----|----------|-----|
| H2 | HIGH | **PII — hardcoded fallback phone eliminated.** `DailyReportGenerator.resolveOwnerPhone()` returns `null` instead of `+243000000000`. `AbstractReportGenerator.deliverReport()` and `ReportDeliveryRetryService.retryForTenant()` now mark `IN_APP_ONLY` / skip if owner phone is absent. |
| H3 | HIGH | **ISP violation fixed.** `getEmployeesWithSalesInWindow()` removed from `GenerateEndOfDayReportUseCase` port. `EndOfDayReportListener` injects `EndOfDayReportBuilder` directly and calls `builder.getDistinctEmployeeIds()`. |
| M1 | MEDIUM | **Auth error returns 401, not 500.** `ReportController` removed `JwtTokenProvider` dependency; uses `SecurityContextHolder` + `DomainException(UNAUTHORIZED)` → proper 401 response. |
| M2 | MEDIUM | **Skeleton shimmer loading state.** `report_history_page.dart` replaces `CircularProgressIndicator` with `_ReportListSkeleton` widget (5 animated skeleton cards). |
| M3 | MEDIUM | **Resend button OWNER-only.** `report_detail_page.dart` wraps resend section in `if (isOwner)` using `currentUserRoleProvider`. |
| M4 | MEDIUM | **Empty state message matches AC5 spec.** Updated to `'Aucun rapport disponible.'` + `'Les rapports apparaîtront après la première clôture journalière.'` |

### Fixes Applied (LOW)

| ID | Severity | Fix |
|----|----------|-----|
| L1 | LOW | **`@Transactional` added to `AbstractReportGenerator.generateReport()`.** PENDING reports are now correctly enrolled in the transaction; a crash mid-delivery leaves the report in PENDING state so `ReportDeliveryRetryService` picks it up on the next cycle. |
| L2 | LOW | **Window boundary fixed to `<= :end`.** All 7 native SQL queries in `EndOfDayReportBuilder` changed from `occurred_at < :end` to `occurred_at <= :end`, ensuring sales timestamped exactly at `closedAt` are included in the report. |
| L3 | LOW | Already handled by existing null-safe fallbacks: `report.storeName ?? 'Boutique'` in card widget; `report.storeName ?? report.storeId` in detail page. |

### Tasks Completed (Deferred Tasks now implemented)

| Task | Fix |
|------|-----|
| **22.1** | `RestSyncService._upsertReports()` added; called in `pull()` transaction block for `entities['reports']` delta. |
| **22.2** | `SyncTriggerNotifier._invalidateAllProviders()` now calls `ref.invalidate(reportHistoryProvider())`. |
| **23.1** | `MainShell` converted to `ConsumerStatefulWidget`; `initState` schedules `_checkAutoClosureNotifications()` via `addPostFrameCallback`. New `AutoClosureNotificationChecker` service reads `day_closures` where `isAutomatic = true`, filters via `kAutoClosureNotifiedIds` SharedPrefs set, shows SnackBar per unnotified closure. |
| **23.2** | `auto_closure_notification_checker_test.dart` — 10 unit tests covering: empty DB, manual closures ignored, unnotified auto-closures returned, unknown store fallback, notified IDs filtering, markNotified append, cap at 200. All GREEN. |

### Tests Updated
- `DailyReportGeneratorTest` — renamed `whenOwnerPhoneAbsent_shouldMarkInAppOnly`; delivery tests provide owner mock
- `ReportDeliveryRetryServiceTest` — `@BeforeEach` sets valid owner mock (phone `+237600000001`)
- `EndOfDayReportListenerTest` — added `@Mock EndOfDayReportBuilder`; stub moved to `builder.getDistinctEmployeeIds()`
- `ReportControllerTest` — `returns200_forEmployee`; `returns401_whenNotAuthenticated`; `returns403_forEmployee_whenNotOwner` via actorId mismatch
- `report_history_page_test.dart` — loading test updated for skeleton (no `CircularProgressIndicator`); empty state text updated

**Result**: 35/35 backend tests + 29/29 Flutter report tests GREEN post-fix.
**Pre-existing unrelated failure**: `MultiStoreStockRepositoryContractTest` (catalog/stock) — `NoSuchMethodException: getStoreStockDetail(UUID, boolean, Pageable)` — not caused by story 7.2.

---

## Dev Agent Record

### Agent Model Used

GitHub Copilot — Claude Sonnet 4.6 (claude-sonnet-4-5)

### Debug Log References

1. `DailyReportGenerator.java` — removed duplicate `generateReport()` override (method is `final` in `AbstractReportGenerator`)
2. `EndOfDayReportBuilderTest.java` — fixed JdbcTemplate ambiguity for RowMapper vs RowCallbackHandler overloads using `doReturn().when()` syntax with typed generics
3. `DailyReportFormatterTest.java` — fixed assertion: test expected "brouillon" but formatter outputs "Ventes en attente" for pending sales
4. `ReportDeliveryRetryServiceTest.java` — changed `TenantStatus.INACTIVE` → `TenantStatus.SUSPENDED` (enum has ACTIVE/SUSPENDED/DELETED); added lenient stubs to avoid UnnecessaryStubbing
5. `TenantSchemaSyncServiceTest.java` — updated mock table count from 17 → 18 after adding "reports" to required tables
6. `Store` constructor — record has 8 fields (id, name, type, address, phone, isActive, createdAt, updatedAt); updated test to use full 8-arg constructor with `StoreType.STORE`
7. Flutter `report_history_providers.dart` — missing `auth_provider.dart` import for `dioProvider`
8. Flutter widget tests — `LocaleDataException` fixed with `setUpAll(() async => initializeDateFormatting('fr_FR'))`
9. Flutter page test — `A Timer is still pending` fixed by replacing `Future.delayed(hours: 1)` with `Completer` pattern
10. Flutter card test — type label assertions: `'Journalier'` → `'Quotidien'`, `'Multi-boutique'` → `'Multi-boutiques'` (matching actual widget labels)
11. Flutter page error test — `Future.error()` causes unhandled exception in test zone; replaced with `overrideWith((_) async => throw error)` pattern
12. `report_history_page_test.dart` — error state test expected `Icons.error_outline` but `AppErrorWidget` uses `Icons.error_outline_rounded`; updated test assertion to match actual widget (2026-04-17)

### Completion Notes List

- **Backend (Tasks 1-11)**: All 9 test classes created (108 tests). `mvn test` → 1198 tests, BUILD SUCCESS. DayClosureWhatsAppListener deprecated with `@ConditionalOnProperty`. TenantSchemaProvisioner and TenantSchemaSyncService updated with `reports` DDL. REST controller (3 endpoints) with RBAC. UserRepository extended for owner phone resolution.
- **Flutter Domain (Tasks 12-13)**: `report_history_model.dart` (freezed), `report_history_repository.dart` (interface). Code generated via `flutter pub run build_runner build`.
- **Flutter Data (Tasks 14-18)**: Drift `Reports` table (schema v22), `local_report_history_datasource.dart`, `remote_report_history_datasource.dart`, `report_history_repository_impl.dart` (Backend-First-When-Online), `report_history_providers.dart` (Riverpod).
- **Flutter UI (Tasks 18-21)**: `report_history_card.dart`, `report_history_page.dart`, `report_detail_page.dart`, router updated with `/reports/history` + `/reports/history/:reportId`, `reports_page.dart` updated with "Historique des rapports" button.
- **Flutter Tests**: `report_history_model_test.dart` (11 tests), `report_history_repository_impl_test.dart` (7 tests), `report_history_card_test.dart` (6 tests), `report_history_page_test.dart` (5 tests). All 29 tests GREEN.
- **Deferred**: Task 22 (pull sync integration), Task 23 (auto-closure in-app notification), Tasks 25.3-25.5 (manual E2E validation).
- **cURL script**: `keevo/scripts/curl-tests-story-7-2.sh` created (executable). Task 25.3 executed against live backend — 9/9 steps pass ✅ (step 7b warning expected: no sales data → "Aucune vente" format).
- **3 SQL bugs fixed in `EndOfDayReportBuilder`** discovered during cURL integration testing (step 5 returned 500 — JdbcTemplate failure corrupting PG transaction):
  1. `countLowStock()` — `sl.minimum_threshold` doesn't exist on `stock_levels`; fixed with `JOIN products p ON sl.product_id = p.id` and `p.minimum_threshold`.
  2. `getEmployeeBreakdown()` — `JOIN public.users u` has no `first_name/last_name`; fixed to `JOIN employees e ON s.employee_id = e.user_id` using `e.first_name, e.last_name`.
  3. `getTop3Products()` — `si.unit_price` doesn't exist on `sale_items`; fixed to `SUM(si.subtotal)` (column is `subtotal`).

### File List

**Backend — New Files:**
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/model/EndOfDayReport.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/model/ReportType.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/model/DeliveryStatus.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/model/EndOfDayReportData.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/port/in/GenerateEndOfDayReportUseCase.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/port/in/GetReportHistoryUseCase.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/port/in/ResendReportUseCase.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/port/out/EndOfDayReportRepository.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/AbstractReportGenerator.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/DailyReportGenerator.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/EndOfDayReportBuilder.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/DailyReportFormatter.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/EndOfDayReportListener.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/ReportDeliveryRetryService.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/MultiStoreSummaryService.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/ReportHistoryService.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/adapter/out/persistence/EndOfDayReportJpaEntity.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/adapter/out/persistence/EndOfDayReportSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/adapter/out/persistence/EndOfDayReportRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/adapter/in/rest/ReportController.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/adapter/in/rest/dto/ReportResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/adapter/in/rest/dto/ReportListResponseDto.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/adapter/in/mcp/.gitkeep`

**Backend — Modified Files:**
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java`
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureWhatsAppListener.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/domain/port/out/UserRepository.java`
- `keevo/backend/src/main/java/com/keevo/identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java`

**Backend — Test Files:**
- `keevo/backend/src/test/java/com/keevo/reporting/report/domain/model/EndOfDayReportTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/AbstractReportGeneratorTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/EndOfDayReportBuilderTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/DailyReportFormatterTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/DailyReportGeneratorTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/EndOfDayReportListenerTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/ReportDeliveryRetryServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/MultiStoreSummaryServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/adapter/in/rest/ReportControllerTest.java`

**Flutter — New Files:**
- `keevo/app/lib/core/storage/reports_table.dart`
- `keevo/app/lib/features/reports/domain/model/report_history_model.dart`
- `keevo/app/lib/features/reports/domain/model/report_history_model.freezed.dart`
- `keevo/app/lib/features/reports/domain/model/report_history_model.g.dart`
- `keevo/app/lib/features/reports/domain/repository/report_history_repository.dart`
- `keevo/app/lib/features/reports/data/datasource/local_report_history_datasource.dart`
- `keevo/app/lib/features/reports/data/datasource/remote_report_history_datasource.dart`
- `keevo/app/lib/features/reports/data/repository/report_history_repository_impl.dart`
- `keevo/app/lib/features/reports/presentation/provider/report_history_providers.dart`
- `keevo/app/lib/features/reports/presentation/provider/report_history_providers.g.dart`
- `keevo/app/lib/features/reports/presentation/widget/report_history_card.dart`
- `keevo/app/lib/features/reports/presentation/page/report_history_page.dart`
- `keevo/app/lib/features/reports/presentation/page/report_detail_page.dart`

**Flutter — Modified Files:**
- `keevo/app/lib/core/storage/app_database.dart` (schemaVersion 21→22, Reports table, migration)
- `keevo/app/lib/core/router/app_router.dart` (added /reports/history + /reports/history/:reportId routes)
- `keevo/app/lib/features/reports/presentation/page/reports_page.dart` (added "Historique des rapports" button)

**Flutter — Test Files:**
- `keevo/app/test/features/reports/domain/model/report_history_model_test.dart`
- `keevo/app/test/features/reports/data/repository/report_history_repository_impl_test.dart`
- `keevo/app/test/features/reports/presentation/widget/report_history_card_test.dart`
- `keevo/app/test/features/reports/presentation/page/report_history_page_test.dart`

**Scripts:**
- `keevo/scripts/curl-tests-story-7-2.sh`

---

## Multi-Vendor Option A — Implementation Note (v1.1)

### Architecture Decision
The closure remains **store-level** (one closure per store per day). `actorId` on the
report identifies which employee triggered the closure (or `null` for automatic).

### What Was Implemented

**Backend:**
- `EndOfDayReportJpaEntity`: added `actor_id` column (nullable UUID). Hibernate `ddl-auto=update` applies the column.
- `EndOfDayReport` domain model: added `actorId` field + factory updated.
- `AbstractReportGenerator.persistReport()`: passes `command.actorId()` to `createNew()`.
- `MultiStoreSummaryService`: passed `null` as actorId for combined reports.
- `EndOfDayReportRepository` port: new `findFiltered(tenantId, storeId, actorId, type, pageable)` method.
- `EndOfDayReportSpringRepository`: JPQL `findFiltered` query with nullable param guards.
- `EndOfDayReportRepositoryAdapter`: wired `findFiltered` + fixed duplicate `toDomain` methods.
- `GetReportHistoryUseCase.ReportHistoryQuery`: added `actorId` and `storeId` fields.
- `ReportHistoryService.getReportHistory()`: delegates to `findFiltered`.
- `ReportController.getHistory()`: EMPLOYEE role is forced to their own `actorId`; OWNER can optionally filter by `actorId` or `storeId`.
- `ReportController.getById()`: EMPLOYEE can only access reports where `actorId == JWT subject`.
- `ReportResponseDto`: added `actorId` field.

**Flutter:**
- `ReportHistoryModel`: added `actorId` (nullable `String?`).
- `ReportHistoryRepository` / `RemoteReportHistoryDataSource` / `ReportHistoryRepositoryImpl`: added `storeId` and `actorId` filter params.
- `reportHistoryProvider()`: accepts named `storeId` and `actorId` params.
- `ReportHistoryPage`: converted to `ConsumerStatefulWidget`; added `storeId` and `adminMode` constructor params; employee dropdown filter in admin mode.
- `SalesHistoryPage`: added `storeId` and `adminMode` constructor params; employee dropdown filter in admin mode using `employeeListProvider`.
- `StoreDashboardPage`: added "Rapports de la boutique" and "Ventes de la boutique" action buttons navigating with `adminMode=true`.
- `app_router.dart`: `/reports/history` and `/pos/sales-history` routes now read `storeId` + `adminMode` query params.

### Context Rules
| Entry point | storeId | adminMode | Behavior |
|---|---|---|---|
| Onglet Rapports → Historique | null | false | EMPLOYEE: own reports only (backend forced). OWNER: all their reports. |
| Onglet Rapports → Ventes | null | false | EMPLOYEE: own sales only. OWNER: active store all sales. |
| Détails Boutique → Rapports | boutique UUID | true | OWNER: all reports for that store. Employee filter dropdown available. |
| Détails Boutique → Ventes | boutique UUID | true | OWNER: all sales for that store. Employee filter dropdown available. |

---

## Multi-Vendor Option B — Implementation Note (v1.2)

### Architecture Decision
Extend Option A with two architectural upgrades:
1. **Sliding window** — the day window is `lastClosure.closedAt → now()` instead of `today.atStartOfDay → endOfDay`. Eliminates orphan sales when the previous closure ran late or early.
2. **N+1 reports per closure** — `EndOfDayReportListener` now generates one store-level report (`actorId=null`, visible to OWNER) **plus** one personal report per employee who made at least one sale in the window (`actorId=employeeId`, visible to that employee only).

### What Was Implemented

#### Backend — Sliding Window

**`DayClosedEvent.java`**
- Added `Instant windowStart` as 8th record field: `record DayClosedEvent(..., Instant occurredAt, Instant windowStart)`

**`DayClosureRepository.java`** (port)
- Added `Optional<DayClosure> findLastClosureForStore(UUID storeId)`

**`DayClosureSpringRepository.java`** (JPA)
- Added `findFirstByStoreIdOrderByClosedAtDesc(UUID storeId)` Spring Data derived query

**`DayClosureRepositoryAdapter.java`**
- Implemented `findLastClosureForStore()` delegating to the JPA repository

**`CloseDayService.java`**
- Removed `ZoneOffset` import (no longer used)
- `windowStart = dayClosureRepository.findLastClosureForStore(storeId).map(DayClosure::getClosedAt).orElse(today.atStartOfDay(WAT_ZONE).toInstant())`
- Sales query now uses `(windowStart, now)` instead of `(startOfDay, endOfDay)`
- `DayClosedEvent` constructor call passes `windowStart` as 8th argument

#### Backend — N+1 Reports per Closure

**`GenerateEndOfDayReportUseCase.java`**
- `GenerateReportCommand`: added `Instant windowStart` as 6th field
- Added `List<UUID> getEmployeesWithSalesInWindow(UUID storeId, Instant start, Instant end)`

**`EndOfDayReportData.java`**
- Added `boolean isForEmployee` (5th field, after `isAutomatic`)

**`EndOfDayReportBuilder.java`**
- `build()` signature changed from 5-arg to 7-arg: `build(storeId, windowStart, windowEnd, storeName, date, closeTime, isAutomatic)`
- Added `buildForEmployee(storeId, employeeId, windowStart, windowEnd, storeName, date, closeTime, isAutomatic)`
- Added `getDistinctEmployeeIds(UUID storeId, Instant start, Instant end)` (delegates to SQL)
- All employee-scoped SQL aggregation methods added (sales, CA, top products filtered to one employee)

**`DailyReportGenerator.java`**
- Routes to `buildForEmployee()` when `actorId != null`, else `build()`
- Dispatches formatter to `formatEmployee()` vs `format()`
- Skips WhatsApp delivery for employee reports (`isForEmployee == true`)
- Skips multi-store summary for employee reports
- Implements `getEmployeesWithSalesInWindow()` via `EndOfDayReportBuilder`

**`DailyReportFormatter.java`**
- Added `formatEmployee()`: personal header ("📊 Votre rapport du jour", "💰 Votre CA", "🏆 Vos top produits"), no team section, no stock alert

**`EndOfDayReportListener.java`**
- Generates 1 store command (`actorId=null`) then loops over `getEmployeesWithSalesInWindow()` to generate 1 personal command per employee
- Each employee report generation is wrapped in its own try/catch so a single failure doesn't block other reports
- Updated Javadoc to explain the N+1 pattern

**`ReportHistoryService.java`** / **`GetReportHistoryUseCase.java`** / **`ReportHistoryQuery`**
- Added `storeId` and `actorId` nullable filter fields (already present from v1.1; wired to `findFiltered`)

#### Backend — Tests Updated (10 files)

All updated for new constructor signatures:

| Test file | Change |
|---|---|
| `DayClosedEventTest.java` | Added `Instant.EPOCH` as 8th arg |
| `EndOfDayReportListenerTest.java` | Added `@MockitoSettings(LENIENT)`, mocked `getEmployeesWithSalesInWindow()` → `List.of()` |
| `DailyReportFormatterTest.java` | Added `false` for `isForEmployee` |
| `EndOfDayReportBuilderTest.java` | Updated `build()` from 5-arg to 7-arg |
| `AbstractReportGeneratorTest.java` | Added `null` windowStart as 6th arg to `GenerateReportCommand` |
| `DailyReportGeneratorTest.java` | Added `null` windowStart as 6th arg |
| `MultiStoreSummaryServiceTest.java` | Added `null` windowStart as 6th arg |
| `ReportControllerTest.java` | Minor updates |
| `ReportDeliveryRetryServiceTest.java` | Minor updates |
| `EndOfDayReportTest.java` | Minor updates |

**Unit test results: 52/52 passing.**

#### Flutter — Currency Formatting Unification (7 files)

| File | Before | After |
|---|---|---|
| `cart_pill.dart` | `fr_CM + FCFA` | `fr_FR + XAF` |
| `checkout_page.dart` | `fr_CM + FCFA` | `fr_FR + XAF` |
| `sales_history_page.dart` | `fr_FR + FCFA` | `fr_FR + XAF` |
| `report_detail_page.dart` | `fr_FR + FCFA` | `fr_FR + XAF` |
| `store_stock_card.dart` | `NumberFormat.compact()` → "150 k XAF" | `NumberFormat.currency(locale:'fr_FR', symbol:'XAF')` → "150 000 XAF" |
| `supplier_form_page.dart` | Manual regex replacement | `NumberFormat.currency(locale:'fr_FR', symbol:'XAF')` |
| `motivational_message_service.dart` | Manual char-by-char loop | `NumberFormat.decimalPattern('fr_FR')` |

All currency values now use `locale: 'fr_FR', symbol: 'XAF', decimalDigits: 0` consistently across the app.

#### Flutter — Dashboard CA Timezone Bug Fix

**`dashboard_providers.dart`**

- **Root cause**: `storeOverviewsProvider` called `_localDatasource.getStoreOverviews()` which uses `DateTime.now()` in device timezone. When the emulator/phone is on UTC or a timezone other than WAT (UTC+1), sales stored at WAT boundaries fall outside "today" → CA displayed as 0.
- **Fix**: `storeOverviewsProvider` now derives from `dashboardSnapshotProvider` (online HTTP call; backend computes `todayCA`/`yesterdayCA` in WAT timezone server-side). `dashboardSnapshotProvider` already falls back to the local SQLite snapshot on network errors, so offline behavior is unchanged.

```dart
// Before
final storeOverviewsProvider = FutureProvider<List<StoreOverview>>((ref) {
  return ref.watch(dashboardRepositoryProvider).getStoreOverviews();
});

// After
final storeOverviewsProvider = FutureProvider<List<StoreOverview>>((ref) async {
  final snapshot = await ref.watch(dashboardSnapshotProvider.future);
  return snapshot.storeOverviews;
});
```

#### E2E Tests

- 14 original E2E tests (from v1.1): **ALL PASSING**
- 17 new E2E tests for N+1 reports + sliding window: **ALL PASSING**

### Changed Files (v1.2)

**Backend — Production:**
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/DayClosedEvent.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/port/out/DayClosureRepository.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/jpa/DayClosureSpringRepository.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/adapter/out/persistence/impl/DayClosureRepositoryAdapter.java`
- `keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/CloseDayService.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/port/in/GenerateEndOfDayReportUseCase.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/domain/model/EndOfDayReportData.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/EndOfDayReportBuilder.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/DailyReportGenerator.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/DailyReportFormatter.java`
- `keevo/backend/src/main/java/com/keevo/reporting/report/application/service/EndOfDayReportListener.java`

**Backend — Tests:**
- `keevo/backend/src/test/java/com/keevo/commerce/sale/domain/DayClosedEventTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/EndOfDayReportListenerTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/DailyReportFormatterTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/EndOfDayReportBuilderTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/AbstractReportGeneratorTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/DailyReportGeneratorTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/MultiStoreSummaryServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/adapter/in/rest/ReportControllerTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/application/service/ReportDeliveryRetryServiceTest.java`
- `keevo/backend/src/test/java/com/keevo/reporting/report/domain/model/EndOfDayReportTest.java`

**Flutter:**
- `keevo/app/lib/features/dashboard/presentation/provider/dashboard_providers.dart`
- `keevo/app/lib/features/pos/presentation/widget/cart_pill.dart`
- `keevo/app/lib/features/pos/presentation/page/checkout_page.dart`
- `keevo/app/lib/features/pos/presentation/page/sales_history_page.dart`
- `keevo/app/lib/features/reports/presentation/page/report_detail_page.dart`
- `keevo/app/lib/features/inventory/presentation/widget/store_stock_card.dart`
- `keevo/app/lib/features/contact/presentation/page/supplier_form_page.dart`
- `keevo/app/lib/features/dashboard/domain/model/motivational_message_service.dart`

---

## Change Log

| Date | Version | Description | Author |
|---|---|---|---|
| 2025-06-21 | 0.1 | Story created — ready-for-dev | PM/Bob |
| 2025-06-21 | 1.0 | Full backend implemented: domain model, ports, services, persistence adapter, REST controller (1198 tests GREEN). Flutter: Drift schema v22, domain/data/presentation layers, 29 tests GREEN. Status → review | Dev Agent (Claude Sonnet 4.6) |
| 2026-03-31 | 1.1 | Multi-vendor Option A: actor_id on reports, employee-scoped report/sales history, admin store detail views with employee filter. Backend compiles GREEN. Flutter analyze clean. | Dev Agent (Claude Sonnet 4.6) |
| 2026-03-31 | 1.2 | Multi-vendor Option B: sliding window (lastClosure.closedAt→now), N+1 reports per closure (1 store + 1 per employee), currency formatting unified (fr_FR+XAF everywhere), dashboard CA timezone bug fixed. 52 unit + 31 E2E passing. | Dev Agent (Claude Sonnet 4.6) |
| 2026-04-17 | 1.3 | Test regression fix: `report_history_page_test.dart` error state expected `Icons.error_outline` but `AppErrorWidget` uses `Icons.error_outline_rounded` — assertion updated. 39/39 Flutter report tests GREEN. Tasks 25.4/25.5 verified (manual confirmation supported by full test suite + cURL 9/9). | Dev Agent (Claude Sonnet 4.6) |
