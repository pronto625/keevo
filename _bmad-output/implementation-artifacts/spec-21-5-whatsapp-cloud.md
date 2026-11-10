---
title: '21-5 — Module whatsapp-cloud: owner WABA account, Meta webhook, inbox, message templates, invoices by bref_facture'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: '50c4cfa0289edffb425dd463f35f7c268e475b3b'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-9b-canaux-bref-whatsapp-meta-web-push.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-8-envoi-sms-email.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A BUSINESS owner coming from Bref loses his WhatsApp Business inbox: Keevo cannot hold a tenant's own WhatsApp Business account (WABA), receive Meta's webhook, show and answer conversations, manage message templates, nor send invoices / quotes from the owner's number with Meta template `bref_facture` (today `WhatsAppInvoiceChannel` sends a signed link as free text from the platform number — item routed from the 19.9b review).

**Approach:** New paid tenant module `whatsapp-cloud` (package `com.keevo.whatsappcloud`, depends on `shared` only) ported from api-bref `src/whatsapp/*` + `customer-campaign/owner-template.service.ts`: one WABA account per tenant in `public.whatsapp_accounts` (access token AES-256-GCM encrypted at rest, FUS-NFR4), one JWT-free Meta webhook `GET|POST /api/v1/whatsapp/webhook` verified by `X-Hub-Signature-256` and routed to the tenant by indexed `phone_number_id` (statuses too; template reviews by `waba_id`), inbound messages stored idempotently in the tenant schema and pushed to the owners (inbox row + push), free-text replies inside the 24 h window, approved-template sends outside it, owner templates with Meta review follow-up; Meta Graph API behind a port (real `meta` / `fake` / `disabled`). Invoicing's WhatsApp channel sends through the tenant's WABA (`bref_facture`, PDF header) when usable, platform free text otherwise, via a new shared capability port.

## Boundaries & Constraints

**Always:**
- Offer as data: V68 grants `whatsapp-cloud` to `BUSINESS`, `BUSINESS_TRIAL`, `PREMIUM`, `PREMIUM_TRIAL` (BUSINESS per brief; the archived complete plans hold every paid module) and owner-disables it for existing tenants; tenant DDL only in `db/modules/whatsapp-cloud/V1__whatsapp_cloud.sql`; `ModuleIdsAtGoLivePinTest` + `db/modules/README.md` updated.
- Roles: account status `GET /api/v1/whatsapp/account` every member role (the app enables the WhatsApp invoice choice from it, Bref `shops/:id/status`); connect / disconnect, inbox, templates: OWNER only (Bref `role === 'owner'`). Module gate `@RequiresEntitlement("whatsapp-cloud")` (GET → READ, others WRITE) on every endpoint except the webhook (`@EntitlementExempt`) and `DELETE /account` (an owner can always disconnect, like revoking an MCP token).
- Token: never returned, never logged, encrypted with AES-256-GCM (random 96-bit IV, AAD = tenant id) by `shared.crypto.SecretCipher`, key `keevo.secrets.encryption-key` (base64, 32 bytes, env `KEEVO_SECRETS_ENCRYPTION_KEY`). No key → connect 503 `WHATSAPP_CLOUD_UNAVAILABLE`. Decrypted only in memory for a Graph call.
- Webhook: POST refused 403 (no processing) unless `X-Hub-Signature-256 = sha256=HMAC_SHA256(appSecret, raw body)` matches in constant time; no app secret configured → every POST refused. GET answers `hub.challenge` as text/plain only for `hub.mode=subscribe` + constant-time `hub.verify_token` match, else 403. Body > 1 MB → 413. Unknown `phone_number_id` / `waba_id` (platform number, owner still on api-bref, deleted company, module not enabled) → ignored, still 200. Processing failure → 500 so Meta redelivers; redelivery is harmless (idempotent on Meta message id, monotone statuses).
- Routing is set-based per delivery: one indexed `= ANY(?)` lookup of all `phone_number_id`s (and one of `waba_id`s), then per tenant one batched insert (`ON CONFLICT (wa_message_id) DO NOTHING`), one conversation upsert per contact, one set-based status update; push only for messages really inserted, one push per contact per delivery, after commit, on a bounded executor (full → dropped, logged).
- 24 h window: free text only when the contact's last inbound message is < 24 h old (`WHATSAPP_REPLY_WINDOW_CLOSED` 422 otherwise); a template send needs an APPROVED template of the tenant and exactly its body variable count.
- Templates: name `^[a-z0-9_]{1,512}$`, category MARKETING | UTILITY, language `^[a-z]{2}(_[A-Z]{2})?$`, header NONE | TEXT (≤ 60) | IMAGE | VIDEO | DOCUMENT (media header needs a `headerHandle` from `POST /my-templates/media`, connected account only), body ≤ 1024 with one example per `{{n}}`, footer ≤ 60, ≤ 100 templates per tenant, unique (name, language). Created as DRAFT when not connected, submitted to Meta at creation when connected and at connection for drafts; Meta failure keeps DRAFT + French `submitError`. Status follows Meta (PENDING → APPROVED | REJECTED | PAUSED | DISABLED) by webhook `message_template_status_update` and by a refresh on list (≤ 1 Graph call / tenant / minute, only when something is PENDING).
- System template `bref_facture` (Bref `INVOICE_TEMPLATE`, exact text, DOCUMENT header, sample PDF) created on the owner's WABA at connection (best effort, already-existing = synced), stored as `system = true`, never listed among owner templates.
- Invoice / quote by WhatsApp: WABA path when module enabled + account CONNECTED + `bref_facture` APPROVED → PDF rendered as `GET /pdf`, uploaded as media, template sent with header document + body {client, « facture » | « devis », reference, shop}, outbound row in the inbox; not usable or certain WABA failure → platform free text (unchanged 14.2 text); WABA unknown outcome (timeout) → `INVOICE_SEND_UNCERTAIN`, no fallback (no double send). Channel available when the WABA path is usable OR the platform sender is configured.
- Additive only for V1 2.0.1 apps (new endpoints / module / tables; invoice send response unchanged); French messages; no UUID, raw code or token in user-facing text (contact phone digits are user data).

