---
title: '19.9 — SMS and email providers with failover, metered tenant sends, persistent in-app notifications, Expo push'
type: 'feature'
created: '2026-11-04'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/keevo/backend/src/main/resources/db/modules/README.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Keevo can only send WhatsApp and FCM push. The fusion needs SMS (MboaSMS, Infobip) and email (Resend) for OTP (19.2), document sending (FUS-FR22, epic 20) and Bref notifications. Bref apps also show a persisted notification inbox, which Keevo lacks: today push is fire-and-forget and FCM-only, while the React Native app (the Bref app) registers **Expo** push tokens. User decision 2026-11-04: for notifications and WhatsApp, Keevo does as Bref does; Bref's providers prevail. Review 19.8 also routed a rule here: a tenant's SMS/email must be counted on its quota only **after** provider success, in a short transaction, never around the HTTP call.

**Approach:**
- **Senders.** Two core ports, `SmsSender` and `EmailSender`, each backed by an ordered failover chain of provider adapters (order set by configuration).
- **Metered service.** A `TenantMessaging` service sends on behalf of a tenant. It probes the quota, sends with no transaction open, then counts in its own short transaction.
- **Inbox.** A tenant `notifications` table, filled by a decorator around the existing `PushNotifier`. Every existing business notification therefore lands in the inbox before its push.
- **Endpoints.** The inbox is exposed by list, unread-count, mark-read and mark-all-read endpoints.
- **Expo push.** Push delivery becomes a composite: Expo for `ExponentPushToken[…]` tokens (the Bref provider, which prevails), FCM kept only for the remaining (Flutter V1) tokens until Flutter is retired.
- **Out of scope.** The platform WhatsApp switch to Meta Cloud API with Bref's system templates, and Web Push, go to story 19-9b.

## Boundaries & Constraints

**Always:**
- **Hexagonal layout.**
  - Ports live in `shared/capability/notification`.
  - Provider HTTP clients are adapters under `messaging/**/adapter/out/external`. There is no `springframework.web`/http type in `domain`/`application` (ArchUnit rule c).
  - Every provider call has explicit timeouts (connect 5 s, read 10 s).
- **Failover.**
  - Providers are tried in the configured order. One counts as delivered only on HTTP 2xx; for Infobip SMS, additionally no `REJECTED` status group.
  - A missing key, an exception or a non-2xx makes the chain try the next provider.
  - Each failure is logged at WARN with the provider name and HTTP status only. Never log the key, message body or full recipient: phones are masked to their last 3 digits, emails as `j***@domain`.
  - A channel with an empty provider list is disabled. `isEnabled()` is false and `send` returns not-delivered without any call.
- **Recipients.**
  - SMS: the phone is normalized by stripping spaces, dashes and dots and must then match `^\+\d{8,15}$`. Otherwise the send is not delivered, no provider is called, and nothing is counted.
  - Sender id: strip non-alphanumerics, max 11 characters, default from config (`BrefPoint`).
- **Metered sends (`TenantMessaging`).**
  - It refuses to run inside an active transaction (`IllegalStateException`), so the HTTP call can never hold a counter row lock.
  - Sequence:
    1. Probe: `assertAndIncrementForSchema(schema, metric, 1)` in a short transaction forced to roll back. An exhausted quota throws `QUOTA_EXCEEDED` here and nothing is sent.
    2. Send through the chain, with no transaction.
    3. Only if delivered: `assertAndIncrementForSchema` again in a new short transaction that commits.
  - If step 3 refuses (a concurrent send won the last unit), the message is already delivered. Log WARN and return delivered: the overrun is bounded by concurrent sends.
  - A failed send is never counted. Metrics are `UsageMetric.SMS` / `UsageMetric.EMAILS`.
- **Fail-fast (10.3).** `ProdSecretsValidator` (prod and staging profiles) checks each provider listed in `keevo.sms.providers` and `keevo.email.providers` with `requireProviderValue`. An unknown provider name throws. An empty list logs WARN only (channel disabled).
- **Inbox persistence.**
  - The decorator persists one row per recipient user with a single batched insert.
  - Owners are resolved by `UserQueryPort.findOwnerUserIds(schema)`, one query.
  - The insert is schema-qualified and validated with `TenantSchema.validate`.
  - A persistence failure is logged and never prevents the push. `sendToTokens` (global admin broadcast) is NOT persisted, only delegated.
- **Inbox endpoints.**
  - Scoped to the current actor (`CurrentActor.actorId()`) in the current tenant.
  - A notification of another user is answered exactly like a missing one (404 `NOTIFICATION_NOT_FOUND`).
  - Mark-read is idempotent.
  - `size` is between 1 and 100 (default 20); `page` is 0 or more. Bad values give 400 via `InvalidRequestParameterException`.
- **Push delivery by token kind.**
  - Tokens starting with `ExponentPushToken[` (or `ExpoPushToken[`) go to Expo: `POST https://exp.host/--/api/v2/push/send` with at most 100 messages per request, `sound:'default'`, `priority:'high'`, `channelId:'default'`, and `data` = metadata + `deepLink` + `type`.
  - An optional `Authorization: Bearer` header is sent when `keevo.push.expo.access-token` is set.
  - A ticket error `DeviceNotRegistered` deletes the token from `device_tokens`.
  - All other tokens go to FCM exactly as today. The FCM adapter must skip Expo tokens.
  - `sendToTokens` partitions tokens by kind and sums the `PushSendResult`s.
  - `isPushAvailable()` is true if any channel is available.
  - Expo is off by default like FCM (`keevo.push.expo.enabled`, `${KEEVO_EXPO_PUSH_ENABLED:false}`); prod turns it on by environment (noted for the deploy). The Logging adapter stays the fallback when neither channel is enabled.
  - Each delivery is best effort and never throws.
- **Compatibility.** Additive API only (V1 2.0.1 apps). The new tenant table ships in `db/modules/messaging/V2__notifications.sql`, idempotent per the module README.

**Never:**
- Never count a tenant SMS or email before or around the provider call. Never count platform messages (OTP, welcome) on a tenant quota: they go through `SmsSender`/`EmailSender` directly, not `TenantMessaging`.
- Never wire OTP (19.2), invoice sending by SMS/email (epic 20), campaigns (FUS-FR32) or SMS-pack purchase here. This story delivers the channels only.
- No Web Push and no WhatsApp provider change here: both go to 19-9b. Expo receipts polling (`getReceipts`) is out of scope: only ticket errors are handled, as in Bref.
- No retries inside one provider (failover only). No template engine.
- Never edit `V1__messaging_baseline.sql` or any released migration.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| SMS failover | providers `mboasms,infobip`; MboaSMS answers 503 | Infobip is called and the result is delivered by `infobip` | WARN `[SMS] mboasms failed (HTTP 503)` with no secret and no full phone |
| All providers fail | both answer 5xx / timeout | not delivered | WARN per provider; nothing counted |
| Channel disabled | `keevo.email.providers` empty | `isEnabled()=false`, not delivered, no HTTP | none |
| Invalid phone | `"6990"` | not delivered, no HTTP | WARN with masked value |
| Metered, quota left | tenant 2 of 5 SMS used | delivered; counter MONTH = 3 | none |
| Metered, quota exhausted | 5 of 5 used, no pack | `QUOTA_EXCEEDED` 403 from the probe; no HTTP call; counter unchanged | caller handles the exception |
| Metered, send fails | quota left, all providers fail | not delivered; counter unchanged | none |
| Metered inside a tx | caller has an active transaction | `IllegalStateException` before any probe or HTTP | programming error |
| Push persisted | `notifyOwners(schema, payload)` with 2 owners | 2 `notifications` rows (type, title, body, data = metadata + deepLink), then FCM push | insert failure: ERROR log, push still sent |
| Expo and FCM tokens mixed | owner has 1 Expo + 1 FCM token | one Expo request + one FCM multicast; each token used once | Expo `DeviceNotRegistered` ticket: token deleted |
| List | GET `/api/v1/notifications?page=0&size=20` | own rows newest first (`created_at DESC, id DESC`), paginated envelope | bad page/size: 400 |
| Unread count | GET `/api/v1/notifications/unread-count` | `{count}` of own rows with `read_at IS NULL` | none |
| Mark read | PATCH `/{id}/read` on own unread row; repeated | 204 and `read_at` set once; repeat gives 204 unchanged | other user's or unknown id: 404 `NOTIFICATION_NOT_FOUND` |
| Mark all read | PATCH `/api/v1/notifications/read-all` | 204; all own unread rows get `read_at` | none |

</intent-contract>

## Code Map

