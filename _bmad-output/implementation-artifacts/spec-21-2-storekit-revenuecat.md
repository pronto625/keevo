---
title: '21-2 — App Store in-app purchases (StoreKit through RevenueCat): external subscriptions, authenticated idempotent webhook, App Store price gap'
type: 'feature'
created: '2026-11-09'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: '9709226be3f0dcb558cb7b3076821ce88d09ed17'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-1-payments-mobile-money.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** iPhone owners pay Bref Point through the App Store (StoreKit, RevenueCat); Keevo has no external subscription, no RevenueCat webhook and no product → plan mapping, so a migrated iOS owner's Apple subscription would stop driving the plan (FUS-FR9, FUS-MIG8). The 21.10 item routed here: the super-admin must see the gap between the Apple price (set in App Store Connect) and the configurable grid.

**Approach:** Extend the `payments` module (21.1) with an `appstore` slice: public V62 tables `external_subscriptions`, `revenuecat_webhook_event` (dedup journal, name kept for the epic-24 import), `store_products` (App Store product → plan × cycle mapping = offer data, owned by `subscription.offer`, seeded with Bref's six products) and the `APP_STORE` ledger method. Bref's `StoreKitService` is ported: an authenticated (Authorization header secret, constant time) RevenueCat webhook processed in ONE transaction (claim by event id + state change + plan + ledger), sandbox filtered unless allowed, stale events ignored, Apple grace honoured, refund = revocation, TRANSFER reconciled through the RevenueCat REST API behind a port (real / disabled; fake in tests). The plan is applied through the same 9-2 unit of work as 21.1 (`PlanAdministrationPort.changePlan`, cause `SELF_SERVICE_PAYMENT`, back to the free plan with cause `EXPIRY`). The admin catalogue gains the App Store products with Apple price, grid price and gap.

## Boundaries & Constraints

**Always:**
- Webhook `POST /api/v1/payments/webhook/revenuecat` (public path already open): the `Authorization` header must equal `REVENUECAT_WEBHOOK_SECRET` (constant time, empty secret or header never matches) → else 401, nothing read or written. Default config (no secret) refuses every call; nothing else breaks.
- One transaction per event: `INSERT … ON CONFLICT (id) DO NOTHING` claims the event (a concurrent duplicate waits and then sees the conflict); duplicate → 200, no change. Any failure rolls the claim back → 503 → RevenueCat redelivers. HTTP to RevenueCat (TRANSFER only) happens BEFORE the transaction.
- Subscription row locked `FOR UPDATE` (unique `(store, original_transaction_id)`); a state event older than `last_event_ms` → outcome STALE, no change. Tenant plan read locked (`currentPlan`).
- Sandbox (`environment=SANDBOX`) ignored unless `keevo.payments.app-store.accept-sandbox=true` (allowed only under the `dev`/`test` profiles, refused at startup otherwise) or the user's email equals `REVIEW_ACCOUNT_EMAIL` (Apple review account, Bref parity). Only `store=APP_STORE` events act; others → IGNORED.
- Tenant resolution: existing binding of the original transaction → else subscriber attribute `keevo_tenant` (tenant code) among the companies the user OWNS → else the user's single owned company → else UNRESOLVED (200, the owner's sync binds later). Unknown user (owner still on api-bref, 24.10) → 200 ignored. An original transaction bound to another company is never moved except by TRANSFER.
- Grant rule: target expiry E = `expiration_at_ms` (or the grace end); E ≤ now → no plan change; same plan already running until ≥ E → no change (no duplicate plan event on replay or sync-then-webhook); else `changePlan(product plan, E, SELF_SERVICE_PAYMENT, actor = app_user_id)` (another plan starts now, as 21.1).
- Revocation rule (EXPIRATION, refund, TRANSFER-out): back to the catalogue's free plan (cause EXPIRY, reason in French) ONLY when the company's current plan is the subscription's plan and its expiry is not after the expiry this subscription granted (+ 1 min): a later Mobile Money payment is never cut.
- Ledger: INITIAL_PURCHASE / RENEWAL with price > 0 write one `tenant_payments` row (method `APP_STORE`, plan, cycle, period purchase date → expiry date WAT, `store_transaction_id` unique → idempotent). Amount XAF: purchase currency XAF → as is; EUR → × 655.957 (fixed peg), else the product's declared Apple price XAF; none known → no row (logged).
- Product mapping and prices are data (no product id in Java); an inactive product still grants renewals (existing subscribers), it is only hidden from the app list.
- Secrets from env only (`REVENUECAT_WEBHOOK_SECRET`, `REVENUECAT_SECRET_KEY`, `REVENUECAT_PROJECT_ID`, `REVIEW_ACCOUNT_EMAIL`), never logged; no payload dump in logs; app_user_id logged only as the tenant id.
- French user texts, no id/code in a message; owner endpoints OWNER only; additive changes only on existing responses; set-based SQL, indexes for every new lookup, constant queries per event (FUS-NFR11).