**Never:** a real Meta call in tests or the live check (fake adapter); the platform number sending an owner's template or reply; storing / logging a clear token or the app secret; fetching a header media from a user URL (SSRF — Bref's `headerMediaUrl` is not ported); STOP/START consent handling or campaigns (21.6); the Bref ↔ Keevo dual-delivery router (24.10) or WABA import (24.6); offline sync of the inbox.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Account status | any member, not connected | 200 `{connected:false, displayPhoneNumber:null, verifiedName:null, connectedAt:null}` | — |
| Connect | OWNER, BUSINESS + module enabled, `{code, wabaId, phoneNumberId}` (embedded signup) | token exchanged, number read, app subscribed, row CONNECTED encrypted, `bref_facture` + drafts submitted; 200 status `connected:true` | Graph refusal → 502 `WHATSAPP_CLOUD_META_ERROR` (Meta's user text when given) |
| Connect — number taken | phone_number_id CONNECTED by another tenant | 409 `WHATSAPP_NUMBER_ALREADY_CONNECTED` | nothing stored |
| Connect — not configured | graph `disabled` or no encryption key | 503 `WHATSAPP_CLOUD_UNAVAILABLE` | — |
| Disconnect | OWNER | 204, row deleted (token gone); idempotent | — |
| Webhook verify | `hub.mode=subscribe&hub.verify_token=OK&hub.challenge=123` | 200 text/plain `123` | wrong token → 403 |
| Webhook message | signed POST, text from `2376…` on tenant's phone_number_id | message stored, conversation unread +1, owners pushed « Nouveau message WhatsApp » | — |
| Webhook replay | same Meta message id again | 200, nothing new, no push | — |
| Webhook bad signature | missing / wrong header | 403, nothing processed | — |
| Webhook unknown number | phone_number_id not connected | 200, ignored | — |
| Status update | `statuses[{id, status:read}]` | outbound row status → read; never goes back (read > delivered > sent/failed) | — |
| Template review | `message_template_status_update` APPROVED / REJECTED+reason | tenant template status / reason updated | unknown waba ignored |
| Conversations | OWNER | ≤ 200 newest `{contactPhone, contactName, lastMessage, lastType, lastDirection, lastAt, unreadCount, replyWindowEndsAt}` | — |
| Thread | GET `/conversations/{phone}/messages` | ≤ 200 oldest → newest (keyset `before`), inbound marked read | bad phone → 400 |
| Reply in window | POST `{text}` within 24 h | sent from owner's number, stored, thread returned | Meta failure → 502 |
| Reply out of window | last inbound > 24 h or none | 422 `WHATSAPP_REPLY_WINDOW_CLOSED` | nothing sent |
| Reply not connected | no account | 409 `WHATSAPP_NOT_CONNECTED` | — |
| Template send | POST `/conversations/{phone}/template` `{templateId, variables}` APPROVED | sent, stored with rendered text | not approved → 409 `WHATSAPP_TEMPLATE_NOT_APPROVED`; wrong count → 400 |
| Create template | valid body, not connected | 201 DRAFT | duplicate → 409 `WHATSAPP_TEMPLATE_NAME_TAKEN`; limit → 409 `WHATSAPP_TEMPLATE_LIMIT` |
| Invoice WABA | connected + `bref_facture` APPROVED | template + PDF from owner's number; result SENT as before | uncertain → `INVOICE_SEND_UNCERTAIN` |
| Invoice fallback | not connected / template pending / WABA FAILED | platform free text with link (14.2) | platform not configured and WABA unusable → 503 `WHATSAPP_UNAVAILABLE` |
| Plan without module | FREE / PRO owner | 403 `MODULE_NOT_ENTITLED` on gated endpoints | — |

</intent-contract>

## Code Map

- Bref source (read-only): `api-bref/src/whatsapp/whatsapp.controller.ts` (verify + HMAC), `whatsapp.service.ts` (`sendTemplateMessage`, `sendInvoiceDocument` :324-404, `sendTextMessage`, `handleIncomingMessage` :579, `handleStatusUpdate` :671 STATUS_RANK, `createTemplate` :881, `uploadTemplateHeaderSample` :746, `listTemplates`, `deleteTemplate`, `metaError`), `whatsapp-inbox.service.ts` (conversations, messages+mark read, reply 24 h, unread count), `system-templates.ts` (`INVOICE_TEMPLATE`, `samplePdf`), `whatsapp-template-text.ts`, `customer-campaign/owner-template.service.ts` (DRAFT → submit, sync statuses, provision system templates, remove); app `bref/features/whatsapp-inbox/api/whatsapp-inbox.api.ts`, `bref/features/whatsapp-marketing/api/marketing.api.ts` (response shapes).
- `keevo/backend/src/main/java/com/keevo/messaging/whatsapp/adapter/out/external/MetaWhatsAppProvider.java` -- platform Meta provider (19.9b) to mirror for HTTP style (`channelRestTemplate`, `ProviderHttp.isReadTimeout`, no secret in logs); unchanged.
- `invoicing/invoice/application/service/channel/WhatsAppInvoiceChannel.java` -- WABA path + fallback; `EmailInvoiceChannel` shows `InvoiceDocumentUseCase.pdf(id, schema)`; `InvoiceSendChannel.Message`; `SendInvoiceUseCase.RETRYABLE` (unchanged).
- `shared/capability/notification/` -- new port `TenantWhatsAppBusiness` (implemented by whatsappcloud, consumed by invoicing); `PushNotifier.notifyOwners`, `NotificationPayload`.
- `shared/module/EntitlementService.isEnabled`, `ModuleProvisioner.ensureProvisioned`, `RequiresEntitlement`, `EntitlementExempt`, `KeevoModule`.
- `shared/infrastructure/persistence/TenantContext`, `TenantSchema.validate` -- schema-qualified JDBC as `assistant/conversation/adapter/out/persistence/JdbcAiConversationRepository.java`.
- `shared/infrastructure/security/SecurityConfig.PUBLIC_PATHS` -- add `/api/v1/whatsapp/webhook`.
- `shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/ErrorMessages.java`, `GlobalExceptionHandler.domainCodeToHttpStatus` -- new codes.
- `shared/event/catalog/auth/McpAccessTokenChangedEvent.java` + `AuditEventListener` + `AuditLabels` -- audit template for connect / disconnect.
- Module templates: `mcp/module/McpAccessModule.java`, `mcp/package-info.java`, `resources/keevo/modules/ai-assistant/module.json`, `db/migration/V66__mcp_access.sql`, `db/modules/ai-assistant/V1__ai_conversations.sql`, `db/modules/README.md`, `test/.../architecture/ModuleIdsAtGoLivePinTest.java`, `test/.../architecture/EndpointEntitlementCoverageTest.java` (regenerates `docs/entitlements/endpoints.md`), `keevo/contracts/mcp-access.json` (contract format).

## Tasks & Acceptance

**Execution:**
- `resources/db/migration/V68__whatsapp_cloud.sql` -- `public.whatsapp_accounts` (tenant_id PK FK CASCADE, waba_id, phone_number_id, display_phone_number, verified_name, access_token_enc BYTEA, connected_by, connected_at, updated_at; unique index phone_number_id, index waba_id), profile grants, owner-disable -- O(1) routing.
- `resources/db/modules/whatsapp-cloud/V1__whatsapp_cloud.sql` -- tenant `whatsapp_messages` (unique wa_message_id, index (contact_phone, created_at DESC, id), partial index unread inbound), `whatsapp_conversations` (PK contact_phone, index last_at DESC), `whatsapp_templates` (unique (name, language), index meta_template_id).
- `resources/keevo/modules/whatsapp-cloud/module.json` + `whatsappcloud/module/WhatsAppCloudModule.java` + `whatsappcloud/package-info.java` -- module registration, display name « WhatsApp Business ».
- `shared/crypto/SecretCipher.java` -- AES-256-GCM helper (versioned blob, AAD), unavailable without key; dev/test keys in `application-dev.yml` / test config.
- `whatsappcloud/graph/` -- port `MetaGraphClient` (exchangeCode, phoneNumber, subscribeApp, sendText, sendTemplate, uploadMedia, uploadHeaderSample, createTemplate, listTemplates, deleteTemplate), `HttpMetaGraphClient` (v21.0, `channelRestTemplate`), `FakeMetaGraphClient`, `DisabledMetaGraphClient`, `WhatsAppCloudProperties` + config (mode `disabled` default, `fake` in dev/test, `meta` fail-fast on missing app id / secret / verify token / key).
- `whatsappcloud/account/` -- repository (public table), `WhatsAppAccountService` (status, connect, disconnect, credentials), controller `/api/v1/whatsapp/account`, audit event.
- `whatsappcloud/webhook/` -- controller (GET verify, POST signed, size cap), `MetaWebhookSignature`, `WhatsAppWebhookService` (parse, set-based routing, per-tenant transaction, push after commit on bounded executor).
- `whatsappcloud/inbox/` -- repository (tenant tables), `WhatsAppInboxService` (conversations, thread, unread count, reply, template send), controller `/api/v1/whatsapp/conversations`.
- `whatsappcloud/template/` -- repository, `WhatsAppTemplateService` (list + throttled refresh, create, submit, submit drafts, provision `bref_facture`, delete, media upload, status from webhook), `TemplateText` (variables, render), controller `/api/v1/whatsapp/my-templates`.
- `whatsappcloud/invoice/WhatsAppBusinessDocumentSender.java` + `shared/capability/notification/TenantWhatsAppBusiness.java` -- invoice document by `bref_facture`; `WhatsAppInvoiceChannel` uses it with fallback.
- `ErrorCode` / `ErrorMessages` / `GlobalExceptionHandler` / `SecurityConfig` / `AuditEventListener` / `AuditLabels` -- new codes, public path, audit labels.
- `keevo/contracts/whatsapp-cloud.json`, `db/modules/README.md`, `ModuleIdsAtGoLivePinTest`, `docs/entitlements/endpoints.md` (regenerated) -- docs & pins.
- Tests: `SecretCipherTest`, `MetaWebhookSignatureTest`, `TemplateTextTest`, `WhatsAppWebhookParsingTest`, `WhatsAppCloudGraphConfigTest` / full-context `WhatsAppCloudFullContextTest` (connect, signed webhook routing + idempotency + status rank + template review, inbox reply window, template lifecycle, isolation between two tenants, FREE refused, employee refused), `WhatsAppInvoiceChannelTest` (WABA, fallback, uncertain).

**Acceptance Criteria:**
- Given two tenants with connected WABAs, when one signed delivery carries messages for both phone_number_ids, then each message lands only in its own tenant's inbox, in one routing query, and each tenant's owners get one push per contact.
- Given the stored account row, when it is read from the database, then `access_token_enc` is not the clear token and decrypts only with the configured key and the same tenant id.
- Given a connected tenant whose `bref_facture` is APPROVED, when an invoice is sent by WhatsApp, then the fake Graph receives a media upload and a `bref_facture` template from the tenant's phone_number_id, and the platform `WhatsAppSender` is not called.
- Given the module is not granted (FREE), when the owner calls any gated endpoint, then 403 `MODULE_NOT_ENTITLED` and invoice sending keeps the platform free-text path.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 50 findings — high 0, medium 17, low 28, false 5, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) Bref app paths `/customer-campaigns/whatsapp-account` and `/whatsapp/shops/{shopId}/status` not served — Keevo has one account per company (no per-shop account): `GET /api/v1/whatsapp/account` is open to every member and replaces both; the RN app is rebuilt against `keevo/contracts/whatsapp-cloud.json` (mapping in the Mobile section).
  - `[low]` `[patch]` (intent) the owner push is not asserted — `webhookRoutesByNumber` now waits for the `WHATSAPP_MESSAGE_RECEIVED` inbox row and checks its French title / body.
  - `[low]` `[reject]` (intent) quotes not exercised — the channel passes `isQuote()` to a one-line label (« devis »); the body rendering is unit-tested; no branch to cover.
  - `[low]` `[reject]` (intent) invoice test approves bref_facture by SQL — the review webhook is covered in `templateLifecycle`; the invoice path only reads the stored status.
  - `[low]` `[reject]` (intent) Graph wire format checked only against the fake — no real Meta call is allowed (intent); component layout unit-tested (`WhatsAppCloudGraphConfigTest.components`).
  - `[false]` `[reject]` (intent) scope beyond the literal intent (public table, uncertain path, media upload, lazy provisioning) — required by routing by number across tenants and by the no-double-send rule; not a defect.
  - `[false]` `[reject]` (intent) R4 app outcome — backend-only repo by project rule.
  - `[medium]` `[patch]` (NFR11 M1) thread keyset skips messages sharing a Meta second — `GET …/messages` takes `beforeId`; repository uses `(created_at, id) < (?, ?)` on the index order; test `threadKeyset` (205 messages in one second).
  - `[medium]` `[patch]` (NFR11 M2) connection makes up to ~100 Meta calls inside the request — `onConnectedAsync` on a bounded 2-thread pool (queue 100, caller-runs when full); tests await the submissions.
  - `[low]` `[patch]` (NFR11 M3) refresh rescans the templates table — Meta's list passed as is to the set-based `applyReviews` (deduped by name + language).
  - `[low]` `[reject]` (NFR11 L1) OR join in `applyReviews` — ≤ 100 rows per company, reviews deduped per name + language before the statement.
  - `[low]` `[reject]` (NFR11 L2) invoice send reads the account up to 3 times — three PK reads on a send that renders a PDF and makes 2 Graph calls; not worth a new resolver API.
  - `[low]` `[reject]` (NFR11 L3) one owner lookup per contact push — Meta sends one message per delivery in practice.
  - `[low]` `[patch]` (NFR11 L4) notifier pool effectively single-threaded — core = max = 2 with core time-out.
  - `[false]` `[reject]` (NFR11 L5) throttle cache invalidation — reviewer itself confirms it is correct (expiry 1 min, invalidated at connection; disconnect makes refresh a no-op).
  - `[medium]` `[patch]` (VG 1) use-case availability with only the company number — invoice test sets the platform sender unconfigured and checks the send from the owner's number.
  - `[medium]` `[patch]` (VG 2) uncertain / refused mapping of the document sender untested — invoice test sends to `+237998…` (409 `INVOICE_SEND_UNCERTAIN`, no platform text) and `+237999…` (platform fallback); fake hooks now accept the 237 prefix.
  - `[medium]` `[patch]` (VG 3) inbox `WHATSAPP_SEND_UNCERTAIN` never exercised — `replyAndStatuses` replies to a `237998…` contact: 409, no OUT row.
  - `[low]` `[patch]` (VG other) `allMatch` on a possibly empty upload list — `hasSize(1)` added.
  - `[medium]` `[patch]` (blind 1) a deleted company keeps its number blocked — connect deletes the account of the number when its company is DELETED (`deleteOfDeletedTenants`); test `numberOfDeletedCompanyIsFree`. The encrypted token of a deleted company at rest → routed to 16-6.
  - `[medium]` `[patch]` (blind 2) reconnecting another WABA keeps stale template statuses — `Connection.otherWaba` → `resetForNewAccount` (all templates DRAFT, Meta ids cleared) then resubmission; invoice test checks bref_facture back to PENDING and the platform fallback.
  - `[low]` `[reject]` (blind 3) disconnect does not unsubscribe the app at Meta — deliveries for the number are then ignored (no account), the owner can remove the app in WhatsApp Manager; a new port call is not worth it.
  - `[low]` `[patch]` (blind 4) uncertain message says « vérifiez la conversation » though nothing is stored — message now « WhatsApp n'a pas répondu à temps : le message est peut-être parti. Attendez quelques minutes avant de le renvoyer. »
  - `[low]` `[reject]` (blind 5) a status may arrive before the OUT row commits — rows are inserted SENT; delivered / read come seconds later and apply; only an early « sent » is lost, which changes nothing.
  - `[low]` `[reject]` (blind 6) connection reset after write treated as certain failure — same rule as the platform providers (`ProviderHttp.isReadTimeout`); a rare double send of a document is tolerable.
  - `[medium]` `[patch]` (blind 7) template delete leaves other languages / never deletes when gone at Meta — owner rows of that name deleted in every language; a refused delete of a name Meta no longer lists is a delete.
  - `[low]` `[patch]` (blind 8) `applyReviews` contract vs SQL — never-submitted DRAFTs now match by Meta id only (system templates adopted by name); Javadoc rewritten.
  - `[low]` `[reject]` (blind 9) Graph paging ignored — ≤ 200 templates / 100 numbers per call covers every Bref account; paging adds loops for no observed case.
  - `[low]` `[reject]` (blind 10) app secret in the code-exchange URL — Meta's documented form; Keevo never logs URLs nor exception messages of Graph calls (operation + status only).
  - `[false]` `[reject]` (blind 11) messages dropped while not fully enabled — `isEnabled` is true in the read-only grace (stored); only an owner who disabled the module stops the inbox, by choice.
  - `[low]` `[reject]` (blind 12) media upload trusts Content-Type — Meta validates the media; the file only goes to the owner's own WABA.
  - `[low]` `[patch]` (blind 13) misleading fallback log — logged after the platform check, with a distinct line when there is no platform.
  - `[low]` `[reject]` (blind 14) phone bounds differ (DB 6–20 vs API 8–15) — the DB bound is wider on purpose (Meta numbers); every write goes through `ContactPhone`.
  - `[low]` `[patch]` (blind 15) `markRead` two updates outside a transaction / template cap race — `markRead` is now one transaction recomputing the count from the unread index; the 100-template cap race is rejected (two concurrent creates by one owner, one extra row).
  - `[medium]` `[patch]` (blind 16) missing tests for risky paths — added: reconnect to another WABA, inbox unknown outcome, deleted company's number, keyset paging; `isReadTimeout` and early status rejected with rows 5 and 6.
  - `[medium]` `[patch]` (edge 1) Meta 5xx treated as certain failure — 5xx is now `UNKNOWN` (no fallback on documents, 409 on inbox sends).
  - `[medium]` `[patch]` (edge 2) unreadable 2xx escapes as a raw exception — any other `RestClientException` is `UNKNOWN`.
  - `[low]` `[patch]` (edge 3) empty create-template answer → NPE — `UNKNOWN` (template stays DRAFT with a note).
  - `[medium]` `[patch]` (edge 4) reconnect to another WABA — same root cause and fix as blind 2.
  - `[medium]` `[patch]` (edge 5) a same-name Meta template turns a local DRAFT APPROVED — same fix as blind 8 (DRAFT matched by Meta id unless system).
  - `[medium]` `[patch]` (edge 6) Meta delete removes every language — same fix as blind 7.
  - `[low]` `[patch]` (edge 7) TEXT header with a variable — refused in French (Keevo sends no header parameter).
  - `[low]` `[reject]` (edge 8) concurrent creates exceed 100 templates — see blind 15.
  - `[low]` `[patch]` (edge 9) unread count drift — same fix as blind 15.
  - `[medium]` `[patch]` (edge 10) keyset — same fix as NFR11 M1.
  - `[medium]` `[patch]` (edge 11) number of a deleted company — same fix as blind 1.
  - `[low]` `[patch]` (edge 12) audit failure after the upsert → 500 — the audit publication is caught and logged.
  - `[medium]` `[patch]` (edge 13) bref_facture stays PENDING when the review webhook was missed — `invoiceTemplateApproved` refreshes from Meta (throttled once a minute per company) while it is PENDING.
  - `[low]` `[patch]` (edge 14) channel race: company number no longer usable and no platform → `WHATSAPP_UNAVAILABLE` before `sendReport`.
  - `[false]` `[reject]` (edge 15) not built on `channelRestTemplate` — that bean is internal to messaging and the module may depend on `shared` only (Modulith); same timeouts as the platform providers, built locally.

