---
title: '21-10 — Configurable offer catalogue: plans, cycles, prices, trial policy, discounts (ADR-05, FUS-FR40)'
type: 'feature'
created: '2026-11-09'
status: 'in-progress'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: PLACEHOLDER
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-7a-plans-en-donnees.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-7b-plans-fusion.md'
warnings: [oversized, multiple-goals]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Since 19.7a/19.7b/19.8 plans, profiles and quotas are data, but nothing else of the commercial offer is: there is no price, no billing cycle, no discount, no trial switch, no super-admin CRUD of the plans themselves, no endpoint the app can read for « Mon plan », and revenue lumps every paid plan into one bucket. Three 19.7a review items wait here (kind change without resync, archived/hidden attribution, revenue per plan).

**Approach:** One public migration adds billing cycles, prices per plan × cycle (seeded with Bref's grid) with a typed price history, discounts (dated promotion, tenant discount, promo code), a trial switch, a catalogue change journal and the plan/cycle of each recorded payment. A `subscription.offer` slice owns the CRUD (exposed to `admin` through `subscription.api`), a pure pricing policy (best single discount) used by a tenant endpoint `GET /api/v1/subscription/offers` and by a quote port that 21.1/21.2 will call, and the revenue dashboard gets a per-plan × cycle breakdown.

## Boundaries & Constraints

**Always:**
- Every price, label, cycle, plafond, quota, trial length and plan code lives in Flyway seed data or in rows the super-admin edits; Java holds only concepts (kinds, discount scopes, entity types). `PlanCodeLiteralGateTest` stays green.
- All new tables are `public` (platform catalogue, not tenant data): one public migration `V60__offer_catalogue.sql`, idempotent, seeds with `ON CONFLICT DO NOTHING`.
- Every write is validated with French messages (422 `VALIDATION_ERROR` + `details.<field>`, 404 `NOT_FOUND`, 409 `INVALID_STATE`), journaled in `public.offer_change_log` (French summary, actor, instant) in the same transaction, and visible at the next request (no cache on prices/discounts; plan writes go through `PlanCatalogStore`, which invalidates the catalogue cache).
- Archiving a plan or a cycle never touches tenants already on it; codes are never deleted.
- Additive only on existing responses (V1 2.0.1 apps): `TenantPaymentDto`, `ActivatePremiumRequest`, `PlanSettings` gain optional fields only.
- Set-based SQL, constant query count per request (FUS-NFR11).

**Never:**
- No new tenant module (`payments` module, its tenant tables and provider integration are 21.1); no payment initiation, no StoreKit/RevenueCat price reading (21.2).
- No dashboard or mobile feature work (sections at the end hand over to the other repos); no change under `keevo/app`.
- No stacking of discounts; no promo-code redemption counting (no payment yet).
- No UUID or raw code in a French message or in the change-journal summary (labels only).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Seeded grid | V60 applied | PRO 3 500/9 500/32 760, TEAM 5 500/15 000/51 480, BUSINESS 8 500/23 000/79 560 XAF on MONTHLY/QUARTERLY/ANNUAL; one history row each (old null, author null = « Données de départ ») | — |
| Price change | PUT PRO × MONTHLY 4 000 | price row 4 000, history (3 500 → 4 000, actor, now), journal line, next `/subscription/offers` shows 4 000 | amount ≤ 0 or > 100 000 000 → 422 `details.amountXaf` |
| Price on a non-paid plan | PUT FREE × MONTHLY | refused | 422 « Seuls les plans payants ont un prix » |
| Best discount | PROMOTION 10 % on PRO/all cycles + TENANT 1 000 XAF on the tenant | quote PRO × MONTHLY 4 000: reduction max(400, 1 000) → 3 000, discount = the tenant one | — |
| Amount over price | amount-off 5 000 on a 3 500 price | effective 0 | — |
| Promo code | `?promoCode=bref10` matching live PROMO_CODE BREF10 | applied (case-insensitive) when it is the best; response `promoCode.valid=true` | unknown/expired → `promoCode.valid=false`, « Code promo invalide ou expiré » (listing); quote port → 422 |
| Expired / future discount | ends_at ≤ now or starts_at > now | ignored | — |
| Kind change of a used plan | PUT plan TEAM kind FREE while a live tenant has TEAM | refused, nothing saved | 409 `INVALID_STATE` « Ce plan est attribué à des entreprises : sa nature ne peut plus changer » |
| Archive a settings plan | archive FREE (free plan of the settings) | refused | 409 « Ce plan est utilisé par les réglages de l'offre : changez d'abord les réglages » |
| Archive a plan in use by tenants | archive PRO | archived, tenants keep PRO, PRO disappears from offers and from activation | — |
| Activate archived plan | `POST /admin/tenants/{id}/plan/premium` planCode=PREMIUM | refused (19.7b behaviour, pinned by a test) | 422 « Ce plan ne peut pas être attribué » |
| Hidden plan | visible=false, not archived | absent from `/subscription/offers` and refused by the quote port; still attributable by the super-admin (private deal) | quote → 422 « Cette offre n'est pas proposée » |
| Duplicate code | create plan PRO / cycle MONTHLY / live promo code BREF10 | refused | 409 « Ce code existe déjà » |
| Trial off | settings `trialEnabled=false` | a new registration starts on the free plan, no end date, REGISTRATION event to the free plan | — |
| Revenue by plan | payments with plan/cycle, legacy payments without | rows per (plan, cycle) with labels; missing plan → « Plan non précisé », missing cycle → « Durée libre » | invalid range → 422 (existing rules) |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/migration/V60__offer_catalogue.sql` -- NEW (last public V59).
- `shared/capability/entitlement/PlanSettings.java` -- record +`trialEnabled` (canonical 5 args, 4-arg constructor keeps `true`); `PlanCatalog.SEED` unchanged values.
- `shared/module/adapter/out/persistence/JdbcPlanCatalog.java` -- SETTINGS_SQL / UPSERT_SETTINGS_SQL + `trial_enabled`; `save`/`saveSettings` reused by the CRUD (cache invalidation at write and after commit already there).
- `identity/auth/application/service/TenantFactory.java:96-118` -- trial off → free plan, `null` months; `shared/infrastructure/persistence/TenantSchemaProvisioner.java:123,264-283` -- `Integer` months overload (`setNull` → `make_interval(NULL)` → `expires_at NULL`).
- `admin/subscription/application/service/AdminPlanService.java:151-215` -- activation stores plan + optional cycle on the payment; `paidPlan` already refuses archived / non-PAID (19.7b).
- `admin/subscription/domain/model/TenantPayment.java`, `adapter/out/persistence/JdbcTenantPaymentRepository.java`, `adapter/in/rest/dto/ActivatePremiumRequest.java`, `dto/TenantPaymentDto.java`, `domain/port/in/ActivatePremiumCommand.java` -- additive `planCode`, `billingCycle`.
- `admin/revenue/application/service/AdminRevenueService.java:60-63` (PAYMENTS_SQL), `domain/model/RevenuePayment.java`, `RevenueMetrics.countedPayments/monthlyValue` -- reused for the by-plan breakdown; `adapter/in/rest/AdminRevenueController.java` -- new route.
- `subscription/api/` -- NEW `OfferAdministration` (admin CRUD + views), `OfferPricing` (quote port for 21.1/21.2 + tenant offers view).
- `subscription/offer/**` -- NEW slice: `domain/model` (BillingCycle, PlanPrice, Discount, DiscountScope, PricedOffer, OfferPricer), `domain/port/out/OfferStore`, `adapter/out/persistence/JdbcOfferStore`, `application/service/OfferCatalogService`, `OfferPricingService`, `adapter/in/rest/OffersController`.
- `admin/offer/adapter/in/rest/AdminOfferController.java` + `dto/*` -- NEW super-admin REST (`/api/v1/admin/offers/**`, `subscription :: api` dependency already allowed).
- `shared/module/PlanProfileAdmin.java` (`list()`) -- limits, modules, quotas of each plan for the offers view (one batch read).
- `shared/capability/entitlement/PlanCatalog.java` -- plan lookups; `shared/module/application/port/PlanCatalogStore.java` -- plan writes.
- Tests to mirror: `JdbcPlanCatalogPostgresIntegrationTest`, `PlansAreDataFullContextTest`, `AdminPlanServiceTest`, `AdminRevenueServiceTest`, `TenantFactoryTest`, `PlanCodeLiteralGateTest`.

## Tasks & Acceptance

**Execution:**
- `V60__offer_catalogue.sql` -- `billing_cycles` (code, label, months 1..36, sort_order, archived_at; seed MONTHLY « Mensuel » 1, QUARTERLY « Trimestriel » 3, ANNUAL « Annuel » 12); `plan_prices` (plan × cycle PK, amount_xaf > 0, updated_by/at) + `plan_price_history` (old/new nullable, changed_by nullable, changed_at; indexes (plan, cycle, changed_at DESC) and (changed_at DESC)); Bref grid seeded with its history rows through a CTE; `discounts` (scope PROMOTION|TENANT|PROMO_CODE, label, exactly one of percent_off ∈ ]0,100] / amount_off_xaf > 0, plan_codes[] / cycle_codes[] empty = all, starts_at/ends_at, tenant_id FK for TENANT only, promo_code for PROMO_CODE only, PROMOTION requires ends_at, archived_at, created/updated by/at; unique live upper(promo_code); partial indexes for live tenant/promo lookups); `offer_change_log` (entity, entity_key, summary, actor_id, occurred_at; index occurred_at DESC); `plan_settings.trial_enabled` default true; `tenant_payments.plan_code`/`cycle_code` FKs + set-based backfill of plan_code from the activation event (LATERAL, latest PAID-kind event ≤ payment creation + 1 min).
- `PlanSettings`, `JdbcPlanCatalog`, `TenantFactory`, `TenantSchemaProvisioner` -- trial switch.
- `subscription/offer/domain/model/OfferPricer.java` -- pure policy: candidates matching plan/cycle/window/scope, reduction = round-half-up(base × % / 100) or min(amount, base), best reduction wins (tie → PROMO_CODE, TENANT, PROMOTION, then earlier end); effective = base − reduction.
- `subscription/offer/adapter/out/persistence/JdbcOfferStore.java` -- cycles, prices (row lock + history), discounts (one query of live candidates for tenant/promo/now), journal, tenant counts per plan (GROUP BY), plans referenced by settings, copy of a source plan's profile/modules/quotas (three INSERT … SELECT), reorder (one UPDATE over `unnest … WITH ORDINALITY`).
- `subscription/offer/application/service/OfferCatalogService.java` -- validation + journal + guards (kind change of a used plan, archive of a settings plan, price only on PAID, codes unique, references exist).
- `subscription/offer/application/service/OfferPricingService.java` -- `quote(tenantId, plan, cycle, promoCode)` (plan live + visible + PAID, cycle live, price present; promo invalid → 422) and `offersFor(tenantId, promoCode)`.
- `subscription/offer/adapter/in/rest/OffersController.java` -- `GET /api/v1/subscription/offers` (OWNER or store member; super-admin 403).
- `admin/offer/adapter/in/rest/AdminOfferController.java` -- GET catalogue; plans POST / PUT / archive / unarchive / order; cycles POST / PUT / archive / unarchive; prices PUT / DELETE / history; settings PUT; discounts GET / POST / PUT / archive; changes GET; quote GET.
- `AdminPlanService`, payment model/repository/DTOs -- plan + optional cycle on recorded payments.
- `AdminRevenueService` + `AdminRevenueController` -- `GET /api/v1/admin/revenue/by-plan?from&to`.
- `ErrorCode` -- no new code: 422 `VALIDATION_ERROR` (`details.<field>`), 404 `NOT_FOUND`, 409 `INVALID_STATE`, all through `DomainException.userFacing` with the French message.
- Tests -- `OfferPricerTest` (matrix rows: best discount, cap, window, promo case, tie), `OfferCatalogServiceTest` (guards, validation), Postgres integration test on V60 + `JdbcOfferStore` (seed grid, history, discount candidate query, reorder, profile copy, backfill), full-context test of the admin + tenant endpoints (price change visible at once, archived plan out of offers, kind change 409, trial off registration), `AdminRevenueService` by-plan test, `AdminPlanServiceTest` (plan/cycle stored, archived refused), gate test still green.

**Acceptance Criteria:**
- Given V60 on a database at V59, when it is applied twice, then the second run changes nothing and the seeded grid, cycles and history match the I/O matrix.
- Given the super-admin endpoints, when a plan, cycle, price, trial policy or discount is created, modified, reordered, hidden or archived, then the change is validated in French, journaled (`GET /admin/offers/changes` lists it with the actor's name), and the next `GET /api/v1/subscription/offers` reflects it.
- Given an owner, when they call `GET /api/v1/subscription/offers`, then they get the visible live FREE/PAID plans in display order with limits, modules, quotas, every live cycle's base price, effective price, per-month price and the applied discount (label, percent or amount, end date), plus their current plan.
- Given the quote port, when 21.1 asks for a plan × cycle, then it receives the effective discounted price at that instant (the caller stores it at initiation).
- Given payments recorded with plan and cycle, when the super-admin reads `/admin/revenue/by-plan`, then MRR, paying tenants and collected revenue are split per plan × cycle with French labels.

## Spec Change Log

## Review Triage Log

## Design Notes

- **No `payments` tenant module here (decision).** The brief says « migration du module payments », but prices, cycles and discounts are platform catalogue data read by every tenant and by the super-admin: like `plans`/`plan_quotas` they belong in `public`. A tenant module would need a per-tenant copy and a backfill for nothing. The `payments` module (transactions, providers) stays 21.1.
- **Patterns:** catalogue-as-data repository behind ports (`OfferStore`, `PlanCatalogStore`); pricing is a pure **policy object** (`OfferPricer`, best-single-discount strategy) shared by the tenant listing, the admin preview and the 21.1 quote port, so the price shown is the price charged. Journal = append-only audit table written in the same transaction as the change.
- **Hidden vs archived (19.7a item):** archived = closed for any new attribution (activation already refuses since 19.7b, now pinned by a test; quote refuses); hidden = not offered to customers (offers listing, quote) but still attributable by the super-admin for a private deal. Settings already require live plans.
- **Kind change (19.7a item):** refused (409) while a non-deleted tenant has the plan (`public.tenants.plan_type`, kept in sync by `JdbcPlanChangeRecorder`, index `ix_tenants_plan_type`) or settings reference it — a resync of every tenant's module rows and expiry semantics on a kind flip is riskier than asking the admin to create a new plan.
- **Revenue (19.7a item):** payments now carry plan and cycle; the by-plan view reuses `RevenueMetrics` counted payments (one query for payments, catalogue labels from the cache, one cycles query), legacy rows bucketed « Plan non précisé » / « Durée libre ».
- **Trial beneficiaries:** `trial_enabled` (every new company / nobody). « First company of an owner only » needs the owner at `TenantFactory.create()`; not asked by any AC — not done.
- **Cost:** offers listing = catalogue cache + 1 profiles batch + 1 cycles/prices query + 1 live-discounts query + 1 current-plan read; pricing in memory O(plans × cycles × discounts) on tens of rows. Admin catalogue = 4 queries + 1 tenant-count GROUP BY. Price write = 1 locked read + 1 upsert + 2 inserts. Reorder = 1 UPDATE. Backfill = one UPDATE … FROM LATERAL over `tenant_payments` (index `idx_tenant_plan_events_tenant_occurred`).
- **Routed:** StoreKit price gap shown to the super-admin → 21-2 (store prices are not readable before RevenueCat); price kept at initiation + promo redemption counting → 21-1 (stores the quote with the payment).

## Verification

**Commands:**
- `kmvn.sh … -q -B clean compile` -- expected: success.
- `wt-pkgs.sh keevo/backend 21-10 subscription admin architecture` then `shared identity` -- expected: 0 failure.

**Manual checks (if no CLI):**
- Local backend (WhatsApp noop, FCM off) on keevo_dev: V60 applied; `GET /api/v1/admin/offers` shows the grid; PUT a price then `GET /api/v1/subscription/offers` as an owner (not kv_11b5zm) shows it; restore the price.