**Never:**
- No real RevenueCat/Apple call in tests or the live check; no edit of a committed migration; no `keevo/app` change; no Play Store / Stripe handling; no account credit / SMS packs (21.3); no `OfferPricing` quote for Apple (Apple prices live in App Store Connect — PRD); no WhatsApp for App Store events (push + inbox only); no dashboard/mobile feature work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Initial purchase | owner (1 company) INITIAL_PURCHASE `brefpoint.pro.month`, PRODUCTION, price 2 500 XAF, exp +1 month | sub ACTIVE bound; plan PRO until exp; plan event SELF_SERVICE_PAYMENT; ledger APP_STORE 2 500 PRO/MONTHLY; observed price stored; push « Abonnement App Store actif » | — |
| Replay | same event id again | nothing changes | 200 |
| Renewal | RENEWAL newer, exp +1 month | expiry moved, second ledger row | — |
| Stale | RENEWAL older than last processed | outcome STALE, no change | 200 |
| Bad secret | wrong / missing Authorization | nothing written | 401 |
| No secret configured | default config | every call refused | 401 |
| Sandbox in prod | SANDBOX, accept-sandbox false, not review account | IGNORED_SANDBOX | 200 |
| Review account | SANDBOX, email = REVIEW_ACCOUNT_EMAIL | applied | — |
| Unknown user | app_user_id unknown / not UUID | ignored | 200 |
| Several companies | owner of 2, no attribute | UNRESOLVED | 200 |
| Attribute | `keevo_tenant`=KV-… owned | bound to it | — |
| Unknown product | not in store_products | UNKNOWN_PRODUCT, WARN | 200 |
| Cancellation | CANCELLATION (auto-renew off) | status CANCELLED, plan kept; push « Renouvellement automatique désactivé » | — |
| Refund | CANCELLATION cancel_reason CUSTOMER_SUPPORT | REVOKED, free plan now, ledger row of that transaction voided « Remboursé par Apple » | — |
| Billing issue + grace | BILLING_ISSUE with grace_period_expiration_at_ms | status BILLING_ISSUE, plan expiry = grace end; push « Problème de paiement App Store » | — |
| Billing issue no grace | no grace field | status BILLING_ISSUE, plan untouched | — |
| Expiration | EXPIRATION | EXPIRED, free plan (cause EXPIRY); push « Abonnement App Store terminé » | — |
| Expiration after MoMo | owner paid Mobile Money beyond the Apple expiry | EXPIRED, plan kept | — |
| Uncancellation | UNCANCELLATION | ACTIVE, auto-renew on | — |
| Transfer | TRANSFER from A to B | API read per user: A's rows not active any more → TRANSFERRED + revoked; B's active subs bound to B's company and granted | client disabled / RC error → 503 (claim rolled back) |
| Test event | TEST | recorded TEST | 200 |
| Sync | owner `POST /payments/app-store/sync`, RC says active PRO | bound + granted, view returned | disabled → 503 PAYMENT_UNAVAILABLE; RC error → 502 PAYMENT_PROVIDER_ERROR; none → 404 PAYMENT_NOT_FOUND; bound elsewhere → 409 INVALID_STATE |
| Gap | PRO×MONTHLY grid 3 500, declared Apple 2 500 | catalogue `storeProducts[].gapXaf = -1000` | — |

</intent-contract>

## Code Map

- Bref (read-only): `/home/toor/Project/FreeLance/AI/api-bref/src/storekit/{storekit.service.ts, storekit-webhook.controller.ts, constants/apple-products.constant.ts}`, migration `1753700000000-revenuecat-webhook-idempotency…` — secret compare, event claim + stale check, TRANSFER via REST v2 (`/projects/{p}/customers/{id}/subscriptions`, `/projects/{p}/products` → `store_identifier`), sandbox filter + review account, BILLING_ISSUE grace, one owner per original transaction.
- `db/migration/V62__app_store_subscriptions.sql` -- NEW (last public V61).
- `payments/package-info.java` -- add `identity :: api` already allowed; nothing new.
- `payments/appstore/**` -- NEW slice (domain model, ports, JDBC store, RevenueCat client + config, services, listener, controllers).
- `payments/checkout/adapter/out/persistence/JdbcPaymentRequestStore` LEDGER_SQL -- pattern for the ledger INSERT.
- `subscription/api/PlanAdministrationPort` (MANDATORY tx), `shared/capability/entitlement/PlanCatalog.freePlan()/find()`, `PlanChangeCause.SELF_SERVICE_PAYMENT/EXPIRY` -- reused activation path.
- `subscription/api/StoreProducts.java` -- NEW port (find by store+product, active list, record observed price) implemented in `subscription/offer`.
- `subscription/api/OfferAdministration.java` -- `CatalogueView` + `storeProducts` (3-arg constructor kept), `StoreProductView`, `StoreProductInput`, `storeProducts()`, `saveStoreProduct(...)`.
- `subscription/offer/{domain/model/StoreProduct.java, OfferEntity (+STORE_PRODUCT), domain/port/out/OfferStore, adapter/out/persistence/JdbcOfferStore, application/service/OfferCatalogService, application/service/StoreProductService}` -- mapping CRUD, gap computation, journal.
- `admin/offer/adapter/in/rest/AdminOfferController.java` + `dto/OfferRequests.java` -- `GET /store-products`, `PUT /store-products/{productId}`.
- `identity/api/UserQueryPort` + `identity/auth/adapter/in/api/UserQueryAdapter` -- `findOwnedTenants(userId)` (record `OwnedTenantRef(code, schema)`), `findEmail(userId)`.
- `admin/subscription/domain/model/PaymentMethod` (+APP_STORE), `JdbcTenantPaymentRepository` (online = request or store transaction), `TenantPaymentDto` (label « Paiement en ligne (App Store) »).
- `application.yml`, `application-dev.yml`, `test/resources/application-test.yml`, `.env.example` -- `keevo.payments.app-store.*`.
- Tests to mirror: `payments/PaymentsFullContextTest` (TestAccess, cleanup), `MyCoolPayGatewayTest` (MockRestServiceServer), `PaymentGatewayConfigTest`, `RegistryEventSerializationTest`, `OfferCatalogServiceTest`.

## Tasks & Acceptance

