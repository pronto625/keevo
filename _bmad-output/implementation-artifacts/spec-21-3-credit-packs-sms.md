---
title: '21-3 — Account credit and SMS packs: credit ledger imputed on the 21-1 checkout, pack catalogue, pack purchase and pack history'
type: 'feature'
created: '2026-11-09'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: '297dcff24de3569fdc8276235b8de4e9abb3b492'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-1-payments-mobile-money.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-8-compteurs-usage-quotas.md'
warnings: [oversized]
deferred:
  - summary: >-
      Plans paid with the account credit are outside MRR / paying figures (tenant_payments records cash only)
    evidence: |-
      Contract choice of 21-3; routed to 24-5 (epics-fusion-keevo-bref.md, story 24.5 routed items, 2026-11-09) where the Bref credit import decides how credit-paid periods count.
    location: >-
      PaymentConfirmationService.apply, admin/revenue RevenueMetrics
    severity: low
---

<intent-contract>

## Intent

**Problem:** Bref owners hold account credit (`account_credit`, applied first to a plan payment) and buy SMS (`sms_recharge_request`); Keevo has the 19.8 pack balance (`usage_credits`) but no credit at all, no way to buy a pack, no pack price, and pack consumption leaves no trace (routed from the 19.8 review: who, when, which counter, shown in « Mon plan »). FUS-FR10 / R9: credit and packs are real money, kept, displayed and usable.

