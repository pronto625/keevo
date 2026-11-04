# Epic 19 Context: Socle d'accueil — identité, rôles, plans, quotas (P1a)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the Keevo backend able to welcome a Bref user exactly as they are today — possibly without a phone, signing in by OTP, Google/Apple or username, holding several roles across several companies, on a plan that is pure data with monthly quotas — without breaking anything for installed Keevo V1 2.0.1 Flutter apps. This is the first build phase of the Keevo × Bref merge (gate G0 opened 03/11/2026); epics 20, 21, 22–24 all build on it, and the refonte only goes to production at the GL milestone, once the whole merge is done. Golden rule of the merge: no user reinstalls more than the planned once, loses data, changes password or pays twice.

## Stories

- Story 19.1: Extend `public.users` (multiple identifiers, profile) — **done**
- Story 19.2: OTP authenticator (SMS, WhatsApp, email)
- Story 19.3: Google and Apple authenticators
- Story 19.4: Username login for members
- Story 19.5: MANAGER and VIEWER roles, per-role permissions
- Story 19.6: One person, several roles in several companies
- Story 19.7a: Plans are data (catalogue, plan kind, trial settings) — **done**
- Story 19.7b: Fusion plans (FREE/PRO/TEAM/BUSINESS, PREMIUM→TEAM, FREE 1 employee, 1-month trial on the subscription)
- Story 19.8: Usage counters and atomic quotas
- Story 19.9: SMS and email providers, persistent notifications
- Story 19.10: Minimum app versions
- Story 19.11: Handle a large number of tenants (load)
- Story 19.12: Extended stores and default sale-flow preference
- Story 19.13: Interface profile — new modules disabled by default, owner-activatable

## Requirements & Constraints