**Execution:**
- `V62__app_store_subscriptions.sql` -- `external_subscriptions(id, tenant_id FK CASCADE, store CHECK APP_STORE, app_user_id UUID, original_transaction_id, product_id, plan_code FK, cycle_code FK, status ∈ {ACTIVE, CANCELLED, BILLING_ISSUE, EXPIRED, REVOKED, TRANSFERRED}, auto_renew, environment ∈ {PRODUCTION, SANDBOX}, expires_at, grace_expires_at, granted_expires_at, last_event_ms, created_at, updated_at)`, unique (store, original_transaction_id), index (tenant_id, updated_at DESC), index (app_user_id); `revenuecat_webhook_event(id PK, type, app_user_id, original_transaction_id, environment, event_timestamp_ms, tenant_id, outcome, processed_at)` + index (tenant_id, processed_at DESC); `store_products(store, product_id, plan_code FK, cycle_code FK, active, declared_price_xaf, observed_price numeric, observed_currency, observed_price_xaf, observed_at, created_at, updated_at, PK(store, product_id))` + index (plan_code, cycle_code) + seed of Bref's 6 products when plan and cycle exist (ON CONFLICT DO NOTHING); `tenant_payments` method check + APP_STORE, `store_transaction_id` + unique partial index; `offer_change_log` entity check + STORE_PRODUCT (NOT VALID then VALIDATE, as V61).
- `payments/appstore/domain/model/*` -- `RevenueCatEvent` (parsed, tolerant), `ExternalSubscription`, `SubscriptionStatus` (French labels), `EventOutcome`, `XafAmount` (XAF / EUR peg / declared).
- `payments/appstore/domain/port/out/{RevenueCatClient, ExternalSubscriptionStore}.java` -- client: `activeSubscriptions(appUserId)` → list (original tx, store product id, expires, environment, store); store: claim event, finish event, lock/insert subscription, update state, ledger insert/void, find current of tenant, find by app user.
- `payments/appstore/adapter/out/revenuecat/{RevenueCatRestClient, DisabledRevenueCatClient, AppStoreConfig}.java` -- REST v2 with 10 s/30 s timeouts, product map cached 1 h (refreshed on a missing id), `limit=100` + `next_page` ≤ 10 pages; config: webhook secret, accept-sandbox allow-list, review email.
- `payments/appstore/adapter/out/persistence/JdbcExternalSubscriptionStore.java` -- all SQL, set-based, single statements.
- `payments/appstore/application/service/{AppStoreWebhookService, AppStoreSubscriptionService, AppStoreText, AppStoreSubscriptionView, AppStoreSettings}.java` -- webhook: auth → parse → duplicate pre-check → (TRANSFER: fetch) → tx (claim → route → apply → finish); service: grant / revoke / sync / current / products.
- `payments/appstore/domain/event/AppStoreSubscriptionNotice.java` + `application/listener/AppStorePushListener.java` -- after commit, `@DeliverOnce`, push + inbox types `APP_STORE_ACTIVE | APP_STORE_CANCELLED | APP_STORE_BILLING_ISSUE | APP_STORE_EXPIRED`, deep link `/plan`.
- `payments/appstore/adapter/in/rest/{AppStoreController, RevenueCatWebhookController}.java` -- `GET /api/v1/payments/app-store/products`, `GET /api/v1/payments/app-store/subscription`, `POST /api/v1/payments/app-store/sync` (OWNER, exempt « core: abonnement »); webhook (exempt, hidden).
- subscription / admin / identity changes -- as in the Code Map; regenerate `docs/entitlements/endpoints.md`.
- Tests -- `RevenueCatEventTest`, `XafAmountTest`, `RevenueCatRestClientTest` (MockRestServiceServer, pagination, error → provider error, no secret in exception), `AppStoreConfigTest`, `AppStoreFullContextTest` (every matrix row through MockMvc with a fake client bean), `AppStorePushListenerTest`, offer tests for the mapping CRUD + gap, `RegistryEventSerializationTest` fixture, `ApplicationModulesBaselineTest` unchanged.

**Acceptance Criteria:**
- Given an owner of one company and a signed INITIAL_PURCHASE then RENEWAL then EXPIRATION, when processed, then `/subscription/me` shows PRO until each Apple expiry and then the free plan, `tenant_payments` holds two APP_STORE rows, `tenant_plan_events` two SELF_SERVICE_PAYMENT rows (purchase, renewal) and one EXPIRY, and the super-admin payments list shows « Paiement en ligne (App Store) ».
- Given the same event delivered twice concurrently, when both are processed, then exactly one state change and one ledger row exist.
- Given V62 applied, when re-run, then nothing changes; the 21.1/21.10 endpoints keep their contracts (additive fields only).
- Given a super-admin, when `GET /api/v1/admin/offers`, then `storeProducts` lists each App Store product with plan, cycle, Apple price (declared or observed), grid price and gap.

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 45 findings — high 0, medium 13, low 26, false 6, maybe-false 0
- findings:
  - `[medium]` `[patch]` (nfr11) every purchase updates the shared `store_products` row and holds its lock for the event — the observed-price UPDATE now only writes a new price (`IS DISTINCT FROM`), so renewals at the same price take no lock.
  - `[medium]` `[patch]` (nfr11) a product never in RevenueCat's map forces a full product reload on every call, serialised under the lock — a forced reload is skipped when the map is less than 1 minute old (double-checked inside the lock); truncation after 10 pages is logged.
  - `[low]` `[patch]` (nfr11) email lookup per sandbox subscription in the stream — computed once per user (transfer) / once per sync.
  - `[low]` `[reject]` (nfr11) sync may change the plan once per active subscription — k = one owner's Apple subscriptions (1 in practice); grant is idempotent, the latest expiry is applied last.
  - `[low]` `[reject]` (nfr11) TRANSFER locks rows one old customer at a time — bounded by the payload cap (10), one indexed query each.
  - `[low]` `[reject]` (nfr11) TRANSFER reads RevenueCat sequentially in the request — bounded (≤ 20 ids), duplicates skipped by the pre-check; async processing is not worth the state at this volume.
  - `[low]` `[reject]` (nfr11) `active()` filters in Java — a table of a handful of rows read in one query.
  - `[low]` `[reject]` (nfr11) `currentOfTenant` sorts the company's rows — a few rows per company, index filters them.
  - `[low]` `[reject]` (nfr11) optional dependency passed as null in `OfferCatalogService` — kept to leave the 21.10 unit tests unchanged; the Spring path always wires it (revenue slice test imports it).
  - `[low]` `[patch]` (intent) bundle/module state not asserted — `lifecycle` asserts the PRO paid-module rows after the purchase.
  - `[low]` `[reject]` (intent) grace from RevenueCat instead of Bref's fixed 16 days — decision recorded (Design Notes « Grace »): no access Apple does not give.
  - `[false]` `[reject]` (intent) sandbox filtering in prod only inferred — `AppStoreConfigTest.devOnly` proves `accept-sandbox` is refused outside dev/test and the default is false; prod has no other path.
  - `[low]` `[reject]` (intent) FUS-MIG2 sandbox check with a migrated owner — a manual pre-wave step (no real RevenueCat call allowed); listed as a user action in the report.
  - `[low]` `[patch]` (intent) gap asserted on the PUT response only; price read from RevenueCat — the catalogue row's `gapXaf`/`gapLabel` are asserted; RevenueCat's API exposes no product price, so the Apple price is the one observed on RevenueCat purchase events or entered by the super-admin (decision).
  - `[medium]` `[defer]` (intent) `revenuecat_webhook_event` columns differ from Bref's — matters for the epic-24 import of Bref's processed events → routed to 24-5-import-monetisation.
  - `[false]` `[reject]` (intent) extra surfaces (sync, products, ledger, refund, transfer) — descriptive; carried over from Bref's StoreKitService and the architecture (« writes go to tenant_payments and external_subscriptions »).
  - `[medium]` `[patch]` (blind) a purchase left UNRESOLVED / UNKNOWN_PRODUCT never reaches the ledger after a later binding — the event row keeps the purchase details (V62 columns + partial index); binding a subscription (sync, later event, transfer) replays those purchases into the ledger (idempotent on the store transaction id) and marks them APPLIED.
  - `[medium]` `[patch]` (blind) unmapped-product events lost for good — same root cause and fix (replayed at binding once mapped).
  - `[low]` `[patch]` (blind) sync ignores RevenueCat's auto-renew and billing state — `auto_renewal_status` and `status` (grace / billing retry) are mapped; sync sets ACTIVE / CANCELLED / BILLING_ISSUE from them.
  - `[medium]` `[patch]` (blind) REFUND_REVERSED ignored — handled like a re-grant: ACTIVE, plan granted, the voided ledger row restored.
  - `[low]` `[patch]` (blind) TRANSFER/sync do not move `last_event_ms` — the TRANSFER timestamp is stored on the rows it binds, so a late event of the previous owner is stale.
  - `[low]` `[reject]` (blind) ledger records Apple's gross price — decision (gross, Design Notes « Ledger amount »); commission is Apple's, net revenue is in App Store Connect.
  - `[low]` `[patch]` (blind) re-mapping a product with subscribers is silent — the journal line records the previous mapping.
  - `[low]` `[patch]` (blind) transferred subscription announced as « terminé » — new notice kind TRANSFERRED (« Abonnement App Store transféré »); the best-effort swallow of a push failure is kept as in 21.1's listener.
  - `[low]` `[reject]` (blind) purchase can bind to a suspended company — the plan is only data there; the suspended company cannot use the API (JwtAuthFilter 403) and keeps what it paid for.
  - `[low]` `[reject]` (blind) purging a company cascades its subscription; event journal grows — a purged company has no plan to follow; a later renewal binds by the usual rule. The journal is a few rows per subscription per month.
  - `[false]` `[reject]` (blind) seeded `brefpoint.pro.month` looks wrong — it is Bref's exact constant (`apple-products.constant.ts`), kept in sync with App Store Connect.
  - `[medium]` `[patch]` (blind) RevenueCat truncation silent and reload storm — same root cause as nfr11 #2 (throttle + warning).
  - `[low]` `[reject]` (blind) test-only constructor / fully qualified names — cosmetic.
  - `[low]` `[patch]` (blind) untested paths — tests added for PRODUCT_CHANGE, REFUND_REVERSED, ledger currencies / trial, unresolved-then-sync, transfer still active; others are covered by unit tests or are trivial branches.
  - `[medium]` `[patch]` (verification-gap) ledger currency / trial / declared-price path untested end to end — `ledgerCurrencies` (USD → declared 3 000, EUR → 3 273 + note, TRIAL → no row).
  - `[medium]` `[patch]` (verification-gap) owner notices never checked where published — `@RecordApplicationEvents`: ACTIVE, ACTIVE, EXPIRED in `lifecycle`, TRANSFERRED in `transfer`, one ACTIVE for sync-then-webhook (a purchase already applied by the sync is no longer announced twice — code patched).
  - `[medium]` `[patch]` (verification-gap) TRANSFER still-active branch untested — `transferStillActive`.
  - `[low]` `[patch]` (verification-gap other) a cycle used only by an App Store product could change its months — `CYCLE_IN_USE_SQL` also checks `store_products`.
  - `[medium]` `[patch]` (edge) TRANSFER to a customer with 0 / 2+ companies leaves the row bound to the old company forever — a TRANSFERRED row can be bound by the new owner's sync.
  - `[medium]` `[patch]` (edge) old and new customer both active in RevenueCat → both companies keep the plan — originals held by the new customers are never « still active » for the old ones.
  - `[false]` `[reject]` (edge) App Store purchase replaces a longer other plan — the 21.1 rule (another plan starts today), applied identically.
  - `[false]` `[reject]` (edge) `currentPlan` empty → 503 loop — every company has its subscription row from registration (as triaged in 21.1).
  - `[low]` `[patch]` (edge) sync answers GRANTED with nothing granted (no / past expiry) — such a subscription is NOTHING_ACTIVE (404 « Aucun abonnement App Store actif… »).
  - `[low]` `[reject]` (edge) event on a DELETED (not yet purged) company — the window is short and the purge cascade removes the row.
  - `[low]` `[patch]` (edge) huge price → overflow → retry loop — prices above 10⁹ are neither converted nor observed; stored price rounded/capped to NUMERIC(14, 2).
  - `[low]` `[patch]` (edge) product mapped to an archived cycle — refused (422 « Cette durée est archivée : choisissez une durée active ») unless it is the current mapping.
  - `[low]` `[patch]` (edge) products of archived cycles listed to the app — filtered.
  - `[medium]` `[patch]` (edge) truncated RevenueCat pages — same root cause as nfr11 #2.
  - `[false]` `[reject]` (edge) empty plan label in a message — `plan_code` is a foreign key to `plans`; the label always exists.

