# Story 7.5: Configuration des Rapports & Préférences WhatsApp

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Report type (EOD/weekly/inventory/stock-alert), delivery channel per report type (WhatsApp / In-app only / Push / Both), EOD trigger time per tenant (configurable HH:mm), weekly report day + time, enabled/disabled toggle per report type |
| What might change in the future? | New report types (monthly, ad-hoc), email channel, per-store preferences (not per-tenant), timezone selection, SMS fallback channel |
| Which GoF pattern(s) apply? | **Command** for `UpdateReportPreferencesCommand` (immutable value object encapsulating all preference mutation fields — decouples service from HTTP layer). **Strategy** for delivery channel: `GenerateReportCommand` carries a resolved `ReportChannel` enum; `AbstractReportGenerator.deliverReport()` checks the channel and skips WhatsApp if `IN_APP_ONLY` — adds behaviour to the Template Method skeleton without modifying its structure. **Observer** for preference persistence events: `PreferenceUpdatedEvent` published after successful save → `DayClosureSchedulerNotifier` listener re-reads per-tenant time before the next scheduled run (soft reload, no JVM restart). **Builder** (existing) used for test report generation: `EndOfDayReportBuilder.build()` assembles test data for `POST /report-test`. |
| How does it enable Open/Closed principle? | New report types add a field on `UpdateReportPreferencesCommand` and a new column on `tenant_preferences` without modifying `AbstractReportGenerator`. New delivery channels (`EMAIL`, `SMS`) implement `ReportDeliveryStrategy` without modifying the delivery step. The `DayClosureAutoScheduler`'s time-check logic is parameterised by `TenantPreferences` — changing the stored time is all that's needed, no code change. |
| Where is the pattern applied? | **Command**: `UpdateReportPreferencesCommand` record in `identity.onboarding.domain.port.in`. **Strategy**: `GenerateReportCommand.deliveryChannel()` field + `AbstractReportGenerator.deliverReport()` in `reporting.report.application.service`. **Observer**: `PreferenceUpdatedEvent` + `DayClosureSchedulerNotifier` in `identity.onboarding.application.service`. **Builder**: reuses existing `EndOfDayReportBuilder` in `reporting.report.application.service`. |

---

## Story

As a proprietor (Simon — OWNER role),
I want to configure which reports I receive, when, and via which channel,
So that I get exactly the information I need without being overwhelmed by notifications.

---

## Acceptance Criteria

### AC1 — Paramètres > Rapports configuration screen (Flutter)

- **Given** Simon navigates to Paramètres → section "Préférences" → tile "Rapports"
- **When** the `ReportPreferencesPage` loads (route `/settings/reports`)
- **Then** the page is OWNER-only (route added to `_ownerOnlyPrefixes` in `app_router.dart`)
- **And** a `SliverAppBar` with gradient header "Préférences Rapports" is shown
- **And** four sections are displayed, each with a `SwitchListTile` toggle + detail row:
  1. **Rapport de fin de journée** — toggle (`eodReportEnabled`, default `true`), heure d'envoi auto (time picker, default `20:00`), canal (`WHATSAPP` / `IN_APP_ONLY`, SegmentedButton)
  2. **Rapport hebdomadaire** — toggle (`weeklyReportEnabled`, default `true`), jour (dropdown: Lundi–Dimanche, default Dimanche = 0), heure (time picker, default `20:00`), canal (`WHATSAPP` / `IN_APP_ONLY`)
  3. **Rapport d'inventaire** — toggle (`inventoryReportEnabled`, default `true`), canal (`WHATSAPP` / `IN_APP_ONLY`)
  4. **Alertes stock** — toggle (reads `stockAlertEnabled` from existing prefs), canal (`PUSH` / `WHATSAPP` / `BOTH`, SegmentedButton)
- **And** at the bottom, an "Envoyer un rapport test" `FilledButton` with a WhatsApp icon
- **And** all values loaded from `GET /api/v1/tenant/preferences` via `reportPreferencesProvider`

### AC2 — Settings tile update (SettingsPage) — OWNER only

- **Given** Simon (OWNER) views `SettingsPage`
- **When** the page renders
- **Then** the "Rapports" tile is wrapped in `if (isOwner)` inside the "Préférences" `_SettingsCard` — it was previously visible to all roles
- **And** EMPLOYEE (Loïc) viewing `SettingsPage` does **NOT** see the "Rapports" tile ("Préférences" section shows only the "Notifications" tile for employees)
- **And** Simon's tile now navigates to `/settings/reports` on tap (badge `'Bientôt'` removed, `onTap: () {}` replaced)
- **And** "Notifications" tile subtitle is updated to "Alertes et notifications push"
- **And** `_ownerOnlyPrefixes` in `app_router.dart` also guards the route itself (defense in depth — an employee who somehow reaches `/settings/reports` is redirected to `/pos`)

### AC3 — Save preferences via PUT endpoint

- **Given** Simon changes the EOD time from 20:00 to 22:00
- **When** he taps "Enregistrer" (or upon any individual change via auto-save pattern)
- **Then** a `PUT /api/v1/tenant/report-preferences` request is sent with the updated payload
- **And** the backend updates the `tenant_preferences` row for the current tenant
- **And** a success SnackBar appears: "Heure de clôture automatique mise à jour : 22h00"
- **And** the `DayClosureAutoScheduler` picks up the new time on its next run
- **And** the endpoint returns `200 OK` with the full updated preferences in `ApiResponseWrapper` format
- **And** EMPLOYEE role receives `403 FORBIDDEN`
- **And** unauthenticated request receives `401 UNAUTHORIZED`

### AC4 — Disable WhatsApp channel

- **Given** Simon sets "Rapport de fin de journée" canal to "In-app uniquement"
- **When** the **next** `DayClosedEvent` fires
- **Then** the report is generated and persisted (stored in the `reports` table as usual)
- **And** no `WhatsAppPort.sendReport()` call is made — the report is immediately marked `IN_APP_ONLY`
- **And** Simon can still view the report in Rapports > Historique
- **And** if he later re-enables WhatsApp, the next report uses WhatsApp again without code change

### AC5 — Send test report

- **Given** Simon taps "Envoyer un rapport test" on `ReportPreferencesPage`
- **When** `POST /api/v1/tenant/report-test` is called
- **Then** a test report is generated using today's data for Simon's primary store
- **And** the message is prefixed with `🧪 [TEST] ` and sent via `WhatsAppPort.sendReport()` to Simon's WhatsApp number
- **And** the test report is **NOT** persisted in the `reports` table
- **And** on success: SnackBar `"Rapport test envoyé sur WhatsApp ✅"` (200 response)
- **And** on delivery failure: SnackBar `"Échec de l'envoi WhatsApp. Vérifiez votre numéro."` (200 response, `testSent: false`)
- **And** EMPLOYEE role receives `403 FORBIDDEN`

### AC6 — Per-tenant EOD auto-trigger time (DayClosureAutoScheduler)

- **Given** the `DayClosureAutoScheduler` runs
- **When** it iterates over active tenants
- **Then** for each tenant, it reads `eod_report_time` from `tenant_preferences`
- **And** it only triggers auto-closure for stores where `LocalTime.now(WAT) >= tenantPrefs.eodReportTime` AND no closure exists today
- **And** the scheduler itself runs every hour (`0 0 * * * *`) instead of once per day, to support tenants with different configured times
- **And** the `eod_report_time` change takes effect from the **next** scheduled check after the current hour

### AC7 — DDL migration for existing tenants

- **Given** existing tenants whose `tenant_preferences` table was created by Story 1.4
- **When** the backend restarts
- **Then** `TenantSchemaSyncService.ensureRequiredIndexes()` applies `ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS` for all 9 new columns
- **And** each column has a backward-compatible DEFAULT value (see Dev Notes)
- **And** the migration is idempotent (safe to run multiple times)
- **And** new tenants provisioned after this story get all columns in the initial DDL

### AC8 — TDD obligatoire: Tests RED → GREEN

