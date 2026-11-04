# Epic 19 Context: Socle d'accueil — identité, rôles, plans, quotas (P1a)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the Keevo Spring Boot backend able to welcome a Bref Point user exactly as they are today: an account with no phone (email or username only), sign-in by OTP, Google, Apple or username + password, membership roles beyond OWNER/EMPLOYEE, one person in several companies, plans that are database data (FREE/PRO/TEAM/BUSINESS), atomic monthly usage quotas, SMS/email providers with persisted notifications, minimum app versions, extended stores with a default sale path, and an interface profile that hides modules that are new to the user. This is the foundation of the Keevo × Bref merge: epics 20, 21 and 24 build on it. Throughout, the Flutter V1 app in production must keep working without an update.

## Stories

- Story 19.1: Extend `public.users` (multiple identifiers, profile) — done
- Story 19.2: OTP authenticator (SMS, WhatsApp, email)
- Story 19.3: Google and Apple authenticators
- Story 19.4: Username login for members
- Story 19.5: MANAGER and VIEWER roles, permissions per role
- Story 19.6: One person, several roles in several companies
- Story 19.7a: Plans as data — done
- Story 19.7b: Merge plans (FREE/PRO/TEAM/BUSINESS, PREMIUM → TEAM, 1-month Business trial) — done
- Story 19.8: Usage counters and atomic quotas — done
- Story 19.9: SMS and email providers, persisted notifications
- Story 19.10: Minimum app versions
- Story 19.11: Handle a large number of tenants (R1)
- Story 19.12: Extended stores and sale-path preference
- Story 19.13: Interface profile — new modules disabled, can be enabled in settings

## Requirements & Constraints

- **Strictly additive API (backward compatibility):** nullable columns, new endpoints, new delta fields only. The Flutter test suite and the relevant `curl-tests-story-*.sh` stay green. DTOs never return `null` where Flutter V1 expects a value: omit the field or use `""`, and document it. An unknown role or plan must not crash V1 (V1 still sees FREE / PREMIUM_TRIAL / PREMIUM through a configurable mapping).
- **Sign-in:** phone + password (Keevo), OTP by SMS/WhatsApp/email, Google, Apple, or username + password. Every flow ends in the existing two-step login (`loginToken` → `select-tenant`, or tokens directly when there is one tenant). An account needs at least one of: E.164 phone, email, username. Email and username are unique and case-insensitive; look them up through `lower(...)` on the `uq_users_*_lower` indexes.
- **OTP:** 6 digits, stored hashed, valid for 5 min, at most 5 attempts counted in the database, rate-limited per identifier and per IP. The response never reveals whether the account exists. An unknown account is created without a tenant. Phones outside the allowed countries (CM, GA, TD, CD, SN) → `PHONE_COUNTRY_NOT_SUPPORTED`.
- **Social:** the token is verified on the server. Lookup by `(provider, social_id)` first. Auto-link by email only when the email is verified and the matched account is not member-only. Apple may omit the email after the first consent. Accept the audiences of both bundles (Keevo and `com.brefpoint.app`).
- **Bref passwords:** bcrypt `$2a/$2b` hashes (cost 12) are reused as-is, with no forced rehash or reset. A member's temporary password is returned once only. Usernames are unique globally. Usernames may be all digits, so login must tell them apart from a phone typed without “+”.
- **Roles:** OWNER, MANAGER, EMPLOYEE, VIEWER. VIEWER never writes: 403 `FORBIDDEN` over HTTP, `REJECTED/FORBIDDEN` in sync. MANAGER = EMPLOYEE + team management for their own store + CRM/product read.
- **Existing user added as a member:** they get an extra membership (no `USER_ALREADY_EXISTS`, password unchanged, user notified). The tenant picker lists every company with its role.
- **Offer is data (ADR-05):** no plan code, label, price, limit, quota or trial length in code, except Flyway seeds and a logged safe fallback. Seed values: FREE = 1 employee, with acquired rights (existing tenants above the limit keep their employees but cannot add more → `PLAN_LIMIT_EXCEEDED` with limit and usage); 1-month trial of the richest plan; PREMIUM → TEAM at the same price until the paid period ends.
- **Quotas:** documents above the FREE ceiling (300/month), SMS, email and AI are counted; core writes (POS sale, payment) are never blocked. On overrun → `QUOTA_EXCEEDED {metric, limit, resetAt}`. A replayed sync operation (`DUPLICATE`) is never counted twice. Purchased SMS packs are used after the monthly quota.
- **Messaging:** providers have a configurable fallback order. Logs never contain secrets. Quota is charged only after the provider confirms, in a short transaction, never around the HTTP call. Fail-fast on missing secrets applies only to enabled channels.
- **Load (19.11):** with N schemas (measured tenants × 2), boot < 2 min, tenant provisioning < 5 s, one module migration across all schemas < 10 min. If a target is missed, an architecture decision is required before epic 24.
- **Patterns & complexity (blocking in review):** each spec names its design pattern and the complexity of its hot paths. No N+1, set-based SQL, keyset pagination, an index for every new filter or sort, batch operations, single-statement atomic counter updates.
- **No technical data in the UI:** French labels only, no UUIDs or raw codes. Scope is backend only: each spec carries a mobile section and a dashboard section, tracked as separate `m19-*` / `d19-*` keys in sprint-status.

