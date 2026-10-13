---
title: '9-5 (2/2) — Global push notifications from the super admin (FR82)'
type: 'feature'
created: '2026-10-13'
status: 'done'
baseline_revision: 'ba337ea2ddb3defa3ccb7984702d97b13bcc3d34'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Toor cannot announce maintenance or new features to merchants (FR82, epic 9 story 9.5 « Diffusion globale »): push notifications are only sent per tenant by business events.

**Approach:** A « Notifications » dashboard page to compose (title ≤ 80, body ≤ 200, target segment, now or scheduled), preview and send a global push via FCM; sends are persisted with delivery counts and a 90-day history; the app stores the announcement in its in-app notification history and opens it like other notifications.

## Boundaries & Constraints

**Always:**
- Flyway table `public.global_notifications (id uuid pk, title varchar(80) not null, body varchar(200) not null, target varchar(20) check in (`ALL`,`PREMIUM`,`TRIAL`,`FREE`,`TENANT`), target_tenant_id uuid null (required iff TENANT), scheduled_at timestamptz not null, status varchar(10) check in (`SCHEDULED`,`SENDING`,`SENT`,`FAILED`,`CANCELLED`), recipient_count int, delivered_count int, failure_count int, created_by uuid not null, created_at timestamptz default now(), sent_at timestamptz null)`; rows older than 90 days purged daily.
- Endpoints (`CurrentActor.requireSuperAdmin()`): `POST /api/v1/admin/notifications/global` `{ title, body, target, targetTenantId?, scheduledAt? }` (scheduledAt null = now; must be ≥ now − 1 min and ≤ now + 30 days) → row `SCHEDULED`, returns `{ id, recipientEstimate }`; `GET /admin/notifications/global` (history, newest first, 90 days); `POST /admin/notifications/global/{id}/cancel` (only while SCHEDULED); `GET /admin/notifications/global/estimate?target&targetTenantId` → device count.
- A ShedLock job every minute picks due `SCHEDULED` rows (atomic `UPDATE … SET status='SENDING' WHERE status='SCHEDULED' AND scheduled_at <= now() RETURNING`), resolves targets: tenants not DELETED/SUSPENDED (excluding `KV-ADMIN`), filtered by current plan of each schema (ALL / PREMIUM / TRIAL = PREMIUM_TRIAL / FREE / one tenant), all device tokens of every user (owners and employees) from each schema's `device_tokens`; sends via FCM multicast in chunks of 500 through a new port method returning counts (success/failure), removes tokens FCM reports as unregistered (existing `processResponse` logic), then `SENT` with counts and `sent_at` (or `FAILED` when FCM is not configured / every chunk threw). A `GlobalPushSentEvent` (actorId, target, recipientCount, sentAt, title) is published and logged.
- FCM data payload: `type = GLOBAL_ANNOUNCEMENT`, `title`, `body`, notification block for the system tray; no deep link other than the notifications screen.
- Flutter app: `fcm_service.dart` stores `GLOBAL_ANNOUNCEMENT` messages in the local notifications table (foreground and background/tap paths, same as existing types), shows them with a megaphone icon and the label « Annonce Keevo » in the notifications list; bump `keevo/app/pubspec.yaml` version (patch + build, currently 1.1.50+49 → 1.1.51+50).
- Dashboard `/notifications` page: form with live counters (80 / 200), target select « Tous / Premium / Essai / Gratuit / Un commerce » (tenant picker by name when « Un commerce »), « Maintenant / Programmer » with datetime, recipient estimate, Android-style and iOS-style preview cards, confirmation dialog « Envoyer à N appareils ? »; after submit a status card « Envoi en cours — N appareils ciblés » polled every 10 s until « ✅ Envoyé — X livrés / Y échecs »; history table (date, titre, cible label, livrés, échecs, statut label, « Annuler » on scheduled). French labels, no ids. Sidebar entry « Notifications ».

