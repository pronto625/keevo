# Epic 19 Context: Socle d'accueil — identité, rôles, plans, quotas (P1a)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the Keevo Spring Boot backend able to welcome a Bref Point user exactly as they are today: an account with no phone (email or username only), sign-in by OTP, Google, Apple or username + password, membership roles beyond OWNER/EMPLOYEE, one person in several companies, plans that are database data (FREE/PRO/TEAM/BUSINESS), atomic monthly usage quotas, Bref's messaging channels (SMS, email, push, WhatsApp) with persisted notifications, minimum app versions, extended stores with a default sale path, and an interface profile that hides modules new to the user. This is the foundation of the Keevo × Bref merge: epics 20, 21 and 24 build on it. Throughout, the Flutter V1 app in production must keep working without an update.

## Stories

- Story 19.1: Extend `public.users` (multiple identifiers, profile) — done
- Story 19.2: OTP authenticator (SMS, WhatsApp, email) — done
- Story 19.3: Google and Apple authenticators — done
- Story 19.3b: Social accounts — nonce, link/unlink, Apple revocation
- Story 19.4: Username login for members — done
- Story 19.5: MANAGER and VIEWER roles, permissions per role — done
- Story 19.5b: Fine-grained rights of manager and viewer (product decisions)
- Story 19.6: One person, several roles in several companies — done
- Story 19.6b: Adding an existing account — consent, privacy, recovery (product decisions)
- Story 19.7a: Plans as data — done
- Story 19.7b: Merge plans (FREE/PRO/TEAM/BUSINESS, PREMIUM → TEAM, 1-month Business trial) — done
- Story 19.8: Usage counters and atomic quotas — done
- Story 19.9: SMS and email providers, persisted notifications, Expo push — done
- Story 19.9b: Bref channels — platform WhatsApp via Meta Cloud API, Web Push — done
- Story 19.9c: Meta templates for Keevo's WhatsApp messages
- Story 19.10: Minimum app versions
- Story 19.11: Handle a large number of tenants (R1)
- Story 19.12: Extended stores and sale-path preference
- Story 19.13: Interface profile — new modules disabled, can be enabled in settings

## Requirements & Constraints

- **Strictly additive API:** nullable columns, new endpoints, new delta fields only. Flutter tests and relevant `curl-tests-story-*.sh` stay green. DTOs never return `null` where Flutter V1 expects a value (omit or `""`, documented). Unknown roles/plans must not crash V1 (V1 sees FREE / PREMIUM_TRIAL / PREMIUM through a configurable mapping; unknown role treated as EMPLOYEE read).
- **Sign-in:** phone + password, OTP, Google, Apple, username + password; every flow ends in the two-step login (`loginToken` → `select-tenant`, or tokens directly with one tenant). An account has at least one of E.164 phone, email, username (unique, case-insensitive, `lower(...)` lookups). Phones outside CM, GA, TD, CD, SN → `PHONE_COUNTRY_NOT_SUPPORTED`.
- **Social accounts (19.3b):** backend verifies the `nonce` claim against the clear nonce sent by the app (replay protection; app side in 23.1); authenticated endpoints to link, list and unlink Google/Apple (today a 409 `EMAIL_ALREADY_EXISTS` has no way out); account deletion revokes the Sign in with Apple token (App Store requirement, needs the Apple private key in config) and removes `user_authenticators` links. Auto-link by email only if verified and the account is not member-only, judged on memberships.
- **Roles:** OWNER, MANAGER, EMPLOYEE, VIEWER. VIEWER never writes: 403 `FORBIDDEN` (HTTP), `REJECTED/FORBIDDEN` (sync). MANAGER is never less restricted than an employee by default.
- **Fine rights (19.5b) — awaits product decisions:** whether a manager sees all sales/history/inventory sessions of their store and can cancel a seller's session; what a viewer sees (POST reads such as export requests/previews are currently refused; sales history is empty); manager access to sellers' device sessions and reactivation of members disabled by the owner; whether MANAGER/VIEWER count in the plan's employee ceiling. Implement exactly what is decided, tested per role.
- **Existing account added as member (19.6b) — awaits product decisions:** immediate attach (current) vs invitation accepted by the person (today any owner can probe whether a phone/username has an account, and each attempt sends a message); privacy of the shared account's other identifiers shown to the owner; password recovery for a shared member with only a username (owner can no longer reset it).
- **Offer is data:** no plan code, label, price, limit, quota or trial length in code (only seeds). FREE = 1 employee with acquired rights; adding beyond → `PLAN_LIMIT_EXCEEDED` with limit and usage.
- **Quotas:** documents above the FREE ceiling, SMS, email, AI are counted; core writes never blocked; `QUOTA_EXCEEDED {metric, limit, resetAt}`; replayed sync never counted twice.
- **Messaging:** configurable provider fallback; no secrets in logs; quota charged only after provider success, in a short transaction outside the HTTP call; fail-fast on missing secrets only for enabled channels. Keevo free-text WhatsApp messages (daily/weekly report, subscription reminders and changes, stock and sync-silence alerts, account deletion) must each get an approved Meta UTILITY template or move to push + inbox (19.9c; list and wording validated by the user, then submitted to Meta).
- **Minimum versions (19.10):** a VIEWER (or MANAGER) on V1 2.0.1 sees a seller UI and loses offline sales rejected at sync — enforce a minimum version for these roles, or refuse their V1 login with a clear French message.
- **Load (19.11):** with N schemas (measured tenants × 2): boot < 2 min, tenant provisioning < 5 s, one module migration on all schemas < 10 min; a missed target needs an architecture decision before epic 24. Includes notification inbox retention: set-based per-tenant purge (e.g. read > 90 days) and removal of a departed user's rows.
- **Patterns & complexity (blocking in review):** each spec names its pattern and hot-path complexity; no N+1, set-based SQL, keyset pagination, indexes for new filters, batch jobs, single-statement atomic counters.
- **No technical data in UI:** French labels, no UUIDs/raw codes. Backend only: each spec has a mobile section (and a dashboard section when it exposes super-admin API), tracked as `m19-*` / `d19-*` keys.

