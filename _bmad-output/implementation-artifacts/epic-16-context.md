# Epic 16 Context: Licences, Migration & Go-Live (Phase 6)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make modular commercialisation real and ship the refonte to production: per-tenant module lifecycle (trial, read-only grace, real-time enforcement), à-la-carte modules and bundles ("packs"), super-admin-configurable plan profiles and limits, a super-admin console, migration of existing tenants to `tenant_modules`, session hardening, employee store confidentiality, an API ready for the new mobile client, GDPR verification, and a canary go-live. The go-live is **backend-only**: this repo delivers the API (`keevo/backend`) and the Next.js super-admin dashboard; the production mobile app becomes a React Native (Expo) app built by another developer in the `AI/bref` repo, while the V1 2.0.1 Flutter apps already installed keep running against the new backend until the RN app forces its minimum version.

## Stories

- Story 16.1: Licence lifecycle (hybrid trial, read-only grace, real time) — done
- Story 16.2: À-la-carte + bundles — done
- Story 16.3: Active modules in the app without a JWT claim — done
- Story 16.4: Super-admin console « Modules » — done
- Story 16.5: Backfill `tenant_modules` for existing tenants — done
- Story 16.6: GDPR account/data deletion (verify refonte ACs)
- Story 16.7: Progressive go-live (canary) + runbook
- Story 16.8: Configurable plan profiles and limits — done
- Story 16.9: DGI electronic invoicing (waiting for DGI specs)
- Story 16.10: Employee store scope on transfer history — done
- Story 16.11: Long and safe sessions — done
- Story 16.12: Convert remaining tenant DDL to module migrations
- Story 16.13: Employee confidentiality of other stores' data (sync + device purge)
- Story 16.14: API ready for the React Native mobile client (additive fixes)

## Requirements & Constraints

- **Delivery scope**: backend + super-admin dashboard only. `keevo/app` (Flutter) is a test client for basic checks on the Redmi, not a deliverable; no Flutter feature work is shipped. Mobile parts of remaining stories (e.g. 16.13 device purge) are API here + handoff.
- **Every spec ends with a « ## Mobile (React Native — dépôt AI/bref) » section**: what the API does, which endpoints/fields/error codes to use, mobile requirements and mobile test scenarios. Each story gets a separate mobile tracking key `m<story-key>` in the MOBILE block of `sprint-status.yaml` (backlog → in-progress → review → done after we test the delivered RN app). `m16-11` carries the API conventions and `m16-10` the sync protocol.
- **V1 2.0.1 compatibility is mandatory**: any API change must be additive — no existing response shape, error code or sync reason may change in a way that breaks installed V1 apps. Known untouchable inconsistencies: two success shapes (`/auth/*` raw vs `{data}`) and two pagination shapes (`meta` vs `data.content`). `keevo.jwt.legacy-claims=true` stays on (indicative V1 claims, never read by the server) until the RN app forces its minimum version.
- Lifecycle per tenant module: `AVAILABLE → TRIAL → ACTIVE → EXPIRED (7-day read-only grace) → DISABLED (hidden, data kept forever)`. Enforcement is real time from `expires_at`/`grace_until` at each request; the nightly job only materialises states and sends notifications. Core is never auto-blocked. Hybrid trial: 6 months global at signup, 14 days per reactivated module.
- Data of disabled modules is never purged; only a GDPR account deletion purges (drop of the tenant schema + anonymisation of public identity, audited).
- Plan limits and profile composition (Free default 1 store / 500 products / 2 employees) are read from the database at every check, editable without redeploy; a lowered limit only refuses new creations.
- Activation is super-admin only. Bundles are just module-ID lists applied through the same activation engine.
- Security before go-live: employee sees only its store's transfers/movements/sales (server pull and device), rights read server-side on every request, revocation effective at the next request.
- Errors exposed to clients must carry French messages; no technical data (UUIDs, raw codes) in UI, exports or audit detail.
- Go-live gate: E2E regression on staging (17.9, which also checks V1 2.0.1 compatibility), backfill dry run on a prod copy, ops runbooks executed (10-8), then canary on ~10 % of tenants with documented rollback. Never deploy without an explicit request.

## Technical Decisions

- Module state lives in `public.tenant_modules` (with `origin`: `PLAN`/`SYSTEM`/`ADMIN`; plan sync and backfill never touch `SYSTEM`/`ADMIN` rows). Plan-derived rows are kept in step by every subscription writer.
- `plan_profiles` + `profile_modules` are the source of truth for limits/composition; the manifest `free` flag is the only free/paid switch; `PlanType` constants are only a fallback.
- Long-running admin operations (backfill, provision-all, profile re-sync) run as persisted async jobs (`public.admin_jobs`, 202 + `jobId`, progress and report).
- No module list in the JWT: clients read `GET /api/v1/tenants/me/modules` (full catalogue with per-module state) and keep the last answer offline; backend `@RequiresEntitlement` remains the defence.
- Tokens: access 30 min carrying only `sub`, `tenantId`, `sid`, `iat`, `exp` (+ legacy V1 claims behind the flag); refresh 365 days sliding, rotated, replay revokes the device chain; super-admin session 12 h. Access state (role, store, statuses, password-change flag, session) loaded server-side per request, cached ≤ 30 s and invalidated cluster-wide via PostgreSQL `LISTEN/NOTIFY`. Sessions are per device (« Appareils connectés »).
- Sync scope for employees must be passed through the pull command (like `ownerOnly`), not read from the security context inside providers.
- `TenantSchemaSyncService` stays as a shim until 16.12 (after go-live); per-module Flyway lives under `db/modules/<id>` with `flyway_history_<id>`.
- Strict layering: no direct imports between bounded contexts (ArchUnit/Modulith blocking).

## UX & Interaction Patterns

- Super-admin console (Next.js): Modules in the tenant drawer (status/expiry, dependency and cascade feedback, forced deactivation after hook failure), pages Modules, Packs, Profils & plafonds; French labels, 422 reasons shown as returned.
- Mobile behaviour (read-only banner with grace date, plan-limit sheet using server limits, silent token renewal, connected devices) is specified for the RN app in each spec's Mobile section, not built here.

## Cross-Story Dependencies

- Execution order: 16.13 → 16.14 → epic 17 (17.10 → 17.11 → 17.12 → 17.8 → 17.7 → 17.2) → 16.6 → 10-8 (ops) → 17.9 (go-live gate) → 16.7 (canary) → 16.12 (after go-live); 16.9 waits for DGI specs.
- 16.14 updates the Mobile sections of 16.1, 16.8, 16.10, 16.11 specs (French messages for auth-filter errors and every `ErrorCode`, French `VALIDATION_ERROR`, grace date to employees, limit/usage on `PLAN_LIMIT_EXCEEDED` sync rejections).
- 16.7 carries open go-live items: legacy claims off with forced minimum app version, backfill dry run on prod copy, invoice-link secret/public URL/logo volume config, 415 mapping, French `NOT_FOUND` messages, stray global-table copies decision, and the gate that module enforcement codes (`MODULE_INACTIVE`/`MODULE_READ_ONLY`/`MODULE_NOT_ENTITLED`) must not hurt V1 clients.
- Before disabling or letting `pos`/`crm` expire in prod, held-operation gaps (inventory sessions, stock ops, day summaries) remain open.
- Epic 18–25 (Keevo × Bref fusion) starts only once the refonte is in production.