## Design Notes

- **Patterns.** Hexagonal port + Strategy for Meta Graph (`meta` / `fake` / `disabled`, chosen once at startup, fail-fast) as 21.1's provider port; Adapter/Facade `TenantWhatsAppBusiness` in `shared.capability` so invoicing never depends on the module (Modulith `shared` only); idempotent consumer keyed by Meta message id (unique index, `ON CONFLICT DO NOTHING`) instead of Bref's in-memory dedupe map (cluster-safe); monotone state machine for message status (rank guard in SQL); denormalized read model `whatsapp_conversations` maintained on write (CQRS-lite) instead of Bref's correlated subqueries per list; Template Method kept in `SendInvoiceUseCase`, only the WhatsApp strategy changes.
- **Costs.** Webhook delivery with M messages / S statuses for T tenants: 1 HMAC over the body, 1 account lookup (`phone_number_id = ANY`, unique index), per tenant 1 cached entitlement + 1 warm provisioning check, 1 batched insert (M rows, one round trip), ≤ distinct-contacts upserts by PK, 1 status UPDATE … FROM unnest (index wa_message_id) (conversations carry no status); push after commit, ≤ distinct contacts. Conversation list: index scan `last_at DESC LIMIT 200`, O(page). Thread: index (contact_phone, created_at DESC) LIMIT 200 + 1 UPDATE on the partial unread index. Unread count: SUM over the conversations table (O(contacts), bounded by business size; no message scan). Reply: 1 PK conversation read + 1 Graph call + 1 insert + 1 upsert. Template list: 1 index scan + at most 1 Graph list per minute per tenant (Caffeine timestamp, bounded 10 000 tenants) then 1 batched update. Invoice WABA check: 1 PK read on `whatsapp_accounts` + cached entitlement + 1 indexed template read.
- **Fallback decision (recorded).** Brief: « WABA when connected, platform free text as fallback ». Fallback also on a certain WABA failure (Meta refused) so the client still gets the document; never on an uncertain outcome. `bref_facture` must be APPROVED (Meta reviews UTILITY templates in minutes to hours); until then the platform path is used.
- **Account connection (decision).** Bref has no connect endpoint (rows written after Meta's embedded signup outside the API). Keevo: `POST /account` takes the embedded-signup result `{code, wabaId, phoneNumberId}`, exchanges the code with the app id / secret (`oauth/access_token`), checks the number belongs to the WABA, reads its display number / verified name, subscribes the app to the WABA. One account per tenant (PK tenant_id); a number connected elsewhere → 409.
- **Implementation notes.** Template CHECK uses `name ~ '^[a-z0-9_]+$' AND length(name) <= 512` (PostgreSQL regex repetition is capped at 255). `InvoiceSendChannel` gains a default `assertAvailable(String tenantSchema)` (the WhatsApp availability now depends on the company); `SendInvoiceUseCase` calls it. The bref_facture footer stays Bref's (« Envoyé avec Bref Point ») so accounts migrated from Bref keep their approved template. Inbound STOP / START (marketing consent) and media-header template sends belong to campaigns (21.6).
- **Media headers.** Bref's owner templates fetch `headerMediaUrl` server-side (SSRF surface). Keevo takes an uploaded file (`multipart file`, JPEG/PNG ≤ 5 MB, MP4 / PDF ≤ 10 MB — the API's multipart limit) and returns Meta's `headerHandle`; requires a connected account.

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- expected: BUILD SUCCESS
- `wt-pkgs.sh keevo/backend 21-5 com.keevo.whatsappcloud` then `com.keevo.invoicing`, `com.keevo.messaging`, `com.keevo.architecture`, `com.keevo.admin`, `com.keevo.shared` -- expected: all green
- `-Dkeevo.entitlements.regenerate=true` on `EndpointEntitlementCoverageTest` -- expected: `docs/entitlements/endpoints.md` lists the new endpoints

