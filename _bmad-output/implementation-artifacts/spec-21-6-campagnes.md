---
title: '21-6 — Client WhatsApp campaigns and super-admin platform campaigns (push, WhatsApp, SMS, email), STOP/START consent, media-header template sends, platform-number inbox'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: '517b2c42200d2883a169bf48ef93dfc52dae8ebf'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-5-whatsapp-cloud.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-2-crm-etendu-clients-par-etablissement.md'
warnings: ['oversized', 'multiple-goals']
deferred: []
---

<intent-contract>

## Intent

**Problem:** A Bref owner can send WhatsApp campaigns to his consenting clients and the Bref super-admin can send campaigns to merchant segments; Keevo has neither (FUS-FR28, FR32). Three items routed from the 21.5 review are also open: a client's STOP / START sent to a WhatsApp number does not change his marketing consent, templates with a media header cannot be sent, and messages received on the platform number are dropped (no super-admin inbox).

**Approach:** (1) Client campaigns in the `whatsapp-cloud` module (`whatsappcloud.campaign`): owner campaigns over an approved template of his WABA, audience = clients with active marketing consent (20.2) of the chosen stores, de-duplicated by phone, materialized as recipient rows when sending starts, then sent asynchronously by a work-queue dispatcher in throttled batches, consent re-checked and quota counted per batch, delivery tracked from Meta statuses. (2) Platform campaigns in `admin.campaign` (Bref `/campaigns`, 20.13 K1): super-admin campaigns to the owners of a tenant segment (plans, store countries, store activities, explicit tenants) over push, WhatsApp (platform number, approved template), SMS and email, same queue mechanics, audited. (3) STOP/START keywords on a company number update the client's opt-out (crm), on the platform number a platform opt-out; a confirmation is sent at most once per number per minute. (4) Template sends accept a header media (uploaded media id or https link). (5) Platform-number inbox for the super-admin (conversations, thread, reply in the 24 h window, unread count) through the same webhook.

## Boundaries & Constraints

**Always:**
- Additive only for V1 2.0.1 apps; French messages; no UUID / raw code / Meta id in user-facing text (statuses carry a French `statusLabel`).
- Client campaigns: OWNER only, `@RequiresEntitlement("whatsapp-cloud")` (GET READ, others WRITE); platform campaigns and platform inbox: SUPER_ADMIN only (`/api/v1/admin/**`, `CurrentActor.requireSuperAdmin()`, `@EntitlementExempt("super-admin")`).
- Consent is checked twice: at audience materialization (eligible = consent given, no STOP, not archived — `crm` rule) and again for every batch right before sending (`ClientQueryPort.marketingEligible`); a client who withdrew or sent STOP in between is SKIPPED, never sent. Platform WhatsApp recipients whose number sent STOP to the platform number are SKIPPED at send time.
- Idempotent, at-most-once sends: one recipient row per (campaign, phone) for clients, per (campaign, channel, user) for platform; a row is claimed PENDING → SENDING in one statement before the provider call and ends SENT / FAILED / UNCERTAIN; a row left SENDING beyond the lease (crash) becomes UNCERTAIN, never resent. Sending is started by a guarded transition (DRAFT|SCHEDULED → SENDING) so a double click / cron overlap starts it once.
- Throttling: per dispatcher tick (every 2 s, `keevo.campaigns.tick-ms`) each sending campaign sends at most `keevo.campaigns.batch-size` (default 40) messages ⇒ ≤ 20 messages/s per sender number (Meta Cloud API default 80/s); Meta throughput / spam / pair rate-limit refusals (codes 130429, 131048, 131056, 80007) put the recipient back to PENDING and pause the campaign 60 s; only one client campaign SENDING per company (partial unique index), the next waits.
- Quotas: new usage metric `WHATSAPP_MESSAGES` « Messages WhatsApp » (limits are data, none seeded = counted, not limited). Starting a client campaign probes the quota for the whole audience (`QUOTA_EXCEEDED` 403, nothing sent); each batch counts the messages Meta accepted in its own short transaction (a concurrent overrun is logged and absorbed); a batch refused by the quota ends the campaign with the remaining recipients SKIPPED « Quota de messages WhatsApp atteint ». Platform campaigns are platform messages (not counted).
- Limits: ≤ 5 000 recipients per client campaign, ≤ 50 000 per platform campaign (`CAMPAIGN_TOO_MANY_RECIPIENTS` 422); name 1..100, description ≤ 500, title ≤ 100, message ≤ 1 000 (SMS ≤ 480), ≤ 100 stores / tenants / plans / countries / activities in a filter.
- STOP / START (Bref `handleIncomingMessage` :623-667, keywords stop, arret, arreter, desabonner, unsubscribe / start, reprendre, subscribe after lower-case + accent strip + letters only): applied in the same transaction as the inbound message insert (Meta redelivery = nothing new); company number → `clients.marketing_opt_out_at = now()` / `NULL` for every client of the tenant with that phone (`phone_normalized`), `updated_at` bumped so devices pull it; never touches the team consent. Platform number → `public.whatsapp_marketing_opt_outs` row inserted / deleted. Confirmation text (Bref wording) after commit from the same number, at most one per (number, contact) per minute (bounded cache), best effort.
- Header media at send (inbox template send, client and platform campaigns): IMAGE / VIDEO / DOCUMENT templates require exactly one of `headerMediaId` (from `POST …/whatsapp/media`, uploaded to the sending number) or `headerMediaLink` (`https://`, ≤ 2048, fetched by Meta, never by Keevo); TEXT / NONE templates refuse both. Named body variables are sent with `parameter_name`.
- Platform number: id / WABA / token from `keevo.whatsapp-cloud.platform.*` (defaulting to the 19.9b `keevo.whatsapp.meta.*` keys); not configured = WhatsApp channel and platform inbox reply refused 503 `PLATFORM_WHATSAPP_UNAVAILABLE`, other channels unaffected; webhook messages to it are stored once configured.
- Public DDL in `V69__campaigns.sql` only; tenant DDL in `db/modules/whatsapp-cloud/V2__campaigns.sql` and `db/modules/crm/V4__clients_marketing_indexes.sql`.

