---
title: '19.9b — Bref channels: platform WhatsApp through Meta Cloud API (templates) with Wassender/Twilio fallback, Expo receipts, Web Push (VAPID)'
type: 'feature'
created: '2026-11-04'
status: 'done'
baseline_revision: '9735678fe9b103e2438229e2085ebf3b38d4bf7b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: [oversized, multiple-goals]
deferred:
  - summary: >-
      Invoices and quotes are not sent through Meta template bref_facture.
    evidence: |-
      In Bref, bref_facture is sent from the owner's own WABA number, never the platform number (whatsapp.service.ts:318-330); Keevo has no per-tenant WABA yet. Routed to 21.5 (epic brief + sprint-status).
    location: >-
      keevo/backend/src/main/java/com/keevo/invoicing (SendInvoiceUseCase → WhatsAppSender.sendReport)
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The user decided Bref's providers prevail for notifications and WhatsApp. Keevo's platform WhatsApp still goes only through Wassender/Twilio free text, Expo push receipts are never read (stale tokens accumulate), and Bref's browser notifications (Web Push) do not exist in Keevo.

**Approach:** (1) Turn `WhatsAppSender` into a Chain of Responsibility over WhatsApp providers: Meta Cloud API first for sign-in codes (Bref's approved template `bref_point_otp`), Wassender then Twilio as fallback and for every free-text message (Meta free text only works inside the 24-hour window). (2) Store Expo ticket ids and read their receipts in a short scheduled job, deleting unregistered tokens in one statement per tenant. (3) Port Bref's Web Push (VAPID keys, subscribe/unsubscribe, send to a user's browsers, delete on 404/410) as an extra delivery path of the 19.9 push composite.

## Boundaries & Constraints

**Always:**
- WhatsApp config: `keevo.whatsapp.provider` keeps its values (`noop`, `wassender`, `twilio`, `failover` = Wassender→Twilio) and gains `meta` = Meta→Wassender→Twilio. Meta keys `keevo.whatsapp.meta.{access-token,phone-number-id,otp-template(default bref_point_otp)}` from Bref's env names `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_OTP_TEMPLATE`. `ProdSecretsValidator` requires Meta keys only when the provider is `meta` (and still the Wassender/Twilio keys it already requires for failover presets; with `meta`, at least one fallback configured is not required).
- `WhatsAppSender` port: `sendReport(phone, text)` unchanged (free text: Wassender→Twilio only, never Meta). Sign-in/reset codes use a new port method `sendCode(phone, code, fallbackText, language)`: Meta template `bref_point_otp` with body parameter = code and URL button parameter = code (exact Bref component layout), language `fr` then `en` on Meta error 132001 (translation missing) when the language is French, else `en`; on any other Meta failure (HTTP error, timeout, not configured) the fallback text goes through Wassender→Twilio. `RequestOtpService` (19.2) and `RequestPasswordResetService` (14.12, its existing reset text as fallback) call `sendCode`. `sendLoginCode`/`sendOtp` defaults are removed or delegate to `sendCode` (no caller left on the old path).
- Meta HTTP: `POST https://graph.facebook.com/v21.0/{phoneNumberId}/messages`, Bearer token, `to` = digits only, connect/read timeouts like the 19.9 providers (`ProviderHttp`), a read timeout is an unknown outcome → no fallback (no duplicate code), logged WARN. Logs never contain the code, the token or the full phone (masked).
- Expo receipts: every `ok` ticket id from `ExpoPushAdapter` is stored with its token and tenant schema in `public.expo_push_tickets(ticket_id pk, token, tenant_schema, created_at)` (one batch insert per send). A `@Scheduled` + ShedLock job every 15 min reads tickets older than 15 min in batches of 1000 (`POST https://exp.host/--/api/v2/push/getReceipts {ids}`), deletes the tokens whose receipt error is `DeviceNotRegistered` with one `deleteByTokens` per tenant schema present in the batch, logs other errors (e.g. `MessageRateExceeded`, `InvalidCredentials`) at WARN by count, and deletes the processed ticket rows in one statement; tickets older than 24 h are deleted unread (Expo keeps receipts 24 h). Job off when Expo is off.
- Web Push: config `keevo.push.web.{vapid-public-key,vapid-private-key,subject}` from Bref's env names `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_SUBJECT` (default `mailto:admin@brefpoint.app`) — same keys as Bref so imported browser subscriptions stay valid. Missing keys → Web Push disabled (WARN at boot), endpoints still answer. Table `public.web_push_subscriptions(id uuid pk, user_id uuid not null, endpoint varchar(2048) unique not null, p256dh varchar(200) not null, auth varchar(100) not null, user_agent varchar(500), created_at, updated_at)` + index on `user_id`.
- Endpoints: `GET /api/v1/web-push/vapid-public-key` public → `{"publicKey":"…"}` (empty string when disabled); `POST /api/v1/web-push/subscribe` (authenticated, any role) body `{endpoint,p256dh,auth,userAgent?}` → 204, upsert by endpoint; an endpoint owned by another user is reassigned only when `p256dh` and `auth` are identical, else 409 `PUSH_ENDPOINT_OWNED` « Cet appareil est déjà lié à un autre compte »; endpoint host must be in Bref's allow-list (`fcm.googleapis.com`, `updates.push.services.mozilla.com`, `updates-autopush.stage.mozaws.net`, `updates-autopush.dev.mozaws.net`, suffixes `.push.apple.com`, `.notify.windows.com`; https, no userinfo, port 443 only, ≤2048 chars) else 422 `VALIDATION_FAILED`; `DELETE /api/v1/web-push/subscribe` body `{endpoint}` → 204, deletes only the caller's row.
- Web Push delivery: `PersistingPushNotifier` resolves recipients once (owners or given users), sends device tokens as today and, when Web Push is enabled, loads the recipients' subscriptions in one query (`user_id = ANY(?)`) and sends each one (RFC 8291 `aes128gcm` payload encryption, RFC 8292 VAPID `Authorization: vapid t=<ES256 JWT>, k=<public key>`, `TTL: 86400`); payload JSON `{title, body, icon:"/icon.svg", badge:"/favicon-32x32.png", data}`. 404/410 → those subscription rows deleted in one statement; other errors WARN. Never throws; the inbox insert and device push are unaffected by a Web Push failure. Implemented with JDK crypto only (ECDH P-256, HKDF-SHA256, AES-128-GCM, SHA256withECDSA converted to raw R‖S) — no new Maven dependency (offline build).
- New public migrations `V46__expo_push_tickets.sql`, `V47__web_push_subscriptions.sql` (idempotent). Security: `vapid-public-key` public; `subscribe` authenticated; controller `@EntitlementExempt`; `docs/entitlements/endpoints.md` regenerated; `.env.example` and `application.yml` updated.

**Never:**
- No per-tenant WhatsApp Business (owner WABA, `bref_facture` sent from the owner's number, inbox, campaigns, webhooks — story 21.5). No Meta templates for Keevo's free-text messages (daily report, subscription reminders, alerts): their list and wording need the user's validation and Meta's approval — routed to a new key `19-9c`. No change to SMS/email (19.9) or to the meaning of existing provider values. No Web Push `sendToAll`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Code, provider meta, Meta OK | `sendCode(+2376…, 123456, text, fr)` | 1 Meta call, template `bref_point_otp` lang `fr`, no fallback | — |
| Meta 132001 in fr | | retry lang `en`, then fallback text only if `en` also fails | — |
| Meta 400/500 or not configured | | Wassender text; Wassender fails → Twilio | WARN, masked phone |
| Meta read timeout | | no fallback | WARN « unknown outcome » |
| Free text, provider meta | `sendReport(…)` | Wassender→Twilio, never Meta | existing exception if both fail |
| Expo receipt DeviceNotRegistered | 3 tickets, 2 tenants | tokens deleted with 1 statement per tenant, ticket rows deleted | — |
| Tickets > 24 h | | deleted without a receipt call | — |
| Subscribe new endpoint | valid FCM endpoint | 204, row created | — |
| Subscribe endpoint of another user, other keys | | — | 409 `PUSH_ENDPOINT_OWNED` |
| Subscribe `https://evil.example/x` or `http://fcm…` | | — | 422 `VALIDATION_FAILED` |
| Push to owner with 2 browsers, one gone | 410 on one | 1 delivered, 410 row deleted | — |
| VAPID keys missing | | `publicKey:""`, no Web Push send | WARN at boot |

</intent-contract>

## Code Map

- `api-bref/src/whatsapp/whatsapp.service.ts:459-578` (read-only, `/home/toor/Project/FreeLance/AI/api-bref`) -- `sendTemplate` fr→en fallback on 132001, `sendOtp` components (body text + URL button index 0).
- `api-bref/src/web-push/web-push.service.ts`, `web-push.controller.ts`, `dto/push-endpoint.ts`, `entities/web-push-subscription.entity.ts` -- subscribe upsert rule, allow-list, 404/410 deletion, payload shape.
- `messaging/whatsapp/adapter/out/external/{FailoverWhatsAppAdapter,WassenderWhatsAppAdapter,TwilioWhatsAppAdapter}.java`, `noop/NoOpWhatsAppAdapter.java` -- current `@ConditionalOnProperty(keevo.whatsapp.provider)` adapters; Wassender/Twilio send code to reuse as chain providers.
- `shared/capability/notification/WhatsAppSender.java` -- port (`isConfigured`, `sendReport`, defaults `sendOtp`, `sendLoginCode`).
- `identity/auth/application/service/RequestOtpService.java:~150-160`, `RequestPasswordResetService.java:108` -- the two code senders.
- `messaging/channel/adapter/out/external/ProviderHttp.java`, `application/FailoverSmsSender.java` -- 19.9 timeouts, outcome DELIVERED/FAILED/UNKNOWN, failover pattern to mirror.
- `messaging/notification/adapter/out/ExpoPushAdapter.java:80-160` -- ticket parsing, `deviceTokens.deleteByTokens`; `domain/port/out/DeviceTokenRepository.java:18`.
- `messaging/notification/adapter/out/PersistingPushNotifier.java` -- composite (`persist`, `deliver`, `route`); add recipient resolution once + Web Push path.
- `shared/infrastructure/config/ProdSecretsValidator.java:95-150` -- WhatsApp provider checks.
- `shared/infrastructure/security/SecurityConfig.java:61` -- `PUBLIC_PATHS`.
- `src/main/resources/db/migration/` -- last `V45__otp_challenges.sql`.
- Scheduled job pattern with ShedLock: `identity/auth/application/service/AuthTokenCleanupJob.java:43`.

## Tasks & Acceptance

**Execution:**
- `messaging/whatsapp/domain/port/out/WhatsAppProvider.java` + `application/ChainWhatsAppSender.java` -- provider interface (`name`, `isConfigured`, `sendText`, `supportsTemplates`, `sendCode` → `SendOutcome`) and the chain implementing `WhatsAppSender` per the rules; `adapter/out/config` builds the ordered list from `keevo.whatsapp.provider` presets (single bean, replaces the per-value conditional adapters; noop stays a provider that logs without the text body).
- `messaging/whatsapp/adapter/out/external/MetaWhatsAppProvider.java` -- Graph API template call, fr→en, timeouts.
- Wassender/Twilio adapters -- become `WhatsAppProvider` implementations (same HTTP calls, same behaviour).
- `shared/capability/notification/WhatsAppSender.java` -- `sendCode(phone, code, fallbackText, language)`; callers `RequestOtpService`, `RequestPasswordResetService` switched.
- `V46__expo_push_tickets.sql`, `ExpoPushAdapter.java` (store ok tickets), `messaging/notification/application/service/ExpoReceiptJob.java` + JDBC repository -- receipts per the rules.
- `V47__web_push_subscriptions.sql`, `messaging/notification/domain/port/out/WebPushSubscriptionRepository.java` + JDBC adapter, `adapter/out/WebPushSender.java` (crypto: `WebPushCrypto` with RFC 8291 encryption and VAPID JWT), `application/service/WebPushSubscriptionService.java`, `adapter/in/rest/WebPushController.java` + DTOs -- per the rules.
- `PersistingPushNotifier.java` -- recipients resolved once; Web Push path after device tokens.
- `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler` -- `PUSH_ENDPOINT_OWNED` 409.
- `ProdSecretsValidator.java`, `SecurityConfig.java`, `application.yml`, `application-prod.yml` (nothing forced on), `.env.example`, `docs/entitlements/endpoints.md`.
- Tests -- unit: chain (every WhatsApp matrix row, no Meta for `sendReport`, timeout = no fallback, logs without code), Meta request body (components exactly as Bref), presets parsing; `WebPushCrypto` against RFC 8291 Appendix A test vector (decrypting with the vector's receiver keys) and a VAPID JWT verified with the public key; allow-list; subscribe rules; notifier Web Push path (410 deletion, failure isolation); receipt job (batches, per-tenant delete, 24 h cleanup); Postgres tests for the two new repositories; `ProdSecretsValidator` meta cases; existing WhatsApp/OTP/reset tests green.

**Acceptance Criteria:**
- Given `keevo.whatsapp.provider=meta` with Meta secrets, when an OTP is requested for a phone, then the message goes through template `bref_point_otp`, the fallback is used only on failure, and boot with `meta` and missing Meta keys fails fast in prod.
- Given existing providers (`failover` in prod), when daily reports and alerts are sent, then behaviour is unchanged (existing tests green).
- Given the full suite run package by package, then it is green, including `TenantSchemaInterpolationGuardTest` and architecture tests.

## Spec Change Log

## Review Triage Log

### 2026-11-04 — Review pass
- verdicts: 28 findings — high 0, medium 5, low 19, false 3, maybe-false 1 (grouped rows list every reviewer finding they cover)
- findings:
  - `[medium]` `[patch]` (patterns F1) Expo ticket keyset pages rescan the created_at tie group — index changed to (created_at, ticket_id) in unreleased V46.
  - `[medium]` `[patch]` (patterns F2, blind 7, edge 5) Web Push HTTP calls run one by one on the caller's thread (up to 15 s each) inside listeners/schedulers — Web Push path dispatched on the AsyncConfig executor.
  - `[low]` `[reject]` (patterns F3) VAPID JWT re-signed per notification — a few ES256 signatures per event; a cache adds state for no measurable gain.
  - `[low]` `[reject]` (patterns F4) per-tenant SELECT in SalesTrendDetectionScheduler — pre-existing loop, indexed query, now async.
  - `[false]` `[reject]` (patterns F5) unique btree on 2048-char endpoint — reviewer confirms it is valid and needed.
  - `[medium]` `[patch]` (intent 1) invoice half of the criterion not met — `bref_facture` is sent from the owner's WABA in Bref, never the platform number: routed to 21.5 (epic brief + sprint-status), 19.9b brief annotated.
  - `[medium]` `[patch]` (intent 2, blind 2, edge 1, edge 2, edge claim) meta-only setup: chain reports configured but no text provider → every report/alert/invoice throws — `isConfigured()` = text provider configured, `canSendCodes()` for codes, boot WARN in ProdSecretsValidator.
  - `[low]` `[reject]` (intent 3) no context test wiring provider=meta end to end — FullContextIntegrationTest boots the chain (noop); provider units + chain + validator tested.
  - `[false]` `[reject]` (intent 4) out-of-window Meta templates not done — split to 19.9c by design (user validation and Meta approval needed).
  - `[low]` `[patch]` (intent 5, vg 1, vg 2, blind 11) no controller test for Web Push endpoints (public key, 204, 409 mapping) — WebPushControllerTest added.
  - `[false]` `[reject]` (intent 6) « un ordre SQL » vs one DELETE per tenant schema — tokens live in tenant schemas; one statement per schema is the minimum.
  - `[low]` `[reject]` (blind 1) VAPID public/private mismatch not detected — operator misconfiguration visible as WARN on first send; key pair check adds crypto code.
  - `[medium]` `[patch]` (blind 3, edge 7) Wassender/Twilio read timeout on the code fallback maps to FAILED → second code sent — read timeout mapped to UNKNOWN on the code path only.
  - `[low]` `[reject]` (blind 4, edge 9) reset code language hard-coded fr and sent with the sign-in template — reset service has no locale input; Bref uses the same `bref_point_otp` template for every OTP purpose.
  - `[low]` `[reject]` (blind 5, edge 4) payload > 4096 bytes not checked — notifications carry a title, short body and small metadata; unlikely in practice.
  - `[low]` `[reject]` (blind 6) no company reference in Web Push data — the dashboard click routing is not part of this story; payload matches Bref.
  - `[low]` `[reject]` (blind 7b) no cap on subscriptions per user — a user can only slow their own notifications, now delivered async.
  - `[low]` `[reject]` (blind 8) subscriptions not removed on account deletion — a deleted user is never a recipient again; orphan rows die on 404/410.
  - `[low]` `[reject]` (blind 9) admin broadcasts (`sendToTokens`) not sent to browsers — broadcasts target device tokens by design (19.9).
  - `[low]` `[patch]` (blind 10, edge 8) 132001 detected by substring — JSON `error.code` parsed.
  - `[low]` `[reject]` (blind 12) DELETE /subscribe with a JSON body — Bref API parity; the dashboard client is ours.
  - `[low]` `[reject]` (blind 13) ticket created_at uses Instant.now() — seconds apart from the Clock; windows are 15 min / 24 h.
  - `[low]` `[patch]` (edge 3) invalid p256dh/auth stored forever — validated at subscribe (P-256 point, 16-byte auth) → VALIDATION_FAILED.
  - `[low]` `[reject]` (edge 6) persistent receipt failure on the oldest batch blocks later batches — only on a persistent Expo 4xx (misconfiguration); the 24 h purge bounds it.
  - `[maybe-false]` `[patch]` (edge 10) owner lookup moved out of REQUIRES_NEW could mark the caller's transaction rollback-only — lookup put back inside requiresNew (trivial, restores the 19.9 behaviour).
  - `[low]` `[reject]` (edge 11) owners resolved twice (inbox/Web Push vs findOwnerTokens) — device token query was already owner-scoped in 19.9; sets differ only under concurrent role changes.
  - `[low]` `[patch]` (vg 3) ExpoReceiptHttpClient parsing untested — ExpoReceiptHttpClientTest added.
  - `[low]` `[patch]` (vg 4) English WhatsApp code branch untested — RequestOtpServiceTest en-US case added.

## Design Notes

**Patterns.** Chain of Responsibility (`ChainWhatsAppSender` over `WhatsAppProvider`s, built once from a preset — Factory) mirroring 19.9's SMS/email failover; Strategy per provider; the `meta` capability is a provider flag, not a branch in callers. Web Push is a Composite member of the 19.9 push notifier but keyed by user (not by device token), so it is a separate path inside `PersistingPushNotifier`, not a `PushDeliveryChannel`. Receipts: scheduled batch job (Expo's documented pattern).

**Complexity (FUS-NFR11).** WhatsApp code: ≤1 Meta call (+1 on 132001) + ≤2 fallback calls. Receipts job: per run ⌈n/1000⌉ receipt calls, 1 SELECT batch (index on `created_at`), 1 `deleteByTokens` per distinct tenant schema in the batch (bounded by tenants that actually had dead tokens), 1 DELETE of processed tickets; ticket insert = 1 batch insert per Expo chunk. Web Push send: 1 query for all recipients' subscriptions, 1 HTTP call per subscription (bounded by a user's browsers), 1 DELETE for dead ones; subscribe = 1 SELECT + 1 upsert on the unique endpoint index.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='*WhatsApp*Test,Meta*Test,WebPush*Test,ExpoReceipt*Test,ExpoPush*Test,PersistingPushNotifier*Test,ProdSecretsValidator*Test,RequestOtpService*Test,RequestPasswordReset*Test'` -- expected: green.
- Full suite package by package (scratchpad `run-pkgs-wait.sh`, ≥4 GB free) -- expected: 0 failures.

**Manual checks:**
- Local backend (WhatsApp noop, FCM/Expo off, no VAPID): `GET /api/v1/web-push/vapid-public-key` → `{"publicKey":""}`; with a generated VAPID pair: key returned, subscribe 204, subscribe of a `http://` endpoint 422, delete 204; OTP request still 200.

## Auto Run Result

Status: done

**Summary.** Platform WhatsApp is a Chain of Responsibility over providers (`ChainWhatsAppSender`): preset `meta` = Meta → Wassender → Twilio for sign-in/reset codes (Bref template `bref_point_otp`, fr→en on Meta error 132001, read timeout = unknown outcome, no second code), free text always Wassender → Twilio. Expo tickets stored (`public.expo_push_tickets`, V46) and their receipts read every 15 min (`ExpoReceiptJob`, ShedLock), `DeviceNotRegistered` tokens deleted per tenant schema. Web Push ported from Bref with JDK crypto only (RFC 8291 aes128gcm + RFC 8292 VAPID, RFC test vector reproduced), `public.web_push_subscriptions` (V47), `GET /api/v1/web-push/vapid-public-key`, `POST/DELETE /api/v1/web-push/subscribe`, delivered asynchronously from `PersistingPushNotifier` to the recipients' browsers, rows deleted on 404/410. Bref env names reused (`WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `VAPID_*`) so Bref keys and browser subscriptions stay valid.

**Files.** New: `messaging/whatsapp/{domain/port/out/WhatsAppProvider, application/ChainWhatsAppSender, adapter/out/config/WhatsAppChainConfig, adapter/out/external/MetaWhatsAppProvider}`; `messaging/notification/{application/service/ExpoReceiptJob, WebPushSubscriptionService, adapter/out/ExpoReceiptHttpClient, WebPushSender, WebPushCrypto, persistence JDBC repositories, adapter/in/rest/WebPushController + DTOs, domain/model/PushKeys, PushEndpoints}`; migrations V46, V47. Changed: Wassender/Twilio/noop adapters → providers (old per-value conditional adapters and `FailoverWhatsAppAdapter` removed), `WhatsAppSender` (`sendCode`, `canSendCodes`), `RequestOtpService`, `RequestPasswordResetService`, `ExpoPushAdapter`, `PersistingPushNotifier`, `ProdSecretsValidator`, `SecurityConfig`, error codes (`PUSH_ENDPOINT_OWNED` 409), `application.yml`, `.env.example`, `docs/entitlements/endpoints.md`.

**Review.** 40 reviewer findings in 28 entries (5 layers): 0 high, 5 medium, 19 low, 3 false, 1 maybe-false. Patched: 5 medium (keyset index, async Web Push, meta-only `isConfigured`/`canSendCodes` + boot WARN, no duplicate code on Wassender/Twilio timeout, `bref_facture` routed) and 6 low/maybe-false (JSON 132001, key validation, owner lookup back in REQUIRES_NEW, 3 test gaps). Deferred: 1 → 21.5. Split before implementation: Meta templates for Keevo free-text messages → 19-9c (user validation + Meta approval). Rejected: 16, reasons in the triage log.

**Follow-up review: recommended (true)** — 5 medium patches; unverified risk: the WhatsApp chain has never talked to the real Meta Graph API (template parameters and 132001 handling only against mocks), and Web Push only against the RFC vector, not a real browser push service.

**Verification.** Targeted tests green. Full suite package by package: 5 361 tests, 0 failures after one fix (`TenantSchemaInterpolationGuardTest` flagged the hand-built VAPID JSON — rebuilt with Jackson). Live `keevo_dev` (WhatsApp noop, FCM/Expo off, generated VAPID pair): public key 200; anonymous subscribe 401; subscribe 204 (row stored); `http://` endpoint 422; invalid `p256dh` 422; delete 204 (row gone); OTP request still 200; V46/V47 indexes present.

**Residual risks.** Real Meta and browser deliveries untested until keys exist (end-of-epic device test). With `meta` and no Wassender/Twilio keys, free-text WhatsApp is off (boot WARN).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit cb99ea66). Conventions : spec-16-11 ; synchro : spec-16-10.

**Ce qui est fait côté API :** codes de connexion WhatsApp par le modèle Meta de Bref (rien à faire côté app) ; nettoyage des jetons Expo désinscrits ; notifications navigateur (Web Push) pour la version web de l'app, avec les mêmes clés VAPID que Bref.

**À utiliser (version web uniquement) :**
1. `GET /api/v1/web-push/vapid-public-key` (sans authentification) → `200 {"publicKey":"B…"}` ; chaîne vide = Web Push indisponible, ne rien proposer.
2. `POST /api/v1/web-push/subscribe` (authentifié) body `{"endpoint","p256dh","auth","userAgent"?}` (valeurs de `PushSubscription.toJSON()`, clés en base64url) → `204`. Erreurs : 422 `VALIDATION_FAILED` « Données invalides » (adresse de service push non reconnue ou clés invalides) ; 409 `PUSH_ENDPOINT_OWNED` « Cet appareil est déjà lié à un autre compte » (se désabonner puis réessayer après reconnexion) ; 401 non connecté.
3. `DELETE /api/v1/web-push/subscribe` (authentifié) body `{"endpoint"}` → `204` (à la déconnexion).
4. Notification reçue par le service worker : JSON `{title, body, icon:"/icon.svg", badge:"/favicon-32x32.png", data}` (`data` = métadonnées et lien profond de la notification, comme le push Expo).

Code backend : `keevo/backend/src/main/java/com/keevo/messaging/notification/adapter/in/rest/WebPushController.java`, `application/service/WebPushSubscriptionService.java`, `adapter/out/WebPushSender.java`.

**À implémenter côté mobile :**
1. Web uniquement : service worker qui affiche `title`/`body` et ouvre le lien de `data` au clic (reprendre celui de Bref).
2. Après connexion, si `publicKey` non vide et permission accordée : `pushManager.subscribe({userVisibleOnly:true, applicationServerKey})` puis `POST /subscribe` ; à la déconnexion `DELETE /subscribe`.
3. Natif : rien de nouveau (jeton Expo via `/devices/token`, 19.9).
4. Libellés français, aucune donnée technique à l'écran.

**Implémentation de référence :** Bref web (`AI/bref`, enregistrement Web Push existant).

**Critères d'acceptation mobile / scénarios de test :**
- Given la version web connectée avec la permission accordée, When une alerte de stock est émise pour l'owner, Then une notification navigateur s'affiche et le clic ouvre l'écran concerné.
- Given la déconnexion, Then l'abonnement est supprimé (plus de notification).
- Mise en place : générer une paire VAPID, lancer le backend avec `VAPID_PUBLIC_KEY`/`VAPID_PRIVATE_KEY`, `curl localhost:4500/api/v1/web-push/vapid-public-key`.

**Clé de suivi mobile :** `m19-9b-canaux-bref-whatsapp-meta-web-push`.