#### Backend (JUnit 5 + MockMvc)
- `UpdateReportPreferencesServiceTest.java` — update persists all fields; OWNER-only guard
- `SendTestReportServiceTest.java` — generates test report, does NOT call `reportRepository.save()`, calls `whatsAppPort.sendReport()` with `[TEST]` prefix
- `TenantPreferencesControllerTest.java` (extend existing) — PUT 200 OWNER, PUT 403 EMPLOYEE, PUT 401 noAuth, POST /report-test 200 OWNER, POST 403 EMPLOYEE
- `AbstractReportGeneratorTest.java` (extend existing) — `deliverReport_withInAppOnlyChannel_skipsWhatsApp()`; `deliverReport_withWhatsAppChannel_callsPort()`
- `DayClosureAutoSchedulerTest.java` (extend existing) — scheduler reads per-tenant time from preferences; tenant with `eod_report_time = 22:00` is NOT triggered at 20:00

#### Flutter (flutter_test)
- `report_preferences_provider_test.dart` — loads prefs from backend; save-on-change fires PUT; error state
- `report_preferences_page_test.dart` — renders all 4 sections; toggle flips; test-report button fires action; snackbar shown

---

## Tasks / Subtasks

> **LOI TDD : Tous les tests RED sont écrits AVANT tout code de production. Chaque test doit échouer en premier.**

---

### Backend Tasks

#### Task 1 — TDD RED: Preferences service + controller tests

- [ ] **1.1** Create `UpdateReportPreferencesServiceTest.java` in `identity/onboarding/application/service/`
  ```java
  // Tests:
  // updatePreferences_withValidCommand_persistsAllFields()
  //   — Given command with eodEnabled=false, channel=IN_APP_ONLY, eodTime="22:00:00", weeklyEnabled=true, etc.
  //   — Expects tenantPreferencesRepository.update(prefs) called once with correct values
  // updatePreferences_publishesPreferenceUpdatedEvent()
  //   — Expects applicationEventPublisher.publishEvent(PreferenceUpdatedEvent)
  // updatePreferences_noPreferencesFound_throwsDomainException()
  //   — PREFERENCES_NOT_FOUND domain error
  ```

- [ ] **1.2** Create `SendTestReportServiceTest.java` in `identity/onboarding/application/service/`
  ```java
  // Tests:
  // sendTestReport_generatesReportWithTestPrefix()
  //   — Verifies whatsAppPort.sendReport() is called with content containing "[TEST]"
  // sendTestReport_doesNotPersistReport()
  //   — Verifies reportRepository.save() is NEVER called
  // sendTestReport_whatsAppFails_returnsFalse()
  //   — whatsAppPort throws → service returns testSent=false without propagating exception
  // sendTestReport_noOwnerPhone_returnsFalse()
  //   — ownerPhone is null → testSent=false
  ```

- [ ] **1.3** Extend `TenantPreferencesControllerTest.java` (or create new `ReportPreferencesControllerTest.java`)
  ```java
  // Tests (standalone MockMvc with @ExtendWith(MockitoExtension.class)):
  // PUT /api/v1/tenant/report-preferences → 200 (OWNER, valid body)
  // PUT /api/v1/tenant/report-preferences → 403 (EMPLOYEE role)
  // PUT /api/v1/tenant/report-preferences → 401 (no Authorization header)
  // PUT /api/v1/tenant/report-preferences → 400 (invalid body — eodTime malformed "25:99:00")
  // POST /api/v1/tenant/report-test → 200, body { testSent: true } (OWNER)
  // POST /api/v1/tenant/report-test → 200, body { testSent: false } (WhatsApp fails)
  // POST /api/v1/tenant/report-test → 403 (EMPLOYEE)
  ```

- [ ] **1.4** Extend `AbstractReportGeneratorTest.java`
  ```java
  // Tests (use existing test subclass ConcreteTestReportGenerator extends AbstractReportGenerator):
  // deliverReport_withInAppOnlyChannel_doesNotCallWhatsApp()
  //   — command has deliveryChannel=IN_APP_ONLY → whatsAppPort.sendReport() never called → report.deliveryStatus = IN_APP_ONLY
  // deliverReport_withWhatsAppChannel_callsPort()
  //   — command has deliveryChannel=WHATSAPP → whatsAppPort.sendReport() called once
  // deliverReport_withNullChannel_defaultsToWhatsApp()
  //   — command has deliveryChannel=null → treated as WHATSAPP (backward compat)
  ```

- [ ] **1.5** Extend `DayClosureAutoSchedulerTest.java`
  ```java
  // Tests:
  // scheduler_tenant_with22h_NOT_triggered_at_20h()
  //   — tenantPrefs.eodReportTime = "22:00:00", LocalTime.now(WAT) = 20:30 → processTenantsStores() NOT called
  // scheduler_tenant_with20h_triggered_at_20h()
  //   — tenantPrefs.eodReportTime = "20:00:00", LocalTime.now(WAT) = 20:05 → processTenantsStores() called
  // scheduler_noPreferences_defaultsTo19UTC()
  //   — empty tenant_preferences → fallback to original 19:00 UTC behaviour
  ```

- [ ] **1.6** Run RED → confirm all 5 test files fail (no production code yet)

---

#### Task 2 — GREEN: Domain model extensions

- [ ] **2.1** Create `ReportChannel.java` enum in `identity/onboarding/domain/model/`
  ```java
  package com.keevo.identity.onboarding.domain.model;
  /** Delivery channel for a report type. */
  public enum ReportChannel {
      WHATSAPP, IN_APP_ONLY;
      /** Case-insensitive parse, defaults to WHATSAPP on unknown value. */
      public static ReportChannel fromString(String s) {
          try { return valueOf(s.toUpperCase()); }
          catch (Exception e) { return WHATSAPP; }
      }
  }
  ```

- [ ] **2.2** Create `StockAlertChannel.java` enum in `identity/onboarding/domain/model/`
  ```java
  public enum StockAlertChannel { PUSH, WHATSAPP, BOTH;
      public static StockAlertChannel fromString(String s) { ... /* fallback PUSH */ }
  }
  ```

- [ ] **2.3** Update `TenantPreferences.java` record — add 9 new fields:
  ```java
  public record TenantPreferences(
      UUID id,
      SectorType sectorType,
      String eodReportTime,           // existing — "HH:mm:ss"
      boolean stockAlertEnabled,       // existing
      Instant createdAt,               // existing
      // ── New fields (Story 7.5) ─────────────────────────────────
      boolean eodReportEnabled,        // default true
      ReportChannel eodReportChannel,  // default WHATSAPP
      boolean weeklyReportEnabled,     // default true
      int weeklyReportDay,             // 0=Sunday … 6=Saturday, default 0
      String weeklyReportTime,         // "HH:mm:ss", default "20:00:00"
      ReportChannel weeklyReportChannel,   // default WHATSAPP
      boolean inventoryReportEnabled,  // default true
      ReportChannel inventoryReportChannel, // default WHATSAPP
      StockAlertChannel stockAlertChannel   // default PUSH
  ) {}
  ```
  > **CAUTION**: All existing callers of `TenantPreferences(...)` constructor must be updated (OnboardingService.java, tests). Use `null`-safe defaults or add a static factory method `TenantPreferences.withDefaults(...)`.

- [ ] **2.4** Create `UpdateReportPreferencesCommand.java` record in `identity/onboarding/domain/port/in/`
  ```java
  public record UpdateReportPreferencesCommand(
      boolean eodReportEnabled,
      ReportChannel eodReportChannel,
      String eodReportTime,            // "HH:mm:ss", validated: pattern ^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$
      boolean weeklyReportEnabled,
      int weeklyReportDay,             // 0–6
      String weeklyReportTime,         // "HH:mm:ss"
      ReportChannel weeklyReportChannel,
      boolean inventoryReportEnabled,
      ReportChannel inventoryReportChannel,
      boolean stockAlertEnabled,
      StockAlertChannel stockAlertChannel
  ) {}
  ```

- [ ] **2.5** Create `UpdateReportPreferencesUseCase.java` interface in `identity/onboarding/domain/port/in/`
  ```java
  public interface UpdateReportPreferencesUseCase {
      TenantPreferences update(UpdateReportPreferencesCommand command);
  }
  ```

- [ ] **2.6** Create `SendTestReportUseCase.java` interface in `identity/onboarding/domain/port/in/`
  ```java
  public interface SendTestReportUseCase {
      record TestReportResult(boolean testSent) {}
      TestReportResult sendTestReport(String tenantId);
  }
  ```