**Never:** a real Meta / SMS / email / push call in tests or the live check; sending to a client without active consent or to an opted-out number; resending an UNCERTAIN row; a per-tenant query loop on a request path (segment resolution is chunked `UNION ALL`, push fan-out is per company in the async job only); fetching a media URL server-side; Bref purchase-behaviour criteria (`inactiveForDays`, `purchasedWithinDays`, `totalSpent*`, `totalInvoices*` — need per-client sales/document aggregates owned by reporting: routed to new key `21-11-ciblage-achats`); super-admin reading an owner's campaigns (support view, not in AC); platform template creation (managed in Meta Business Manager; listing only); the dual-delivery router (24.10).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Preview | OWNER `POST /whatsapp/campaigns/preview-targeting {storeIds}` | `{count}` eligible clients, one per phone | unknown store → 400 « Boutique inconnue ou désactivée » |
| Create | `{name, templateId?, variables, storeIds, scheduledAt?}` | 201 DRAFT (SCHEDULED when `scheduledAt`), `targetedCount` = preview | name blank → 400 |
| Send | DRAFT, connected, template APPROVED, 3 eligible + 1 withdrawn | 200 SENDING, 3 recipients PENDING; within seconds 3 SENT, fake Graph got 3 template sends from the company number | not connected → 409 `WHATSAPP_NOT_CONNECTED`; no template → 422 `CAMPAIGN_NOT_READY`; none eligible → 422 `CAMPAIGN_NO_RECIPIENT` |
| Double send | second `send` while SENDING / SENT | 409 `CAMPAIGN_ALREADY_SENT` | nothing re-sent |
| Other campaign sending | send B while A SENDING | 409 `CAMPAIGN_SENDING_IN_PROGRESS` | B unchanged |
| Consent withdrawn mid-send | client withdraws before his batch | recipient SKIPPED « Le client ne souhaite plus recevoir d'offres », `skippedCount`+1 | — |
| Quota | audience 10, MONTH limit 5 left | 403 `QUOTA_EXCEEDED` at send, nothing sent | — |
| Meta refusal | recipient refused (fake `999…`) | FAILED with Meta's French text, `failedCount`+1 | campaign continues |
| Meta timeout | fake `998…` | UNCERTAIN, never resent | — |
| Rate limit | fake `997…` (130429) | recipient back to PENDING, campaign paused 60 s | — |
| Statuses | webhook delivered / read for a campaign message | `deliveredCount` / `readCount` +1 once (monotone) | replay changes nothing |
| Edit / delete sent | PATCH / DELETE a SENDING / SENT campaign | 409 `CAMPAIGN_ALREADY_SENT` | — |
| Cancel | `POST /{id}/cancel` on SCHEDULED | CANCELLED, job removed | SENDING → 409 `CAMPAIGN_ALREADY_SENT` |
| Scheduled due | SCHEDULED, `scheduledAt` past | dispatcher starts it like send; not connected / not ready → stays SCHEDULED, retried in 5 min | — |
| STOP on company number | client `2376…` writes « Stop » | message stored, client `marketingOptOutAt` set, `marketingEligible=false`, confirmation sent once | second STOP within 1 min: no second confirmation |
| START | « START » | opt-out cleared (team consent unchanged) | — |
| STOP on platform number | owner writes « arrêter » | platform opt-out row; next platform WhatsApp campaign SKIPS him | — |
| Media template send | inbox `POST …/template {templateId, variables, headerMediaId}` IMAGE template | template sent with image header | missing / both media → 400; link not https → 400 |
| Platform campaign | super-admin `{channels:[PUSH,EMAIL,SMS,WHATSAPP], segment:{planCodes:[BUSINESS], countries:[CM], activities:[food]}}` | owners of matching tenants: push inbox row, email / SMS / WhatsApp sends; per-channel counters; history « Créée », « Envoi lancé », « Envoi terminé » | no channel → 400; WhatsApp without platform number → 503 `PLATFORM_WHATSAPP_UNAVAILABLE` |
| Platform inbox | super-admin `GET /admin/whatsapp/conversations` after a message to the platform number | conversation listed; reply in window from the platform number | out of window → 422 `WHATSAPP_REPLY_WINDOW_CLOSED` |
| Rights | EMPLOYEE on client campaigns; OWNER on `/admin/campaigns` | 403 | FREE plan → 403 `MODULE_NOT_ENTITLED` |

</intent-contract>

## Code Map

- Bref (read-only): `api-bref/src/customer-campaign/customer-campaign.service.ts` (recipients SQL, dispatch claim, components, `resolve`, counters, cron), `dto/customer-campaign.dto.ts` (limits, sources), `api-bref/src/campaign/{campaign.service,targeting.service,campaign.controller,domain/campaign}.ts` (platform campaigns, WhatsApp variables `firstName|lastName|fullName|shopName|static`), `api-bref/src/whatsapp/whatsapp.service.ts:80-102,579-667` (keywords, confirmation texts, cooldown), `whatsapp-inbox.service.ts` (`ownerId NULL` = platform), `whatsapp-templates.controller.ts` (platform templates, super-admin).
- `whatsappcloud/webhook/application/WhatsAppWebhookService.java` -- `processMessages` routing (`schemaByNumber`), `store()` tx (insertAll → per-contact deltas → `applyStatuses`), `usable()`; add platform routing key, keyword handling inside the tx, status changes → campaign counters.
- `whatsappcloud/inbox/adapter/out/persistence/JdbcWhatsAppInboxRepository.java` -- `table(schema, t)` (make the platform key map to `public.platform_whatsapp_*`), `applyStatuses` (return campaign status changes), `insertAll` (+ `campaign_id`).
- `whatsappcloud/inbox/application/WhatsAppInboxService.java` -- `sendTemplate` (media header refusal to replace), `recordOutbound`, `reply`, `REPLY_WINDOW`.
- `whatsappcloud/webhook/application/InboundMessageNotifier.java` -- bounded executor to reuse for the confirmation sends.
- `whatsappcloud/graph/**` -- `MetaGraphClient.sendTemplate/uploadMedia/listTemplates`, `MetaGraphException(kind, metaCode)`, `FakeMetaGraphClient.outcome` (add `997` = rate limit 130429), `MetaTemplate` (+ body text / header format for listing), `HttpMetaGraphClient` list parsing.
- `whatsappcloud/template/application/WhatsAppTemplateService.java:189 approved()`, `domain/model/TemplateText.java`, `account/application/WhatsAppAccountService.credentials()`; `template/adapter/in/rest/WhatsAppTemplateController.java` (media rules of 21.5 to reuse for `POST /whatsapp/media`).
- `crm/api/ClientQueryPort.java` (+ `marketingAudience`), `ClientCommandPort.java` (+ `setMarketingOptOutByPhone`), `crm/contact/adapter/in/api/ClientApiAdapter.java` (`TenantContext.requireCurrent`), `ClientSpringRepository.findMarketingEligibleIds` (array-literal pattern); `store/api/StoreQueryPort.namesByIds`, `StoreCategory`, `StoreCountry`.
- `shared/capability/entitlement/UsageMetric.java`, `UsageQuotaPort.assertAndIncrementForSchema` (MANDATORY tx); `db/migration/V44__usage_quotas.sql`, `V63__account_credit_usage_packs.sql` (metric CHECKs to widen).
- `shared/capability/notification/{PushNotifier.notifyUsers,SmsSender,EmailSender}`; new `PlatformWhatsApp` port here.
- `admin/notification/**` (9-5 global push: claim / stuck pattern, `JdbcPushAudienceAdapter.REACHABLE`), `admin/crosstenant/{CrossTenantSql.query,TenantBranch,TenantTable}` (chunked `UNION ALL` for store country / category), `admin/package-info.java` (+ `store :: api`), `whatsappcloud/package-info.java` (+ `crm :: api`, `store :: api`).
- `shared/infrastructure/web/{AuditEventListener,AuditLabels,ErrorMessages,GlobalExceptionHandler}.java`, `shared/domain/exception/ErrorCode.java`, `shared/event/catalog/auth/WhatsAppAccountChangedEvent.java` (audit event template), `shared/infrastructure/persistence/TenantContext`.
- Tests to mirror: `test/.../whatsappcloud/WhatsAppCloudFullContextTest.java` (signed webhook helpers, fake graph, two tenants), `architecture/EndpointEntitlementCoverageTest.java` (regenerates `docs/entitlements/endpoints.md`), `architecture/ModuleIdsAtGoLivePinTest.java` (no new module).

## Tasks & Acceptance

**Execution:**
- `resources/db/migration/V69__campaigns.sql` -- usage metric `WHATSAPP_MESSAGES` in the metric CHECKs (plan_quotas, usage_counters, usage_credits, usage_packs, usage_credit_movements); `public.whatsapp_campaign_jobs` (tenant_id, campaign_id PK, next_run_at, index next_run_at); `public.platform_whatsapp_messages` / `platform_whatsapp_conversations` (same shape as the tenant tables + campaign_id); `public.whatsapp_marketing_opt_outs` (phone PK); `public.platform_campaigns` (+ partial index on next_run_at for SCHEDULED / SENDING, created_at keyset), `public.platform_campaign_recipients` (PK campaign/channel/user, partial pending and sending indexes; Meta id kept for support), `public.platform_campaign_stats` (counters per channel), `public.platform_campaign_events` (audit).
- `resources/db/modules/whatsapp-cloud/V2__campaigns.sql` -- `whatsapp_messages.campaign_id`; `whatsapp_campaigns` (+ one-SENDING partial unique index, created_at keyset index); `whatsapp_campaign_recipients` (PK campaign/phone, pending partial index).
- `resources/db/modules/crm/V4__clients_marketing_indexes.sql` -- `(phone_normalized)` and the eligible-audience partial index.
- `shared/capability/entitlement/UsageMetric.java` -- `WHATSAPP_MESSAGES("Messages WhatsApp")`.
- `shared/capability/notification/PlatformWhatsApp.java` -- port (configured?, send template from the platform number with campaign id, opted-out numbers) + `TemplateMessage` / `HeaderMedia` records.
- `crm` -- `ClientQueryPort.marketingAudience(schema, storeIds, limit)`, `ClientCommandPort.setMarketingOptOutByPhone(schema, e164, optedOut, at)`; adapter + repository queries.
- `whatsappcloud/campaign/**` -- domain (campaign, recipient, statuses + French labels, variable sources), `JdbcCampaignRepository`, `CampaignService` (CRUD, preview, send/start, cancel, recipients page), `CampaignDispatcher` + scheduler (job claim, batch send, consent / quota / rate-limit handling, completion), `CampaignController` `/api/v1/whatsapp/campaigns`, audit event `WhatsAppCampaignChangedEvent`.
- `whatsappcloud/template/domain/model/TemplateComponents.java` -- Meta components (named variables, header media) used by inbox and campaigns; `WhatsAppInboxService.sendTemplate` accepts header media; `POST /api/v1/whatsapp/media`.
- `whatsappcloud/consent/KeywordConsent.java` -- keyword parsing, confirmation texts, cooldown cache.
- `whatsappcloud/platform/**` -- `PlatformNumberSettings`, `PlatformWhatsAppService` (implements the port, platform inbox, opt-outs, platform templates list), `AdminWhatsAppController` `/api/v1/admin/whatsapp/{conversations,templates,media}`; webhook routes the platform number and publishes `PlatformWhatsAppStatusesChangedEvent`.
- `admin/campaign/**` -- domain, `JdbcPlatformCampaignRepository`, audience resolver (public SQL + `CrossTenantSql` store branch), `PlatformCampaignService`, `PlatformCampaignDispatcher` + scheduler, status-event listener, `AdminCampaignController` `/api/v1/admin/campaigns`; `TenantTable.STORE_PROFILE`.
- Errors / audit / config -- new codes + French messages + statuses; audit labels; `application.yml` (`keevo.campaigns.*`, `keevo.whatsapp-cloud.platform.*`), `application-dev.yml` / test config.
- Contracts / docs -- `keevo/contracts/whatsapp-cloud.json` (campaigns, media), `keevo/contracts/admin-campaigns.json`, `docs/entitlements/endpoints.md` regenerated.
- Tests -- unit: keyword parsing, components builder, variable resolution, campaign status rules; full context: client campaign end-to-end (consent filter, dedupe, async batches, SKIPPED on withdrawal, quota refusal, FAILED / UNCERTAIN / rate-limit, statuses → counters, one SENDING, isolation between two tenants, EMPLOYEE / FREE refused), STOP/START (company + platform, cooldown), media header sends, platform campaign (segment by plan / country / activity, 4 channels with test doubles, opt-out skip, audit history, OWNER refused), platform inbox.

