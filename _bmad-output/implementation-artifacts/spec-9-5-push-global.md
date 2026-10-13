---
title: '9-5 (2/2) — Global push notifications from the super admin (FR82)'
type: 'feature'
created: '2026-10-13'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
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

## Review Triage Log

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.messaging.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/app && flutter analyze && flutter test --concurrency=4 test/core/notification && flutter test --concurrency=4 test/features/notifications` -- expected: no new warning, green
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed
