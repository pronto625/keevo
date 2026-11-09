---
title: '21-1 — Payments module: Mobile Money self-service checkout (MyCoolPay), frozen quote, signed idempotent webhook'
type: 'feature'
created: '2026-11-09'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: '31bd99056e43960aea21a7dfaee8c85ea921acee'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-10-catalogue-offres-configurable.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** An owner can only get a paid plan through the super-admin (off-platform payment recorded by hand). Bref's owners pay by Mobile Money (MyCoolPay: USSD / OTP) and their plan activates by itself; Keevo has the offer catalogue and the quote port (21.10) but no checkout, no provider integration and no webhook. Three 21.10 items wait here: frozen price at initiation (0 FCFA quote = free offer), promo-code use counter (+ cap), plan/cycle written on the confirmed payment.

**Approach:** A new Modulith module `com.keevo.payments` (backend code only — public platform tables, no tenant DDL, no module manifest) owns one public migration (V61: `payment_requests`, promo-code counters, `tenant_payments.payment_request_id`, cause `SELF_SERVICE_PAYMENT`). The owner starts a checkout priced through `OfferPricing.quote` (stored with the request); the MyCoolPay flow of Bref (`src/payment-gateway/*`) is ported behind a `MobileMoneyGateway` port (real MyCoolPay adapter, sandbox adapter for tests/local, disabled adapter by default); the signed webhook (and a status poll safety net) re-verifies with the provider, then one transaction writes `tenant_payments`, applies the plan through `PlanAdministrationPort.changePlan` (subscription row, public plan, `tenant_plan_events`, paid-module rows) and counts the discount use; the owner is notified (WhatsApp + push/inbox).

## Boundaries & Constraints

**Always:**
- One in-flight request per tenant: partial unique index on `payment_requests(tenant_id) WHERE status IN ('INITIATING','PENDING')`; a stale `INITIATING` (> 2 min) or `PENDING` (> `keevo.payments.pending-timeout`, default 10 min) is expired by the next checkout of that tenant (one UPDATE) — no HTTP call inside a DB transaction.
- The price charged is the quote stored at initiation (base, reduction, due, discount, label, promo code, priced_at); confirmation never re-prices. A 0 FCFA quote is confirmed at once without the provider and without a `tenant_payments` row (amount must be > 0 there).
- Webhook: secret path segment compared in constant time, MyCoolPay md5 signature verified (constant time), then `status(ref)` re-verified with the provider (SUCCESS, amount ≥ due, currency XAF) before any activation. Idempotent: request row locked `FOR UPDATE`, `CONFIRMED` → no-op; `tenant_payments.payment_request_id` unique as a second guard. Unknown transaction ref (owner still on api-bref, 24.10) → HTTP 200 « OK », ignored, no error.
- A payment the provider confirms is honoured whatever the local status (EXPIRED / CANCELLED / FAILED → CONFIRMED): money received is never lost.
- Renewal like the super-admin activation (ADR-06): a running PAID plan is extended from the day after its end; else the period starts today (WAT); end = start + cycle months − 1 day, expiry 23:59:59 WAT.
- Secrets only from env (`MYCOOLPAY_PUBLIC_KEY`, `MYCOOLPAY_PRIVATE_KEY`, `MYCOOLPAY_CALLBACK_HASH`, `MYCOOLPAY_BASE_URL`), never logged; phone numbers masked in logs; no payload dump in logs.
- Gateway selection `keevo.payments.gateway = disabled | sandbox | mycoolpay` (default `disabled`): `sandbox` refused at startup under `prod`/`staging`; `mycoolpay` refused at startup when a key is blank. Tests and the local live check never reach MyCoolPay.
- French user messages, no UUID/code in a message; OWNER only on the tenant endpoints (super-admin 403); additive only on existing responses (V1 2.0.1 apps untouched).
- Set-based SQL, indexes for every new lookup, constant query count per request (FUS-NFR11).

**Never:**
- No StoreKit/RevenueCat, no account credit, no SMS packs (21.2, 21.3); no card payment; no refund/revocation flow; no `keevo/app` change; no dashboard/mobile feature work (hand-over sections only).
- No real money call in tests or live check; no edit of a committed migration.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Checkout USSD | owner, PRO × MONTHLY 3 500, MTN_MOMO, phone `677 00 01 11` | request PENDING, quote stored, phone `+237677000111` sent, `nextStep=USSD` + `ussdCode` | — |
| Checkout OTP | provider answers REQUIRE_OTP | PENDING, `nextStep=OTP` | — |
| OTP authorize | `POST /payments/{id}/authorize {code}` on OTP request | provider authorize with the stored ref; PENDING `nextStep=USSD` | wrong code → status FAILED « Code de validation incorrect » (200); not an OTP request → 409 `INVALID_STATE` |
| Second checkout | a PENDING < 10 min exists | refused | 409 `PAYMENT_IN_PROGRESS` « Un paiement est déjà en cours. Terminez-le ou annulez-le. » |
| Stale lock | INITIATING > 2 min or PENDING > 10 min | expired, new checkout accepted | — |
| Concurrent checkouts | 2 parallel calls | one PENDING, one 409 | unique index |
| Provider down at initiation | gateway throws | request FAILED, lock released | 502 `PAYMENT_PROVIDER_ERROR` « Le paiement n'a pas pu démarrer. Vérifiez le numéro et réessayez. » |
| Gateway disabled | `keevo.payments.gateway=disabled`, amount > 0 | nothing stored | 503 `PAYMENT_UNAVAILABLE` « Le paiement Mobile Money est momentanément indisponible » |
| Free offer | quote 0 (100 % code) | request CONFIRMED at once, provider NONE, plan applied, no `tenant_payments`, discount counted; method/phone not required | — |
| Invalid offer / promo | archived/hidden plan, unknown cycle, bad code | nothing stored | 422 `VALIDATION_ERROR` (21.10 messages) |
| Webhook SUCCESS | valid hash + signature, provider status SUCCESS, amount ≥ due | one `tenant_payments` (MOBILE_MONEY, plan, cycle, request id), plan + expiry applied, event `SELF_SERVICE_PAYMENT`, promo counter +1, WhatsApp + push, « OK » | — |
| Webhook replay | same SUCCESS again (or webhook + poll race) | no second payment, no second extension, « OK » | — |
| Bad signature / hash | tampered body or wrong path secret | nothing changes | 200 « KO », WARN without payload |
| Provider not confirmed | signed SUCCESS but `status` PENDING or amount < due | nothing changes | 200 « KO » (provider retries) |
| Unknown ref | ref not in Keevo (owner on api-bref) | ignored | 200 « OK » |
| Webhook FAILED/CANCELED | signed | PENDING request → FAILED « Le paiement n'a pas abouti », lock released | — |
| Status poll | `GET /payments/{id}` PENDING | provider status: SUCCESS → confirmed as above; FAILED → FAILED; else unchanged | another tenant's id → 404 `PAYMENT_NOT_FOUND` |
| Cancel | `POST /payments/{id}/cancel` PENDING/INITIATING | CANCELLED, lock released (a later provider SUCCESS still confirms) | other status → 409 `INVALID_STATE` |
| Renewal | PRO running until 30/11, pays PRO × QUARTERLY | period 01/12 → 28/02, expiry 28/02 23:59:59 WAT | — |
| Promo cap | PROMO_CODE with `maxRedemptions=1`, used once | code no longer offered | quote 422 « Code promo invalide ou expiré »; offers `valid=false` |

</intent-contract>

## Code Map

- `db/migration/V61__payments_mobile_money.sql` -- NEW (last public V60). `payment_requests`; `discounts.redemption_count` / `max_redemptions`; `tenant_payments.payment_request_id` (unique, FK SET NULL); cause check + `SELF_SERVICE_PAYMENT` (NOT VALID then VALIDATE, as V43).
- Bref source (read-only): `/home/toor/Project/FreeLance/AI/api-bref/src/payment-gateway/{providers/mycoolpay/mycoolpay.adapter.ts, payment-gateway.controller.ts, payment-gateway.service.ts, payment-gateway-webhook.controller.ts}` -- payin body (`transaction_amount, transaction_currency, transaction_reason, app_transaction_ref, customer_phone_number, customer_lang`), `/api/{pub}/payin`, `/payin/authorize {transaction_ref, code}` (401 = wrong code), `/checkStatus/{ref}`, signature md5(ref+type+amount+currency+operator+privateKey), INITIATING claim, re-verify before activation.
- `subscription/api/OfferPricing.java` + `subscription/offer/application/service/OfferPricingService.java` -- `Quote` gains `@JsonIgnore UUID discountId`; new `recordRedemption(UUID discountId)` (MANDATORY tx, one UPDATE).
- `subscription/offer/domain/model/Discount.java`, `adapter/out/persistence/JdbcOfferStore.java` (DISCOUNT_COLUMNS, CANDIDATES_SQL cap filter, INSERT/UPDATE bind `max_redemptions`), `domain/port/out/OfferStore.java`, `application/service/OfferCatalogService.java` (validDiscount: `maxRedemptions` PROMO_CODE only, 1..1 000 000; views), `subscription/api/OfferAdministration.java` (`DiscountInput.maxRedemptions`, `DiscountView.maxRedemptions/redemptionCount`), `admin/offer/adapter/in/rest/dto/OfferRequests.java` + `AdminOfferController` (map the field).
- `shared/capability/entitlement/PlanChangeCause.java` -- + `SELF_SERVICE_PAYMENT` (counts as a conversion / activation like ADMIN_ACTIVATION in `RevenueMetrics`: no special case needed).
- `subscription/api/PlanAdministrationPort.java` (`currentPlan` FOR UPDATE, `changePlan` → subscription row, public plan, plan event, `PlanModuleRowSync` = the module-lifecycle path of the AC) -- reused, MANDATORY tx.
- `shared/event/catalog/plan/PlanActivatedEvent.java` + `subscription/plan/application/service/PlanWhatsAppNotificationListener.java` -- reused for the owner WhatsApp; `shared/capability/notification/PushNotifier.notifyOwners` for push + inbox (pattern `messaging/.../InvoiceOverdueNotificationListener.java`).
- `admin/subscription/domain/model/TenantPayment.java`, `adapter/out/persistence/JdbcTenantPaymentRepository.java`, `adapter/in/rest/dto/TenantPaymentDto.java` -- read `payment_request_id IS NOT NULL` → additive `online` flag, `recordedByLabel` « Paiement en ligne (Mobile Money) ».
- `shared/domain/PhoneNumbers.toE164(raw, "+237")`; `identity/api/TenantQueryPort.findBySchemaName`; `shared/infrastructure/security/CurrentActor`; `shared/infrastructure/persistence/TenantContext`/`TenantSchema`.
- `shared/infrastructure/security/SecurityConfig.PUBLIC_PATHS` -- + `/api/v1/payments/webhook/**`.
- `shared/domain/exception/ErrorCode.java` + `shared/infrastructure/web/GlobalExceptionHandler.java` -- + `PAYMENT_IN_PROGRESS` (409), `PAYMENT_NOT_FOUND` (404), `PAYMENT_UNAVAILABLE` (503), `PAYMENT_PROVIDER_ERROR` (502).
- `test/.../architecture/ApplicationModulesBaselineTest.EXPECTED_MODULES` -- + `payments`.
- `application.yml` / `application-dev.yml` / `test/resources/application-test.yml` -- `keevo.payments.*` (disabled / sandbox / sandbox).
- Tests to mirror: `admin/offer/OfferCatalogueFullContextTest` (full context + MockMvc + TestAccess tokens + cleanup), `OfferCataloguePostgresIntegrationTest`, `AdminPlanServiceTest`.

## Tasks & Acceptance