**Approach:** One public migration V63. (1) **Account credit** in module `payments`: `tenant_credits` (balance ≥ 0) + append-only `tenant_credit_movements`; the owner opts in with `useCredit` on the existing 21-1 checkout: the credit is reserved (debited) with the request, released when the request ends unpaid, and a quote fully covered is confirmed at once without the operator. (2) **Usage packs** are offer data in `subscription.offer` (`usage_packs`, super-admin CRUD, journaled, seeded from Bref's 20 FCFA per SMS); the owner buys one through the SAME checkout/gateway/webhook (`packCode` instead of `planCode`), the confirmation credits `usage_credits`. (3) **Pack ledger** `usage_credit_movements` (append-only) records every purchase and every pack-paid consumption (actor, metric, quota period), readable by the owner and summed for the month in `/subscription/me`.

## Boundaries & Constraints

**Always:**
- Balance never negative: `CHECK (balance_xaf >= 0)` + every debit is one guarded statement (`… WHERE balance_xaf >= ?`) or follows a `FOR UPDATE` read in the same transaction; each balance change and its movement row are written in ONE statement (data-modifying CTE) or one transaction. Movements are append-only (trigger rejects UPDATE/TRUNCATE; DELETE only through the tenant purge).
- Credit reservation is exactly-once per request lifecycle: debited (`PAYMENT_USE`) in the transaction that inserts the request; released (`PAYMENT_RELEASE`) inside the SAME statement that moves the request from in-flight to FAILED / CANCELLED / EXPIRED (CTE on `payment_requests` transition → `tenant_credits` → movement). A late provider confirmation of such a request re-debits; when the balance no longer covers it, the money received is added to the credit (`PAYMENT_TO_CREDIT`, unique per request), the plan/pack is NOT applied, the request keeps its status with a French reason — never a negative balance, never free value.
- Prices frozen at initiation (21-1 rule): pack price/label/quantity/metric copied onto the request; plan quotes unchanged (`OfferPricing.quote`). `amount_due = base − reduction − credit_applied`, `0 ≤ credit_applied ≤ base − reduction`.
- `tenant_payments` keeps recording CASH only (subscription ledger, MRR): a plan paid partly by credit records the cash part; fully covered by credit → no row (like a free offer); pack purchases never go to `tenant_payments` (no subscription period) — the admin revenue summary gains additive pack totals read from confirmed pack requests.
- The free-offer-taken rule (21-1) now means `reduction_xaf = base_xaf` (a credit-covered request is not a « free offer »); a credit-covered request with a capped promo code takes the use within the cap (like a free offer).
- Pack catalogue = data (ADR-05): code, label, metric (`UsageMetric`), quantity 1..1 000 000, price 1..100 000 000 FCFA, active, sort order; no pack, price or quantity in Java. Discounts and promo codes do not apply to packs (422 if a promo code is sent with `packCode`).
- One payment in flight per company, plan or pack (same partial unique index, same throttle).
- OWNER only on owner endpoints; super-admin only on admin endpoints (`requireSuperAdmin`); French messages, no id/code in a message; additive changes only on existing responses; secrets/gateway selection unchanged (sandbox in tests/live check).
- FUS-NFR11: constant query count per request, keyset pagination (≤ 100 rows) for histories, an index for every new lookup, consumption recording = the same single statement as the guarded pack debit.

**Never:**
- No card payment, no refund to Mobile Money, no owner-to-owner credit transfer, no credit expiry, no new payment provider, no `keevo/app` change, no dashboard/mobile feature work (hand-over sections only), no edit of a committed migration, no real operator call.
- No port of Bref's `sms_recharge_request` rows: api-bref never collects them (the request is stored `pending`, no provider call, no confirmation code path) — nothing paid to carry; credit and pack balances are imported by 24.5.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Plan with partial credit | credit 1 000, PRO×MONTHLY 3 500, `useCredit:true`, MTN | request PENDING, `creditAppliedXaf` 1 000, `amountXaf` 2 500 sent to provider; balance 0, movement PAYMENT_USE −1 000 | — |
| Credit covers all | credit 5 000, PRO×MONTHLY 3 500, `useCredit:true`, no method/phone | CONFIRMED at once (provider NONE), plan applied, balance 1 500, no `tenant_payments` row | — |
| No credit chosen | credit 5 000, `useCredit` absent | 21-1 behaviour, balance untouched | — |
| Request ends unpaid | PENDING with credit 1 000 → cancel / provider FAILED / stale expiry | balance +1 000, movement PAYMENT_RELEASE (once) | — |
| Late payment, credit still there | CANCELLED request (credit released), provider SUCCESS, balance ≥ credit | re-debit PAYMENT_USE, plan applied, CONFIRMED | — |
| Late payment, credit spent | same, balance < credit | balance += amount paid (PAYMENT_TO_CREDIT), plan not applied, status kept, reason « Paiement reçu après l'annulation : 2 500 FCFA ajoutés à votre crédit » ; replay → no second credit | — |
| Buy SMS pack | `packCode` SMS_100 (100 SMS, 2 000), ORANGE_MONEY → webhook SUCCESS | `usage_credits` SMS +100, movement PURCHASE (+100, actor owner), push « Pack crédité », `/subscription/me` credits.SMS shows it | — |
| Pack replay | same webhook twice | one +100 | — |
| Pack + promo | `packCode` + `promoCode` | nothing stored | 422 « Les codes promo ne s'appliquent pas aux packs » |
| Unknown / inactive pack | `packCode` XYZ | nothing stored | 422 « Ce pack n'est pas proposé » |
| Plan and pack together | `planCode` + `packCode` | nothing stored | 422 « Choisissez un plan ou un pack, pas les deux » |
| Pack consumed | SMS MONTH quota used, pack 10, a manager sends an SMS | pack 9, movement CONSUMPTION −1, metric SMS, period MONTH, actor manager; `/subscription/me` `packsUsedThisMonth.SMS = 1` | — |
| Consumption probe rolled back | 19.9 probe transaction | no movement left (same statement, rolled back) | — |
| Admin credit adjustment | super-admin `+5 000 « Geste commercial »` | balance +5 000, movement ADMIN_ADJUSTMENT with actor and reason | debit beyond balance → 409 « Le crédit ne peut pas devenir négatif » ; amount 0 / no reason → 422 |
| Admin pack edit | PUT `/admin/offers/usage-packs/SMS_100` price 1 800 | next checkout charges 1 800; a request started before keeps 2 000; journal entry USAGE_PACK | invalid → 422 per field |
| Concurrency | two parallel checkouts with credit | one PENDING (credit debited once), one 409 | unique index |

</intent-contract>

## Code Map

- `db/migration/V63__account_credit_usage_packs.sql` -- NEW (last public V62).
- `payments/checkout/application/service/CheckoutService.java` -- `start`: + `packCode`, `useCredit`; credit reserved in the insert transaction; full coverage → `PaymentConfirmationService.confirmCovered`.
- `payments/checkout/application/service/PaymentConfirmationService.java` -- `confirm`: branch PLAN/USAGE_PACK, late re-debit / conversion; `confirmFree` unchanged semantics; new `confirmCovered` (MANDATORY tx).
- `payments/checkout/adapter/out/persistence/JdbcPaymentRequestStore.java` -- new columns; FAIL/CANCEL/EXPIRE statements become CTEs releasing credit; `FREE_TAKEN_SQL` on `reduction_xaf = base_xaf`; `confirm` with nullable period; `findCredited`.
- `payments/checkout/domain/model/PaymentRequest.java`, `PaymentKind` (NEW), `PaymentView`, `PaymentText`, `dto/CheckoutRequest`, `PaymentsController` -- additive fields.
- `payments/checkout/application/listener/PaymentPushListener.java` + `domain/event/PaymentNoticeEvent` (NEW: pack credited / payment converted to credit).
- `payments/credit/**` (NEW) -- `AccountCreditStore` port + `JdbcAccountCreditStore`, `AccountCreditService` (owner history, admin adjust), `CreditMovementKind` (French labels), `AccountCreditController` (`GET /api/v1/payments/credit`), `AdminAccountCreditController` (`/api/v1/admin/tenants/{tenantId}/credit`).
- `shared/capability/entitlement/UsageCredits.java` -- `add(tenant, metric, n, PackCredit)` with source/reference/actor; `UsagePackSource` enum.
- `subscription/plan/adapter/out/persistence/impl/JdbcUsageCounterStore.java` -- consume/add as CTEs writing `usage_credit_movements`; `USAGE_SQL` + month pack consumption; history query.
- `subscription/plan/application/service/UsageQuotaService.java` -- actor via `CurrentActor.actorIdOrNull()`, period passed to consume; `packMovements(...)`.
- `shared/infrastructure/security/CurrentActor.java` -- + `actorIdOrNull()`.
- `subscription/plan/adapter/in/rest/SubscriptionController.java` + `dto/SubscriptionResponse.java` -- `packsUsedThisMonth`; `GET /me/pack-movements`.
- `subscription/offer/**` -- `UsagePackService` (mirror `StoreProductService`), `UsagePackStore` + `JdbcUsagePackStore`, `OfferEntity.USAGE_PACK`; `subscription/api/UsagePacks.java` (NEW port read by payments); `OfferAdministration.usagePacks/saveUsagePack` + `CatalogueView.usagePacks`; `OfferPricing.OffersView.usagePacks`.
- `admin/offer/adapter/in/rest/AdminOfferController.java` + `dto/OfferRequests.java` -- `GET/PUT /api/v1/admin/offers/usage-packs`.
- `admin/revenue/**` -- `RevenueSummary` + DTO additive `packsCollectedXaf`, `packsSold`.
- `identity/auth/application/service/TenantDeletionService.java` -- purge credit, credit movements, pack movements.
- Bref (read-only): `api-bref/src/shop/{shop.service.ts:130, dto/recharge-sms.dto.ts}` (20 FCFA per SMS, request never collected), `src/payment-gateway/payment-gateway.service.ts:145-195` and `src/users/users.controller.ts:900-1080` (credit read `FOR UPDATE`, applied first, re-checked at confirmation, fully-covered confirmation without provider).
- Tests to mirror: `payments/PaymentsFullContextTest`, `subscription/plan/application/service/UsageQuotaPostgresIntegrationTest`, `admin/offer/OfferCatalogueFullContextTest`.

## Tasks & Acceptance

**Execution:**
- `V63__account_credit_usage_packs.sql` -- `usage_packs` (+ seed SMS_100 100/2 000, SMS_250 250/5 000, SMS_500 500/10 000, SMS_1000 1 000/20 000, ON CONFLICT DO NOTHING); `offer_change_log.entity` + USAGE_PACK; `tenant_credits`; `tenant_credit_movements` (kind ∈ ADMIN_ADJUSTMENT, PAYMENT_USE, PAYMENT_RELEASE, PAYMENT_TO_CREDIT, MIGRATION; unique request for PAYMENT_TO_CREDIT); `usage_credit_movements` (source ∈ PURCHASE, CONSUMPTION, MIGRATION; period ∈ MONTH, LIFETIME; unique request for PURCHASE); append-only triggers; `payment_requests` + kind, credit_applied_xaf, pack columns, plan columns nullable, shape check, new due check, pack revenue index.
- `payments/credit/*` + checkout changes + listener -- as Code Map.
- `subscription/offer/*`, `subscription/api/*`, `admin/offer/*` -- pack catalogue as Code Map; regenerate `docs/entitlements/endpoints.md`.
- `subscription/plan/*`, `shared/capability/entitlement/UsageCredits.java`, `CurrentActor` -- pack ledger as Code Map.
- `admin/revenue/*`, `TenantDeletionService` -- as Code Map.
- Tests -- `AccountCreditFullContextTest` (every credit/pack row of the matrix through MockMvc + signed sandbox webhook, concurrency, admin endpoints, role guards), `UsagePackCatalogueTest` (admin CRUD, validation, journal, frozen price), `UsageQuotaPostgresIntegrationTest` additions (consumption movement with actor/period, rollback leaves none, purchase idempotence guard), unit tests for `CreditMovementKind` labels and `PaymentView` messages; existing 21-1/21-2/19-8 tests stay green.

**Acceptance Criteria:**
- Given an owner with 1 000 FCFA credit, when they pay PRO × MONTHLY with `useCredit` and the signed webhook confirms, then the plan is active, `tenant_payments` has one MOBILE_MONEY row of 2 500, the credit history shows −1 000 « Utilisé pour un paiement » with « Pro · Mensuel », and the balance is 0.
- Given a SMS pack bought and confirmed, when the owner opens `GET /subscription/me` and `GET /subscription/me/pack-movements`, then `credits.SMS` includes the pack, the history lists the purchase (who, when) and each later pack-paid SMS (who, when, « quota du mois épuisé »).
- Given V63 run twice, then nothing changes; existing 21-1/21-2/21-10/19-8 contracts keep their fields (additive only).

## Spec Change Log

## Review Triage Log

### 2026-11-09 — Review pass
- verdicts: 38 findings (37 from the five layers + 1 from the live check) — high 1, medium 5, low 28, false 4, maybe-false 0
- findings:
  - `[low]` `[reject]` (blind) credit-funded plans skew MRR / « premium sans paiement » — real, by the contract's choice (`tenant_payments` = cash); routed to 24-5 (credit import decides the accounting of credit-paid periods) as a deferred item.
  - `[low]` `[reject]` (blind) late payment converted to credit not in collected revenue, no outstanding-credit figure — same root as above, routed with it to 24-5.
  - `[low]` `[reject]` (blind) pack sales missing from the revenue trend and per-company payment list — totals are in the summary; support sees purchases through the owner journal; not worth a new admin listing now.
  - `[low]` `[reject]` (blind) company deletion erases the credit journal — RGPD deletion by design (spec Design Notes); the cash trace stays in `tenant_payments` / `payment_requests`.
  - `[low]` `[patch]` (blind) month pack total scans every movement of the month — partial covering index `ix_usage_credit_movements_consumed (tenant_id, created_at) INCLUDE (metric, delta) WHERE source = 'CONSUMPTION'` in V63; retention rejected (money history kept).
  - `[medium]` `[patch]` (blind) a pack can be bought on a plan that never consumes it (no MONTH/LIFETIME quota for its metric) — checkout refuses: 422 « Votre offre ne limite pas cette consommation : ce pack ne vous servirait pas » (one status read).
  - `[low]` `[reject]` (blind) support adjustment not idempotent / owner not notified — super-admin action seen at once in the history; idempotency key adds public surface for a rare double click.
  - `[low]` `[patch]` (blind) replay after conversion to credit answered ALREADY_CONFIRMED — returns CREDITED (unit test).
  - `[low]` `[patch]` (blind) an existing pack code could change its metric — 422 « Le contenu d'un pack ne peut pas changer… » (full-context test).
  - `[low]` `[reject]` (blind) malformed phone sent with a credit-covered checkout → 422 — a value sent is validated; the client simply omits it.
  - `[low]` `[patch]` (blind) untested paths (deletion purges, exact revenue, OTP/start failure release, late pack payment) — deletion and revenue covered (see verification rows); OTP/start failure share the tested FAIL statement; late pack payment shares the tested confirm path.
  - `[low]` `[patch]` (blind) `PaymentView.months` int → Integer, plan fields null for packs — stated in the Mobile section (payments endpoints are new in 21-1, not used by V1 apps).
  - `[low]` `[patch]` (blind) « 1 SMS ajoutés » plural, dead null checks on `Long[]` — singular for 1; null checks removed.
  - `[low]` `[reject]` (blind) quota service reads the actor from the security context — the send runs on the request thread (19.9 counts in the caller's thread); no context = « Envoi automatique », which is the truth for scheduled sends; an extra port parameter would ripple through every caller.
  - `[low]` `[patch]` (edge) `amountXaf = Long.MIN_VALUE` overflows `Math.abs` → 500 — explicit range compare.
  - `[low]` `[reject]` (edge) credit-paid plans out of MRR — duplicate of the blind row, routed to 24-5.
  - `[low]` `[patch]` (edge) status poll re-asks the provider for a request already converted to credit — skipped when converted (one indexed EXISTS, only for an ended request holding credit).
  - `[low]` `[reject]` (edge) two concurrent creations of the same new pack code — super-admin only, the second write wins and both are journaled; no money involved.
  - `[false]` `[reject]` (edge) tenant deleted with a request in flight loses the release journal — the deletion removes the credit and its journal by design; there is nothing left to release.
  - `[medium]` `[patch]` (verification) pack-credited / late-payment push notices untested — `PaymentPushListenerTest.notice`.
  - `[medium]` `[patch]` (verification) tenant-deletion purges of the new tables untested — `TenantDeletionServiceTest` verifies the three deletes; `AccountDeletionRgpdPostgresIntegrationTest` seeds and checks them (and the other company keeps its rows).
  - `[medium]` `[patch]` (verification) revenue pack figures only `>=` — exact deltas: +1 / +2 000 for a paid pack, +1 / +0 for a pack paid by credit.
  - `[medium]` `[patch]` (verification) credit-covered checkout while Mobile Money is down untested — `CheckoutServiceTest.creditWhileGatewayDown` (confirmed without provider; short credit → 503, nothing stored).
  - `[low]` `[patch]` (verification) promo cap on a credit-covered checkout untested — `PaymentConfirmationServiceTest.coveredPromoUsedUp`.
  - `[high]` `[patch]` (verification, other) `RegistryEventSerializationTest` lacks a `PaymentNoticeEvent` fixture (would fail) — fixture added.
  - `[low]` `[patch]` (verification, other) `months` null for packs not documented — Mobile section.
  - `[low]` `[patch]` (nfr11) month pack total heap filter — same index as the blind row.
  - `[low]` `[reject]` (nfr11) actor read from the security context — same as the blind row.
  - `[low]` `[reject]` (nfr11) payments formatting helper lives in the credit service — same module, no cross-module edge.
  - `[low]` `[reject]` (nfr11) long positional `PaymentRequest` constructors — record style of 21-1, covered by tests; a builder is a refactor beyond the story.
  - `[low]` `[reject]` (nfr11) redundant reads in `adjust` — constant cost on a super-admin action.
  - `[low]` `[reject]` (nfr11) extra read after the pack upsert; `active` filtered in Java — mirrors `StoreProductService`, a handful of rows.
  - `[low]` `[reject]` (nfr11) owner credit history joins `users` — primary-key join on ≤ 101 rows; one SQL for both views.
  - `[false]` `[reject]` (nfr11) running balance wrong if two requests of one company are released together — impossible: one request in flight per company (unique index), documented in the SQL.
  - `[low]` `[reject]` (nfr11) V63 constraints validated under lock — `payment_requests` is small; same practice as V61.
  - `[false]` `[reject]` (intent) credit opt-in instead of Bref's automatic use — the AC says « si l'owner le choisit »; the mobile section tells the app to pre-check it.
  - `[false]` `[reject]` (intent) fixed packs instead of Bref's free quantity at 20 FCFA — ADR-05 (prices are catalogue data); Bref never collected those requests; App Store payments cannot use credit (Apple charges the buyer itself).
  - `[low]` `[patch]` (live check) owner shown « Un membre de l'équipe » on a purchase or a payment when the owner has no name — « Le propriétaire » for purchases and payment movements.

## Design Notes

**Patterns.** Ledger + balance (account pattern): a balance row (fast read, guarded by CHECK) and an append-only journal written in the same statement — the journal is the audit trail, the balance the concurrency guard. Reservation (two-phase hold) for credit on a checkout, compensated by a release fused into the state-machine transition statement, so a transition and its compensation can never diverge (no listener, no retry job). Strategy unchanged for gateways; the request is a tagged union (`kind`) so the one checkout/webhook/settlement path serves plans and packs (no second payment flow). Catalogue service mirrors `StoreProductService` (port + journal).

**Release CTE (shape):**
```sql
WITH moved AS (UPDATE public.payment_requests SET status='CANCELLED', … WHERE id=? AND tenant_id=? AND status IN ('INITIATING','PENDING')
               RETURNING id, tenant_id, credit_applied_xaf),
     refund AS (UPDATE public.tenant_credits c SET balance_xaf = c.balance_xaf + m.credit_applied_xaf
                FROM moved m WHERE c.tenant_id = m.tenant_id AND m.credit_applied_xaf > 0 RETURNING c.tenant_id, c.balance_xaf),
     logged AS (INSERT INTO public.tenant_credit_movements (…) SELECT … FROM moved m JOIN refund r USING (tenant_id))
SELECT count(*) FROM moved
```
(one in-flight request per company ⇒ at most one moved row per tenant, so the join is 1:1.)

**Cost (FUS-NFR11).** Checkout: +1 locked balance read and ≤ 1 debit statement inside the existing insert transaction. Transitions: still one statement. Confirmation: + ≤ 2 statements (re-debit or conversion; pack credit as one CTE). Pack consumption: the existing guarded UPDATE becomes one CTE (+1 index insert), no extra round trip. `/subscription/me`: the existing usage UNION gains one branch (index `(tenant_id, created_at)`), still one query. Histories: one keyset query with a `LEFT JOIN public.users` for names (PK) — no N+1. Admin revenue: one aggregate over the partial index `(confirmed_at) WHERE kind='USAGE_PACK' AND status='CONFIRMED'`. No per-tenant loop anywhere.

**Decisions.** Credit is reserved at initiation, not at confirmation: it guarantees the provider is asked for the right remainder and a concurrent use is impossible. Tenant deletion (RGPD) deletes the credit, its movements and the pack ledger (the cash trace stays in `tenant_payments` / `payment_requests`). Owner credit history shows French labels and the plan/pack bought, never the admin's free-text reason (support-only, shown on the admin endpoint). `packsUsedThisMonth` counts pack units consumed since the 1st (WAT).

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- expected: BUILD SUCCESS
- `wt-pkgs.sh keevo/backend <tag> payments subscription admin architecture identity messaging` then `shared` alone -- expected: 0 failures
- `-Dkeevo.entitlements.regenerate=true` on the endpoint coverage test -- expected: `docs/entitlements/endpoints.md` updated

**Manual checks:**
- Live (keevo_dev, sandbox gateway, WhatsApp noop, FCM off): V63 applied; admin grants credit to KV-JMYV4N; owner checkout PRO with `useCredit`; sandbox signed webhook; pack purchase; `/subscription/me` and both histories; admin pack price edit.

## Auto Run Result

Status: done

**Summary.** Account credit (`tenant_credits` + append-only `tenant_credit_movements`) usable on the 21-1 checkout (`useCredit`: reserved with the request, released in the same statement as an unpaid end, full coverage confirmed without the provider, late payment re-takes the credit or is added to it); usage packs as offer data (`usage_packs`, super-admin CRUD, journaled, seeded from Bref's 20 FCFA per SMS) bought through the same checkout/webhook (`packCode`), credited to `usage_credits` with a `PURCHASE` journal row; pack ledger `usage_credit_movements` (purchases and every pack-paid unit, actor and exhausted quota, same statement as the debit), owner history endpoint and `packsUsedThisMonth`; support credit endpoints; pack sales in the admin revenue summary; tenant deletion purges the new tables.

**Files changed.** V63 migration; `payments` (checkout: kind/credit/pack on requests, release CTE, confirmation, settlement, view, texts, push notice; new `credit` package: store, service, owner + admin controllers); `subscription` (usage pack catalogue service/store/port, offers listing, pack journal in `JdbcUsageCounterStore`, `PackHistoryService` + controller, `/me` field); `shared` (`UsageCredits.Grant`, `UsagePackSource`, `UsageStatus`, `CurrentActor.actorIdOrNull`); `admin` (pack endpoints, revenue summary); `identity` (tenant deletion); `docs/entitlements/endpoints.md`; tests (`AccountCreditFullContextTest`, `UsagePackCatalogueFullContextTest`, `PackHistoryServiceTest`, `UsagePackServiceTest`, `PaymentConfirmationServiceTest`, additions to `UsageQuotaPostgresIntegrationTest`, `CheckoutServiceTest`, `PaymentPushListenerTest`, `RegistryEventSerializationTest`, deletion tests, revenue integration wiring).

**Review.** 38 findings: 1 high, 5 medium, 28 low, 4 false. Patched: 1 high (event fixture), 5 medium (unusable pack refused, notice tests, deletion tests, exact revenue tests, credit while gateway down), 13 low. Deferred: 1 (credit-funded plans in MRR → 24-5). Rejected with reasons in the triage log.

**Follow-up review recommended: true** — one high and five medium patched; the unverified risk is the credit reservation/release across the provider paths not driven by the sandbox (real MyCoolPay timeouts between the insert and the provider answer, then a late callback): covered by construction (release fused in the transition statement, re-take or conversion at confirmation) and by sandbox tests, not by a real operator.

**Verification.** Clean compile; packages after the last fix: payments 84, subscription 153, admin 690, architecture 135, identity 866, messaging 370 — 0 failure; shared 1 321 (full package, run alone after one memory-killed attempt). Endpoints doc regenerated (+6 handlers). Live keevo_dev (profile dev, sandbox gateway, WhatsApp noop, FCM off): V63 applied; support +1 000 then −5 000 → 409 French message; PRO with credit → PENDING 2 500 due, signed webhook → CONFIRMED, history −1 000 « Pro · Mensuel », ledger 2 500 with credit note; SMS_100 by Orange (OTP then USSD) → webhook + replay → 100 SMS once, « 100 SMS ajoutés à votre compte », `/subscription/me` credits SMS 100, history « Pack acheté »; pack + promo → 422; admin price edit 1 900 seen by the owner then restored; admin credit view with reason; revenue packs 1 / 2 000. Three test companies were created by registration on keevo_dev (KV-SM81V3, KV-VPCEU6, KV-NSH0JI); kv_11b5zm untouched.

**Residual risks.** Credit-paid plan periods are not in MRR (routed to 24-5). The owner's credit is opt-in per payment (AC wording); the app should pre-check it. Bref's credit source (refund of a replaced scheduled plan) does not exist in Keevo (plans switch at once).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `4792f2b6`, branche `refonte/modulaire` = `develop`). Clé de suivi : `m21-3-credit-packs-sms`. Conventions : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API**
- Crédit du compte (FCFA, jamais négatif) : solde + historique ; utilisable sur un paiement de plan ou de pack si l'owner coche « Utiliser mon crédit ». Le crédit est réservé au démarrage du paiement, rendu si le paiement n'aboutit pas (annulation, échec, délai dépassé). Un prix entièrement couvert par le crédit est confirmé tout de suite, sans Mobile Money.
- Packs (SMS d'abord) : catalogue géré par le super-admin, achat par le même paiement Mobile Money que le plan (`POST /payments/checkout` avec `packCode`), crédités à la confirmation, visibles dans « Mon plan ».
- Historique des packs : achats et chaque unité consommée après épuisement du quota (qui, quand, quel quota), et total consommé sur les packs ce mois-ci.

**À utiliser** (fichiers : `payments/checkout/adapter/in/rest/PaymentsController.java`, `payments/credit/adapter/in/rest/AccountCreditController.java`, `subscription/plan/adapter/in/rest/PackMovementsController.java`, `subscription/offer/adapter/in/rest/OffersController.java`, `subscription/plan/adapter/in/rest/dto/SubscriptionResponse.java`)
1. `GET /api/v1/subscription/offers` → nouveau champ `usagePacks: [{code, label, metric, metricLabel, quantity, priceXaf}]` (packs proposés, pas de remise ni code promo sur les packs).
2. `GET /api/v1/subscription/me` → nouveau champ `packsUsedThisMonth: {"SMS": 3}` (unités prises sur les packs depuis le 1er du mois) ; `credits` (19.8) = unités restantes des packs.
3. `GET /api/v1/subscription/me/pack-movements?metric=SMS&limit=20&cursor=…` (OWNER) → `{items: [{metric, metricLabel, change, remainingAfter, source, sourceLabel, counterLabel, byLabel, at}], nextCursor}` ; `change` +100 achat / −1 consommation ; `counterLabel` « Quota du mois épuisé » / « Quota à vie épuisé » (consommations seulement) ; `byLabel` nom, « Le propriétaire », « Envoi automatique », ou null (repris de l'ancien compte). `nextCursor` null = dernière page.
4. `GET /api/v1/payments/credit?limit=20&cursor=…` (OWNER) → `{balanceXaf, movements: [{kind, label, detail, amountXaf, balanceAfterXaf, at}], nextCursor}` ; `label` p. ex. « Utilisé pour un paiement », « Rendu : paiement non abouti », « Ajustement par le support », « Paiement reçu ajouté au crédit » ; `detail` « Pro · Mensuel » / « Pack 100 SMS ».
5. `POST /api/v1/payments/checkout` — corps 21-1 + `packCode` (au lieu de `planCode`/`cycleCode`) + `useCredit` (booléen, défaut false). Réponse `PaymentView` + `kind` (`PLAN|USAGE_PACK`), `packCode`, `packLabel`, `packMetric`, `packMetricLabel`, `packQuantity`, `creditAppliedXaf` ; `amountXaf` = ce que Mobile Money encaisse. **Attention :** pour un pack, `planCode`, `planLabel`, `cycleCode`, `cycleLabel`, `months` et `planExpiresAt` sont `null` (`months` devient nullable). Si `useCredit` couvre tout : `status` CONFIRMED tout de suite, `method`/`phoneNumber` facultatifs. Ensuite même suivi qu'en 21-1 (OTP, USSD, `GET /payments/{id}`, annulation).
6. Erreurs (HTTP, message français à afficher tel quel) :
   - 422 `VALIDATION_ERROR` « Choisissez un plan ou un pack, pas les deux » (`details.packCode`) ;
   - 422 « Ce pack n'est pas proposé » (`details.packCode`) → recharger la liste des packs ;
   - 422 « Votre offre ne limite pas cette consommation : ce pack ne vous servirait pas » (`details.packCode`) → masquer le pack ;
   - 422 « Les codes promo ne s'appliquent pas aux packs » (`details.promoCode`) ;
   - 422 « Choisissez Orange Money ou MTN Mobile Money » / numéro invalide quand le crédit ne couvre pas tout ;
   - 409 `PAYMENT_IN_PROGRESS` (un paiement, plan ou pack, à la fois), 503 `PAYMENT_UNAVAILABLE` (crédit insuffisant et Mobile Money coupé), 403 pour un membre non propriétaire sur crédit / historique des packs.
7. Un paiement reçu après annulation ou expiration, quand le crédit réservé a déjà été dépensé : l'argent va au crédit, la demande garde son statut et `message` = « Paiement reçu après l'annulation : 2 500 FCFA ajoutés à votre crédit ».
8. Notifications (push + boîte de réception, lien `/plan`) : `USAGE_PACK_CREDITED` « Pack crédité » / « 100 SMS ajoutés à votre compte. » ; `PAYMENT_CREDITED` « Paiement reçu » / « 2 500 FCFA ajoutés à votre crédit. ».

**À implémenter côté mobile**
1. « Mon plan » : afficher le solde de crédit (`/payments/credit`) et, à côté de chaque quota mensuel, les packs restants (`credits`) et consommés ce mois (`packsUsedThisMonth`) ; lien vers l'historique des packs et l'historique du crédit (pagination par `nextCursor`).
2. Achat d'un pack : liste `usagePacks` (prix, quantité), puis le même parcours Mobile Money que le plan avec `packCode`.
3. Case « Utiliser mon crédit (X FCFA) » quand le solde > 0, cochée par défaut (Bref l'appliquait toujours) ; afficher « Crédit utilisé » et « À payer » depuis la réponse ; si `status` CONFIRMED d'emblée, afficher la confirmation sans écran Mobile Money.
4. Hors ligne : afficher les derniers soldes en cache avec la date de mise à jour ; achat et paiement uniquement en ligne.
5. Aucun code technique à l'écran (`kind`, `source`, `metric` servent à la logique ; afficher les `…Label`).

**Implémentation de référence :** aucune dans `keevo/app` (API seulement).

**Critères d'acceptation mobile / scénarios de test**
- Étant donné un crédit de 1 000 FCFA (ajouté par le support), quand l'owner paie PRO Mensuel avec « Utiliser mon crédit », alors l'écran indique 1 000 FCFA de crédit utilisé et 2 500 FCFA à payer, et après validation le plan est actif et le solde est 0.
- Étant donné un crédit qui couvre le prix, quand l'owner paie avec le crédit, alors le plan est actif sans écran Mobile Money.
- Étant donné l'achat du « Pack 100 SMS » confirmé, quand l'owner ouvre « Mon plan », alors il voit 100 SMS de pack et la ligne « Pack acheté » dans l'historique.
- Mise en place (curl, serveur local profil dev, passerelle sandbox) : `POST /api/v1/admin/tenants/{tenantId}/credit/adjustments {"amountXaf":1000,"reason":"Test"}` (super-admin) ; webhook sandbox signé comme en 21-1.

**Clé de suivi mobile :** `m21-3-credit-packs-sms`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée (commit `4792f2b6`). Clé de suivi : `d21-3-credit-packs-sms`.

**Ce qui est fait côté API :** catalogue des packs modifiable (ADR-05), crédit du compte par entreprise (solde, historique avec auteur et raison, ajustements), ventes de packs dans le résumé des revenus.

**À utiliser** (fichiers : `admin/offer/adapter/in/rest/AdminOfferController.java`, `payments/credit/adapter/in/rest/AdminAccountCreditController.java`, `admin/revenue/adapter/in/rest/dto/RevenueSummaryDto.java`)
1. `GET /api/v1/admin/offers/usage-packs` → `[{code, label, metric, metricLabel, quantity, priceXaf, unitPriceXaf, active, sortOrder, updatedAt}]` ; aussi dans `GET /api/v1/admin/offers` (`usagePacks`).
2. `PUT /api/v1/admin/offers/usage-packs/{code}` `{label, metric, quantity, priceXaf, active, sortOrder}` — création (tous requis sauf `active`/`sortOrder`) ou modification (champs absents conservés). 422 `VALIDATION_ERROR` par champ (`details.code|label|metric|quantity|priceXaf|sortOrder`), dont « Le contenu d'un pack ne peut pas changer : créez un nouveau pack avec un autre code ». Journalisé (`GET /api/v1/admin/offers/changes`, entité « Pack »). Un paiement déjà démarré garde son prix. `active=false` = plus proposé.
3. `GET /api/v1/admin/tenants/{tenantId}/credit?limit=&cursor=` → `{balanceXaf, movements: [{kind, label, detail, amountXaf, balanceAfterXaf, byLabel, reason, at}], nextCursor}` ; 404 entreprise inconnue.
4. `POST /api/v1/admin/tenants/{tenantId}/credit/adjustments` `{amountXaf (signé, ≠ 0, ±100 000 000), reason (3..300)}` → même vue ; 409 « Le crédit ne peut pas devenir négatif (solde : 1 000 FCFA) » ; 422 montant ou raison.
5. `GET /api/v1/admin/revenue/summary` → nouveaux champs `packsSold`, `packsCollectedXaf` (argent encaissé sur la période, hors crédit).

**À implémenter côté dashboard**
1. Écran « Offre » : onglet « Packs » (liste, création, édition, désactivation) avec prix unitaire affiché.
2. Fiche entreprise : bloc « Crédit » (solde, historique, bouton « Ajuster » avec montant signé et raison obligatoire, confirmation avant un retrait).
3. Revenus : tuiles « Packs vendus » et « Encaissé sur les packs ».
4. Libellés français uniquement, jamais d'identifiant à l'écran.

**Critères d'acceptation dashboard**
- Étant donné le pack SMS_100 à 2 000 FCFA, quand le super-admin le passe à 1 900, alors le journal montre la modification et la liste owner affiche 1 900.
- Étant donné une entreprise sans crédit, quand le super-admin retire 500 FCFA, alors le message « Le crédit ne peut pas devenir négatif (solde : 0 FCFA) » s'affiche et rien ne change.

**Clé de suivi dashboard :** `d21-3-credit-packs-sms`.