- **Sign-in methods:** phone + password (existing), 6-digit OTP by SMS/WhatsApp/email (hashed, 5 min TTL, 5 attempts, rate-limited per identifier and IP, response never reveals account existence; phones outside allowed countries → `PHONE_COUNTRY_NOT_SUPPORTED`), Google/Apple `idToken` verified server-side (both bundles accepted: Keevo and `com.brefpoint.app`), username + password for members. All end in the existing two-step login (`loginToken` → select-tenant, or direct tokens with one tenant). OTP for an unknown identifier creates a tenant-less account and sets `phone_verified_at`/`email_verified_at`.
- **Identity:** an account has at least one of phone (E.164), email, username. Email and username are unique and case-insensitive; lookups must use `lower(col) = lower(?)` on the existing lower-case unique indexes, and a uniqueness violation on save becomes a business error. Login must disambiguate an all-digit username (allowed by the Bref rule `^[A-Za-z0-9._-]{1,50}$`) from a phone typed without « + ». Social login links to an existing account by email **only** if the email is verified and the account is not member-only; Apple may omit the email after first consent.
- **Passwords:** Bref bcrypt hashes (`$2a$`/`$2b$`, cost 12) accepted as-is. An initial member password is shown once, never returned in clear again.
- **Roles:** OWNER, MANAGER, EMPLOYEE, VIEWER. VIEWER never writes (403 `FORBIDDEN` over HTTP, `REJECTED/FORBIDDEN` over sync). MANAGER = EMPLOYEE + team management of their store + CRM/product read. Adding an existing person as a member adds a membership (no `USER_ALREADY_EXISTS`, password untouched, user notified); the tenant picker lists every company with its role.
- **Plans are data (nothing commercial hard-coded):** no plan codes, labels, prices, limits, quotas or trial lengths in code (outside Flyway seeds and a logged safe fallback). Fusion seeds: FREE, PRO, TEAM, BUSINESS. FREE = 1 store / 1 employee / 300 documents per month / 5 SMS / 100 emails; PRO adds expenses + finance; BUSINESS adds campaigns, templates, WhatsApp inbox. FREE tenants already above 1 employee keep them but cannot add more (`PLAN_LIMIT_EXCEEDED` with limit and usage; member limits per store as in Bref). Keevo PREMIUM → TEAM at unchanged price until the paid period ends (traced plan event). New sign-ups get a 1-month trial of the richest plan, carried by the subscription (trial status), then FREE; running PREMIUM_TRIAL trials run to term. Super-admin can always grant a plan manually (`admin_grant`, traced).
- **Routed to 19.7b:** the admin « Activer Premium » action still assigns the fixed PREMIUM code — the paid plan must become a parameter (it is whitelisted by the plan-literal gate test until then); plan activation must tie `expiresAt` to the plan kind (required for trial/paid, absent for free; 422 with a French message).
- **Routed to 21.10 (not this epic, but do not contradict):** forbid or resync a kind change on a plan in use; refuse archived/hidden plans for new assignments; per-plan/cycle revenue once prices are in base.
- **Quotas:** monthly per tenant and period for documents, SMS, emails, AI requests; overrun → `QUOTA_EXCEEDED {metric, limit, resetAt}`. Core writes (POS sale, payment) are never quota-blocked. Replayed sync ops (`DUPLICATE`) are not counted twice. Purchased SMS packs are consumed after the monthly quota. Messaging quota counted only on provider success.
- **Stores:** `category` (19 Bref values), `description`, `logo_path`, `niu`, `rccm`, `country` (default CM; CM, GA, TD, CD, SN supported), in REST and sync delta. Logo through the file-storage port with size/type checks.
- **Sale flow:** `default_sale_flow ∈ {POS, DOCUMENT}` — POS for existing tenants, DOCUMENT for imported Bref owners, editable by the owner.
- **Minimum app versions:** per app identity and platform; unauthenticated check returns `{updateRequired, updateAvailable, storeUrl, message}`; editable from the super-admin dashboard with audit.
- **Compatibility (blocking):** every `/api/v1` change is additive. Existing responses never return `null` where Flutter V1 expects a value (e.g. `phoneNumber` is `""` for a phone-less account); unknown roles must not crash V1 (read-only EMPLOYEE); V1 sees FREE / PREMIUM_TRIAL / PREMIUM derived from the plan kind (paid → PREMIUM, trial → PREMIUM_TRIAL). Flutter suite and relevant `curl-tests-story-*.sh` stay green.
- **Load:** 25 Keevo tenants (+1 Bref owner) measured → target 52 schemas: boot < 2 min, tenant provisioning < 5 s, one module migration on all schemas < 10 min, backup/restore measured; a missed target yields an architecture decision before epic 24.
- **Data facts:** the only Bref owner has phone + verified email and collides with an active Keevo OWNER (→ same user, second OWNER membership); the 3 Bref members have a username only (→ 19.4 is mandatory for the first wave). Bref keeps evolving (no freeze): check the Bref change journal before each story.
- **Quality:** each spec names its design pattern and hot-path complexity — no N+1, set-based SQL, keyset pagination, indexes for every new filter/join/sort, atomic single-statement counters. A violation is a blocking review finding.
- **Repo scope:** backend only. A story changing what the app sees ends with a French « Mobile (React Native — dépôt AI/bref) » section and an `m<story>` key in sprint-status; a super-admin API change gets a « Dashboard super-admin » section and a `d<story>` key. No UUIDs or raw codes in user-facing labels; French labels only.

## Technical Decisions

