# Epic 19 Context: Socle d'accueil — identité, rôles, plans, quotas (P1a)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the Keevo backend able to welcome a Bref user exactly as they are today — possibly without a phone, signing in by OTP, Google/Apple or username, holding several roles across several companies, on a plan that is pure data with monthly quotas — without breaking anything for installed Keevo V1 2.0.1 Flutter apps. This is the first build phase of the Keevo × Bref merge (gate G0 opened 03/11/2026); epics 20, 21, 22–24 all build on it. Golden rule of the merge: no user reinstalls more than the planned once, loses data, changes password or pays twice.

## Stories

- Story 19.1: Extend `public.users` (multiple identifiers, profile)
- Story 19.2: OTP authenticator (SMS, WhatsApp, email)
- Story 19.3: Google and Apple authenticators
- Story 19.4: Username login for members
- Story 19.5: MANAGER and VIEWER roles, per-role permissions
- Story 19.6: One person, several roles in several companies
- Story 19.7: PRO, TEAM, BUSINESS profiles and bundles (plans are data)
- Story 19.8: Usage counters and atomic quotas
- Story 19.9: SMS and email providers, persistent notifications
- Story 19.10: Minimum app versions
- Story 19.11: Handle a large number of tenants (load)
- Story 19.12: Extended stores and default sale-flow preference
- Story 19.13: Interface profile — new modules disabled by default, owner-activatable

## Requirements & Constraints

- **Sign-in methods:** phone + password (existing), 6-digit OTP by SMS/WhatsApp/email (hashed, 5 min TTL, 5 attempts, rate-limited per identifier and IP, response never reveals account existence), Google/Apple `idToken` verified server-side (both app bundles accepted: Keevo and `com.brefpoint.app`), username + password for members. All end in the existing two-step login (`loginToken` → select-tenant, or direct tokens with one tenant). OTP for an unknown identifier creates a tenant-less account.
- **Identity:** an account has at least one of phone (E.164), email, username — phone no longer mandatory. Email and username are unique and case-insensitive. Social login links to an existing account by email **only** if the email is verified and the account is not member-only; Apple may omit the email after first consent.
- **Passwords:** Bref bcrypt hashes (`$2a$`/`$2b$`, cost 12) are accepted as-is, no forced reset. An initial member password is shown once, never returned in clear again.
- **Roles:** OWNER, MANAGER, EMPLOYEE, VIEWER. VIEWER never writes (403 `FORBIDDEN` over HTTP, `REJECTED/FORBIDDEN` over sync). MANAGER = EMPLOYEE + team management of their store + CRM/product read. Adding an existing person as a member adds a membership (no `USER_ALREADY_EXISTS`, password untouched); the tenant picker lists every company with its role.
- **Plans are data (nothing commercial hard-coded):** no closed list of plan codes, labels, prices, limits, quotas or trial lengths in code (outside Flyway seed data and a logged safe fallback). Seeds: FREE, PRO, TEAM, BUSINESS. FREE = 1 store / 1 employee / 300 documents per month / 5 SMS / 100 emails; PRO adds expenses + finance; BUSINESS adds campaigns, templates, WhatsApp inbox. FREE tenants already above 1 employee keep them (acquired right) but cannot add more (`PLAN_LIMIT_EXCEEDED` with limit and usage). Keevo PREMIUM → TEAM at unchanged price until the paid period ends. Every new sign-up gets a 1-month trial of the richest plan, then FREE; running 6-month PREMIUM_TRIAL trials run to term. Super-admin can always grant a plan manually (`admin_grant`, traced in `tenant_plan_events`).
- **Quotas:** monthly per tenant and period for documents, SMS, emails, AI requests; overrun → `QUOTA_EXCEEDED {metric, limit, resetAt}`. Core writes (POS sale, payment) are never quota-blocked. Replayed sync ops (`DUPLICATE`) are not counted twice. Purchased SMS packs are consumed after the monthly quota. Quota counted only on provider success.
- **Stores:** `category` (19 Bref values), `description`, `logo_path`, `niu`, `rccm`, `country` (default CM; CM, GA, TD, CD, SN supported). Logo goes through the file-storage port with size/type checks.
- **Sale flow:** `default_sale_flow ∈ {POS, DOCUMENT}` — POS for existing tenants, DOCUMENT for imported Bref owners, editable by the owner.
- **Minimum app versions:** per app identity and platform; unauthenticated check returns `{updateRequired, updateAvailable, storeUrl, message}`; editable from the super-admin dashboard with audit.
- **Compatibility (blocking):** every `/api/v1` change is additive (nullable columns, new endpoints, new optional fields, new delta fields). Existing responses never return `null` where Flutter V1 expects a value; unknown roles (MANAGER/VIEWER) must not crash the V1 app (treated as read-only EMPLOYEE); PRO/TEAM/BUSINESS appear as Premium to V1 through a configurable mapping to FREE / PREMIUM_TRIAL / PREMIUM. Flutter suite and relevant `curl-tests-story-*.sh` stay green.
- **Load:** measured production = 25 Keevo tenants (+1 Bref owner) → target 52 schemas: boot < 2 min, tenant provisioning < 5 s, one module migration on all schemas < 10 min, backup/restore time measured; if a target is missed, an architecture decision is recorded before epic 24.
- **Data facts that drive priorities:** the only Bref owner has phone + verified email and collides with an active Keevo OWNER (→ same `public.users`, second OWNER membership); the 3 Bref members have a username only (→ 19.4 is mandatory for the first wave). Bref `develop` is in production since 03/11/2026; check the Bref change journal before each story.
- **Quality:** each spec names its design pattern and hot-path complexity — no N+1, set-based SQL, keyset pagination, indexes for every new filter/join/sort (e.g. `usage_counters(tenant_id, metric, period)`), atomic single-statement counters. A violation is a blocking review finding.
- **Repo scope:** backend only. A story that changes what the app sees ends with a French « Mobile (React Native — dépôt AI/bref) » section and gets an `m<story>` key in sprint-status; a super-admin API change gets a « Dashboard super-admin » section and a `d<story>` key. No UUIDs or raw codes in user-facing labels.

