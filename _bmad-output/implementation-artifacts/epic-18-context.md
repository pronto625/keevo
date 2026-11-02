# Epic 18 Context: Cadrage de la fusion Keevo × Bref (P0)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Decide the Keevo × Bref Point merger on facts rather than assumptions. This epic ships no product code: it measures both production databases, writes down every open product decision as an ADR, freezes the functional scope of Bref, secures the app-store identities under which each user base will receive the unified app, and produces the fusion brief/PRD. Its output is the G0 gate: no P1 story (epics 19–25) starts until the numbers are known, the decisions are written, and the Keevo refonte (epic 16) is in production or dated. Duration target: two weeks.

## Stories

- Story 18.1: Measure the Bref and Keevo databases (Q1–Q9, Q13)
- Story 18.2: Settle the open product decisions (gate P0)
- Story 18.3: Freeze the functional scope of Bref
- Story 18.4: Secure app identities and store accounts
- Story 18.5: Fusion product brief and PRD

## Requirements & Constraints

- **Measurement report (18.1)** must be dated and contain aggregates only: owners total / active < 90 days / paying by plan and payment method (Apple, MyCoolPay, admin); row volumes per table (shops, invoices by type, items, payments, customers, products, expenses, members); owners and members without a phone; intersection of E.164-normalised phones between Bref and Keevo; shops outside CM, VAT ≠ 19.25 %, non-integer amounts; remaining account credits and SMS packs; invoices whose stored total differs from a recalculation; owners with `plan ≠ free` but no subscription in force; the date `develop` reached production on api-bref (`develop` reached `main` on 2026-10-26, a3faeb2; the server-side status rules of b957867 are among the later `develop` commits and are not yet deployed; Q7 concerns documents created before that date); the real published Keevo `applicationId`, signing key and available Google/Apple developer accounts.
- **Privacy and safety**: scripts are read-only, run against a replica or locally restored dump, never against prod directly; no personal data (phones, names, emails) in the report, only counts and sums.
- **Decisions (18.2)**: one ADR per open question — unified FREE plan (employee count, documents/month, keep 6-month trial?) and single price grid; final brand (one store listing or two durable brands); country scope (CM-XAF only or CM/GA/TD/CD/SN with their VAT, currency, time zone implications); recently expired Mobile Money subscriptions (30-day grace recommended vs immediate FREE); clients at tenant level with optional store (recommended); early publication of the unified app under `com.brefpoint.app` with legacy mode (recommended). ADRs are appended to the fusion epics document; its frontmatter `status` flips to `ready`. A decision that contradicts a recommendation must list and update the ⚠️-tagged stories before they become ready-for-dev.
- **Freeze (18.3)**: tag `develop` of `AI/bref` and `AI/api-bref` as `pre-fusion-freeze`; only security fixes, blocking fixes and epic-24 migration enablers are allowed; exceptions are logged; any business-rule change shipped on Bref gets a mirror story in epic 20 or 21.
- **Identities (18.4)**: document the Play Store identity and make the upload key available for EAS; if the published app really is `com.example.keevo`, record the keep-or-republish decision and its impact (new listing = reinstall). For iOS, create the Keevo App ID/bundle/certificates on Bref's Apple team or record the decision not to ship Keevo on iOS in P2.
- **Brief/PRD (18.5)**: `prd-fusion-keevo-bref.md` must restate the FUS-FR/MIG/NFR/UX requirements without contradiction, and `architecture.md` gains a "Fusion Bref" section.
- **Golden rule to carry into every ADR**: no user reinstalls, loses data, changes password or pays twice.

## Technical Decisions