- **Migrations:** public changes via Flyway `db/migration` (latest: V41 identifiers, V42 plan catalogue). Tenant tables (`stores`, `tenant_preferences`, persistent `notifications`, interface-profile state if tenant-side) ship only as idempotent module migrations `db/modules/<id>/V2+` — never public DDL for tenant data. Existing rows stay unchanged.
- **Plans (19.7a delivered):** `public.plans` (code, label, description, order, visible, archived_at, **kind ∈ FREE/TRIAL/PAID**) and one-row `plan_settings` (free plan, trial plan, trial months); codes referenced by FKs, no CHECK on codes. `PlanType` is a value type; every behaviour depends on the plan **kind** (SQL joins on `public.plans`), read through the cached `PlanCatalog` port; V1 value derived from kind. A grep gate test forbids plan-code literals in main code. 19.7b adds seeds and data moves only on top of this; the TRIAL kind remains for V1 mapping of running PREMIUM_TRIAL subscriptions.
- **Identity anchors:** `Role` (today OWNER/EMPLOYEE/SUPER_ADMIN); `KeevoModule.employeePermissions()` → `permissionsFor(Role)`, with `ModulePermissions` validating at boot an explicit declaration per role and module; `EndpointEntitlementCoverageTest` drives the parameterised VIEWER write test; `CreateEmployeeService` holds `USER_ALREADY_EXISTS`; `PhoneNumbers` normalises phones (default country = store `country`); `AuthController` + `SelectTenantUseCase` carry the two-step login; role comes from `/auth/profile`, never the JWT. WhatsApp OTP reuses the existing WhatsApp sender port; `CompanyLogoStorage` is the logo-storage precedent.
- **Quotas:** `UsageQuotaService.assertAndIncrement(tenant, metric, n)` *(new)* = one `UPDATE … SET used = used + n WHERE used + n <= limit RETURNING used` on `public.usage_counters(tenant_id, metric, period)`; proven by a 10-thread Testcontainers test.
- **Messaging:** new `SmsSender` / `EmailSender` ports in `messaging` with configurable provider order (MboaSMS, Infobip; Resend), failover, logs without secrets, prod fail-fast on missing secrets only when the channel is enabled. Notifications: `GET /notifications` (paginated, unread count), `PATCH /notifications/{id}/read`, FCM push when a token exists.
- **Entitlement vs owner activation (19.13):** a module is usable only if the plan includes it **and** the owner has activated it. Owner deactivation reuses `ModuleState.DISABLED` with a new owner origin, never purges, stops jobs/listeners/notifications through existing module gating, and refuses writes like an inactive module. `GET /tenants/me/modules` additively returns the preset (`CAISSE` | `DOCUMENT`, from `default_sale_flow`) and per-module activation. Existing Keevo tenants: idempotent migration activating only V1 modules; initial rules for new tenants are data editable by super-admin.
- **Load (19.11):** core tenant DDL is already under per-module Flyway and `TenantSchemaSyncService` is gone (16-12); what remains is the staging load test and report/decision.

## UX & Interaction Patterns

- Existing users keep their landmarks: same home, same main tabs in the same order, same labels. Two navigation presets — « Caisse » (Keevo) and « Document » (Bref) — computed from the interface profile and active modules in one app.
- A feature new to a user is disabled by default even if the plan includes it: no screen, menu, job or notification, no promotional banner. The owner toggles it in *Paramètres › Mes modules*; modules outside the plan belong to *Mon plan*. Employees see only what their role allows.

## Cross-Story Dependencies

- **Upstream:** epic 16 on the branch (16-2, 16-3, 16-5, 16-8, 16-11, 16-12) and 18.2 (ADR-01..09).
- **Inside the epic:** 19.1 (done) → 19.2, 19.3, 19.4, 19.6 (19.2 and 19.4 carry the case-insensitive email/username lookup items deferred from 19.1); 19.7a (done) → 19.7b → 19.8 limits and 19.13 plan-included modules; 19.9 provides SMS/email to 19.2 and is gated by 19.8 quotas; 19.5 roles before 19.6 picker roles and 19.13 role filtering; 19.12 `default_sale_flow` feeds 19.13's preset.
- **Downstream:** epic 20 needs 19.1, 19.5, 19.7, 19.8; epic 21 builds on 19.7/19.8 (21.10 owns the full offer CRUD, prices, discounts and the routed 19.7a items); 22.3/23.1, 22.8, 22.9 consume these APIs; 24.2/24.7 rely on 19.1/19.6/19.12/19.13; 19.10 serves Flutter retirement; 19.11's verdict gates epic 24.