**Acceptance Criteria:**
- Given a company with 3 consenting clients (two sharing one phone in two stores) and 1 without consent, when the owner sends a campaign, then exactly 2 template messages leave from the company number, asynchronously, and the campaign ends SENT with `sentCount=2`.
- Given a client who sends « STOP » to the company number, when the owner previews or sends a campaign, then that client is not counted and receives nothing; « START » makes him eligible again only if the team consent is still given.
- Given the dispatcher, when a campaign of N recipients runs, then the statements per batch are constant (claim, eligibility, quota probe, outcomes, counters, conversations, quota) plus one insert per message sent (it must exist before Meta's status callback), and no batch sends more than the configured size nor runs past half the lease.
- Given a platform campaign over PUSH + WHATSAPP to segment plan BUSINESS / country CM, when it runs, then each owner of a matching tenant gets one inbox notification and one WhatsApp template from the platform number (except opted-out numbers, SKIPPED), and the campaign history lists who created and launched it.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 47 findings — high 0, medium 15, low 22, false 4, maybe-false 6
- findings:
  - `[medium]` `[patch]` (blind) start: targeted count too high when two E.164 spellings collapse to one Meta number; empty list after normalization went SENDING; any unique violation reported as « campaign sending » — recipients de-duplicated by Meta digits before the insert, empty → `CAMPAIGN_NO_RECIPIENT`, only `uq_whatsapp_campaigns_sending` maps to `CAMPAIGN_SENDING_IN_PROGRESS`.
  - `[medium]` `[patch]` (blind) recordOutcomes counted outcomes whose guarded row update matched nothing (row already expired UNCERTAIN) — counters now built from the rows the batch really moved (JDBC batch counts), both repositories.
  - `[medium]` `[patch]` (blind) the 2-min lease was taken for all jobs at claim time and a slow batch could outlive it — lease renewed when each job starts, a batch stops sending after half the lease (rest back to PENDING), same on the platform WhatsApp channel.
  - `[low]` `[reject]` (blind) quota exhaustion skips the rest instead of a partial send — monthly quota is a hard stop by design (French reason « Quota de messages WhatsApp atteint » on every skipped row); a partial probe loop adds complexity for a rare case.
  - `[medium]` `[patch]` (blind) a due scheduled campaign that can never start was retried every 5 min forever — permanent refusals (not ready, nobody, too many, template gone / not approved) put it back to DRAFT (audited, job removed) on both sides; transient ones (number disconnected, another campaign sending, quota) keep the 5-min retry.
  - `[medium]` `[patch]` (blind) media id (30 days at Meta) vs scheduling up to 365 days — a scheduled campaign with an uploaded media id is refused beyond 29 days (link required), both sides.
  - `[medium]` `[patch]` (blind) platform channel exception released the batch to PENDING (possible double send) — now UNCERTAIN, never resent.
  - `[low]` `[reject]` (blind) platform template content typed by the dashboard, not checked against Meta — the dashboard picks it from `GET /admin/whatsapp/templates`; a wrong one fails visibly per recipient; a Graph call per create is not worth it.
  - `[low]` `[patch]` (blind) START confirmation sent even when nothing changed — no confirmation for a START that changed nothing; the multi-instance cooldown is a bounded best effort (documented, rejected part).
  - `[low]` `[reject]` (blind) no opt-out for platform email / SMS / push — Bref has none either; platform messages go to account holders; outside the routed STOP/START item (WhatsApp keywords).
  - `[medium]` `[patch]` (blind + edge) `GREATEST(0, …)` clamps made counters drift when a FAILED status arrives before the batch bookkeeping — clamps removed: plain sums, order independent (final values exact).
  - `[low]` `[reject]` (blind) platform campaigns share the 19.9b sign-in number — configuration decision: `KEEVO_WHATSAPP_CLOUD_PLATFORM_*` can point to a dedicated marketing number; listed in the user actions for deploy.
  - `[medium]` `[patch]` (blind) platform preview excluded STOP numbers but the frozen targets include them; platform recordOutcomes not transactional — preview now counts like the frozen audience (STOP skipped at send, counted « skipped »); outcomes + counters in one transaction.
  - `[low]` `[patch]` (blind) layering: application → concrete JDBC opt-out adapter — `PlatformOptOutRepository` port added.
  - `[low]` `[reject]` (blind) status-delta logic duplicated in the admin listener — two tiny pure functions in two modules that cannot share code (shared would need a new type for 4 lines).
  - `[medium]` `[patch]` (blind) Meta's own (possibly English) error text stored as the recipient reason — French reasons by Meta code only.
  - `[low]` `[reject]` (blind) missing tests for lease expiry / concurrent dispatchers — covered by the guarded single-statement SQL; a two-instance test needs a second context.
  - `[medium]` `[patch]` (NFR11) lease vs serial tick — same fix as the lease row above.
  - `[medium]` `[patch]` (NFR11) full audience loaded to count it (preview, create, update) — `ClientQueryPort.countMarketingAudience` (one count query, rows never loaded).
  - `[medium]` `[patch]` (NFR11) platform preview loaded up to 50 001 owners and a 50k opt-out array — `PlatformAudiencePort.count` (one SQL with FILTER counts).
  - `[low]` `[reject]` (NFR11) platform channel: insert + conversation upsert per message — bounded by the batch (≤ 40), the insert is required per message (status callback race).
  - `[low]` `[patch]` (NFR11) per-channel UPDATE loop in platform expireStuck — one data-modifying CTE.
  - `[low]` `[reject]` (NFR11) checkStores loads every active store — a company has a handful of stores; adding a store-module port for it is more surface than gain.
  - `[low]` `[patch]` (NFR11) application depending on a JDBC adapter — same as the port row above.
  - `[medium]` `[patch]` (verification) store targeting never exercised — `storeTargeting` full-context test (preview per store, send only to that store's client, legacy client excluded).
  - `[low]` `[patch]` (verification) storeName variable never checked — exact body asserted with the store name.
  - `[medium]` `[patch]` (verification) WhatsApp-messages quota never asserted — `usage_counters` WHATSAPP_MESSAGES = 3 asserted after the send (probe refusal covered by the unchanged QUOTA_EXCEEDED path of 19.8).
  - `[low]` `[patch]` (verification) Meta listing parse and admin templates route untested — parser extracted (`HttpMetaGraphClient.template`) and unit-tested; `platformTemplates` full-context test.
  - `[low]` `[reject]` (verification) `ensureProvisioned` in `canSendDocuments` only exercised on fresh tenants — a V1-only tenant fixture is costly; the failure is a logged best-effort inbox write.
  - `[low]` `[patch]` (edge) unexpected exception mid-loop lost what the batch had sent — each send guarded: an unexpected failure is UNCERTAIN, the batch is always recorded and counted.
  - `[maybe-false]` `[reject]` (edge) deterministic sender exception loops forever — patched path now ends UNCERTAIN (no retry), so the loop cannot happen.
  - `[medium]` `[patch]` (edge) job claim could starve on jobs of deleted companies — the deleted-company filter moved inside the locked sub-query.
  - `[low]` `[patch]` (edge) PATCH of a past-due scheduled campaign refused for any field — date checked only when sent in the PATCH; stores re-checked only when sent.
  - `[maybe-false]` `[reject]` (edge) a deactivated store blocks edits — same patch.
  - `[low]` `[patch]` (edge) `before` without `beforeId` returned an empty page — `created_at < ?` used then (both lists).
  - `[low]` `[patch]` (edge) platform phones of 6–7 / > 15 digits counted then always failed — 8–15 digits, as Meta / `ContactPhone`.
  - `[low]` `[patch]` (edge) `TemplateComponents.build` NPE on a blank media — header added only with an id or a link.
  - `[low]` `[patch]` (edge) spec claimed a `wa_message_id` index on platform recipients — spec corrected (Meta id kept for support only).
  - `[low]` `[patch]` (edge) spec claimed constant queries per batch — spec corrected (constant statements + one insert per message sent).
  - `[false]` `[reject]` (intent a) spec Mobile / Dashboard sections and m/d keys missing — written at finalization (this pass ran before it).
  - `[false]` `[reject]` (intent a) `docs/entitlements/endpoints.md` not regenerated — regenerated in verification.
  - `[maybe-false]` `[reject]` (intent b) FREE plan refusal untested for campaigns — same `@RequiresEntitlement` gate as the 21.5 endpoints, covered by `EndpointEntitlementCoverageTest` and 21.5's FREE test.
  - `[false]` `[reject]` (intent d) throughput only a configuration argument — the batch-size / tick / pause rules are tested (`rateLimitPauses`, batch-size 2 in tests); Meta's real throughput cannot be measured locally.
  - `[maybe-false]` `[reject]` (intent e) audit only per campaign for platform campaigns — the super-admin has no general audit table in Keevo (« [ADMIN AUDIT] » logs + per-feature history, as 21.3 / 21.10); both are written.
  - `[maybe-false]` `[reject]` (intent h) owners only / store country & activity / one SENDING per company / purchase criteria routed — decisions recorded in Design Notes; purchase criteria routed to `21-11-ciblage-achats`.
  - `[false]` `[reject]` (intent c, f, g) tracking, routed items and French labels — reported as aligned, no defect.
  - `[maybe-false]` `[reject]` (edge) per-row correlated tenant lookup in platform claim — primary-key probe, ≤ batch size.

## Design Notes

- **Patterns.** Transactional work queue + claim/lease (`FOR UPDATE SKIP LOCKED`, guarded single-statement transitions) for the async fan-out (cluster-safe, crash-safe at-most-once), same as 9-5 / 21.1; Outbox-like recipient rows materialized once (the audience is frozen at start, consent re-checked per batch = Specification re-evaluated at send time); Strategy per channel (`ChannelSender` push / WhatsApp / SMS / email) in platform campaigns; Hexagonal ports so `admin` never touches whatsappcloud tables (`PlatformWhatsApp`) and whatsappcloud never touches crm tables (`ClientQueryPort` / `ClientCommandPort`); Observer (domain event) for platform WhatsApp statuses → admin counters (Bref `WHATSAPP_CAMPAIGN_MESSAGE_UPDATED_EVENT`); Builder for Meta components.
- **Costs.** Dispatcher tick: 1 claim statement on the public job table (index next_run_at, LIMIT 20); per claimed campaign and batch of B ≤ 40: 1 claim UPDATE…RETURNING (partial pending index), 1 eligibility query (`id = ANY`), 1 quota probe, B Graph calls (no transaction open), 1 batched recipient update + 1 batched inbox insert + 1 conversation upsert batch + 1 counter update + 1 quota increment, i.e. O(1) statements per batch, O(B) rows. Start: 1 audience query (index `idx_clients_marketing_audience`, LIMIT 5 001), 1 store-name query, 1 batched insert. Webhook statuses: unchanged 1 status statement now returning the changed campaign rows, + 1 counter UPDATE … FROM (VALUES) per company. Platform segment: public SQL on `ix_tenants_plan_type` / `ix_utm_tenant_role`; country / activity via `CrossTenantSql.query` (chunks of 100 `UNION ALL` branches, `LIMIT 1` per tenant), never a query per tenant on the request; push fan-out = 1 `notifyUsers` per company per batch (device tokens live in tenant schemas). Lists: keyset on (created_at, id) / phone, ≤ 100 rows. Memory: one batch at a time; audiences streamed into batched inserts.
- **Decisions.** Recipients are owners (company accounts), not every user (Bref targeted all user roles; Keevo platform marketing addresses the account holder). Segment « pays » / « activité » = an active store of the company in that country / category (20-13 geography used the phone code as V1 proxy; stores now carry them, 19.12). The one-SENDING-per-company rule replaces Bref's concurrent loops (one sender number = one throughput budget). Media ids from Meta expire after 30 days: a scheduled campaign further out should use a link.

## Décisions (prises par défaut, à valider par l'utilisateur)

- Destinataires des campagnes plateforme : les propriétaires des entreprises (un par personne, son entreprise la plus ancienne), pas tous les utilisateurs comme Bref.
- « Pays » et « activité » d'un segment : ceux d'une boutique active de l'entreprise (19.12), plus l'indicatif du téléphone.
- Une seule campagne client en cours d'envoi par entreprise (un numéro = un budget de débit Meta) ; la suivante est refusée (409) tant que la première n'est pas finie.
- Quota : nouvelle métrique `WHATSAPP_MESSAGES` « Messages WhatsApp », comptée sans limite tant que le super-admin n'en fixe pas dans l'offre ; un lot refusé par le quota termine la campagne (destinataires restants « Non envoyé »).
- Pas de désinscription pour les campagnes plateforme par e-mail / SMS / notification (comme Bref) : seul le STOP WhatsApp au numéro plateforme est géré.
- Numéro plateforme : par défaut celui des codes de connexion (19.9b) ; un numéro marketing dédié se configure par `KEEVO_WHATSAPP_CLOUD_PLATFORM_*`.
- Les critères d'achat Bref du ciblage client (inactifs, récents, montants, nombre d'achats) sont routés vers `21-11-ciblage-achats`.
- Une campagne programmée qui ne peut pas partir pour une raison durable repasse « Brouillon » ; un fichier joint (30 jours chez Meta) interdit une programmation au-delà de 29 jours.

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- expected: BUILD SUCCESS
- `wt-pkgs.sh keevo/backend t216 whatsappcloud admin crm subscription architecture shared messaging` -- expected: all green
- `-Dkeevo.entitlements.regenerate=true` on `EndpointEntitlementCoverageTest` -- expected: `docs/entitlements/endpoints.md` lists the new routes

**Manual checks:**
- Live keevo_dev (dev profile, WhatsApp noop, FCM off, graph fake, fake platform number): fresh test company, consenting / non-consenting clients, campaign send → SENT counters, STOP / START via signed webhook, media template send, platform campaign over the 4 channels to a segment containing only the test company, platform inbox; no existing tenant touched (never kv_11b5zm).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `11aba29a` (+ `1a963fdc`, `9d91fd8d`), clé `m21-6-campagnes` dans `sprint-status.yaml`). Contrat : `keevo/contracts/whatsapp-cloud.json` (entrées 21.6). Conventions d'erreur et d'affichage : spec-16-11 ; protocole de synchro : spec-16-10 (rien à synchroniser : campagnes en ligne uniquement ; l'opt-out d'un client arrive par le pull `clients` habituel).