- **Existing ports, next to the new ones.** `keevo/backend/src/main/java/com/keevo/shared/capability/notification/`:
  - `WhatsAppSender.java`
  - `PushNotifier.java:17-60`: `notifyOwners`, `notifyUsers`, `isPushAvailable`, `sendToTokens`
  - `NotificationPayload.java`: `type`, `title`, `body`, `deepLink`, `Map<String,String> metadata`
- **Failover style to mirror (but with a configured order list, not hard-coded).** `messaging/whatsapp/adapter/out/external/FailoverWhatsAppAdapter.java:28-140`:
  - `tryX` returns false on missing keys or on an exception, and logs a WARN.
  - The constructor logs which providers are configured and which are missing.
- **Push implementations to wrap.**
  - `messaging/notification/adapter/out/FcmNotificationAdapter.java:30-32`: `@Primary @ConditionalOnProperty keevo.fcm.enabled=true`.
  - `LoggingNotificationAdapter.java:21`: the opposite condition.
  - Both lose any `@Primary` and get `@Qualifier("pushDelivery")`. The new decorator becomes the `@Primary PushNotifier`.
  - Check `src/test/.../admin/plan/PlanProfilePostgresIntegrationTest.java:68`: `@MockitoBean PushNotifier` must still resolve, via primary or by naming the bean.
- **Expo reference (Bref, read-only):** `/home/toor/Project/FreeLance/AI/api-bref/src/notification/expo-push.service.ts` (URL, body fields, token prefix check, ticket errors only logged; Keevo also deletes `DeviceNotRegistered`). Token read in tenant: `DeviceTokenRepository.findOwnerTokens()` / `findByUserIds()` as in `FcmNotificationAdapter.java:47-110` (sets/clears `TenantContext`), delete via `deleteByToken`.
- **Push callers (unchanged; they gain persistence through the decorator).**
  - `messaging/notification/application/listener/*NotificationListener.java` (9 of them) and `application/scheduler/SalesTrendDetectionScheduler.java`.
  - `reporting/export/application/service/ExportJobRunner.java:196` (`notifyOwners`).
  - `admin/notification/application/service/GlobalNotificationDispatcher.java` (`sendToTokens`, not persisted).
- **Schema-qualified JDBC template to follow.** `messaging/notification/adapter/out/persistence/impl/JdbcNotificationCooldownAdapter.java`: `qualifiedTable()` plus tenant validation. It works in async listeners where `search_path` is not set.
- **Owner ids.** `identity/api/UserQueryPort.java:14`: `findOwnerUserIds(String tenantSchema)`. `messaging/package-info.java` already allows `identity :: api`.
- **Tenant migration.** `keevo/backend/src/main/resources/db/modules/messaging/V1__messaging_baseline.sql`:
  - Its header and style are the template for V2.
  - Read the rules in `db/modules/README.md`.
  - `src/test/.../shared/infrastructure/persistence/FoundationMigrationsPostgresIntegrationTest.java` asserts the messaging tables: add `notifications`.
  - `FlywayModuleMigrationsTest.java:101` enforces no `${`.
- **Quota.**
  - `shared/capability/entitlement/UsageQuotaPort.java:52-58`: `assertAndIncrementForSchema` with `MANDATORY` propagation, so it needs an existing transaction.
  - `UsageMetric.java`: `SMS` and `EMAILS`. Its Javadoc already says "counted on provider success, 19.9"; keep it true.
  - Use `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`. The probe calls `status.setRollbackOnly()`.
- **Fail-fast.**
  - `shared/infrastructure/config/ProdSecretsValidator.java`: `@Value` constructor at :95-106, `validateWhatsAppProvider` at :182-200 as the model, `requireProviderValue` at :213, placeholder detection at :236-247.
  - Its test sits next to it under `src/test/.../shared/infrastructure/config/`.
- **Config.**
  - `keevo/backend/src/main/resources/application.yml:228-243` (`keevo.fcm`, `keevo.whatsapp`): add `keevo.sms` and `keevo.email` there.
  - `messaging/notification/adapter/out/config/MessagingPropertiesConfig.java`: hand-built property records plus named `RestTemplate`s. Follow that style, or `RestClient` with the same timeouts.
- **REST conventions.**
  - `shared/infrastructure/web/ApiResponseWrapper.java:31,55`: `ok` and `paginated`.
  - `invoicing/invoice/adapter/in/rest/InvoiceController.java:115-153`: page/size validation and `@RequiresEntitlement` usage.
  - `shared/infrastructure/security/CurrentActor.java:39` (`actorId()`).
  - Entitlement manifest `src/main/resources/keevo/modules/messaging/module.json` (free, no permissions): use `@RequiresEntitlement("messaging")`, with `access=READ` on GETs.
