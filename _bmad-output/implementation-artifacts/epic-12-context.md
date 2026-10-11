# Epic 12 Context: Stabilisation V1 — Sécurité & Hardening (Piste A, release-blocker)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->
<!-- Scope: V1-stabilization (v1s-12-*) epic, NOT the refonte-modulaire epic 12 (bounded contexts) nor V1 epic files. -->

## Goal

Close the security findings from the 2026-07-20 audit (S1–S9, S12, B-HIGH-5/6/9/10) and the residual RBAC gaps found by the 2026-10-11 alignment audit on the production V1 line, so the multi-tenant isolation guarantee and owner/employee role boundaries hold at every enforcement seam: JWT signing key, session revocation, admin path guard, tenant-schema validation, REST and offline-sync RBAC, client secret handling, auth/session edge cases and prod secrets. Every story is tag A: an urgent V1 patch shipped on the V1 line, which the modular refonte later re-applies (cherry-pick or native re-implementation).

## Stories

- Story 12.1: Fix prod JWT private key permissions (done)
- Story 12.2: Session revocation in under 5 min (done)
- Story 12.3: SUPER_ADMIN enforcement at path level (done)
- Story 12.4: Next.js dashboard route guard (done, obsolete under Next 16 `proxy.ts`)
- Story 12.5: Validate tenantId in JwtAuthFilter SQL (done)
- Story 12.6: Tighten owner-only RBAC + employee store scope (done)
- Story 12.7: Flutter hardening: secure storage, Bearer logs, HTTPS (done)
- Story 12.8: RBAC on `/api/v1/sync/push` handlers (done; tracker-only, no epics heading)
- Story 12.9: Transfer completion scoped to destination store (done; tracker-only)
- Story 12.10: Residual sync RBAC (PROMOTE_PRODUCT, TOGGLE_CATEGORY, inventory session) (in progress)
- Story 12.11: Transfers: an employee transfers only from their own store (REST + sync) (done)
- Story 12.12: Auth & session hardening (JWT filter, revocation cache, password reset, sync device ownership) (backlog)
- Story 12.13: Prod secrets & config hardening (ProdSecretsValidator extension) (backlog)

## Requirements & Constraints

- **Tenant isolation must be 100%.** No cross-tenant data leak, including through raw JDBC or malformed JWT claims.
- **An employee is limited to operations in their assigned store (FR36).** This applies on both channels, REST and offline sync. An OWNER stays unscoped across stores.
- **Owner-only governance.** Store management, day-closure reads, pending-sale cancel and catalog promote/category delete/toggle are OWNER-only. The equivalent sync operations must apply the same rule.
- **Transfer rule (PO decision 2026-10-11).** An EMPLOYEE may initiate a transfer only when `sourceStoreId` equals their JWT-assigned store. Otherwise REST returns 403 and the sync op is REJECTED. OWNER keeps cross-store transfers. Shipped by 12.11, which reverted commit 4302eba's "any store" opening; it supersedes the earlier "transfer initiation OWNER-only" wording. Do not reopen it. Transfer receipt (`complete()`) stays scoped to the destination store (12.9) and is REST-only; no sync handler exists for it.
- **Session revocation.** A deactivated employee or a password change must invalidate access within 5 min, even though access tokens are long-lived. Revoked tokens get 401 `SESSION_REVOKED`. A remote session revoke by the owner is supported (FR68).
- **Secrets.** Passwords use bcrypt with cost ≥12. The prod private key must not be world-readable. Prod must fail fast on any weak or defaulted secret (DB password, provider tokens, admin password).
- **Client secrets.** Tokens are stored encrypted on every platform. Authorization headers never reach release logs. HTTPS is the default.
- **Tests.** Each RBAC change ships tests for: EMPLOYEE own store (allowed), EMPLOYEE other store (403/REJECTED, use case never invoked) and OWNER (unchanged).
- **Deferred items.** Stories 12.10–12.13 carry routed deferred-work items. Each item must be resolved, or rejected with a reason recorded beside it (box ticked, commit or decision noted). deferred-work stays empty.

## Technical Decisions

- **Layered enforcement.** Spring Security filter chain (`/api/v1/admin/**` → `hasRole('SUPER_ADMIN')`), then method-level `@PreAuthorize`, then handler/service scope checks. The layers are kept redundant on purpose.
- **Where the actor comes from.** Role and assigned store always come from the authenticated actor (JWT → `AuthDetails` via `Authentication.getDetails()`), never from the request payload.
  - REST controllers use `extractAssignedStoreId()` / `isOwnerRole()`. A missing storeId claim for an EMPLOYEE fails closed.