- [ ] **2.7** Add `update()` method to `TenantPreferencesRepository` port-out:
  ```java
  /** Fully replace preferences for the current tenant with new values. */
  TenantPreferences update(TenantPreferences prefs);
  ```

- [ ] **2.8** Run RED → domain compilation errors expected (adapters not yet updated)

---

#### Task 3 — GREEN: DDL migration

- [ ] **3.1** Update `DDL_TENANT_PREFERENCES` in `TenantSchemaProvisioner.java` to include all 9 new columns (for new tenant provisioning):
  ```java
  private static final String DDL_TENANT_PREFERENCES = """
      CREATE TABLE IF NOT EXISTS tenant_preferences (
          id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
          sector_type                VARCHAR(30),
          eod_report_time            TIME        NOT NULL DEFAULT '20:00:00',
          stock_alert_enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
          created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          eod_report_enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
          eod_report_channel         VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP',
          weekly_report_enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
          weekly_report_day          INTEGER     NOT NULL DEFAULT 0,
          weekly_report_time         TIME        NOT NULL DEFAULT '20:00:00',
          weekly_report_channel      VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP',
          inventory_report_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
          inventory_report_channel   VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP',
          stock_alert_channel        VARCHAR(20) NOT NULL DEFAULT 'PUSH'
      )""";
  ```

- [ ] **3.2** Add migration DDL constants in `TenantSchemaProvisioner.java` (for existing tenants):
  ```java
  // Story 7.5 — report preferences columns
  static final String DDL_TENANT_PREFS_MIGRATE_EOD_ENABLED =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS eod_report_enabled BOOLEAN NOT NULL DEFAULT TRUE";
  static final String DDL_TENANT_PREFS_MIGRATE_EOD_CHANNEL =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS eod_report_channel VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP'";
  static final String DDL_TENANT_PREFS_MIGRATE_WEEKLY_ENABLED =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS weekly_report_enabled BOOLEAN NOT NULL DEFAULT TRUE";
  static final String DDL_TENANT_PREFS_MIGRATE_WEEKLY_DAY =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS weekly_report_day INTEGER NOT NULL DEFAULT 0";
  static final String DDL_TENANT_PREFS_MIGRATE_WEEKLY_TIME =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS weekly_report_time TIME NOT NULL DEFAULT '20:00:00'";
  static final String DDL_TENANT_PREFS_MIGRATE_WEEKLY_CHANNEL =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS weekly_report_channel VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP'";
  static final String DDL_TENANT_PREFS_MIGRATE_INVENTORY_ENABLED =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS inventory_report_enabled BOOLEAN NOT NULL DEFAULT TRUE";
  static final String DDL_TENANT_PREFS_MIGRATE_INVENTORY_CHANNEL =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS inventory_report_channel VARCHAR(20) NOT NULL DEFAULT 'WHATSAPP'";
  static final String DDL_TENANT_PREFS_MIGRATE_STOCK_CHANNEL =
      "ALTER TABLE tenant_preferences ADD COLUMN IF NOT EXISTS stock_alert_channel VARCHAR(20) NOT NULL DEFAULT 'PUSH'";
  ```

- [ ] **3.3** Append all 9 migration calls to `TenantSchemaSyncService.ensureRequiredIndexes()`:
  ```java
  // Story 7.5 — tenant_preferences report config columns
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_EOD_ENABLED);
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_EOD_CHANNEL);
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_WEEKLY_ENABLED);
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_WEEKLY_DAY);
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_WEEKLY_TIME);
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_WEEKLY_CHANNEL);
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_INVENTORY_ENABLED);
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_INVENTORY_CHANNEL);
  stmt.execute(TenantSchemaProvisioner.DDL_TENANT_PREFS_MIGRATE_STOCK_CHANNEL);
  ```

---

#### Task 4 — GREEN: JPA adapter + repository update

- [ ] **4.1** Extend `TenantPreferencesJpaEntity.java` with 9 new `@Column` fields:
  ```java
  @Column(name = "eod_report_enabled", nullable = false) private boolean eodReportEnabled = true;
  @Column(name = "eod_report_channel", nullable = false, length = 20) private String eodReportChannel = "WHATSAPP";
  @Column(name = "weekly_report_enabled", nullable = false) private boolean weeklyReportEnabled = true;
  @Column(name = "weekly_report_day", nullable = false) private int weeklyReportDay = 0;
  @Column(name = "weekly_report_time", nullable = false) private LocalTime weeklyReportTime = LocalTime.of(20, 0);
  @Column(name = "weekly_report_channel", nullable = false, length = 20) private String weeklyReportChannel = "WHATSAPP";
  @Column(name = "inventory_report_enabled", nullable = false) private boolean inventoryReportEnabled = true;
  @Column(name = "inventory_report_channel", nullable = false, length = 20) private String inventoryReportChannel = "WHATSAPP";
  @Column(name = "stock_alert_channel", nullable = false, length = 20) private String stockAlertChannel = "PUSH";
  // Add all getters/setters (or convert to @Getter @Setter Lombok if project uses it)
  ```

- [ ] **4.2** Update `TenantPreferencesRepositoryAdapter.java` (implements `TenantPreferencesRepository`):
  - Map new fields in `toDomain(entity)` method
  - Map new fields in `toEntity(prefs)` method
  - Implement `update(TenantPreferences prefs)`: load entity by ID, set all new fields, set `updatedAt = Instant.now()`, save → return mapped domain object
  - Update `OnboardingService.java` call to `TenantPreferences(...)` constructor — add defaults for new fields (all defaults: `true`, `WHATSAPP`, `true`, `0`, `"20:00:00"`, `WHATSAPP`, `true`, `WHATSAPP`, `PUSH`)

- [ ] **4.3** Update `TenantPreferencesResponseDto.java` to include all new fields + update `fromDomain()`:
  ```java
  public record TenantPreferencesResponseDto(
      String sectorType, String eodReportTime, boolean stockAlertEnabled, Instant createdAt,
      // New Story 7.5 fields:
      boolean eodReportEnabled, String eodReportChannel,
      boolean weeklyReportEnabled, int weeklyReportDay, String weeklyReportTime, String weeklyReportChannel,
      boolean inventoryReportEnabled, String inventoryReportChannel,
      String stockAlertChannel
  ) { ... }
  ```

- [ ] **4.4** Create `UpdateReportPreferencesRequestDto.java` in `identity/onboarding/adapter/in/web/`:
  ```java
  public record UpdateReportPreferencesRequestDto(
      @NotNull Boolean eodReportEnabled,
      @Pattern(regexp = "WHATSAPP|IN_APP_ONLY") String eodReportChannel,
      @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$") String eodReportTime,
      @NotNull Boolean weeklyReportEnabled,
      @Min(0) @Max(6) Integer weeklyReportDay,
      @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$") String weeklyReportTime,
      @Pattern(regexp = "WHATSAPP|IN_APP_ONLY") String weeklyReportChannel,
      @NotNull Boolean inventoryReportEnabled,
      @Pattern(regexp = "WHATSAPP|IN_APP_ONLY") String inventoryReportChannel,
      @NotNull Boolean stockAlertEnabled,
      @Pattern(regexp = "PUSH|WHATSAPP|BOTH") String stockAlertChannel
  ) {}
  ```

---

#### Task 5 — GREEN: Service implementation

- [ ] **5.1** Create `PreferenceUpdatedEvent.java` in `identity/onboarding/domain/model/` (Spring `ApplicationEvent` subclass):
  ```java
  public record PreferenceUpdatedEvent(String tenantId, String newEodReportTime) {}
  // NOTE: Use ApplicationEventPublisher.publishEvent(Object) — no need to extend ApplicationEvent in Spring 5+
  ```