**Execution:**
- `V61__payments_mobile_money.sql` -- `payment_requests(id, tenant_id FK tenants ON DELETE CASCADE, tenant_schema, requested_by, provider ∈ {MYCOOLPAY, SANDBOX, NONE}, method ∈ {ORANGE_MONEY, MTN_MOMO} NULL, phone, plan_code FK plans, cycle_code FK billing_cycles, months, base_xaf, reduction_xaf, amount_due_xaf ≥ 0, currency 'XAF', discount_id FK discounts SET NULL, discount_label, promo_code, priced_at, status ∈ {INITIATING, PENDING, CONFIRMED, FAILED, CANCELLED, EXPIRED}, next_step ∈ {OTP, USSD, NONE}, ussd_code, app_transaction_ref UNIQUE, provider_transaction_ref, provider_status, failure_reason, tenant_payment_id, period_start, period_end, expires_at, confirmed_at, created_at, updated_at)`; indexes: unique in-flight per tenant (partial), unique (provider, provider_transaction_ref) partial, (tenant_id, created_at DESC); discounts counters (`CHECK max_redemptions IS NULL OR (scope='PROMO_CODE' AND max_redemptions > 0)`); tenant_payments.payment_request_id + unique partial index; cause constraint.
- `payments/package-info.java` -- `@ApplicationModule(allowedDependencies = {"shared", "identity :: api", "subscription :: api"})`.
- `payments/checkout/domain/model/*` -- `PaymentRequest`, `PaymentStatus` (French labels), `NextStep`, `MobileMoneyMethod` (labels), `PlanPeriod` (pure renewal rule).
- `payments/checkout/domain/port/out/MobileMoneyGateway.java` -- `initiate(Payin)`, `authorize(ref, code)`, `status(ref)`, `verifyCallback(JsonNode-free Map)`, `provider()`; result records with provider statuses mapped to `PENDING|REQUIRE_OTP|SUCCESS|FAILED|CANCELED`.
- `payments/checkout/domain/port/out/PaymentRequestStore.java` + `adapter/out/persistence/JdbcPaymentRequestStore.java` -- expire stale (1 UPDATE), insert (unique → in-progress), mark pending/failed/cancelled (guarded UPDATEs), find by id+tenant, find in-flight, lock by id / by provider ref (`FOR UPDATE`), confirm (UPDATE), insert `tenant_payments` row.
- `payments/checkout/adapter/out/gateway/{MyCoolPaySignature, MyCoolPayGateway, SandboxMobileMoneyGateway, DisabledMobileMoneyGateway, PaymentGatewayConfig}.java` -- port of the Bref adapter (RestClient, 10 s connect / 30 s read), sandbox: in-memory refs, `REQUIRE_OTP` for ORANGE_MONEY, USSD `*126#` for MTN_MOMO, `status` = outcome of the last signed callback (default PENDING), OTP `000000` refused; config validation at startup.
- `payments/checkout/application/service/CheckoutService.java` -- start (expire stale, quote, free path, INITIATING insert, gateway call outside tx, PENDING/FAILED), authorize, refresh (status poll), cancel, current.
- `payments/checkout/application/service/PaymentConfirmationService.java` -- `@Transactional confirm(requestId, providerAmount)`: lock, idempotent, lock plan, period, tenant_payments, changePlan(SELF_SERVICE_PAYMENT, actor = requester), recordRedemption, request CONFIRMED, publish `PlanActivatedEvent` + `PlanPaymentConfirmedEvent`; `markFailed`.
- `payments/checkout/application/service/PaymentWebhookService.java` -- verify → route → re-verify → confirm; returns OK / KO / RETRY.
- `payments/checkout/application/listener/PaymentPushListener.java` -- after commit, `notifyOwners` type `PLAN_PAYMENT_CONFIRMED`, « Paiement reçu » / « Votre plan Pro est actif jusqu'au 28/02/2027. », deep link `/plan`, `@DeliverOnce`, best effort.
- `payments/checkout/adapter/in/rest/PaymentsController.java` -- `POST /api/v1/payments/checkout`, `POST /api/v1/payments/{id}/authorize`, `GET /api/v1/payments/{id}`, `POST /api/v1/payments/{id}/cancel`, `GET /api/v1/payments/current`; `@EntitlementExempt("core: abonnement")`; OWNER only.
- `payments/checkout/adapter/in/rest/PaymentWebhookController.java` -- `POST /api/v1/payments/webhook/mycoolpay/{hash}` (text/plain « OK » / « KO », 503 on unexpected error); `@EntitlementExempt`.
- subscription.offer / admin.offer / admin.subscription / ErrorCode / SecurityConfig / config files / baseline test -- as in the Code Map; regenerate `docs/entitlements/endpoints.md`.
- Tests -- `PlanPeriodTest`, `MyCoolPaySignatureTest`, `MyCoolPayGatewayTest` (MockRestServiceServer: payin USSD/OTP, authorize 401, checkStatus mapping, error → provider error, no secret in exception), `PaymentGatewayConfigTest` (sandbox refused in prod, blank key refused), `PaymentsFullContextTest` (every I/O row through MockMvc + signed webhook on the sandbox gateway, concurrency of two checkouts, replayed webhook and webhook/poll race → one payment), `OfferCatalogServiceTest`/`OfferCatalogueFullContextTest` additions (cap validation, exhausted code), `JdbcTenantPaymentRepository` online flag via the full-context test.