**Manual checks:**
- Live (local backend, profile dev, WhatsApp noop, FCM off, graph fake): a freshly registered test company (no existing tenant touched), signed curl webhook (message, replay, status, template review, bad signature), conversations / reply / template, invoice send via WABA.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `e23afd2a`, clé `m21-5-whatsapp-cloud` dans `sprint-status.yaml`). Contrat : `keevo/contracts/whatsapp-cloud.json`. Conventions d'erreur et d'affichage : spec-16-11 ; protocole de synchro : spec-16-10 (rien à synchroniser : messagerie et modèles en ligne uniquement).

**Ce qui est fait côté API :** module payant `whatsapp-cloud` « WhatsApp Business » (offres BUSINESS, essai BUSINESS, PREMIUM ; désactivé par défaut pour les entreprises existantes : « Mes modules »). L'owner connecte le numéro WhatsApp Business de l'entreprise (inscription intégrée de Meta), reçoit les messages de ses clients (notification « Nouveau message WhatsApp »), y répond dans les 24 h suivant le dernier message du client, relance avec un modèle approuvé au-delà, gère ses modèles (validation Meta suivie). Les factures et devis envoyés par WhatsApp (`POST /api/v1/invoices/{id}/send`, inchangé) partent du numéro de l'entreprise avec le PDF quand il est connecté et que le modèle facture est approuvé ; sinon le texte avec le lien, comme avant.