## Technical Decisions

- **Identity:** `public.users` has nullable `phone_number`/`password_hash`, plus `email`, `username`, `first_name`, `last_name`, `email_verified_at`, `phone_verified_at`, and a CHECK requiring at least one identifier (done in 19.1). New global tables live in `public` (`user_authenticators` or columns, `otp_challenges`, `usage_counters`, `app_versions`). Phones always go through `PhoneNumbers.normalize` (E.164), with the store's `country` as the default country.
- **OTP porting:** port the normalization and failure counting from `api-bref/src/otp/*`. The WhatsApp channel reuses `WhatsAppMessageService`; SMS and email come from 19.9 (`SmsSender`/`EmailSender` ports in `messaging`; MboaSMS and Infobip for SMS, Resend for email).
- **Permissions:** generalise `KeevoModule.employeePermissions()` to `permissionsFor(Role)`. Every manifest declares every role explicitly, validated at boot by `ModulePermissions`. A parameterised VIEWER test runs over all endpoints of `EndpointEntitlementCoverageTest`. Roles are no longer in the JWT: they come from `/auth/profile`.
- **Plans:** a plan code in the database (catalogue: label, order, visibility, archive, V1 compatibility code, nature such as trial). `plan_profiles`/`profile_modules` are keyed by plan code; the `BUSINESS_TRIAL` plan carries the trial. Plan changes are traced in `tenant_plan_events`; `admin_grant` stays available to the super-admin.
- **Usage counters:** `public.usage_counters(tenant_id, metric, period, used)`, indexed on `(tenant_id, metric, period)`. The increment is one `UPDATE … SET used = used + n WHERE used + n <= limit RETURNING used`, never read-then-write, and is tested with 10 concurrent threads (Testcontainers).
- **Notifications:** a per-tenant `notifications` table (user, type, title, body, data, `read_at`); `GET /notifications` is paginated with an unread count; `PATCH /notifications/{id}/read`; an FCM push is sent when a token exists.
- **App versions:** `public.app_versions(app_identity, platform, min_version, latest_version, store_url, message)`. `GET /api/v1/app-version/check` is unauthenticated and returns `{updateRequired, updateAvailable, storeUrl, message}`; dashboard edits are audited.
- **Stores:** `category` (19 Bref values), `description`, `logo_path` (file-storage port: disk now, S3 optional; size and type checked), `niu`, `rccm`, `country` (default CM). All are added to the sync delta. `tenant_preferences.default_sale_flow ∈ {POS, DOCUMENT}`: POS for existing tenants, DOCUMENT for imported Bref owners.
- **Interface profile:** entitlement (the plan includes the module) and owner activation are separate notions; a module is usable only when it is both entitled and activated. Owner deactivation reuses `ModuleState.DISABLED` with a new `OWNER` origin (`tenant_modules.origin` today: SYSTEM/PLAN/ADMIN) and never purges data. `GET /tenants/me/modules` adds the navigation preset `CAISSE`/`DOCUMENT` and the activated/disabled state of each module. A disabled module runs no jobs or notifications, and its writes are refused. Initial activation rules for new tenants are database data. The Flyway backfill is idempotent: V1 modules are activated, every module new to the tenant is disabled.
- **Load:** the core tenant DDL moves under per-module Flyway, and `TenantSchemaSyncService` is removed from the boot path.

## UX & Interaction Patterns

- Users keep their landmarks: an ex-Keevo user lands on the Caisse flow and an ex-Bref user on the Document flow. Features that are new to a user stay invisible until the owner turns them on in *Paramètres › Mes modules* (no promotional banners). Modules outside the plan belong to *Mon plan*. Employees see only what their role allows.

## Cross-Story Dependencies

- Builds on epic 16 work already on the branch: 16-2 bundles, 16-3 active modules (`/tenants/me/modules`), 16-5, 16-8 configurable profiles, 16-11 role removed from the token. It also relies on ADR-01 to ADR-09 from 18.2.
- 19.2 depends on 19.9 for SMS and email. 19.4 and 19.6 depend on 19.1 (lookups). 19.13 depends on 19.12 (`default_sale_flow`) and 19.7. Granting `invoicing` to FREE is 19.13's job: the module stays disabled for existing tenants, and the sale → invoice bridge must not fire unless the owner has enabled it.
- Deferred items routed out: SMS/email quota charging after provider success → 19.9; SMS packs and credit → 21.3; super-admin offer editing → 21.10.
- Epic 20 needs 19.1, 19.5, 19.7 and 19.8. The migration import (24.2) creates the interface profile and the DOCUMENT preference. Load results from 19.11 gate epic 24. The RN screens (22.3, 23.1) use these endpoints.