## Technical Decisions

- **Messaging providers — Bref's prevail:** Expo push, Meta WhatsApp Cloud API with Bref system templates (`bref_point_otp`), MboaSMS/Infobip SMS, Resend/Infobip email, Web Push (VAPID). Wassender/Twilio are fallback only, through the `WhatsAppProvider` chain from 19.9b. `bref_facture` (sent from the owner's number) belongs to 21.5.
- **Identity:** `public.users` extended (nullable phone/password, email, username, names, `*_verified_at`, at-least-one-identifier CHECK); global tables `user_authenticators`, `otp_challenges`, `usage_counters`, `app_versions`. Usernames prefixed `google-`/`apple-` are reserved for social accounts without email.
- **Roles:** read from the membership (`/auth/profile`), not the JWT nor `users.role`; every module manifest declares `permissionsFor(Role)`, validated at boot.
- **Plans:** plan code in a DB catalogue (with V1 compatibility code and kind, trial carried by `BUSINESS_TRIAL`); changes traced in `tenant_plan_events`.
- **Usage counters:** one `UPDATE … WHERE used + n <= limit RETURNING used`, never read-then-write.
- **App versions:** `public.app_versions(app_identity, platform, min_version, latest_version, store_url, message)`; unauthenticated `GET /api/v1/app-version/check?identity=&platform=&version=` → `{updateRequired, updateAvailable, storeUrl, message}`; dashboard edits audited.
- **Stores:** add `category` (19 Bref values), `description`, `logo_path` (file-storage port, size/type checked), `niu`, `rccm`, `country` (default CM), all in the sync delta. `tenant_preferences.default_sale_flow ∈ {POS, DOCUMENT}`: POS for existing tenants, DOCUMENT for imported Bref owners, owner-editable.
- **Interface profile:** entitlement (plan includes module) and owner activation are distinct; usable only when both. Owner deactivation reuses `DISABLED` with an owner origin, never purges data. `GET /tenants/me/modules` adds the navigation preset (`CAISSE`/`DOCUMENT`) and per-module activated/disabled state; a disabled module runs no jobs or notifications and its writes are refused. Initial activation rules for new tenants are DB data; the Flyway backfill is idempotent (V1 modules on, new ones off). `invoicing` is granted to FREE here, disabled for existing tenants; the sale → invoice bridge must not fire unless enabled.
- **Load:** core tenant DDL moves under per-module Flyway; `TenantSchemaSyncService` leaves the boot path.

## UX & Interaction Patterns

- Users keep their landmarks: ex-Keevo lands on Caisse, ex-Bref on Document. Features new to a user stay invisible until the owner enables them in *Paramètres › Mes modules* (no promotional banners); modules outside the plan belong to *Mon plan*; employees see only what their role allows.

## Cross-Story Dependencies

- Builds on epic 16 work on the branch (16-2, 16-3, 16-5, 16-8, 16-11 role out of the token) and the P0 ADRs (18.2).
- 19.3b nonce verification pairs with the RN sign-in screens (23.1). 19.5b, 19.6b and 19.9c cannot be coded before the user's product decisions (19.9c also needs Meta approval).
- 19.10 closes the V1 MANAGER/VIEWER gap opened by 19.5. 19.13 depends on 19.12 (`default_sale_flow`), 19.7 and 19.8 (documents quota).
- Routed elsewhere: SMS packs and credit → 21.3; super-admin offer editing → 21.10; per-tenant WhatsApp Business and `bref_facture` → 21.5; identity collisions at import → 24.7.
- Epic 20 needs 19.1, 19.5, 19.7, 19.8. Migration import (24.2) creates the interface profile and DOCUMENT preference; 19.11 load results gate epic 24. RN screens (22.3, 23.x) consume these endpoints.