**Acceptance Criteria:**
- Given an owner on the sandbox gateway, when they check out PRO × MONTHLY then the signed webhook confirms it, then `GET /api/v1/subscription/me` shows PRO with the new expiry, `tenant_payments` holds one MOBILE_MONEY row with plan/cycle and the quoted amount, `tenant_plan_events` one `SELF_SERVICE_PAYMENT` row, the paid-module rows follow PRO, and the super-admin payment list shows it with « Paiement en ligne (Mobile Money) ».
- Given a price change or a promotion created after initiation, when the payment is confirmed, then the stored quote is applied, not the new price.
- Given the same webhook delivered twice (or a webhook racing a status poll), when both are processed, then exactly one payment row, one plan event and one extension exist.
- Given V61 applied twice, when re-run, then nothing changes; the V60 offers/admin endpoints keep their contracts (additive fields only).

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 55 findings — high 5, medium 13, low 25, false 9, maybe-false 3
- findings:
  - `[medium]` `[patch]` (nfr11) promo cap not atomic: increment unguarded, several companies can quote the last use — free offers (confirmed in the checkout transaction) now take the use with a guarded `UPDATE … WHERE redemption_count < max_redemptions`, 0 row → 422 « Code promo invalide ou expiré », nothing stored; a PAID payment confirmed by the provider is always counted (soft cap: money received is honoured), documented on the port.
  - `[low]` `[patch]` (nfr11) stale in-flight request only expired by a new checkout — `GET /payments/current` expires the stale requests of the company first (same indexed UPDATE).
  - `[low]` `[patch]` (nfr11) webhook reads twice (id by ref then row) — one read of the row by provider ref (same unique index).
  - `[low]` `[patch]` (nfr11) V61 comment « weaker lock » wrong inside one Flyway transaction — comment corrected.
  - `[low]` `[reject]` (nfr11) new FK on tenant_payments validated with a scan — every existing row has a NULL column, small platform table; no practical lock.
  - `[high]` `[patch]` (blind) provider timeout at initiation marks the request FAILED without provider ref; a payment then made is ignored by the webhook (unknown ref) — the webhook falls back on `app_transaction_ref` (ours, unique index) and attaches the provider ref (guarded `WHERE provider_transaction_ref IS NULL`), then settles server-to-server; FAILED requests are honoured when the provider confirms.
  - `[high]` `[patch]` (blind) = same root cause: callback before `recordProviderAnswer` commits / crash between initiate and record — same fallback.
  - `[medium]` `[patch]` (blind) nothing re-checks requests that left PENDING (lost webhook) — the status poll asks the provider for any non-confirmed request holding a provider ref created less than 24 h ago.
  - `[medium]` `[patch]` (blind) = nfr11 promo cap (same root cause).
  - `[low]` `[reject]` (blind) a cap lowered below the current count exhausts the code at once — that is what lowering a cap means; the view shows count and cap.
  - `[high]` `[patch]` (blind) buying a different paid plan during a running one: the new plan applies at once from the old plan's end, giving the upgraded plan for the unpaid remaining days — the extension now applies to the same plan only (a renewal); another plan starts today (the switch is immediate, remaining days of the old plan are not carried — told to the app in the mobile section). Matrix « Renewal » row (same plan) unchanged.
  - `[medium]` `[patch]` (blind) sandbox refused only under prod/staging (deny-list) — allow-list: sandbox only under the `dev` or `test` profile.
  - `[low]` `[patch]` (blind) env/deploy docs missing — `.env.example` documents the gateway and the four MyCoolPay variables; production values and the webhook URL to register at MyCoolPay are a user decision (report).
  - `[medium]` `[patch]` (blind) no throttle on payin prompts (checkout/cancel loop can spam a phone) — at most 5 checkouts per company per hour (one indexed count), 429 `RATE_LIMIT_EXCEEDED` « Trop de tentatives de paiement. Réessayez dans une heure. »
  - `[low]` `[reject]` (blind) status poll calls the provider each time — Bref parity, bounded by the PENDING window and the 24 h re-check window; a throttle column adds state for a negligible gain.
  - `[low]` `[patch]` (blind) = nfr11 stale `current()` (same root cause).
  - `[low]` `[patch]` (blind) disabled gateway answers « KO » (final) to real callbacks — answers RETRY (503) so the provider redelivers once re-enabled.
  - `[low]` `[reject]` (blind) free offers counted as conversions / WhatsApp after a 0 FCFA activation — a free plan by promo code is an activation of a paid plan (the WhatsApp text has no amount); revenue is unaffected (no ledger row).
  - `[low]` `[patch]` (blind) stale docs (PlanChange Javadoc, handler comment, baseline comment) — updated.
  - `[low]` `[reject]` (blind) online payments can be voided by the super-admin — the void is the existing correction tool (a refund happens outside); refunds/revocation are out of the story.
  - `[medium]` `[patch]` (blind) missing tests (sandbox, push listener, label mapping, terminal callback, mask, null cap) — grouped with verification-gap rows below.
  - `[low]` `[patch]` (blind) sandbox truncates the callback amount, MyCoolPay rounds — one shared conversion.
  - `[low]` `[patch]` (blind) `@RequestBody` required makes the null handling dead — `required = false`, French field errors.
  - `[medium]` `[patch]` (verification-gap) server-to-server re-check untestable through the sandbox (callback copied into status) — Mockito tests of `PaymentSettlementService` / `PaymentWebhookService`: provider PENDING → KO, provider unreachable → RETRY (503), other currency → NOT_VERIFIED, app-ref fallback.
  - `[medium]` `[patch]` (verification-gap) push after confirmation untested — `PaymentPushListenerTest` (type, title, body date in WAT) + inbox assertion in the full-context test.
  - `[low]` `[patch]` (verification-gap) PAYMENT_UNAVAILABLE → 503 not checked at HTTP level — status-mapping assertion added.
  - `[low]` `[patch]` (verification-gap) stale INITIATING expiry untested — test added (INITIATING 3 min → expired, PENDING 3 min → kept).
  - `[low]` `[patch]` (verification-gap other) = nfr11 promo cap.
  - `[low]` `[patch]` (verification-gap other) counter not checked on a paid payment — assertion added on a paid webhook-confirmed payment with a partial promo code.
  - `[high]` `[patch]` (edge) = blind provider timeout (same root cause).
  - `[medium]` `[patch]` (edge) = blind recordProviderAnswer failure (same root cause: app-ref fallback).
  - `[medium]` `[patch]` (edge) = blind early callback (same root cause).
  - `[medium]` `[patch]` (edge) = nfr11 promo cap.
  - `[medium]` `[patch]` (edge) repeated free checkout with an uncapped 100 % promotion / company discount extends the plan forever — a company takes a given free offer (discount) once: a CONFIRMED free request with the same discount → 422 « Vous avez déjà profité de cette offre ».
  - `[high]` `[patch]` (edge) = blind different-plan purchase (same root cause).
  - `[low]` `[reject]` (edge) a period starting on the 29–31 ends one day early in a shorter month — `plusMonths` clamps like every Keevo period (admin activation, trial); a one-day edge at month ends, no money at stake worth a calendar special case.
  - `[medium]` `[patch]` (edge) = blind lost webhook after PENDING (same root cause).
  - `[low]` `[patch]` (edge) = nfr11 stale current().
  - `[low]` `[patch]` (edge) late provider answer writes next_step on a terminal request — next_step written only while in flight.
  - `[false]` `[reject]` (edge) company without subscription row → webhook 503 forever — every company gets its subscription row at registration (TenantFactory) and it is never deleted; `currentPlan` empty cannot happen for a live tenant.
  - `[false]` `[reject]` (edge) suspended / deletion-pending company can pay — `JwtAuthFilter` answers 403 ACCOUNT_SUSPENDED / ACCOUNT_DELETION_PENDING before the controller.
  - `[low]` `[reject]` (edge) non-JSON callback → 400 — MyCoolPay posts JSON (Bref's controller binds a JSON body); a malformed body is redelivered by the provider.
  - `[false]` `[reject]` (edge) sandbox string amount → null — patched anyway by the shared conversion (blind row); for MyCoolPay the amount is numeric.
  - `[false]` `[reject]` (edge) amount over Integer.MAX_VALUE — catalogue prices are capped at 100 000 000 (21.10 validation, CHECK).
  - `[low]` `[reject]` (edge claim) renewal ignores an expiry granted by a voided payment (admin uses effectiveCurrentEnd) — a voided-payment correction is a super-admin action, who then also corrects the plan; online renewal from the stored expiry is the expected behaviour.
  - `[maybe-false]` `[reject]` (edge claim) « KO » with HTTP 200 may not make MyCoolPay retry — Bref's contract, kept for parity; settled only by MyCoolPay's documentation; the owner's poll and the 24 h re-check confirm anyway (would be low).
  - `[maybe-false]` `[reject]` (intent) the 24.10 router path is not exercised — the router does not exist yet (epic 24); the unknown-reference behaviour it relies on is tested here (would be low).
  - `[low]` `[patch]` (intent) « bundle activé » not asserted — assertion on the company's plan modules after confirmation added.
  - `[low]` `[patch]` (intent) = verification-gap push untested.
  - `[false]` `[reject]` (intent) module identity A2 (tenant module) — the caller's constraint is conditional (« if you create it »); decision recorded in Design Notes.
  - `[false]` `[reject]` (intent) ModuleLifecycleService not called directly — `changePlan` runs `PlanModuleRowSync`, the module-lifecycle path of plan changes since 16.5.
  - `[false]` `[reject]` (intent) base price 0 plan cannot be paid — catalogue prices are > 0 (21.10 CHECK); a 0 quote only comes from a discount.
  - `[maybe-false]` `[reject]` (intent) free confirmations write plan/cycle only on payment_requests — the routed item targets `tenant_payments` rows, which need an amount > 0; revenue per plan of a 0 FCFA activation is 0 (would be low).
  - `[false]` `[reject]` (intent) endpoint shape differs from the brief — aliases `plan`, `period` accepted.
  - `[false]` `[reject]` (intent) concurrency only C2 — C1 is a subset of C2.

## Design Notes

- **Module, not manifest (decision).** `payments` is a Spring Modulith module (`com.keevo.payments`) holding platform billing: a request must be found by the provider reference without knowing the tenant, so its table is public like `tenant_payments`. No `keevo/modules/payments/module.json`: a manifest would make it an entitled / owner-toggleable module, and the go-live rule would owner-disable it for every existing tenant — paying must stay possible for every owner, FREE or expired. No tenant DDL, so no `db/modules/payments`. 21.2/21.3 extend this module.
- **Ledger writes.** `public.tenant_payments` is the platform payment ledger (public migration V17, read by admin revenue); payments inserts its own rows there (one INSERT, `payment_request_id` unique) rather than calling into `admin` (admin → subscription → … would cycle).
- **Patterns.** Ports & adapters with a **strategy** gateway (MyCoolPay / sandbox / disabled chosen by configuration, fail-fast validation); **state machine** on `payment_requests.status` with guarded single-statement transitions (`UPDATE … WHERE status IN (…)`); **idempotent consumer** for the webhook (row lock + terminal state + unique ledger key); **quote object** (21.10 `OfferPricing.Quote`) frozen with the request; plan application reuses the 9-2 unit of work (`PlanAdministrationPort`), notifications reuse the after-commit listeners.
- **Why the re-verification.** As in Bref, the callback is only a signal: a replayed or forged-but-signed body never activates anything unless `checkStatus` (server-to-server, public key path) says SUCCESS with enough money.
- **Cost.** Checkout: 1 UPDATE (stale) + quote (21.10: 3 reads) + 1 INSERT + 1 HTTP + 1 UPDATE. Confirmation: 1 locked read + 1 locked plan read + ≤ 1 INSERT ledger + plan change (2 UPDATE + 1 INSERT + module sync, 9-2 path) + ≤ 1 counter UPDATE + 1 UPDATE request — constant. Webhook routing by unique index; status poll: 1 read + ≤ 1 HTTP. No loop over tenants or rows anywhere; the stale-lock sweep is per tenant at checkout (index `uq_payment_requests_in_flight`), no global job.
- **Free offer.** Confirmed in the checkout transaction (request INITIATING → CONFIRMED in one tx), no ledger row (CHECK amount > 0), so revenue is unchanged; the plan event and the counter are written.
- **Counter.** Every confirmed discount use increments `discounts.redemption_count` (any scope — statistics); the cap (`max_redemptions`) exists for promo codes only and is enforced at quote time (exhausted codes are not candidates). A free offer takes its use atomically in its own transaction (`… WHERE redemption_count < max_redemptions`, 0 row → 422, nothing stored) and a company takes a given free offer once; a PAID payment the provider collected is always counted (soft cap, bounded overrun — money already taken).
- **Review patches (2026-11-09).** Webhook routing falls back on our `app_transaction_ref` (unique index) when the provider reference was never stored (initiation timed out, callback before the answer was recorded, crash in between): the reference is attached and the server-to-server status decides. The status poll re-checks any non-confirmed request holding a provider reference less than 24 h old (lost webhook, payment after cancel/expiry). A renewal extends only the same paid plan; another plan starts today (an upgrade never runs on unpaid days). Sandbox allowed under `dev`/`test` only (allow-list). A disabled gateway answers callbacks 503 (redelivery). At most 5 checkouts per company per hour (one count on `ix_payment_requests_tenant_created`) — each checkout prompts a phone. `GET /payments/current` expires stale requests first.

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- expected: success.
- `wt-pkgs.sh keevo/backend 21-1 payments subscription admin architecture` then `shared identity` -- expected: 0 failure, 0 error.
- `EndpointEntitlementCoverageTest -Dkeevo.entitlements.regenerate=true` -- expected: endpoints.md lists the 6 new handlers as exempt.

**Manual checks (if no CLI):**
- Local backend (dev profile, gateway sandbox, WhatsApp noop, FCM off) on keevo_dev: owner KV-JMYV4N (or a new owner) checks out PRO × MONTHLY → PENDING USSD; a signed webhook (md5 with the sandbox private key) confirms it; `/subscription/me` shows PRO; a replay changes nothing; a bad signature → « KO »; an unknown ref → « OK ». kv_11b5zm untouched; backend stopped with `fuser -k 4500/tcp`.

## Auto Run Result

- **Summary:** owners pay their plan by Mobile Money without the super-admin. New Modulith module `com.keevo.payments` (public data only, no manifest, no tenant DDL — decision in Design Notes). V61: `payment_requests` (quote frozen at initiation, provider refs, state machine, partial unique index = one request in flight per company), promo-code counters (`redemption_count`, `max_redemptions`), `tenant_payments.payment_request_id` (unique), cause `SELF_SERVICE_PAYMENT`. Bref's MyCoolPay flow ported behind the `MobileMoneyGateway` port (MyCoolPay / sandbox dev-test only / disabled by default). Signed webhook (secret path + md5 signature, constant time) re-verified server-to-server, idempotent (row lock + terminal state + unique ledger key), routed by provider ref then by our `app_transaction_ref`, unknown ref (owner still on api-bref) → « OK » ignored. Confirmation in one transaction: ledger row (plan, cycle, frozen amount), plan applied through `PlanAdministrationPort.changePlan` (subscription, public plan, plan event, paid-module rows), discount use counted, owner told (WhatsApp + push « Paiement reçu »). 0 FCFA quote = free offer (no provider, once per company, capped code taken atomically). 21.10 routed items done: frozen price, promo counter + cap, plan/cycle on the confirmed payment.
- **Commit:** `1eafeb25cb52cf682d9d7fb6e4a67f9ebea82902` (code), spec/status commit after it.
- **Endpoints:** `POST /api/v1/payments/checkout`, `POST /api/v1/payments/{id}/authorize`, `GET /api/v1/payments/{id}`, `POST /api/v1/payments/{id}/cancel`, `GET /api/v1/payments/current` (owner, exempt « core: abonnement »), `POST /api/v1/payments/webhook/mycoolpay/{hash}` (public); additive: `maxRedemptions`/`redemptionCount` on admin discounts, `online` on admin payments; `docs/entitlements/endpoints.md` regenerated.
- **Files:** `db/migration/V61__payments_mobile_money.sql`; `payments/**` (domain model, ports, JDBC store, gateways + config, checkout / confirmation / settlement / webhook services, push listener, controllers); `subscription/api/OfferPricing` (+`discountId`, `recordRedemption`), `subscription/api/OfferAdministration`, `subscription/offer/**` (counters, cap, exhausted codes not offered); `admin/offer` (cap field), `admin/subscription` (online flag/label); `PlanChangeCause`, `ErrorCode` (+4), `ErrorMessages`, `GlobalExceptionHandler`, `SecurityConfig` (webhook public), `application*.yml`, `.env.example`; tests (`PaymentsFullContextTest` 16, `PaymentSettlementServiceTest`, `MyCoolPayGatewayTest`, `MyCoolPaySignatureTest`, `PaymentGatewayConfigTest`, `CheckoutServiceTest`, `PlanPeriodTest`, `PaymentPushListenerTest`, additions to `OfferCatalogServiceTest`, `GlobalExceptionHandlerTest`, `RegistryEventSerializationTest`, `ApplicationModulesBaselineTest`).
- **Review:** 55 findings (5 layers incl. FUS-NFR11) — high 5, medium 13, low 25, false 9, maybe-false 3. Patched groups: app-ref webhook fallback (high — provider timeout / early callback / crash), other plan starts today (high — no unpaid upgrade days), lost-webhook re-check by the status poll, free-offer cap atomic + once per company, checkout throttle, sandbox allow-list, disabled gateway → 503, stale `current()`, next_step on terminal requests, one-read webhook routing, shared amount conversion, optional request bodies, docs/comments, `.env.example`, and test gaps (settlement/webhook with a mocked gateway, push listener, 503 mapping, stale INITIATING, paid promo counter, plan modules granted). Rejected with reasons in the triage log; nothing deferred.
- **Follow-up review recommended: true** — high entries were patched after the review and not re-reviewed; the named risks are the `app_transaction_ref` fallback routing (attach-then-settle on a FAILED request) and the renewal rule change (same plan extended, other plan from today).
- **Verification:** clean compile OK; tests by package, all green after the patches — payments 39, subscription 145, architecture 134, admin 688, identity 866, shared 1 321 (one fixture-shape failure fixed, then `shared/infrastructure/event` 41 green). Matrix audit: every I/O row covered by `PaymentsFullContextTest` (ussd, otp, lock/cancel/stale, concurrent, provider failure + validation, free offer + cap, webhook rejections, poll racing webhook, renewal + frozen price + late payment, roles, stale INITIATING, paid promo, other plan, free once, throttle, late callback by our reference) or `CheckoutServiceTest` (gateway disabled → 503). Live keevo_dev (dev profile, sandbox, WhatsApp noop, FCM off): V61 applied; new owner KV-M01191 (trial) → PRO Mensuel MTN: PENDING « Composez *126# … », second checkout 409, bad secret « KO », bad signature « KO », unknown ref « OK », signed SUCCESS « OK », replay « OK », status CONFIRMED « Votre plan Pro est actif jusqu'au 08/12/2026 », `/subscription/me` PRO until 08/12 23:59:59 WAT, 1 ledger row + 1 SELF_SERVICE_PAYMENT event; Orange Money → OTP, wrong code → « Code de validation incorrect ». kv_11b5zm untouched; backend stopped with `fuser -k 4500/tcp`. No MyCoolPay call.
- **Residual risks:** real MyCoolPay contract only checked against Bref's code (mocked HTTP); « KO » with HTTP 200 relies on MyCoolPay redelivering (Bref parity); a PAID payment can push a promo code past its cap (soft cap, money honoured); production stays `disabled` until the user sets the MyCoolPay variables and registers the webhook URL.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée, commit `1eafeb25` (branche `refonte/modulaire`, poussée aussi sur `develop`). Clé de suivi : `m21-1-payments-mobile-money`. Conventions : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API**
- Le propriétaire paie son plan par Mobile Money (Orange Money, MTN Mobile Money) depuis « Mon plan ». Le prix est celui de `GET /subscription/offers` (21.10) au moment du démarrage, figé : un changement de prix ensuite ne change pas ce paiement.
- Un prix à 0 FCFA (remise de 100 %) est une offre gratuite : activée tout de suite, sans Mobile Money, une seule fois par entreprise pour une même remise.
- À la confirmation (rappel signé de MyCoolPay ou vérification pendant le suivi), le plan est activé (modules, date de fin prolongée pour un renouvellement du même plan ; un autre plan démarre aujourd'hui), le paiement apparaît côté super-admin, et le propriétaire reçoit une notification « Paiement reçu » (push + boîte de réception, type `PLAN_PAYMENT_CONFIRMED`, lien `/plan`) et un WhatsApp.
- Un seul paiement en cours par entreprise ; 5 démarrages au plus par heure.
- Rien ne change pour les apps V1 2.0.1.

**À utiliser** (propriétaire uniquement ; les autres rôles reçoivent 403 « Seul le propriétaire peut payer l'abonnement » ; code : `keevo/backend/src/main/java/com/keevo/payments/checkout/adapter/in/rest/PaymentsController.java`, vue `payments/checkout/application/service/PaymentView.java`)
- `POST /api/v1/payments/checkout` corps `{planCode, cycleCode, promoCode?, method, phoneNumber}` (alias acceptés : `plan`, `period`/`cycle`, `paymentMethod`, `phone`). `method` = `ORANGE_MONEY` | `MTN_MOMO` (`orange_money` / `momo` acceptés). `phoneNumber` : numéro camerounais (`677 00 01 11` ou `+237677000111`). `method` et `phoneNumber` ne sont pas demandés pour une offre gratuite.
- `POST /api/v1/payments/{id}/authorize` corps `{code}` (alias `otp`) : le code reçu par SMS quand `nextStep = OTP`.
- `GET /api/v1/payments/{id}` : l'état du paiement (l'API interroge l'opérateur tant qu'il n'est pas confirmé). À appeler toutes les 3 à 5 secondes pendant l'attente.
- `POST /api/v1/payments/{id}/cancel` : annule le paiement en cours (un paiement que l'opérateur confirme quand même plus tard est appliqué).
- `GET /api/v1/payments/current` : le paiement en cours de l'entreprise ; `data` absent quand il n'y en a pas.
- Réponse (`data`) : `{id, status, statusLabel, nextStep, ussdCode, method, methodLabel, planCode, planLabel, cycleCode, cycleLabel, months, baseXaf, reductionXaf, amountXaf, discountLabel, message, createdAt, confirmedAt, planExpiresAt}`.
  - `status` : `PENDING` (en attente), `CONFIRMED`, `FAILED`, `CANCELLED`, `EXPIRED` (`INITIATING` très bref) ; `statusLabel` et `message` sont les textes à afficher (par exemple « Composez *126# puis validez le paiement sur votre téléphone », « Saisissez le code reçu par SMS pour valider le paiement », « Votre plan Pro est actif jusqu'au 08/12/2026 », « Code de validation incorrect », « Le paiement n'a pas abouti », « Délai de paiement dépassé »).
  - `nextStep` : `OTP` (afficher la saisie du code), `USSD` (afficher `message` ; `ussdCode` peut être absent : validation par une notification de l'opérateur), `NONE`.
  - `amountXaf` = montant payé (0 pour une offre gratuite) ; `baseXaf` / `reductionXaf` / `discountLabel` pour le détail.
- Erreurs (afficher `error` tel quel) :
  - 422 `VALIDATION_ERROR` + `details.<champ>` : « Cette offre n'est pas proposée » (`planCode`), « Cette durée n'est pas proposée » / « Cette offre n'a pas de prix pour cette durée » (`cycleCode`), « Code promo invalide ou expiré » (`promoCode`), « Vous avez déjà profité de cette offre » (`promoCode`), « Choisissez Orange Money ou MTN Mobile Money » (`method`), « Numéro Mobile Money invalide (numéro camerounais à 9 chiffres) » (`phoneNumber`), « Code de validation invalide » (`code`) ;
  - 409 `PAYMENT_IN_PROGRESS` « Un paiement est déjà en cours. Terminez-le ou annulez-le. » → reprendre `GET /payments/current` ;
  - 409 `INVALID_STATE` « Ce paiement n'attend pas de code de validation », « Ce paiement ne peut plus être annulé » ;
  - 404 `PAYMENT_NOT_FOUND` « Paiement introuvable » ;
  - 429 `RATE_LIMIT_EXCEEDED` « Trop de tentatives de paiement. Réessayez dans une heure. » ;
  - 502 `PAYMENT_PROVIDER_ERROR` « Le paiement n'a pas pu démarrer. Vérifiez le numéro et réessayez. » / « La validation du code a échoué. Réessayez. » ;
  - 503 `PAYMENT_UNAVAILABLE` « Le paiement Mobile Money est momentanément indisponible » (paiement non configuré sur le serveur).

**À implémenter côté mobile**
1. Sur Android, dans « Mon plan », un bouton « Payer » par prix (plan × durée) de `/subscription/offers` ; un écran de paiement qui reprend le prix `effectiveXaf`, le champ « Code promo », le choix Orange Money / MTN Mobile Money et le numéro (préremplir avec le numéro du compte).
2. Après `checkout` : selon `nextStep`, afficher la saisie du code (OTP) ou le `message` (USSD) ; suivre l'état par `GET /payments/{id}` jusqu'à `CONFIRMED`, `FAILED`, `CANCELLED` ou `EXPIRED` (arrêter le suivi après 10 minutes) ; bouton « Annuler ».
3. À l'ouverture de « Mon plan », appeler `GET /payments/current` pour reprendre un paiement en cours (app fermée pendant la validation).
4. À `CONFIRMED` : afficher `message`, rafraîchir `/subscription/me`, `/tenants/me/modules` et `/subscription/offers`.
5. Offre gratuite (prix 0) : bouton « Activer » sans moyen de paiement ; réponse `CONFIRMED` directe.
6. Changement de plan : prévenir que le nouveau plan démarre tout de suite et que les jours restants de l'ancien plan ne sont pas reportés ; un renouvellement du même plan s'ajoute à la fin du plan en cours.
7. Hors ligne : pas de paiement (bouton désactivé avec « Connexion requise pour payer »).
8. Montants en FCFA avec espaces ; aucune donnée technique à l'écran (ni `id`, ni codes) : seulement les libellés et `message`.

**Implémentation de référence :** aucune dans `keevo/app` (client de test Flutter non modifié).

**Critères d'acceptation mobile / scénarios de test**
- Given un propriétaire en essai sur un serveur local (passerelle sandbox), when il paie Pro Mensuel avec MTN Mobile Money au 677 00 01 11, then l'écran affiche « Composez *126# puis validez le paiement sur votre téléphone » et attend.
- Given ce paiement, when le rappel signé arrive (simulation ci-dessous), then le suivi passe à « Votre plan Pro est actif jusqu'au … » et « Mon plan » affiche Pro.
- Given Orange Money, when il saisit le code 000000 (refusé par la sandbox), then « Code de validation incorrect » s'affiche et un nouveau paiement est possible.
- Given un paiement en cours, when il relance un paiement, then « Un paiement est déjà en cours. Terminez-le ou annulez-le. » et l'app reprend le paiement en cours.
- Setup curl : `POST /auth/register`, `POST /auth/login`, `POST /auth/select-tenant`, puis `curl -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' $API/payments/checkout -d '{"planCode":"PRO","cycleCode":"MONTHLY","method":"MTN_MOMO","phoneNumber":"677000111"}'`. Rappel simulé (serveur local, profil dev) : `POST $API/payments/webhook/mycoolpay/sandbox-callback` avec `{transaction_ref, transaction_type:"PAYIN", transaction_amount, transaction_currency:"XAF", transaction_operator:"CM_MOMO", transaction_status:"SUCCESS", signature}` où `signature = md5(transaction_ref + transaction_type + transaction_amount + transaction_currency + transaction_operator + "sandbox-private-key")` (la référence `transaction_ref` est celle stockée pour le paiement ; à défaut, ajouter `app_transaction_ref`).

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée, commit `1eafeb25`. Clé de suivi : `d21-1-payments-mobile-money`.

**Ce qui est fait côté API**
- Les paiements en ligne des propriétaires apparaissent dans la liste des paiements d'une entreprise, avec le plan et la durée.
- Les codes promo peuvent avoir un nombre d'utilisations maximal ; chaque remise compte ses utilisations confirmées.

**À utiliser**
- `GET /api/v1/admin/tenants/{id}/payments` : champ ajouté `online` (booléen) ; pour un paiement en ligne, `recordedByLabel` = « Paiement en ligne (Mobile Money) » et `note` = « Paiement en ligne — Orange Money » (ou MTN Mobile Money). Code : `admin/subscription/adapter/in/rest/dto/TenantPaymentDto.java`.
- `POST /api/v1/admin/offers/discounts` et `PUT /api/v1/admin/offers/discounts/{id}` : champ `maxRedemptions` (1 à 1 000 000, codes promo uniquement, absent = illimité). Erreurs 422 `details.maxRedemptions` : « Seul un code promo peut avoir un nombre d'utilisations maximal », « Le nombre d'utilisations doit être compris entre 1 et 1 000 000 ».
- `GET /api/v1/admin/offers/discounts` : champs ajoutés `maxRedemptions` et `redemptionCount` ; un code épuisé a `live = false`.
- Revenus : les paiements en ligne entrent dans `/admin/revenue` et `/admin/revenue/by-plan` comme les autres (plan et durée connus).

**À implémenter côté dashboard**
1. Dans la liste des paiements, un badge « En ligne » quand `online` est vrai (l'auteur affiché est `recordedByLabel`).
2. Dans le formulaire d'un code promo, le champ « Nombre d'utilisations maximal » (facultatif) ; dans la liste des remises, « Utilisations : 3 / 10 » (ou « 3 » sans plafond).
3. Rien de technique à l'écran.

**Critères d'acceptation dashboard**
- Given un propriétaire qui a payé Pro en ligne, when on ouvre ses paiements, then la ligne affiche « Paiement en ligne (Mobile Money) », Pro, Mensuel et le montant payé.
- Given un code promo plafonné à 1 et déjà utilisé, when on ouvre les remises, then il affiche « Utilisations : 1 / 1 » et n'est plus en vigueur.
- Setup curl : connexion super-admin puis `curl -H "Authorization: Bearer $T" $API/admin/tenants/<id>/payments`.