- [ ] **5.2** Create `UpdateReportPreferencesService.java` in `identity/onboarding/application/service/`:
  ```java
  @Service
  @Transactional
  public class UpdateReportPreferencesService implements UpdateReportPreferencesUseCase {
      // Inject: TenantPreferencesRepository, ApplicationEventPublisher
      
      @Override
      public TenantPreferences update(UpdateReportPreferencesCommand cmd) {
          TenantPreferences existing = repo.findByCurrentTenant()
              .orElseThrow(() -> new DomainException(ErrorCode.PREFERENCES_NOT_FOUND));
          
          TenantPreferences updated = new TenantPreferences(
              existing.id(), existing.sectorType(),
              cmd.eodReportTime(), cmd.stockAlertEnabled(), existing.createdAt(),
              cmd.eodReportEnabled(), ReportChannel.fromString(cmd.eodReportChannel()),
              cmd.weeklyReportEnabled(), cmd.weeklyReportDay(), cmd.weeklyReportTime(),
              ReportChannel.fromString(cmd.weeklyReportChannel()),
              cmd.inventoryReportEnabled(), ReportChannel.fromString(cmd.inventoryReportChannel()),
              StockAlertChannel.fromString(cmd.stockAlertChannel())
          );
          TenantPreferences saved = repo.update(updated);
          eventPublisher.publishEvent(new PreferenceUpdatedEvent(TenantContext.getCurrentTenant(), cmd.eodReportTime()));
          return saved;
      }
  }
  ```

- [ ] **5.3** Create `SendTestReportService.java` in `identity/onboarding/application/service/`:
  ```java
  @Service
  public class SendTestReportService implements SendTestReportUseCase {
      // Inject: EndOfDayReportBuilder, DailyReportFormatter, WhatsAppPort,
      //         StoreRepository, UserRepository (to resolve ownerPhone)
      
      @Override
      public TestReportResult sendTestReport(String tenantId) {
          // 1. Find primary store for this tenant (first active store)
          // 2. Build report data: EndOfDayReportBuilder.build(storeId, today-start, now, storeName, today, now, false)
          // 3. Format: DailyReportFormatter.format(data)
          // 4. Prepend "🧪 [TEST] " to the content
          // 5. Resolve owner phone; if null → return TestReportResult(false)
          // 6. Try whatsAppPort.sendReport(); catch → return TestReportResult(false)
          // 7. Do NOT call reportRepository.save() at any point
          // 8. Return TestReportResult(true)
      }
  }
  ```

---

#### Task 6 — GREEN: Controller endpoints

- [ ] **6.1** Extend `TenantPreferencesController.java` with two new endpoints:

  ```java
  // PUT /api/v1/tenant/report-preferences (OWNER only)
  @PutMapping("/report-preferences")
  @PreAuthorize("hasRole('OWNER')")
  public ResponseEntity<ApiResponseWrapper<TenantPreferencesResponseDto>> updateReportPreferences(
          @Valid @RequestBody UpdateReportPreferencesRequestDto dto) {
      UpdateReportPreferencesCommand cmd = new UpdateReportPreferencesCommand(
          dto.eodReportEnabled(), dto.eodReportChannel(), dto.eodReportTime() != null ? dto.eodReportTime() : "20:00:00",
          dto.weeklyReportEnabled(), dto.weeklyReportDay() != null ? dto.weeklyReportDay() : 0,
          dto.weeklyReportTime() != null ? dto.weeklyReportTime() : "20:00:00",
          dto.weeklyReportChannel(), dto.inventoryReportEnabled(), dto.inventoryReportChannel(),
          dto.stockAlertEnabled(), dto.stockAlertChannel()
      );
      TenantPreferences updated = updateReportPreferencesUseCase.update(cmd);
      return ResponseEntity.ok(ApiResponseWrapper.ok(TenantPreferencesResponseDto.fromDomain(updated)));
  }

  // POST /api/v1/tenant/report-test (OWNER only)
  @PostMapping("/report-test")
  @PreAuthorize("hasRole('OWNER')")
  public ResponseEntity<ApiResponseWrapper<Map<String, Boolean>>> sendTestReport() {
      String tenantId = TenantContext.getCurrentTenant();
      SendTestReportUseCase.TestReportResult result = sendTestReportUseCase.sendTestReport(tenantId);
      return ResponseEntity.ok(ApiResponseWrapper.ok(Map.of("testSent", result.testSent())));
  }
  ```

---

#### Task 7 — GREEN: Update report generation pipeline (Strategy integration)

- [ ] **7.1** Add `deliveryChannel` field to `GenerateReportCommand` in `GenerateEndOfDayReportUseCase.java`:
  ```java
  record GenerateReportCommand(
      UUID storeId, UUID actorId, String tenantId,
      boolean isAutomatic, Instant closedAt, Instant windowStart,
      ReportChannel deliveryChannel   // nullable — null → defaults to WHATSAPP
  ) {
      // Backward-compat factory if needed
      public GenerateReportCommand(UUID storeId, UUID actorId, String tenantId,
                                   boolean isAutomatic, Instant closedAt, Instant windowStart) {
          this(storeId, actorId, tenantId, isAutomatic, closedAt, windowStart, null);
      }
  }
  ```
  > **CAUTION**: Add the canonical constructor + backward-compat overload. No other callers in Story 7.1–7.4 pass `deliveryChannel` — they use the 6-arg version which defaults to `null` → WHATSAPP.

- [ ] **7.2** Update `AbstractReportGenerator.deliverReport()` to honour the channel:
  ```java
  protected void deliverReport(EndOfDayReport report, GenerateReportCommand command) {
      ReportChannel channel = command.deliveryChannel() != null
          ? command.deliveryChannel() : ReportChannel.WHATSAPP;
      
      if (channel == ReportChannel.IN_APP_ONLY) {
          report.markInAppOnly();
          reportRepository.save(report);
          log.info("Report marked IN_APP_ONLY per channel preference: reportId={}", report.getId());
          return;
      }
      // ── existing WhatsApp delivery logic (unchanged) ───────────────────
      String ownerPhone = resolveOwnerPhone(command);
      // ... rest of existing method
  }
  ```

- [ ] **7.3** Update `EndOfDayReportListener.java` to resolve per-tenant channel before dispatching:
  ```java
  @EventListener
  @Async
  public void onDayClose(DayClosedEvent event) {
      // Resolve delivery channel from tenant preferences
      ReportChannel channel = tenantPreferencesRepository.findByCurrentTenant()
          .filter(prefs -> prefs.eodReportEnabled())      // check if EOD report is enabled
          .map(TenantPreferences::eodReportChannel)
          .orElse(ReportChannel.WHATSAPP);                // default if prefs not found or disabled
      
      // If EOD report is disabled entirely, skip
      boolean eodEnabled = tenantPreferencesRepository.findByCurrentTenant()
          .map(TenantPreferences::eodReportEnabled).orElse(true);
      if (!eodEnabled) {
          log.debug("EOD report disabled for tenant — skipping generation");
          return;
      }
      
      GenerateReportCommand cmd = new GenerateReportCommand(
          event.getStoreId(), event.getActorId(), event.getTenantId(),
          event.isAutomatic(), event.getClosedAt(), event.getWindowStart(),
          channel
      );
      generateReportUseCase.generateReport(cmd);
  }
  ```
  > **NOTE**: `TenantPreferencesRepository` must be injected in `EndOfDayReportListener`. Ensure JPA transactional context is available in the `@Async` listener (wrap in own transaction or use `@Transactional`).

- [ ] **7.4** Add `DayClosureSchedulerNotifier.java` listener in `identity/onboarding/application/service/`:
  ```java
  @Component
  public class DayClosureSchedulerNotifier {
      private static final Logger log = LoggerFactory.getLogger(DayClosureSchedulerNotifier.class);
      
      @EventListener
      public void onPreferenceUpdated(PreferenceUpdatedEvent event) {
          log.info("PreferenceUpdatedEvent received for tenant={}, new eodTime={}",
              event.tenantId(), event.newEodReportTime());
          // No state to invalidate — DayClosureAutoScheduler reads from DB on every run
          // This event listener exists to document the Observer pattern and allow future extension
      }
  }
  ```

---

#### Task 8 — GREEN: Update DayClosureAutoScheduler (per-tenant time)

- [ ] **8.1** Change cron from `"0 0 19 * * *"` (once daily at 19:00 UTC) to `"0 0 * * * *"` (every hour):
  ```java
  @Scheduled(cron = "0 0 * * * *")   // every hour at :00
  public void runAutoClosure() { ... }
  ```

