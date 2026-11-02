# Epic 16 Context: Licences, Migration & Go-Live (Phase 6)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make modular commercialisation real and ship the refonte safely: per-tenant module licences with real-time enforcement, à-la-carte modules plus bundles, configurable plan profiles and limits, a super-admin console, migration of existing tenants onto `tenant_modules`, short-lived tokens with long sliding sessions, employee store confidentiality, an API ready for the new React Native and dashboard clients, GDPR account deletion, and a canary go-live with a runbook. Most stories are done. What remains: 16.6 (GDPR check, next), 16.7 (canary, after the 17.9 E2E gate), 16.12 (tenant DDL under Flyway, after go-live) and 16.9 (DGI e-invoicing, waiting for the specs).

## Stories

- Story 16.1: Licence lifecycle (hybrid trial, read-only grace, real time) — done
- Story 16.2: À-la-carte + bundles — done
- Story 16.3: Active modules in the app without a JWT claim + offline gating — done
- Story 16.4: Super-admin "Modules" console — done
- Story 16.5: Existing-tenant migration (`tenant_modules` backfill) — done
- Story 16.6: GDPR — account and data deletion (FR91) — **next**
- Story 16.7: Progressive go-live (canary) + runbook
- Story 16.8: Configurable plan profiles and limits — done
- Story 16.9: DGI electronic invoicing — on hold
- Story 16.10: Employee store scope on transfer history — done
- Story 16.11: Long, safe sessions — done
- Story 16.12: Convert remaining tenant DDL to module migrations
- Story 16.13: Employee confidentiality of other stores' data (sync + device purge) — done
- Story 16.14: API ready for the React Native and dashboard clients — done

## Requirements & Constraints

**Story 16.6 (next) — acceptance criteria to meet exactly:**
- Given an account deletion request, when it is processed, then the tenant's data is purged with `DROP SCHEMA kv_xxx` (schema-per-tenant, clean and complete), the `public` identity is anonymised or deleted, and the request leaves an audit trace.
- Given a module that is only **deactivated** (no account deletion), when time passes, then its data is **never** purged (kept for life) and reappears when the module is reactivated.
- Only a GDPR account deletion purges. There is no 90-day purge and no purge job for `DISABLED` modules: their tables stay in the tenant schema indefinitely.

**16.6 status in the tracker:** the FR91 core already shipped in V1 (v1s-14-5, hardened by v1s-15-9 and v1s-15-11) and is present on `refonte/modulaire`. The tracker scope is to verify the refonte ACs, fill any gaps, then close. Delivered behaviour to verify, not rebuild:
- OWNER-only `POST /api/v1/account/delete` puts the tenant in `DELETION_PENDING` with a 30-day grace period (`AccountDeletionPolicy.GRACE_PERIOD`), publishes an audited event and sends a French WhatsApp confirmation. A second request returns 409 `DELETION_ALREADY_REQUESTED`.
- OWNER-only `POST /api/v1/account/delete/cancel` sets the tenant back to `ACTIVE` and publishes an audited event. It returns 409 `DELETION_NOT_PENDING` when nothing is pending, and sends no WhatsApp.
- Writes are refused with 403 `ACCOUNT_DELETION_PENDING` during the grace period. Since 16.11 this is read from the database on every request, not from the token.
- A daily scheduler (and the super-admin force delete through `deleteNow`) runs in this order: row lock, `DROP SCHEMA … CASCADE` (fails loudly, `IF EXISTS`, idempotent), delete the tenant's memberships, delete only the `public.users` left orphaned (a user who still belongs to another tenant must survive), delete refresh tokens and orphans' device tokens, mark the tenant `DELETED` last, then send the final WhatsApp after commit. If the purge fails, the tenant stays `DELETION_PENDING` and the next run retries.
- Points to check against the refonte: the refonte added `public` tables keyed by tenant or user (`tenant_modules` and its history, plan-profile rows, `admin_jobs`, per-device sessions and sync scope from 16.11/16.13, subscriptions, feedback, audit). Decide for each one: purge, anonymise or keep with a reason. The audit trace of the request must survive the purge. Module-disable paths (lifecycle, expiry job, cascade/forced deactivation) must never drop data.
- An EMPLOYEE cannot delete the account. The app tells them to ask the owner.

**Epic-wide constraints:**
- Module data is kept for life. Reactivation brings everything back. The Core is never auto-blocked (only the Free plan limits apply to it).
- Enforcement is real time (`expires_at < now` on every request). Crons only materialise states and send notifications, under `@SchedulerLock`.
- Every API change must stay compatible with the V1 2.0.1 apps: additive only, no change to an existing response shape.
- All user-facing text, audit details, exports and error messages are in French, with no UUIDs or raw codes. Every `ErrorCode` needs a French message (guard test).
- Planned before go-live (in 16.7): a backfill dry run on a prod copy, a forced minimum app version, `keevo.jwt.legacy-claims=false` in the same release, and go-live config secrets.

## Technical Decisions

- PostgreSQL 16 with one schema per tenant (`kv_[a-z0-9]{6}`). Flyway runs on `public` and, per module, in the tenant schema (`flyway_history_<id>`). `TenantSchemaSyncService` stays a shim until 16.12.
- Licences: `tenant_modules` rows with `origin` (`SYSTEM`/`PLAN`/`ADMIN`). Plan profiles live in `plan_profiles`/`profile_modules`. Bundles are lists of module IDs applied by one activation plan.
- Tokens carry identity only (`sub`, `tenantId`, `sid`, `iat`, `exp`). Role, store, account status and the password-change flag are read server-side (`AccessStateService`, cache of 30 s or less, invalidated through `LISTEN/NOTIFY keevo_access_state`). A status change such as `DELETION_PENDING` therefore applies on the next request.
- Long admin operations run as persisted async jobs (`public.admin_jobs`, 202 + `jobId`, `GET /admin/jobs/{id}`).
- Hexagonal modules with Spring Modulith events. Audit goes through `AuditEventListener`. Super-admin actions write an `[ADMIN AUDIT]` log.
- Tests run in chunks (one package at a time, never the full suite). The local backend runs with WhatsApp in noop mode and FCM off.

## UX & Interaction Patterns

- The project is backend only: the mobile app (React Native, Bref repo) and the dashboard are built by other developers. Each spec carries "Mobile" and "Dashboard" sections, and mobile work is tracked under separate `m<story>` keys in sprint-status.
- The scheduled-deletion banner (date, days left, "Annuler la suppression") and the confirmation by phone number already exist in V1. The API must keep giving clients the status and the scheduled date.

## Cross-Story Dependencies

- 16.6 relies on 16.11 (state read from the database) and must account for the tables added by 16.4, 16.5, 16.8, 16.11 and 16.13. Story 10-8 (ops) also cleans up leftover legacy `DELETED` tenants that still hold a schema or memberships (runbook §16).
- Planned go-live order: 16.6 and 10-8, then 17.9 (staging E2E gate), then 16.7 canary. 16.12 comes after go-live. 16.9 does not block go-live.