**Ce qui est fait côté API :** l'owner d'une entreprise ayant le module « WhatsApp Business » crée des campagnes WhatsApp vers ses clients qui ont donné leur accord marketing (20.2) et n'ont pas répondu STOP, choisit les boutiques visées, un modèle approuvé et la valeur de chaque variable (texte fixe, nom du client, nom de la boutique), envoie tout de suite ou programme l'envoi. Les messages partent en arrière-plan depuis le numéro de l'entreprise (≤ 20 par seconde), l'accord est revérifié juste avant chaque envoi, chaque message compte sur le quota « Messages WhatsApp ». Les compteurs (envoyés, délivrés, lus, échecs, non envoyés, incertains) suivent les accusés de Meta. Un client qui écrit STOP (ou ARRÊT, ARRÊTER, DÉSABONNER, UNSUBSCRIBE) au numéro de l'entreprise est désinscrit (START / REPRENDRE le réinscrit) et reçoit une confirmation. Les modèles à en-tête image / vidéo / document s'envoient désormais depuis la messagerie et dans les campagnes.

**À utiliser** (code : `keevo/backend/src/main/java/com/keevo/whatsappcloud/campaign/adapter/in/rest/WhatsAppCampaignController.java`, `inbox/adapter/in/rest/WhatsAppMediaController.java`, `WhatsAppConversationController.java`) — correspondance Bref → Keevo :
- `GET /v1/customer-campaigns` → `GET /api/v1/whatsapp/campaigns[?before=<createdAt>&beforeId=<id>]` (50 plus récentes ; page suivante = `createdAt` et `id` de la dernière).
- `GET /v1/customer-campaigns/templates` → `GET /api/v1/whatsapp/campaigns/templates` `{id, name, language, category, headerFormat, bodyText, variableCount}`.
- `POST /v1/customer-campaigns/preview-targeting` → `POST /api/v1/whatsapp/campaigns/preview-targeting` `{storeIds?}` → `{count, overLimit}` (400 « Boutique inconnue ou désactivée »).
- `POST|GET|PATCH|DELETE /v1/customer-campaigns[/:id]` → identiques sous `/api/v1/whatsapp/campaigns`. Corps : `{name, description?, storeIds?, templateId?, variables?: [{source: static|clientName|storeName, value?}], headerMediaId?, headerMediaLink?, scheduledAt?}` (Bref `whatsapp.templateName/bodyVariables` → `templateId` + `variables` ; `customerName`/`shopName` acceptés). PATCH : seuls les champs présents changent, `"scheduledAt": null` déprogramme. Réponse : `{id, name, description, status, statusLabel, storeIds, storeNames, templateId, templateName, templateLanguage, templateBody, headerFormat, hasHeaderMedia, headerMediaLink, variables, scheduledAt, startedAt, sentAt, targetedCount, sentCount, deliveredCount, readCount, failedCount, skippedCount, uncertainCount, createdAt, updatedAt}`.
- `POST /v1/customer-campaigns/:id/send` → `POST /api/v1/whatsapp/campaigns/{id}/send` → la campagne « En cours d'envoi » (rafraîchir `GET /{id}` pour les compteurs). Erreurs : 409 `CAMPAIGN_ALREADY_SENT` « Cette campagne est déjà partie ou annulée : elle ne peut plus être modifiée » ; 409 `CAMPAIGN_SENDING_IN_PROGRESS` « Une autre campagne est en cours d'envoi : attendez qu'elle se termine » ; 409 `WHATSAPP_NOT_CONNECTED` ; 409 `WHATSAPP_TEMPLATE_NOT_APPROVED` ; 422 `CAMPAIGN_NOT_READY` (message français précis : modèle, variables, média) ; 422 `CAMPAIGN_NO_RECIPIENT` « Aucun destinataire ne peut recevoir cette campagne » ; 422 `CAMPAIGN_TOO_MANY_RECIPIENTS` (> 5 000) ; 403 `QUOTA_EXCEEDED` (carte « Mon plan »). Afficher `error` tel quel.
- Nouveau : `POST /api/v1/whatsapp/campaigns/{id}/cancel` (campagne programmée → « Annulée ») ; `GET /api/v1/whatsapp/campaigns/{id}/recipients[?after=<chiffres du dernier numéro>]` `{clientName, phone, storeName, status, statusLabel, error}` (100 par page).
- Nouveau : `POST /api/v1/whatsapp/media` (multipart `file`, JPEG/PNG ≤ 5 Mo, MP4/PDF ≤ 10 Mo) → `{mediaId}` ; puis `headerMediaId` dans `POST /api/v1/whatsapp/conversations/{phone}/template` ou dans la campagne ; sinon `headerMediaLink` (https). Un modèle sans en-tête média refuse les deux (400).
- Client : `marketingOptOutAt` / `marketingEligible` (20.2) changent quand le client répond STOP / START (rien à appeler).
- Campagne programmée : si elle ne peut pas partir à l'heure (modèle supprimé ou plus approuvé, plus aucun client, trop de clients), elle repasse « Brouillon » (date effacée) ; un fichier joint (`headerMediaId`) expire après 30 jours chez Meta : programmer plus loin avec un fichier est refusé (400 « Un fichier joint expire après 30 jours… »), utiliser un lien https.
- Quota : la métrique `WHATSAPP_MESSAGES` « Messages WhatsApp » apparaît dans l'usage de « Mon plan » (afficher `label`).

