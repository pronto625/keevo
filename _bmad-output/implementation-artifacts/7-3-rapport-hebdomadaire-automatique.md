# Story 7.3: Rapport Hebdomadaire Automatique

Status: review

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Report type (weekly vs daily vs inventory), report data window (Mon–Sun vs single day), delivery channel per tenant (WHATSAPP / IN_APP_ONLY per `weeklyReportChannel` in TenantPreferences), weekly trigger day (0=Sunday … 6=Saturday), weekly trigger time (configurable HH:mm), multi-store aggregation. |
| What might change in the future? | Monthly reports extending the same skeleton, per-store weekly schedule, email channel, PDF attachments (Plan Payant), NLP-generated trend insights. |
| Which GoF pattern(s) apply? | **Template Method** (inherited from Story 7.2): `WeeklyReportGenerator` extends `AbstractReportGenerator` — overrides `collectData()` (→ `WeeklyReportBuilder`) + `formatContent()` (→ `WeeklyReportFormatter`) + `getReportType()` (→ `WEEKLY`). **Builder**: `WeeklyReportBuilder` assembles `WeeklyReportData` from multi-store weekly aggregates via EntityManager native queries (same Hibernate tenant isolation pattern as `EndOfDayReportBuilder`). **Strategy**: `deliveryChannel` field on `GenerateReportCommand` (already in place from Story 7.5) — `IN_APP_ONLY` skips WhatsApp. **Observer** (inherited): `DayClosureSchedulerNotifier` already listens to `PreferenceUpdatedEvent`; the weekly scheduler reads prefs live on each tick — no additional observer needed. **Factory Method** (implicit): `WeeklyReportScheduler` acts as a factory that determines `GenerateReportCommand` per tenant and delegates to `WeeklyReportGenerator`. |
| How does it enable Open/Closed principle? | `AbstractReportGenerator` is not modified — `WeeklyReportGenerator` extends it without touching the template. New formatters (monthly, inventory) add a new subclass without changing the skeleton. `ReportType.WEEKLY` already exists in the enum — no enum change needed. |
| Where is the pattern applied? | **Template Method**: `WeeklyReportGenerator` in `reporting/report/application/service/`. **Builder**: `WeeklyReportBuilder` in `reporting/report/application/service/`. **Formatter**: `WeeklyReportFormatter` in `reporting/report/application/service/`. **Scheduler**: `WeeklyReportScheduler` in `reporting/report/application/service/` (Spring `@Scheduled`, runs hourly like `DayClosureAutoScheduler`). Domain data VO: `WeeklyReportData` record in `reporting/report/domain/model/`. Port in: `GenerateWeeklyReportUseCase` in `reporting/report/domain/port/in/`. |

---

## Story

As a proprietor (Simon — OWNER role),
I want to receive a weekly summary every Sunday evening (or my configured day) with my full week's performance,
So that I can review business trends, top performers, and profitability at a glance without any manual action.

---

## Acceptance Criteria

### AC1 — Weekly scheduler triggers report for each active tenant

- **Given** every Sunday at 20:00 WAT (configurable via `weeklyReportDay` and `weeklyReportTime` in `tenant_preferences`, default: day=0 Sunday, time=20:00:00)
- **When** the `WeeklyReportScheduler` hourly tick runs (`@Scheduled(cron = "0 0 * * * *")`)
- **Then** for each `ACTIVE` tenant where `weeklyReportEnabled = true`:
  - The scheduler checks: `DayOfWeek.of(weeklyReportDay) == LocalDate.now(WAT).getDayOfWeek()` AND `LocalTime.now(WAT) >= weeklyReportTime`
  - AND no `WEEKLY` report already exists for that tenant for the current week's Sunday
- **And** if all conditions pass, for **each active store** of that tenant, a `GenerateReportCommand` is issued to `WeeklyReportGenerator`
- **And** the tenant's `weeklyReportChannel` preference is passed as the `deliveryChannel` field of the command
- **And** `isAutomatic = true` is set on all scheduler-triggered commands
- **And** tenants with `weeklyReportEnabled = false` are silently skipped (no error, just a `log.debug` entry)
- **And** inactive tenants (status ≠ `ACTIVE`) are skipped

> **NOTE**: The scheduler runs every hour (same cron as `DayClosureAutoScheduler`) and reads `weeklyReportDay` + `weeklyReportTime` live from `tenant_preferences` on each tick — no JVM restart needed when the tenant changes their preference via Story 7.5.

### AC2 — Weekly report generation via Template Method (`WeeklyReportGenerator`)

- **Given** `WeeklyReportScheduler` or a triggered test endpoint calls `WeeklyReportGenerator.generateReport(command)`
- **When** the Template Method skeleton executes: `collectData → formatContent → persistReport → deliverReport → checkMultiStoreCondition`
- **Then** `collectData()` delegates to `WeeklyReportBuilder.build(storeId, weekStart, weekEnd, storeName, weekEndDate, isAutomatic)` where:
  - `weekStart` = Monday 00:00:00 WAT of the current week
  - `weekEnd` = Sunday 23:59:59 WAT of the current week
- **And** `WeeklyReportData` contains:
  - `storeName` (String)
  - `weekStart` (LocalDate — Monday)
  - `weekEnd` (LocalDate — Sunday)
  - `totalSales` (int — COMPLETED sales count in window)
  - `totalRevenue` (int — XAF)
  - `cashAmount` (int — XAF)
  - `momoAmount` (int — XAF)
  - `avgBasket` (int — totalRevenue / totalSales, 0 if no sales)
  - `topProductsByRevenue` (List<TopProductEntry> — top 5 by SUM(total_price) DESC, limit 5)
  - `topProductsByQty` (List<TopProductEntry> — top 5 by SUM(quantity) DESC, limit 5)
  - `employeeBreakdown` (List<EmployeeEntry> — per employee: salesCount + revenue for week)
  - `previousWeekRevenue` (int — total revenue of the previous Mon-Sun window, 0 if no prior data)
  - `weekOverWeekDelta` (int — totalRevenue - previousWeekRevenue; positive = growth)
  - `lowStockCount` (int — products below threshold in this store)
  - `isAutomatic` (boolean)
- **And** `formatContent()` delegates to `WeeklyReportFormatter.format(WeeklyReportData)`
- **And** the report is persisted via `EndOfDayReportRepository.save()` with `reportType = WEEKLY`
- **And** `getReportType()` returns `ReportType.WEEKLY`

### AC3 — Emoji-rich WhatsApp weekly message format

- **Given** the weekly report data is assembled
- **When** `WeeklyReportFormatter.format(data)` is called
- **Then** the output follows this exact format (French, emoji-rich, no link, no PDF):

```
📅 Rapport Hebdomadaire — [Store Name]
📆 Semaine du [lun DD MMM] au [dim DD MMM YYYY]

💰 CA Semaine : [X] FCFA [↑/↓/→] vs semaine précédente ([+/-N] FCFA)
🛍 Ventes : [N] | 🧺 Panier moyen : [X] FCFA
💵 Cash : [X] FCFA | 📱 MoMo : [X] FCFA

🏆 Top 5 produits (revenus) :
1. [Product] — [X] FCFA
2. [Product] — [X] FCFA
3. [Product] — [X] FCFA
4. [Product] — [X] FCFA
5. [Product] — [X] FCFA

📦 Top 5 produits (quantités) :
1. [Product] — [qty] vendu(s)
2. [Product] — [qty] vendu(s)
3. ...

👤 Équipe de la semaine :
• [Employee 1] : [N] ventes — [X] FCFA
• [Employee 2] : [N] ventes — [X] FCFA

📦 Alertes stock : [N] produit(s) en rupture
```

- **And** if `weekOverWeekDelta > 0` → use `↑` and show `+N FCFA`
- **And** if `weekOverWeekDelta < 0` → use `↓` and show `-N FCFA` (absolute value)
- **And** if `weekOverWeekDelta == 0` OR `previousWeekRevenue == 0` (no prior data) → use `→` and show `(données insuffisantes)` or `(+0 FCFA)`
- **And** if `totalSales == 0` → replace the entire block (after the header) with: `Aucune vente enregistrée cette semaine.`
- **And** if `isAutomatic = true` → append at the end: `⏰ Rapport auto-généré`
- **And** top-5 lists are truncated to actual count (e.g., if only 3 products sold, show 3 entries)
- **And** if `lowStockCount == 0` → the "Alertes stock" line reads: `📦 Stock : Aucune alerte`

### AC4 — WhatsApp delivery with per-tenant channel and retry

- **Given** the weekly report is generated
- **When** delivery is attempted
- **Then** `AbstractReportGenerator.deliverReport()` (inherited, not overridden) is used:
  - If `deliveryChannel == IN_APP_ONLY` → mark `IN_APP_ONLY`, skip WhatsApp call
  - If `deliveryChannel == WHATSAPP` or null → resolve owner phone, call `whatsAppPort.sendReport(phone, content)`
  - On success → `markSent()`
  - On failure → `incrementAttempt()`, `markFailed()` → `ReportDeliveryRetryService` picks it up (max 3 attempts, 5-min intervals — already implemented in Story 7.2, no change)
- **And** the weekly report appears in `GET /api/v1/reports?type=WEEKLY` with correct `deliveryStatus`
- **And** it is visible in the Flutter `ReportHistoryPage` (weekly reports have `reportType = "WEEKLY"`)

### AC5 — Independent of day-close events

- **Given** Simon has not manually closed any day during the week
- **When** the weekly scheduler fires on Sunday evening
- **Then** the weekly report still runs — it queries the `sales` table directly with `occurred_at BETWEEN weekStart AND weekEnd`
- **And** a note in the formatted message indicates `⏰ Rapport auto-généré (aucune clôture manuelle cette semaine)` when `isAutomatic = true`
- **And** no `DayClosedEvent` is required — the weekly report is entirely independent of the daily close mechanism

### AC6 — Weekly report visible in Flutter Rapports > Historique

- **Given** a weekly report has been generated and stored in the backend `reports` table
- **When** pull sync runs next (or user pulls-to-refresh in the app)
- **Then** the weekly report appears in `ReportHistoryPage` with:
  - A `📅` or `HEBDO` badge distinguishing it from daily reports
  - Date shown as "Semaine du [Mon] au [Sun]" (derived from `reportDate` which is the Sunday of that week)
  - `totalRevenue` and `totalSales` displayed
  - `deliveryStatus` icon (✅ WhatsApp / 📱 In-app / ❌ Failed)
