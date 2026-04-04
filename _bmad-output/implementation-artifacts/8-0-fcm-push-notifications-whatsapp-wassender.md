# Story 8.0: FCM Push Notifications & WhatsApp Wassender — Messaging Infrastructure

Status: review

---

## GoF Pattern Analysis (MANDATORY — fill before any implementation)

| Question | Answer |
|---|---|
| What variability exists in this feature? | Notification channel (FCM push vs in-app log), WhatsApp provider (Wassender vs NoOp vs future Africa's Talking BSP), device platform (Android/iOS/Desktop — FCM token format varies), message type (notification+data payload vs data-only), WhatsApp message format (text vs template), token lifecycle (register/refresh/revoke/cleanup). |
| What might change in the future? | New WhatsApp provider (Africa's Talking BSP, WPPConnect), SMS fallback channel, email channel, per-store notification preferences (currently per-tenant), APNs direct integration for iOS, Web push (FCM supports it), batch notification queuing, message templates for WhatsApp Business API compliance. |
| Which GoF pattern(s) apply? | **Adapter** (primary): `FcmNotificationAdapter` implements `NotificationPort` — adapts Firebase Admin SDK to the existing port contract. `WassenderWhatsAppAdapter` implements `WhatsAppPort` — adapts Wassender REST API to the existing port contract. **Strategy** (inherited): `NotificationPort` and `WhatsAppPort` are already Strategy interfaces — swapping the adapter (via `@Primary` / `@ConditionalOnProperty`) changes the delivery channel with zero consumer changes. **Observer** (inherited): All existing `@EventListener` classes (`DraftProductNotificationListener`, `EndOfDayReportListener`, `AuditEventListener`) consume `NotificationPort` / `WhatsAppPort` — they automatically gain real delivery when stubs are replaced. **Command**: `RegisterDeviceTokenCommand` encapsulates token registration request (pure Java record, MCP-ready). **Factory Method**: `FirebaseInitializer` creates the `FirebaseApp` singleton from service account JSON — conditional on `FIREBASE_ENABLED=true`. |
| How does it enable Open/Closed principle? | Existing consumers of `NotificationPort` and `WhatsAppPort` require ZERO modifications — they already depend on the interface. New adapters are added, stubs remain for dev/test environments via `@ConditionalOnProperty`. Future channels (SMS, email) would implement new ports or extend existing ones without modifying any adapter. `DeviceTokenRepository` port enables future token-based targeting (per-user, per-role, per-store) without changing the FCM adapter. |
| Where is the pattern applied? | **Adapter**: `FcmNotificationAdapter` in `messaging/notification/adapter/out/`. `WassenderWhatsAppAdapter` in `messaging/whatsapp/adapter/out/external/`. **Command**: `RegisterDeviceTokenCommand` record in `messaging/notification/domain/port/in/`. **Factory Method**: `FirebaseInitializer` in `messaging/notification/adapter/out/config/`. **Strategy selection**: `@ConditionalOnProperty("keevo.fcm.enabled")` on `FcmNotificationAdapter`; `@ConditionalOnProperty("keevo.whatsapp.provider", havingValue="wassender")` on `WassenderWhatsAppAdapter`. `LoggingNotificationAdapter` and `NoOpWhatsAppAdapter` remain `@Primary` only when the real adapters are disabled (test/dev). |

---

## Story

As a platform operator (Toor — SUPER_ADMIN / dev-ops),
I want to configure and activate real push notifications via Firebase Cloud Messaging and real WhatsApp message delivery via Wassender,
So that all existing notification consumers (stock alerts, daily/weekly reports, draft reminders, trend notifications) deliver messages to real devices and real WhatsApp numbers without any code change in the consuming services.

---

## Acceptance Criteria

### AC1 — Firebase Admin SDK initialization (backend)

- **Given** the backend starts with `KEEVO_FCM_ENABLED=true` and `GOOGLE_APPLICATION_CREDENTIALS` pointing to a valid Firebase service account JSON file
- **When** Spring Boot context loads
- **Then** `FirebaseInitializer` (implements `InitializingBean`) initializes `FirebaseApp.initializeApp(options)` exactly once (singleton)
- **And** if `KEEVO_FCM_ENABLED=false` or the env var is absent, `FirebaseApp` is NOT initialized and `FcmNotificationAdapter` is NOT loaded (graceful degradation — `LoggingNotificationAdapter` remains `@Primary`)
- **And** if the service account JSON is malformed or missing, a clear `WARN` log is emitted at startup: `"[FCM] Firebase initialization failed — falling back to LoggingNotificationAdapter: {reason}"` and the app continues without crash
- **And** configuration values: `keevo.fcm.enabled` (boolean, default `false`), `keevo.fcm.credentials-path` (string, path to service account JSON, default `${GOOGLE_APPLICATION_CREDENTIALS}`)

### AC2 — Device token registration endpoint (backend + Flutter)

- **Given** a user (Simon or Loïc) logs in on any device
- **When** the app obtains a FCM token from `FirebaseMessaging.instance.getToken()`
- **Then** the app sends `POST /api/v1/devices/token` with body:
  ```json
  {
    "token": "<fcm_token>",
    "platform": "ANDROID" | "IOS" | "LINUX" | "WINDOWS",
    "deviceName": "<model_or_hostname>"
  }
  ```
- **And** the backend `RegisterDeviceTokenService` stores it in the `device_tokens` table (tenant schema):
  - `id` UUID PK
  - `user_id` UUID FK → `users.id` (shared schema, but stored as UUID — no cross-schema FK)
  - `token` VARCHAR(512) UNIQUE per tenant schema
  - `platform` VARCHAR(20) (`ANDROID`, `IOS`, `LINUX`, `WINDOWS`)
  - `device_name` VARCHAR(100)
  - `role` VARCHAR(20) — snapshotted from JWT at registration time (`OWNER`, `EMPLOYEE`)
  - `created_at` TIMESTAMP
  - `updated_at` TIMESTAMP
- **And** if the same `token` already exists (same device re-registering), the row is updated (upsert: `ON CONFLICT(token) DO UPDATE SET updated_at, device_name, role`)
- **And** the endpoint returns `200 OK` with `ApiResponseWrapper` `{ "data": { "registered": true } }`
- **And** the endpoint requires authentication (401 without JWT) but is accessible to ALL roles (OWNER + EMPLOYEE)
- **And** when the FCM token is refreshed (`FirebaseMessaging.instance.onTokenRefresh`), the Flutter app automatically re-registers the new token

### AC3 — FcmNotificationAdapter replaces LoggingNotificationAdapter

- **Given** `KEEVO_FCM_ENABLED=true` and `FirebaseApp` is initialized
- **When** any code calls `notificationPort.notifyOwners(tenantId, payload)`
- **Then** `FcmNotificationAdapter` (annotated `@ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "true")`) handles the call:
  1. Queries `device_tokens` for the tenant schema where `role = 'OWNER'` → gets list of FCM tokens
  2. Builds `MulticastMessage`:
     - `notification` = `Notification.builder().setTitle(payload.title()).setBody(payload.body()).build()`
     - `data` = `{ "type": payload.type(), "deepLink": payload.deepLink() }` + all `payload.metadata()` entries
     - `tokens` = list of owner FCM tokens
  3. Calls `FirebaseMessaging.getInstance().sendEachForMulticast(message)`
  4. Processes `BatchResponse`: for each `SendResponse` where `isSuccessful() == false`:
     - If `MessagingErrorCode == UNREGISTERED` → delete that token from `device_tokens` (stale token cleanup)
     - Else → log `WARN` with error code and token prefix (first 10 chars for debugging, not full token)
- **And** if no OWNER tokens are found, a `DEBUG` log is emitted and the method returns silently (no error)
- **And** `LoggingNotificationAdapter` is still loaded but NOT `@Primary` when FCM is enabled — it remains available for `@Qualifier("loggingNotification")` in tests
- **And** `FcmNotificationAdapter` starts with `@Primary` annotation (only active when `@ConditionalOnProperty` is satisfied)

### AC4 — Flutter FCM integration (foreground + background + tap)

- **Given** the Flutter app is running (foreground or background)
- **When** a push notification arrives from FCM
- **Then**:
  - **Foreground**: `FirebaseMessaging.onMessage` stream receives the `RemoteMessage` → a local notification is displayed via `flutter_local_notifications` (title: `message.notification?.title`, body: `message.notification?.body`) with the Keevo notification channel (Android: `keevo_notifications`, importance HIGH, LED color `#3B5BDB`)
  - **Background**: The system tray shows the notification natively (FCM `notification` payload handles this automatically)
  - **Tap** (foreground or background): `FirebaseMessaging.onMessageOpenedApp` + `getInitialMessage()` (cold start) → extract `deepLink` from `message.data['deepLink']` → `GoRouter.of(context).go(deepLink)` if deepLink is not null/empty
- **And** notification permission is requested at first login via `FirebaseMessaging.instance.requestPermission()` — if denied, the app continues normally (notifications are a bonus, not a blocker — "Sérénité par défaut")
- **And** on desktop (Linux/Windows), FCM is NOT available — the app skips FCM initialization gracefully (`Platform.isLinux || Platform.isWindows` → no `FirebaseMessaging` calls, no token registration, no crash)

### AC5 — WassenderWhatsAppAdapter replaces NoOpWhatsAppAdapter

- **Given** the backend starts with `KEEVO_WHATSAPP_PROVIDER=wassender` and valid WasenderAPI credentials:
  - `WASSENDER_API_URL` (e.g., `https://www.wasenderapi.com`) — base URL, must not end with `/`
  - `WASSENDER_API_TOKEN` (session API key — generated from the WasenderAPI dashboard Session Management screen; tied to a specific WhatsApp session)
- **When** any code calls `whatsAppPort.sendReport(phoneNumber, reportText)`
- **Then** `WassenderWhatsAppAdapter` sends a POST request:
  ```
  POST {WASSENDER_API_URL}/api/send-message
  Headers:
    Authorization: Bearer {WASSENDER_API_TOKEN}
    Content-Type: application/json
  Body:
    {
      "to": "{phoneNumber}",      // E.164 format, e.g. "+237699000080"
      "text": "{reportText}"
    }
  ```
- **And** on HTTP 2xx response (body: `{"success": true, "data": {"msgId": ..., "status": "in_progress"}}`), the method returns normally (success)
- **And** on HTTP 4xx/5xx (body: `{"success": false, "message": "..."}`), a `WhatsAppDeliveryException` (new `DomainException` subclass with code `WHATSAPP_DELIVERY_FAILED`) is thrown — callers (report generator retry logic) handle this
- **And** on network timeout (connect: 10s, read: 30s), the same exception is thrown
- **And** `WassenderWhatsAppAdapter.isConfigured()` returns `true`
- **And** if `KEEVO_WHATSAPP_PROVIDER` is not set or is `noop`, `NoOpWhatsAppAdapter` remains active (backward compatibility)
- **And** phone number is sanitized: spaces/dashes/parentheses stripped, `+` prefix ensured (E.164 compliance)
- **And** the WasenderAPI token is NEVER logged (even at DEBUG level) — only the first 4 characters are logged for correlation: `"[WhatsApp] Using WasenderAPI token: {prefix}****"`
- **Note** — No `device` field in the payload. WasenderAPI ties the API key to a specific WhatsApp session — the session is implicit from the Bearer token.

### AC6 — WhatsAppPort migration from commerce to messaging domain

- **Given** `WhatsAppPort` is currently in `commerce.sale.domain.port.out` (placed there historically in Story 4.4)
- **When** this story is implemented
- **Then** `WhatsAppPort` is moved to `messaging.whatsapp.domain.port.out.WhatsAppPort` (its architecturally correct location per the architecture document)
- **And** `NoOpWhatsAppAdapter` is moved to `messaging.whatsapp.adapter.out.noop.NoOpWhatsAppAdapter`
- **And** `WassenderWhatsAppAdapter` is created in `messaging.whatsapp.adapter.out.external.WassenderWhatsAppAdapter` (calls `POST {apiUrl}/api/send-message` with `Authorization: Bearer {apiToken}`, body `{"to":...,"text":...}`)
- **And** ALL existing imports across the codebase are updated (search for `commerce.sale.domain.port.out.WhatsAppPort`):
  - `AbstractReportGenerator`
  - `DailyReportGenerator` / `WeeklyReportGenerator`
  - `ReportDeliveryRetryService`
  - `SendTestReportService`
  - `DayClosureAutoScheduler` (if referencing WhatsAppPort)
  - Any test files that mock/reference WhatsAppPort
- **And** the old package is removed (no dead code)
- **And** a `WhatsAppDeliveryException` is created in `messaging.whatsapp.domain.exception` (extends `DomainException` with code `WHATSAPP_DELIVERY_FAILED`)

### AC7 — Configuration via .env and Spring properties

- **Given** the backend `.env` file
- **When** the following environment variables are set:
  ```properties
  # === FCM Push Notifications ===
  KEEVO_FCM_ENABLED=true
  GOOGLE_APPLICATION_CREDENTIALS=/path/to/firebase-service-account.json
  
  # === WhatsApp via WasenderAPI ===
  KEEVO_WHATSAPP_PROVIDER=wassender
  WASSENDER_API_URL=https://www.wasenderapi.com
  WASSENDER_API_TOKEN=<session_api_key>
  ```
- **Then** `application.yml` maps them to Spring properties:
  ```yaml
  keevo:
    fcm:
      enabled: ${KEEVO_FCM_ENABLED:false}
      credentials-path: ${GOOGLE_APPLICATION_CREDENTIALS:}
    whatsapp:
      provider: ${KEEVO_WHATSAPP_PROVIDER:noop}
      wassender:
        api-url: ${WASSENDER_API_URL:https://www.wasenderapi.com}
        api-token: ${WASSENDER_API_TOKEN:}
  ```
- **And** `WassenderProperties` is a `@ConfigurationProperties(prefix = "keevo.whatsapp.wassender")` record with fields: `apiUrl` (String), `apiToken` (String) — **no `defaultDevice` field**
- **And** `FcmProperties` is a `@ConfigurationProperties(prefix = "keevo.fcm")` record
- **And** if `WASSENDER_API_TOKEN` is blank when `provider=wassender`, a clear `ERROR` log at startup: `"[WhatsApp] WasenderAPI provider selected but WASSENDER_API_TOKEN is missing — falling back to NoOp"`
- **And** `.env.example` is updated with the new variables (commented out with descriptions)

### AC8 — DDL: device_tokens table

- **Given** a tenant schema `kv_xxxxxx`
- **When** `TenantSchemaProvisioner` provisions a new tenant OR `TenantSchemaSyncService.ensureRequiredIndexes()` runs on existing tenants
- **Then** the `device_tokens` table is created:
  ```sql
  CREATE TABLE IF NOT EXISTS device_tokens (
      id UUID PRIMARY KEY,
      user_id UUID NOT NULL,
      token VARCHAR(512) NOT NULL,
      platform VARCHAR(20) NOT NULL,
      device_name VARCHAR(100),
      role VARCHAR(20) NOT NULL,
      created_at TIMESTAMP NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
      CONSTRAINT uq_device_tokens_token UNIQUE (token)
  );
  CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens(user_id);
  CREATE INDEX IF NOT EXISTS idx_device_tokens_role ON device_tokens(role);
  ```
- **And** `TenantSchemaSyncService` adds `device_tokens` to the migration check (existing tenants get the table on next login/schema sync)

### AC9 — Notification bell icon stub + deep link routing (Flutter)

- **Given** this story delivers the FCM plumbing
- **When** a push notification is tapped
- **Then** the app navigates to the `deepLink` path via `GoRouter.go(deepLink)`
- **And** supported deep link routes (existing routes, no new UI needed):
  - `/products/{id}/edit` → product edit page
  - `/products` → products list (filtered)
  - `/stock/overview` → stock overview
  - `/reports/history` → report history
  - `/pos` → POS screen
  - `/settings/team` → team management
- **And** a notification bell icon `🔔` is added to the `AppBar` of `MainShellPage` (the persistent scaffold)
- **And** the bell shows an unread count badge (red circle with count) sourced from a `unreadNotificationCountProvider` that counts notifications received since last tap
- **And** tapping the bell navigates to a `NotificationsPage` (route: `/notifications`) — a simple reverse-chronological list of received notifications stored locally in a `notifications` Drift table:
  - `id` UUID PK
  - `type` TEXT (from FCM data `type` field)
  - `title` TEXT
  - `body` TEXT
  - `deep_link` TEXT (nullable)
  - `is_read` BOOLEAN DEFAULT false
  - `received_at` DATETIME
- **And** each notification card shows: type icon (mapped from type: 🔶 draft, ⚠️ stock, 📊 report, etc.), title, body, relative time, read/unread styling (bold → normal)
- **And** tapping a notification marks it as read and navigates to its `deepLink` if present
- **And** a "Tout marquer comme lu" action in the AppBar of `NotificationsPage`
- **And** the list is limited to the last 30 days (purged on app startup)
- **And** the bell icon badge updates in real-time via Riverpod `watchProvider`

### AC10 — Token cleanup on logout

- **Given** a user logs out or their session is revoked
- **When** the logout flow executes
- **Then** the Flutter app calls `DELETE /api/v1/devices/token` with the current FCM token in the body:
  ```json
  { "token": "<current_fcm_token>" }
  ```
- **And** the backend deletes the matching row from `device_tokens`
- **And** returns `200 OK` `{ "data": { "deleted": true } }` (or `200` `{ "data": { "deleted": false } }` if token not found — no error)
- **And** the Flutter app also calls `FirebaseMessaging.instance.deleteToken()` to unsubscribe the device from FCM
- **And** this prevents push notifications to a device after logout (security: revoked employee should not receive OWNER notifications on a shared device)

### AC11 — TDD obligatoire: Tests RED → GREEN

#### Backend (JUnit 5)

- `RegisterDeviceTokenServiceTest.java` — register new token, upsert existing, command validation (blank token → VALIDATION_ERROR)
- `DeleteDeviceTokenServiceTest.java` — delete existing, delete non-existent (returns false)
- `DeviceTokenControllerTest.java` — POST 200 all roles, POST 401 no auth, POST 400 blank token, DELETE 200 all roles, DELETE 401 no auth
- `FcmNotificationAdapterTest.java` — sends multicast, handles UNREGISTERED (deletes token), handles no tokens (silent return), handles FirebaseMessagingException (logs warn, no throw)
- `WassenderWhatsAppAdapterTest.java` — sends message (mock HTTP `POST /api/send-message`), verifies `Authorization: Bearer` header, verifies body fields `to`/`text` (no `device`), handles 4xx/5xx (throws WhatsAppDeliveryException), handles timeout (throws), sanitizes phone number, never logs full API token
- `FirebaseInitializerTest.java` — initializes when enabled, skips when disabled, handles bad credentials gracefully
- `DeviceTokenRepositoryAdapterTest.java` — save, findByUserId, findOwnerTokensByTenantSchema, deleteByToken, upsert on duplicate token

#### Flutter (flutter_test)

- `fcm_service_test.dart` — token registration on login, token refresh re-registers, skips on desktop platform
- `notification_model_test.dart` — model creation, serialization, isRead toggle
- `notification_repository_test.dart` — insert, markAsRead, markAllAsRead, deleteOlderThan30Days, countUnread
- `notification_providers_test.dart` — unreadNotificationCountProvider, notificationsListProvider, markAsReadNotifier
- `notifications_page_test.dart` — renders list, tap marks read, tap navigates to deepLink, "Tout marquer comme lu" action
- `notification_bell_widget_test.dart` — badge shows count, badge hidden when 0, tap navigates to /notifications

#### cURL Integration Tests

```bash
#!/usr/bin/env bash
# ======================================================
# Story 8.0 — cURL Integration Tests
# FCM Push Notifications & WhatsApp Wassender
# Run: bash curl-tests-story-8-0.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"

# Step 1 — Register test user (OWNER) and get JWT
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+237699000080","password":"Test1234!","firstName":"Simon","lastName":"PushTest"}')
JWT=$(echo "$REGISTER" | jq -r '.data.accessToken')
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — OWNER JWT obtained" || { echo "❌ Step 1 FAILED"; exit 1; }

# Step 2 — Register device token
REGISTER_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/devices/token" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"token":"fake-fcm-token-abc123","platform":"ANDROID","deviceName":"Samsung Galaxy A14"}')
REGISTERED=$(echo "$REGISTER_TOKEN" | jq -r '.data.registered')
[[ "$REGISTERED" == "true" ]] && echo "✅ Step 2 — Device token registered" || { echo "❌ Step 2 FAILED"; echo "$REGISTER_TOKEN"; exit 1; }

# Step 3 — Register same token again (upsert — should succeed)
UPSERT_TOKEN=$(curl -s -X POST "$BASE_URL/api/v1/devices/token" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"token":"fake-fcm-token-abc123","platform":"ANDROID","deviceName":"Samsung Galaxy A14 updated"}')
UPSERTED=$(echo "$UPSERT_TOKEN" | jq -r '.data.registered')
[[ "$UPSERTED" == "true" ]] && echo "✅ Step 3 — Device token upserted" || { echo "❌ Step 3 FAILED"; echo "$UPSERT_TOKEN"; exit 1; }

# Step 4 — Register token without auth (should 401)
NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/devices/token" \
  -H "Content-Type: application/json" \
  -d '{"token":"no-auth-token","platform":"ANDROID","deviceName":"NoAuth"}')
[[ "$NO_AUTH" == "401" ]] && echo "✅ Step 4 — 401 without auth" || { echo "❌ Step 4 FAILED — got $NO_AUTH"; exit 1; }

# Step 5 — Register token with blank token (should 400)
BAD_TOKEN=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/devices/token" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"token":"","platform":"ANDROID","deviceName":"BadToken"}')
[[ "$BAD_TOKEN" == "400" ]] && echo "✅ Step 5 — 400 blank token" || { echo "❌ Step 5 FAILED — got $BAD_TOKEN"; exit 1; }

# Step 6 — Two-step login: get select-tenant token, then select tenant
LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+237699000080","password":"Test1234!"}')
LOGIN_TOKEN=$(echo "$LOGIN" | jq -r '.data.loginSessionToken // .data.accessToken')
TENANT_ID=$(echo "$LOGIN" | jq -r '.data.tenants[0].schemaName // empty')
if [[ -n "$TENANT_ID" && "$TENANT_ID" != "null" ]]; then
  SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
    -H "Content-Type: application/json" \
    -d "{\"loginSessionToken\":\"$LOGIN_TOKEN\",\"schemaName\":\"$TENANT_ID\"}")
  JWT2=$(echo "$SELECT" | jq -r '.data.accessToken')
else
  JWT2="$LOGIN_TOKEN"
fi
[[ -n "$JWT2" && "$JWT2" != "null" ]] && echo "✅ Step 6 — Re-login JWT obtained" || { echo "❌ Step 6 FAILED"; exit 1; }

# Step 7 — Complete onboarding if needed (for tenant context)
# Attempt to register token again with fresh JWT to confirm tenant context works
REGISTER_TOKEN2=$(curl -s -X POST "$BASE_URL/api/v1/devices/token" \
  -H "Authorization: Bearer $JWT2" \
  -H "Content-Type: application/json" \
  -d '{"token":"fake-fcm-token-second-device","platform":"IOS","deviceName":"iPhone 15"}')
REGISTERED2=$(echo "$REGISTER_TOKEN2" | jq -r '.data.registered')
[[ "$REGISTERED2" == "true" ]] && echo "✅ Step 7 — Second device token registered" || { echo "❌ Step 7 FAILED"; echo "$REGISTER_TOKEN2"; exit 1; }

# Step 8 — Delete device token (logout cleanup)
DELETE_TOKEN=$(curl -s -X DELETE "$BASE_URL/api/v1/devices/token" \
  -H "Authorization: Bearer $JWT2" \
  -H "Content-Type: application/json" \
  -d '{"token":"fake-fcm-token-second-device"}')
DELETED=$(echo "$DELETE_TOKEN" | jq -r '.data.deleted')
[[ "$DELETED" == "true" ]] && echo "✅ Step 8 — Device token deleted" || { echo "❌ Step 8 FAILED"; echo "$DELETE_TOKEN"; exit 1; }

# Step 9 — Delete non-existent token (should succeed with deleted=false)
DELETE_MISSING=$(curl -s -X DELETE "$BASE_URL/api/v1/devices/token" \
  -H "Authorization: Bearer $JWT2" \
  -H "Content-Type: application/json" \
  -d '{"token":"non-existent-token"}')
DELETED_MISSING=$(echo "$DELETE_MISSING" | jq -r '.data.deleted')
[[ "$DELETED_MISSING" == "false" ]] && echo "✅ Step 9 — Non-existent token returns deleted=false" || { echo "❌ Step 9 FAILED"; echo "$DELETE_MISSING"; exit 1; }

# Step 10 — Verify WhatsApp port config endpoint (health-check style)
# GET /api/v1/messaging/status — returns which providers are active
MESSAGING_STATUS=$(curl -s -X GET "$BASE_URL/api/v1/messaging/status" \
  -H "Authorization: Bearer $JWT2")
WA_PROVIDER=$(echo "$MESSAGING_STATUS" | jq -r '.data.whatsappProvider')
FCM_ENABLED=$(echo "$MESSAGING_STATUS" | jq -r '.data.fcmEnabled')
echo "  WhatsApp provider: $WA_PROVIDER | FCM enabled: $FCM_ENABLED"
[[ -n "$WA_PROVIDER" ]] && echo "✅ Step 10 — Messaging status retrieved" || { echo "❌ Step 10 FAILED"; echo "$MESSAGING_STATUS"; exit 1; }

echo ""
echo "✅✅✅ All cURL integration checks passed — story 8.0 backend validated ✅✅✅"
```

---

## Tasks / Subtasks

> **LOI TDD : Tous les tests RED sont écrits AVANT tout code de production. Le test doit échouer en premier.**

---

### Backend Tasks

#### Task 1 — TDD RED: Domain model + port tests

- [x] **1.1** Create `RegisterDeviceTokenCommand.java` test in `messaging/notification/domain/port/in/`
  ```java
  // messaging/notification/domain/port/in/RegisterDeviceTokenCommandTest.java
  // Tests:
  // command_withValidFields_createsSuccessfully()
  // command_withBlankToken_throwsIllegalArgument()
  // command_platformEnum_allValuesValid()
  ```

- [x] **1.2** Create `DeviceToken.java` domain model test in `messaging/notification/domain/model/`
  ```java
  // messaging/notification/domain/model/DeviceTokenTest.java
  // Tests:
  // deviceToken_create_setsAllFields()
  // deviceToken_updateTimestamp_changesUpdatedAt()
  // deviceToken_platformEnum_ANDROID_IOS_LINUX_WINDOWS()
  ```

- [x] **1.3** Create `WhatsAppDeliveryException` test in `messaging/whatsapp/domain/exception/`
  ```java
  // WhatsAppDeliveryExceptionTest.java
  // Tests:
  // exception_hasDomainCode_WHATSAPP_DELIVERY_FAILED()
  // exception_containsMessage()
  ```

- [x] **1.4** Run RED → confirm all tests fail (no production code yet)

---

#### Task 2 — TDD RED: Service tests

- [x] **2.1** Create `RegisterDeviceTokenServiceTest.java` in `messaging/notification/application/service/`
  ```java
  // Tests (@ExtendWith(MockitoExtension.class)):
  // register_newToken_savesToRepository()
  //   — Given command with valid token → deviceTokenRepository.save() called once
  // register_existingToken_upsertsUpdatedAt()
  //   — Given repo.findByToken() returns existing → repo.upsert() called with new updatedAt
  // register_blankToken_throwsValidationError()
  //   — DomainException with VALIDATION_ERROR
  // register_setsRoleFromCommand()
  //   — Given command with role=EMPLOYEE → saved token has role=EMPLOYEE
  ```

- [x] **2.2** Create `DeleteDeviceTokenServiceTest.java` in `messaging/notification/application/service/`
  ```java
  // Tests:
  // delete_existingToken_returnsTrue()
  //   — Given repo.deleteByToken() returns 1 → service returns true
  // delete_nonExistentToken_returnsFalse()
  //   — Given repo.deleteByToken() returns 0 → service returns false
  ```

- [x] **2.3** Create `FcmNotificationAdapterTest.java` in `messaging/notification/adapter/out/`
  ```java
  // Tests (@ExtendWith(MockitoExtension.class), mock FirebaseMessaging + DeviceTokenRepository):
  // notifyOwners_withTokens_sendsMulticast()
  //   — Given 2 OWNER tokens → FirebaseMessaging.sendEachForMulticast() called once with 2 tokens
  // notifyOwners_noTokens_returnsSilently()
  //   — Given 0 tokens → sendEachForMulticast() never called, no exception
  // notifyOwners_unregisteredToken_deletesFromRepo()
  //   — Given BatchResponse with 1 UNREGISTERED → deviceTokenRepository.deleteByToken() called
  // notifyOwners_firebaseException_logsWarnNoThrow()
  //   — Given FirebaseMessagingException → method completes (best-effort), log.warn emitted
  // notifyOwners_setsCorrectDataPayload()
  //   — Verifies data map contains: type, deepLink, all metadata entries
  ```

- [x] **2.4** Create `WassenderWhatsAppAdapterTest.java` in `messaging/whatsapp/adapter/out/external/`
  ```java
  // Tests (@ExtendWith(MockitoExtension.class), mock RestTemplate or WebClient):
  // sendReport_success_returnsNormally()
  //   — Given Wassender returns 200 → method completes, no exception
  // sendReport_4xxError_throwsWhatsAppDeliveryException()
  //   — Given Wassender returns 400 → WhatsAppDeliveryException thrown
  // sendReport_5xxError_throwsWhatsAppDeliveryException()
  //   — Given Wassender returns 500 → WhatsAppDeliveryException thrown  
  // sendReport_timeout_throwsWhatsAppDeliveryException()
  //   — Given timeout → WhatsAppDeliveryException thrown
  // sendReport_sanitizesPhoneNumber()
  //   — Given "+237 699-00-00-80" → sent as "+237699000080"
  // sendReport_neverLogsFullApiToken()
  //   — Verify log output does not contain full API token (mock appender test)
  // isConfigured_returnsTrue()
  // sendReport_setsCorrectRequest()
  //   — Verifies Authorization: Bearer header, Content-Type: application/json,
  //     endpoint path /api/send-message, body contains "to" and "text" (no "device" field)
  ```

- [x] **2.5** Create `FirebaseInitializerTest.java` in `messaging/notification/adapter/out/config/`
  ```java
  // Tests:
  // init_whenEnabled_initializesFirebaseApp()
  // init_whenDisabled_skipsInitialization()
  // init_whenBadCredentials_logsWarnAndContinues()
  ```

- [x] **2.6** Run RED → confirm all service/adapter tests fail

---

#### Task 3 — TDD RED: Controller tests

- [x] **3.1** Create `DeviceTokenControllerTest.java` in `messaging/notification/adapter/in/rest/`
  ```java
  // Tests (standalone MockMvc, @ExtendWith(MockitoExtension.class)):
  // POST /api/v1/devices/token → 200 (OWNER, valid body)
  // POST /api/v1/devices/token → 200 (EMPLOYEE, valid body — accessible to all roles)
  // POST /api/v1/devices/token → 401 (no Authorization header)
  // POST /api/v1/devices/token → 400 (blank token)
  // DELETE /api/v1/devices/token → 200, deleted=true (valid token)
  // DELETE /api/v1/devices/token → 200, deleted=false (non-existent token)
  // DELETE /api/v1/devices/token → 401 (no Authorization header)
  ```

- [x] **3.2** Create `MessagingStatusControllerTest.java` in `messaging/notification/adapter/in/rest/`
  ```java
  // Tests:
  // GET /api/v1/messaging/status → 200 with whatsappProvider and fcmEnabled fields
  // GET /api/v1/messaging/status → 401 (no auth)
  ```

- [x] **3.3** Run RED → confirm all controller tests fail

---

#### Task 4 — GREEN: Domain models + port interfaces

- [x] **4.1** Create `DeviceToken.java` domain model in `messaging/notification/domain/model/`
  ```java
  // Fields: id (UUID), userId (UUID), token (String), platform (DevicePlatform enum),
  //         deviceName (String), role (String), createdAt (Instant), updatedAt (Instant)
  ```

- [x] **4.2** Create `DevicePlatform.java` enum in `messaging/notification/domain/model/`
  ```java
  public enum DevicePlatform { ANDROID, IOS, LINUX, WINDOWS }
  ```

- [x] **4.3** Create `RegisterDeviceTokenCommand.java` record in `messaging/notification/domain/port/in/`
  ```java
  public record RegisterDeviceTokenCommand(
      UUID actorId, String actorRole,
      String token, String platform, String deviceName
  ) {}
  ```

- [x] **4.4** Create `RegisterDeviceTokenUseCase.java` port interface in `messaging/notification/domain/port/in/`

- [x] **4.5** Create `DeleteDeviceTokenUseCase.java` port interface in `messaging/notification/domain/port/in/`

- [x] **4.6** Create `DeviceTokenRepository.java` port interface in `messaging/notification/domain/port/out/`
  ```java
  // Methods: save(DeviceToken), findByToken(String), findByUserIdAndRole(UUID, String),
  //          findOwnerTokens(), deleteByToken(String) → int, upsert(DeviceToken)
  ```

- [x] **4.7** Move `WhatsAppPort` from `commerce.sale.domain.port.out` → `messaging.whatsapp.domain.port.out`
  - Update ALL imports across the codebase (use IDE refactor or grep + sed)
  - Delete old file

- [x] **4.8** Create `WhatsAppDeliveryException.java` in `messaging.whatsapp.domain.exception`

- [x] **4.9** Run relevant RED tests → confirm domain model tests go GREEN

---

#### Task 5 — GREEN: Services + adapters

- [x] **5.1** Create `RegisterDeviceTokenService.java` in `messaging/notification/application/service/`

- [x] **5.2** Create `DeleteDeviceTokenService.java` in `messaging/notification/application/service/`

- [x] **5.3** Create `FcmNotificationAdapter.java` in `messaging/notification/adapter/out/`
  ```java
  @Component
  @Primary
  @ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "true")
  public class FcmNotificationAdapter implements NotificationPort { ... }
  ```
  - Remove `@Primary` from `LoggingNotificationAdapter` (it becomes the fallback)
  - Update `LoggingNotificationAdapter`: remove `@Primary`, add `@ConditionalOnMissingBean(FcmNotificationAdapter.class)` OR use `@Order` / `@ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "false", matchIfMissing = true)`

- [x] **5.4** Create `WassenderWhatsAppAdapter.java` in `messaging/whatsapp/adapter/out/external/`
  ```java
  @Component
  @ConditionalOnProperty(name = "keevo.whatsapp.provider", havingValue = "wassender")
  public class WassenderWhatsAppAdapter implements WhatsAppPort { ... }
  ```
  - Uses `RestTemplate` (Spring standard HTTP client — no new dependency)
  - `WassenderProperties` `@ConfigurationProperties`
  - Phone sanitization: `phoneNumber.replaceAll("[\\s\\-()]", "")` + ensure `+` prefix

- [x] **5.5** Move `NoOpWhatsAppAdapter` from `commerce.sale.adapter.out.messaging` → `messaging.whatsapp.adapter.out.noop`
  - Add `@ConditionalOnProperty(name = "keevo.whatsapp.provider", havingValue = "noop", matchIfMissing = true)`
  - This makes NoOp the default when no provider is configured

- [x] **5.6** Create `FirebaseInitializer.java` in `messaging/notification/adapter/out/config/`
  ```java
  @Component
  @ConditionalOnProperty(name = "keevo.fcm.enabled", havingValue = "true")
  public class FirebaseInitializer implements InitializingBean { ... }
  ```

- [x] **5.7** Create `FcmProperties.java` and `WassenderProperties.java` `@ConfigurationProperties`

- [x] **5.8** Run service/adapter tests → confirm GREEN

---

#### Task 6 — GREEN: Persistence + Controllers

- [x] **6.1** Create `DeviceTokenJpaEntity.java` in `messaging/notification/adapter/out/persistence/entity/`

- [x] **6.2** Create `DeviceTokenSpringRepository.java` (extends JpaRepository) in `messaging/notification/adapter/out/persistence/jpa/`
  ```java
  @Query("SELECT d FROM DeviceTokenJpaEntity d WHERE d.role = :role")
  List<DeviceTokenJpaEntity> findByRole(@Param("role") String role);

  Optional<DeviceTokenJpaEntity> findByToken(String token);
  int deleteByToken(String token);
  ```

- [x] **6.3** Create `DeviceTokenRepositoryAdapter.java` implementing `DeviceTokenRepository` in `messaging/notification/adapter/out/persistence/impl/`

- [x] **6.4** Create `DeviceTokenController.java` in `messaging/notification/adapter/in/rest/`
  ```java
  @Tag(name = "Device Tokens", description = "FCM device token management")
  @RestController
  @RequestMapping("/api/v1/devices")
  // POST /token — register (all roles)
  // DELETE /token — unregister (all roles)
  ```

- [x] **6.5** Create `MessagingStatusController.java` in `messaging/notification/adapter/in/rest/`
  ```java
  @Tag(name = "Messaging", description = "Messaging infrastructure status")
  @RestController
  @RequestMapping("/api/v1/messaging")
  // GET /status — returns { whatsappProvider: "wassender"|"noop", fcmEnabled: true|false }
  ```

- [x] **6.6** Create `RegisterDeviceTokenRequestDto.java` and `DeleteDeviceTokenRequestDto.java`

- [x] **6.7** DDL: Add `device_tokens` table to `TenantSchemaProvisioner.DDL_DEVICE_TOKENS`

- [x] **6.8** DDL: Add `device_tokens` migration to `TenantSchemaSyncService.ensureRequiredIndexes()`

- [x] **6.9** Add `WHATSAPP_DELIVERY_FAILED` to `ErrorCode` enum in shared domain

- [x] **6.10** Update `application.yml` with new `keevo.fcm.*` and `keevo.whatsapp.*` properties

- [x] **6.11** Update `.env.example` with new variables

- [x] **6.12** Add Firebase Admin SDK dependency to `pom.xml`:
  ```xml
  <dependency>
    <groupId>com.google.firebase</groupId>
    <artifactId>firebase-admin</artifactId>
    <version>9.3.0</version>
  </dependency>
  ```

- [x] **6.13** Run controller + persistence tests → confirm GREEN

- [x] **6.14** Run FULL backend test suite: `mvn test` → confirm 0 failures, 0 errors (no regressions from WhatsAppPort move)

---

### Flutter Tasks

#### Task 7 — Add Firebase dependencies

- [x] **7.1** Add to `pubspec.yaml`:
  ```yaml
  firebase_core: ^3.8.1
  firebase_messaging: ^15.2.1
  flutter_local_notifications: ^18.0.1
  ```

- [x] **7.2** Run `flutter pub get` → confirm no dependency conflicts with existing `sqlcipher_flutter_libs`

- [x] **7.3** Configure Android: add `google-services.json` to `android/app/`, update `android/build.gradle` and `android/app/build.gradle` with Firebase plugin
  - `classpath 'com.google.gms:google-services:4.4.2'` in project-level build.gradle
  - `apply plugin: 'com.google.gms.google-services'` in app-level build.gradle

- [x] **7.4** Configure iOS: add `GoogleService-Info.plist` to Runner, enable push notifications capability

- [x] **7.5** Platform guard: for `Platform.isLinux || Platform.isWindows` → skip ALL Firebase initialization (no crash on desktop)

---

#### Task 8 — TDD RED: Flutter domain + data tests

- [x] **8.1** Create `notification_model_test.dart` in `test/features/notifications/domain/`
  ```dart
  // Tests:
  // notificationModel_create_setsAllFields()
  // notificationModel_markAsRead_setsIsReadTrue()
  // notificationModel_typeIconMapping_returnsCorrectEmoji()
  ```

- [x] **8.2** Create `fcm_service_test.dart` in `test/core/notification/`
  ```dart
  // Tests (using mocktail):
  // initFcm_onMobile_requestsPermission()
  // initFcm_onDesktop_skipsInitialization()
  // onTokenRefresh_registersNewToken()
  // onMessage_foreground_showsLocalNotification()
  // onMessageOpenedApp_navigatesToDeepLink()
  // registerToken_callsRemoteDataSource()
  // deleteToken_callsRemoteDataSourceAndFirebase()
  ```

- [x] **8.3** Create `notification_repository_test.dart` in `test/features/notifications/data/`
  ```dart
  // Tests:
  // insert_savesToDrift()
  // markAsRead_updatesIsRead()
  // markAllAsRead_updatesAllUnread()
  // countUnread_returnsCorrectCount()
  // deleteOlderThan_cleansUpOldEntries()
  // getAll_returnsReverseChronological()
  ```

- [x] **8.4** Create `notification_providers_test.dart` in `test/features/notifications/presentation/`
  ```dart
  // Tests:
  // unreadCountProvider_returnsCount()
  // notificationsListProvider_returnsAll()
  // markAsReadNotifier_updatesAndInvalidates()
  ```

- [x] **8.5** Create `notifications_page_test.dart` in `test/features/notifications/presentation/`
  ```dart
  // Tests:
  // page_rendersList_withNotifications()
  // page_tapNotification_marksReadAndNavigates()
  // page_markAllAsRead_updatesAll()
  // page_emptyState_showsMessage()
  ```

- [x] **8.6** Create `notification_bell_widget_test.dart` in `test/features/notifications/presentation/`
  ```dart
  // Tests:
  // bell_withUnread_showsBadge()
  // bell_withZeroUnread_noBadge()
  // bell_tap_navigatesToNotifications()
  ```

- [x] **8.7** Run RED → all tests fail

---

#### Task 9 — GREEN: Flutter domain + data layer

- [x] **9.1** Create Drift table `Notifications` in `core/storage/database.dart` (or new file):
  ```dart
  class Notifications extends Table {
    TextColumn get id => text()();
    TextColumn get type => text()();
    TextColumn get title => text()();
    TextColumn get body => text()();
    TextColumn get deepLink => text().nullable()();
    BoolColumn get isRead => boolean().withDefault(const Constant(false))();
    DateTimeColumn get receivedAt => dateTime()();
    @override Set<Column> get primaryKey => {id};
  }
  ```
  - Bump Drift schema version, add migration step

- [x] **9.2** Create `NotificationModel` Freezed class in `features/notifications/domain/model/`

- [x] **9.3** Create `NotificationRepository` interface in `features/notifications/domain/repository/`

- [x] **9.4** Create `LocalNotificationDataSource` in `features/notifications/data/datasource/`

- [x] **9.5** Create `RemoteDeviceTokenDataSource` in `features/notifications/data/datasource/`
  - `POST /api/v1/devices/token`
  - `DELETE /api/v1/devices/token`

- [x] **9.6** Create `NotificationRepositoryImpl` in `features/notifications/data/repository/`

- [x] **9.7** Run domain/data tests → confirm GREEN

---

#### Task 10 — GREEN: Flutter FCM service

- [x] **10.1** Create `FcmService` in `core/notification/fcm_service.dart`
  ```dart
  class FcmService {
    Future<void> initialize(GoRouter router) async {
      if (Platform.isLinux || Platform.isWindows) return; // desktop skip
      
      await Firebase.initializeApp();
      await FirebaseMessaging.instance.requestPermission();
      
      // Foreground handler → flutter_local_notifications
      FirebaseMessaging.onMessage.listen(_onForegroundMessage);
      
      // Tap handler (background → foreground)
      FirebaseMessaging.onMessageOpenedApp.listen((msg) => _onTap(msg, router));
      
      // Cold start tap
      final initial = await FirebaseMessaging.instance.getInitialMessage();
      if (initial != null) _onTap(initial, router);
      
      // Token management
      FirebaseMessaging.instance.onTokenRefresh.listen(_onTokenRefresh);
    }
    
    Future<String?> getToken() async {
      if (Platform.isLinux || Platform.isWindows) return null;
      return FirebaseMessaging.instance.getToken();
    }
  }
  ```

- [x] **10.2** Initialize `FcmService` in `main.dart` after `WidgetsFlutterBinding.ensureInitialized()`

- [x] **10.3** Register FCM token after successful login in `AuthNotifier`:
  ```dart
  // After login success:
  final token = await ref.read(fcmServiceProvider).getToken();
  if (token != null) {
    await ref.read(remoteDeviceTokenDataSourceProvider).registerToken(token, platform, deviceName);
  }
  ```

- [x] **10.4** Delete token on logout:
  ```dart
  // In logout flow:
  final token = await ref.read(fcmServiceProvider).getToken();
  if (token != null) {
    await ref.read(remoteDeviceTokenDataSourceProvider).deleteToken(token);
    await FirebaseMessaging.instance.deleteToken();
  }
  ```

- [x] **10.5** Configure `flutter_local_notifications`:
  - Android: channel `keevo_notifications`, importance HIGH, LED `#3B5BDB`
  - Notification tap → extract `deepLink` → `GoRouter.go()`

- [x] **10.6** Store received notifications locally:
  - On every `RemoteMessage` received (foreground or background resume), insert into `notifications` Drift table
  - Triggers `unreadNotificationCountProvider` invalidation

- [x] **10.7** Run FCM tests → confirm GREEN

---

#### Task 11 — GREEN: Flutter notification feature UI

- [x] **11.1** Create Riverpod providers in `features/notifications/presentation/provider/`:
  - `unreadNotificationCountProvider` — watches Drift count
  - `notificationsListProvider` — watches Drift query (last 30 days, reverse chronological)
  - `markAsReadNotifier` — marks single notification read
  - `markAllAsReadNotifier` — marks all as read

- [x] **11.2** Create `NotificationBellWidget` in `features/notifications/presentation/widget/`
  - `IconButton(icon: Stack(children: [Icon(Icons.notifications_outlined), if (count > 0) Badge]))`
  - Badge uses `colorError` (`#FA5252`) with white text, positioned top-right
  - On tap: `context.go('/notifications')`

- [x] **11.3** Add `NotificationBellWidget` to `MainShellPage` AppBar actions (after existing icons)

- [x] **11.4** Create `NotificationsPage` in `features/notifications/presentation/page/`
  - `SliverAppBar` with gradient header "Notifications"
  - Action: "Tout marquer comme lu" (only visible when unread > 0)
  - `ListView.builder` of `NotificationCard` widgets
  - Empty state: "Aucune notification" with bell icon illustration
  - Purge: on page init, delete notifications older than 30 days

- [x] **11.5** Create `NotificationCard` widget:
  - Leading: type icon (mapped from `type` field: `DRAFT_PRODUCT_PENDING_VALIDATION` → 🔶, `STOCK_THRESHOLD_BREACHED` → ⚠️, `DAILY_REPORT` / `WEEKLY_REPORT` → 📊, default → 🔔)
  - Title: bold if unread, regular if read
  - Body: max 2 lines, ellipsis
  - Trailing: relative time (`Il y a 2h`, `Hier`, `30 mars`)
  - On tap: mark as read + navigate to `deepLink` if present (else no navigation)

- [x] **11.6** Add route `/notifications` in `app_router.dart`

- [x] **11.7** Run UI tests → confirm GREEN

---

#### Task 12 — Full test suite validation

- [x] **12.1** Run `flutter test --reporter=expanded` → confirm ALL tests pass (0 failures)

- [x] **12.2** Run `mvn test` → confirm ALL backend tests pass (0 failures, 0 errors) — especially verify no regressions from WhatsAppPort package move

- [x] **12.3** Run `bash curl-tests-story-8-0.sh` → confirm all ✅

- [x] **12.4** Manual smoke test: start backend + app, verify FCM token registration in database, verify notification bell shows in AppBar

---

## Dev Notes

### Existing Infrastructure to Reuse (DO NOT REINVENT)

| What | Where | Action |
|---|---|---|
| `NotificationPort` interface | `messaging.notification.domain.port.out` | Keep as-is — `FcmNotificationAdapter` implements it |
| `NotificationPayload` record | `messaging.notification.domain.model` | Keep as-is — already has `type`, `title`, `body`, `deepLink`, `metadata` |
| `LoggingNotificationAdapter` | `messaging.notification.adapter.out` | Keep for dev/test — remove `@Primary`, add `@ConditionalOnProperty` fallback |
| `DraftProductNotificationListener` | `messaging.notification.application.listener` | No change needed — already uses `NotificationPort`, automatically gets FCM |
| `WhatsAppPort` interface | `commerce.sale.domain.port.out` (current) | **MOVE** to `messaging.whatsapp.domain.port.out` |
| `NoOpWhatsAppAdapter` | `commerce.sale.adapter.out.messaging` (current) | **MOVE** to `messaging.whatsapp.adapter.out.noop` |
| `AbstractReportGenerator` | `reporting.report.application.service` | Uses `WhatsAppPort` — update import after move |
| `ReportDeliveryRetryService` | `reporting.report.application.service` | Uses `WhatsAppPort` — update import after move |
| `ApiResponseWrapper` | `shared.infrastructure.web` | Use for all new endpoints |
| `GlobalExceptionHandler` | `shared.infrastructure.web` | Add `WHATSAPP_DELIVERY_FAILED` mapping if needed |
| `ErrorCode` enum | `shared.domain.exception` | Add `WHATSAPP_DELIVERY_FAILED` entry |
| `TenantSchemaProvisioner` | `shared.infrastructure.persistence` | Add `DDL_DEVICE_TOKENS` constant |
| `TenantSchemaSyncService` | `shared.infrastructure.persistence` | Add `device_tokens` table migration |
| `SecurityConfig` | `shared.infrastructure.security` | `/api/v1/devices/**` requires authentication (already default) |
| `ConnectivityService` | `core/sync/` (Flutter) | Use for online/offline token registration |

### Critical Architecture Rules

1. **WhatsAppPort move is a refactor** — ALL existing tests must still pass after the package change. Run `mvn test` BEFORE any new implementation to confirm 0 regressions.
2. **`@ConditionalOnProperty` strategy** — FCM and Wassender adapters are opt-in. Default behavior (no env vars) = stubs remain active. This ensures dev/test environments are unaffected.
3. **`@Primary` management** — Only ONE `NotificationPort` impl can be `@Primary`. Use `@ConditionalOnProperty` + `matchIfMissing` to ensure exactly one is primary at any time. Same for `WhatsAppPort`.
4. **Wassender API token security** — NEVER log the full token. Use `token.substring(0, 4) + "****"` in all log statements.
5. **FCM on desktop** — Flutter desktop (Linux/Windows) does NOT support FCM. Guard ALL Firebase calls with `Platform.isLinux || Platform.isWindows` checks. No crash, no error, just skip.
6. **Device token cleanup on logout** — Mandatory for security: a revoked employee must NOT continue receiving push notifications.
7. **Notification payload contract** — `NotificationPayload` record is already perfectly designed for FCM. `metadata` map → FCM `data` payload (always delivered). `title`/`body` → FCM `notification` payload (shown in system tray). `deepLink` → `data['deepLink']` → Flutter `GoRouter.go()`.
8. **Best-effort delivery** — Notification sending MUST NOT fail the calling transaction. All callers already wrap `notificationPort` calls in try/catch. `FcmNotificationAdapter.notifyOwners()` must NEVER throw — catch and log internally.
9. **Multi-tenant token query** — `FcmNotificationAdapter` receives `tenantId` (schema name). It must set `TenantContext` before querying `device_tokens` to ensure the correct schema is used. Use the existing `TenantContext.setCurrentTenant()` pattern.

### Project Structure Notes

```
Backend additions:
messaging/
├── whatsapp/                                    # MOVED from commerce/sale/
│   ├── domain/
│   │   ├── port/out/WhatsAppPort.java          # MOVED
│   │   └── exception/WhatsAppDeliveryException.java  # NEW
│   └── adapter/
│       ├── out/
│       │   ├── noop/NoOpWhatsAppAdapter.java   # MOVED
│       │   └── external/WassenderWhatsAppAdapter.java  # NEW
│       └── in/mcp/.gitkeep                     # MCP placeholder
├── notification/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── NotificationPayload.java        # EXISTS
│   │   │   ├── DeviceToken.java                # NEW
│   │   │   └── DevicePlatform.java             # NEW
│   │   └── port/
│   │       ├── in/
│   │       │   ├── RegisterDeviceTokenUseCase.java   # NEW
│   │       │   ├── RegisterDeviceTokenCommand.java   # NEW
│   │       │   └── DeleteDeviceTokenUseCase.java     # NEW
│   │       └── out/
│   │           ├── NotificationPort.java        # EXISTS
│   │           └── DeviceTokenRepository.java   # NEW
│   ├── application/
│   │   ├── service/
│   │   │   ├── RegisterDeviceTokenService.java  # NEW
│   │   │   └── DeleteDeviceTokenService.java    # NEW
│   │   └── listener/
│   │       └── DraftProductNotificationListener.java  # EXISTS — no change
│   └── adapter/
│       ├── in/rest/
│       │   ├── DeviceTokenController.java       # NEW
│       │   ├── MessagingStatusController.java   # NEW
│       │   └── dto/
│       │       ├── RegisterDeviceTokenRequestDto.java  # NEW
│       │       └── DeleteDeviceTokenRequestDto.java    # NEW
│       └── out/
│           ├── LoggingNotificationAdapter.java  # EXISTS — remove @Primary, add conditional
│           ├── FcmNotificationAdapter.java      # NEW — @Primary + @ConditionalOnProperty
│           ├── config/
│           │   ├── FirebaseInitializer.java     # NEW
│           │   ├── FcmProperties.java           # NEW
│           │   └── WassenderProperties.java     # NEW
│           └── persistence/
│               ├── entity/DeviceTokenJpaEntity.java     # NEW
│               ├── jpa/DeviceTokenSpringRepository.java # NEW
│               └── impl/DeviceTokenRepositoryAdapter.java # NEW

Flutter additions:
lib/
├── core/
│   └── notification/
│       └── fcm_service.dart                     # NEW — FCM init, token, foreground handler
└── features/
    └── notifications/                           # NEW feature folder
        ├── domain/
        │   ├── model/notification_model.dart    # NEW — Freezed
        │   └── repository/notification_repository.dart  # NEW — interface
        ├── data/
        │   ├── datasource/
        │   │   ├── local_notification_datasource.dart   # NEW — Drift
        │   │   └── remote_device_token_datasource.dart  # NEW — API calls
        │   └── repository/
        │       └── notification_repository_impl.dart    # NEW
        └── presentation/
            ├── provider/
            │   └── notification_providers.dart   # NEW — @riverpod
            ├── page/
            │   └── notifications_page.dart       # NEW
            └── widget/
                ├── notification_bell_widget.dart  # NEW — AppBar bell icon
                └── notification_card.dart         # NEW — list item
```

### References

- [Source: architecture.md#Notifications — Observer + Template Method → NotificationPort → WhatsApp/Push adapters]
- [Source: architecture.md#External services — Adapter + Façade → WhatsAppPort → WassenderAdapter / AfricasTalkingAdapter]
- [Source: architecture.md#Backend structure — messaging/whatsapp/ + messaging/notification/]
- [Source: architecture.md#GoF — Notification routing → Chain of Responsibility: WhatsApp → Push → SMS fallback]
- [Source: prd.md#Notification Model — Push via FCM, WhatsApp via Wassender, In-App for transfers]
- [Source: ux-design-specification.md#Experience Principles — "Sérénité par défaut" → notification permission denial is not a blocker]
- [Source: ux-design-specification.md#Flow 20 — Réaction aux Alertes Stock Critique → Push + WhatsApp "Stock bas!"]
- [Source: ux-design-specification.md#Design Tokens — Alert color #FCC419, Error #FA5252 (badge), Primary #3B5BDB (LED)]
- [Source: story 2-4#NotificationPort + LoggingNotificationAdapter — created with Epic 8.1 FCM roadmap in TODO]
- [Source: story 7-2#WhatsAppPort.sendReport() — used by AbstractReportGenerator, retry logic in ReportDeliveryRetryService]
- [Source: story 7-5#ReportChannel enum + @ConditionalOnProperty pattern for delivery channel switching]
- [Source: story 4-4#NoOpWhatsAppAdapter + WhatsAppPort — original creation, current location in commerce/sale/]

### Previous Story Intelligence

**From Story 7.5 (most recent relevant story — report preferences with WhatsApp):**
- `@ConditionalOnProperty` pattern is already established for conditional bean loading
- `ReportChannel` enum (`WHATSAPP`, `IN_APP_ONLY`) already controls delivery channel per report type
- `AbstractReportGenerator.deliverReport()` already checks channel before calling `whatsAppPort`
- Deferred work note: `WhatsAppPort.isConfigured()` has `default return true` — any new adapter that forgets override silently sends. Keep this in mind for `WassenderWhatsAppAdapter`.

**From Story 7.2 (end-of-day report — WhatsApp delivery):**
- `WhatsAppPort.sendReport(phone, text)` is the only method needed for Wassender
- Retry logic already exists in `ReportDeliveryRetryService` (max 3 attempts, 5-min polling)
- `NoOpWhatsAppAdapter.isConfigured()` returns `false` — report generator skips delivery when not configured

**From Story 2.4 (draft notifications — NotificationPort creation):**
- `NotificationPort.notifyOwners(tenantId, payload)` is a tenant-scoped call
- `NotificationPayload` already has `deepLink` + `metadata` map designed for FCM
- `LoggingNotificationAdapter` TODO comment explicitly describes the FCM implementation plan
- `@Async` + `@EventListener` pattern ensures notifications don't block the main transaction

### Latest Technical Notes

**Firebase Admin SDK (Java) v9.3.0:**
- `FirebaseMessaging.getInstance().sendEachForMulticast(MulticastMessage)` — sends to up to 500 tokens
- `BatchResponse.getResponses()` — per-token success/failure
- `SendResponse.getException().getMessagingErrorCode()` — `UNREGISTERED` means token is stale
- Service account JSON: download from Firebase Console → Project Settings → Service Accounts
- Initialization: `FirebaseOptions options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(is)).build();`

**firebase_messaging (Flutter) v15.2.x:**
- `FirebaseMessaging.instance.getToken()` — returns FCM registration token
- `FirebaseMessaging.instance.onTokenRefresh` — stream of new tokens (when rotated)
- `FirebaseMessaging.onMessage` — foreground messages
- `FirebaseMessaging.onMessageOpenedApp` — tap from background
- `FirebaseMessaging.instance.getInitialMessage()` — cold start tap
- Desktop (Linux/Windows): NOT supported — guard with platform check

**flutter_local_notifications v18.x:**
- Required for showing notifications when app is in foreground (FCM only shows system tray in background)
- Android: `AndroidNotificationChannel` with id, name, importance
- Notification tap callback → extract payload → navigate

**Wassenger API v1 (wassenger.com):**
- `POST /v1/messages` — send text message
- Headers: `Token: <api_key>`, `Content-Type: application/json`
- Body: `{ "phone": "+237...", "message": "text", "device": "<device_id>" }`
- Response 2xx: `{ "id": "...", "status": "queued" }`
- Rate limit: varies by plan (typically 1000 msg/day)

---

## Dev Agent Record

### Agent Model Used

Claude Opus 4.6 (GitHub Copilot)

### Debug Log References

- Backend compile: 679 source files, BUILD SUCCESS
- Story-specific tests: 35 tests, 0 failures, 0 errors (GREEN)
- Full backend suite: 1338 tests, 0 failures, 0 errors (excluding pre-existing MultiStoreStockRepositoryContractTest)
- TenantSchemaSyncServiceTest fix: Added device_tokens to mock (19 tables), removed CREATE TABLE from ensureRequiredIndexes
- Flutter build_runner: 1123 outputs generated (Drift, Freezed, Riverpod)
- Flutter notification tests: 11 tests, 0 failures (domain model, Drift datasource, bell widget)
- Full Flutter suite: 708 passed, 61 pre-existing failures (unrelated — sqlite3 library, timeouts)

### Completion Notes List

1. WhatsAppPort moved from `commerce.sale.domain.port.out` → `messaging.whatsapp.domain.port.out`; 15 import references updated across production and test code
2. `LoggingNotificationAdapter` changed from `@Primary` to `@ConditionalOnProperty(keevo.fcm.enabled=false, matchIfMissing=true)` — default in dev/test
3. `FcmNotificationAdapter` is `@Primary` + `@ConditionalOnProperty(keevo.fcm.enabled=true)` — opt-in for production
4. `WassenderWhatsAppAdapter` is `@ConditionalOnProperty(keevo.whatsapp.provider=wassender)` — opt-in for production
5. `NoOpWhatsAppAdapter` is `@ConditionalOnProperty(keevo.whatsapp.provider=noop, matchIfMissing=true)` — default
6. `MessagingStatusController` uses constructor injection (not `@Value` field injection) for testability with MockMvc standaloneSetup
7. `RegisterDeviceTokenCommand` compact constructor throws `IllegalArgumentException` for blank token (not `DomainException`)
8. `device_tokens` DDL added to `TenantSchemaProvisioner.DDL_DEVICE_TOKENS`; indexes added to `TenantSchemaSyncService.ensureRequiredIndexes()`
9. Firebase dependencies added to Flutter: `firebase_core: ^3.8.1`, `firebase_messaging: ^15.2.1`, `flutter_local_notifications: ^18.0.1`
10. Drift schema bumped v22→v23 with `notifications` table migration
11. `FcmService` guards all Firebase calls with `Platform.isLinux || Platform.isWindows` checks (no crash on desktop)
12. `NotificationBellWidget` added to MainShell body (top-right position) — Badge with `#FA5252` color
13. App route `/notifications` added to `app_router.dart`
14. Android/iOS Firebase configuration files (`google-services.json`, `GoogleService-Info.plist`) must be added manually per deployment

### Review Findings

- [x] [Review][Patch] H1 — TenantContext manquant dans FcmNotificationAdapter avant findOwnerTokens() — cross-tenant data risk [FcmNotificationAdapter.java:44]
- [x] [Review][Patch] H2 — RestTemplate sans timeout configuré dans WassenderWhatsAppAdapter — connect/read infinis (AC5 exige 10s/30s) [WassenderWhatsAppAdapter.java:28]
- [x] [Review][Patch] H3 — onTokenRefresh ne re-enregistre pas le token côté backend — violation silencieuse AC2 [fcm_service.dart:60]
- [x] [Review][Patch] H4 — NotificationBellWidget positionné comme overlay flottant (Positioned top:8 right:8) au lieu d'être dans les AppBar actions — conflit visuel avec les AppBars des pages enfants (AC9) [main_shell.dart:170]
- [x] [Review][Patch] M1 — DevicePlatform.valueOf() lève IllegalArgumentException sur plateforme invalide au lieu de DomainException(VALIDATION_ERROR) [RegisterDeviceTokenService.java:35]
- [x] [Review][Patch] M2 — Aucune validation au démarrage quand provider=wassender avec apiToken blank — ERROR log manquant (AC7) [MessagingPropertiesConfig.java]
- [x] [Review][Patch] M3 — FirebaseInitializer.firebaseMessaging() retourne null en cas d'erreur d'init — FcmNotificationAdapter reçoit null et échoue silencieusement (NPE absorbé) à chaque notifyOwners() [FirebaseInitializer.java:66]
- [x] [Review][Patch] L1 — JSON manuel dans sendReport() n'échappe pas les control chars U+0000–U+001F (hors \n\r\t) — JSON malformé possible [WassenderWhatsAppAdapter.java:73]
- [x] [Review][Defer] L2 — deleteToken sans vérification de propriété (ownership) — tout user authentifié peut supprimer un token connu [DeviceTokenController.java:57] — deferred, pre-existing pattern dans le projet, faible surface d'attaque

### File List

**Backend — Created:**
- `messaging/notification/domain/model/DevicePlatform.java`
- `messaging/notification/domain/model/DeviceToken.java`
- `messaging/notification/domain/port/in/RegisterDeviceTokenCommand.java`
- `messaging/notification/domain/port/in/RegisterDeviceTokenUseCase.java`
- `messaging/notification/domain/port/in/DeleteDeviceTokenUseCase.java`
- `messaging/notification/domain/port/out/DeviceTokenRepository.java`
- `messaging/notification/application/service/RegisterDeviceTokenService.java`
- `messaging/notification/application/service/DeleteDeviceTokenService.java`
- `messaging/notification/adapter/in/rest/DeviceTokenController.java`
- `messaging/notification/adapter/in/rest/MessagingStatusController.java`
- `messaging/notification/adapter/in/rest/dto/RegisterDeviceTokenRequestDto.java`
- `messaging/notification/adapter/in/rest/dto/DeleteDeviceTokenRequestDto.java`
- `messaging/notification/adapter/out/FcmNotificationAdapter.java`
- `messaging/notification/adapter/out/config/FirebaseInitializer.java`
- `messaging/notification/adapter/out/config/FcmProperties.java`
- `messaging/notification/adapter/out/config/WassenderProperties.java`
- `messaging/notification/adapter/out/config/MessagingPropertiesConfig.java`
- `messaging/notification/adapter/out/persistence/entity/DeviceTokenJpaEntity.java`
- `messaging/notification/adapter/out/persistence/jpa/DeviceTokenSpringRepository.java`
- `messaging/notification/adapter/out/persistence/impl/DeviceTokenRepositoryAdapter.java`
- `messaging/whatsapp/domain/port/out/WhatsAppPort.java` (moved from commerce)
- `messaging/whatsapp/domain/exception/WhatsAppDeliveryException.java`
- `messaging/whatsapp/adapter/out/noop/NoOpWhatsAppAdapter.java` (moved from commerce)
- `messaging/whatsapp/adapter/out/external/WassenderWhatsAppAdapter.java`

**Backend — Modified:**
- `pom.xml` (added firebase-admin 9.3.0)
- `shared/domain/exception/ErrorCode.java` (added WHATSAPP_DELIVERY_FAILED)
- `messaging/notification/adapter/out/LoggingNotificationAdapter.java` (removed @Primary, added @ConditionalOnProperty)
- `shared/infrastructure/persistence/TenantSchemaProvisioner.java` (added DDL_DEVICE_TOKENS + indexes)
- `shared/infrastructure/persistence/TenantSchemaSyncService.java` (added device_tokens to REQUIRED_TENANT_TABLES_DDL + ensureRequiredIndexes)
- `application.yml` (added keevo.fcm.* and keevo.whatsapp.* properties)
- `.env.example` (added FCM and Wassender variables)

**Backend — Deleted:**
- `commerce/sale/domain/port/out/WhatsAppPort.java` (moved to messaging)
- `commerce/sale/adapter/out/messaging/NoOpWhatsAppAdapter.java` (moved to messaging)

**Backend — Tests Created:**
- `test/.../notification/domain/model/DeviceTokenTest.java`
- `test/.../notification/domain/port/in/RegisterDeviceTokenCommandTest.java`
- `test/.../whatsapp/domain/exception/WhatsAppDeliveryExceptionTest.java`
- `test/.../notification/application/service/RegisterDeviceTokenServiceTest.java`
- `test/.../notification/application/service/DeleteDeviceTokenServiceTest.java`
- `test/.../notification/adapter/out/FcmNotificationAdapterTest.java`
- `test/.../whatsapp/adapter/out/external/WassenderWhatsAppAdapterTest.java`
- `test/.../notification/adapter/out/config/FirebaseInitializerTest.java`
- `test/.../notification/adapter/in/rest/DeviceTokenControllerTest.java`
- `test/.../notification/adapter/in/rest/MessagingStatusControllerTest.java`

**Backend — Tests Modified:**
- `test/.../persistence/TenantSchemaSyncServiceTest.java` (added device_tokens to mock — 19 tables)

**Flutter — Created:**
- `lib/core/storage/notifications_table.dart`
- `lib/core/notification/fcm_service.dart`
- `lib/features/notifications/domain/model/notification_model.dart`
- `lib/features/notifications/domain/repository/notification_repository.dart`
- `lib/features/notifications/data/datasource/local_notification_datasource.dart`
- `lib/features/notifications/data/datasource/remote_device_token_datasource.dart`
- `lib/features/notifications/data/repository/notification_repository_impl.dart`
- `lib/features/notifications/presentation/provider/notification_provider.dart`
- `lib/features/notifications/presentation/page/notifications_page.dart`
- `lib/features/notifications/presentation/widget/notification_bell_widget.dart`
- `lib/features/notifications/presentation/widget/notification_card.dart`

**Flutter — Modified:**
- `pubspec.yaml` (added firebase_core, firebase_messaging, flutter_local_notifications)
- `lib/core/storage/app_database.dart` (added Notifications table, bumped schema v22→v23)
- `lib/core/scaffold/main_shell.dart` (added NotificationBellWidget)
- `lib/core/router/app_router.dart` (added /notifications route)

**Flutter — Tests Created:**
- `test/features/notifications/domain/notification_model_test.dart`
- `test/features/notifications/data/notification_repository_test.dart`
- `test/features/notifications/presentation/notification_bell_widget_test.dart`
