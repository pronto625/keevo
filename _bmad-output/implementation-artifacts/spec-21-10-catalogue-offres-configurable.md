---
title: '21-10 — Configurable offer catalogue: plans, cycles, prices, trial policy, discounts (ADR-05, FUS-FR40)'
type: 'feature'
created: '2026-11-09'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: '61520022ae88be4f3f82bf5548b40fe130989859'
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

### 2026-11-09 — Review pass
- verdicts: 39 findings — high 0, medium 13, low 21, false 5, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) createPlan duplicate check reads the cache, then UPSERT can overwrite an existing plan — insert-only path, 409 on conflict, catalogue invalidated now and after commit.
  - `[medium]` `[patch]` (blind) journal keyset cursor on occurred_at only skips rows sharing the boundary timestamp — cursor (occurred_at, id) with optional beforeId, index (occurred_at DESC, id DESC), tie test.
  - `[medium]` `[patch]` (blind) kind change of an unused PAID plan leaves its prices — 409 while prices exist.
  - `[medium]` `[patch]` (blind) a cycle's months can change while prices/payments use it — 409 when in use.
  - `[low]` `[reject]` (blind) activation stores a cycle without checking price/amount/period — off-platform payments record what was actually paid (9-2 rule: amount is free, custom deals); the cycle is a label for revenue. Fix would add rules the intent does not ask.
  - `[false]` `[reject]` (blind) admin quote cannot preview hidden plans — by design (Design Notes): the quote is the self-service price; hidden plans are private deals priced by the super-admin at activation. Unknown tenantId ignored → promotions-only quote, harmless.
  - `[low]` `[reject]` (blind) 0 XAF effective price allowed — a 100 % discount is a legitimate « offert »; how 21.1 handles a 0 quote is noted in its routed item. Discounts on FREE/TRIAL plans never apply (no price) — harmless.
  - `[medium]` `[patch]` (blind) admin services read public.billing_cycles with raw SQL — go through the subscription.api port.
  - `[low]` `[patch]` (blind) payment label fallbacks differ between /payments (null) and /revenue/by-plan — shared French fallbacks.
  - `[low]` `[reject]` (blind) discounts ON DELETE CASCADE with tenant hard delete; journal not DB-enforced append-only — a company-specific discount has no meaning once the company is purged (16-7); the journal keeps the French line. Enforcement trigger not worth the complexity.
  - `[medium]` `[patch]` (blind) missing tests on many write paths — grouped with the verification-gap rows below.
  - `[low]` `[patch]` (blind) setPrice returns clock.instant() as updatedAt when unchanged — stored updated_at returned.
  - `[low]` `[patch]` (blind) unknown current plan reported as kind FREE — kind null + WARN. (Controller using the concrete service: within the module, false as a defect.)
  - `[medium]` `[patch]` (edge) = blind createPlan overwrite (same root cause).
  - `[low]` `[patch]` (edge) concurrent first setPrice: FOR UPDATE locks no row → two « created » history rows — plan row locked first.
  - `[medium]` `[patch]` (edge) = blind kind change with prices.
  - `[medium]` `[patch]` (edge) = blind cycle months change.
  - `[low]` `[patch]` (edge) empty cycleCode → 422 at bean validation — pattern accepts empty, blank = absent.
  - `[medium]` `[patch]` (nfr11) = blind journal cursor + index shape (also price history index).
  - `[low]` `[patch]` (nfr11) discounts.tenant_id FK only covered by a partial index — plain index.
  - `[medium]` `[patch]` (nfr11) discount list unbounded with includeArchived — LIMIT added.
  - `[low]` `[patch]` (nfr11) planItem loads every price — prices(planCode).
  - `[low]` `[patch]` (nfr11) cycle-only price history filter has no index — (cycle_code, changed_at DESC).
  - `[medium]` `[patch]` (nfr11) = blind raw SQL on billing_cycles from admin.
  - `[low]` `[patch]` (nfr11) comparator indexOf per comparison — precomputed rank maps.
  - `[low]` `[patch]` (nfr11) byPlanAndCycle Javadoc understates cost — comment fixed.
  - `[false]` `[reject]` (nfr11) split OfferCatalogService — reviewer's own « no action »: an application service over ports, no complexity defect.
  - `[false]` `[reject]` (nfr11) referencing-side FK indexes on tenant_payments / price history — codes are never deleted or renamed (archive only), so the FK lookup never runs.
  - `[low]` `[reject]` (nfr11) shared summary verdict « no N+1, no per-tenant loop » — informational, not a finding.
  - `[low]` `[patch]` (verification-gap) cycle CRUD and archived-cycle exclusion untested — tests added.
  - `[low]` `[patch]` (verification-gap) hidden live plan exclusion untested — test added.
  - `[low]` `[patch]` (verification-gap) deletePrice / updateDiscount / archiveDiscount untested — tests added.
  - `[low]` `[patch]` (verification-gap) payment plan/cycle never round-tripped through Postgres — integration test added.
  - `[medium]` `[patch]` (verification-gap other) AdminRevenueServiceIntegrationTest context lacks PlanCatalog — import added, by-plan case added.
  - `[low]` `[reject]` (intent) AC3 « paiement initié garde son prix » has no surface here — no payment initiation exists before 21.1; the quote port is delivered and the frozen price is routed to 21-1 (intent's own reference « (21.1, 21.2) »).
  - `[false]` `[reject]` (intent) modules/limits/quotas not edited by the new CRUD — they are edited by the existing /admin/plan-profiles endpoint (16.8/19.7b/19.8), documented in the dashboard section; nothing is read-only.
  - `[low]` `[reject]` (intent) hidden plans stay attributable (narrow reading of « archivé ou masqué ») — decision recorded in Design Notes; the item's action is « refuser un plan archivé ».
  - `[false]` `[reject]` (intent) archived refusal on /admin/subscriptions/{schema}/activate not tested here — ActivatePlanService.validate refuses archived plans since 19.7b with its own tests (ActivatePlanServiceTest); unchanged by this diff.
  - `[low]` `[reject]` (intent) kind guard only through the new API — the other PlanCatalogStore.save callers are seeds/tests and the one-shot 19.7b job; no other super-admin path changes a kind.

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

## Auto Run Result

- **Summary:** the whole commercial offer is data edited by the super-admin. V60 adds billing cycles (Mensuel 1, Trimestriel 3, Annuel 12), prices per plan × cycle seeded with Bref's grid and a typed price history (« Données de départ »), discounts (dated promotion, company discount, promo code; percent or amount; plans/cycles; best single discount, no stacking), an append-only change journal, `plan_settings.trial_enabled` and the plan/cycle of recorded payments (set-based backfill of the plan). New `subscription.offer` slice (ports `OfferAdministration`, `OfferPricing` in `subscription.api`; pure `OfferPricer`); super-admin REST `/api/v1/admin/offers/**`; owner `GET /api/v1/subscription/offers`; `GET /api/v1/admin/revenue/by-plan`; optional `cycleCode` on the paid-plan activation, plan/cycle labels on the payment list. 19.7a items: kind of a used plan frozen (409), archived plan refused for attribution (pinned), revenue per plan × cycle.
- **Commit:** `14c773c35e9d327725c1e6611ff5423735cfd2f0` (code), spec/status commit after it.
- **Files:** `db/migration/V60__offer_catalogue.sql`; `subscription/api/{OfferAdministration,OfferPricing}`; `subscription/offer/**` (domain model + `OfferPricer`, `OfferStore`/`JdbcOfferStore`, `OfferCatalogService`, `OfferPricingService`, `OfferText`, `OffersController`); `admin/offer/**` (`AdminOfferController`, request DTOs); `admin/revenue/**` (by-plan view, `PlanCycleRevenue`); `admin/subscription/**` (payment plan/cycle, activation cycle, labels); `PlanSettings`, `JdbcPlanCatalog`, `TenantFactory`, `TenantSchemaProvisioner` (trial switch); `AdminLabels`; `docs/entitlements/endpoints.md`; tests (`OfferPricerTest`, `OfferCatalogServiceTest`, `OfferCataloguePostgresIntegrationTest`, `OfferCatalogueFullContextTest`, revenue/activation/registration additions).
- **Review:** 39 findings (5 layers incl. FUS-NFR11) — patched 26 rows (medium 13, low 13) in ~18 fix groups: insert-only plan creation (no overwrite through a stale cache), journal keyset on (occurred_at, id) + index, 409 on kind change of a priced plan and on a months change of a used cycle, plan-row lock for the first price, stored updatedAt, admin reads cycles through the `subscription.api` port, shared French label fallbacks, unknown plan kind null, empty cycleCode accepted, bounded discount list, per-plan price read, plain tenant index on discounts, cycle history index, precomputed sort ranks, revenue IT context, 6 test gaps (cycles, hidden plan, price delete, discount update/archive, Postgres payment round trip, new 409s). Rejected 13 rows with reasons in the triage log (no deferral).
- **Follow-up review recommended: true** — 13 medium entries were patched after the review and not re-reviewed; the named risk is the concurrency of the catalogue writes (insert-only plan creation + catalogue cache invalidation after commit, plan-row lock around the first price) and the journal keyset cursor.
- **Verification:** clean compile OK; tests by package, all green — subscription 143, architecture 131, admin 688 (implementation agent, after the review patches), shared 1 320, identity 866 (re-run here after the patches); 0 failure, 0 error. Matrix audit: every I/O row has a passing test (`OfferCataloguePostgresIntegrationTest.seed/rerunIsNoOp/backfill`, `OfferPricerTest`, `OfferCatalogServiceTest`, `OfferCatalogueFullContextTest` priceChangeVisibleAtOnce/archivedPlan/hiddenPlan/kindChangeOfUsedPlan/promoCode/tenantDiscountBeatsPromotion/trialOff/cycles/deletePrice/updateAndArchiveDiscount, `AdminPlanServiceTest.activate_archivedPremium_refused`, `AdminRevenueServiceTest.byPlan`, `TenantFactoryTest.create_trialOff_startsOnFreePlan`). Live keevo_dev (WhatsApp noop, FCM off): V60 applied (9 prices, 9 history rows, 3 legacy payments backfilled with their plan); admin catalogue shows the grid, companies per plan and settings; new owner KV-JMYV4N: Essai Business, offers list FREE/PRO/TEAM/BUSINESS with per-month prices (PRO Trimestriel 3 167, Annuel 2 730); PRO Mensuel set to 4 000 + promotion 10 % on PRO → offers at once 4 000 → 3 600, 9 500 → 8 550, 32 760 → 29 484, unknown promo code → « Code promo invalide ou expiré »; super-admin on offers → 403; kind change of TEAM → 409; price on FREE → 422; trial off → new company KV-OCCNSI on Gratuit with no end date; journal and price history show French lines with « Super admin » / « Données de départ »; settings, price and promotion restored. kv_11b5zm untouched. Backend stopped with `fuser -k 4500/tcp`.
- **Routed:** frozen price at initiation, promo-code use counter, plan/cycle written at payment confirmation → 21-1-payments-mobile-money; App Store price gap shown to the super-admin → 21-2-storekit-revenuecat; super-admin rate-limit bucket (shared « public » bucket, reflection reset in tests) → 16-7-go-live-canary-runbook.
- **Residual risks:** `/admin/revenue/by-plan` is empty until payments carry a plan in the range (legacy cycle = « Durée libre »); hidden plans stay attributable by the super-admin (decision); the trial switch has two states only (no « first company of an owner »).

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée, commit `14c773c3` (branche `refonte/modulaire`, poussée aussi sur `develop`). Clé de suivi : `d21-10-catalogue-offres-configurable`.

**Ce qui est fait côté API**
- Toute l'offre est en base et modifiable : plans (libellé, description, nature, visibilité, ordre, archivage), durées de facturation, prix par plan et par durée (FCFA) avec historique, réglages de l'essai, remises (promotion datée, remise d'une entreprise, code promo).
- Chaque modification est validée, inscrite au journal de l'offre (phrase en français, auteur, date) et visible tout de suite par l'app.
- Les plafonds, modules et quotas d'un plan restent sur l'écran « Profils & plafonds » existant (`GET/PUT /api/v1/admin/plan-profiles/{plan}`, voir d16-8 / d19-7b / d19-8). Aucun écran de l'offre n'est en lecture seule.
- Revenus ventilés par plan et par durée.

**À utiliser** (toutes les routes : super-admin, réponse `{ success, data }`, erreurs `{ error, domainCode, details }` ; code dans `keevo/backend/src/main/java/com/keevo/admin/offer/adapter/in/rest/AdminOfferController.java`)
- `GET /api/v1/admin/offers` → `{ plans[], cycles[], settings }`. Plan : `code, label, description, kind (FREE|TRIAL|PAID), kindLabel, sortOrder, visible, archived, archivedAt, tenantCount, prices[{cycleCode, cycleLabel, months, amountXaf, updatedAt}]`. Durée : `code, label, months, sortOrder, archived, archivedAt`. Réglages : `freePlanCode/Label, trialPlanCode/Label, trialMonths, trialEnabled, defaultPaidPlanCode/Label`.
- Plans : `POST /admin/offers/plans {code, label, description?, kind, visible?, copyProfileFrom?}` (plafonds, modules et quotas copiés du plan indiqué, par défaut le plan gratuit) ; `PUT /admin/offers/plans/{code} {label?, description?, kind?, visible?}` ; `POST /admin/offers/plans/{code}/archive` et `/unarchive` ; `PUT /admin/offers/plans/order {codes:[…]}`.
- Durées : `POST /admin/offers/cycles {code, label, months (1–36), sortOrder?}` ; `PUT /admin/offers/cycles/{code} {label?, months?, sortOrder?}` ; `POST …/cycles/{code}/archive` et `/unarchive`.
- Prix : `PUT /admin/offers/prices/{planCode}/{cycleCode} {amountXaf}` ; `DELETE` sur la même route (le plan n'est plus vendu sur cette durée) ; `GET /admin/offers/prices/history?planCode=&cycleCode=&limit=` → `[{planLabel, cycleLabel, oldAmountXaf, newAmountXaf, changedByLabel, changedAt}]` (`oldAmountXaf` null = création, `newAmountXaf` null = suppression, auteur « Données de départ » pour la grille initiale).
- Réglages : `PUT /admin/offers/settings {freePlanCode, trialPlanCode, trialMonths (1–24), trialEnabled, defaultPaidPlanCode}`.
- Remises : `GET /admin/offers/discounts?includeArchived=false` ; `POST /admin/offers/discounts {scope: PROMOTION|TENANT|PROMO_CODE, label, percentOff | amountOffXaf, planCodes[], cycleCodes[], startsAt?, endsAt (obligatoire pour une promotion), tenantId (TENANT), promoCode (PROMO_CODE)}` ; `PUT /admin/offers/discounts/{id}` ; `POST /admin/offers/discounts/{id}/archive`. Vue : `scopeLabel, label, percentOff, amountOffXaf, planLabels, cycleLabels, startsAt, endsAt, tenantName, promoCode, live, archived`.
- Journal : `GET /admin/offers/changes?limit=&before=&beforeId=` → `[{id, entityLabel, summary, actorLabel, occurredAt}]` ; page suivante : `before` = `occurredAt` et `beforeId` = `id` de la dernière ligne.
- Aperçu de prix : `GET /admin/offers/quote?planCode=&cycleCode=&tenantId=&promoCode=` → `{planLabel, cycleLabel, months, baseXaf, reductionXaf, effectiveXaf, perMonthXaf, discount}`.
- Revenus : `GET /api/v1/admin/revenue/by-plan?from=&to=` → `[{planLabel, cycleLabel, mrr, payingTenants, revenueCollected, paymentsCollected}]` (« Plan non précisé » / « Durée libre » pour les paiements anciens).
- Activation d'un plan payant : `POST /api/v1/admin/tenants/{id}/plan/premium` accepte `cycleCode` (facultatif, une durée en vigueur). La liste des paiements (`GET /admin/tenants/{id}/payments`) ajoute `planCode, planLabel, cycleCode, cycleLabel`.
- Erreurs (message `error` à afficher tel quel) :
  - 422 `VALIDATION_ERROR` avec `details.<champ>`, par exemple « Seuls les plans payants ont un prix », « Le montant doit être compris entre 1 et 100 000 000 FCFA » ;
  - 404 `NOT_FOUND` : « Plan introuvable », « Durée introuvable », « Remise introuvable », « Prix introuvable » ;
  - 409 `INVALID_STATE` : « Ce code existe déjà », « Ce plan est attribué à des entreprises : sa nature ne peut plus changer », « Ce plan est utilisé par les réglages de l'offre : changez d'abord les réglages », « Ce plan a des prix : supprimez-les avant de changer sa nature », « Cette durée est utilisée : créez une nouvelle durée ».

**À implémenter côté dashboard**
1. Un écran « Offre » qui crée, modifie, masque, réordonne, archive et désarchive les plans. Il affiche les libellés et la nature en français, ainsi que le nombre d'entreprises sur chaque plan, et propose un lien vers « Profils & plafonds » pour les plafonds, modules et quotas.
2. Une grille des prix plan × durée, modifiable cellule par cellule (montant ou suppression), avec l'historique d'une cellule.
3. La gestion des durées de facturation.
4. Un bloc « Essai » : plan d'essai, durée en mois, essai activé ou non, plan gratuit et plan payant par défaut.
5. Une liste des remises (en cours, à venir, archivées) avec un formulaire par type. Pour la remise d'une entreprise, la recherche se fait par nom (jamais par identifiant affiché).
6. Le journal de l'offre, paginé.
7. Un tableau des revenus par plan et par durée.
8. Le champ « Durée » dans « Activer une offre ».
9. Rien de technique à l'écran : on affiche les libellés, jamais les codes ni les identifiants.

**Critères d'acceptation dashboard**
- Given la grille de départ, when on ouvre « Offre », then Pro, Team et Business affichent 3 500 / 9 500 / 32 760, 5 500 / 15 000 / 51 480 et 8 500 / 23 000 / 79 560 FCFA.
- Given un prix modifié, when on ouvre l'historique de la cellule, then on voit l'ancien et le nouveau prix, l'auteur et la date.
- Given une nature changée sur un plan utilisé, when on enregistre, then le message 409 s'affiche et rien ne change.
- Setup curl : connexion super-admin (`POST /auth/login` puis `/auth/select-tenant` KV-ADMIN), puis `curl -H "Authorization: Bearer $T" $API/admin/offers`.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée, commit `14c773c3` (branche `refonte/modulaire`, poussée aussi sur `develop`). Clé de suivi : `m21-10-catalogue-offres-configurable`. Conventions : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API**
- « Mon plan » peut lire les offres en vigueur : plans proposés, plafonds, modules, quotas et prix de chaque durée. Chaque prix porte le prix de base, le prix remisé, le prix par mois et la remise appliquée (libellé, pourcentage ou montant, date de fin).
- La meilleure remise s'applique, sans cumul : promotion en cours, remise de l'entreprise ou code promo saisi.
- Les changements du super-admin sont visibles au prochain appel, sans mise à jour de l'app.
- Rien ne change pour les apps V1 2.0.1 : `/subscription/me` est inchangé.

**À utiliser**
- `GET /api/v1/subscription/offers?promoCode=XXX` (propriétaire ou membre d'une boutique ; le super-admin reçoit 403). Code : `keevo/backend/src/main/java/com/keevo/subscription/offer/adapter/in/rest/OffersController.java`, vues dans `subscription/api/OfferPricing.java`. Réponse `data` :
  - `currentPlan {code, label, kind}` (`kind` vaut null pour un plan inconnu) ;
  - `promoCode {code, valid, message}`, ou null sans code. Pour un code inconnu ou expiré : `valid=false`, `message` = « Code promo invalide ou expiré » ;
  - `plans[]` dans l'ordre d'affichage : `{code, label, description, kind (FREE|PAID), current, maxStores, maxProducts, maxEmployees, maxEmployeesPerStore (null = illimité), modules[{id, moduleId, displayName}], quotas[{metricLabel, periodLabel, limit}], prices[]}` ;
  - `prices[]` contient `{cycleCode, cycleLabel, months, baseXaf, effectiveXaf, perMonthXaf, discount}`, avec `discount` = `{scopeLabel, label, percentOff, amountOffXaf, endsAt}` ou null. Le plan gratuit n'a pas de prix.
- Erreurs : 401 (session), 403 « Vous n'avez pas les droits nécessaires ».

**À implémenter côté mobile**
1. Pour chaque plan payant de « Mon plan », afficher un sélecteur de durée (libellés `cycleLabel`). Le prix affiché est `effectiveXaf`. Quand `effectiveXaf < baseXaf`, afficher `baseXaf` barré, le libellé de la remise et sa fin (« jusqu'au JJ/MM/AAAA », `endsAt` en heure du Cameroun, borne exclue : afficher la veille de `endsAt` si l'heure locale est 00:00).
2. Afficher le prix par mois (`perMonthXaf`) et l'économie par rapport au prix mensuel.
3. Marquer le plan courant (`current`). Pour un essai, afficher `currentPlan.label`.
4. Proposer un champ « Code promo » qui relance l'appel avec `promoCode`, et afficher le `message` quand `valid=false`.
5. Hors ligne : afficher la dernière réponse mise en cache avec la mention « Prix au JJ/MM » et ne rien proposer à l'achat. Rafraîchir à chaque ouverture de « Mon plan ».
6. Formatter les montants en FCFA avec espaces. Aucune donnée technique à l'écran : pas de code de plan, de durée ou de remise.
7. Le paiement lui-même arrive avec 21.1 (Mobile Money) et 21.2 (App Store). Le prix facturé sera celui du devis à l'initiation.

**Implémentation de référence :** aucune dans `keevo/app` (client de test Flutter non modifié).

**Critères d'acceptation mobile / scénarios de test**
- Given un propriétaire d'une entreprise en essai, when il ouvre « Mon plan », then il voit Gratuit, Pro, Team et Business, et Pro Trimestriel à 9 500 FCFA (3 167 FCFA/mois).
- Given une promotion de 10 % sur Pro créée par le super-admin, when il rouvre « Mon plan », then Pro Mensuel affiche 3 500 barré, 3 150 FCFA, le libellé et la date de fin de la promotion.
- Given un code promo inconnu, when il le saisit, then « Code promo invalide ou expiré » s'affiche et les prix restent ceux sans code.
- Setup curl : `POST /auth/register {phoneNumber, password}`, `POST /auth/login`, `POST /auth/select-tenant`, puis `curl -H "Authorization: Bearer $T" "$API/subscription/offers?promoCode=TEST"`. Côté super-admin : `POST /admin/offers/discounts {"scope":"PROMOTION","label":"Lancement","percentOff":10,"planCodes":["PRO"],"endsAt":"2026-12-31T23:00:00Z"}`.