**À implémenter côté mobile :**
1. Menu « Campagnes WhatsApp » (owner, module WhatsApp Business) : liste avec `statusLabel`, compteurs, date ; création / modification (nom, boutiques par leur nom, modèle approuvé, une ligne par variable : « Texte fixe » / « Nom du client » / « Nom de la boutique » + valeur de repli, média si `headerFormat` image / vidéo / document, date d'envoi facultative) avec l'aperçu « N clients recevront ce message » (`preview-targeting`, « Plus de 5 000 clients » si `overLimit`).
2. Bouton « Envoyer » avec confirmation ; « Annuler » pour une campagne programmée ; suppression d'un brouillon.
3. Détail : compteurs (Envoyés, Délivrés, Lus, Échecs, Non envoyés, Incertains) rafraîchis toutes les quelques secondes pendant « En cours d'envoi », liste des destinataires (`statusLabel`, motif).
4. Messagerie : envoi d'un modèle à en-tête média (joindre une image / vidéo / un PDF, ou coller un lien https).
5. Fiche client : la mention « Le client s'est désinscrit » (20.2) suit les STOP reçus sur WhatsApp.
6. En ligne uniquement ; jamais d'identifiant à l'écran (ids, `mediaId`, codes de statut).

**Implémentation de référence :** aucune dans `keevo/app` ; écrans Bref `bref/features/whatsapp-marketing` (campagnes clients), chemins à préfixer par `/api`.

**Critères d'acceptation mobile / scénarios de test :**
- Given 3 clients avec accord dont deux partagent un numéro et 1 sans accord, When l'owner prévisualise, Then « 2 clients » ; When il envoie, Then la campagne passe « En cours d'envoi » puis « Envoyée » avec 2 envoyés.
- Given un client qui écrit STOP au numéro de la boutique (curl local : POST signé sur `/api/v1/whatsapp/webhook`, secret dev `dev-whatsapp-app-secret`), Then sa fiche affiche « Le client s'est désinscrit » et l'aperçu ne le compte plus.
- Given un modèle à en-tête image approuvé, When l'owner joint une image, Then le client reçoit le modèle avec l'image.
- Given une autre campagne en cours d'envoi, When l'owner en envoie une seconde, Then le message « Une autre campagne est en cours d'envoi… » s'affiche.

**Clé de suivi mobile :** `m21-6-campagnes`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée (commit `11aba29a` (+ `1a963fdc`, `9d91fd8d`), clé `d21-6-campagnes`). Contrat : `keevo/contracts/admin-campaigns.json`. Conventions : spec-16-11 ; jeton SUPER_ADMIN, toutes les routes sous `/api/v1/admin/**` (un owner reçoit 403).

**Ce qui est fait côté API :** campagnes plateforme (Bref `/v1/campaigns`, route K1 de 20.13) vers les propriétaires des entreprises d'un segment (offres, pays et activité d'une boutique active, entreprises choisies) par notification (gardée dans la boîte de l'app), WhatsApp (modèle approuvé du numéro plateforme), SMS et e-mail ; envoi immédiat ou programmé, en arrière-plan ; compteurs par canal ; historique audité (qui a créé, modifié, lancé, annulé, supprimé ; fin d'envoi). Les numéros qui ont répondu STOP au numéro plateforme sont exclus des campagnes WhatsApp. Messagerie du numéro plateforme (conversations, réponse dans les 24 h), liste de ses modèles, dépôt d'un média d'en-tête.