## Technical Decisions

- **Schemas:** `public.users` changes (19.1), `usage_counters` (19.8), `app_versions` (19.10), plan catalogue (19.7), OTP challenges / authenticators go through Flyway `db/migration` (latest today: `V40`). Tenant tables (`stores`, `tenant_preferences`, persistent `notifications`, interface-profile state if tenant-side) ship only as idempotent module migrations `db/modules/<id>/V2+` (`core`, `store`, `messaging`…) — never public DDL for tenant data (see `db/modules/README.md`: `IF NOT EXISTS`, guarded constraints `NOT VALID`, no data `UPDATE`/`DELETE`). Migrations must leave existing rows unchanged.
- **Existing anchors (verified in `keevo/backend`):** `Role` (`identity/api`, today OWNER/EMPLOYEE/SUPER_ADMIN); `KeevoModule.employeePermissions()` → generalise to `permissionsFor(Role)`, with `ModulePermissions` validating at boot an explicit declaration per role and module; `EndpointEntitlementCoverageTest` drives the parameterised VIEWER write test; `CreateEmployeeService` holds the `USER_ALREADY_EXISTS` restriction; `PhoneNumbers` normalises every phone (default country = store `country`); `AuthController` + `SelectTenantUseCase` carry the two-step login; `WhatsAppSender` (capability port, failover adapters) is the WhatsApp OTP channel; `CompanyLogoStorage` is the logo-storage precedent.
- **Plans:** `public.plan_profiles` / `profile_modules` (V33) are keyed by `plan_type` with a CHECK on the `PlanType` enum, and `PlanType` itself carries hard-coded limits — 19.7 replaces both with a DB plan code read through `PlanProfile` / `EntitlementService`; `PlanChangeRecorder` records plan events. Super-admin CRUD for the full offer (cycles, prices, promotions) is story 21.10; 19.7 must already let a DB-created plan work for sign-up, limit guards and modules.
- **Quotas:** `UsageQuotaService.assertAndIncrement(tenant, metric, n)` *(new)* = one `UPDATE … SET used = used + n WHERE used + n <= limit RETURNING used`, no read-then-write; proven with a 10-thread Testcontainers test.
- **Messaging:** `SmsSender` / `EmailSender` ports *(new)* in `messaging` with configurable provider order (MboaSMS, Infobip; Resend), failover, logs without secrets, fail-fast on missing secrets in prod only when the channel is enabled. Persistent notifications: `GET /notifications` (keyset-paginated, unread count), `PATCH /notifications/{id}/read`, plus FCM push when a token exists.
- **Entitlement vs owner activation (19.13):** a module is usable only if the plan includes it **and** the owner has it activated. Owner deactivation reuses `ModuleState.DISABLED` with a new owner origin (today `tenant_modules.origin ∈ {SYSTEM, PLAN, ADMIN}`, V32), never purges data, and must stop scheduled jobs, listeners and notifications through the existing module gating (`ModuleScopedListenerInterceptor`, `ActiveModulesCache`) and refuse writes like an inactive module. `GET /api/v1/tenants/me/modules` (`TenantModulesController`) additively returns the interface profile: preset `CAISSE` | `DOCUMENT` (from `default_sale_flow`) and, per plan module, activated / disabled-by-owner. Initialisation for existing Keevo tenants is an idempotent Flyway migration activating only V1 modules; initial activation rules for new tenants are data, editable from the super-admin dashboard.
- **Load (19.11):** core tenant DDL is already under per-module Flyway and `TenantSchemaSyncService` is already gone (16-12); what remains is the staging load test at 52 schemas and the report/decision.
- **Two-step login, no role in JWT:** the role is read from `/auth/profile` (16.11), so new roles reach clients there.

