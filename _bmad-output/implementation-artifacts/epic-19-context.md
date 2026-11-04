# Epic 19 Context: Socle d'accueil — identité, rôles, plans, quotas (P1a)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the Keevo Spring Boot backend able to welcome a Bref Point user exactly as they are today: an account with no phone (email or username only), sign-in by OTP, Google, Apple or username + password, membership roles beyond OWNER/EMPLOYEE, one person in several companies, plans that are database data (FREE/PRO/TEAM/BUSINESS), atomic monthly usage quotas, Bref's messaging channels (SMS, email, push, WhatsApp) with persisted notifications, minimum app versions, extended stores with a default sale path, and an interface profile that hides modules new to the user. This is the foundation of the Keevo × Bref merge: epics 20, 21 and 24 build on it. Throughout, the Flutter V1 app in production must keep working without an update.

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
- Story 19.9: SMS and email providers, persisted notifications, Expo push — done
- Story 19.9b: Bref channels — platform WhatsApp via Meta Cloud API, Web Push
- Story 19.10: Minimum app versions
- Story 19.11: Handle a large number of tenants (R1)
- Story 19.12: Extended stores and sale-path preference
- Story 19.13: Interface profile — new modules disabled, can be enabled in settings

## Requirements & Constraints

- **Strictly additive API:** nullable columns, new endpoints, new delta fields only. Flutter tests and the relevant `curl-tests-story-*.sh` stay green. DTOs never return `null` where Flutter V1 expects a value (omit or `""`, documented). Unknown roles/plans must not crash V1 (V1 still sees FREE / PREMIUM_TRIAL / PREMIUM through a configurable mapping).
- **Sign-in:** phone + password, OTP (SMS/WhatsApp/email), Google, Apple, username + password. Every flow ends in the existing two-step login (`loginToken` → `select-tenant`, or tokens directly with one tenant). An account needs at least one of E.164 phone, email, username; email and username are unique, case-insensitive, looked up via `lower(...)` on the `uq_users_*_lower` indexes.
- **OTP:** 6 digits, hashed, 5 min, max 5 attempts counted in DB, rate-limited per identifier and IP, never reveals whether the account exists; unknown account → created without tenant. Phones outside CM, GA, TD, CD, SN → `PHONE_COUNTRY_NOT_SUPPORTED`.
- **Social:** server-side token verification; lookup by `(provider, social_id)`; auto-link by email only if the email is verified and the account is not member-only; Apple may omit email after first consent; accept audiences of both bundles (Keevo and `com.brefpoint.app`).
- **Bref passwords/members:** bcrypt `$2a/$2b` (cost 12) reused without forced rehash. Temporary member password returned once only. Usernames globally unique and may be all digits — login must disambiguate from a phone typed without “+”.
- **Roles:** OWNER, MANAGER, EMPLOYEE, VIEWER (Bref admin→MANAGER, seller→EMPLOYEE, viewer→VIEWER). VIEWER never writes: 403 `FORBIDDEN` (HTTP), `REJECTED/FORBIDDEN` (sync). MANAGER = EMPLOYEE + team of own store + CRM/product read. Adding an existing user as member adds a membership (no `USER_ALREADY_EXISTS`, password unchanged, user notified).
- **Offer is data:** no plan code, label, price, limit, quota or trial length in code (only Flyway seeds and a logged safe fallback). FREE = 1 employee with acquired rights (above-limit tenants keep employees, adding more → `PLAN_LIMIT_EXCEEDED` with limit and usage).
- **Quotas:** documents above the FREE ceiling, SMS, email, AI are counted; core writes (POS sale, payment) never blocked. Overrun → `QUOTA_EXCEEDED {metric, limit, resetAt}`. Replayed sync (`DUPLICATE`) never counted twice. SMS packs consumed after the monthly quota.
- **Messaging:** configurable provider fallback order; no secrets in logs; quota charged only after provider success, in a short transaction, never around the HTTP call; fail-fast on missing secrets only for enabled channels.
- **Load (19.11):** with N schemas (measured tenants × 2): boot < 2 min, tenant provisioning < 5 s, one module migration across all schemas < 10 min; a missed target requires an architecture decision before epic 24.
- **Patterns & complexity (blocking in review):** each spec names its pattern and hot-path complexity; no N+1, set-based SQL, keyset pagination, an index per new filter/sort, batch jobs, single-statement atomic counters.
- **No technical data in UI:** French labels, no UUIDs/raw codes. Backend only: each spec has a mobile section and, when it exposes super-admin API, a dashboard section, tracked as `m19-*` / `d19-*` keys.