**À utiliser** (code : `keevo/backend/src/main/java/com/keevo/admin/campaign/adapter/in/rest/AdminCampaignController.java`, `whatsappcloud/platform/adapter/in/rest/AdminWhatsAppController.java`) :
- `GET /api/v1/admin/campaigns[?before=&beforeId=]`, `GET /api/v1/admin/campaigns/{id}` (avec `stats` par canal et `history` `{actionLabel, actorName, detail, at}`), `POST /api/v1/admin/campaigns`, `PATCH /api/v1/admin/campaigns/{id}`, `DELETE /api/v1/admin/campaigns/{id}`, `POST /api/v1/admin/campaigns/{id}/send`, `POST /api/v1/admin/campaigns/{id}/cancel`, `POST /api/v1/admin/campaigns/preview-targeting` `{segment}` → `{owners, byChannel, overLimit}`.
- Corps : `{name, description?, channels: [PUSH|WHATSAPP|SMS|EMAIL], title (notification / objet de l'e-mail), message (≤ 1000, ≤ 480 avec SMS), segment: {planCodes?, countries?, activities?, tenantIds?}, whatsapp?: {templateName, language, bodyText, headerFormat, headerMediaId? | headerMediaLink?, variables: [{source: STATIC|FIRST_NAME|LAST_NAME|FULL_NAME|COMPANY_NAME, value?}]}, scheduledAt?}`. Les variables suivent l'ordre des variables du modèle (`{{1}}`, `{{2}}`… ou nommées dans l'ordre d'apparition).
- Erreurs : 400 (message français du champ), 404 `CAMPAIGN_NOT_FOUND`, 409 `CAMPAIGN_ALREADY_SENT`, 422 `CAMPAIGN_NOT_READY` (« Rédigez le message de la campagne », « Donnez un titre… », « Choisissez le modèle WhatsApp… », média), 422 `CAMPAIGN_NO_RECIPIENT`, 422 `CAMPAIGN_TOO_MANY_RECIPIENTS` (> 50 000 envois), 503 `PLATFORM_WHATSAPP_UNAVAILABLE` « Le numéro WhatsApp de la plateforme n'est pas configuré ».
- L'aperçu compte en une requête : `WHATSAPP` / `SMS` = propriétaires avec un numéro utilisable, `EMAIL` = avec un e-mail ; les numéros désinscrits (STOP) sont écartés à l'envoi (compteur « Non envoyés »). Une campagne programmée qui ne peut pas partir repasse « Brouillon » (historique « Modifiée » par « Système »).
- Numéro plateforme : `GET /api/v1/admin/whatsapp/conversations`, `GET …/conversations/unread-count`, `GET …/conversations/{phone}/messages[?before=&beforeId=]`, `POST …/conversations/{phone}/messages {text}` (422 `WHATSAPP_REPLY_WINDOW_CLOSED`), `GET /api/v1/admin/whatsapp/templates` `{name, language, status, statusLabel, category, headerFormat, bodyText, variableCount}`, `POST /api/v1/admin/whatsapp/media` (multipart) → `{mediaId}`.