- **Errors.**
  - `shared/domain/exception/ErrorCode.java`
  - `shared/infrastructure/web/ErrorMessages.java`: French message « Notification introuvable ».
  - `GlobalExceptionHandler.java:246`: add the new code to the 404 group.
  - `ErrorMessagesTest` and `ErrorCodeCompletenessTest` must pass.
- **Architecture gates.**
  - `src/test/.../architecture/EndpointEntitlementCoverageTest.java`: regenerate `docs/entitlements/endpoints.md` with `-Dkeevo.entitlements.regenerate=true`.
  - `KeevoArchitectureRules.java`: rules a, b and c.
  - `ApplicationModulesBaselineTest.java:98`.
- **Bref reference (read-only), `/home/toor/Project/FreeLance/AI/api-bref/src/`.**
  - `sms/sms.service.ts:32-123`:
    - Infobip: `POST {base}/sms/2/text/advanced`, header `Authorization: App <key>`, body `{messages:[{from,destinations:[{to}],text}]}`.
    - MboaSMS: `POST {base}/api/v1/developer/sms/send`, header `X-API-Key`, body `{phoneNumbers:[to],message,senderId}`.
  - `email/email.service.ts:114-161`: Resend, `POST https://api.resend.com/emails`, header `Bearer`, body `{from,to:[to],subject,text,html?,attachments?:[{filename,content(base64)}]}`.
  - `notification/**`: their repository drops `channel`. Do not copy that bug, and do not copy the unpaginated `GET /all`.

## Tasks & Acceptance

**Execution:**

**Ports** (`keevo/backend/src/main/java/com/keevo/shared/capability/notification/`):
- `SmsSender.java` (new) -- port with `boolean isEnabled()` and `DeliveryResult send(SmsMessage)`. Never throws on provider failure. Core contract, so OTP and invoicing depend on `shared` only.
- `EmailSender.java` (new) -- same contract for email.
- `SmsMessage.java`, `EmailMessage.java`, `EmailAttachment.java`, `DeliveryResult.java` (new records):
  - `SmsMessage(to, text, senderId?)`
  - `EmailMessage(to, subject, text, html?, fromName?, attachments)`
  - `EmailAttachment(filename, contentType, byte[] content)`
  - `DeliveryResult(boolean delivered, String provider)` with static `notDelivered()`.
- `TenantMessaging.java` (new) -- port with `DeliveryResult sendSms(String tenantSchema, SmsMessage)` and `sendEmail(String tenantSchema, EmailMessage)`. Javadoc states the probe/send/count rule and that `QUOTA_EXCEEDED` may be thrown before sending.

**Senders** (`messaging/channel/`):
- `messaging/channel/domain/port/out/SmsProvider.java`, `EmailProvider.java` (new) -- provider strategy with `name()`, `isConfigured()` and `boolean send(...)`.
- `messaging/channel/application/FailoverSmsSender.java`, `FailoverEmailSender.java` (new) -- chain of responsibility over the configured order. They implement the ports and contain no HTTP types.
- `messaging/channel/application/MeteredTenantMessaging.java` (new) -- implements `TenantMessaging`:
  - Active-transaction guard.
  - Probe in a rolled-back `REQUIRES_NEW` transaction.
  - Send.
  - Count in a `REQUIRES_NEW` transaction. A post-send refusal is logged and absorbed.
- `messaging/channel/adapter/out/external/MboaSmsProvider.java`, `InfobipSmsProvider.java`, `ResendEmailProvider.java`, `InfobipEmailProvider.java` (new) -- HTTP adapters with the timeouts and masked logging.
  - Infobip email: `POST {base}/email/4/messages`, `App` auth, per the Bref reference `email.service.ts:54-112`.
- `messaging/channel/adapter/out/config/ChannelPropertiesConfig.java` (new) -- properties and bean wiring:
  - The provider order comes from `keevo.sms.providers` / `keevo.email.providers` (comma list).
  - An unknown name fails at startup in every profile.