## UX & Interaction Patterns

- Existing users keep their landmarks: same home, same main tabs in the same order, same labels. Two navigation presets computed from the interface profile and active modules — « Caisse » (Keevo) and « Document » (Bref) — in one app.
- A feature new to a user (stock/POS for an ex-Bref; quotes, expenses, assistant for an ex-Keevo; any later module) is disabled by default even if the plan includes it: no screen, menu, shortcut, job or notification, and no promotional banner. The owner toggles it in *Paramètres › Mes modules*; modules outside the plan belong to *Mon plan*. Employees additionally see only what their role allows.
- Mobile note to carry into specs: Bref production already shows « Modules », Stock, Inventaire, Fournisseurs, Achats « bientôt disponible » entries to existing users — contrary to this rule; flag it in the Mobile section.

## Cross-Story Dependencies

- **Upstream:** epic 16 on the branch (16-2 bundles, 16-3 active modules for the app, 16-5 backfill, 16-8 configurable profiles, 16-11 sessions, 16-12 tenant DDL under module Flyway) and story 18.2 (ADR-01..09).
- **Inside the epic:** 19.1 first (nullable phone/password, email/username) → 19.2, 19.3, 19.4, 19.6; 19.9 provides SMS/email channels to 19.2 and is gated by 19.8 quotas; 19.5 roles before 19.6 picker roles and 19.13 role filtering; 19.12 `default_sale_flow` feeds 19.13's preset; 19.7 plan data feeds 19.8 limits and 19.13 plan-included modules.
- **Downstream:** epic 20 needs 19.1, 19.5, 19.7, 19.8; epic 21 (payments, credit, AI quotas, 21.10 offer CRUD) builds on 19.7/19.8; 22.3/23.1 (RN auth screens), 22.8 (entitlements + interface profile route guards), 22.9 (« Mes modules ») consume these APIs; 24.2/24.7 (import, identity bridge) rely on 19.1/19.6/19.12/19.13; 19.10 serves Flutter retirement (22.14–22.16); 19.11's verdict gates epic 24.