**À utiliser** (code : `keevo/backend/src/main/java/com/keevo/whatsappcloud/**/adapter/in/rest/*Controller.java`) — correspondance Bref → Keevo :
- `GET /v1/customer-campaigns/whatsapp-account` et `GET /v1/whatsapp/shops/{shopId}/status` → `GET /api/v1/whatsapp/account` (tous les rôles) `{data:{connected, displayPhoneNumber, verifiedName, connectedAt}}` — un seul numéro par entreprise (plus de statut par boutique).
- Connexion (nouveau, owner) : `POST /api/v1/whatsapp/account` `{code, wabaId, phoneNumberId}` (résultat de l'inscription intégrée Meta : `FB.login` → `authResponse.code` ; message `WA_EMBEDDED_SIGNUP` → `waba_id`, `phone_number_id`). Erreurs : 409 `WHATSAPP_NUMBER_ALREADY_CONNECTED` « Ce numéro WhatsApp est déjà connecté à une autre entreprise » ; 502 `WHATSAPP_CLOUD_META_ERROR` (message de Meta en clair, proposer « Recommencer ») ; 503 `WHATSAPP_CLOUD_UNAVAILABLE` « WhatsApp Business n'est pas disponible pour le moment ». Déconnexion : `DELETE /api/v1/whatsapp/account` → 204.
- `GET /v1/whatsapp/conversations` → `GET /api/v1/whatsapp/conversations` (mêmes champs que Bref : `contactPhone, contactName, lastMessage, lastType, lastDirection in|out, lastAt, unreadCount, replyWindowEndsAt`).
- `GET …/unread-count` → `GET /api/v1/whatsapp/conversations/unread-count` `{data:{count}}`.
- `GET …/{phone}/messages` → `GET /api/v1/whatsapp/conversations/{phone}/messages[?before=<createdAt>&beforeId=<id>]` (200 derniers, du plus ancien au plus récent ; page précédente = `createdAt` et `id` du plus ancien affiché) ; les messages reçus passent en lus.
- `POST …/{phone}/messages {text}` → identique sous `/api/v1`. Erreurs : 422 `WHATSAPP_REPLY_WINDOW_CLOSED` « Ce client ne vous a pas écrit depuis plus de 24 h : relancez-le avec un modèle de message approuvé. » → proposer les modèles ; 409 `WHATSAPP_SEND_UNCERTAIN` « WhatsApp n'a pas répondu à temps : le message est peut-être parti. Attendez quelques minutes avant de le renvoyer. » → ne pas renvoyer automatiquement ; 409 `WHATSAPP_NOT_CONNECTED` ; 502 `WHATSAPP_CLOUD_META_ERROR`.
- Nouveau : `POST /api/v1/whatsapp/conversations/{phone}/template` `{templateId, variables[]}` (modèle approuvé sans en-tête média) ; 409 `WHATSAPP_TEMPLATE_NOT_APPROVED`, 400 (nombre de valeurs, message français).
- `GET|POST /v1/whatsapp/my-templates`, `DELETE …/{id}` → identiques sous `/api/v1` (forme Bref + `statusLabel`). `headerMediaUrl` n'existe plus : déposer d'abord le média par `POST /api/v1/whatsapp/my-templates/media` (multipart `file`, JPEG/PNG ≤ 5 Mo, MP4/PDF ≤ 10 Mo, numéro connecté) → `{data:{headerHandle}}`, puis l'envoyer dans `headerHandle`. Erreurs : 409 `WHATSAPP_TEMPLATE_NAME_TAKEN`, 409 `WHATSAPP_TEMPLATE_LIMIT`, 404 `WHATSAPP_TEMPLATE_NOT_FOUND`, 400 (message à afficher tel quel).
- Module absent : 403 `MODULE_NOT_ENTITLED` (carte d'offre) / `MODULE_DISABLED_BY_OWNER` (renvoi vers « Mes modules »). Autres rôles que l'owner : 403 `FORBIDDEN` sur la messagerie et les modèles.
- Notification : type `WHATSAPP_MESSAGE_RECEIVED`, `deepLink` `/whatsapp/conversations/<contactPhone>`, `metadata.contactPhone`.

**À implémenter côté mobile :**
1. Paramètres › « WhatsApp Business » (owner) : statut, bouton « Connecter » (inscription intégrée Meta dans une vue web, puis `POST /account`), « Déconnecter » avec confirmation.
2. Écran d'envoi de facture : libellé du choix WhatsApp selon `GET /account` (« depuis votre numéro » quand connecté) ; rien d'autre ne change.
3. Messagerie (owner) : liste, badge non lus, fil (chargement des anciens par `before` + `beforeId`), réponse ; hors fenêtre de 24 h (`replyWindowEndsAt` passé ou nul) : champ libre désactivé et choix d'un modèle approuvé avec ses valeurs.
4. Modèles : liste avec `statusLabel` (jamais `status`), motif de refus, erreur de soumission ; création (en-tête texte sans variable, ou média déposé d'abord) ; suppression avec confirmation.
5. Ouverture de la notification → conversation du `contactPhone`.
6. En ligne uniquement ; jamais d'identifiant (id, handle, ids Meta) à l'écran ; numéros affichés au format international.

**Implémentation de référence :** aucune dans `keevo/app` ; écrans Bref `bref/features/whatsapp-inbox`, `bref/features/whatsapp-marketing` (chemins à préfixer par `/api`).

**Critères d'acceptation mobile / scénarios de test :**
- Given un owner BUSINESS connecté, When un client écrit (curl local : POST signé `X-Hub-Signature-256` sur `/api/v1/whatsapp/webhook`, secret dev `dev-whatsapp-app-secret`), Then la notification arrive et la conversation affiche 1 non lu.
- Given une conversation de moins de 24 h, When l'owner répond, Then le message s'affiche « envoyé » ; Given plus de 24 h, Then le champ libre est remplacé par le choix d'un modèle.
- Given un modèle créé avant la connexion, When l'owner connecte son numéro, Then il passe « En cours de validation » puis « Approuvé ».
- Given le modèle facture approuvé, When une facture est envoyée par WhatsApp, Then le client reçoit le PDF depuis le numéro de la boutique.

**Clé de suivi mobile :** `m21-5-whatsapp-cloud`.

## Dashboard super-admin (autre dépôt)

Aucune nouvelle route super-admin : `whatsapp-cloud` est une ligne de plus du catalogue d'offres (21.10, `profile_modules`) et des modules d'une entreprise ; la messagerie du numéro plateforme est routée vers 21.6. Pas de clé `d21-5`.

## Auto Run Result

Status: done

**Summary.** (commit `e23afd2a`) Module payant `whatsapp-cloud` « WhatsApp Business » (package `com.keevo.whatsappcloud`, dépend de `shared` seulement), porté d'api-bref `src/whatsapp/*` + `owner-template.service.ts` : compte WABA unique par entreprise (`public.whatsapp_accounts`, V68 ; jeton AES-256-GCM lié à l'entreprise par `shared.infrastructure.security.crypto.SecretCipher`, clé `KEEVO_SECRETS_ENCRYPTION_KEY`), connexion par le résultat de l'inscription intégrée Meta, webhook Meta unique `GET|POST /api/v1/whatsapp/webhook` (`X-Hub-Signature-256`, routage set-based par `phone_number_id` / `waba_id`, idempotent sur l'id Meta, push aux owners après commit), messagerie (conversations maintenues à l'écriture, fenêtre de 24 h, modèle approuvé hors fenêtre, pagination keyset), modèles (brouillon → soumission à la connexion, suivi de validation par webhook et par rafraîchissement limité, `bref_facture` système), API Graph derrière le port `MetaGraphClient` (meta / fake / disabled). Factures et devis WhatsApp : port partagé `TenantWhatsAppBusiness` → PDF + modèle `bref_facture` depuis le numéro de l'owner quand il est utilisable, texte plateforme sinon ou sur refus certain, jamais sur issue incertaine.

**Files.** Tenant DDL `db/modules/whatsapp-cloud/V1__whatsapp_cloud.sql`; public `V68__whatsapp_cloud.sql`; manifest `keevo/modules/whatsapp-cloud/module.json`; `whatsappcloud/**` (account, graph, inbox, template, webhook, invoice, config, module); `shared/.../crypto/SecretCipher`, `shared/capability/notification/TenantWhatsAppBusiness`, `shared/event/catalog/auth/WhatsAppAccountChangedEvent`; error codes (ErrorCode, ErrorMessages, GlobalExceptionHandler), `SecurityConfig.PUBLIC_PATHS`, audit (AuditEventListener, AuditLabels); invoicing `WhatsAppInvoiceChannel`, `InvoiceSendChannel.assertAvailable(String)`, `SendInvoiceUseCase`; config (application, dev, test); `db/modules/README.md`; `docs/entitlements/endpoints.md` (regenerated); contract `keevo/contracts/whatsapp-cloud.json`; tests (whatsappcloud full context + units, SecretCipherTest, WhatsAppInvoiceChannelTest, module-count / plan-grant pins in architecture, shared/module, admin).

**Review.** 5 layers (blind, edge case, verification gap, intent alignment, FUS-NFR11): 50 findings — 0 high, 17 medium, 28 low, 5 false. Patched: 17 medium + 15 low (keyset `beforeId`, asynchronous submissions at connection, WABA change resets templates, number of a deleted company freed, Meta 5xx / unreadable answers = unknown outcome, DRAFT adoption limited, delete in every language and when gone at Meta, invoice-template refresh while PENDING, unread recount in one transaction, audit failure caught, tests for the uncertain / refused / company-number-only paths and the push). Rejected: 13 low + 5 false (reasons in the triage log). Routed: 1 → 16-6, 3 → 21-6.

**Follow-up review recommended: true** — 17 medium entries patched on a first pass; the unverified risk is the real Meta Graph wire format (embedded-signup code exchange, resumable header upload, document header send) never exercised outside the fake: to check on the first real connection in staging.

**Verification.** `kmvn clean compile` OK; tests per package: whatsappcloud 21, invoicing 469, messaging 370, architecture 146, admin 690, shared 1 354 — all green; `EndpointEntitlementCoverageTest` regenerated `docs/entitlements/endpoints.md` (+13 routes). Live keevo_dev (profile dev, WhatsApp noop, FCM off, graph fake, fresh test company KV-6HIIVN, no existing tenant touched): status, draft before connection, connect (token not in clear, 77-byte blob), bref_facture + draft submitted, number outside the WABA 502, signed webhook stored once (replay no-op, unsigned 403, unknown number ignored), conversation + unread + push inbox row « Nouveau message WhatsApp | Awa : … », reply in window, statuses delivered → (late sent ignored) → read, 422 out of window, template review → « Approuvé », template send, bref_facture approved → invoice sent from the company number and stored in the inbox, delete, disconnect, audit rows; no token in the logs.

**Residual risks.** Real Meta behaviour (above); the encrypted token of a deleted company stays at rest until 16-6 purges it; STOP/START consent, media-header sends and the platform-number inbox are 21-6.