**Configuration:**
- `keevo/backend/src/main/resources/application.yml` -- add the keys, all defaulting to empty (disabled):
  - `keevo.sms.{providers,sender-id,mboasms.{base-url,api-key},infobip.{base-url,api-key}}`
  - `keevo.email.{providers,from,from-name,resend.api-key,infobip.{base-url,api-key}}`
  - Environment variables: `KEEVO_SMS_PROVIDERS`, `KEEVO_SMS_SENDER_ID`, `MBOASMS_BASE_URL`, `MBOASMS_API_KEY`, `INFOBIP_BASE_URL`, `INFOBIP_API_KEY`, `KEEVO_EMAIL_PROVIDERS`, `KEEVO_EMAIL_FROM`, `RESEND_API_KEY`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidator.java` -- add `validateSmsProviders()` and `validateEmailProviders()`. Update its test with the cases: missing key, unknown provider, empty list.

**Inbox** (`messaging/notification/`):
- `keevo/backend/src/main/resources/db/modules/messaging/V2__notifications.sql` (new) -- `notifications(id uuid pk default gen_random_uuid(), user_id uuid not null, type varchar(64) not null, title varchar(150) not null, body varchar(1000) not null, data jsonb, read_at timestamptz, created_at timestamptz not null default now())`.
  - Index `(user_id, created_at DESC, id DESC)`.
  - Partial index `(user_id) WHERE read_at IS NULL`.
  - Idempotent, with `lock_timeout`.
- `messaging/notification/domain/port/out/NotificationInboxRepository.java` + `adapter/out/persistence/impl/JdbcNotificationInboxAdapter.java` (new) -- all SQL schema-qualified:
  - `insertAll` (batch; title and body truncated to the column sizes)
  - `page(userId, offset, limit)`
  - `countByUser`
  - `countUnread`
  - `markRead(id, userId)`: `UPDATE … WHERE id=? AND user_id=? AND read_at IS NULL`. When it updates 0 rows, an existence check `id+user_id` decides between 404 and an idempotent 204.
  - `markAllRead`
- `messaging/notification/adapter/out/PersistingPushNotifier.java` (new) -- `@Primary` decorator:
  - Persists, then delegates to `@Qualifier("pushDelivery") PushNotifier`.
  - `sendToTokens` and `isPushAvailable` only delegate.
  - Recipients are de-duplicated.
- `FcmNotificationAdapter.java`, `LoggingNotificationAdapter.java` -- qualifier change (Fcm drops `@Primary`); FCM skips Expo tokens. Fix tests that relied on it.
- `messaging/notification/adapter/out/ExpoPushAdapter.java` (new) -- Expo delivery (`@Qualifier("pushDelivery")`, `@ConditionalOnProperty keevo.push.expo.enabled=true`): token fetch per tenant, chunks of 100, timeouts connect 5 s / read 10 s, `DeviceNotRegistered` → delete. The decorator receives `List<PushNotifier>` qualified `pushDelivery` (composite) and calls each.
- `application.yml` -- `keevo.push.expo.{enabled: ${KEEVO_EXPO_PUSH_ENABLED:false}, access-token: ${EXPO_ACCESS_TOKEN:}}`.
- `messaging/notification/domain/port/in/*` + `application/service/NotificationInboxService.java` (new) -- list, unread count, mark read, mark all read for `(schema, actorId)`.
- `messaging/notification/adapter/in/rest/NotificationController.java` + `dto/NotificationDto.java` (new):
  - Routes: `GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `PATCH /api/v1/notifications/{id}/read`, `PATCH /api/v1/notifications/read-all`.
  - DTO: `{id, type, title, body, data, read, readAt, createdAt}`.
- `ErrorCode`, `ErrorMessages`, `GlobalExceptionHandler` -- add `NOTIFICATION_NOT_FOUND` (404, « Notification introuvable »).
- `docs/entitlements/endpoints.md` -- regenerate.

**Tests:**
- Unit:
  - Failover chain: order, skipping unconfigured providers, all fail, disabled channel.
  - Phone and sender-id normalization; masking.
  - Each provider adapter against `MockRestServiceServer` or WireMock: request shape, headers, 2xx versus failure, Infobip `REJECTED`.
  - `MeteredTenantMessaging`: active-transaction guard; probe refusal means no send; failure means no count; post-send refusal is absorbed.
  - `ExpoPushAdapter` against `MockRestServiceServer`: body shape, chunks of 100, `DeviceNotRegistered` deletes the token, 5xx never throws.
  - FCM skips Expo tokens; `sendToTokens` partition and sum.
  - `PersistingPushNotifier`: persist-then-push; persistence error still pushes; `sendToTokens` not persisted.
- Postgres integration:
  - Metered SMS counts exactly 1 MONTH and 1 DAY on success and 0 on failure (stub providers).
  - The `QUOTA_EXCEEDED` probe leaves counters unchanged.
  - Inbox endpoints, covering pagination order, another user's id giving 404, and idempotent mark-read.
  - `FoundationMigrationsPostgresIntegrationTest` updated.

**Acceptance Criteria:**
- Given the prod profile with `keevo.sms.providers=mboasms` and no `MBOASMS_API_KEY`, when the application starts, then it fails fast with `[FailFast] MBOASMS_API_KEY ...`. With `keevo.sms.providers` empty it starts with a WARN.
- Given an existing business event that calls `PushNotifier.notifyOwners`, for example a stock-threshold breach, when it fires, then each owner has a new unread row in `GET /api/v1/notifications`, `unread-count` grows by 1, and FCM is still attempted.
- Given a tenant whose schema predates this story, when the application boots, then `notifications` is created by messaging V2 without touching other tables. A second boot is a no-op.
- Given installed V1 apps, when they call existing endpoints, then no response changes.

## Design Notes

**Patterns:**
- **Strategy plus Chain of Responsibility for providers.** A `SmsProvider` list in the configured order is walked by the failover sender. Adding a provider means adding an adapter plus a name, with no change to callers (open/closed). This beats the WhatsApp per-value `@ConditionalOnProperty` beans, which hard-code the order.
- **Decorator over `PushNotifier` for the inbox.** One class makes all 11 existing call sites persistent, with no edit to the listeners. The global broadcast (`sendToTokens`) keeps its separate admin store `public.global_notifications`.

- **Composite over push deliveries.** Expo and FCM each handle only their token kind; the decorator fans out to all of them. When Flutter is retired, the FCM bean is removed with no other change.

**Probe-then-count instead of reserve-then-refund.** A refund would need a decrement on the right bucket (period or pack), which is a new port method and a double-entry risk. The probe reuses the exact guarded upsert of 19.8, pack fallback included, and rolls it back. The only cost is a bounded overrun when concurrent sends race the last unit, which is acceptable for SMS. Pseudocode:

```java
if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException(...);
probeTx.executeWithoutResult(s -> { quotas.assertAndIncrementForSchema(schema, SMS, 1); s.setRollbackOnly(); });
DeliveryResult r = smsSender.send(msg);          // no tx, no lock
if (r.delivered()) try { countTx.executeWithoutResult(s -> quotas.assertAndIncrementForSchema(schema, SMS, 1)); }
                   catch (DomainException e) { log.warn("[Quota] SMS sent past quota (concurrent)"); }
```

**Complexity (FUS-NFR11):**
- **Metered send:** 2 short transactions, each one guarded upsert per quota period (at most 3), plus 1 to N HTTP calls.
- **Push delivery:** per delivery channel, 1 token query and ⌈tokens/100⌉ Expo requests or ⌈tokens/500⌉ FCM multicasts. Partitioning is O(tokens).
- **Push persistence:** 1 owner-id query plus 1 JDBC batch insert per event. It is O(recipients) rows with no per-row round trip, and adds nothing per device token.
- **List:** 1 page query served by `(user_id, created_at DESC, id DESC)` plus 1 count. `size` is at most 100.
- **Unread count:** 1 query served by the partial index.
- **Mark all read:** 1 UPDATE.
- **Pagination:** offset pagination is acceptable. Per-user volume is small and the page size is bounded; keyset pagination is not needed for an inbox at this scale.

**Not in this story:**
- **Inbox retention.** There is no purge of old notifications. It would need a job across N tenant schemas, which belongs with the multi-tenant load work. It is routed to 19.11.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='*Sms*,*Email*,*Failover*,*TenantMessaging*,*Notification*,ProdSecretsValidator*'` -- expected: green.
- `cd keevo/backend && mvn -o -q test -Dtest='EndpointEntitlementCoverageTest,ApplicationModulesBaselineTest,KeevoArchitectureRules*,ErrorMessagesTest,ErrorCodeCompletenessTest,FlywayModuleMigrationsTest,FoundationMigrationsPostgresIntegrationTest'` -- expected: green.
- Check `free -m` before each run and start only with at least 4096 MB available. Run one package at a time, never the full suite at once.