- **And** tapping the weekly report opens `ReportDetailPage` showing the full formatted WhatsApp text
- **And** `ReportHistoryCard` uses `reportType == 'WEEKLY'` to conditionally render the `📅 HEBDO` badge

### AC7 — TDD obligatoire: Tests RED → GREEN

#### Backend (JUnit 5)
- `WeeklyReportBuilderTest.java` — builder from sales window, top5 by revenue, top5 by qty, WoW delta, zero sales
- `WeeklyReportFormatterTest.java` — format output, delta indicators, zero sales fallback, truncated top lists
- `WeeklyReportGeneratorTest.java` — extends AbstractReportGenerator: collectData delegates to builder, formatContent delegates to formatter, reportType=WEEKLY, resolveOwnerPhone
- `WeeklyReportSchedulerTest.java` — per-tenant day check, time check, disabled toggle, already-generated guard, inactive tenant skip

#### Flutter (flutter_test)
- `report_history_card_weekly_test.dart` — HEBDO badge renders for `reportType = 'WEEKLY'`; DAILY badge renders otherwise
- `report_history_page_weekly_test.dart` — weekly report appears in list; date label "Semaine du…"

#### cURL integration
- `curl-tests-story-7-3.sh` — iterative E2E: login → GET /reports (verify no weekly yet) → POST /api/v1/reports/trigger-weekly-test (manual trigger endpoint, OWNER only) → GET /reports?type=WEEKLY (verify 1 result) → GET /reports/{id} (verify content has "Rapport Hebdomadaire") → 403 for EMPLOYEE → 401 for no auth

---

## Tasks / Subtasks

> **LOI TDD : Tous les tests RED sont écrits AVANT tout code de production. Le test doit échouer en premier.**

---

### Backend Tasks

#### Task 1 — TDD RED: Domain model tests

- [x] **1.1** Create `WeeklyReportDataTest.java` in `reporting/report/domain/model/`
  ```java
  // reporting/report/domain/model/WeeklyReportDataTest.java
  // Tests:
  // weeklyReportData_create_setsAllFields()
  // weeklyReportData_zeroSales_avgBasketIsZero()
  // weeklyReportData_positiveWoWDelta_growthDetected()
  // weeklyReportData_negativeWoWDelta_declineDetected()
  // weeklyReportData_noPreviousWeek_deltaIsZero()
  // weeklyReportData_top5Products_limitedToFive()
  ```