**Never:** No email/SMS channel; no per-user targeting; no rich media.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Send now ALL | 3 tenants, 5 tokens | row SENT, recipient 5, delivered/failure from FCM | — |
| Target PREMIUM | tenants FREE, PREMIUM | only PREMIUM tenant tokens | — |
| TENANT without id | target TENANT, no tenant | 422 `VALIDATION_ERROR` | — |
| Title 81 chars / body 201 | — | 422 `VALIDATION_ERROR` | form blocks before |
| Scheduled in 1 h | scheduledAt +1 h | SCHEDULED, sent by the job after due time, not before | — |
| Cancel scheduled | SCHEDULED | CANCELLED, never sent | SENT → 409 `INVALID_STATE` |
| Two instances | job runs twice concurrently | sent once (ShedLock + atomic status update) | — |
| FCM not configured | no Firebase | FAILED, counts 0 | dashboard shows « Échec de l'envoi » |
| Unregistered token | FCM error UNREGISTERED | token deleted, counted as failure | — |
| App receives | GLOBAL_ANNOUNCEMENT | entry in notifications list « Annonce Keevo » | — |
| Non-admin | OWNER | 403 `FORBIDDEN` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/messaging/notification/adapter/out/FcmNotificationAdapter.java:45-130` -- multicast send, `processResponse` (token cleanup); add a counted multicast send used by the global push.
- `keevo/backend/src/main/java/com/keevo/messaging/notification/domain/port/out/{NotificationPort,DeviceTokenRepository}.java` -- ports; `LoggingNotificationAdapter` fallback when FCM absent.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java:820` -- per-schema `device_tokens`; `subscriptions.plan_type`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/scheduling/` -- ShedLock job pattern.
- `keevo/app/lib/core/notification/fcm_service.dart`, `keevo/app/lib/features/notifications/data/datasource/local_notification_datasource.dart`, notifications list widgets -- type handling, icon/label.
- `keevo/dashboard/src/components/layout/Sidebar.tsx`, `lib/labels.ts`, tenants API (`lib/api/admin-tenants.ts`) for the tenant picker.

## Tasks & Acceptance

**Execution:**
- `db/migration/V21__global_notifications.sql` (next free version) -- table + index (status, scheduled_at).
- `admin/notification/**` (new) -- controller, DTOs, `GlobalNotificationService`, dispatcher job, purge, ports/adapters; FCM counted send in `messaging/notification`.
- Tests: validation, target resolution per plan, atomic claim (no double send), cancel guards, FCM-absent FAILED, unregistered token cleanup, controller 403/422/409 + `jsonPath`.
- Flutter: `fcm_service.dart` + notifications UI for `GLOBAL_ANNOUNCEMENT`, tests in `test/core/notification` and `test/features/notifications`; `pubspec.yaml` bump.
- Dashboard: `types/global-notification.ts`, `lib/api/admin-notifications.ts`, `hooks/useGlobalNotifications.ts`, `components/notifications/{ComposeForm,PushPreview,SendStatusCard,NotificationHistory}.tsx`, `app/notifications/page.tsx`, `Sidebar.tsx`; component tests (counters, validation, preview text, status polling stop, labels).

**Acceptance Criteria:**
- Given a sent announcement, when an owner opens the app's notifications screen, then the announcement is listed as « Annonce Keevo » with its title and body.
- Given the history, when Toor opens « Notifications », then each send of the last 90 days shows its delivered and failed counts.

## Spec Change Log

### 2026-10-13 — Orchestrator decision during review (no loopback)
- Trigger: the AC « an owner opening the app's notifications sees the announcement » was unmet for announcements received in background and never tapped (no background isolate handler).
- Decision: the app pulls its announcements from a new tenant endpoint `GET /api/v1/notifications/announcements?since=` on start and resume (inserted by id, never overwriting) instead of a background isolate writing to the encrypted Drift database; applied as a patch.

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 37 findings — high 2, medium 10, low 18, false 3, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[medium]` `[patch]` (blind) rows stuck in SENDING forever — >15 min SENDING → FAILED « interrompu », purge no longer skips old SENDING.
  - `[high]` `[patch]` (blind) estimate failure after insert → 500 while the push is scheduled → duplicate broadcast — estimate computed before insert, no row on failure.
  - `[medium]` `[patch]` (blind) same device in several companies gets the push twice — token set across companies.
  - `[medium]` `[patch]` (blind) big broadcast blocks the single scheduler thread — pool size 4, lock PT10M.
  - `[high]` `[patch]` (blind) background announcements never stored unless tapped (AC unmet) — app fetches its announcements from a new tenant endpoint on start/resume, upsert by id.
  - `[low]` `[patch]` (blind) company picker page-0 only, offers unreachable companies — server-side reachable search.
  - `[false]` `[reject]` (blind) plan targets from `public.tenants.plan_type` — authoritative since 9-2 (synced on every plan change, realigned at startup); same source as the 9-2 alerts and 9-5 feed.
  - `[low]` `[patch]` (blind) no audit trail — `[ADMIN AUDIT]` lines for schedule/cancel/outcome, title in the event log.
  - `[low]` `[patch]` (blind) FAILED rows without reason/time — `failure_reason` + `sent_at` on terminal states, French labels.
  - `[medium]` `[patch]` (blind) status card stops for scheduled sends — polls while SCHEDULED/SENDING with a cap.
  - `[low]` `[patch]` (blind) stale server field errors — cleared on edit/target change.
  - `[low]` `[patch]` (blind) history lacks targeted devices — « Appareils ciblés » column.
  - `[low]` `[patch]` (blind) dialog stale estimate, no Escape/focus, no date bounds — fixed.
  - `[low]` `[patch]` (blind) null-body details incomplete; cancel non-UUID untested — details for all fields; test.
  - `[medium]` `[patch]` (edge) stuck SENDING — same as blind row 1.
  - `[medium]` `[patch]` (edge) partial failure ends FAILED 0/0/0 — per-company catch, accumulated counts kept.
  - `[medium]` `[patch]` (edge) empty audience marked SENT — FAILED `NO_RECIPIENT`.
  - `[medium]` `[patch]` (edge) duplicate tokens — same as blind row 3.
  - `[high]` `[patch]` (edge) estimate after insert — same as blind row 2.
  - `[low]` `[patch]` (edge) trim vs strip on Unicode spaces — `strip()`.
  - `[low]` `[patch]` (edge) browser clock vs server — polling no longer depends on the client clock comparison.
  - `[low]` `[patch]` (edge) scheduled card never updates — same as blind status row.
  - `[low]` `[patch]` (edge) card without dismiss — « Fermer » always shown.
  - `[low]` `[patch]` (edge) picker offers KV-ADMIN / page 0 — same as blind picker row.
  - `[low]` `[patch]` (edge) stale field errors — same as blind row.
  - `[high]` `[patch]` (edge) background announcement not stored — same as blind row 5.
  - `[low]` `[patch]` (edge) receivedAt = tap time — `sentTime`.
  - `[high]` `[patch]` (edge, claim) AC « owner sees the announcement » unmet — same as blind row 5.
  - `[low]` `[patch]` (gap) TenantContext during token cleanup unverified — assertion added.
  - `[low]` `[patch]` (gap) `admin-notifications.ts` untested — client-mocked tests.
  - `[low]` `[patch]` (gap) global-only tables never checked — one check for the whole set.
  - `[medium]` `[patch]` (gap other) stuck SENDING — same as blind row 1.
  - `[false]` `[reject]` (intent) per-tenant chunking vs global — chunking per company is required for per-schema token cleanup; FCM cost difference negligible.
  - `[false]` `[reject]` (intent) two-instance scenario not run end to end — atomic UPDATE … RETURNING claim proven by a concurrent Postgres test; ShedLock covered by the lock coverage test.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.messaging.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/app && flutter analyze && flutter test --concurrency=4 test/core/notification && flutter test --concurrency=4 test/features/notifications` -- expected: no new warning, green
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed

## Auto Run Result

Status: done

**Summary:** Story 9-5 part 2 delivered (FR82). Toor composes a global announcement on « Notifications » (title ≤ 80, body ≤ 200, target Tous / Premium / Essai / Gratuit / Un commerce via a server-side reachable-company search, now or scheduled ≤ 30 days, device estimate, Android/iOS previews, confirmation). `public.global_notifications` (V21) with failure reason and timestamps; a ShedLock job (every minute, scheduler pool 4) claims due rows atomically, sends FCM multicast in 500-token chunks per company with tokens de-duplicated across companies, cleans unregistered tokens in the right schema, ends SENT with counts or FAILED with a French-labelled reason, recovers rows stuck in SENDING; history 90 days with cancel. The app shows « Annonce Keevo » entries (foreground, tap, and a pull on start/resume from `/api/v1/notifications/announcements`). Admin actions logged `[ADMIN AUDIT]`.

**Files changed:** backend `V21__global_notifications.sql`, `admin/notification/**`, `messaging/notification` (port `sendToTokens`/`isPushAvailable`, `PushSendResult`, FCM + logging adapters, `AnnouncementController`), schedulers, `ErrorCode.SERVICE_UNAVAILABLE` → 503, `application.yml` (scheduler pool), `TenantSchemaSyncService.globalOnlyTables()`; tests (unit, controllers, Postgres IT); app `fcm_service.dart`, notifications model/card, `RemoteAnnouncementDataSource`, `AnnouncementSyncService`, `LocalNotificationDataSource.insertIfAbsent`, `MainShell` lifecycle hook, sign-out prefs, `pubspec.yaml` 1.1.51+50 + tests; dashboard `types/global-notification.ts`, `lib/api/admin-notifications.ts`, `hooks/useGlobalNotifications.ts`, `components/notifications/*`, `app/notifications/page.tsx`, `lib/labels.ts`, `Sidebar.tsx` + tests.

**Review:** 37 findings — patched 2 high entries (duplicate broadcast after a failing estimate; background announcements missing in the app), 10 medium and 18 low; deferred 0; rejected 5 (false 3, low with reasons). One orchestrator decision logged (announcements pull).

**Follow-up review recommended:** true — highs were patched; unverified risk: FCM delivery and the app pull are tested with mocks/Postgres only, not with a real Firebase project and device.

**Verification:** dashboard lint, tsc, `npm test` (53 files, 252 tests), build — green. Backend full chunked regression (13 packages, 3 263 tests) — 0 failures/errors. App `flutter analyze` 0 warning/error; `test/core` 521, `test/features/notifications` 24, `settings` 52, `auth` 104 — all passed.

**Residual risks:** a phone registered in several companies receives one push but appears once per company in the in-app pull (same id → single row); FCM quotas not throttled beyond chunking.