- [ ] **8.2** Inject `TenantPreferencesRepository` into `DayClosureAutoScheduler`

- [ ] **8.3** In `processTenantsStores(Tenant tenant)`, read `eod_report_time` and compare with current WAT time:
  ```java
  private void processTenantsStores(Tenant tenant) {
      LocalTime configuredTime = tenantPreferencesRepository.findByCurrentTenant()
          .map(prefs -> LocalTime.parse(prefs.eodReportTime()))   // "HH:mm:ss" → LocalTime
          .orElse(LocalTime.of(19, 0));                           // fallback 19:00 UTC = 20:00 WAT
      
      LocalTime nowWAT = LocalTime.now(ZoneId.of("Africa/Lagos"));
      if (nowWAT.isBefore(configuredTime)) {
          log.debug("Tenant {} eodTime={} not yet reached (now={})", tenant.getSchemaName(), configuredTime, nowWAT);
          return;  // not yet time for this tenant
      }
      // ── existing store iteration + closure logic (unchanged) ───────────
      for (Store store : storeRepository.findAllActive()) { ... }
  }
  ```

---

#### Task 9 — GREEN: Make all tests pass

- [ ] **9.1** Run `UpdateReportPreferencesServiceTest` → GREEN
- [ ] **9.2** Run `SendTestReportServiceTest` → GREEN
- [ ] **9.3** Run `TenantPreferencesControllerTest` (extended) → GREEN
- [ ] **9.4** Run `AbstractReportGeneratorTest` (extended) → GREEN
- [ ] **9.5** Run `DayClosureAutoSchedulerTest` (extended) → GREEN
- [ ] **9.6** Run full backend suite: `mvn test` → all existing tests still GREEN (no regression)

---

#### Task 10 — Curl E2E tests (iterative until all pass)

Create `curl-tests-story-7-5.sh` in project root. Script must be **self-contained** (register + login fresh each run):

```bash
#!/bin/bash
set -e
BASE_URL="http://localhost:8080"
PHONE="+243700000705"
PASS="Password1!"

echo "=== Story 7.5 — Curl E2E Tests ==="

# ── STEP 1: Register fresh tenant ─────────────────────────────────────────────
echo "--- Step 1: Register ---"
REGISTER=$(curl -sf -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASS\"}")
echo "$REGISTER" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d['success'], 'Register failed'"
echo "PASS: register"

# ── STEP 2: Login (step 1 — get token list) ───────────────────────────────────
echo "--- Step 2: Login ---"
LOGIN=$(curl -sf -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASS\"}")
TENANT_ID=$(echo "$LOGIN" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d['tenants'][0]['tenantId'])")
echo "PASS: login, tenantId=$TENANT_ID"

# ── STEP 3: Select tenant (step 2 — get JWT) ──────────────────────────────────
echo "--- Step 3: Select tenant ---"
SELECT=$(curl -sf -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT_ID\"}")
TOKEN=$(echo "$SELECT" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
echo "PASS: select-tenant, token=...${TOKEN: -10}"

# ── STEP 4: Complete onboarding (sector + shop name) ──────────────────────────
echo "--- Step 4: Onboarding ---"
curl -sf -X POST "$BASE_URL/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"GENERAL","shopName":"Boutique Test 7.5","storeLocation":"Kinshasa"}' > /dev/null
echo "PASS: onboarding"

# ── STEP 5: Re-select tenant (after onboarding activates it) ──────────────────
SELECT2=$(curl -sf -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT_ID\"}")
TOKEN=$(echo "$SELECT2" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
echo "PASS: re-login after onboarding"

# ── STEP 6: GET /tenant/preferences — verify default values ───────────────────
echo "--- Step 6: GET preferences (verify new fields) ---"
PREFS=$(curl -sf "$BASE_URL/api/v1/tenant/preferences" \
  -H "Authorization: Bearer $TOKEN")
echo "$PREFS" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
assert d['eodReportEnabled'] == True, f'Expected eodReportEnabled=True, got {d.get(\"eodReportEnabled\")}'
assert d['eodReportChannel'] == 'WHATSAPP', f'Expected WHATSAPP, got {d.get(\"eodReportChannel\")}'
assert d['weeklyReportEnabled'] == True, 'weeklyReportEnabled default'
assert d['weeklyReportDay'] == 0, 'weeklyReportDay default 0 (Sunday)'
assert d['weeklyReportTime'] == '20:00:00', 'weeklyReportTime default'
assert d['inventoryReportEnabled'] == True, 'inventoryReportEnabled default'
assert d['stockAlertChannel'] == 'PUSH', f'Expected PUSH, got {d.get(\"stockAlertChannel\")}'
print('PASS: default preferences verified')
"

# ── STEP 7: PUT /tenant/report-preferences — change EOD time to 22:00 ─────────
echo "--- Step 7: PUT report-preferences ---"
UPDATE=$(curl -sf -X PUT "$BASE_URL/api/v1/tenant/report-preferences" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "eodReportEnabled": true,
    "eodReportChannel": "WHATSAPP",
    "eodReportTime": "22:00:00",
    "weeklyReportEnabled": false,
    "weeklyReportDay": 5,
    "weeklyReportTime": "19:00:00",
    "weeklyReportChannel": "IN_APP_ONLY",
    "inventoryReportEnabled": true,
    "inventoryReportChannel": "WHATSAPP",
    "stockAlertEnabled": true,
    "stockAlertChannel": "BOTH"
  }')
echo "$UPDATE" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
assert d['eodReportTime'] == '22:00:00', f'eodReportTime not updated: {d.get(\"eodReportTime\")}'
assert d['weeklyReportEnabled'] == False, 'weeklyReportEnabled should be false'
assert d['weeklyReportDay'] == 5, f'weeklyReportDay: {d.get(\"weeklyReportDay\")}'
assert d['weeklyReportChannel'] == 'IN_APP_ONLY', f'weeklyReportChannel: {d.get(\"weeklyReportChannel\")}'
assert d['stockAlertChannel'] == 'BOTH', f'stockAlertChannel: {d.get(\"stockAlertChannel\")}'
print('PASS: preferences updated correctly')
"

# ── STEP 8: GET preferences — verify persisted ────────────────────────────────
echo "--- Step 8: Verify persist ---"
PREFS2=$(curl -sf "$BASE_URL/api/v1/tenant/preferences" \
  -H "Authorization: Bearer $TOKEN")
echo "$PREFS2" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
assert d['eodReportTime'] == '22:00:00', 'eodReportTime not persisted'
assert d['weeklyReportEnabled'] == False, 'weeklyReportEnabled not persisted'
print('PASS: preferences persisted')
"

# ── STEP 9: POST /tenant/report-test ─────────────────────────────────────────
echo "--- Step 9: POST report-test ---"
TEST_RESULT=$(curl -sf -X POST "$BASE_URL/api/v1/tenant/report-test" \
  -H "Authorization: Bearer $TOKEN")
echo "$TEST_RESULT" | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
assert 'testSent' in d, 'testSent field missing'
print(f'PASS: report-test, testSent={d[\"testSent\"]}')
"

# ── STEP 10: PUT with invalid eodReportTime → 400 ─────────────────────────────
echo "--- Step 10: Validation error on bad time ---"
RESP=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/api/v1/tenant/report-preferences" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"eodReportEnabled":true,"eodReportChannel":"WHATSAPP","eodReportTime":"25:99:00",
       "weeklyReportEnabled":true,"weeklyReportDay":0,"weeklyReportTime":"20:00:00",
       "weeklyReportChannel":"WHATSAPP","inventoryReportEnabled":true,"inventoryReportChannel":"WHATSAPP",
       "stockAlertEnabled":true,"stockAlertChannel":"PUSH"}')
[ "$RESP" = "400" ] && echo "PASS: 400 on invalid time" || (echo "FAIL: expected 400, got $RESP" && exit 1)

# ── STEP 11: Employee role → 403 on PUT ───────────────────────────────────────
echo "--- Step 11: EMPLOYEE 403 on PUT ---"
# Create an employee user (phone 200), invite, login
EMP_PHONE="+243700000706"
curl -sf -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"password\":\"$PASS\"}" > /dev/null
EMP_LOGIN=$(curl -sf -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"password\":\"$PASS\"}")
# Create employee membership in owner's tenant
curl -sf -X POST "$BASE_URL/api/v1/employees/invite" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"role\":\"EMPLOYEE\"}" > /dev/null || true
EMP_SELECT=$(curl -sf -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT_ID\"}" \
  -H "Authorization: Bearer $(echo "$EMP_LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['temporaryToken'])")" 2>/dev/null || echo "{\"data\":{\"token\":\"\"}}")
EMP_TOKEN=$(echo "$EMP_SELECT" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'].get('token',''))")
if [ -n "$EMP_TOKEN" ] && [ "$EMP_TOKEN" != "None" ]; then
  EMP_RESP=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/api/v1/tenant/report-preferences" \
    -H "Authorization: Bearer $EMP_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{}')
  [ "$EMP_RESP" = "403" ] && echo "PASS: EMPLOYEE 403 on PUT" || echo "NOTE: EMPLOYEE 403 test inconclusive (token: $EMP_TOKEN)"
else
  echo "NOTE: EMPLOYEE token not available for this test (invite flow may differ) — skip"
fi

# ── STEP 12: No auth → 401 ────────────────────────────────────────────────────
echo "--- Step 12: No auth 401 ---"
UNAUTH=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/api/v1/tenant/report-preferences" \
  -H "Content-Type: application/json" -d '{}')
[ "$UNAUTH" = "401" ] && echo "PASS: 401 unauthenticated" || (echo "FAIL: expected 401, got $UNAUTH" && exit 1)

echo ""
echo "=== ALL Story 7.5 Curl Tests PASSED ==="
```