## Technical Decisions

- **Messaging providers — Bref's prevail (user decision 2026-11-04):** for notifications and WhatsApp, use Bref's providers: Expo push, Meta WhatsApp Cloud API with Bref's system templates (`bref_point_otp`, `bref_facture`), MboaSMS/Infobip for SMS, Resend/Infobip for email. Wassender/Twilio are fallback only (story 19.9b), kept while Keevo's free-text WhatsApp sends (daily report, alerts, subscription reminders) lack an approved Meta template. 19.9b also adds Expo receipt processing (batch job, set-based removal of dead tokens) and Web Push (VAPID; subscriptions per user, removed on 404/410) behind the push composite from 19.9.
- **Identity:** `public.users` extended in 19.1 (nullable phone/password, email, username, names, `*_verified_at`, at-least-one-identifier CHECK). New global tables in `public` (`user_authenticators` or columns, `otp_challenges`, `usage_counters`, `app_versions`). Phones via `PhoneNumbers.normalize` (E.164). OTP ports normalization and failure counting from `api-bref/src/otp/*`; channels use the `messaging` ports (`SmsSender`, `EmailSender`, WhatsApp sender).
- **Permissions:** generalise `KeevoModule.employeePermissions()` to `permissionsFor(Role)`; every manifest declares every role, validated at boot by `ModulePermissions`; parameterised VIEWER test over all `EndpointEntitlementCoverageTest` endpoints. Roles come from `/auth/profile`, not the JWT.
- **Plans:** plan code in DB catalogue (label, order, visibility, archive, V1 compatibility code, kind such as trial); `plan_profiles`/`profile_modules` keyed by code; trial carried by `BUSINESS_TRIAL`; changes traced in `tenant_plan_events`; `admin_grant` kept.
- **Usage counters:** `public.usage_counters(tenant_id, metric, period, used)`; increment = one `UPDATE … WHERE used + n <= limit RETURNING used`, never read-then-write.
- **Notifications:** per-tenant `notifications` inbox; `GET /notifications` paginated with unread count, `PATCH /notifications/{id}/read`; push on notifiable events. Retention purge and removal of a departed user's rows belong to 19.11's multi-tenant job.
- **App versions:** `public.app_versions(app_identity, platform, min_version, latest_version, store_url, message)`; unauthenticated `GET /api/v1/app-version/check` → `{updateRequired, updateAvailable, storeUrl, message}`; dashboard edits audited.
- **Stores:** add `category` (19 Bref values), `description`, `logo_path` (file-storage port, size/type checked), `niu`, `rccm`, `country` (default CM), all in the sync delta. `tenant_preferences.default_sale_flow ∈ {POS, DOCUMENT}`: POS for existing tenants, DOCUMENT for imported Bref owners.
- **Interface profile:** entitlement (plan includes module) and owner activation are distinct; usable only when both. Owner deactivation reuses `DISABLED` with an owner origin, never purges data. `GET /tenants/me/modules` adds the navigation preset (`CAISSE`/`DOCUMENT`) and per-module activated/disabled state. A disabled module runs no jobs/notifications and its writes are refused. Initial activation rules for new tenants are DB data; the Flyway backfill is idempotent (V1 modules on, new ones off).
- **Load:** core tenant DDL moves under per-module Flyway; `TenantSchemaSyncService` leaves the boot path.

## UX & Interaction Patterns

- Users keep their landmarks: ex-Keevo lands on Caisse, ex-Bref on Document. Features new to a user stay invisible until the owner enables them in *Paramètres › Mes modules* (no promotional banners); modules outside the plan belong to *Mon plan*; employees see only what their role allows.

## Cross-Story Dependencies

- Builds on epic 16 work on the branch (16-2 bundles, 16-3 active modules, 16-5, 16-8 configurable profiles, 16-11 role out of the token) and on the P0 ADRs (18.2).
- 19.2 uses 19.9 channels (and 19.9b for Meta WhatsApp). 19.4 and 19.6 rely on 19.1 lookups. 19.13 depends on 19.12 (`default_sale_flow`) and 19.7; granting `invoicing` to FREE is 19.13's job (disabled for existing tenants; the sale → invoice bridge must not fire unless enabled).
- Routed elsewhere: SMS packs and credit → 21.3; super-admin offer editing → 21.10; per-tenant WhatsApp Business (WABA, inbox, campaigns) → 21.5.
- Epic 20 needs 19.1, 19.5, 19.7, 19.8. Migration import (24.2) creates the interface profile and DOCUMENT preference; 19.11 load results gate epic 24. RN screens (22.3, 23.x) consume these endpoints.