- **Sync push.** Sync push runs synchronously on the HTTP request thread, behind the same filter chain, so `SecurityContextHolder` is available inside handlers.
  - Reuse the shared `protected extractAssignedStoreId()` / `isOwnerRole()` on `AbstractSyncOperationHandler`. Do not change `SyncOperation`, `SyncOperationHandler` or the `handle` signature.
  - Throw `DomainException(ErrorCode.FORBIDDEN, …)` before calling the use case. It surfaces as a REJECTED op.
  - Null auth must fail closed (12.10 item).
- **Placement of scope logic.** Use cases (`TransferStockUseCase`, `AdjustStockUseCase`, …) have no role awareness. The scope check lives in the controller and is mirrored in the sync handler. Sale validate/cancel is the exception: the service already enforces scope and only needs the real `assignedStoreId`.
- **Tenant schema validation.** Validate the tenant id with `TenantSchema.validate` (`^kv_[a-z0-9]{6}$`) before any raw tenant-qualified SQL. An invalid id returns 401 `TOKEN_INVALID`.
- **Revocation mechanism.**
  - `tokens_valid_after` on `user_tenant_memberships`, behind `TokenRevocationPort` with Jdbc and cached (≤5 min) adapters. The filter rejects `iat < tokens_valid_after`.
  - EMPLOYEE status is also checked per request (defense-in-depth).
  - 12.12 hardens the cache (Caffeine bounds, atomic load, injected Clock) and adds a clock-skew policy.
- **Error contract.**
  - The `ErrorCode` enum is mapped by `GlobalExceptionHandler`: `SESSION_REVOKED` → 401, `FORBIDDEN` → 403.
  - The access-denied handler must return a JSON body, not servlet HTML (12.12).
- **Flutter secure storage.** One centralized `SecureStorageProvider` (encryptedSharedPreferences on Android). `AuthInterceptor` handles `SESSION_REVOKED` / `ACCOUNT_INACTIVE` by expiring the session and redirecting to login.
- **Mobile parity.** Backend RBAC changes carry a Flutter counterpart in the same story: hide owner-only FABs/actions and keep defensive UI conditions. Example: the "Réceptionner" button visibility in the transfer history.
- **Branches.** Work targets the V1 line (`deploy` → `develop` fast-forward). The refonte absorbs each fix later, per its "Refonte absorption" note.

## UX & Interaction Patterns

- EMPLOYEE UI hides owner-only actions: transfer initiation outside their store, day closure, employee management, dashboard, profitability.
- When stock is only available in another store, an EMPLOYEE sees a "Contacter le propriétaire" CTA instead of a transfer action.
- On a revoked or suspended session, the app clears tokens and redirects to login with the SnackBar "Votre accès a été suspendu…".
- Sync rejections must be explicit to the user. Feedback on rejects is handled in Epic 16.

## Cross-Story Dependencies

- **12.11 (done)** restored the transfer-source rule on REST (`StockTransferController`) and sync (`TransferSyncHandler`) using the 12.6/12.8 helpers, without touching 12.9's destination-scope check on `complete()`. Its two routed items are resolved (sync EMPLOYEE source = JWT store; tenant membership already enforced by the transfer service's store lookup; `_shouldShowReceiveButton` widget tests added). Later stories must not regress it.
- **12.10** extends the same sync-handler pattern to `ProductSyncHandler`, `CategorySyncHandler` and `InventorySessionSyncHandler`.
  - It passes the real actor role into `UpdateProductDto` instead of the `"OWNER"` default.
  - It also makes null-auth fail closed in `AbstractSyncOperationHandler` (today null auth is treated as OWNER). That change affects every sync handler, including 12.11's transfer handler; re-run their tests.
  - TOGGLE_CATEGORY sync must become OWNER-only, matching the REST delete/toggle.
- **12.12** follows up on reviews of 12.2, 12.3, 12.5, 13.2, 14.11 and 14.12.
  - It touches `JwtAuthFilter`, `CachedTokenRevocationAdapter`, password reset, `SyncController` device ownership and `SecurityConfig`.
  - One item needs a PO decision: whether cancelling a deletion should restore SUSPENDED.
- **12.13** extends Story 10.3's `ProdSecretsValidator` and relates to 12.1.
- Client-side session revocation spans 12.2, 12.7 and 14.9 (suspension banners).
- Outside this epic: access-token lifetime (60 days configured vs 24 h specified) is an open audit finding with no story yet. A future roles & privileges module is noted but not planned.