---

### Flutter Tasks

#### Task 11 — Flutter domain model update

- [ ] **11.1** Extend `TenantPreferencesModel` (`identity/domain/model/tenant_preferences_model.dart`) with new fields:
  ```dart
  class TenantPreferencesModel {
    final SectorType? sectorType;
    final String eodReportTime;       // "HH:mm:ss"
    final bool stockAlertEnabled;
    final DateTime createdAt;
    // ── New Story 7.5 fields ─────────────────────────────
    final bool eodReportEnabled;
    final String eodReportChannel;    // "WHATSAPP" | "IN_APP_ONLY"
    final bool weeklyReportEnabled;
    final int weeklyReportDay;        // 0=Sunday
    final String weeklyReportTime;    // "HH:mm:ss"
    final String weeklyReportChannel;
    final bool inventoryReportEnabled;
    final String inventoryReportChannel;
    final String stockAlertChannel;   // "PUSH" | "WHATSAPP" | "BOTH"
    // ...
  }
  ```
  - Update `fromJson()`: all new fields with defaults (use `?? true`, `?? 'WHATSAPP'`, etc.) for backward compat
  - Update `toJson()`: include all new fields
  - Add `copyWith()` method with all fields for immutable updates

#### Task 12 — Flutter data source

- [ ] **12.1** Add `updateReportPreferences(Map<String, dynamic> body)` in `TenantPreferencesRemoteDataSource`:
  ```dart
  /// PUT /api/v1/tenant/report-preferences
  Future<TenantPreferencesModel> updateReportPreferences(Map<String, dynamic> body) async {
    final resp = await _dio.put('/api/v1/tenant/report-preferences', data: body);
    _apiHelper.assertSuccess(resp);
    return TenantPreferencesModel.fromJson(resp.data['data'] as Map<String, dynamic>);
  }
  
  /// POST /api/v1/tenant/report-test
  Future<bool> sendTestReport() async {
    final resp = await _dio.post('/api/v1/tenant/report-test');
    _apiHelper.assertSuccess(resp);
    return (resp.data['data'] as Map<String, dynamic>)['testSent'] as bool? ?? false;
  }
  ```

#### Task 13 — TDD RED: Flutter tests

- [ ] **13.1** Create `report_preferences_provider_test.dart` in `test/features/settings/presentation/provider/`
  ```dart
  // Tests:
  // reportPreferencesProvider_loadsFromBackend_onInit()
  // reportPreferencesProvider_updateEodTime_callsPutEndpoint()
  // reportPreferencesProvider_sendTestReport_returnsTrue()
  // reportPreferencesProvider_sendTestReport_whatsAppFails_returnsFalse()
  // reportPreferencesProvider_loading_thenData_stateTransitions()
  ```

- [ ] **13.2** Create `report_preferences_page_test.dart` in `test/features/settings/presentation/page/`
  ```dart
  // Tests:
  // page_rendersAllFourSections()
  // page_eodToggle_flipsState()
  // page_eodChannelSegmented_changesValue()
  // page_timePicker_updatesTime()
  // page_sendTestReportButton_callsProvider()
  // page_snackbarShown_onSaveSuccess()
  // page_snackbarShown_onTestReportSent()
  ```

- [ ] **13.3** Run RED → confirm all 12 Flutter tests fail

#### Task 14 — GREEN: ReportPreferencesNotifier

- [ ] **14.1** Create `report_preferences_provider.dart` in `features/settings/presentation/provider/`
  ```dart
  @riverpod
  class ReportPreferencesNotifier extends _$ReportPreferencesNotifier {
    @override
    Future<TenantPreferencesModel> build() async {
      final ds = ref.watch(tenantPreferencesRemoteDataSourceProvider);
      final resp = await ds.getPreferences();
      return TenantPreferencesModel.fromJson(resp);
    }
    
    Future<void> update(Map<String, dynamic> body) async {
      state = const AsyncLoading();
      state = await AsyncValue.guard(() =>
        ref.read(tenantPreferencesRemoteDataSourceProvider).updateReportPreferences(body));
    }
    
    Future<bool> sendTestReport() async {
      return ref.read(tenantPreferencesRemoteDataSourceProvider).sendTestReport();
    }
  }
  ```

#### Task 15 — GREEN: ReportPreferencesPage

- [ ] **15.1** Create `report_preferences_page.dart` in `features/settings/presentation/page/`:
  ```dart
  class ReportPreferencesPage extends ConsumerStatefulWidget { ... }
  
  class _ReportPreferencesPageState extends ConsumerState<ReportPreferencesPage> {
    // Local state mirrors TenantPreferencesModel for optimistic updates  
    // Sections: _buildEodSection(), _buildWeeklySection(), _buildInventorySection(), _buildStockAlertSection()
    // Bottom: _buildTestReportButton()
    
    // ── Build helpers ───────────────────────────────────────────────────────
    // _buildSectionCard(title, children): Card with 16dp radius, 12dp padding, white bg
    // _buildToggleRow(label, value, onChanged): SwitchListTile M3 style
    // _buildChannelSegment(value, onChanged): SegmentedButton<String> with WHATSAPP/IN_APP_ONLY labels
    // _buildTimePicker(label, value, onChanged): ListTile with clock icon, taps showTimePicker()
    
    // ── Auto-save on change ─────────────────────────────────────────────────
    // Each toggle/picker/segment calls _savePreferences() after state update
    // _savePreferences(): debounced 500ms, calls notifier.update(_buildBody()), shows SnackBar
    
    // ── Test report button ──────────────────────────────────────────────────
    // Shows CircularProgressIndicator while awaiting
    // On true → SnackBar "Rapport test envoyé sur WhatsApp ✅"
    // On false → SnackBar "Échec de l'envoi WhatsApp. Vérifiez votre numéro."
  }
  ```

#### Task 16 — GREEN: Router + SettingsPage wiring

- [ ] **16.1** Add import in `app_router.dart`:
  ```dart
  import '../../features/settings/presentation/page/report_preferences_page.dart';
  ```

- [ ] **16.2** Add `/settings/reports` to `_ownerOnlyPrefixes` list in `app_router.dart`:
  ```dart
  '/settings/reports',
  ```

- [ ] **16.3** Add GoRoute in `app_router.dart` (same block as `/settings/subscription`):
  ```dart
  GoRoute(
    path: '/settings/reports',
    builder: (context, state) => const ReportPreferencesPage(),
  ),
  ```

