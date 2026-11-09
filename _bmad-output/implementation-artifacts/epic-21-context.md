# Epic 21 Context: Monétisation en libre-service et engagement (P1b)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Let an owner pay for their plan on their own (Mobile Money on Android, App Store on iOS) and have Keevo collect the payment and activate the plan's modules without super-admin action, with the entire commercial offer (plans, prices, trials, discounts) managed as data from the super-admin dashboard. In parallel, bring over Bref's engagement features — AI assistant, WhatsApp Business, client campaigns, client invoice portal — so a migrated Bref owner loses nothing. All AI goes through a single tool layer built on Keevo use cases, which is also exposed to external AI clients over MCP. This epic is part of the API parity required before the refonte go-live (milestone GL).

## Stories

- Story 21.1: Module `payments` — Mobile Money (MyCoolPay)
- Story 21.2: Achats intégrés iOS (StoreKit / RevenueCat) et abonnements externes
- Story 21.3: Crédit de compte et packs SMS
- Story 21.4: Module `ai-assistant` — assistant par outils, parité « Bref Point »
- Story 21.5: Module `whatsapp-cloud` — compte, boîte de réception, modèles
- Story 21.6: Campagnes clients et campagnes plateforme
- Story 21.7: Module `client-portal`
- Story 21.8: Couche d'outils IA sur les use cases (MCP-ready)
- Story 21.9: Serveur MCP externe
- Story 21.10: Catalogue d'offres entièrement configurable — plans, prix, essais, remises

## Requirements & Constraints

- **Self-service payment**: a confirmed payment activates the plan's bundle and extends the expiry with no super-admin step. Port Bref's hardening: one pending checkout per tenant, atomic `INITIATING` lock on pay (recoverable after a timeout), transaction reference checked at OTP, credit read with a row lock, idempotent webhooks (deliveries can repeat), stale events ignored, sandbox subscriptions filtered in production, Apple grace period respected, `TRANSFER` and refund (refund means revocation) handled.
- **Migration continuity**: Bref webhooks (MyCoolPay, RevenueCat, Meta) and WhatsApp Business accounts must keep working before, during and after each owner's migration. A webhook for an owner Keevo doesn't know yet (still on api-bref) is ignored without error. The RevenueCat `app_user_id` of migrated owners stays valid because user UUIDs are preserved. Processed RevenueCat events are imported so nothing replays.
- **Account credit and SMS packs are real money**: they are kept, displayed and usable. Movements are append-only and the balance is never negative. Credit can be applied first to a plan payment if the owner chooses. Pack consumption needs a history (who, when, which counter), shown in "Mon plan".
- **Payments priced from the offer catalogue**: every paid path quotes through `subscription.api.OfferPricing.quote(tenant, plan, cycle, promoCode)` at initiation and stores the quote (base, discount, amount due); confirmation applies the stored price, never the current one. A zero quote is a free offer with no provider call. Promo-code uses are counted on confirmation (cap taken atomically). Confirmed payments write `tenant_payments.plan_code`/`cycle_code`. 21.1 implements all of this for Mobile Money; 21.2 and 21.3 must follow the same rules.
- **Apple price gap**: Apple prices are set in App Store Connect; read StoreKit products via RevenueCat and show the super-admin, in the admin offer catalogue, the gap between the Apple price and the configurable grid per plan and cycle.
- **Offer as data**: no plan code, label, price, ceiling, quota, trial length or discount is hard-coded (Flyway seeds and a logged safe fallback excepted; this is a blocking review check). Bref's XAF price grid is seed data only. Every change is validated with French messages, audited (author, date, old and new value), and applies right away without redeploying the API or the app. Archiving a plan never affects the tenants already on it. A payment already started keeps the price shown when it started. Apple prices live in App Store Connect, so the configurable grid covers Mobile Money and granted plans, and any gap between the two is shown to the super-admin.
- **AI**: guided topics are free (no model call, no credit). Free-text chat answers only by calling tools, never by generating or running SQL. Quota is charged once per question, never on a failure, and only one question can run per account at a time. Conversations are capped at 20 messages. The response shapes the Bref app consumes are kept. A parity corpus of at least 30 real questions with exact figures is an acceptance gate.
- **Isolation everywhere**: AI tools, the MCP server and the portal must refuse or filter exactly as the REST API does (tenant, module, role, employee scope). Each needs explicit isolation tests. The portal shows the bare minimum of fields per document.
- **Campaigns**: client campaigns target only clients with active marketing consent. Sending is async, with sent/delivered/failed tracking, and counts against quotas. Platform campaigns target tenant segments over push, WhatsApp, SMS and email, and are audited.
- **WhatsApp**: WABA tokens are encrypted at rest. Meta webhook signatures (`X-Hub-Signature-256`) are verified. The template approval cycle and the 24 h window are respected. Invoices and quotes go out from the owner's WABA number (`bref_facture` template), never the platform number, with platform free text as the fallback.
- **Transverse**: no technical data (UUIDs, raw codes) in user-facing output, French labels only. Flutter V1 2.0.x compatibility on `/api/v1` (no `null` where V1 expects a value). Core writes (POS sale, payment) are never blocked by quota.

## Technical Decisions

- **New modules** follow the existing registry: manifest, `db/modules/<id>/`, per-role permissions, `@RequiresEntitlement`, `DeltaEntityProvider` where synced. The registry modules are `ai-assistant`, `whatsapp-cloud` (BUSINESS), `client-portal` (platform/global), and the paid `mcp-access`. `payments` (21.1) is a backend Modulith module holding public platform data only (no manifest, no tenant DDL).
- **Activation path**: payments apply the plan in one transaction through `PlanAdministrationPort.changePlan` (subscription row, public plan, `tenant_plan_events`, paid-module rows), with a dedicated plan-change cause. 21.1 established the patterns to reuse: provider behind a strategy port (real / sandbox / disabled by config, fail-fast validation), state machine on `payment_requests` with guarded single-statement transitions and one in-flight request per tenant (partial unique index), idempotent webhook consumer (row lock + terminal state + unique ledger key), server-to-server re-verification, unknown reference answered OK and ignored. Writes go to `tenant_payments` and `external_subscriptions` (provider, original transaction id, product, status, expiry, environment), with `revenuecat_webhook_event` for deduplication. New global tables live in `public`, and tenant data in `kv_x` schemas.
- **Quotas** reuse `public.usage_counters` from 19.8 with an atomic single-statement increment. Purchased packs are consumed after the monthly quota.
- **AI tool layer (21.8)**: one `{Module}McpTools` bean per module in `adapter/in/mcp/` (Spring AI `@Tool`), taking existing `…Query` records as parameters, with French descriptions and structured results. Actor and tenant always come from the session, never from tool parameters. Every call is audited (tool, actor, duration, no personal content). An architecture test (sibling of `EndpointEntitlementCoverageTest`) fails on a tool with no module or with an actor/tenant parameter, and `docs/ai/tools.md` is generated. The layer is read-only for now; write tools will require explicit confirmation. It replaces `AuditMcpPlaceholder`. Check that the Spring AI version is compatible with the backend's Spring Boot.
- **AI provider** is configurable (OpenAI / Gemini / Ollama, ported from Bref) and receives only the tools this user is authorised to call. Conversations are stored in `kv_x.ai_conversations`.
- **External MCP (21.9)**: Spring AI over HTTP/SSE. Authentication uses personal tokens created by the owner (revocable, limited to chosen tools, expiring), with revocation taking effect immediately (same mechanism as 16-11 sessions). Calls count against the tenant's AI quota. When the module is not entitled, the answer is `MODULE_NOT_ENTITLED`.
- **Client portal**: global `public.portal_clients`, separate from merchant accounts, with OTP login from 19.2. `public.invoice_phone_index(phone_normalized, tenant_id, invoice_id)` is fed at issue and at import. Each document is read from its own tenant's schema. A per-tenant switch turns the portal off.
- **WhatsApp**: one Meta webhook per Meta app, routed to the tenant by `phone_number_id`. The platform WhatsApp provider and the per-tenant WABA module coexist. Bref's providers take priority (Meta Cloud, MboaSMS/Infobip, Resend, Expo).
- **Performance rule (mandatory)**: pick the right pattern; no N+1, set-based SQL, keyset pagination, an index for every new filter/join/sort, batched fan-out for campaigns. Each spec states its pattern and hot-path complexity.
- **Scope of this repo**: backend only. Each spec needs a mobile section (the RN app lives in the Bref repo) and a "Dashboard super-admin (autre dépôt)" section for every admin feature. The offer catalogue must be fully editable from the dashboard (no read-only screens). API contracts go in `keevo/contracts/`.

## UX & Interaction Patterns

- "Mon plan" shows the plan, expiry, quotas, credit and pack balances, the current prices and any discount (struck-through price, promotion end date), all read from the API. Payment is Mobile Money on Android (USSD/OTP, the API returns the next step) or in-app purchase on iOS. On confirmation, modules activate without a restart and the owner is notified.
- The AI assistant offers guided topics (diagnostic, revenue, unpaid, collections, sellers, stores, clients; period, comparison, store → sellers drill-down, unpaid call list) plus free text. Errors return clear French messages.

## Cross-Story Dependencies

- Epic 21 depends on 19.7 (plans as data), 19.8 (quotas, SMS packs), 19.9 (notifications, plus 19.9b for WhatsApp Meta and Web Push), 19.2 (OTP for the portal), 16-13 (employee scope), 16-11 (session revocation), and 20.2 (client consent, per-store clients).
- AI order is strict: 21.8 → 21.4 → 21.9.
- 21.10 (done) delivered the configurable offer catalogue and the `OfferPricing` port; it closed the 19.7a follow-ups (plan kind change, archived plans refused, revenue split by plan and cycle). Its items routed to 21.1 are done (frozen price, free offers, promo counter + cap, `plan_code`/`cycle_code`); the Apple price gap remains open on 21.2.
- 21.1 (done) delivered the `payments` module, `payment_requests` and Mobile Money checkout/webhook. 21.2 reuses its confirmation/ledger path for App Store payments; 21.3 plugs credit imputation into its checkout (credit applied first when the owner chooses).
- 21.6 overlaps with the Bref campaign admin routes (20.13) and uses the 21.5 WABA channel. 21.5 changes invoice sending in `invoicing` (`SendInvoiceUseCase`).
- Epic 24 relies on this epic: the webhook dual-delivery router (24.10), the monetisation import (credit, packs, subscriptions with cause `MIGRATION_BREF`), the portal index fill, and the WABA import (24.6). The AI parity corpus is an acceptance criterion for the migration wave.