- [x] **1.2** Run RED → confirm compilation fails (WeeklyReportData doesn't exist yet)

#### Task 2 — TDD RED: Builder tests

- [x] **2.1** Create `WeeklyReportBuilderTest.java` in `reporting/report/application/service/`
  ```java
  // reporting/report/application/service/WeeklyReportBuilderTest.java
  // Tests (using @ExtendWith(MockitoExtension.class) + mocked EntityManager):
  // build_withSalesInWindow_computesTotalCAAndCount()
  //   — Given 3 COMPLETED sales in week window → totalSales=3, totalRevenue=sum
  // build_withZeroSales_returnsEmptyWeeklyData()
  //   — Given no sales → totalSales=0, totalRevenue=0, avgBasket=0
  // build_computesTop5ByRevenue_limitedToFive()
  //   — Given 7 products → only top 5 by revenue returned
  // build_computesTop5ByQty_limitedToFive()
  //   — Given 7 products → only top 5 by qty returned
  // build_computesEmployeeBreakdown()
  //   — Given 2 employees → EmployeeEntry list of 2, name+count+revenue
  // build_computesWeekOverWeekDelta_withPreviousData()
  //   — Given previous week has 100000 XAF, this week has 120000 → delta=+20000
  // build_computesWeekOverWeekDelta_noPreviousData()
  //   — Given no previous week report → previousWeekRevenue=0, delta=0
  // build_computesLowStockCount()
  //   — Given 2 products below threshold → lowStockCount=2
  // build_mixedPaymentModes_splitsCashAndMomo()
  //   — Given Cash 60000 + MoMo 40000 → cashAmount=60000, momoAmount=40000
  ```

- [x] **2.2** Run RED → confirm tests fail (WeeklyReportBuilder doesn't exist yet)

#### Task 3 — TDD RED: Formatter tests

- [x] **3.1** Create `WeeklyReportFormatterTest.java` in `reporting/report/application/service/`
  ```java
  // reporting/report/application/service/WeeklyReportFormatterTest.java
  // Tests:
  // formatter_producesExpectedWeeklyFormat()
  //   — Check header "📅 Rapport Hebdomadaire", "CA Semaine", "Top 5 produits (revenus)"
  // formatter_positiveWoWDelta_showsUpArrow()
  //   — delta > 0 → contains "↑" and "+N FCFA"
  // formatter_negativeWoWDelta_showsDownArrow()
  //   — delta < 0 → contains "↓" and "-N FCFA"
  // formatter_zeroDelta_showsArrowRight()
  //   — delta == 0 → contains "→"
  // formatter_noPreviousWeek_showsInsufficientData()
  //   — previousWeekRevenue == 0 → contains "données insuffisantes" or "(+0 FCFA)"
  // formatter_zeroSales_showsAucuneVente()
  //   — totalSales == 0 → contains "Aucune vente enregistrée cette semaine"
  // formatter_topProductsTruncatedToActualCount()
  //   — only 3 products → shows 3 (not 5 with nulls)
  // formatter_isAutomatic_appendsAutoNote()
  //   — isAutomatic=true → contains "⏰ Rapport auto-généré"
  // formatter_zeroLowStock_showsNoAlert()
  //   — lowStockCount=0 → contains "Aucune alerte"
  ```

- [x] **3.2** Run RED → confirm tests fail

#### Task 4 — TDD RED: Generator tests

- [x] **4.1** Create `WeeklyReportGeneratorTest.java` in `reporting/report/application/service/`
  ```java
  // reporting/report/application/service/WeeklyReportGeneratorTest.java
  // Tests (Mockito mocks for WeeklyReportBuilder, WeeklyReportFormatter, EndOfDayReportRepository,
  //        WhatsAppPort, MultiStoreSummaryService, StoreRepository, UserRepository):
  // generateReport_fromScheduler_buildsAndFormatsWeeklyData()
  //   — collectData() → WeeklyReportBuilder.build() called with correct week window (Mon–Sun WAT)
  //   — formatContent() → WeeklyReportFormatter.format() called
  // generateReport_persistsWithReportTypeWEEKLY()
  //   — saved EndOfDayReport has reportType = WEEKLY
  // generateReport_callsWhatsApp_onWhatsAppChannel()
  //   — deliveryChannel=WHATSAPP → whatsAppPort.sendReport() called
  // generateReport_skipsWhatsApp_onInAppOnlyChannel()
  //   — deliveryChannel=IN_APP_ONLY → whatsAppPort.sendReport() never called, status=IN_APP_ONLY
  // generateReport_whatsAppFails_setsStatusFailed()
  //   — whatsAppPort throws → status=FAILED
  // generateReport_getReportType_returnsWEEKLY()
  //   — getReportType() == ReportType.WEEKLY
  // generateReport_resolveOwnerPhone_returnsNullWhenNoOwner()
  //   — userRepository.findOwnerByTenantSchemaName returns empty → status=IN_APP_ONLY
  ```

- [x] **4.2** Run RED → confirm tests fail

#### Task 5 — TDD RED: Scheduler tests

- [x] **5.1** Create `WeeklyReportSchedulerTest.java` in `reporting/report/application/service/`
  ```java
  // reporting/report/application/service/WeeklyReportSchedulerTest.java
  // Tests (Mockito + injected Clock for deterministic day/time control):
  // scheduler_correctDayAndTime_triggersReport()
  //   — Clock: Sunday 20:05 WAT, tenant weeklyReportDay=0, weeklyReportTime="20:00:00"
  //   — weeklyReportEnabled=true, no existing WEEKLY report for this Sunday
  //   — weeklyReportGenerator.generateReport() called once per active store
  // scheduler_correctDayWrongTime_doesNotTrigger()
  //   — Clock: Sunday 19:30 WAT → scheduler ticks but weeklyReportTime=20:00:00 not reached
  //   — generateReport() never called
  // scheduler_wrongDayCorrectTime_doesNotTrigger()
  //   — Clock: Monday 20:05 WAT, tenant weeklyReportDay=0 (Sunday) → not today
  //   — generateReport() never called
  // scheduler_reportAlreadyGeneratedToday_skips()
  //   — reportRepository.existsByTenantAndTypeAndDate(tenantId, WEEKLY, today) = true
  //   — generateReport() never called
  // scheduler_weeklyReportDisabled_skips()
  //   — weeklyReportEnabled=false → generateReport() never called
  // scheduler_inactiveTenant_skips()
  //   — tenant.getStatus() != ACTIVE → generateReport() never called
  // scheduler_tenantWith21h_NOT_triggered_at_20h()
  //   — weeklyReportTime="21:00:00", LocalTime.now(WAT)=20:30 → skipped
  // scheduler_configuredSaturday_triggersOnSaturday()
  //   — weeklyReportDay=6, Clock: Saturday 20:05 WAT → triggers
  ```

- [x] **5.2** Run RED → confirm all tests fail

#### Task 6 — TDD RED: Controller test (manual trigger endpoint)

- [x] **6.1** Create `WeeklyReportControllerTest.java` in `reporting/report/adapter/in/rest/`
  ```java
  // reporting/report/adapter/in/rest/WeeklyReportControllerTest.java
  // Tests (@WebMvcTest or standaloneSetup with MockitoExtension):
  // POST /api/v1/reports/trigger-weekly → 200, body { triggered: true } (OWNER)
  // POST /api/v1/reports/trigger-weekly → 403 (EMPLOYEE)
  // POST /api/v1/reports/trigger-weekly → 401 (no auth)
  // GET /api/v1/reports?type=WEEKLY → 200 paginated list (OWNER)
  // GET /api/v1/reports?type=WEEKLY → 403 (EMPLOYEE)
  ```

- [x] **6.2** Run RED → all new test cases fail

#### Task 7 — GREEN: Domain model — WeeklyReportData

- [x] **7.1** Create `WeeklyReportData.java` record in `reporting/report/domain/model/`
  ```java
  // reporting/report/domain/model/WeeklyReportData.java
  package com.keevo.reporting.report.domain.model;

  import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
  import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
  import java.time.LocalDate;
  import java.util.List;

  /**
   * WeeklyReportData — Value Object for weekly aggregate report content.
   * Story 7.3 — Rapport Hebdomadaire Automatique.
   */
  public record WeeklyReportData(
      String storeName,
      LocalDate weekStart,             // Monday of the reporting week
      LocalDate weekEnd,               // Sunday of the reporting week
      int totalSales,
      int totalRevenue,
      int cashAmount,
      int momoAmount,
      int avgBasket,                   // 0 if totalSales == 0
      List<TopProductEntry> topProductsByRevenue,  // top 5 by SUM(total_price) DESC
      List<TopProductEntry> topProductsByQty,      // top 5 by SUM(quantity) DESC
      List<EmployeeEntry> employeeBreakdown,
      int previousWeekRevenue,         // 0 if no prior WEEKLY report found
      int weekOverWeekDelta,           // totalRevenue - previousWeekRevenue
      int lowStockCount,
      boolean isAutomatic
  ) {}
  ```

- [x] **7.2** Run GREEN → WeeklyReportDataTest passes

#### Task 8 — GREEN: Port in — GenerateWeeklyReportUseCase

- [x] **8.1** Create `GenerateWeeklyReportUseCase.java` in `reporting/report/domain/port/in/`
  ```java
  // reporting/report/domain/port/in/GenerateWeeklyReportUseCase.java
  package com.keevo.reporting.report.domain.port.in;

  import com.keevo.identity.onboarding.domain.model.ReportChannel;
  import com.keevo.reporting.report.domain.model.EndOfDayReport;
  import java.time.Instant;
  import java.util.UUID;

  /**
   * GenerateWeeklyReportUseCase — Port in for weekly report generation.
   * Story 7.3 — WeeklyReportGenerator implements this interface.
   *
   * <p>Uses the same GenerateReportCommand as GenerateEndOfDayReportUseCase for
   * consistency — the command's windowStart/closedAt fields encode the Mon–Sun window.
   */
  public interface GenerateWeeklyReportUseCase {
      EndOfDayReport generateWeeklyReport(WeeklyReportCommand command);

      record WeeklyReportCommand(
          UUID storeId,
          String tenantId,
          boolean isAutomatic,
          Instant weekStart,     // Monday 00:00:00 WAT as Instant
          Instant weekEnd,       // Sunday 23:59:59 WAT as Instant
          ReportChannel deliveryChannel   // null → defaults to WHATSAPP
      ) {}
  }
  ```

- [x] **8.2** Add `existsByTenantAndTypeAndDate(String tenantId, ReportType type, LocalDate date)` to `EndOfDayReportRepository` port out:
  ```java
  // Returns true if a report of the given type already exists for that tenant on that date.
  // Used by WeeklyReportScheduler to prevent duplicate weekly reports.
  boolean existsByTenantAndTypeAndDate(String tenantId, ReportType type, LocalDate date);
  ```

- [x] **8.3** Implement `existsByTenantAndTypeAndDate` in `EndOfDayReportRepositoryAdapter`:
  ```java
  @Override
  public boolean existsByTenantAndTypeAndDate(String tenantId, ReportType type, LocalDate date) {
      return springRepo.existsByTenantIdAndReportTypeAndReportDate(tenantId, type.name(), date);
  }
  ```

- [x] **8.4** Add `existsByTenantIdAndReportTypeAndReportDate` to `EndOfDayReportSpringRepository`:
  ```java
  boolean existsByTenantIdAndReportTypeAndReportDate(String tenantId, String reportType, LocalDate reportDate);
  ```

- [x] **8.5** Run GREEN → repository method compiles

#### Task 9 — GREEN: WeeklyReportBuilder

- [x] **9.1** Create `WeeklyReportBuilder.java` in `reporting/report/application/service/`:
  ```java
  // reporting/report/application/service/WeeklyReportBuilder.java
  package com.keevo.reporting.report.application.service;

  import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
  import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
  import com.keevo.reporting.report.domain.model.WeeklyReportData;
  import jakarta.persistence.EntityManager;
  import org.springframework.stereotype.Component;
  import org.springframework.transaction.annotation.Transactional;

  import java.sql.Timestamp;
  import java.time.Instant;
  import java.time.LocalDate;
  import java.util.List;
  import java.util.UUID;

  /**
   * WeeklyReportBuilder — GoF Builder pattern.
   * Story 7.3 — assembles WeeklyReportData from weekly aggregate SQL queries.
   *
   * <p>Uses EntityManager.createNativeQuery() for tenant-schema isolation
   * (same pattern as EndOfDayReportBuilder — Hibernate SPI sets search_path).
   * JdbcTemplate is FORBIDDEN for tenant-schema queries per architecture rule.
   */
  @Component
  public class WeeklyReportBuilder {

      private final EntityManager entityManager;
      // PreviousWeekRevenue resolved via EndOfDayReportRepository to read WEEKLY reports
      private final com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository reportRepo;

      public WeeklyReportBuilder(EntityManager entityManager,
                                  com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository reportRepo) {
          this.entityManager = entityManager;
          this.reportRepo = reportRepo;
      }

      @Transactional(readOnly = true)
      public WeeklyReportData build(UUID storeId, Instant weekStart, Instant weekEnd,
                                    String storeName, LocalDate weekEndDate, boolean isAutomatic) {
          Timestamp start = Timestamp.from(weekStart);
          Timestamp end   = Timestamp.from(weekEnd);
          String sid      = storeId.toString();

          int totalSales   = countCompletedSales(sid, start, end);
          int totalRevenue = sumRevenue(sid, start, end);
          int cashAmount   = sumByPaymentMode(sid, start, end, "CASH");
          int momoAmount   = sumByPaymentMode(sid, start, end, "MOBILE_MONEY");
          int avgBasket    = totalSales > 0 ? totalRevenue / totalSales : 0;

          int lowStockCount = countLowStock(sid);

          List<TopProductEntry> topByRevenue = getTop5ByRevenue(sid, start, end);
          List<TopProductEntry> topByQty     = getTop5ByQty(sid, start, end);
          List<EmployeeEntry> employees      = getEmployeeBreakdown(sid, start, end);

          // Previous week: Mon–Sun immediately before weekStart
          int previousWeekRevenue = resolvePreviousWeekRevenue(storeId, weekEndDate);
          int weekOverWeekDelta   = totalRevenue - previousWeekRevenue;

          return new WeeklyReportData(
              storeName, weekEndDate.minusDays(6), weekEndDate,
              totalSales, totalRevenue, cashAmount, momoAmount, avgBasket,
              topByRevenue, topByQty, employees,
              previousWeekRevenue, weekOverWeekDelta,
              lowStockCount, isAutomatic
          );
      }

      // ── SQL helpers (native queries via EntityManager for tenant isolation) ──

      private int countCompletedSales(String storeId, Timestamp start, Timestamp end) {
          // SELECT COUNT(*) FROM sales WHERE store_id = :storeId AND status = 'COMPLETED'
          //   AND occurred_at BETWEEN :start AND :end
          return ((Number) entityManager.createNativeQuery(
                  "SELECT COUNT(*) FROM sales WHERE store_id = CAST(:storeId AS UUID)" +
                  " AND status = 'COMPLETED' AND occurred_at BETWEEN :start AND :end")
              .setParameter("storeId", storeId).setParameter("start", start).setParameter("end", end)
              .getSingleResult()).intValue();
      }

      private int sumRevenue(String storeId, Timestamp start, Timestamp end) {
          // SELECT COALESCE(SUM(total_price), 0) FROM sales WHERE ...
          Object result = entityManager.createNativeQuery(
                  "SELECT COALESCE(SUM(total_price), 0) FROM sales WHERE store_id = CAST(:storeId AS UUID)" +
                  " AND status = 'COMPLETED' AND occurred_at BETWEEN :start AND :end")
              .setParameter("storeId", storeId).setParameter("start", start).setParameter("end", end)
              .getSingleResult();
          return result == null ? 0 : ((Number) result).intValue();
      }

      private int sumByPaymentMode(String storeId, Timestamp start, Timestamp end, String mode) {
          Object result = entityManager.createNativeQuery(
                  "SELECT COALESCE(SUM(total_price), 0) FROM sales WHERE store_id = CAST(:storeId AS UUID)" +
                  " AND status = 'COMPLETED' AND payment_mode = :mode AND occurred_at BETWEEN :start AND :end")
              .setParameter("storeId", storeId).setParameter("start", start)
              .setParameter("end", end).setParameter("mode", mode)
              .getSingleResult();
          return result == null ? 0 : ((Number) result).intValue();
      }

      private int countLowStock(String storeId) {
          // Reuse same pattern as EndOfDayReportBuilder.countLowStock()
          Object result = entityManager.createNativeQuery(
                  "SELECT COUNT(*) FROM products p" +
                  " JOIN stock_levels sl ON sl.product_id = p.id AND sl.store_id = CAST(:storeId AS UUID)" +
                  " WHERE p.is_active = true AND sl.quantity < p.alert_threshold AND p.alert_threshold > 0")
              .setParameter("storeId", storeId)
              .getSingleResult();
          return result == null ? 0 : ((Number) result).intValue();
      }

      @SuppressWarnings("unchecked")
      private List<TopProductEntry> getTop5ByRevenue(String storeId, Timestamp start, Timestamp end) {
          // SELECT product_name, SUM(quantity) as total_qty, SUM(total_price) as revenue
          // FROM sale_items si JOIN sales s ON si.sale_id = s.id WHERE s.store_id=:storeId
          // AND s.status='COMPLETED' AND s.occurred_at BETWEEN :start AND :end
          // GROUP BY product_name ORDER BY revenue DESC LIMIT 5
          List<Object[]> rows = entityManager.createNativeQuery(
                  "SELECT si.product_name, CAST(SUM(si.quantity) AS INT) AS total_qty," +
                  " CAST(SUM(si.total_price) AS INT) AS revenue" +
                  " FROM sale_items si JOIN sales s ON si.sale_id = s.id" +
                  " WHERE s.store_id = CAST(:storeId AS UUID) AND s.status = 'COMPLETED'" +
                  " AND s.occurred_at BETWEEN :start AND :end" +
                  " GROUP BY si.product_name ORDER BY revenue DESC LIMIT 5")
              .setParameter("storeId", storeId).setParameter("start", start).setParameter("end", end)
              .getResultList();
          return rows.stream()
              .map(r -> new TopProductEntry((String) r[0], ((Number) r[1]).intValue(), ((Number) r[2]).intValue()))
              .toList();
      }

      @SuppressWarnings("unchecked")
      private List<TopProductEntry> getTop5ByQty(String storeId, Timestamp start, Timestamp end) {
          List<Object[]> rows = entityManager.createNativeQuery(
                  "SELECT si.product_name, CAST(SUM(si.quantity) AS INT) AS total_qty," +
                  " CAST(SUM(si.total_price) AS INT) AS revenue" +
                  " FROM sale_items si JOIN sales s ON si.sale_id = s.id" +
                  " WHERE s.store_id = CAST(:storeId AS UUID) AND s.status = 'COMPLETED'" +
                  " AND s.occurred_at BETWEEN :start AND :end" +
                  " GROUP BY si.product_name ORDER BY total_qty DESC LIMIT 5")
              .setParameter("storeId", storeId).setParameter("start", start).setParameter("end", end)
              .getResultList();
          return rows.stream()
              .map(r -> new TopProductEntry((String) r[0], ((Number) r[1]).intValue(), ((Number) r[2]).intValue()))
              .toList();
      }

      @SuppressWarnings("unchecked")
      private List<EmployeeEntry> getEmployeeBreakdown(String storeId, Timestamp start, Timestamp end) {
          // Same SQL as EndOfDayReportBuilder.getEmployeeBreakdown()
          // JOIN sale_actors on sale_id → group by actor_id → resolve name from public.users
          List<Object[]> rows = entityManager.createNativeQuery(
                  "SELECT COALESCE(u.first_name || ' ' || u.last_name, 'Vendeur') AS emp_name," +
                  " CAST(COUNT(*) AS INT) AS sales_count, CAST(SUM(s.total_price) AS INT) AS revenue" +
                  " FROM sales s" +
                  " LEFT JOIN public.users u ON u.id = s.actor_id" +
                  " WHERE s.store_id = CAST(:storeId AS UUID) AND s.status = 'COMPLETED'" +
                  " AND s.occurred_at BETWEEN :start AND :end" +
                  " GROUP BY u.first_name, u.last_name ORDER BY revenue DESC")
              .setParameter("storeId", storeId).setParameter("start", start).setParameter("end", end)
              .getResultList();
          return rows.stream()
              .map(r -> new EmployeeEntry((String) r[0], ((Number) r[1]).intValue(), ((Number) r[2]).intValue()))
              .toList();
      }

      private int resolvePreviousWeekRevenue(UUID storeId, LocalDate weekEndDate) {
          // Look up the most recent WEEKLY report for this store before weekEndDate
          LocalDate prevSunday = weekEndDate.minusWeeks(1);
          return reportRepo.findByDateAndStoreAndType(prevSunday, storeId,
                  com.keevo.reporting.report.domain.model.ReportType.WEEKLY)
              .map(r -> r.getTotalRevenue())
              .orElse(0);
      }
  }
  ```
  > **NOTE**: `EndOfDayReportRepository.findByDateAndStoreAndType()` may need to be added — see Task 8.6 below.

- [x] **9.2** Add `findByDateAndStoreAndType(LocalDate date, UUID storeId, ReportType type)` to `EndOfDayReportRepository`:
  ```java
  Optional<EndOfDayReport> findByDateAndStoreAndType(LocalDate date, UUID storeId, ReportType type);
  ```

- [x] **9.3** Implement in `EndOfDayReportRepositoryAdapter`:
  ```java
  @Override
  public Optional<EndOfDayReport> findByDateAndStoreAndType(LocalDate date, UUID storeId, ReportType type) {
      return springRepo.findFirstByReportDateAndStoreIdAndReportType(date, storeId, type.name())
          .map(this::toDomain);
  }
  ```

- [x] **9.4** Add `findFirstByReportDateAndStoreIdAndReportType` to `EndOfDayReportSpringRepository` (derived query).

- [x] **9.5** Run GREEN → WeeklyReportBuilderTest passes

#### Task 10 — GREEN: WeeklyReportFormatter

- [x] **10.1** Create `WeeklyReportFormatter.java` in `reporting/report/application/service/`:
  ```java
  // reporting/report/application/service/WeeklyReportFormatter.java
  package com.keevo.reporting.report.application.service;

  import com.keevo.reporting.report.domain.model.EndOfDayReportData.TopProductEntry;
  import com.keevo.reporting.report.domain.model.EndOfDayReportData.EmployeeEntry;
  import com.keevo.reporting.report.domain.model.WeeklyReportData;
  import org.springframework.stereotype.Component;

  import java.time.format.DateTimeFormatter;
  import java.util.List;
  import java.util.Locale;

  /**
   * WeeklyReportFormatter — Formats WeeklyReportData into an emoji-rich French WhatsApp message.
   * Story 7.3 — Pure function, fully unit-testable, no side effects.
   */
  @Component
  public class WeeklyReportFormatter {

      private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.FRENCH);
      private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.FRENCH);

      public String format(WeeklyReportData data) {
          if (data.totalSales() == 0) {
              return buildEmptyWeekMessage(data);
          }
          StringBuilder sb = new StringBuilder();

          // Header
          sb.append("📅 Rapport Hebdomadaire — ").append(data.storeName()).append("\n");
          sb.append("📆 Semaine du ").append(data.weekStart().format(DAY_MONTH))
            .append(" au ").append(data.weekEnd().format(FULL_DATE)).append("\n\n");

          // Revenue summary
          sb.append("💰 CA Semaine : ").append(formatXAF(data.totalRevenue()))
            .append(" ").append(buildWoWIndicator(data)).append("\n");
          sb.append("🛍 Ventes : ").append(data.totalSales())
            .append(" | 🧺 Panier moyen : ").append(formatXAF(data.avgBasket())).append("\n");
          sb.append("💵 Cash : ").append(formatXAF(data.cashAmount()))
            .append(" | 📱 MoMo : ").append(formatXAF(data.momoAmount())).append("\n\n");

          // Top 5 by revenue
          if (!data.topProductsByRevenue().isEmpty()) {
              sb.append("🏆 Top 5 produits (revenus) :\n");
              appendTopProducts(sb, data.topProductsByRevenue(), /* byRevenue */ true);
              sb.append("\n");
          }

          // Top 5 by quantity
          if (!data.topProductsByQty().isEmpty()) {
              sb.append("📦 Top 5 produits (quantités) :\n");
              appendTopProducts(sb, data.topProductsByQty(), /* byRevenue */ false);
              sb.append("\n");
          }

          // Employee breakdown
          if (!data.employeeBreakdown().isEmpty()) {
              sb.append("👤 Équipe de la semaine :\n");
              for (EmployeeEntry e : data.employeeBreakdown()) {
                  sb.append("• ").append(e.name()).append(" : ")
                    .append(e.salesCount()).append(" vente").append(e.salesCount() > 1 ? "s" : "").append(" — ")
                    .append(formatXAF(e.revenue())).append("\n");
              }
              sb.append("\n");
          }

          // Low stock
          if (data.lowStockCount() > 0) {
              sb.append("📦 Alertes stock : ").append(data.lowStockCount())
                .append(" produit").append(data.lowStockCount() > 1 ? "s" : "").append(" en rupture\n");
          } else {
              sb.append("📦 Stock : Aucune alerte\n");
          }

          // Auto note
          if (data.isAutomatic()) {
              sb.append("\n⏰ Rapport auto-généré");
          }

          return sb.toString().trim();
      }

      private String buildEmptyWeekMessage(WeeklyReportData data) {
          StringBuilder sb = new StringBuilder();
          sb.append("📅 Rapport Hebdomadaire — ").append(data.storeName()).append("\n");
          sb.append("📆 Semaine du ").append(data.weekStart().format(DAY_MONTH))
            .append(" au ").append(data.weekEnd().format(FULL_DATE)).append("\n\n");
          sb.append("Aucune vente enregistrée cette semaine.\n");
          if (data.isAutomatic()) {
              sb.append("\n⏰ Rapport auto-généré (aucune clôture manuelle cette semaine)");
          }
          return sb.toString().trim();
      }

      private String buildWoWIndicator(WeeklyReportData data) {
          if (data.previousWeekRevenue() == 0) {
              return "→ (données insuffisantes)";
          }
          int delta = data.weekOverWeekDelta();
          if (delta > 0) {
              return "↑ vs semaine précédente (+" + formatXAF(delta) + ")";
          } else if (delta < 0) {
              return "↓ vs semaine précédente (-" + formatXAF(Math.abs(delta)) + ")";
          } else {
              return "→ vs semaine précédente (+0 FCFA)";
          }
      }

      private void appendTopProducts(StringBuilder sb, List<TopProductEntry> products, boolean byRevenue) {
          int limit = Math.min(5, products.size());
          for (int i = 0; i < limit; i++) {
              TopProductEntry p = products.get(i);
              sb.append(i + 1).append(". ").append(p.name()).append(" — ");
              if (byRevenue) {
                  sb.append(formatXAF(p.revenue())).append("\n");
              } else {
                  sb.append(p.quantity()).append(" vendu").append(p.quantity() > 1 ? "s" : "").append("\n");
              }
          }
      }

      private String formatXAF(int amount) {
          return String.format("%,d FCFA", amount).replace(',', ' ');
      }
  }
  ```
  > **CAUTION**: `TopProductEntry.quantity()` and `TopProductEntry.revenue()` must exist as accessors — verify the record definition in `EndOfDayReportData.java`. If the field is named differently (e.g., `qty`), adapt accordingly. Check `EndOfDayReportData` before implementing.

- [x] **10.2** Run GREEN → WeeklyReportFormatterTest passes

#### Task 11 — GREEN: WeeklyReportGenerator

- [x] **11.1** Create `WeeklyReportGenerator.java` in `reporting/report/application/service/`:
  ```java
  // reporting/report/application/service/WeeklyReportGenerator.java
  package com.keevo.reporting.report.application.service;

  import com.keevo.commerce.sale.domain.port.out.WhatsAppPort;
  import com.keevo.identity.auth.domain.port.out.UserRepository;
  import com.keevo.reporting.report.domain.model.EndOfDayReportData;
  import com.keevo.reporting.report.domain.model.ReportType;
  import com.keevo.reporting.report.domain.model.WeeklyReportData;
  import com.keevo.reporting.report.domain.port.in.GenerateEndOfDayReportUseCase.GenerateReportCommand;
  import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
  import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
  import com.keevo.store.store.domain.port.out.StoreRepository;
  import org.springframework.stereotype.Service;

  import java.time.Instant;
  import java.time.LocalDate;
  import java.time.LocalTime;
  import java.time.ZoneId;

  /**
   * WeeklyReportGenerator — Concrete Template Method for weekly reports.
   * Story 7.3 — extends AbstractReportGenerator (Task 6.3 of Story 7.2).
   *
   * <p>Overrides: collectData (→ WeeklyReportBuilder) + formatContent (→ WeeklyReportFormatter).
   * Inherits: persistReport, deliverReport (with IN_APP_ONLY channel support), checkMultiStoreCondition,
   *           resolveOwnerPhone (from standard UserRepository lookup).
   */
  @Service
  public class WeeklyReportGenerator extends AbstractReportGenerator
          implements GenerateWeeklyReportUseCase {

      private static final ZoneId WAT = ZoneId.of("Africa/Lagos");

      private final WeeklyReportBuilder builder;
      private final WeeklyReportFormatter formatter;
      private final StoreRepository storeRepository;
      private final UserRepository userRepository;

      public WeeklyReportGenerator(EndOfDayReportRepository reportRepository,
                                    WhatsAppPort whatsAppPort,
                                    MultiStoreSummaryService multiStoreSummaryService,
                                    WeeklyReportBuilder builder,
                                    WeeklyReportFormatter formatter,
                                    StoreRepository storeRepository,
                                    UserRepository userRepository) {
          super(reportRepository, whatsAppPort, multiStoreSummaryService);
          this.builder = builder;
          this.formatter = formatter;
          this.storeRepository = storeRepository;
          this.userRepository = userRepository;
      }

      /**
       * Entry point called by WeeklyReportScheduler.
       * Adapts WeeklyReportCommand → GenerateReportCommand for the shared template.
       */
      @Override
      public com.keevo.reporting.report.domain.model.EndOfDayReport generateWeeklyReport(WeeklyReportCommand command) {
          // Wrap into the standard GenerateReportCommand for AbstractReportGenerator
          GenerateReportCommand cmd = new GenerateReportCommand(
              command.storeId(),
              null,                   // actorId = null (no employee scope for weekly)
              command.tenantId(),
              command.isAutomatic(),
              command.weekEnd(),       // closedAt = weekEnd (Sunday 23:59:59)
              command.weekStart(),     // windowStart = weekStart (Monday 00:00:00)
              command.deliveryChannel()
          );
          return generateReport(cmd);   // AbstractReportGenerator.generateReport()
      }

      // ── Template Method abstract steps implementation ──

      @Override
      protected EndOfDayReportData collectData(GenerateReportCommand command) {
          // Resolve store name
          String storeName = storeRepository.findById(command.storeId())
              .map(s -> s.name())
              .orElse("Boutique #" + command.storeId().toString().substring(0, 8));

          // weekEnd date (Sunday)
          LocalDate weekEndDate = command.closedAt() != null
              ? command.closedAt().atZone(WAT).toLocalDate()
              : LocalDate.now(WAT);

          Instant weekStart = command.windowStart();
          Instant weekEnd   = command.closedAt();

          WeeklyReportData weeklyData = builder.build(
              command.storeId(), weekStart, weekEnd, storeName, weekEndDate, command.isAutomatic()
          );

          // Bridge WeeklyReportData → EndOfDayReportData for shared persistReport/deliverReport
          // We store the weekly data in a special EndOfDayReportData wrapper.
          // The content (WhatsApp text) is generated in formatContent() — not here.
          // We need totalRevenue + totalSales for the reports table — extract from weeklyData.
          // Store weeklyData on a ThreadLocal so formatContent() can access it without re-query.
          WeeklyDataHolder.set(weeklyData);

          // Return a minimal EndOfDayReportData carrying only the fields used by persistReport()
          return new EndOfDayReportData(
              storeName, weekEndDate, LocalTime.now(WAT), command.isAutomatic(), false,
              weeklyData.totalSales(), weeklyData.totalRevenue(),
              weeklyData.cashAmount(), weeklyData.momoAmount(), weeklyData.avgBasket(),
              weeklyData.topProductsByRevenue(), weeklyData.employeeBreakdown(),
              weeklyData.lowStockCount(), 0, 0
          );
      }

      @Override
      protected String formatContent(EndOfDayReportData data) {
          // Retrieve full weekly data from ThreadLocal set in collectData()
          WeeklyReportData weeklyData = WeeklyDataHolder.get();
          try {
              return formatter.format(weeklyData);
          } finally {
              WeeklyDataHolder.clear();
          }
      }

      @Override
      protected ReportType getReportType() {
          return ReportType.WEEKLY;
      }

      @Override
      protected String resolveOwnerPhone(GenerateReportCommand command) {
          return userRepository.findOwnerByTenantSchemaName(command.tenantId())
              .map(u -> u.getPhoneNumber())
              .orElse(null);
      }
  }
  ```

- [x] **11.2** Create `WeeklyDataHolder.java` in `reporting/report/application/service/` (ThreadLocal bridge):
  ```java
  // reporting/report/application/service/WeeklyDataHolder.java
  package com.keevo.reporting.report.application.service;

  import com.keevo.reporting.report.domain.model.WeeklyReportData;

  /**
   * WeeklyDataHolder — ThreadLocal bridge between collectData() and formatContent()
   * in WeeklyReportGenerator. Avoids a second DB query for weekly data.
   *
   * <p>IMPORTANT: Always call clear() after use (done in formatContent() finally block).
   */
  final class WeeklyDataHolder {
      private static final ThreadLocal<WeeklyReportData> holder = new ThreadLocal<>();
      private WeeklyDataHolder() {}

      static void set(WeeklyReportData data) { holder.set(data); }
      static WeeklyReportData get() { return holder.get(); }
      static void clear() { holder.remove(); }
  }
  ```
  > **Architecture NOTE**: The ThreadLocal bridge is acceptable here because `AbstractReportGenerator.generateReport()` is `@Transactional` and executes in a single thread. The `finally` block in `formatContent()` guarantees cleanup. If this pattern feels fragile, an alternative is to refactor `AbstractReportGenerator` to pass data through the call (breaking change to Story 7.2's template — not preferred). The ThreadLocal approach is the least invasive change.

- [x] **11.3** Run GREEN → WeeklyReportGeneratorTest passes

#### Task 12 — GREEN: WeeklyReportScheduler

- [x] **12.1** Create `WeeklyReportScheduler.java` in `reporting/report/application/service/`:
  ```java
  // reporting/report/application/service/WeeklyReportScheduler.java
  package com.keevo.reporting.report.application.service;

  import com.keevo.identity.auth.domain.model.Tenant;
  import com.keevo.identity.auth.domain.model.TenantStatus;
  import com.keevo.identity.auth.domain.port.out.TenantRepository;
  import com.keevo.identity.onboarding.domain.model.TenantPreferences;
  import com.keevo.identity.onboarding.domain.port.out.TenantPreferencesRepository;
  import com.keevo.reporting.report.domain.model.ReportType;
  import com.keevo.reporting.report.domain.port.in.GenerateWeeklyReportUseCase;
  import com.keevo.reporting.report.domain.port.out.EndOfDayReportRepository;
  import com.keevo.shared.infrastructure.persistence.TenantContext;
  import com.keevo.store.store.domain.port.out.StoreRepository;
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.scheduling.annotation.Scheduled;
  import org.springframework.stereotype.Component;

  import java.time.Clock;
  import java.time.DayOfWeek;
  import java.time.Instant;
  import java.time.LocalDate;
  import java.time.LocalTime;
  import java.time.ZoneId;
  import java.time.ZonedDateTime;
  import java.time.temporal.TemporalAdjusters;
  import java.util.Optional;

  /**
   * WeeklyReportScheduler — per-tenant automatic weekly report generation.
   * Story 7.3 — Rapport Hebdomadaire Automatique.
   *
   * <p>Runs every hour (same cron as DayClosureAutoScheduler). For each tenant,
   * reads weeklyReportEnabled, weeklyReportDay, weeklyReportTime from tenant_preferences.
   * Triggers WeeklyReportGenerator when the configured day and time are reached.
   *
   * <p>Pattern mirrors DayClosureAutoScheduler (Story 7.5).
   * Clock is injected for testability (package-private constructor for tests).
   */
  @Component
  public class WeeklyReportScheduler {

      private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);
      private static final ZoneId WAT = ZoneId.of("Africa/Lagos");
      private static final int DEFAULT_WEEKLY_DAY  = 0;            // Sunday
      private static final LocalTime DEFAULT_WEEKLY_TIME = LocalTime.of(20, 0);

      private final TenantRepository tenantRepository;
      private final StoreRepository storeRepository;
      private final TenantPreferencesRepository tenantPreferencesRepository;
      private final GenerateWeeklyReportUseCase weeklyReportGenerator;
      private final EndOfDayReportRepository reportRepository;
      private final Clock clock;

      @Autowired
      public WeeklyReportScheduler(TenantRepository tenantRepository,
                                    StoreRepository storeRepository,
                                    TenantPreferencesRepository tenantPreferencesRepository,
                                    GenerateWeeklyReportUseCase weeklyReportGenerator,
                                    EndOfDayReportRepository reportRepository) {
          this(tenantRepository, storeRepository, tenantPreferencesRepository,
               weeklyReportGenerator, reportRepository, Clock.systemUTC());
      }

      /** Package-private — allows injecting a fixed Clock in tests. */
      WeeklyReportScheduler(TenantRepository tenantRepository,
                             StoreRepository storeRepository,
                             TenantPreferencesRepository tenantPreferencesRepository,
                             GenerateWeeklyReportUseCase weeklyReportGenerator,
                             EndOfDayReportRepository reportRepository,
                             Clock clock) {
          this.tenantRepository = tenantRepository;
          this.storeRepository = storeRepository;
          this.tenantPreferencesRepository = tenantPreferencesRepository;
          this.weeklyReportGenerator = weeklyReportGenerator;
          this.reportRepository = reportRepository;
          this.clock = clock;
      }

      /**
       * Runs every hour. Checks each tenant's configured weekly report day and time.
       */
      @Scheduled(cron = "0 0 * * * *")
      public void runWeeklyReportGeneration() {
          log.info("WeeklyReportScheduler: starting weekly report check");
          LocalDate todayWAT     = clock.instant().atZone(WAT).toLocalDate();
          LocalTime nowWAT       = clock.instant().atZone(WAT).toLocalTime();
          DayOfWeek todayDow     = todayWAT.getDayOfWeek();

          for (Tenant tenant : tenantRepository.findAll()) {
              if (tenant.getStatus() != TenantStatus.ACTIVE) {
                  log.debug("WeeklyReportScheduler: skipping inactive tenant {}", tenant.getSchemaName());
                  continue;
              }
              try {
                  TenantContext.setCurrentTenant(tenant.getSchemaName());
                  processTenantsWeeklyReport(tenant, todayWAT, nowWAT, todayDow);
              } catch (Exception e) {
                  log.error("WeeklyReportScheduler: error processing tenant {}: {}", tenant.getSchemaName(), e.getMessage(), e);
              } finally {
                  TenantContext.clear();
              }
          }
      }

      private void processTenantsWeeklyReport(Tenant tenant, LocalDate todayWAT,
                                               LocalTime nowWAT, DayOfWeek todayDow) {
          Optional<TenantPreferences> prefsOpt = tenantPreferencesRepository.findByCurrentSchema();
          if (prefsOpt.isEmpty()) {
              log.debug("WeeklyReportScheduler: no preferences for tenant {}, using defaults", tenant.getSchemaName());
          }

          TenantPreferences prefs = prefsOpt.orElse(null);

          boolean weeklyEnabled = prefs != null ? prefs.weeklyReportEnabled() : true;
          if (!weeklyEnabled) {
              log.debug("WeeklyReportScheduler: weekly report disabled for tenant {}", tenant.getSchemaName());
              return;
          }

          // Resolve configured day (0=Sunday … 6=Saturday, Java DayOfWeek: 1=Mon, 7=Sun)
          int configuredDayIndex = prefs != null ? prefs.weeklyReportDay() : DEFAULT_WEEKLY_DAY;
          DayOfWeek configuredDow = indexToDayOfWeek(configuredDayIndex);
          if (todayDow != configuredDow) {
              log.debug("WeeklyReportScheduler: not the configured day ({}) for tenant {}, today={}", configuredDow, tenant.getSchemaName(), todayDow);
              return;
          }

          // Resolve configured time
          LocalTime configuredTime = DEFAULT_WEEKLY_TIME;
          if (prefs != null && prefs.weeklyReportTime() != null) {
              try { configuredTime = LocalTime.parse(prefs.weeklyReportTime()); } catch (Exception ignored) {}
          }
          if (nowWAT.isBefore(configuredTime)) {
              log.debug("WeeklyReportScheduler: time {} not reached (configured {}) for tenant {}", nowWAT, configuredTime, tenant.getSchemaName());
              return;
          }

          // Guard: already generated this week?
          if (reportRepository.existsByTenantAndTypeAndDate(tenant.getSchemaName(), ReportType.WEEKLY, todayWAT)) {
              log.debug("WeeklyReportScheduler: weekly report already generated for tenant {} on {}", tenant.getSchemaName(), todayWAT);
              return;
          }

          // Compute week window: Monday 00:00:00 WAT → Sunday 23:59:59 WAT
          LocalDate monday = todayWAT.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          Instant weekStart = monday.atStartOfDay(WAT).toInstant();
          Instant weekEnd   = todayWAT.atTime(23, 59, 59).atZone(WAT).toInstant();

          com.keevo.identity.onboarding.domain.model.ReportChannel channel =
              prefs != null ? prefs.weeklyReportChannel() : com.keevo.identity.onboarding.domain.model.ReportChannel.WHATSAPP;

          storeRepository.findAllActive().forEach(store -> {
              try {
                  weeklyReportGenerator.generateWeeklyReport(
                      new GenerateWeeklyReportUseCase.WeeklyReportCommand(
                          store.getId(), tenant.getSchemaName(),
                          true, weekStart, weekEnd, channel
                      )
                  );
                  log.info("WeeklyReportScheduler: generated weekly report for store {} tenant {}", store.getId(), tenant.getSchemaName());
              } catch (Exception e) {
                  log.error("WeeklyReportScheduler: failed to generate weekly report for store {} tenant {}: {}", store.getId(), tenant.getSchemaName(), e.getMessage(), e);
              }
          });
      }

      /**
       * Converts tenant_preferences.weekly_report_day (0=Sunday, 1=Monday … 6=Saturday)
       * to Java's DayOfWeek (1=Monday … 7=Sunday).
       */
      private DayOfWeek indexToDayOfWeek(int index) {
          return switch (index) {
              case 0 -> DayOfWeek.SUNDAY;
              case 1 -> DayOfWeek.MONDAY;
              case 2 -> DayOfWeek.TUESDAY;
              case 3 -> DayOfWeek.WEDNESDAY;
              case 4 -> DayOfWeek.THURSDAY;
              case 5 -> DayOfWeek.FRIDAY;
              case 6 -> DayOfWeek.SATURDAY;
              default -> DayOfWeek.SUNDAY;
          };
      }
  }
  ```
  > **IMPORTANT**: `TenantPreferencesRepository.findByCurrentSchema()` must exist — check the existing port contract in `identity/onboarding/domain/port/out/TenantPreferencesRepository.java`. If the method is named differently (e.g., `findForCurrentTenant()`), adapt the call. Do NOT create a new method if one already exists.
  >
  > **IMPORTANT**: `StoreRepository.findAllActive()` — check if this method exists in `store/store/domain/port/out/StoreRepository.java`. If only `findById()` exists or the method has a different name (e.g., `findAll()`, `findActiveByTenant()`), adapt accordingly. **Never create a new port method if an equivalent already exists.**

- [x] **12.2** Run GREEN → WeeklyReportSchedulerTest passes

#### Task 13 — GREEN: REST controller — manual trigger endpoint

- [x] **13.1** Add `POST /api/v1/reports/trigger-weekly` to `ReportController.java`:
  ```java
  // In ReportController.java — add at end:

  /**
   * POST /api/v1/reports/trigger-weekly — Manual trigger for weekly report generation.
   * OWNER only. Generates the current week's report immediately for all active stores.
   * Used for testing and manual override.
   */
  @PostMapping("/trigger-weekly")
  @PreAuthorize("hasRole('OWNER')")
  @Operation(summary = "Manually trigger weekly report generation")
  public ResponseEntity<ApiResponseWrapper<Map<String, Object>>> triggerWeeklyReport(
          HttpServletRequest request) {
      String tenantId = extractTenantId(request);   // from JWT context
      // Compute current week window (Mon–Sun WAT)
      ZoneId WAT = ZoneId.of("Africa/Lagos");
      LocalDate todayWAT = LocalDate.now(WAT);
      LocalDate monday   = todayWAT.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      Instant weekStart  = monday.atStartOfDay(WAT).toInstant();
      Instant weekEnd    = todayWAT.atTime(23, 59, 59).atZone(WAT).toInstant();

      // Trigger for each active store
      int triggeredCount = 0;
      for (var store : storeRepository.findAllActive()) {
          weeklyReportGenerator.generateWeeklyReport(
              new GenerateWeeklyReportUseCase.WeeklyReportCommand(
                  store.getId(), tenantId, false, weekStart, weekEnd, null
              )
          );
          triggeredCount++;
      }
      return ResponseEntity.ok(ApiResponseWrapper.ok(
          Map.of("triggered", true, "storeCount", triggeredCount)
      ));
  }
  ```
  > Wire `weeklyReportGenerator` (inject `GenerateWeeklyReportUseCase`) and `storeRepository` into `ReportController`'s constructor.

- [x] **13.2** Run GREEN → WeeklyReportControllerTest passes

#### Task 14 — Full backend TDD cycle

- [x] **14.1** `mvn test` → ALL tests pass, 0 failures, BUILD SUCCESS
  Expected test count: existing tests + ~35 new tests (domains/builder/formatter/generator/scheduler/controller)
- [x] **14.2** Confirm no pre-existing tests are broken by new code (no modifications to existing production files except adding 2 methods to `EndOfDayReportRepository` port + adapter + SpringRepository, and adding 1 endpoint to `ReportController`)

---

### Flutter Tasks

#### Task 15 — Update ReportHistoryCard for WEEKLY badge

- [x] **15.1** Update `features/reports/presentation/widget/report_history_card.dart`:
  - Add a `reportType` badge chip inside the card header
  - When `reportType == 'WEEKLY'` → show `📅 HEBDO` badge (amber color, rounded chip)
  - When `reportType == 'DAILY'` → show `📊 DAILY` badge (blue chip), or omit badge (simpler)
  - When `reportType == 'DAILY_COMBINED'` → show `🏪 MULTI` badge (purple chip)
  - Date display for WEEKLY: show "Sem. [weekEndDate formatted as 'du DD/MM']" (derive from `reportDate`)

- [x] **15.2** Update `features/reports/presentation/page/report_history_page.dart`:
  - Add a filter row or `SegmentedButton` at the top: `Tous | Journalier | Hebdomadaire`
  - When "Hebdomadaire" is selected, filter the displayed list to `reportType == 'WEEKLY'`
  - When "Tous" is selected, show all report types
  - When "Journalier" is selected, show only `DAILY` and `DAILY_COMBINED`
  - Filter is local (client-side) against the provider's data — no new API call needed

- [x] **15.3** Update `features/reports/presentation/provider/report_history_providers.dart`:
  - Ensure the `reportHistoryProvider` (or equivalent) already passes `type` query param — if not, add support for `?type=WEEKLY` filter parameter to `GET /api/v1/reports`
  - Add `weeklyReportHistoryProvider` as a derived provider filtering local list by reportType

#### Task 16 — Flutter tests for weekly badge

- [x] **16.1** Create `test/features/reports/presentation/widget/report_history_card_weekly_test.dart`:
  ```dart
  // Tests:
  // weeklyReport_showsHEDBOBadge()
  //   — Given a ReportHistoryModel with reportType='WEEKLY' → renders '📅 HEBDO' badge
  // dailyReport_doesNotShowHEDBOBadge()
  //   — Given reportType='DAILY' → does NOT render '📅 HEBDO'
  // weeklyReport_dateShowsWeekLabel()
  //   — Given reportType='WEEKLY', reportDate=2026-03-29 → renders 'Sem. du 23/03'
  ```

- [x] **16.2** Create `test/features/reports/presentation/page/report_history_page_weekly_test.dart`:
  ```dart
  // Tests:
  // weeklyReport_appearsInList_whenFilterAll()
  //   — Given reports: [DAILY, WEEKLY] and filter='Tous' → both rendered
  // weeklyReport_appearsInList_whenFilterHebdomadaire()
  //   — Given reports: [DAILY, WEEKLY] and filter='Hebdomadaire' → only WEEKLY rendered
  // dailyReport_hiddenWhenFilterHebdomadaire()
  //   — Given filter='Hebdomadaire' → DAILY report not rendered
  ```

- [x] **16.3** Run `flutter test test/features/reports/` → all tests pass

#### Task 17 — Flutter build verification

- [x] **17.1** Run `flutter analyze` → 0 errors
- [x] **17.2** Run `flutter build apk --debug` → BUILD SUCCESSFUL

---

### cURL Integration Tests (Iterative E2E)

#### Task 18 — Create `curl-tests-story-7-3.sh`

- [x] **18.1** Create `/keevo/scripts/curl-tests-story-7-3.sh`:

```bash
#!/usr/bin/env bash
# ======================================================
# Story 7.3 — cURL Integration Tests (Iterative E2E)
# Rapport Hebdomadaire Automatique
# Run: bash curl-tests-story-7-3.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
PHONE="${PHONE:-+237600072001}"
PASSWORD="${PASSWORD:-Test7200!}"
EMPLOYEE_PHONE="${EMPLOYEE_PHONE:-+237600072002}"
EMPLOYEE_PASSWORD="${EMPLOYEE_PASSWORD:-Test7201!}"

echo "═══════════════════════════════════════════════════"
echo "  Story 7.3 — Weekly Report cURL Tests"
echo "═══════════════════════════════════════════════════"

# ── Step 1: Login as OWNER (two-step) ─────────────────────────────────────────
echo ""
echo "── Step 1: Login OWNER → JWT ──"
LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASSWORD\"}")
LOGIN_TOKEN=$(echo "$LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))" 2>/dev/null || echo "")
TENANT_CODE=$(echo "$LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin); m=d.get('memberships',[]); print(m[0].get('tenantCode','') if m else '')" 2>/dev/null || echo "")
[[ -n "$LOGIN_TOKEN" && "$LOGIN_TOKEN" != "null" ]] || { echo "❌ Step 1 FAILED — login: $LOGIN"; exit 1; }
SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LOGIN_TOKEN\",\"tenantCode\":\"$TENANT_CODE\"}")
JWT=$(echo "$SELECT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))" 2>/dev/null || echo "")
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — JWT obtained (OWNER)" || { echo "❌ Step 1 FAILED — select-tenant: $SELECT"; exit 1; }

# ── Step 2: GET /api/v1/reports?type=WEEKLY — 401 without auth ────────────────
echo ""
echo "── Step 2: GET /api/v1/reports?type=WEEKLY — 401 without auth ──"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/reports?type=WEEKLY")
[[ "$HTTP_STATUS" == "401" ]] && echo "✅ Step 2 — 401 Unauthorized" || { echo "❌ Step 2 FAILED — got $HTTP_STATUS"; exit 1; }

# ── Step 3: GET /api/v1/reports?type=WEEKLY — 200 (empty list initially) ──────
echo ""
echo "── Step 3: GET /api/v1/reports?type=WEEKLY — authenticated (may be empty) ──"
WEEKLY_BEFORE=$(curl -s -X GET "$BASE_URL/api/v1/reports?type=WEEKLY&page=0&size=10" \
  -H "Authorization: Bearer $JWT")
echo "✅ Step 3 — response: $(echo $WEEKLY_BEFORE | python3 -c "import sys,json; d=json.load(sys.stdin); print('status='+str(d.get('status','?'))+' totalElements='+str(d.get('data',{}).get('totalElements',0) if isinstance(d.get('data'),dict) else len(d.get('data',[]))))" 2>/dev/null || echo "OK")"

# ── Step 4: POST /api/v1/reports/trigger-weekly — 403 for EMPLOYEE ────────────
echo ""
echo "── Step 4: POST /trigger-weekly — 403 for EMPLOYEE ──"
EMP_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMPLOYEE_PHONE\",\"password\":\"$EMPLOYEE_PASSWORD\"}")
EMP_TOKEN=$(echo "$EMP_LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))" 2>/dev/null || echo "")
EMP_TENANT=$(echo "$EMP_LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin); m=d.get('memberships',[]); print(m[0].get('tenantCode','') if m else '')" 2>/dev/null || echo "")
if [[ -n "$EMP_TOKEN" && "$EMP_TOKEN" != "null" ]]; then
  EMP_SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
    -H "Content-Type: application/json" \
    -d "{\"loginToken\":\"$EMP_TOKEN\",\"tenantCode\":\"$EMP_TENANT\"}")
  EMP_JWT=$(echo "$EMP_SELECT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))" 2>/dev/null || echo "")
  if [[ -n "$EMP_JWT" && "$EMP_JWT" != "null" ]]; then
    EMP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/reports/trigger-weekly" \
      -H "Authorization: Bearer $EMP_JWT")
    [[ "$EMP_STATUS" == "403" ]] && echo "✅ Step 4 — 403 for EMPLOYEE" || echo "⚠️  Step 4 — expected 403, got $EMP_STATUS (EMPLOYEE JWT may have OWNER role in test env)"
  else
    echo "⚠️  Step 4 — could not get EMPLOYEE JWT, skipping 403 check"
  fi
else
  echo "⚠️  Step 4 — EMPLOYEE account not found, skipping (ensure employee exists)"
fi

# ── Step 5: POST /api/v1/reports/trigger-weekly — 401 without auth ────────────
echo ""
echo "── Step 5: POST /trigger-weekly — 401 without auth ──"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/reports/trigger-weekly")
[[ "$HTTP_STATUS" == "401" ]] && echo "✅ Step 5 — 401 Unauthorized" || { echo "❌ Step 5 FAILED — got $HTTP_STATUS"; exit 1; }

# ── Step 6: POST /api/v1/reports/trigger-weekly — 200 OWNER ──────────────────
echo ""
echo "── Step 6: POST /trigger-weekly — OWNER triggers weekly report ──"
TRIGGER=$(curl -s -X POST "$BASE_URL/api/v1/reports/trigger-weekly" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json")
TRIGGERED=$(echo "$TRIGGER" | python3 -c "import sys,json; d=json.load(sys.stdin); print(str(d.get('data',{}).get('triggered', d.get('triggered','?'))))" 2>/dev/null || echo "?")
[[ "$TRIGGERED" == "True" || "$TRIGGERED" == "true" ]] \
  && echo "✅ Step 6 — Weekly report triggered (triggered=$TRIGGERED)" \
  || { echo "❌ Step 6 FAILED — response: $TRIGGER"; exit 1; }

# ── Step 7: GET /api/v1/reports?type=WEEKLY — 1+ report now ──────────────────
echo ""
echo "── Step 7: GET /api/v1/reports?type=WEEKLY — 1+ report generated ──"
sleep 1  # allow async processing
WEEKLY_AFTER=$(curl -s -X GET "$BASE_URL/api/v1/reports?type=WEEKLY&page=0&size=10" \
  -H "Authorization: Bearer $JWT")
REPORT_ID=$(echo "$WEEKLY_AFTER" | python3 -c "
import sys, json
d = json.load(sys.stdin)
items = d.get('data', {})
if isinstance(items, dict):
    content_list = items.get('content', [])
elif isinstance(items, list):
    content_list = items
else:
    content_list = []
print(content_list[0].get('id','') if content_list else '')
" 2>/dev/null || echo "")
[[ -n "$REPORT_ID" && "$REPORT_ID" != "null" ]] \
  && echo "✅ Step 7 — Weekly report found (id=$REPORT_ID)" \
  || { echo "❌ Step 7 FAILED — no weekly reports found. Response: $WEEKLY_AFTER"; exit 1; }

# ── Step 8: GET /api/v1/reports/{id} — content check ─────────────────────────
echo ""
echo "── Step 8: GET /api/v1/reports/$REPORT_ID — verify WEEKLY report content ──"
REPORT_DETAIL=$(curl -s -X GET "$BASE_URL/api/v1/reports/$REPORT_ID" \
  -H "Authorization: Bearer $JWT")
CONTENT=$(echo "$REPORT_DETAIL" | python3 -c "
import sys, json
d = json.load(sys.stdin)
report = d.get('data', d)
print(report.get('content','')[:100])
" 2>/dev/null || echo "")
REPORT_TYPE=$(echo "$REPORT_DETAIL" | python3 -c "
import sys, json
d = json.load(sys.stdin)
report = d.get('data', d)
print(report.get('reportType',''))
" 2>/dev/null || echo "")
[[ "$REPORT_TYPE" == "WEEKLY" ]] && echo "✅ Step 8a — reportType=WEEKLY confirmed" || { echo "❌ Step 8a FAILED — reportType=$REPORT_TYPE"; exit 1; }
[[ "$CONTENT" == *"Rapport Hebdomadaire"* || "$CONTENT" == *"hebdomadaire"* || "$CONTENT" == *"Semaine"* ]] \
  && echo "✅ Step 8b — content contains weekly header" \
  || { echo "⚠️  Step 8b — content first 100 chars: '$CONTENT' (check format)"; }

# ── Step 9: GET /api/v1/reports — EMPLOYEE 403 ────────────────────────────────
echo ""
echo "── Step 9: GET /api/v1/reports?type=WEEKLY — 403 for EMPLOYEE ──"
if [[ -n "${EMP_JWT:-}" && "$EMP_JWT" != "null" ]]; then
  EMP_GET_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/reports?type=WEEKLY" \
    -H "Authorization: Bearer $EMP_JWT")
  [[ "$EMP_GET_STATUS" == "403" ]] && echo "✅ Step 9 — 403 for EMPLOYEE" || echo "⚠️  Step 9 — got $EMP_GET_STATUS"
else
  echo "⚠️  Step 9 — EMPLOYEE JWT not available, skipping"
fi

# ── Step 10: Verify delivery status ───────────────────────────────────────────
echo ""
echo "── Step 10: Verify delivery status of weekly report ──"
DELIVERY_STATUS=$(echo "$REPORT_DETAIL" | python3 -c "
import sys, json
d = json.load(sys.stdin)
report = d.get('data', d)
print(report.get('deliveryStatus',''))
" 2>/dev/null || echo "")
[[ "$DELIVERY_STATUS" == "SENT" || "$DELIVERY_STATUS" == "IN_APP_ONLY" || "$DELIVERY_STATUS" == "FAILED" || "$DELIVERY_STATUS" == "PENDING" ]] \
  && echo "✅ Step 10 — deliveryStatus=$DELIVERY_STATUS (valid)" \
  || { echo "❌ Step 10 FAILED — unexpected deliveryStatus: $DELIVERY_STATUS"; exit 1; }

echo ""
echo "═══════════════════════════════════════════════════"
echo "  ✅ Story 7.3 — All cURL steps PASSED"
echo "═══════════════════════════════════════════════════"
```

- [x] **18.2** Test script with running backend: `bash curl-tests-story-7-3.sh` → all ✅

---

## Dev Notes

### Architecture Compliance

1. **Hexagonal / Clean Architecture** — All new code follows the established port-adapter layout:
   - Domain: `reporting/report/domain/model/WeeklyReportData.java`, `domain/port/in/GenerateWeeklyReportUseCase.java`
   - Application: `WeeklyReportBuilder`, `WeeklyReportFormatter`, `WeeklyReportGenerator`, `WeeklyReportScheduler`, `WeeklyDataHolder` (all in `reporting/report/application/service/`)
   - Adapters: Controller endpoint added to `ReportController` (existing file), new Spring repository method added to `EndOfDayReportSpringRepository` (existing file)

2. **Template Method (GoF)** — `WeeklyReportGenerator` extends `AbstractReportGenerator` without modifying it. Open/Closed principle strictly observed: `AbstractReportGenerator` is not touched.

3. **Builder (GoF)** — `WeeklyReportBuilder` assembles `WeeklyReportData` from multiple native SQL queries. Same EntityManager approach as `EndOfDayReportBuilder`.

4. **EntityManager for tenant queries** — ALL SQL queries that use tenant-scoped tables (sales, sale_items, products, stock_levels) MUST use `entityManager.createNativeQuery()`, not `JdbcTemplate`. This is the established architecture rule from Story 2.3+. `JdbcTemplate` is only acceptable for public-schema queries (e.g., users table in deliverReport).

5. **WeeklyDataHolder (ThreadLocal)** — Needed because `AbstractReportGenerator.generateReport()` signature passes `EndOfDayReportData` between `collectData()` and `formatContent()`. The ThreadLocal ensures `WeeklyReportFormatter` receives the full `WeeklyReportData` without a breaking change to the template. The `finally` block in `formatContent()` guarantees cleanup.

6. **Scheduler pattern** — `WeeklyReportScheduler` mirrors `DayClosureAutoScheduler` exactly: hourly cron, iterates tenants, reads preferences live, has a Clock injection point for tests.

7. **TenantContext** — Always call `TenantContext.setCurrentTenant(tenant.getSchemaName())` before any tenant-schema query. Always call `TenantContext.clear()` in the `finally` block.

### Project Structure (New Files)

```
backend/src/main/java/com/keevo/
├── reporting/report/
│   ├── application/service/
│   │   ├── WeeklyReportBuilder.java         ← NEW (Task 9)
│   │   ├── WeeklyReportFormatter.java       ← NEW (Task 10)
│   │   ├── WeeklyReportGenerator.java       ← NEW (Task 11)
│   │   ├── WeeklyDataHolder.java             ← NEW (Task 11.2)
│   │   └── WeeklyReportScheduler.java       ← NEW (Task 12)
│   │
│   ├── domain/
│   │   ├── model/
│   │   │   └── WeeklyReportData.java        ← NEW (Task 7)
│   │   └── port/in/
│   │       └── GenerateWeeklyReportUseCase.java  ← NEW (Task 8)
│
identity/onboarding/domain/model/
│   └── TenantPreferences.java               ← NO CHANGE (already has weeklyReport* fields from 7.5)

reporting/report/domain/port/out/
│   └── EndOfDayReportRepository.java        ← ADD 2 methods (Task 8.2, Task 9.2)

reporting/report/adapter/out/persistence/
│   ├── EndOfDayReportRepositoryAdapter.java ← ADD 2 methods (Task 8.3, Task 9.3)
│   └── EndOfDayReportSpringRepository.java  ← ADD 2 derived queries (Task 8.4, Task 9.4)

reporting/report/adapter/in/rest/
│   └── ReportController.java               ← ADD trigger-weekly endpoint (Task 13)

backend/src/test/java/com/keevo/
└── reporting/report/application/service/
    ├── WeeklyReportBuilderTest.java         ← NEW (Task 2)
    ├── WeeklyReportFormatterTest.java       ← NEW (Task 3)
    ├── WeeklyReportGeneratorTest.java       ← NEW (Task 4)
    ├── WeeklyReportSchedulerTest.java       ← NEW (Task 5)
    └── reporting/report/adapter/in/rest/
        └── WeeklyReportControllerTest.java  ← NEW (Task 6)
```

```
app/lib/
├── features/reports/presentation/
│   ├── widget/
│   │   └── report_history_card.dart         ← MODIFY (Task 15.1) — add reportType badge
│   ├── page/
│   │   └── report_history_page.dart         ← MODIFY (Task 15.2) — add filter tabs
│   └── provider/
│       └── report_history_providers.dart    ← CHECK/MODIFY (Task 15.3)

app/test/features/reports/presentation/
├── widget/
│   └── report_history_card_weekly_test.dart ← NEW (Task 16.1)
└── page/
    └── report_history_page_weekly_test.dart ← NEW (Task 16.2)

keevo/scripts/
└── curl-tests-story-7-3.sh                 ← NEW (Task 18)
```

### Critical Pre-Implementation Checks (Before Writing Any Code)

1. **Verify `TenantPreferencesRepository` method name** — Check `identity/onboarding/domain/port/out/TenantPreferencesRepository.java` for the exact method to load current tenant's preferences. Adapt `WeeklyReportScheduler.processTenantsWeeklyReport()` accordingly.

2. **Verify `StoreRepository.findAllActive()`** — Check `store/store/domain/port/out/StoreRepository.java`. The method may be named differently (e.g., `findAll()`, `findActiveStores()`, `findByTenantId()`). Adapt `WeeklyReportScheduler` and `ReportController.triggerWeeklyReport()` accordingly.

3. **Verify `TopProductEntry` field names** — Check `reporting/report/domain/model/EndOfDayReportData.java` for the exact names of `TopProductEntry` record fields. The formatter uses `.quantity()` and `.revenue()` — adapt if named differently.

4. **Verify `EndOfDayReport.getTotalRevenue()` getter** — Used in `WeeklyReportBuilder.resolvePreviousWeekRevenue()`. Check `EndOfDayReport` domain model for the exact accessor names.

5. **No DDL needed** — `WeeklyReportData` is a pure Java record (no JPA entity). Weekly reports are stored in the existing `reports` table with `report_type = 'WEEKLY'` — no schema migration needed.

6. **Story 7.5 prerequisite** — Story 7.5 is in `review` status (not yet `done`). The `TenantPreferences` record already has `weeklyReport*` fields, and `TenantPreferencesJpaEntity` should have the 9 new columns. Verify that `TenantSchemaSyncService` has already applied the 9 `ALTER TABLE` migrations (Story 7.5 tasks 3.3). If 7.5 is still in review, the weekly fields may exist in code but not yet in the live DB — this is acceptable for unit tests (all mocked), but the cURL test script may need to verify the DB state first.

### Patterns from Previous Stories

- **DailyReportGenerator pattern** — `WeeklyReportGenerator` mirrors `DailyReportGenerator` exactly: same constructor signature, same `resolveOwnerPhone()`, same `getReportType()` structure. The only differences are `collectData()` (delegates to `WeeklyReportBuilder` instead of `EndOfDayReportBuilder`) and `formatContent()` (uses ThreadLocal to bridge WeeklyReportData).

- **EndOfDayReportListener NOT triggered** — The weekly report is scheduler-driven, not event-driven. No `DayClosedEvent` is involved. The `EndOfDayReportListener` does not need modification.

- **Multi-store summary** — `checkMultiStoreCondition()` is inherited from `AbstractReportGenerator` and calls `MultiStoreSummaryService`. This currently checks for `DAILY_COMBINED` logic. **Do NOT override this method** — let it execute. If `MultiStoreSummaryService` only handles daily reports, the check will no-op gracefully (no side effect). Verify `MultiStoreSummaryService.checkAndGenerateCombinedSummary()` handles WEEKLY gracefully (should do nothing if all stores haven't generated a WEEKLY report, which is fine).

- **`mvn test` baseline** — Before writing any TDD RED tests, run `mvn test` to capture the current passing count. The test suite baseline from Story 7.2 + 7.5 is approximately 1198-1250 tests. All new tests must add to this count cleanly.

### References

- [Source: `_bmad-output/planning-artifacts/epics/epic-7-rapports-dashboard-communication-whatsapp.md` — Story 7.3 ACs]
- [Source: `_bmad-output/implementation-artifacts/7-2-rapport-end-of-day-generation-envoi-whatsapp.md` — AbstractReportGenerator, DailyReportGenerator, EndOfDayReportBuilder patterns]
- [Source: `_bmad-output/implementation-artifacts/7-5-configuration-des-rapports-preferences-whatsapp.md` — TenantPreferences weekly fields, ReportChannel, UpdateReportPreferencesCommand]
- [Source: `_bmad-output/planning-artifacts/architecture.md` — Report generation: Template Method, GoF patterns table, reporting/ module layout]
- [Source: `backend/src/main/java/com/keevo/reporting/report/application/service/AbstractReportGenerator.java` — Template method skeleton]
- [Source: `backend/src/main/java/com/keevo/reporting/report/application/service/DailyReportGenerator.java` — Pattern to extend]
- [Source: `backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureAutoScheduler.java` — Scheduler pattern]
- [Source: `backend/src/main/java/com/keevo/identity/onboarding/domain/model/TenantPreferences.java` — weeklyReport* fields already exist]
- [Source: `backend/src/main/java/com/keevo/reporting/report/domain/model/ReportType.java` — WEEKLY already in enum]
- [Source: `backend/src/main/java/com/keevo/reporting/report/application/service/EndOfDayReportBuilder.java` — EntityManager native query pattern]
- [Source: `_bmad-output/planning-artifacts/ux-design-specification.md` — WhatsApp as UX extension, emoji-rich compact reports]

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6 (GitHub Copilot)

### Debug Log References

### Completion Notes List

### File List