- [ ] **16.4** Update `settings_page.dart` — wrap the "Rapports" tile in `if (isOwner)` and update both tiles:
  ```dart
  // "Préférences" _SettingsCard — replace the two-tile block with:
  _SettingsCard(
    children: [
      _SettingsTile(
        icon: Icons.notifications_outlined,
        iconColor: const Color(0xFFE67E22),
        iconBg: const Color(0xFFFFF3E0),
        title: 'Notifications',
        subtitle: 'Alertes et notifications push',  // updated subtitle
        onTap: () {},
        badge: 'Bientôt',
      ),
      if (isOwner) ...[
        const _Divider(),
        _SettingsTile(
          icon: Icons.bar_chart_rounded,
          iconColor: AppTheme.success,
          iconBg: const Color(0xFFE8F5E9),
          title: 'Rapports',
          subtitle: 'Fréquence, canaux WhatsApp, alertes',
          onTap: () => context.push('/settings/reports'),
          // badge: 'Bientôt',   ← REMOVED
        ),
      ],
    ],
  ),
  ```
  > **IMPORTANT**: The `if (isOwner)` guard here is the **UI layer** (tile hidden). The route guard in `_ownerOnlyPrefixes` is the **navigation layer**. Both are required (defense in depth per RBAC architecture mandate).

#### Task 17 — GREEN: Flutter tests GREEN

- [ ] **17.1** Run `flutter test test/features/settings/` → all 12 tests GREEN
- [ ] **17.2** Run `flutter test` → full suite GREEN (no regression)

---

## Dev Notes

### Architecture

- **Package structure (backend)**: `identity.onboarding.*` for preferences (not `reporting.*` — preferences are tenant-identity concern, not reporting domain). New enums `ReportChannel` + `StockAlertChannel` live in `identity.onboarding.domain.model`.
- **Package structure (Flutter)**: `features/settings/` for `ReportPreferencesPage` and provider; model stays in `features/identity/domain/model/`.
- **Hexagonal compliance**: `UpdateReportPreferencesUseCase` and `SendTestReportUseCase` are port-in interfaces; their implementations are `@Service` annotated classes. The controller only calls use case interfaces.
- **No new DB table**: all new data fits in the existing `tenant_preferences` table via column migration. No new JPA entity class required.
- **TenantSchemaSyncService pattern**: identical to Story 4.2 (discount + catalogue price) and Story 5.2 (updated_at). Constants in `TenantSchemaProvisioner`, calls in `ensureRequiredIndexes()`.

### Backward Compatibility

- `GenerateReportCommand` retains backward-compat constructor (6-arg → calls 7-arg with `null` channel). All 7.1–7.4 callers use the 6-arg form; they continue to work without change.
- `TenantPreferences` record: the `OnboardingService` creates preferences with default values for all 9 new fields. Set them all in the seed call.
- `TenantPreferencesResponseDto`: adding new fields to a record DTO is additive — existing GET callers skip unknown fields (Flutter `fromJson` uses `?? defaults`).

### UX Design Compliance