**À implémenter côté dashboard :**
1. Écran « Campagnes » : liste (nom, `statusLabel`, canaux par `channelLabels`, date, compteurs), création / modification (canaux, titre, message avec compteur 480 si SMS, segment : offres du catalogue, pays, activités par leur libellé, entreprises par leur nom — jamais l'id —, réglages WhatsApp en choisissant un modèle approuvé de `GET /admin/whatsapp/templates` puis une source par variable, média), aperçu par canal, envoi / programmation / annulation / suppression avec confirmation.
2. Détail : compteurs par canal (Ciblés, Envoyés, Délivrés, Lus, Échecs, Non envoyés, Incertains), historique (`actionLabel`, `actorName`, date), rafraîchi pendant l'envoi.
3. Écran « Messagerie WhatsApp plateforme » : conversations, badge non lus, fil, réponse (champ désactivé hors fenêtre de 24 h).
4. Aucun identifiant à l'écran.

**Critères d'acceptation dashboard / scénarios de test :**
- Given un segment « offre BUSINESS, pays CM », When le super-admin prévisualise puis envoie sur les 4 canaux, Then la campagne passe « Envoyée », les compteurs par canal s'affichent et l'historique montre « Créée », « Envoi lancé », « Envoi terminé ».
- Given un propriétaire qui a répondu STOP au numéro plateforme, When une campagne WhatsApp part, Then il est compté « Non envoyé ».
- Given un message reçu sur le numéro plateforme, When le super-admin ouvre la messagerie, Then la conversation apparaît et il peut répondre dans les 24 h.

**Clé de suivi dashboard :** `d21-6-campagnes`.

## Auto Run Result

Status: done

**Summary.** (commits `11aba29a`, `1a963fdc`, `9d91fd8d`) Client WhatsApp campaigns in the `whatsapp-cloud` module (tenant V2: `whatsapp_campaigns`, `whatsapp_campaign_recipients`, `whatsapp_messages.campaign_id`): audience = clients with the team's consent, no STOP, not archived, one per Meta number, of the chosen stores (crm port, crm V4 indexes); send started by a guarded DRAFT|SCHEDULED → SENDING transition that freezes template and audience; asynchronous batches by a work-queue dispatcher (`public.whatsapp_campaign_jobs`, SKIP LOCKED + lease renewed per job, ≤ 40 messages per 2 s tick, half-lease send budget, 60 s pause on Meta's rate limits), consent re-checked per batch, quota `WHATSAPP_MESSAGES` probed at start and counted per batch, at-most-once (UNCERTAIN never resent), counters moved by Meta's statuses (order-independent deltas); scheduling, cancel, back to draft when a scheduled one cannot leave; recipients page. Platform campaigns in `admin.campaign` (public `platform_campaigns`, `_recipients`, `_stats`, `_events`): segment by plan / store country / store activity / companies (chunked `UNION ALL` for stores), owners one per person, Strategy per channel (push with inbox row, WhatsApp template of the platform number through the shared `PlatformWhatsApp` port, SMS, email), counters per channel, audited history. STOP / START on company numbers (client opt-out through crm, devices pull it) and on the platform number (`whatsapp_marketing_opt_outs`), confirmation once per minute. Template sends with a media header (uploaded media id or https link). Platform-number inbox and template list for the super-admin. Account deletion purges the 21.6 rows (jobs, owner contact data of platform recipients).

**Files.** V69; `db/modules/whatsapp-cloud/V2__campaigns.sql`, `db/modules/crm/V4__clients_marketing_indexes.sql`; `whatsappcloud/{campaign,consent,platform}/**`, webhook (platform routing, keywords, status changes), inbox (platform key, media header, campaign id), graph (listing parse, fake 997 rate limit), `MetaSends`, `PlatformNumberSettings`, `TemplateComponents`, media upload; `admin/campaign/**`, `TenantTable.STORE_PROFILE`; crm API (`marketingAudience`, `countMarketingAudience`, `setMarketingOptOutByPhone`); `UsageMetric.WHATSAPP_MESSAGES`; shared `PlatformWhatsApp` port and catalog events; error codes / messages; audit labels; `TenantDeletionService`; config (application, dev, test); contracts `whatsapp-cloud.json`, `admin-campaigns.json`; `docs/entitlements/endpoints.md`; tenant-shape fixture; tests (2 full-context classes, unit tests, pinned migration / quota / scheduler-classification tests updated).

**Review.** 5 layers (blind, edge case, verification gap, intent alignment, FUS-NFR11): 47 findings — high 0, medium 15, low 22, false 4, maybe-false 6. Patched 28 (15 medium, 13 low), rejected 19 with reasons (triage log), deferred 0. Routed: Bref purchase-behaviour targeting → `21-11-ciblage-achats` (new key + brief).

**Follow-up review recommended: true** — 15 medium entries patched on a first pass; unverified risk: the real Meta wire format of template sends with media headers / named parameters and Meta's rate-limit codes under production throughput (fake Graph only), and the lease / budget behaviour with several instances.

**Verification.** Compile OK. Tests per package (before the last rebase): architecture 153, whatsappcloud 58, admin 697 (campaign class re-run green after the history-order fix), crm 240 (module tests re-run green after the V4 pins), subscription 153 (plan re-run 111 green), identity/auth 517, shared 1 354 (module 555 + config 118 re-run green), messaging 370. After the rebase on 37b09ccc (16-6, 19-11b, 19-9c): architecture 154, whatsappcloud 58, admin 711, messaging 404, reporting 463 (export contract regenerated, then green), crm 240, subscription 153, identity/auth 518, shared 1 359 with 1 failure that is not 21.6's: `TenantSchemaInterpolationGuardTest` flags `AdminAnalyticsService` / `AdminCatalogService` lines added by 19-11b (3e0b6000) — reported to the coordinator. `EndpointEntitlementCoverageTest` regenerated `docs/entitlements/endpoints.md`. Live keevo_dev (dev profile, WhatsApp noop, FCM off, fake Graph, fake platform number, fresh company KV-87CTYK, kv_11b5zm untouched): preview 3 (non-consenting excluded), create « Brouillon », send « En cours d'envoi » then « Envoyée » 3 sent, second send 409, read status → délivrés 1 / lus 1, recipients with French labels, body with the store name, quota counter 3, tenant audit (created, send started, completed); STOP → client not eligible, 1 confirmation, preview 2; START → eligible again; media template without media 400, with https link 200; platform inbox (super-admin 200, owner 403), reply, platform STOP recorded; platform campaign over 4 channels to the test company only → push 1 (inbox row), WhatsApp 1, SMS failed (no SMS provider in dev, nothing sent), email 0 (no address), history Créée / Envoi lancé / Envoi terminé; owner on `/admin/campaigns` 403; no token in the logs.

**Residual risks.** Real Meta behaviour (above); platform campaigns share the sign-in number unless a dedicated one is configured; the keyword confirmation cooldown is per instance.