- **Locked decisions (D1–D8) frame all measurements**: Keevo Spring Boot is the single backend; one Bref owner = one Keevo tenant (schema `kv_xxxxxx`), shops → stores, members → employees + memberships; identity reconciliation by E.164 phone first, then email; Bref bcrypt hashes reused as-is; Bref plans become Keevo plan profiles/bundles; strangler migration owner by owner with api-bref kept alive for the unmigrated; two store identities from one React Native code base; AI by a tool layer (MCP), not generated SQL.
- **api-bref** (`/home/toor/Project/FreeLance/AI/api-bref`, branch `develop`): NestJS 11 + TypeORM 0.3 + PostgreSQL, single shared schema. Entities live under `src/**/entities` and `src/**/infrastructure/persistence/relational/entities`; migrations under `src/database/migrations`. Table names are singular (`user`, `shop`, `invoice`, `invoice_item`, `invoice_payment`, `customer`, `client`, `product`, `expense`, `subscription`, `usage_counter`, `account_credit`, `refresh_token`, `plan_payment_request`, `apple_iap_transaction`, `sms_recharge_request`). `user` holds owners and members in one table (`role`, nullable unique `phone`, `email`, `username`, `plan`, `emailVerified`); amounts are `decimal(14,2)`; `shop.country` defaults to `'CM'`. There is no last-login column: "active < 90 days" must be proxied (e.g. `refresh_token.createdAt`, latest invoice) and the proxy stated in the report.
- **Plan in force rule**: the authoritative Bref rule is `SUBSCRIPTION_IN_FORCE` in `src/entitlement/entitlement.service.ts` (`status='active'`, started, not ended or within Apple grace, `paymentMethod` not null). Q8 and all plan counts must apply this predicate, never `user.plan`.
- **Keevo backend** (`/home/toor/Project/FreeLance/AI/Keevo/keevo/backend`): Spring Boot + PostgreSQL, Flyway `db/migration` for the `public` schema (V1 baseline + V2–V40), per-module tenant DDL under `db/modules/<id>/`, one schema per tenant. Identity lives in `public.users` (`phone_number` NOT NULL, `password_hash`, `role`), `public.tenants` (`code`, `schema_name`, `plan_type`, `status`), `public.user_tenant_memberships`; `subscriptions` and `tenant_preferences` are per-tenant tables; payments/plan events are `public.tenant_payments` / `tenant_plan_events`. Q4 reads only `public.users.phone_number`; the tenant count feeds FUS-NFR3 (capacity = measured tenants × 2).
- **Phone normalisation for Q4**: apply the `PhoneNumbers.normalize` rule (`com.keevo.shared.domain`) on both sides in SQL — trim, strip spaces/`-`/`.`/`(`/`)`, prefix `+` when missing — before joining; Keevo accepts any E.164, Bref restricts OTP to prefixes 237/241/235/243/221.
- **Measurement artefacts**: versioned read-only SQL under `_bmad-output/planning-artifacts/research/fusion-mesures/` (directory to create), one file per question group, plus the dated aggregate report. Keep the scripts rerunnable so epic 24 can replay them on each wave's export.
- **Known identity facts to verify in 18.4**: Keevo Flutter repo declares `applicationId`/`namespace` `com.example.keevo` (`keevo/app/android/app/build.gradle.kts`); Bref declares bundle/package `com.brefpoint.app`, Apple team `N9534K29PS`, EAS project `bb0b6dbe-c342-43e4-95cc-2902d7bd4d45` (`bref/app.json`). Keevo prod deploys run from the `origin2` (pronto625/keevo) GHCR images.
- **Sprint tracking**: fusion stories use the `epic-18` block in `sprint-status.yaml`; no story file for epics 19+ may be created before G0.

## Cross-Story Dependencies

- 18.1 feeds 18.2 (decisions rest on the numbers) and 18.4 (Q9 identity facts); 18.2 feeds 18.5 (ADRs are PRD inputs) and unblocks epic 19 (Q10/Q12) and epic 24 (Q1–Q8 sizing, wave order, collision wave).
- 18.3 must be pronounced before any epic 20/21 story starts, otherwise the target keeps moving; it also conditions R12 (solo load).
- Risks owned here: R1 tenant volume, R2 identity collisions, R3 inconsistent invoices (via Q7/Q13), R11 Keevo app identity (18.4), R12 via the freeze.
- External prerequisite for G0: epic 16 (refonte in production) done or dated; 17.12 (`products.track_stock`) is already delivered and no longer an open question.