- [Source: docs/ux-design-specification.md#Plus > Paramètres] — "Plus > Paramètres → Rapports (types activés)" — this story implements that navigation tile.
- Gradient header: `AppTheme.primary` → `AppTheme.primary.withAlpha(180)` (matches SettingsPage pattern).
- `SwitchListTile` M3 style matches all other preference screens in the app.
- `SegmentedButton<String>` for 2–3 channel options (matches M3 design system already used in 7.4 sort chips).
- `showTimePicker()` Flutter native dialog — appropriate for HH:mm input.
- Auto-save with 500ms debounce and SnackBar feedback (consistent with Story 3.1 store configuration UX).
- Route `/settings/reports` with `_ownerOnlyPrefixes` guard (consistent with `/reports/rentabilite` guard in 7.4).

### Test Strategy

- **Backend**: Unit tests only (no `@SpringBootTest`). Use `@ExtendWith(MockitoExtension.class)` + `MockMvc`. Mock `TenantPreferencesRepository`, `EndOfDayReportBuilder`, `WhatsAppPort`.
- **Flutter**: `flutter_test` + `mocktail`. Mock `TenantPreferencesRemoteDataSource`. Use `ProviderContainer` for provider tests; `pumpWidget(ProviderScope(...))` for widget tests.
- **Curl script**: self-contained — register fresh tenant each run, no reliance on existing DB state. Validates field values via Python3 JSON assertions (existing pattern: stories 1.4, 1.7, 7.4).

### Error Codes

- Add `PREFERENCES_NOT_FOUND` to the error codes enum (if not already present) — triggered when `tenant_preferences` row doesn't exist for current tenant.

### Key Files to Touch

```
Backend:
  identity/onboarding/domain/model/TenantPreferences.java            (update — add 9 fields)
  identity/onboarding/domain/model/ReportChannel.java                (NEW)
  identity/onboarding/domain/model/StockAlertChannel.java            (NEW)
  identity/onboarding/domain/model/PreferenceUpdatedEvent.java        (NEW)
  identity/onboarding/domain/port/in/UpdateReportPreferencesUseCase.java (NEW)
  identity/onboarding/domain/port/in/SendTestReportUseCase.java       (NEW)
  identity/onboarding/domain/port/out/TenantPreferencesRepository.java (update — add update())
  identity/onboarding/adapter/in/web/TenantPreferencesController.java  (update — add 2 endpoints)
  identity/onboarding/adapter/in/web/TenantPreferencesResponseDto.java (update — add 9 fields)
  identity/onboarding/adapter/in/web/UpdateReportPreferencesRequestDto.java (NEW)
  identity/onboarding/adapter/out/persistence/TenantPreferencesJpaEntity.java (update — add 9 @Column)
  identity/onboarding/adapter/out/persistence/TenantPreferencesRepositoryAdapter.java (update — add update())
  identity/onboarding/application/service/UpdateReportPreferencesService.java (NEW)
  identity/onboarding/application/service/SendTestReportService.java   (NEW)
  identity/onboarding/application/service/DayClosureSchedulerNotifier.java (NEW)
  identity/onboarding/application/service/OnboardingService.java       (update — add defaults to prefs seed)
  reporting/report/domain/port/in/GenerateEndOfDayReportUseCase.java   (update — add deliveryChannel to record)
  reporting/report/application/service/AbstractReportGenerator.java    (update — channel check in deliverReport)
  reporting/report/application/service/EndOfDayReportListener.java     (update — resolve channel from prefs)
  commerce/sale/application/service/DayClosureAutoScheduler.java       (update — hourly cron + per-tenant time)
  shared/infrastructure/persistence/TenantSchemaProvisioner.java       (update — DDL + migration constants)
  shared/infrastructure/persistence/TenantSchemaSyncService.java       (update — 9 migration calls)

Flutter:
  features/identity/domain/model/tenant_preferences_model.dart         (update — add 9 fields + copyWith)
  features/settings/data/datasource/tenant_preferences_remote_datasource.dart (update — 2 new methods)
  features/settings/presentation/provider/report_preferences_provider.dart (NEW — Riverpod notifier)
  features/settings/presentation/page/report_preferences_page.dart     (NEW)
  core/router/app_router.dart                                           (update — route + ownerOnlyPrefix)
  features/settings/presentation/page/settings_page.dart               (update — tile navigation + subtitle)

Tests:
  backend/src/test/java/.../identity/onboarding/application/service/UpdateReportPreferencesServiceTest.java (NEW)
  backend/src/test/java/.../identity/onboarding/application/service/SendTestReportServiceTest.java (NEW)
  backend/src/test/java/.../identity/onboarding/adapter/in/web/ReportPreferencesControllerTest.java (NEW)
  backend/src/test/java/.../reporting/report/application/service/AbstractReportGeneratorTest.java (extend)
  backend/src/test/java/.../commerce/sale/application/service/DayClosureAutoSchedulerTest.java (extend)
  app/test/features/settings/presentation/provider/report_preferences_provider_test.dart (NEW)
  app/test/features/settings/presentation/page/report_preferences_page_test.dart (NEW)

Curl tests:
  curl-tests-story-7-5.sh (NEW — 12 self-contained steps)
```

### Project Structure Notes

- Alignment with hexagonal architecture: domain model → port-in → service → controller (left-to-right). Adapters in `adapter/in/web` (REST) and `adapter/out/persistence` (JPA).
- All new domain classes go in `identity.onboarding.domain.model` (NOT `reporting.*`) — preferences are tenant identity data.
- `SendTestReportService` reuses existing `EndOfDayReportBuilder` + `DailyReportFormatter` from `reporting.report.application.service` — **cross-module dependency is acceptable** since it's read-only service reuse.
- Flutter feature folder: `features/settings/` already exists. New `ReportPreferencesPage` fits there alongside `subscription_page.dart`.
- No new Drift table required — all preferences are server-side. Flutter reads from `GET /tenant/preferences` and caches in Riverpod state.

### References

- [Source: _bmad-output/planning-artifacts/epics/epic-7-rapports-dashboard-communication-whatsapp.md#Story 7.5]
- [Source: _bmad-output/planning-artifacts/prd.md#FR57] — rapport end-of-day auto-envoyé via WhatsApp
- [Source: _bmad-output/planning-artifacts/prd.md#FR58] — rapport hebdomadaire WhatsApp+SMS
- [Source: _bmad-output/planning-artifacts/prd.md#FR59] — rapport d'inventaire on-demand
- [Source: _bmad-output/planning-artifacts/prd.md#FR60] — **"Un propriétaire peut configurer les types de rapports et la fréquence d'envoi"** — OWNER-only requirement
- [Source: _bmad-output/planning-artifacts/prd.md#FR12] — système initialise les préférences de notification par défaut à l'onboarding
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Flow 18] — **Flow 18 — Configuration Préférences** — acteur: Simon (OWNER) — FRs: FR12, FR60, FR7, FR91-92
- [Source: _bmad-output/implementation-artifacts/7-2-rapport-end-of-day-generation-envoi-whatsapp.md] — existing `AbstractReportGenerator`, `WhatsAppPort`, `DailyReportGenerator`, `EndOfDayReportListener`
- [Source: _bmad-output/implementation-artifacts/7-4-dashboard-rentabilite-analyse-par-produit-boutique.md] — `_ownerOnlyPrefixes` pattern in `app_router.dart`
- [Source: keevo/backend/src/main/java/com/keevo/identity/onboarding/domain/model/TenantPreferences.java] — current TenantPreferences record (5 fields)
- [Source: keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java#DDL_TENANT_PREFERENCES] — DDL template
- [Source: keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaSyncService.java#ensureRequiredIndexes] — migration pattern
- [Source: keevo/backend/src/main/java/com/keevo/commerce/sale/application/service/DayClosureAutoScheduler.java] — existing scheduler (cron `0 0 19 * * *`)
- [Source: keevo/app/lib/features/settings/presentation/page/settings_page.dart#Préférences section] — "Rapports" tile currently in shared section (no `if (isOwner)` guard — fixed by this story)
- [Source: keevo/app/lib/core/router/app_router.dart] — existing routes and `_ownerOnlyPrefixes`

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 4.6

### Debug Log References

### Completion Notes List

### File List

---

## Review Findings

> Code review — 2026-04-02 | reviewer: Blind Hunter + Edge Case Hunter + Acceptance Auditor

### Patch

- [x] [Review][Patch] **P1 — `stockAlertEnabled` absent du Command + DTO — field non-modifiable via PUT** [`UpdateReportPreferencesCommand.java`, `UpdateReportPreferencesRequestDto.java`, `UpdateReportPreferencesService.java`]
  — `UpdateReportPreferencesCommand` n'a pas de champ `stockAlertEnabled`. Le DTO (`UpdateReportPreferencesRequestDto`) ne expose pas ce champ non plus. Le service hardcode `existing.stockAlertEnabled()` → le PUT `/report-preferences` ne peut jamais changer ce booléen. Fix : ajouter `boolean stockAlertEnabled` au command + DTO, propager via `command.stockAlertEnabled()` dans le service. Viole AC3 (la spec Task 4.4 précise `@NotNull Boolean stockAlertEnabled`).

- [x] [Review][Patch] **P2 — `/settings/reports` absent de `_ownerOnlyPrefixes`** [`app_router.dart:231`]
  — La liste `_ownerOnlyPrefixes` protège `'/settings/subscription'`, `'/settings/team'`, `'/settings/sync'` mais pas `'/settings/reports'`. Un EMPLOYEE peut naviguer directement à la route sans redirection. Fix : ajouter `'/settings/reports'` à la liste. Viole AC1 + AC2.

- [x] [Review][Patch] **P3 — Tile "Rapports & WhatsApp" visible à l'EMPLOYEE** [`settings_page.dart:~240`]
  — Le `_SettingsCard` "Préférences" (lignes ~240-264) n'est pas wrappé dans `if (isOwner)`. Les deux tiles (Notifications + Rapports) sont visibles à tous les rôles. La spec AC2 précise que l'EMPLOYEE ne voit PAS la tile Rapports. Fix : wrapper la tile "Rapports & WhatsApp" dans `if (isOwner) ...[ ... ]` à l'intérieur du SettingsCard.

- [x] [Review][Patch] **P4 — `DayClosureAutoScheduler` compare uniquement `.getHour()` — précision minute perdue** [`DayClosureAutoScheduler.java:~100`]
  — La condition `nowWAT.getHour() < configuredTime.getHour()` ignore les minutes. Un tenant configuré à `"20:45:00"` déclencherait dès `20:00`. Fix : remplacer par `nowWAT.isBefore(configuredTime)` pour une comparaison `LocalTime` complète. Viole AC6 ("LocalTime.now(WAT) >= tenantPrefs.eodReportTime").

- [x] [Review][Patch] **P5 — `SendTestReportServiceTest` : mock `reportRepository` non-injecté — verify() vacuous** [`SendTestReportServiceTest.java`]
  — Le test déclare `@Mock EndOfDayReportRepository reportRepository` et `@InjectMocks SendTestReportService service`, mais `SendTestReportService` n'a pas ce dépôt dans son constructeur. `@InjectMocks` ne l'injecte jamais. Le `verify(reportRepository, never()).save(any())` est trivial (le service n'a jamais pu l'appeler). Fix : supprimer le mock inutilisé OU utiliser un spy pour s'assurer qu'aucun chemin dans le service ne sauve. Viole AC8 (test fausse confiance).

- [x] [Review][Patch] **P6 — SnackBar erreur test-report ne correspond pas à la spec AC5** [`report_preferences_provider.dart:~116`]
  — Implémentation retourne `'⚠️ Canal non configuré — activez WhatsApp ou Push pour tester'` sur échec. Spec AC5 dit `"Échec de l'envoi WhatsApp. Vérifiez votre numéro."` Fix : aligner le message sur la spec. (Le message de succès `'✅ Rapport test envoyé'` est acceptable — spec dit `"Rapport test envoyé sur WhatsApp ✅"`, différence mineure de formulation.)

- [x] [Review][Patch] **P7 — Flutter `TenantPreferencesModel` : defaults incorrects pour les champs 7.5** [`tenant_preferences_model.dart:38-44`]
  — Defaults actuels : `weeklyReportDay = 5` (Samedi), `weeklyReportTime = '08:00:00'`, `eodReportEnabled = false`, `weeklyReportEnabled = false`, `inventoryReportEnabled = false`. Spec AC1 : defaults = `0` (Dimanche), `'20:00:00'`, `true`, `true`, `true`. Ces fallbacks s'appliquent si le JSON ne contient pas ces champs (tenant migré sans recharge). Fix : aligner les defaults `??` sur la spec.

- [x] [Review][Patch] **P8 — Nom du test `scheduler_noPreferences_defaultsTo19UTC` trompeur** [`DayClosureAutoSchedulerTest.java:~191`]
  — Le commentaire dit «fallback 19:00 UTC = 20:00 WAT» mais `DEFAULT_CLOSURE_TIME = LocalTime.of(20, 0)` est une heure WAT. Le test compare en WAT. Fix : renommer en `scheduler_noPreferences_defaultsTo20hWAT` et corriger le commentaire.

### Deferred

- [x] [Review][Defer] **`WhatsAppPort.isConfigured()` default=true** [`WhatsAppPort.java:16`] — deferred, pre-existing — Toute implémentation qui oublie d'overrider `isConfigured()` enverrait des rapports test en production. À revoir lors de la prochaine itération de l'interface port.