## Design Notes

- **Where things live.** The mapping App Store product → plan × cycle is commercial offer data (21.10 rule « offer as data »): `store_products` belongs to `subscription.offer` (admin CRUD, journal, gap in the catalogue view); `payments` reads it through the new `subscription :: api StoreProducts` port and records the observed price. Subscriptions, the event journal and the ledger writes stay in `payments` (public platform data, found by provider identifiers without a tenant), as 21.1.
- **Patterns.** Ports & adapters with a **strategy** client (RevenueCat REST / disabled, fail-fast config); **idempotent consumer** = claim row inserted in the same transaction as the effects (no claim/release dance as Bref: a rollback releases it); **state machine** on `external_subscriptions.status`, ordered by `last_event_ms` (stale events dropped); **command dispatch** by event type (one handler per type); plan application reuses the 9-2 unit of work (`changePlan`) exactly like 21.1; notices go through an after-commit listener (`@DeliverOnce`).
- **Why the expiry comes from Apple.** Unlike Mobile Money (we compute the period), Apple owns the period: the plan expiry is Apple's `expiration_at_ms` (grace end during a billing issue). The nightly expiry job still downgrades at that instant if no renewal arrives; a late RENEWAL re-grants.
- **Grace.** RevenueCat sends `grace_period_expiration_at_ms` on BILLING_ISSUE when the App Store grace period is on: the plan runs to it. Without it Apple gives no access during billing retry, so nothing is extended (Bref's fixed 16 days is not ported: it granted days Apple did not).
- **Refund.** RevenueCat signals a refund as CANCELLATION with `cancel_reason = CUSTOMER_SUPPORT`: revocation now + the refunded transaction's ledger row voided (« Remboursé par Apple »).
- **Ledger amount.** `tenant_payments.amount_xaf` is XAF > 0: XAF purchases as is, EUR at the fixed peg 655.957, other currencies at the product's declared Apple price; unknown → no row (the gap screen shows « Prix App Store non renseigné »). Gross amount (Apple's commission is not deducted).
- **Tenant resolution.** A RevenueCat customer is a Keevo user (app_user_id = user UUID, kept by FUS-MIG2); a company is chosen as binding → `keevo_tenant` subscriber attribute (tenant code, set by the app before purchase) → single owned company. The owner's sync, made inside the company, binds an UNRESOLVED subscription.
- **Cost.** Webhook: 1 claim INSERT + 1 product read + ≤ 2 identity reads (unbound only) + 1 locked subscription read/upsert + 1 locked plan read + ≤ 1 plan change (9-2 path) + ≤ 1 ledger INSERT + 1 price UPDATE + 1 finish UPDATE — constant. TRANSFER: ≤ 10 users × 1 REST call (product map cached) before the tx, then one indexed read per user. Sync: 1 REST call + constant SQL. Catalogue: + 1 query (store products). No loop over tenants.
- **Review patches (2026-11-09).** The event row keeps the purchase details: a purchase that could not be applied (UNRESOLVED, UNKNOWN_PRODUCT) is recorded in the ledger as soon as its subscription is bound (sync, later event, transfer). REFUND_REVERSED re-grants and restores the voided row. Sync follows RevenueCat's renewal and billing state. A TRANSFERRED row can be bound by the new owner's sync; originals held by the new customers are never kept by old ones; the TRANSFER timestamp makes the previous owner's late events stale. The observed price is written only when it changes (no lock on the shared product row for same-price renewals); RevenueCat forced product reloads are throttled (1 min). A purchase already applied by the sync is not announced twice. Archived cycles cannot be mapped and are not offered; a cycle used by a product cannot change its months.
- **Decisions.** No `OfferPricing` quote for App Store (Apple's price, PRD); no WhatsApp (renewals monthly would spam; push + inbox like Bref); PRODUCT_CHANGE is informational (the next RENEWAL carries the new product; the sync fixes any gap); the sync only grants (revocations come from webhooks only, so a partial API answer can never cut a plan); no sync throttle (owner only, RevenueCat rate-limits; a repeat is idempotent).

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- expected: success.
- `wt-pkgs.sh keevo/backend 21-2 payments` then `subscription admin architecture` then `identity shared` -- expected: 0 failure, 0 error.
- `EndpointEntitlementCoverageTest -Dkeevo.entitlements.regenerate=true` -- expected: the 6 new handlers listed (exempt).

**Manual checks (if no CLI):**
- Local backend (dev profile, accept-sandbox on, webhook secret set, client disabled, WhatsApp noop, FCM off) on keevo_dev: V62 applied; a new owner (KV-M01191 style) — curl INITIAL_PURCHASE (SANDBOX) with the secret → PRO until the expiry; replay → no change; bad secret → 401; EXPIRATION → free plan; sync → 503 « momentanément indisponibles »; admin catalogue shows `storeProducts` with a gap. kv_11b5zm untouched; backend stopped with `fuser -k 4500/tcp`.

## Auto Run Result

- **Summary:** iPhone owners pay their plan through the App Store (StoreKit via RevenueCat, Bref's flow ported). V62: `external_subscriptions` (one Apple subscription bound to one company, status, Apple expiry, grace, the expiry it granted, last event), `revenuecat_webhook_event` (de-duplication + purchase details kept for later binding), `store_products` (App Store product → plan × cycle, offer data, Bref's 6 products seeded), `tenant_payments` method APP_STORE + unique `store_transaction_id`, journal entity STORE_PRODUCT. The RevenueCat webhook is authenticated by the `Authorization` secret (constant time; no secret = everything refused) and processed in one transaction per event (claim by id, subscription locked and ordered by timestamp, dispatch by type): sandbox filtered outside dev/test except Apple's review account, Apple grace honoured, refund = revocation (and REFUND_REVERSED re-grants), TRANSFER re-read through the RevenueCat port. The plan follows through the 21.1 activation path (`PlanAdministrationPort.changePlan`, SELF_SERVICE_PAYMENT; EXPIRY back to the free plan only when the plan is still this subscription's grant). Owner: products, subscription view, sync after purchase; super-admin: App Store products with Apple price (declared or observed), grid price and gap in `GET /api/v1/admin/offers`. Routed 21.10 item done.
- **Commit:** `21973fdf` (code), spec/status commit after it.
- **Migrations:** `V62__app_store_subscriptions.sql` (public only).
- **Endpoints:** `GET /api/v1/payments/app-store/products`, `GET /api/v1/payments/app-store/subscription`, `POST /api/v1/payments/app-store/sync` (owner, exempt « core: abonnement »), `POST /api/v1/payments/webhook/revenuecat` (public, secret header), `GET /api/v1/admin/offers/store-products`, `PUT /api/v1/admin/offers/store-products/{productId}`; additive: `storeProducts` on `GET /admin/offers`, method APP_STORE / « Paiement en ligne (App Store) » on admin payments; `docs/entitlements/endpoints.md` regenerated (323 handlers).
- **Files:** V62; `payments/appstore/**` (model, ports, JDBC store, RevenueCat REST / disabled / in-memory clients + config, subscription and webhook services, push listener, controllers); `subscription/api/StoreProducts`, `OfferAdministration` (store products), `subscription/offer/**` (store product row/port/JDBC store/service, catalogue view, cycle-in-use check, journal entity); `admin/offer` (2 endpoints), `admin/subscription` (APP_STORE, label, online flag); `identity/api` (`OwnedTenantRef`, `findOwnedTenants`, `findEmail`); config yml + `.env.example`; tests (`AppStoreFullContextTest` 17, `AppStoreEventTest`, `XafAmountTest`, `RevenueCatRestClientTest`, `AppStoreConfigTest`, `AppStoreWebhookServiceTest`, `AppStoreSubscriptionServiceTest`, `AppStorePushListenerTest`, `StoreProductServiceTest`, registry fixture, revenue slice imports).
- **Review:** 45 findings (5 layers incl. FUS-NFR11) — high 0, medium 13, low 26, false 6, maybe-false 0. Patched: pending purchases replayed into the ledger at binding (UNRESOLVED / UNKNOWN_PRODUCT), REFUND_REVERSED, sync follows RevenueCat renewal/billing state, transfer fixes (rebind of a TRANSFERRED row, no double holder, stale ordering), no double « actif » notice after sync, observed price written only on change, throttled RevenueCat product reloads + truncation warning, overflow guards, archived cycles, cycle-in-use, journal keeps the previous mapping, TRANSFERRED notice, tests (ledger currencies / trial, notices, transfer still active, refund reversed, product change, unresolved-then-sync, bundle modules, catalogue gap). 1 deferred → routed to 24-5-import-monetisation. Rejections with reasons in the triage log.
- **Follow-up review recommended: true** — patched medium entries: 10 (high 0). Unverified risk: the pending-purchase replay (event columns + `replayPending` at every binding) and the TRANSFER/sync re-binding rules were added after the review and not re-reviewed.
- **Verification:** clean compile OK; test-compile OK after patches. Tests by package after the last fix: payments 69, subscription 146, admin 688, architecture 135, identity 866 — all green; `shared` full run stopped by the system (low memory, not a failure) → the touched sub-package `shared/infrastructure/event` re-run: 41 green (shared before the patches: no shared code changed except the registry fixture). Endpoints regenerated. Matrix audit: every row covered by `AppStoreFullContextTest` or a unit test (no secret configured, disabled sync → `AppStoreWebhookServiceTest` / `AppStoreSubscriptionServiceTest`). Live keevo_dev (dev profile, WhatsApp noop, FCM off, RevenueCat simulated): V62 applied; new owner KV-42J23U — bad / missing secret 401, TEST recorded, sandbox INITIAL_PURCHASE (allowed in dev) → PRO until the Apple expiry, replay no change, view « Votre plan Pro est actif et se renouvelle le 09/12/2026 », unknown user ignored, CANCELLATION → « Le renouvellement automatique est désactivé … », EXPIRATION → FREE, sync → 404 « Aucun abonnement App Store actif … », 6 products listed; 1 APP_STORE ledger row, events REGISTRATION, SELF_SERVICE_PAYMENT, EXPIRY. kv_11b5zm untouched; backend stopped with `fuser -k 4500/tcp`. No RevenueCat / Apple call.
- **Residual risks:** RevenueCat payload fields and v2 API shapes checked against Bref's code and RevenueCat's documented names only (mocked); production stays refusing (401) until `REVENUECAT_WEBHOOK_SECRET` is set and the webhook URL registered; sync and TRANSFER need `REVENUECAT_SECRET_KEY` / `REVENUECAT_PROJECT_ID`; App Store revenue is gross and non-XAF/EUR purchases rely on the declared Apple price; FUS-MIG2 (a migrated Bref owner's app_user_id) still to be checked in the Apple sandbox before the Apple wave.

## Correctif post-test appareil (10/11/2026)

- **Constat (test appareil de l'epic 21).** Pro payé en Mobile Money (période CONFIRMED jusqu'au 09/12), puis achat App Store (le plan prend l'échéance Apple), puis EXPIRATION / remboursement / transfert App Store → l'entreprise retombait sur le plan gratuit alors que la période Mobile Money courait encore : les jours payés étaient perdus.
- **Règle corrigée.** À la révocation, la garde reste inchangée (rien si un autre plan ou un paiement plus long a pris la main). Sinon, si le dernier paiement Mobile Money de plan CONFIRMED (`payment_requests`, kind PLAN) couvre encore aujourd'hui (dernier jour ≥ aujourd'hui, date WAT), l'entreprise revient à ce plan jusqu'à son dernier jour 23:59:59 WAT — la conversion exacte de 21.1 (`PlanPeriod.expiryOf`, désormais partagée) ; sinon plan gratuit (cause EXPIRY, inchangé). Déjà exactement sur cette période → aucune écriture.
- **Quelle période.** Celle du paiement confirmé le plus récent, pas celle qui finit le plus tard : un paiement d'un autre plan démarre le jour même et clôt le plan précédent (21.1, ADR-06), une période plus ancienne d'un autre plan n'est donc plus celle de l'entreprise ; les renouvellements d'un même plan sont chaînés, le plus récent porte donc le dernier jour. Limite acceptée : un passage manuel du super-admin entre le paiement Mobile Money et l'achat App Store n'est pas pris en compte (cas non rencontré).
- **Cause du changement de plan.** SELF_SERVICE_PAYMENT (effet du paiement en ligne d'origine ; ni churn ni « rétrogradation à échéance » ; les revenus comptent les lignes du registre, pas cet événement), motif « Fin de l'abonnement App Store : retour à la période payée par Mobile Money ». Pas de nouvelle cause (elle exigerait une migration de la contrainte `tenant_plan_events_cause_check`).
- **Cas symétrique vérifié.** Un achat App Store du même plan plus court qu'une période Mobile Money en cours ne change pas le plan (« couvert ») et son expiration ne touche à rien ; un achat d'un autre plan prend la main jusqu'à l'échéance Apple, puis l'expiration rend la période Mobile Money.
- **Conception / coût.** Port `payments.checkout.domain.port.out.PaidPlanPeriods` (lecture, modèle `PaidPlanPeriod`) implémenté par `JdbcPaymentRequestStore` ; une seule lecture indexée par révocation (`ix_payment_requests_tenant_created` (tenant_id, created_at DESC), arrêt à la première ligne CONFIRMED de plan) ; aucune boucle, aucune migration. Pas d'index (tenant_id, status, period_end) : inutile, l'historique d'une entreprise est court et lu du plus récent.
- **Tests.** `AppStoreSubscriptionServiceTest$MobileMoneyPeriod` (10 cas : période rendue, fin aujourd'hui, remboursement, période finie → gratuit, aucun paiement → gratuit, autre plan → intact, paiement plus long → intact, déjà sur la période, achat plus court couvert, achat d'un autre plan) ; `PlanPeriodTest` (conversion partagée) ; `AppStoreFullContextTest` Postgres (période Mobile Money rendue — dont un ancien paiement d'un autre plan finissant plus tard ignoré, même plan avec échéance Apple plus longue, période finie → gratuit, aucun paiement → gratuit, autre plan ayant pris la main → intact).
- **Mobile / dashboard.** Aucun changement d'API.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée, commit `21973fdf` (branche `refonte/modulaire`, poussée aussi sur `develop`). Clé de suivi : `m21-2-storekit-revenuecat`. Conventions : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API**
- Sur iPhone, le propriétaire achète son plan par l'App Store (StoreKit via RevenueCat, comme l'app Bref). RevenueCat prévient Keevo (webhook) ; Keevo active le plan jusqu'à la date d'expiration Apple, le prolonge à chaque renouvellement, le garde pendant la période de grâce Apple, et revient au plan gratuit à l'expiration ou au remboursement.
- L'`app_user_id` RevenueCat doit être l'id de l'utilisateur Keevo (les owners Bref migrés gardent leur id : rien à changer pour eux).
- Les produits App Store (identifiants StoreKit) et le plan × durée que chacun donne sont des données du catalogue (plus de liste en dur dans l'app).
- Le propriétaire reçoit une notification (push + boîte de réception, lien `/plan`) : `APP_STORE_ACTIVE` « Abonnement App Store actif », `APP_STORE_CANCELLED` « Renouvellement automatique désactivé », `APP_STORE_BILLING_ISSUE` « Problème de paiement App Store », `APP_STORE_EXPIRED` (« … terminé », « … remboursé », « … transféré »).
- Rien ne change pour les apps V1 2.0.1.

**À utiliser** (propriétaire uniquement ; autres rôles : 403 « Seul le propriétaire peut payer l'abonnement » ; code : `keevo/backend/src/main/java/com/keevo/payments/appstore/adapter/in/rest/AppStoreController.java`, vue `payments/appstore/application/service/AppStoreSubscriptionView.java`)
- `GET /api/v1/payments/app-store/products` → `data: [{productId, planCode, planLabel, cycleCode, cycleLabel, months}]` : les identifiants à demander à StoreKit / RevenueCat (offerings) ; afficher le prix Apple renvoyé par StoreKit, pas la grille Keevo.
- `POST /api/v1/payments/app-store/sync` (sans corps) : à appeler juste après un achat ou une restauration réussis ; Keevo lit RevenueCat et applique le plan tout de suite (le webhook arrive aussi, sans double effet). Réponse : la vue ci-dessous.
- `GET /api/v1/payments/app-store/subscription` → la vue, `data` absent si l'entreprise n'a pas d'abonnement App Store.
- Vue : `{status, statusLabel, productId, planCode, planLabel, cycleCode, cycleLabel, expiresAt, graceExpiresAt, autoRenew, sandbox, message}` ; `status` = `ACTIVE | CANCELLED | BILLING_ISSUE | EXPIRED | REVOKED | TRANSFERRED` ; afficher `statusLabel` et `message` (« Votre plan Pro est actif et se renouvelle le 09/12/2026 », « Le renouvellement automatique est désactivé : votre plan Pro reste actif jusqu'au … », « Apple n'a pas pu renouveler votre abonnement : mettez à jour votre moyen de paiement avant le … », …).
- Erreurs de `sync` (afficher `error`) : 503 `PAYMENT_UNAVAILABLE` « Les achats App Store sont momentanément indisponibles » ; 502 `PAYMENT_PROVIDER_ERROR` « La vérification de l'achat App Store a échoué. Réessayez. » ; 404 `PAYMENT_NOT_FOUND` « Aucun abonnement App Store actif n'a été trouvé pour ce compte » ; 409 `INVALID_STATE` « Cet abonnement App Store ne correspond à aucune offre. Contactez le support. » / « Cet abonnement App Store est déjà rattaché à une autre entreprise ».

**À implémenter côté mobile**
1. Configurer RevenueCat avec `Purchases.logIn(<id utilisateur Keevo>)` (jamais un id anonyme) ; avant l'achat, `Purchases.setAttributes({ keevo_tenant: <code de l'entreprise, KV-…> })` — indispensable pour un propriétaire de plusieurs entreprises.
2. Dans « Mon plan » sur iOS : lister les offres à partir de `GET /payments/app-store/products` (prix affiché = prix StoreKit) ; acheter avec RevenueCat ; après succès (ou « Restaurer mes achats »), appeler `POST /payments/app-store/sync`, puis rafraîchir `/subscription/me`, `/tenants/me/modules` et `/payments/app-store/subscription`.
3. Afficher l'état de l'abonnement App Store (`statusLabel`, `message`) ; pour `BILLING_ISSUE`, un bouton « Mettre à jour le paiement » qui ouvre la gestion des abonnements Apple ; pour gérer / résilier, ouvrir la page d'abonnements Apple (Keevo ne résilie pas).
4. Android reste sur Mobile Money (21.1) ; ne pas proposer l'App Store sur Android.
5. Hors ligne : pas d'achat (« Connexion requise pour payer ») ; la vue peut être gardée en cache pour l'affichage.
6. Aucune donnée technique à l'écran (ni `productId`, ni codes) : seulement les libellés et `message`.

**Implémentation de référence :** aucune dans `keevo/app` (client de test Flutter non modifié). Flux d'origine : `bref/features/user` (achat RevenueCat) côté app Bref.

**Critères d'acceptation mobile / scénarios de test**
- Given un propriétaire sur iPhone (sandbox Apple, serveur de recette), when il achète Pro Mensuel puis l'app appelle `sync`, then « Mon plan » affiche Pro jusqu'à la date Apple et le message « … se renouvelle le … ».
- Given cet abonnement, when il désactive le renouvellement dans les réglages Apple, then après le webhook l'écran affiche « Le renouvellement automatique est désactivé … ».
- Given un échec de paiement Apple, when RevenueCat envoie BILLING_ISSUE, then l'écran affiche le message de mise à jour du paiement et le plan reste actif jusqu'à la fin de la grâce.
- Setup curl (serveur local, profil dev, secret `dev-revenuecat-secret`) : `curl -X POST $API/payments/webhook/revenuecat -H 'Authorization: dev-revenuecat-secret' -H 'Content-Type: application/json' -d '{"event":{"id":"e1","type":"INITIAL_PURCHASE","app_user_id":"<user id>","original_transaction_id":"1000","transaction_id":"1001","product_id":"brefpoint.pro.month","store":"APP_STORE","environment":"SANDBOX","event_timestamp_ms":<now ms>,"expiration_at_ms":<now+30 j ms>,"price_in_purchased_currency":2500,"currency":"XAF"}}'`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée, commit `21973fdf`. Clé de suivi : `d21-2-storekit-revenuecat`.

**Ce qui est fait côté API**
- Le catalogue d'offres contient les produits App Store : le plan et la durée que chaque produit donne, le prix Apple (saisi par le super-admin depuis App Store Connect, ou constaté sur le dernier achat), le prix de la grille et l'écart.
- Les achats App Store apparaissent dans les paiements de l'entreprise (« Paiement en ligne (App Store) ») et dans les revenus par plan (montant brut, commission Apple non déduite ; achat en devise autre que XAF/EUR compté au prix Apple saisi).

**À utiliser** (code : `admin/offer/adapter/in/rest/AdminOfferController.java`, vue `subscription/api/OfferAdministration.StoreProductView`)
- `GET /api/v1/admin/offers` : champ ajouté `storeProducts` (même forme que ci-dessous).
- `GET /api/v1/admin/offers/store-products` → `[{store, storeLabel, productId, planCode, planLabel, cycleCode, cycleLabel, active, declaredPriceXaf, observedPrice, observedCurrency, observedPriceXaf, observedAt, applePriceXaf, applePriceSource, applePriceSourceLabel, gridPriceXaf, gapXaf, gapLabel, updatedAt}]`.
- `PUT /api/v1/admin/offers/store-products/{productId}` corps `{planCode, cycleCode, declaredPriceXaf?, active?}` : crée ou modifie un produit (journalisé). Erreurs 422 `details.<champ>` : « Identifiant de produit App Store invalide » (`productId`), « Choisissez un plan payant actif » (`planCode`), « Durée introuvable » / « Cette durée est archivée : choisissez une durée active » (`cycleCode`), « Le prix App Store doit être compris entre 1 et 100 000 000 FCFA » (`declaredPriceXaf`).
- `GET /api/v1/admin/tenants/{id}/payments` : `method = APP_STORE`, `recordedByLabel` « Paiement en ligne (App Store) », `online = true`.

**À implémenter côté dashboard**
1. Dans l'écran « Offre », une section « Produits App Store » : produit (identifiant App Store Connect, saisi par le super-admin), plan, durée, prix Apple (saisi ou constaté, avec `applePriceSourceLabel`), prix de la grille, écart (`gapLabel`, en couleur si différent), proposé ou non ; formulaire d'ajout / modification.
2. Prévenir dans le formulaire qu'un changement de plan ou de durée d'un produit s'applique aux renouvellements des abonnés existants.
3. Dans les paiements d'une entreprise, badge « App Store » quand `method = APP_STORE`.

**Critères d'acceptation dashboard**
- Given Pro Mensuel à 3 500 FCFA dans la grille, when le super-admin saisit 2 500 FCFA pour `brefpoint.pro.month`, then la ligne affiche « 1 000 FCFA de moins que la grille ».
- Given un achat App Store confirmé, when on ouvre les paiements de l'entreprise, then la ligne affiche « Paiement en ligne (App Store) ».
- Setup curl : connexion super-admin puis `curl -X PUT -H "Authorization: Bearer $T" -H 'Content-Type: application/json' $API/admin/offers/store-products/brefpoint.pro.month -d '{"planCode":"PRO","cycleCode":"MONTHLY","declaredPriceXaf":2500}'`.
