---
stepsCompleted: ["step-01", "step-02", "step-03", "step-04"]
inputDocuments:
  - _bmad-output/planning-artifacts/prd.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/epics/requirements-inventory.md
  - AUDIT_CONFORMITE_BMAD.md
  - _bmad-output/implementation-artifacts/spec-notif-owner-actions-employe.md
  - _bmad-output/implementation-artifacts/deferred-work.md
  - _bmad-output/implementation-artifacts/sprint-status.yaml
---

# Keevo — Backlog de Stabilisation V1 + Refonte (post-audit 2026-07-20)

> **Objectif stratégique :** corriger **tous les findings** de l'audit `AUDIT_CONFORMITE_BMAD.md` sur la **ligne V1 (pré-refonte)** pour que V1 reste déployable/utilisable en production **pendant** que la **refonte modulaire** se fait derrière, puis faire réabsorber ces corrections par la refonte.
> **Conventions :** chaque story référence les findings (B-*, F-*, X-*, S-*), les FR/NFR/ARCH, les màj doc, et porte un **tag de piste** (A/B/C) + une note **« Refonte absorption »**.
> **Non-cumul :** ces épics s'ajoutent aux Épics 1–11 (refonte existante). Numérotation 12–15 pour éviter les collisions.

---

## 0. Stratégie deux pistes + modèle de branches

### Modèle de branches (vérifié 2026-07-20)
| Branche | Rôle | HEAD | Ligne |
|---|---|---|---|
| **`deploy`** | **V1 (pré-refonte)** — production-utilisable | `d766035` | V1 |
| **`refractoring` / `dev`** | **Refonte modulaire** en cours | `d8d61d0` (« refractoring start », +1 sur `deploy`) | Refonte |
| `v1.0.0` | snapshot V1 plus ancien (story-8.6) | `d1ac71c` | V1 (archive) |
| `staging` | iso-prod staging | `851b722` | V1 |
| `master` | scaffold seul | `f389881` | — |

L'audit a tourné sur `refractoring` ; la refonte venant à peine de commencer, **les 100% des findings s'appliquent à la ligne V1 (`deploy`)**.

### Les deux pistes
- **Piste A — Stabilisation V1** : branche de hotfix issue de `deploy` (ex. `v1-stabilization`), corrections mergées vers `deploy` + `staging`. **But : V1 solide et déployable maintenant, pendant la refonte.** Priorité = release-blockers sécurité + concurrence + cassures runtime.
- **Piste B — Refonte modulaire** : continue sur `refractoring`/`dev`. **Réabsorbe** chaque fix de la piste A (cherry-pick depuis `v1-stabilization` **ou** ré-implémentation native dans la nouvelle structure modulaire — au choix par story, noté dans « Refonte absorption »).

### Règle de tag par story
| Tag | Sens | Cible |
|---|---|---|
| **A** | Patch V1 (urgence, ship sur `deploy` maintenant) | `v1-stabilization` → `deploy` |
| **B** | Refonte-native (mieux fait dans la nouvelle structure modulaire) | `refractoring` |
| **C** | Patch V1 **+** refonte absorbe (V1-shippable maintenant, refonte ré-applique) | les deux |

### Ordre d'exécution
1. **Sprint 1 (bloquant V1)** : Epic 12 (sécurité) + Epic 13 (concurrence) + Story 14.8 (cassures API runtime) + Story 14.10 (notif spec) + **Epic 16** (mobile hardening/parity) — tous tag A.
2. **Sprint 2 (scope V1 promis)** : Stories 14.5/14.9/14.2/14.11/14.12 (RGPD, bannières, leaderboard, édition employé, mot de passe oublié) — tag C.
3. **Sprint 3 (refonte-native)** : Stories 14.1/14.3/14.4 (variantes, revenus, push+santé) + Epic 15.1/15.2/15.4/15.6 — tag B.
4. **Continu** : Epic 15.3/15.5 + màj doc transverses.

> **Parité mobile :** chaque story backend porte une note **« Contrepartie mobile (Flutter) »** quand il y en a une (convention BMAD backend+Flutter dans la même story, ex. Story 2.3). Les findings pure-mobile sans contrepartie backend sont dans l'**Epic 16**.

---

## Vue d'ensemble

| Epic | Titre | Risk class | Release-blocker | Stories |
|---|---|---|---|---|
| **Epic 12** | Stabilisation V1 — Sécurité & Hardening (Piste A) | S1–S7, B-HIGH-5/6/9/10 | OUI (V1) | 11 |
| **Epic 13** | Stabilisation V1 — Concurrence & Intégrité données (Piste A) | B-CRIT-1/2/3, F-CRIT-1/2/3, F-HIGH-4 | OUI (V1) | 12 |
| **Epic 14** | Récupération scope V1 — Gaps fonctionnels (Pistes A/B/C) | FR22/44/55/64/65/66/67/79/82/83/90/91/92, X-* | Partiel | 13 |
| **Epic 15** | Discipline de tests & dette technique (Pistes B/C) | ARCH11–14, dette | Non (hygiène) | 11 |
| **Epic 16** | Mobile Frontend Hardening & Parity (Piste A/C) | frontend F-1..F-6, UX9/15/20, FR27/30 | OUI (V1) | 9 |
| **Epic 18** | Export de données & Audit détaillé (Piste C) — sprint-change-proposal-2026-10-11 | FR55, FR84, FR94–FR98 | Non | 7 |

---

# Epic 12: Stabilisation V1 — Sécurité & Hardening (Piste A, release-blocker)

Fermer S1–S7 sur la ligne `deploy` pour que la garantie multi-tenant (NFR10) tienne au niveau des coutures d'enforcement. **Toutes tag A** (patch V1 urgent).

## Story 12.1: Corriger les permissions de la clé JWT privée de prod — **[A]**

As a Toor, I want the prod RSA private key unreadable to other OS users, so that no local user can forge admin JWTs.

**Refs:** S1, NFR11, ARCH16-17, deferred [10-5]. **Refonte absorption:** trivial cherry-pick (mêmes workflows CI).

**AC:**
**Given** `.github/workflows/deploy-backend.yml:112` does `chmod 644 keys/private_key.pem keys/public_key.pem`
**When** the chmod is split
**Then** `chmod 600 keys/private_key.pem` + `chmod 644 keys/public_key.pem` separately (pattern from `deploy-staging.yml:115`)
**And** a deploy-step assertion verifies the private key is not world-readable

**Doc:** mark deferred `[10-5]` prod-key **resolved**. **TDD:** grep lint test.

## Story 12.2: Révocation de session <5min (Story 11-6) — **[A]**

As a Toor, I want a revoked membership to lose API access within 5 min regardless of the 24h access-token lifetime (NFR12).

**Refs:** S2, B-HIGH-5, NFR12, FR68, Story 11-6. **Refonte absorption:** re-implement natively in `identity/auth` modular (même `TokenRevocationPort`).

**AC:**
**Given** a membership is revoked
**When** an access token issued before revocation hits `JwtAuthFilter`
**Then** `iat < tokens_valid_after` → 401 `SESSION_REVOKED`
**And** `V4__user_tenant_memberships_tokens_valid_after.sql` adds the column
**And** `TokenRevocationPort` + `JdbcTokenRevocationAdapter` + `CachedTokenRevocationAdapter` (≤5min refresh)
**And** `DeactivateEmployeeService` + `ChangePasswordService` call `revokeAllSessions(actorId, tenantId)`
**And** EMPLOYEE per-request DB check remains (defense-in-depth)

**Contrepartie mobile (Flutter):** `AuthInterceptor.onError` ajoute la branche `SESSION_REVOKED`/`ACCOUNT_INACTIVE` → `_expireSession()` (clear tokens + SharedPreferences) + redirect `/auth/login` avec SnackBar « Votre accès a été suspendu… » ; vérifie `passwordChangeRequired` au splash (déjà existant). Ties Story 12.7.

**Doc:** Story 11-6 `ready-for-dev`→`done`; add `SESSION_REVOKED` to `ErrorCode`+`GlobalExceptionHandler`(→401). **TDD:** `shouldRejectTokenIssuedBeforeRevocation()`, `shouldAcceptTokenIssuedAfterRevocation()`, `shouldRevokeAllSessionsOnDeactivation()`, `shouldRevokeAllSessionsOnPasswordChange()`.

## Story 12.3: Enforcement SUPER_ADMIN au niveau chemin — **[A]**

As a Toor, I want `/api/v1/admin/**` to require `SUPER_ADMIN` at the filter-chain level (not only per-method).

**Refs:** S3, Epic 9 Story 9.1, defense-in-depth. **Refonte absorption:** cherry-pick (même `SecurityConfig`).

**AC:**
**Given** `SecurityConfig.java:91-96`
**When** the chain is configured
**Then** `.requestMatchers("/api/v1/admin/**").hasRole("SUPER_ADMIN")` before `.anyRequest().authenticated()`
**And** the 5 admin controllers keep method-level `requireSuperAdmin()`
**And** a `@WebMvcTest` asserts OWNER → 403 on `/api/v1/admin/tenants`

**Doc:** note layered enforcement. **TDD:** `shouldReturn403ForOwnerOnAdminPath()`, `shouldReturn200ForSuperAdminOnAdminPath()`.

## Story 12.4: Garde-fou de route dashboard Next.js — **[A]**

As a Toor, I want unauthenticated users redirected from protected dashboard routes.

**Refs:** S4, Story 9.1. **Refonte absorption:** N/A (dashboard hors refonte modulaire backend).

**AC:**
**Given** `keevo/dashboard/src/proxy.ts` exports `proxy` (dead)
**When** renamed to `src/middleware.ts` exporting `function middleware` (keep `export const config`)
**Then** `/tenants`, `/monitoring/*` without session → `/login`
**And** the 401 interceptor calls `logout()` before redirect (Story 12.7)

**Doc:** Story 9.1 note middleware live. **TDD:** E2E `/tenants` sans session → `/login`.

## Story 12.5: Valider tenantId dans JwtAuthFilter SQL — **[A]**

As a Toor, I want the JWT tenant identifier validated before any raw SQL interpolation.

**Refs:** S5, B-HIGH-6, ARCH18, NFR10. **Refonte absorption:** re-implement natively (validation port dans `shared/infrastructure`).

**AC:**
**Given** `JwtAuthFilter.java:157` interpolates raw `tenantId`
**When** extracted from claims
**Then** `String schema = TenantSchema.validate(tenantId)` (regex `^kv_[a-z0-9]{6}$`) before any `JdbcTemplate` query
**And** invalid → 401 `TOKEN_INVALID`; the validated `schema` is used

**Doc:** ARCH18 — toute raw-JDBC tenant-qualified query doit passer `TenantSchema.validate`. **TDD:** `shouldRejectTokenWithInvalidTenantIdFormat()`, `shouldAcceptValidTenantId()`.

## Story 12.6: Resserer RBAC owner-only + scope store employé — **[A]**

As a Toor, I want EMPLOYEEs restricted to their assigned store and excluded from owner-only governance endpoints (FR36).

**Refs:** S6, S7, B-HIGH-9/10, FR36, AC6/AC9/AC10 (4.3/4.4). **Refonte absorption:** re-appliquer les `@PreAuthorize` + scope JWT dans la structure modulaire.

**AC:**
**Given** `PendingSaleController:43,60`, `DayClosureController:30`, `StockController`, `InventorySessionController`, `StoreController`, `StockTransferController`, `ProductController`
**When** RBAC tightened
**Then**:
- `/sales/pending` + `/sales/{id}/validate` → `hasRole('OWNER')` (AC6/AC9)
- `GET /day-closures` → `hasRole('OWNER')` (AC10)
- `StoreController` (create/list/update/deactivate) → `@PreAuthorize("hasRole('OWNER')")`
- `StockController`/`InventorySessionController` → extract `storeId` from JWT for EMPLOYEE; reject `request.storeId != jwtStoreId` with 403 `FORBIDDEN` (FR36)
- `StockTransferController` initiate → `hasRole('OWNER')` (EMPLOYEE cross-store sheet "Contacter le propriétaire" remains)
- `ProductController` mutating → confirmer owner-only **OU** documenter la décision HF-2 B4 (EMPLOYEE peut créer/éditer catalogue) — **décision PO à confirmer** (voir checklist)

**And** `@WebMvcTest` per controller asserts EMPLOYEE → 403
**And** `app_router.dart:296-300` misleading comment corrected

**Doc:** Epic 3 (FR36) + Epic 4 (AC6/AC9/AC10) + `deferred-work.md` HF-2. **TDD:** `shouldReturn403ForEmployeeOnPendingSaleValidate()`, `shouldReturn403ForEmployeeOnDayClosureRead()`, `shouldReturn403ForEmployeeOnStoreCreate()`, `shouldEnforceEmployeeStoreIdOnStockAdjust()`, `shouldReturn403ForEmployeeOnTransferInitiate()`.

**Contrepartie mobile (Flutter):** UI gating EMPLOYEE — cacher les FABs/actions owner-only (initier transfert, clôture journée, gestion employés, dashboard, rentabilité) ; ajouter `/pos/pending` à `_ownerOnlyPrefixes` + filtrer la liste offline par rôle ; la sheet cross-store dispo reste "Contacter le propriétaire" pour EMPLOYEE (déjà `_EmployeeCta`) ; confirmer la décision catalogue EMPLOYEE (create/edit ?) côté UI aussi.

## Story 12.7: Hardening Flutter — secure storage, logs Bearer, HTTPS — **[A]**

As a Toor, I want client secrets handled securely on all platforms + session-revoke UX.

**Refs:** S8, S9, S12, ARCH21, Story 1.3 AC5, FR68 client (ties 12.2). **Refonte absorption:** re-appliquer dans le Flutter refonte (mêmes `SecureStorageProvider`/`AuthInterceptor`).

**AC:**
**Given** 6 ad-hoc `const FlutterSecureStorage()` + `LogInterceptor(requestHeader:true)` + `http://localhost` default
**When** centralized hardened storage introduced
**Then** `SecureStorageProvider` uses `AndroidOptions(encryptedSharedPreferences:true)` + `IOSOptions(...)`; all call sites reuse it
**And** `LogInterceptor` gated by `kDebugMode` + redacts `Authorization`
**And** default base URL `https://` + reject bad certs unless `kDebugMode && localhost`
**And** `AuthInterceptor.onError` adds `ACCOUNT_INACTIVE`/`SESSION_REVOKED` branch → `_expireSession()` + suspended redirect (FR68 client, ties 12.2)

**Doc:** ARCH21 + Story 8.3. **TDD:** `shouldUseEncryptedSharedPreferencesOnAndroid()`, `shouldNotLogAuthorizationHeaderInRelease()`, `shouldRedirectToLoginOnAccountInactive()`.

## Story 12.10: RBAC sync résiduel (PROMOTE_PRODUCT, TOGGLE_CATEGORY, session d'inventaire) — **[A]**

As a Toor, I want the remaining sync operations (PROMOTE_PRODUCT, TOGGLE_CATEGORY, inventory session) to enforce the same OWNER-only / store-scope rules as their REST counterparts, so that the sync channel cannot bypass the RBAC delivered by 12.6 / 12.8 (FR36, audit I1 2026-10-11).

**Scope:** (sprint-status.yaml, 2026-10-11 audit I1) PROMOTE_PRODUCT, TOGGLE_CATEGORY and inventory-session sync operations are not limited to the employee's assigned store; align the sync handlers with the REST RBAC (OWNER-only / store scope) delivered by v1s-12-6 / v1s-12-8. Tracker key: `v1s-12-10-sync-rbac-residual`.

**Refs:** S6/S7, FR36, Story 12.6, Story 12.8, audit-coherence-2026-10-11 I1. **Refonte absorption:** re-appliquer dans les handlers sync du module `sync` (12.7 refonte).

**AC:**
**Given** an EMPLOYEE pushing PROMOTE_PRODUCT / TOGGLE_CATEGORY / inventory-session operations **When** the operation targets another store or an OWNER-only action **Then** it is REJECTED with the same rule as the REST endpoint (403 / store scope)

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-12-8, 2026-07-24] D2 extractAssignedStoreId fail-open when auth==null [AbstractSyncOperationHandler.java:50-51] — null auth is treated as OWNER; fail closed — **Resolved 2026-10-11 (v1s-12-10):** `extractAssignedStoreId()` throws `DomainException(FORBIDDEN)` when there is no Authentication → REJECTED `FORBIDDEN`; sale/day-closure/count handler tests now authenticate explicitly; `AbstractSyncOperationHandlerTest` covers it.
- [x] [Deferred-from: CR v1s-14-14, 2026-07-24] TOGGLE_CATEGORY sync has no OWNER-only check [CategorySyncHandler.java:104-120] — named in 12-10 scope; REST DELETE is OWNER-only — **Resolved 2026-10-11 (v1s-12-10):** TOGGLE_CATEGORY (both directions) is OWNER-only, checked before any repository read; REST `PATCH /categories/{id}/toggle` aligned (`@PreAuthorize` OWNER + explicit check → 403).

**Also resolved in v1s-12-10 (2026-10-11, spec-v1s-12-10-sync-rbac-residual.md, audit I1 a/b/c):** PROMOTE_PRODUCT OWNER-only on sync; UPDATE_PRODUCT / CREATE_DRAFT_PRODUCT (sync) and PATCH /products/{id} (REST) pass the real role (10-arg `UpdateProductDto` defaulting to OWNER removed); CREATE_INVENTORY_SESSION, SAVE_INVENTORY_COUNT and REST POST/PUT /counts + POST /quick-add scoped to the employee's store; app: an EMPLOYEE checkout no longer promotes drafts (no PROMOTE_PRODUCT), a stocked draft is not re-prompted for initial stock, EMPLOYEE cannot open edit on a DRAFT; owner notification wording driven by `SaleDraftProductsUpgradedEvent.draftsPromoted`. Routed: InventorySessionSyncHandler use-case bypass → v1s-13-8.

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 12.11: Transfert — un employé ne transfère que depuis sa boutique (REST + sync) — **[A]**

As a Toor, I want an EMPLOYEE to initiate transfers only from their assigned store, so that FR36 and v1s-12-8 AC4 hold again after the opening made in 4302eba.

**Scope:** (sprint-status.yaml, 2026-10-11 décision PO) Un employé ne transfère que depuis sa boutique ; annuler l'ouverture de 4302eba (REST + sync) ; FR36 et v1s-12-8 AC4. Tracker key: `v1s-12-11-transfer-employee-own-store`.

**Refs:** FR36, Story 12.8 AC4, Story 12.9, commit 4302eba. **Refonte absorption:** re-appliquer dans `inventory`/`store` (règle de source de transfert).

**AC:**
**Given** an EMPLOYEE initiating a transfer (REST or TRANSFER sync op) **When** `sourceStoreId` differs from the JWT assigned store **Then** 403 / REJECTED; OWNER keeps cross-store transfers (FR36, v1s-12-8 AC4 restored)

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-12-9, 2026-07-24] No widget test for _shouldShowReceiveButton [transfer_history_page.dart:456-465] — 12-11 changes transfer rules; add EMPLOYEE match/mismatch + OWNER cases — **Resolved 2026-10-11 (v1s-12-11):** 4 widget tests in transfer_history_page_test.dart (EMPLOYEE match / mismatch / null, OWNER null).
- [x] [Deferred-from: CR v1s-12-8, 2026-07-24] D4 TransferSyncHandler does not validate sourceStoreId [TransferSyncHandler.java:41-49] — 12-11 reworks transfer source rules on sync; validate store belongs to tenant/employee — **Resolved 2026-10-11 (v1s-12-11):** EMPLOYEE source must equal JWT store (FORBIDDEN → REJECTED); tenant membership already enforced by ExecuteTransferService STORE_NOT_FOUND lookup in the tenant schema.

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 12.12: Durcissement auth & session (JWT filter, cache de révocation, reset mot de passe, ownership device sync) — **[A]**

As a Toor, I want the auth/session seams hardened (JWT filter edge cases, bounded revocation cache, password-reset transactions and limits, refresh-token hygiene, device ownership on sync), so that the residual findings of the 12.2/12.3/12.5/13.2/14.12 reviews are closed in one pass.

**Scope:** JWT filter edge cases (clock skew, OWNER bad tenantId, >1 row), revocation-cache rework (Caffeine, atomic load, Clock, logs), password-reset hardening (tx around OTP send, @Version attempts, token index/purge, rate-limiter eviction, PII), refresh_tokens purge/index, SessionTokenIssuer tx abort, JSON 403, sync device-ownership TOCTOU/logging/enumeration, E.164 phone normalisation. Tracker key: `v1s-12-12-auth-session-hardening`.

**Refs:** S1–S5, FR68, Stories 12.2 / 12.3 / 12.5 / 13.2 / 14.11 / 14.12, UI-3 lot 1 backend review. **Refonte absorption:** re-appliquer dans `identity` (JwtAuthFilter, révocation, reset) et `sync` (ownership device).

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-14-12, 2026-07-23] @Transactional wraps synchronous WhatsApp OTP send [RequestPasswordResetService.java:58,97] — DB connection held 5-30s during network call; move send after commit — **FIXED (v1s-12-12):** OTP sent in `afterCommit` (try/catch, error logged without phone), direct send when no tx; still before the HTTP response (anti-enumeration timing kept).
- [x] [Deferred-from: CR v1s-14-12, 2026-07-23] Reset token attempts read-modify-write without @Version [ResetPasswordService.java:89-90] — concurrent guesses can exceed attempt cap; @Version or atomic UPDATE — **FIXED (v1s-12-12):** atomic `tryClaimAttempt` UPDATE (`attempts < 5 AND consumed_at IS NULL`) before the bcrypt compare + CAS `markConsumed`; Postgres-tested.
- [x] [Deferred-from: CR v1s-14-12, 2026-07-23] No index on consumed_at + no purge job for reset tokens [V6__password_reset_tokens.sql] — add partial index + cleanup job — **FIXED (v1s-12-12):** V10 partial index `(phone_number, created_at DESC) WHERE consumed_at IS NULL` + `(expires_at)`; `AuthTokenCleanupJob` daily 03:30 purges rows expired > 1 day.
- [x] [Deferred-from: CR v1s-14-12, 2026-07-23] PII phoneNumber in PasswordResetRequestedEvent [PasswordResetRequestedEvent.java] — not serialized today; drop field or mask before event tracing is enabled — **FIXED (v1s-12-12):** field dropped, event = `(userId, occurredAt)`; logs no longer print the phone.
- [x] [Deferred-from: CR v1s-14-12, 2026-07-23] PasswordResetRateLimiter maps never evicted [PasswordResetRateLimiter.java:30-33] — unbounded per-phone buckets; Caffeine/expiry sweep — **FIXED (v1s-12-12):** Caffeine caches per window (`expireAfterWrite` = window, `maximumSize` 100 000, Clock ticker); key = normalised phone.
- [x] [Deferred-from: CR v1s-12-5, 2026-07-21] D3 OWNER + invalid tenantId -> 500 instead of 401 [JwtAuthFilter.java] — OWNER tokens skip tenant validation; global validation — **ALREADY CLOSED:** v1s-12-5 applies `TenantSchema.validate` to all roles; v1s-12-12 adds the OWNER test (401 TOKEN_INVALID, no SQL, no revocation lookup).
- [x] [Deferred-from: CR v1s-12-5, 2026-07-21] D5 attacker-controlled JWT claim in exception message [TenantSchema.java:65] — sanitise before it can reach logs — **FIXED (v1s-12-12):** `TenantSchema.validate` message carries the length only, never the raw value.
- [x] [Deferred-from: CR v1s-12-5, 2026-07-21] D10 IncorrectResultSizeDataAccessException -> 500 [JwtAuthFilter.java:155,183] — catch and map to 401/500 with log; root cause is data integrity — **FIXED (v1s-12-12):** `LIMIT 1` + UNIQUE make >1 row impossible; any other `DataAccessException` on the EMPLOYEE query → logged + 500 JSON `INTERNAL_ERROR`.
- [x] [Deferred-from: CR v1s-12-3, 2026-07-21] accessDeniedHandler returns HTML not JSON [SecurityConfig.java:86-87] — res.sendError(403) -> servlet HTML page; write JSON body — **FIXED (v1s-12-12):** handler writes `ApiResponseWrapper.error(..., "FORBIDDEN", "FORBIDDEN")` JSON with 403.
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] No clock-skew policy / future iat accepted [JwtAuthFilter.java:141] — setAllowedClockSkewSeconds + reject far-future iat — **FIXED (v1s-12-12):** `clockSkewSeconds(60)` + `FutureIssuedAtException` (JwtException) when `iat` > now + 60 s → 401.
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] Revocation cache unbounded [CachedTokenRevocationAdapter.java:40] — Caffeine expireAfterWrite + maximumSize — **FIXED (v1s-12-12):** Caffeine `expireAfterWrite` 5 min + `maximumSize` 100 000.
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] Stale cache re-population race [CachedTokenRevocationAdapter.java:54-55] — atomic compute/tombstone — **FIXED (v1s-12-12):** invalidation on revoke AND again `afterCommit` — a value cached while the revoking tx was open is evicted at commit.
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] Cache stampede on miss [CachedTokenRevocationAdapter.java:49-55] — coalesce loads — **FIXED (v1s-12-12):** `Cache.get(key, loader)` — one delegate load per key under concurrency (tested).
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] TTL-expiry test does not exercise expiry (AC7 coverage) [CachedTokenRevocationAdapterTest.java:42-60] — inject Clock and advance it on the same key; behaviour correct by inspection, so no reopen of v1s-12-2 — **FIXED (v1s-12-12):** Clock-driven Caffeine ticker; test advances a mutable Clock past the TTL on the same key.
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] Unused logger, no observability on revocation cache [CachedTokenRevocationAdapter.java:32] — debug logs on miss/invalidate — **FIXED (v1s-12-12):** debug logs on miss and invalidate.
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] OWNER per-request revocation DB hit on miss [JwtAuthFilter.java:138] — merge with EMPLOYEE query into one round-trip — **REJECTED (v1s-12-12):** the Caffeine cache already coalesces to one DB load per (user, tenant) per 5 min; merging with the EMPLOYEE `employees` query would drop fail-open + cache for EMPLOYEE and couple a tenant table with `public`.
- [x] [Deferred-from: CR v1s-13-2, 2026-07-21] TOCTOU device ownership check vs upsert on pull [SyncController.java:146-157] — UNIQUE device_id + ON CONFLICT — **FIXED (v1s-12-12):** `device_id` is already the PK; ON CONFLICT now guarded `WHERE user_sync_state.user_id = EXCLUDED.user_id` (owner never reassigned), upsert returns whether a row was written, WARN when nothing written; Postgres-tested.
- [x] [Deferred-from: CR v1s-13-2, 2026-07-21] No security log on DEVICE_ID_MISMATCH [SyncController.java] — emit security/audit log — **FIXED (v1s-12-12):** WARN on the `SECURITY` logger with actorId, ownerId, tenantId, deviceId, endpoint.
- [x] [Deferred-from: CR v1s-13-2, 2026-07-21] Device existence disclosure via 403 vs success [SyncController.java] — generic error or rate-limit — **REJECTED (v1s-12-12):** device ids are client UUIDv4 (122 random bits) — probing infeasible; a generic error would hide a real misconfiguration from the legitimate owner. The new security log makes probing visible.
- [x] [Deferred-from: CR v1s-14-11, 2026-07-23] Phone stored as '+237…' or '237…' (no E.164 normalisation) [UpdateEmployeeService.java:83-96, RegistrationRequest] — normalise to E.164 before uniqueness check — **FIXED (v1s-12-12):** `PhoneNumbers.normalize` (`+<digits>`) at login, register, employee create/update, forgot/reset, rate-limit key; V10 prefixes `+` on digit-only rows, skipping users whose `+` form already exists (manual merge).
- [x] [Deferred-from: CR UI-3 lot 1 backend, 2026-10-11] SessionTokenIssuer swallows SQL error inside caller tx [SessionTokenIssuer.java:96] — Postgres aborts tx -> refresh-token save fails; verify whether tenant_preferences can be missing, use to_regclass probe — **FIXED (v1s-12-12):** `to_regclass` probe before the EXISTS query; no SQL error swallowed inside the caller tx.
- [x] [Deferred-from: CR UI-3 lot 1 backend, 2026-10-11] refresh_tokens keeps every rotated row, no purge/index [V8__refresh_token_rotation.sql] — purge job + index on user_id/replaced_by_id — **FIXED (v1s-12-12):** V10 indexes `(user_id)`, `(replaced_by_id) WHERE NOT NULL`, `(expires_at)`; `AuthTokenCleanupJob` purges rows expired > 1 day.
- [x] [Deferred-from: CR v1s-14-11, 2026-07-23] bcrypt DoS: no @Size(max) on SetPasswordRequestDto [SetPasswordRequestDto.java:11] — add @Size(min=8,max=128); also ChangePassword DTOs — **FIXED (v1s-12-12):** `@Size(max = 72)` (not 128 — Spring Security 6.5 BCrypt throws above 72 bytes) on every password DTO field, `min = 8` where a new password is set; `ResetPasswordService` checks 8..72 UTF-8 bytes.
- [x] [Deferred-from: CR v1s-14-5, 2026-07-23] SUSPENDED -> DELETION_PENDING -> cancel -> ACTIVE bypasses suspension [tenant status transitions] — PO decision: restore SUSPENDED on cancel or forbid the request — **FIXED (v1s-12-12, PO decision):** `requestDeletion` on a SUSPENDED tenant → 403 `ACCOUNT_SUSPENDED`; erasure stays available via SUPER_ADMIN `forceDelete`.

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 12.13: Durcissement secrets & config prod (extension ProdSecretsValidator) — **[A]**

As a Toor, I want production to fail fast on every weak or defaulted secret (DB password, provider tokens, admin password strength), so that a misconfigured deploy cannot start with dev defaults.

**Scope:** Extend ProdSecretsValidator to POSTGRES_PASSWORD (+ provider tokens), admin password strength policy, Spring-context fail-fast test. Tracker key: `v1s-12-13-prod-secrets-config-hardening`.

**Refs:** Story 10.3 (refonte), MOD-NFR11, Story 12.1. **Refonte absorption:** native — le validateur est repris tel quel.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR 10-3, 2026-06-19] POSTGRES_PASSWORD falls back to dev default in prod [application.yml:17] — extend ProdSecretsValidator to DB password (and Twilio/Wassender tokens) — **Resolved (v1s-12-13, commit 2001e5c):** `validateDatabasePassword` (blank/placeholder/dev-CI denylist/<12 chars → `[FailFast]` POSTGRES_PASSWORD) + `validateWhatsAppProvider` (wassender URL+token, twilio SID+token+from-number, failover all — required and non-placeholder; noop/unset legal with WARN; empty, padded or unknown provider rejected). Dev default in `application.yml` kept for local dev.
- [x] [Deferred-from: CR 10-3, 2026-06-19] Fail-fast not tested at Spring wiring level [ProdSecretsValidatorTest] — add @SpringBootTest @ActiveProfiles(prod) proving ApplicationContextException — **Resolved (v1s-12-13, commit 2001e5c):** `ProdSecretsFailFastContextTest` boots `KeevoApplication` under `prod` via `SpringApplicationBuilder` (a failing context cannot be asserted with `@SpringBootTest`; the failure surfaces as `BeanCreationException` → root `IllegalStateException [FailFast]`, asserted on the cause chain). New `ProdSecretsValidatorOrdering` makes every `DataSource` depend on the validator so it runs before Hikari/Flyway (verified: removing it makes the context test fail).
- [x] [Deferred-from: CR 10-3, 2026-06-19] No admin password strength policy [ProdSecretsValidator.java:95-96] — only exact 'Admin@1234!' rejected; .env.example placeholder passes — **Resolved (v1s-12-13, commit 2001e5c):** `validateAdminPassword` rejects blank/placeholder/known defaults (case-insensitive, incl. `change_me_strong_password`), < 12 chars, < 3 of 4 classes, > 72 UTF-8 bytes (`PasswordLimits`).

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

---

# Epic 13: Stabilisation V1 — Concurrence & Intégrité données (Piste A, release-blocker)

Éliminer les races/pertes-de-données mécaniquement certaines. **Toutes tag A.**

## Story 13.1: Verrouillage optimiste StockTransfer + StockLevel — **[A]**

As a Toor, I want concurrent transfers/mutations to never double-credit or lose updates (FR32, FR37, FR48).

**Refs:** B-CRIT-1/2, FR32 AC Step2. **Refonte absorption:** re-implement natively (mêmes entités, le `@Version` porte).

**AC:**
**Given** `StockTransferJpaEntity` + `StockLevelJpaEntity` no `@Version`
**When** optimistic locking added
**Then** both get `@Version long version` (+ domain)
**And** `CompleteTransferService` relies on `OptimisticLockException` OR conditional `UPDATE stock_transfers SET status=COMPLETED WHERE id=? AND status='IN_TRANSIT'` (=0 → idempotent silent refresh, FR32 AC)
**And** `StockOperationService.recordOperation` uses `SELECT … FOR UPDATE` OR atomic `UPDATE stock_levels SET quantity = quantity + ? WHERE product_id=? AND store_id=? AND (quantity+?)>=0`
**And** already-completed re-reception returns the existing transfer idempotently

**Doc:** Story 3.3 AC7 + ARCH locking. **TDD:** `shouldNotDoubleCreditOnConcurrentTransferComplete()`, `shouldNotLoseUpdateOnConcurrentStockMutation()`, `shouldReturnExistingTransferOnDuplicateReception()`.

**Contrepartie mobile (Flutter):** gérer la réponse 409 `OptimisticLockException`/`TRANSFER_INVALID_STATUS` côté sale/transfer — refresh silencieux + SnackBar humain « Cette opération a été modifiée, actualisé » (pas d'erreur technique) ; transfert déjà-COMPLETED → silent refresh (ne pas afficher d'erreur).

## Story 13.2: Corriger le verrouillage permanent porte sync 7j — **[A]**

As a Toor, I want a device that pulled fresh data after a 7-day window to be unblocked and able to push (FR74).

**Refs:** B-CRIT-3, FR74, 5-4 AC10. **Refonte absorption:** re-implement dans `sync` modulaire.

**AC:**
**Given** `SyncGateCheckService.isStalePush` checks `last_push_at` exclusively
**When** a gated device performs a successful pull
**Then** staleness computed against `GREATEST(last_push_at, last_pull_at)` (option a) OR successful pull stamps `last_push_at = NOW()` for previously-stale (option b)
**And** after a successful pull, a previously-gated device's next `POST /sync/push` returns 200 (not 423)
**And** `deviceId` bound to authenticated `actorId` — closes the rotation bypass

**Doc:** Story 5-4 AC10. **TDD:** `shouldUnblockPushAfterSuccessfulPull()`, `shouldRejectDeviceIdNotOwnedByActor()`.

**Contrepartie mobile (Flutter):** le client garde un `deviceId` stable (généré une fois, persisté en secure storage, lié à l'actor) — ne pas le régénérer à chaque session ; après un pull réussi qui lève la porte, relancer le push automatiquement (le client considère déjà le déverrouillage via `kLastSyncAtKey` post-pull).

## Story 13.3: Corriger DayClosureDeltaProvider + fenêtre hebdo + timing EOD — **[A]**

As a Toor, I want sync payloads + report windows correct (FR42, FR52, FR53).

**Refs:** B-MED (3 bugs), FR42/52/53/60, UX13. **Refonte absorption:** re-implement dans `reporting`/`sync`.

**AC:**
**Given** `DayClosureDeltaProvider.java:46` maps `totalTransactions` from `row[4]` (dup of `totalSales`)
**When** fixed
**Then** `total_transactions` selected from a real column (DDL+SELECT) **or** the key dropped from the payload
**And** `WeeklyReportScheduler.java:127` uses `TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)` (mirrors `ReportController.triggerWeekly:171-174`)
**And** `DayClosureAutoScheduler` EOD respects per-tenant `eodReportTime` + restores the 20:00 WAT fallback (UX13) **OR** AC/PRD formally updated to retire 20h (décision PO)

**Doc:** Story 7-2/7-3/7-6 + deferred HF-2 D1. **TDD:** `shouldMapDistinctTotalTransactions()`, `shouldComputeMondayWeekStartForAnyConfiguredDay()`, `shouldFireEodAtConfiguredTenantTime()`.

## Story 13.4: Atomicité Drift — sync_queue dans la transaction (Flutter) — **[A]**

As a Toor, I want a locally-recorded sale queued for sync in the same Drift txn (FR73).

**Refs:** F-HIGH-4, AC 4-1/5-6. **Refonte absorption:** re-appliquer dans le Flutter refonte.

**AC:**
**Given** `sale_repository_impl.dart:41-84` enqueues `sync_queue` after `insertAll`
**When** refactored
**Then** `sync_queue` insert inside `_db.transaction(...)` covering sale+items+stock+movements+queue
**And** same pattern applied to store offline writes (`store_repository_impl.dart`, currently bypasses queue) — scope FR30

**Doc:** Story 4-1/5-6 AC. **TDD:** `shouldNotLoseSaleWhenCrashBetweenInsertAndQueue()`, `shouldQueueStoreWriteOffline()`.

## Story 13.5: FR90 — Annulation/correction vente complétée + restauration stock — **[A]**

As a proprietor, I want to cancel/correct a completed sale with justification + automatic stock restoration (FR90, Story 4.5).

**Refs:** B-HIGH-8, F-CRIT-1, FR90. **Refonte absorption:** re-implement dans `commerce` modulaire.

**AC:**
**Given** a COMPLETED sale
**When** owner calls `POST /api/v1/sales/{id}/cancel` (NEW) with `justification` ≥10
**Then** `SALE_CANCELLED` movement restores each item qty; sale → `CANCELLED`; `SaleCancelledEvent` (extended to COMPLETED) → audit
**And** `POST /api/v1/sales/{id}/correct` (NEW) handles item correction + `SaleCorrectedEvent`
**And** EMPLOYEE → 403; Flutter `sale_detail_page.dart` exposes cancel/correct with justification TextField + ≥10 guard

**Doc:** Epic 4 Story 4.5 — author artifact `4-5-*.md`. **TDD:** `shouldCancelCompletedSaleAndRestoreStock()`, `shouldRejectCancellationWithoutJustification()`, `shouldReturn403ForEmployee()`; Flutter `shouldShowJustificationFieldAndValidateMin10()`.

## Story 13.6: Statut ventes brouillon (PENDING_VALIDATION) — **[A]**

As a proprietor, I want a draft-item cart recorded as PENDING_VALIDATION (Story 4.3).

**Refs:** F-CRIT-2, Story 4.3 AC1-4. **Refonte absorption:** re-appliquer Flutter.

**AC:**
**Given** `record_sale_notifier.dart:96-111` hardcodes `status:'COMPLETED'`
**When** cart has DRAFT items (`draftItems.isNotEmpty`)
**Then** sale submitted `status: PENDING_VALIDATION`, stock NOT decremented, `CREATE_SALE`(pending) queued
**And** POS button shows "🔶 Vente brouillon" for draft carts; non-draft carts keep COMPLETED

**Doc:** Story 4-3 AC1-4. **TDD:** `shouldSubmitPendingValidationWhenCartHasDraftItems()`, `shouldNotDecrementStockForPendingSale()`, `shouldShowBrouillonButton()`.

## Story 13.7: Événements d'audit/mouvement sur validation inventaire offline — **[A]**

As a Toor, I want offline inventory validation to emit `StockAdjustedEvent` + `StockMovement(ADJUSTMENT)` + threshold checks (FR48, Story 6.4).

**Refs:** F-CRIT-3, FR48, Story 6.4 AC2/4/5. **Refonte absorption:** re-appliquer (mêmes champs `source`/`inventorySessionId`).

**AC:**
**Given** `validate_inventory_provider.dart:79-111` only updates `stock_levels.quantity`
**When** offline validate runs
**Then** for each `écart != 0` insert `StockMovement` (type `ADJUSTMENT`, `source=INVENTORY`, `inventorySessionId`, qtyBefore/After, actorId)
**And** `StockMovementModel` + `stock_movements_table` gain `source` + `inventorySessionId` columns (mirrors backend gap)
**And** threshold check queues `StockThresholdBreachedEvent` when below min
**And** variant respected (`variantId` filter)

**Doc:** Story 6.4 AC4. **TDD:** `shouldEmitStockMovementOnOfflineValidate()`, `shouldSkipEcartZero()`, `shouldQueueThresholdEventWhenBelowMin()`.

## Story 13.8: Intégrité sync résiduelle (chemins d'écriture offline, parité des handlers) — **[A]**

As a Toor, I want every offline write path to be atomic and every sync handler to validate and be idempotent like its REST counterpart, so that offline sales/stores/products never duplicate movements, double-apply stock or leave ghost rows.

**Scope:** Offline write paths: duplicate SALE movement on pull (movement ids in CREATE_SALE), initial-stock double apply / stale draft map, Drift write+queue atomicity (store, quick-add, product insert), store repo crash/fallback, sync handler validation/idempotency parity (store, stock adjust), offline inventory validation guards, stale sync_queue payloads. Tracker key: `v1s-13-8-sync-integrity-residual`.

**Refs:** B-CRIT-3, F-CRIT-1/2, FR27/FR30, Stories 13.4 / 13.6 / 13.7 / 16.6, UI-3 reviews. **Refonte absorption:** re-appliquer (Flutter Drift + handlers `sync`).

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-13-6, 2026-07-22] Stale draftInitialStocksProvider entries [record_sale_notifier.dart:66-67] — item removed from cart keeps its initial-stock entry which is still applied — **fixed v1s-13-8: entries filtered to draft items in the cart, map cleared after every successful sale**
- [x] [Deferred-from: CR v1s-13-4, 2026-07-22] Initial stock applied twice on retry after recordSale failure [record_sale_notifier.dart:70-83,124] — idempotency guard or move into recordSale transaction — **already resolved by UI-3 (commit 1041827): entries applied in the recordSale Drift transaction; covered by record_sale_notifier_test rollback tests**
- [x] [Deferred-from: CR v1s-13-7, 2026-07-22] UNIQUE violation on variant mismatch in offline inventory [validate_inventory_provider.dart:91-131, stock_levels_table.dart:31-33] — unique index lacks variant_id; extend when variants land — **fixed v1s-13-8: fallback to the (product, store) row; unique-key extension routed to Story 14.1**
- [x] [Deferred-from: CR v1s-13-7, 2026-07-22] Session with zero counts silently VALIDATED [validate_inventory_provider.dart:214-220] — guard empty session — **fixed v1s-13-8: offline validation refuses a session with no counted line**
- [x] [Deferred-from: CR v1s-13-7, 2026-07-22] Re-validation overwrites completedAt [validate_inventory_provider.dart:214-220] — guard already-VALIDATED session — **fixed v1s-13-8: VALIDATED session is a no-op (re-checked inside the transaction)**
- [x] [Deferred-from: legacy cart UI session, 2026-04-07] Stale sync_queue CREATE_SALE payloads with 'status' key [sync_queue / _buildPayload] — one-off migration to rename status->requestedStatus or confirm none remain in prod — **fixed v1s-13-8: SaleSyncHandler reads legacy `status` (COMPLETED/PENDING_VALIDATION only) when `requestedStatus` is absent — no client migration needed**
- [x] [Deferred-from: CR v1s-12-8, 2026-07-24] D3 StockAdjust quantity truncated via intValue [StockAdjustSyncHandler.java:47] — validate range before intValue — **fixed v1s-13-8: integral int-range validation before use (other handlers → v1s-15-9)**
- [x] [Deferred-from: CR v1s-16-6, 2026-07-27] deactivateStore() crashes on existing! when not cached [store_repository_impl.dart:152-153] — null-safe path; queue op already committed before crash — **fixed v1s-13-8: not-cached store → StoreException.notFound before any write/queue**
- [x] [Deferred-from: CR v1s-16-6, 2026-07-27] Store offline write + queueOperation not atomic [store_repository_impl.dart:71-83,121-132,145-151] — single Drift transaction (also clients/suppliers) — **fixed v1s-13-8: single Drift transaction for stores, clients, suppliers**
- [x] [Deferred-from: CR v1s-16-6, 2026-07-27] updateStore() fallback fabricates type/isActive [store_repository_impl.dart:108-114] — mislabelled cache until next sync — **fixed v1s-13-8: not-cached store → notFound, no fabricated row**
- [x] [Deferred-from: CR v1s-16-6, 2026-07-27] CreateStoreSyncHandler skips 2-100 name length [CreateStoreSyncHandler.java:49-52] — sync handlers bypass DTO validation (clients too); align — **fixed v1s-13-8: StoreFactory.validateName shared; client/supplier blank phone rejected**
- [x] [Deferred-from: CR v1s-16-6, 2026-07-27] StoreSyncHandler no idempotency guard [StoreSyncHandler.java] — retried UPDATE/DEACTIVATE re-executes use case — **fixed v1s-13-8: state-based no-op (inactive store / unchanged values); op-id dedup already in SyncPushService**
- [x] [Deferred-from: CR UI-3 lot 1 backend, 2026-10-11] Offline sales create a 2nd SALE stock movement on pull [RecordSaleService.java:89] — send movement ids in CREATE_SALE payload (app + backend) — **fixed v1s-13-8: SALE movement id = client sale item id (payload itemId) on both sides; validate/cascade paths → v1s-13-10**
- [x] [Deferred-from: CR UI-3 lots 2-5 app, 2026-10-11] QuickAdd offline path / LocalProductDataSource insert without transaction [quick_add_product_notifier.dart, local_product_datasource.dart] — write + queue in one Drift transaction — **fixed v1s-13-8: Drift transaction in quick-add and LocalProductDataSource.insert/insertDraft**
- [x] [Deferred-from: build-auto v1s-12-10, 2026-10-11] InventorySessionSyncHandler bypasses CreateInventorySessionUseCase [InventorySessionSyncHandler.java:apply] — writes via repository.save: no store-existence check, no single-active-session check, no InventorySessionCreatedEvent; route through the use case with the client session id (idempotent replay, offline conflict policy) — **fixed v1s-13-8: routed through CreateInventorySessionUseCase with client id (idempotent replay, store mismatch FORBIDDEN, active conflict REJECTED); 12-10 store rule kept; local reconciliation → v1s-16-12**

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 13.9: Verrouillage des schedulers & courses check-then-act — **[A]**

As a Toor, I want schedulers protected by a distributed lock and the remaining check-then-act races closed, so that a multi-instance deploy or a concurrent request cannot double-close a day, exceed a plan limit or lock out every owner.

**Scope:** ShedLock/distributed lock for schedulers (day closure, account deletion, trends), check-then-act races (product count, anti-lockout, cancel sync, transfer clientId, alert cooldown), TTL eviction for notification batch maps. Tracker key: `v1s-13-9-scheduler-locking-races`.

**Refs:** B-CRIT-1/2, NFR (multi-instance), Stories 13.3 / 13.5 / 14.5 / 14.10 / 14.11 / 14.13, legacy 8-1. **Refonte absorption:** re-appliquer (ShedLock transverse, verrous dans `commerce`/`identity`).

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-14-13, 2026-07-24] TOCTOU countActiveProducts->save [CreateProductUseCase.java:128-137] — concurrent creates can exceed plan limit; @Transactional + lock if seen in prod — **fixed v1s-13-9 (8e2471e): CreateProductUseCase.execute @Transactional + per-tenant advisory lock `product-limit:<schema>` as its first statement, covering the clientId lookup, name check and count → save (same lock in CreateDraftProductUseCase)**
- [x] [Deferred-from: CR v1s-14-10, 2026-07-22] Notification batchWindows never evicted [ProductCreationNotificationListener.java, StockModificationNotificationListener.java] — bounded but unbounded-lifetime map; TTL cache — **fixed v1s-13-9 (8e2471e): windows accumulated via compute(); the flush removes an expired window atomically with its events (computeIfPresent → null), so an event racing the flush lands in a fresh window**
- [x] [Deferred-from: CR v1s-13-3, 2026-07-21] Day-closure scheduler check-then-act TOCTOU [DayClosureAutoScheduler.java:122-134] — UNIQUE(store_id, report_date) or distributed lock — **fixed v1s-13-9 (8e2471e): per-store advisory lock `day-closure:<storeId>` taken first in CloseDayService.closeDay (no UNIQUE index: existing prod duplicates would break the boot index sync); scheduler logs a concurrent DAY_ALREADY_CLOSED at debug**
- [x] [Deferred-from: CR v1s-13-3, 2026-07-21] Hourly closure cron overhead [DayClosureAutoScheduler.java:86] — N tenants x M stores x 24; monitor before scale — **decision v1s-13-9 (8e2471e): kept hourly by design (per-tenant eodReportTime); with ShedLock it runs on one instance; per-tick info log (tenant count + elapsed ms) added as the monitoring hook — revisit if a tick exceeds ~1 min**
- [x] [Deferred-from: CR v1s-13-3, 2026-07-21] Scheduler restart can double-close mid-run [DayClosureAutoScheduler.java:91-103] — idempotency key / lock — **fixed v1s-13-9 (8e2471e): ShedLock on runAutoClosure (PT50M/PT1M) + the per-store advisory lock in CloseDayService make a re-run or second instance a no-op (DAY_ALREADY_CLOSED)**
- [x] [Deferred-from: CR v1s-13-5, 2026-07-22] TOCTOU in CancelSaleSyncHandler AC5 guard [CancelSaleSyncHandler.java:54-62] — pass the loaded status into the command — **fixed v1s-13-9 (8e2471e): handler no longer pre-loads the sale; CancelPendingSaleCommand.pendingOnly=true → use case refuses COMPLETED with FORBIDDEN and claims PENDING_VALIDATION→CANCELLED atomically (lost claim on a concurrently validated sale → FORBIDDEN); validateSale claims PENDING_VALIDATION→COMPLETED the same way (loser → SALE_NOT_PENDING, rollback)**
- [x] [Deferred-from: CR v1s-14-11, 2026-07-23] Anti-lockout TOCTOU (two owners demote each other) [ChangeEmployeeRoleService.java:95-99] — SELECT FOR UPDATE / advisory lock — **fixed v1s-13-9 (8e2471e): advisory lock `owners:<schema>` before counting owners on an OWNER→EMPLOYEE demotion; DeactivateEmployeeService applies the same lock + last-owner check when the deactivated membership is OWNER**
- [x] [Deferred-from: legacy CR 8-1, 2026-04-04] StockAlert/notification batchWindows never evicted (8-1 D1) [StockAlertNotificationListener.java] — same as v1s-14-10 item; TTL cache — **fixed v1s-13-9 (8e2471e): window bookkeeping via compute(); @Scheduled(60 s) evictExpiredWindows() removes windows older than the 60 s batch window (not ShedLocked: per-instance map)**
- [x] [Deferred-from: legacy CR 8-1, 2026-04-04] Cooldown check/upsert TOCTOU [StockAlertNotificationListener / cooldown repo] — benign duplicate; advisory lock if dedup becomes critical — **fixed v1s-13-9 (8e2471e): tryAcquireCooldown = single `INSERT … ON CONFLICT DO UPDATE … WHERE last_sent_at <= NOW() - window` (atomic check+set, no advisory lock needed); SalesTrendDetectionScheduler keeps exists+upsert (single runner under ShedLock)**
- [x] [Deferred-from: legacy CR 8-1, 2026-04-04] No @SchedulerLock on trend scheduler [trend scheduler detectTrends()] — ShedLock before multi-instance — **fixed v1s-13-9 (8e2471e): ShedLock (JDBC, DB time, public.shedlock via Flyway V11) on detectTrends (PT50M/PT1M)**
- [x] [Deferred-from: CR v1s-14-5, 2026-07-23] No distributed lock on AccountDeletionScheduler [AccountDeletionScheduler.java] — multi-instance race; ShedLock with the other schedulers — **fixed v1s-13-9 (8e2471e): @SchedulerLock (PT2H/PT1M); also on AuthTokenCleanupJob, SubscriptionExpiryScheduler, WeeklyReportScheduler, ReportDeliveryRetryService (PT15M/PT30S), SyncErrorLogCleanupJob, DayClosureAutoScheduler; SchedulerLockCoverageTest guards new jobs**
- [x] [Deferred-from: CR spec-stock-transfer-offline-sync-flow-fix, 2026-08-11] TOCTOU on transfer clientId idempotency [ExecuteTransferService.java:74-79] — double-fire can decrement twice / PK violation; unique + catch — **fixed v1s-13-9 (8e2471e): advisory lock `transfer:<clientId>` before the findById idempotency check → second double-fire returns the first transfer**

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 13.10: Verrou optimiste sur Sale (@Version + 409) — **[A]**

As a Toor, I want concurrent cancel/correct on the same sale to conflict instead of overwriting each other (mirror of 13.1 for `Sale`).

**Scope:** @Version on Sale + 409 mapping so cancel/correct can't overwrite each other (mirror v1s-13-1). Tracker key: `v1s-13-10-sale-optimistic-lock`.

**Refs:** FR90, Story 13.1, Story 13.5. **Refonte absorption:** native (entité `Sale` du module `commerce`).

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-13-5, 2026-07-22] No optimistic lock on Sale (double cancel / correct overwrites cancel) [CorrectSaleService.java:91-95,115; ValidateSaleService.java] — @Version on SaleJpaEntity + 409 mapping (mirror v1s-13-1) — **fixed v1s-13-10 (commit 5cb755a): `@Version long version` on SaleJpaEntity + domain Sale (Flyway V12 + tenant DDL `DDL_SALES_MIGRATE_VERSION`); status CAS bumps the version; COMPLETED→CANCELLED claim is version-checked (lost claim: CANCELLED → SALE_ALREADY_CANCELLED, else DomainException OPTIMISTIC_LOCK → 409, no SALE_CANCELLED movement); app pulls and re-reads the sale detail on 409**
- [x] [Deferred-from: CR UI-3 lot 1 backend, 2026-10-11] CorrectSaleService race with concurrent cancel (dup) [CorrectSaleService.java:115] — same as v1s-13-5 item; @Version on Sale — **fixed v1s-13-10 (commit 5cb755a): the corrected Sale carries the version read; the adapter saves with saveAndFlush, so a cancel/correction committed meanwhile makes the save throw ObjectOptimisticLockingFailureException (→ 409) and the ADJUSTMENT movements roll back**
- [x] [Deferred-from: build-auto v1s-13-8, 2026-10-11] ValidateSaleService / SaleValidationCascadeService (and app validatePendingSale / cascade in local_sale_datasource.dart) still write SALE movements with server-random ids [ValidateSaleService.java:115, SaleValidationCascadeService.java:100] — a PENDING sale validated later gets a 2nd SALE movement on pull; reuse the sale item id as movement id on both sides (as v1s-13-8 did for RecordSaleService) — **fixed v1s-13-10 (commit 5cb755a): SALE movement id = sale item id in ValidateSaleService, SaleValidationCascadeService and the app's validateAndDecrementStock / cascadeValidatePendingSales; the cascade (backend and app) now claims PENDING_VALIDATION→COMPLETED conditionally and skips a sale settled meanwhile**

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 13.11: Fenêtres de clôture journalière & rapports (décisions PO) — **[A]**

As a Toor, I want the day-closure and weekly-report windows decided and enforced (sales after eodReportTime, cancel/correct after closure, eodReportTime validation), so that no sale is silently excluded from a closure or report.

**Scope:** PO decisions + code: EOD closure window, weekly Sunday 20:00 gap, cancel/correct after closure, eodReportTime validation, Clock in CloseDayService. Tracker key: `v1s-13-11-closure-report-windows`.

**Refs:** FR90, Story 4.4, Story 7.2/7.3, Stories 13.3 / 13.5, UI-3 lot 1 backend review. **Refonte absorption:** re-appliquer dans `reporting`/`commerce`.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-13-3, 2026-07-21] CloseDayService uses LocalDate.now() without Clock [CloseDayService.java:56-58] — inject Clock like DayClosureAutoScheduler — **fixed v1s-13-11 (commit 19efbc8): CloseDayService takes the shared `Clock` bean (`Instant.now(clock)`); fixed-clock tests for closedAt/receivedAt and the 7-day gate**
- [x] [Deferred-from: CR v1s-13-3, 2026-07-21] parseTime silently falls back to 20:00 [DayClosureAutoScheduler.java:141-149] — validate format on PUT /tenant/preferences (422) — **resolved v1s-13-11 (commit 19efbc8): validation already enforced on PUT /tenant/report-preferences (`@Pattern` HH:mm:ss on eodReportTime and weeklyReportTime since fbe7159 → 422 VALIDATION_ERROR), now tested for both fields; stored values are a TIME column, so the schedulers' fallback is documented as defensive only**
- [x] [Deferred-from: CR v1s-13-3, 2026-07-21] Closure at 20:00 excludes 20:01-23:59 sales [CloseDayService.java:67-68] — PO decision: close at eodReportTime or 23:59; document in PRD/AC — **decision D1 v1s-13-11 (commit 19efbc8, PRD FR51): close at eodReportTime (or the manual tap); a closure covers every sale since the previous closure plus late-synced ones (ClosureWindow), so 20:01–23:59 sales go to the next closure — none excluded; 23:59 rejected (one closure per store and day); test proves it**
- [x] [Deferred-from: CR v1s-13-5, 2026-07-22] Cancel/correct allowed after day closure or weekly report [ValidateSaleService.java, CorrectSaleService.java] — PO decision (block/warn/regenerate), then guard e.g. SALE_DAY_CLOSED — **decision D3 v1s-13-11 (commit 19efbc8, PRD FR90): warn, not block — FR90 has no time limit and a 20:00 auto-close would make the morning-after fix impossible; closures and sent reports stay immutable (no regeneration), the action stays audited, and the app's sale detail shows « Cette vente fait partie d'une journée déjà clôturée… » above Annuler/Corriger; no SALE_DAY_CLOSED guard**
- [x] [Deferred-from: CR UI-3 lot 1 backend, 2026-10-11] Weekly report misses Sunday 20:00-23:59 sales [WeeklyReportScheduler.java:124] — pre-existing; decide window with EOD decision — **decision D2 + fix v1s-13-11 (commit 19efbc8, PRD FR52): `WeeklyReportWindowResolver` chains each weekly report on the store's previous one with the ClosureWindow occurrence/arrival rule (end = generation time, or Sunday 23:59:59 when generated after the week; persisted createdAt = receivedUntil; no migration); scheduler and manual trigger share it; already-generated guard now per store**

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 13.12: Hygiène du provisioning de schéma tenant — **[C]**

As a Toor, I want tenant-schema provisioning to be consistent (single validation helper, complete DDL, idempotent constraints) and its failures visible, so that a tenant never ends up with a half-provisioned schema.

**Scope:** Converge schema helpers on TenantSchema, complete DDL_USERS, idempotent V1 CHECK constraints, make TenantSchemaSyncService failures visible. Tracker key: `v1s-13-12-schema-provisioning-hygiene`.

**Refs:** NFR10, Stories 10.1 / 10.2 (refonte), Stories 12.5 / 13.1. **Refonte absorption:** native — le provisioning est repris dans `identity`/infra.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-12-5, 2026-07-21] D7 4 schema helpers not migrated to TenantSchema [SchemaAwareMultiTenantConnectionProvider, TenantSchemaSyncService, TenantSchemaProvisioner] — converge on TenantSchema validation — **fixed v1s-13-12 (fb880b6): every Java check goes through `TenantSchema.isValid/validate` (connection provider, sync service, provisioner ×3, migration runner, TenantSchemaAdapter, JdbcNotificationCooldownAdapter, MultiStoreStockRepositoryAdapter, DeltaStockStrategy, SalesTrendDetectionScheduler, SubscriptionExpiryService), each keeping its throw / no-op / skip contract; `TenantSchema.java` is the only Java definition of the regex (SyncErrorLogCleanupJob's SQL predicate deliberately stays in SQL)**
- [x] [Deferred-from: CR 10-2, 2026-06-19] DDL_USERS in TenantSchemaProvisioner incomplete [TenantSchemaProvisioner.java] — failed_attempts/locked_until only added at first login by TenantSchemaSyncService — **fixed v1s-13-12 (fb880b6): `DDL_USERS` carries `failed_attempts INTEGER NOT NULL DEFAULT 0` + `locked_until TIMESTAMPTZ`; idempotent `DDL_USERS_MIGRATE_FAILED_ATTEMPTS/LOCKED_UNTIL` run after it in provision() and in the sync's `ensureRequiredIndexes` for existing tenants; verified on real PG**
- [x] [Deferred-from: CR v1s-13-1, 2026-07-21] V5 per-tenant column relies on boot-time TenantSchemaSyncService [V5__stock_optimistic_lock.sql, TenantSchemaSyncService.java:230-232] — silent sync failure -> 'column version does not exist'; log ERROR / fail visibly — **fixed v1s-13-12 (fb880b6): TenantSchemaMigrationRunner (now a SmartLifecycle, phase 0: before the web server and @Scheduled registration) runs the full sync (`TenantSchemaSyncService.syncNow`, propagates failures; per-schema advisory xact lock + 10s lock_timeout) for every non-deleted tenant whose schema exists, so per-tenant columns exist before any request or scheduler; the SALE_CANCELLED movement_type migration also runs in the sync for old tenants; per-tenant failure → ERROR log naming the schema + `failed` count + ERROR summary, startup continues (decision: no abort, one broken tenant must not take all tenants down — NFR10); request-path `syncIfNeeded` unchanged (ERROR + retry next request)**
- [x] [Deferred-from: legacy CR 10.1 flyway-baseline, 2026-06-18] V1 inline CHECK constraints not idempotent [V1__baseline_public.sql] — constraints skipped on Hibernate-created tables; DO $$ blocks in a new migration — **fixed v1s-13-12 (fb880b6): `V13__idempotent_check_constraints.sql` (re)creates the 6 V1 CHECK constraints by name in `DO $$` blocks (skip if table missing; drop-if-exists then re-add so stale Hibernate-era value lists are fixed; legacy violating rows → `NOT VALID` + `RAISE WARNING`); `stock_movements_movement_type_check` now includes SALE_CANCELLED; Testcontainers PG16 test covers fresh DB, dropped constraints, re-run and bad row**

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

---

# Epic 14: Récupération scope V1 — Gaps fonctionnels (Pistes A/B/C)

Implémenter les FR documentées mais absentes (~22 FR). Tag par story.

## Story 14.1: FR22 — Variantes produit (taille/couleur) — **[B]**

As a proprietor, I want product variants with distinct stock per combination (FR22).

**Refs:** FR22, Story 2.1 AC4, catalog F5. **Refonte absorption:** native — gros bounded context, mieux dans la structure modulaire `catalog`.

**AC:**
**Given** `catalog/variant/` empty
**When** Variant bounded context built
**Then** `Variant` entity+repo+controller+service; axes Taille/Couleur; distinct `stock_levels` row per combination
**And** unique constraint → `(product_id, variant_id, store_id)` (drop `uq_stock_level(product_id, store_id)`)
**And** card shows "X variantes" from real count; Flutter `ProductFormPage` variant toggle + axis editors

**Doc:** Story 2.1 AC4 (confirm scope Vêtements-first vs all). **TDD:** `shouldCreateDistinctStockPerVariant()`, `shouldEnforceUniqueConstraintWithVariant()`; Flutter `shouldShowVariantBadgeFromRealCount()`.

**Items routed:**
- [ ] [Deferred-from: build-auto v1s-13-8, 2026-10-11] Flutter Drift `stock_levels` unique key is (product_id, store_id) only [keevo/app/lib/core/storage/stock_levels_table.dart:31-33] — extend to (product_id, variant_id, store_id) with a Drift migration when variants land; v1s-13-8 made offline inventory validation fall back to the (product, store) row meanwhile

## Story 14.2: FR44 — Leaderboard vendeurs — **[C]**

As a proprietor, I want a sales leaderboard ranking employees (FR44, hidden for employees — UX16).

**Refs:** FR44, UX16, B-HIGH-7. **Refonte absorption:** V1-shippable puis refonte ré-applique (Strategy pattern existe).

**AC:**
**Given** no leaderboard
**When** `GET /api/v1/reporting/leaderboard` (OWNER-only) added
**Then** ranks employees by CA / sales count / avg basket (Strategy, mirrors `StoreRankingStrategy`); Flutter OWNER-only view + toggle; EMPLOYEE → 403

**Doc:** Epic 4 Story 4.6 artifact. **TDD:** `shouldRankEmployeesByRevenue()`, `shouldReturn403ForEmployee()`.

## Story 14.3: FR79 — Dashboard revenus (MRR/churn/conversion) — **[B]**

As a Toor, I want a revenue dashboard (FR79).

**Refs:** FR79, B-HIGH-7, Epic 9 Story 9.3. **Refonte absorption:** native — nouveau module `admin/revenue`, mieux en modulaire.

**AC:**
**Given** no MRR/churn code
**When** `admin/revenue/` module added
**Then** MRR/ARR/active-paid/churn/new-paid from `public.tenants` + per-tenant `subscriptions`; 12-month MRR trend + churn table + CSV export + date-range; `GET /api/v1/admin/revenue/...` (SUPER_ADMIN); dashboard `/revenus` page + client

**Doc:** Epic 9 Story 9.3 artifact. **TDD:** `shouldComputeMrrFromActivePaidSubscriptions()`, `shouldComputeMonthlyChurnRate()`.

## Story 14.4: FR82 + FR83 — Push global & santé système — **[B]**

As a Toor, I want broadcast push + platform health monitoring (FR82, FR83).

**Refs:** FR82/83, B-HIGH-7, Epic 9 Story 9.5. **Refonte absorption:** native — nouveaux modules `admin/notification` + `admin/system-health`.

**AC:**
**Given** only `FirebaseInitializer`
**When** modules added
**Then** `POST /api/v1/admin/notifications/broadcast` fans out FCM + `GlobalPushSentEvent` + 90-day history
**And** `GET /api/v1/admin/system/health` aggregates Actuator (uptime, p50/p95/p99, 5xx rate, DB conns, sync queue depth) + critical error logs + banners
**And** dashboard "Diffusion globale" form + "Santé système" page

**Doc:** Epic 9 Story 9.5 artifact. **TDD:** `shouldBroadcastFcmToAllDeviceTokens()`, `shouldAggregateActuatorMetricsForHealth()`.

**Contrepartie mobile (Flutter):** le push global FR82 atterrit comme une notification FCM standard — `FcmService` (Epic 8.0) doit afficher les notifications `type: GLOBAL_ANNOUNCEMENT` (titre/body du Super Admin) avec deep-link optionnel ; pas d'action spécifique requise côté employee (juste l'affichage foreground/background, ties Epic 16.2 iOS Darwin).

## Story 14.5: FR91 + FR92 — Suppression compte (RGPD) & feedback — **[C]**

As a user, I want to delete my account/data and submit feedback (FR91, FR92).

**Refs:** FR91/92, frontend C1/C2 + H1/H2, Epic 8. **Refonte absorption:** V1-shippable (compliance) puis refonte ré-applique.

**AC:**
**Given** no delete action; feedback tile stub
**When** implemented
**Then** `POST /api/v1/account/delete` (OWNER) 30-day grace (cancelable), purges `kv_xxxxxx` + `public.tenants` on expiry
**And** `POST /api/v1/feedback` `{type, description, screenContext}` (offline-queueable)
**And** Flutter `AccountPage` "Supprimer mon compte" + irreversible warning + 30-day banner; feedback tile opens real form

**Doc:** Epic 8 Stories 8.4/8.5 artifacts. **TDD:** `shouldScheduleAccountDeletionWith30DayGrace()`, `shouldCancelDeletionWithinGracePeriod()`, `shouldSubmitFeedbackWithScreenContext()`.

## Story 14.6: FR7 + FR65 + FR67 — Tutoriels vidéo, invitation WhatsApp, activité employé — **[C]**

As a proprietor, I want in-app tutorials, WhatsApp invites, employee activity tracking (FR7, FR65, FR67).

**Refs:** FR7/65/67, frontend H1/H2/H3 + H6. **Refonte absorption:** V1-shippable puis refonte ré-applique.

**AC:**
**Given** FR7 stub, FR65 temp-password-only, FR67 absent
**When** implemented
**Then**:
- FR7: `video_player`/`chewie` + "Aide & Tutoriels" page (~2min FR videos per module)
- FR65: `url_launcher` WhatsApp share/intent from `create_employee_page` (`wa.me` with temp-password)
- FR67: `GET /api/v1/employees/{id}/activity` (OWNER) last login, today/week sales, active-session; `employee_card` stats; inactive>48h badge + WhatsApp reminder

**Doc:** Epic 1 (FR7), Epic 3 Story 3.5 (FR65), Epic 8 Story 8.2 (FR67). **TDD:** `shouldPlayEmbeddedVideoTutorial()`, `shouldOpenWhatsAppShareLinkForInvite()`, `shouldShowEmployeeActivityStats()`.

## Story 14.7: FR55 — Export PDF/Excel avec plan-gating — **[C]**

As a proprietor on a paid plan, I want to export reports in PDF/Excel (FR55).

**Refs:** FR55, reporting F2, Epic 7 Story 7.4. **Refonte absorption:** V1-shippable puis refonte (le `PlanGate` port porte).

**AC:**
**Given** no export endpoints/deps
**When** export added
**Then** `GET /api/v1/reports/{id}/export?format=pdf|xlsx` (OWNER) generates file, plan-gated (FREE → 403 `PLAN_LIMIT_EXCEEDED` + upgrade CTA)
**And** Flutter locked badge for FREE ("Disponible sur Plan Premium")
**And** a `PlanGate` port resolves tenant plan from `TenantContext` (reused for FR64 later)

**Doc:** Epic 7 Story 7.4 plan-gate pattern. **TDD:** `shouldReturnPdfForPaidPlan()`, `shouldReturn403ForFreePlan()`.

## Story 14.8: Cassures d'intégration API (X-CRIT/HIGH/MED) — **[A]**

As a Toor, I want Flutter and backend API contracts to fully align (ARCH25/28) — runtime breaks fixed on V1.

**Refs:** X-CRIT-1, X-HIGH-2, X-MED-3/4/5, ARCH25/28/29. **Refonte absorption:** cherry-pick + envelope native on refonte.

**AC:**
**Given** Flutter calls `PATCH /api/v1/products/{id}/unarchive` (no backend endpoint)
**When** endpoint added (delegate to `UnarchiveProductUseCase`, mirror `archive`)
**Then** Flutter unarchive returns 200 (or routes through `syncService.queueOperation('UNARCHIVE_PRODUCT')`)
**And** transfer-history: Flutter sends `destination` (not `dest`); `productId` filter added backend or dropped client
**And** `ReportsDeltaProvider` implements key `"reports"` (reports sync offline)
**And** `api_service.dart:38` reads `data['domainCode']` (not `data['code']`)
**And** `ApiResponseWrapper` adds `meta{page,total}` for paginated (standardize the 5+ shapes)

**Doc:** ARCH28 envelope + Story 2.1/3.3/5.2. **TDD:** `shouldReturn200OnUnarchiveEndpoint()`, `shouldFilterTransferHistoryByDestination()`, `shouldSyncReportsOffline()`, `shouldReadDomainCodeFromEnvelope()`.

## Story 14.9: FR20 — Bannières trial/suspension + branche session-révoquée — **[A]**

As a proprietor, I want trial/suspension banners displayed + session teardown when revoked (FR20, FR68 client).

**Refs:** FR20, F-HIGH-5/8, B-HIGH-5 (ties 12.2/12.7). **Refonte absorption:** re-appliquer Flutter.

**AC:**
**Given** `account_status_provider.dart:26` stub; `SuspensionBanner` unmounted
**When** provider implemented
**Then** reads JWT/subscription (trial days left, plan, suspended); `SuspensionBanner` mounted at top of screens
**And** `AuthInterceptor.onError` `ACCOUNT_INACTIVE`/`SESSION_REVOKED` branch → `_expireSession()` + "Votre accès a été suspendu…" (ties 12.2 backend + 12.7 client)

**Doc:** Story 1.6 (FR20). **TDD:** `shouldDisplayTrialExpiryBanner()`, `shouldDisplaySuspensionBanner()`, `shouldTearDownSessionOnAccountInactive()`.

## Story 14.12: Réinitialisation mot de passe oublié (OWNER + EMPLOYEE) via OTP WhatsApp — **[C]**

As a user who forgot my password (OWNER or EMPLOYEE),
I want to reset it autonomously via a one-time WhatsApp code,
So that I regain access without the owner resetting it for me (FR3b, new).

**Refs:** spec `_bmad-output/implementation-artifacts/spec-mot-de-passe-oublie.md`, new FR3b, Story 1.3 (forgot/reset AC), ARCH23 (WhatsAppPort.sendOtp). **Depends on:** Story 12.2 (revokeAllSessions). **Refonte absorption:** re-implement natively dans `identity/auth` modulaire.

**AC:**
**Given** no forgot-password flow exists; `change-password` requires `currentPassword`
**When** the WhatsApp-OTP reset is implemented
**Then**:
- `POST /api/v1/auth/forgot-password` `{phoneNumber}` (public) — sends 6-digit OTP via `WhatsAppPort.sendOtp` if the user exists; **always returns 200** (anti-enumeration); rate-limited 1/min + 5/hour per phone
- `POST /api/v1/auth/reset-password` `{phoneNumber, code, newPassword}` (public) — validates OTP (hashed, single-use, TTL 10 min), locks after 5 failed attempts; sets `passwordHash` (bcrypt 12), `passwordChangeRequired=false`, `revokeAllSessions` across **all** memberships (ties 12.2)
- New `public.password_reset_tokens` table (cross-tenant; OTP hashed, never plaintext at rest)
- `WhatsAppPort` gains `sendOtp(phone, code)` (FR message "Votre code… expire dans 10 min")
- Flutter: login "Mot de passe oublié ?" → `/auth/forgot-password` (IntlPhoneField) → `/auth/reset-password` (code + new password, min ≥8/≥1 chiffre) → redirect to login
- Events `PasswordResetRequestedEvent` + `PasswordResetEvent` → audit immuable (FR84); no enumeration leak in logs

**Doc:** `requirements-inventory.md` new FR3b + Story 1.3 forgot/reset AC + ARCH23 sendOtp. **TDD:** 11 tests in spec §8.

---

## Story 14.11: Édition complète d'un employé par le OWNER — **[C]**

As a proprietor, I want to edit ALL of an employee's information (name, phone, password, role, store),
so that I can correct/update team accounts without recreating them (FR66 extended).

**Refs:** spec `_bmad-output/implementation-artifacts/spec-edition-complete-employe.md`, FR66 (edit), Story 3.5 (new AC8), Story 8.6 (password scope). **Refonte absorption:** re-implement natively dans `identity/employee` + `identity/auth` (User/membership) modulaires.

**AC:**
**Given** the OWNER can only create/reassign/deactivate/regenerate-password (no edit of name/phone/role/password)
**When** the unified edition is implemented
**Then**:
- `PATCH /api/v1/employees/{id}` (OWNER-only) edits firstName/lastName/phoneNumber/storeId (partial update; phone re-validates uniqueness cross-tenant + E.164 format → 409 `PHONE_ALREADY_REGISTERED`)
- `PATCH /api/v1/employees/{id}/role` (OWNER-only) sets `OWNER|EMPLOYEE` on `user_tenant_memberships`; verrous anti-lockout (403 `CANNOT_CHANGE_OWN_ROLE` self, 403 `CANNOT_DEMOTE_LAST_OWNER`); `revokeAllSessions` (ties 12.2)
- `POST /api/v1/employees/{id}/password` (OWNER-only) sets a chosen password (bcrypt 12, min ≥8/≥1 chiffre) + forces `passwordChangeRequired=true` + `revokeAllSessions` (ties 12.2)
- Each edition emits `EmployeeUpdatedEvent`/`EmployeeRoleChangedEvent`/`EmployeePasswordSetEvent` → audit immuable (FR84/NFR13)
- Flutter: employee detail "Modifier" form (IntlPhoneField, store dropdown, role dropdown) + "Définir le mot de passe" section + confirm dialogs; self-role not editable in UI

**Doc:** `requirements-inventory.md` FR66 (edit) + Story 3.5 AC8 (new) + Story 8.6 (password scope note). **TDD:** 16 tests listed in spec §7.

---

## Story 14.10: Notif owner sur actions employé (produit + stock manuel) — **[A]**

As a proprietor, I want to be directly notified (push + WhatsApp) when an employee creates a product or manually modifies stock (new FR, spec-aligned).

**Refs:** spec `_bmad-output/implementation-artifacts/spec-notif-owner-actions-employe.md`, FR21 (edit), new FR65b, Story 2.1 AC8 (new), Story 2.3 AC7 (new), Story 2.4 AC9 (extend). **Refonte absorption:** re-implement natively (events portent les champs, listeners dans `messaging`).

**AC:**
**Given** spec decisions D1–D4 (ACTIVE directly, Entry+Adjust, Push+WhatsApp, Batching consolidé)
**When** two listeners implemented
**Then**:
- `ProductCreatedEvent` + `ProductCreatedProgressivelyEvent` gain `actorRole`/`actorName`/`storeName`; `ProductCreationNotificationListener` catches both, skips `OWNER`, batches per-employee per-window, emits `EMPLOYEE_PRODUCT_CREATED` / `_BATCH`
- `StockAdjustedEvent` gains `actorRole`/`actorName`/`storeName`; `StockModificationNotificationListener` catches `STOCK_ENTRY`/`ADJUSTMENT` by EMPLOYEE, skips OWNER + SALE/transfer events, batches, emits `EMPLOYEE_STOCK_MODIFIED` / `_BATCH`
- `notifyOwners(...)` `channel=BOTH` → FCM + `WhatsAppPort` to owner phone, best-effort (no rollback)
- Fenêtre `keevo.notification.employee-action-window-min` (default 5 min), per-employee, `ConcurrentHashMap` pattern from `StockAlertNotificationListener`

**And** the 13 TDD tests from spec §6 written RED-first
**And** CSV import stays on `CsvImportCompletedEvent` (no double-notify)

**Doc:** `requirements-inventory.md` (FR21 edit + FR65b new); Story 2.1 AC8 + Story 2.3 AC7 + Story 2.3 AC5/AC6 (event fields) + Story 2.4 AC9 (listener rename + cross-ref). **TDD:** 13 tests spec §6.1/§6.2.

**Contrepartie mobile (Flutter):** réception FCM des types `EMPLOYEE_PRODUCT_CREATED`/`EMPLOYEE_PRODUCT_CREATED_BATCH`/`EMPLOYEE_STOCK_MODIFIED`/`EMPLOYEE_STOCK_MODIFIED_BATCH` → `FcmService` mappe le `type` à une icône + deep-link (`/products/{productId}/edit`, `/stock/history`) ; `onMessageOpenedApp`/cold-start appelle `router.go(payload.deepLink())` ; cloche/notification history affiche ces notifs (ties Epic 16.1/16.2) ; le OWNER reçoit push + WhatsApp (le WhatsApp n'a pas de contrepartie mobile — c'est un message externe).

## Story 14.15: Durcissement Category (500 → 404, événements d'audit, atomicité repo Flutter) — **[C]**

As a Toor, I want category operations to fail cleanly (404 instead of 500), be audited, and be atomic on the device, so that the category feature matches the quality bar of products.

**Scope:** Category IAE->500 (audit M6) + not-found test, audit events on toggle/createCustom, Flutter category repo transaction/atomicity/StateError, sub-category cascade decision. Tracker key: `v1s-14-15-category-hardening`.

**Refs:** audit-coherence-2026-10-11 M6, Stories 14.14 / 15.3 / 15.5, Story 13.4. **Refonte absorption:** re-appliquer dans `catalog` + Flutter.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] DELETE /categories/{id} not-found -> 500 [JpaCategoryRepository.java:118] — IllegalArgumentException falls to generic 500 handler (audit-coherence-2026-10-11 M6); map to DomainException 404 + test — **resolved 2026-10-12 (v1s-14-15)**: `CATEGORY_NOT_FOUND` (404) on delete/toggle/rename/create-parent, DELETE via `ToggleCategoryUseCase.deactivate`; CategoryControllerTest + CategoryUseCasesTest
- [x] [Deferred-from: v1s-15-5 new defers, 2026-07-23] D2 Category toggleActive/createCustom not audited [JpaCategoryRepository.java:79,96] — emit domain event + audit listener; same story as the category 500s — **resolved 2026-10-12 (v1s-14-15)**: Category{Created,ActiveChanged,Renamed}Event from the use cases (REST + sync) → `CATEGORY_CREATED/ACTIVATED/DEACTIVATED/RENAMED`; AuditEventListenerCategoryTest
- [x] [Deferred-from: CR v1s-14-14, 2026-07-24] Category read-check-write not transactional (dup TOGGLE ops) [category_repository_impl.dart:96-118,141-161] — wrap in _database.transaction() — **resolved 2026-10-12 (v1s-14-15)**: read+check+write+queue in one Drift transaction for create/toggle/rename/delete
- [x] [Deferred-from: CR v1s-14-14, 2026-07-24] Category Drift write and queueOperation not atomic [category_repository_impl.dart:141-161] — same transaction as the write (cf. v1s-13-4) — **resolved 2026-10-12 (v1s-14-15)**: queue in the write's transaction, push after commit; rollback tests on queue failure
- [x] [Deferred-from: CR v1s-14-14, 2026-07-24] getSingle() StateError surfaces raw in UI [category_repository_impl.dart:144-146; categories_page.dart:128-139] — getSingleOrNull + user message — **resolved 2026-10-12 (v1s-14-15)**: `CategoryNotFoundException` (« Catégorie introuvable ou supprimée. ») via `appErrorMessage`; delete of a missing row = no-op
- [x] [Deferred-from: CR v1s-14-14, 2026-07-24] No deactivation cascade to sub-categories [category_repository_impl.dart] — PO decision; confirmation dialog only mentions products — **rejected 2026-10-12 (v1s-14-15, spec D3)**: no V1 surface creates sub-categories (onboarding seeds roots only, app `create()` never sends parentId, POS lists roots); cascade belongs to whichever story introduces a sub-category UI

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 14.16: Rapport de clôture WhatsApp envoyé au vrai propriétaire — **[C]**

As a Toor, I want the day-closure WhatsApp report sent to the tenant owner's own number, never to a hardcoded fallback, so that one tenant's sales figures can never reach an unrelated phone.

**Scope:** `DayClosureWhatsAppListener` resolves the recipient through the owner lookup (like `ReportHistoryService` after v1s-15-7 D1) and skips/marks the delivery when no owner phone exists, instead of `System.getProperty("keevo.owner.whatsapp", "+243000000000")`. Tracker key: `v1s-14-16-day-closure-owner-phone`.

**Refs:** Story 15.7 (D1), Story 13.11. **Refonte absorption:** re-appliquer dans `messaging`.

**Items:**
- [x] [Deferred-from: v1s-15-7, 2026-10-11] Hardcoded owner phone fallback [commerce/sale/application/service/DayClosureWhatsAppListener.java:43,73] — every day-closure report goes to `keevo.owner.whatsapp` / `+243000000000`; resolve the owner's phone per tenant, never guess (skip + log when missing), with tests — **resolved 2026-10-12 (v1s-14-16)**: owner lookup per tenant, skip + warn, AFTER_COMMIT, 15 tests; backend sweep found no other hardcoded recipient; follow-ups → v1s-14-17

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

---

## Story 14.17: Résolution unique et fiable du destinataire propriétaire — **[C]**

As a Toor, I want every owner-directed WhatsApp message (reports, alerts, notifications) to resolve its recipient through one shared rule, so that the choice of owner and phone is deterministic, active-only and identical everywhere.

**Scope:** Introduce one owner-recipient resolver (e.g. a `UserRepository` default method or a small `OwnerPhoneResolver`) returning the WhatsApp phone of the tenant's active OWNER — deterministic when several owners exist, skipping deactivated users, trimming and rejecting blank phones — and use it in `DayClosureWhatsAppListener`, `ReportHistoryService`, `DailyReportGenerator`, `MultiStoreSummaryService` and the messaging notification listeners. Tracker key: `v1s-14-17-owner-recipient-resolution`.

**Refs:** Story 14.16 (review pass 1), Story 15.7 (D1), Story 14.11 (role promotion makes several OWNER memberships possible). **Refonte absorption:** re-appliquer dans `identity`/`messaging`.

**Items:**
- [x] [Deferred-from: v1s-14-16, 2026-10-11] Nondeterministic owner when several OWNER memberships exist [identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java:42-50] — `LIMIT 1` without `ORDER BY`; recipient may change between messages. Settle with a deterministic order (or primary owner) + DB test — **resolved 2026-10-12 (v1s-14-17)**: `ORDER BY m.created_at, u.id` (primary = oldest active OWNER membership) + OwnerLookupPostgresIntegrationTest
- [x] [Deferred-from: v1s-14-16, 2026-10-11] Deactivated owner user still receives reports [identity/auth/adapter/out/persistence/impl/UserRepositoryAdapter.java:48] — query filters `m.is_active` but not `u.is_active`; decide whether a deactivated owner must be skipped, add filter + test — **resolved 2026-10-12 (v1s-14-17)**: deactivated owner skipped (`u.is_active` in SQL + adapter check) + DB/unit tests
- [x] [Deferred-from: v1s-14-16, 2026-10-11] Owner phone sent untrimmed [reporting/report/application/service/ReportHistoryService.java:68, commerce/sale/application/service/DayClosureWhatsAppListener.java] — strip before blank check in the shared resolver — **resolved 2026-10-12 (v1s-14-17)**: OwnerRecipientResolver normalizes via PhoneNumbers.normalize, accepts only `+<digits>`
- [x] [Deferred-from: v1s-14-16, 2026-10-11] Active EOD path sends to a blank phone instead of IN_APP_ONLY [reporting/report/application/service/DailyReportGenerator.java:101-105] — no blank filter; `AbstractReportGenerator` only checks null — **resolved 2026-10-12 (v1s-14-17)**: blank → resolver empty → IN_APP_ONLY (+ AbstractReportGenerator blank guard), real-resolver tests Daily/Weekly
- [x] [Deferred-from: v1s-14-16, 2026-10-11] Owner-phone rule duplicated in ~9 call sites [messaging/notification/application/listener/*, reporting/report/application/service/*] — replace with the shared resolver so the rules above cannot drift — **resolved 2026-10-12 (v1s-14-17)**: 11 senders use OwnerRecipientResolver; blocking OwnerRecipientResolutionArchTest; account-deletion fan-out intentionally kept (spec D4)

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

---

# Epic 15: Discipline de tests & dette technique (Pistes B/C)

Satisfaire ARCH11–14 + nettoyer la dette. Tags variables.

## Story 15.1: Base Testcontainers PostgreSQL + migration @DataJpaTest — **[B]**

As a Toor, I want repository tests against a real PostgreSQL (ARCH12).

**Refs:** ARCH12, tests F1/F2, deferred [10-1]. **Refonte absorption:** native — infra de tests posée avec la refonte.

**AC:**
**Given** 4 `@DataJpaTest` use H2; Testcontainers file connects to `localhost:5444`
**When** `@Testcontainers` base with `@Container static PostgreSQLContainer<>("postgres:16")` introduced
**Then** 4 `@DataJpaTest` + `FlywayBaselineIntegrationTest` + `TenantIsolationIntegrationTest` migrate
**And** `docker-java-core` skew resolved (pin all 3 modules)
**And** CI runs without pre-existing PG

**Doc:** ARCH12 confirmé. **TDD:** the migrated tests.

## Story 15.2: Flutter integration_test E2E + riverpod_test — **[B]**

As a Toor, I want E2E tests driving the app (ARCH13/14).

**Refs:** ARCH13, tests F2. **Refonte absorption:** native — posé avec le Flutter refonte.

**AC:**
**Given** `integration_test/` empty; deps absent
**When** E2E added
**Then** `pubspec.yaml` adds `integration_test` + `riverpod_test`; `app_test.dart` covers login→tenant-select→sale→day-close→offline-sync→inventory; runs on emulator in `flutter-ci.yml`

**Doc:** ARCH13 confirmé. **TDD:** the E2E scenarios.

## Story 15.3: Tests RED manquants — **[C]**

As a Toor, I want untested shipped classes to have tests (ARCH14).

**Refs:** ARCH11/14, tests F4/F9/F10. **Refonte absorption:** continuous — V1-patch pour les classes buggy, refonte pour les nouvelles.

**AC:**
**Given** `CategoryController`, `CompleteTransferService`, `DefaultTransferValidationStrategy`, 3 WhatsApp adapters, 7 profitability strategies, `ReportHistoryService`, `EmployeeRepositoryAdapter`, `JdbcProfitabilityRepository`, `StockMovementRepositoryAdapter` zero tests
**When** RED tests written first
**Then** each gets a unit/slice test; `CategoryController` `@WebMvcTest`; WhatsApp adapters Mockito; profitability strategies sort-correctness

**Doc:** stories 2.1/2.3/7.x/8.0. **TDD:** the new tests.

## Story 15.4: ArchUnit/Modulith mode ratchet — **[B]**

As a Toor, I want module-boundary violations to fail the build (ARCH5/7/10, Epic 12.6).

**Refs:** ARCH5/7/10, tests F3, deferred [10-6]. **Refonte absorption:** native — la ratchet est l'outil même de la refonte.

**AC:**
**Given** `KeevoArchUnitBaselineTest` + `ApplicationModulesBaselineTest` warning-mode
**When** ratchet enabled
**Then** known-violation set asserted; NEW violations fail; `allowedDependencies` realigned; Rule 1 covers `shared.infrastructure.persistence.entity`

**Doc:** Epic 12.6 confirmé. **TDD:** ratchet baseline test.

## Story 15.5: Réconcilier deferred-work + code mort + TODOs — **[C]**

As a Toor, I want trackers to reflect reality + dead code removed.

**Refs:** tests F7/F8/F13, deferred stale, dead `common`/`products`/`UserSyncStateDdlInitializer`/`CategoryRepository` TODOs. **Refonte absorption:** continuous.

**AC:**
**Given** deferred-work lists ~5 fixed-but-still-listed (cancelSale RBAC, B3.1/B3.2/B3.4, 11-3 audit)
**When** reconciled
**Then** fixed → "resolved"; `sprint-status.yaml` 11-3 → `done`; `UserSyncStateDdlInitializer` removed; empty `com/keevo/common` + `lib/features/products` removed; `CategoryRepository`/`JpaCategoryRepository` TODOs cleaned

**Doc:** `deferred-work.md` + `sprint-status.yaml` (the reconciliation). **TDD:** build green after removals.

## Story 15.6: Standardiser enveloppe pagination (ARCH28) — **[B]**

As a Toor, I want one pagination envelope (ARCH28).

**Refs:** X-MED-5, ARCH28, ties 14.8. **Refonte absorption:** native — standardisation faite avec la refonte.

**AC:**
**Given** 5+ pagination shapes
**When** `ApiResponseWrapper` extended
**Then** paginated responses use `{data, meta:{page, total, size, totalPages}}`; all paginated endpoints migrate; Flutter + dashboard unwrap uniformly

**Doc:** ARCH28. **TDD:** contract test per paginated endpoint.

## Story 15.11: Base de tests d'intégration Testcontainers + tests Postgres en attente — **[C]**

As a Toor, I want a real-PostgreSQL test base (Testcontainers) and the integration tests that were waiting for it, so that JPA/Flyway/concurrency behaviour is verified in CI instead of by inspection.

**Scope:** Real-Postgres test base (Testcontainers + docker-java alignment) and the tests waiting for it: Flyway baseline/V2 idempotency fixes, @Modifying+@Version, concurrent transfer complete, sale merge-in-place, transfer replay. Tracker key: `v1s-15-11-testcontainers-integration-tests` (numéro 15.6 partagé avec « Standardiser enveloppe pagination » ci-dessus ; les clés sprint-status diffèrent).

**Refs:** ARCH12, Story 15.1, Stories 10.1 / 10.2 (refonte), Stories 13.1 / 13.5, spec-stock-transfer-offline-sync-flow-fix. **Refonte absorption:** native — infra de tests posée avec la refonte.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR 10-1, 2026-06-19] docker-java override only pins api+transport [pom.xml:140-150] — check mvn dependency:tree for docker-java-core skew when Testcontainers is wired — **Resolved (v1s-15-11):** `mvn dependency:tree -Dincludes=com.github.docker-java` shows api, transport-zerodep and transport all at 3.4.1 and no docker-java-core (Testcontainers 1.20 does not use it), so nothing can drift; pins kept, check documented in the pom comment.
- [x] [Deferred-from: CR 10-2, 2026-06-19] v2IsIdempotent asserts migrationsExecuted==0 too broadly [FlywayBaselineIntegrationTest.java:198-201] — V4-V9 now exist; restrict assertion to V2 — **Resolved (v1s-15-11):** the test now asserts V2 ran exactly once on the fresh DB and is absent from a re-migrate's executed list (one flyway_schema_history row).
- [x] [Deferred-from: CR 10-2, 2026-06-19] @BeforeAll DROP DATABASE without pg_terminate_backend [FlywayBaselineIntegrationTest.java] — stale DB after crashed run — **Resolved (v1s-15-11):** `SharedPostgresContainer.freshDatabase()` terminates the database's backends before DROP/CREATE.
- [x] [Deferred-from: CR 10-2, 2026-06-19] @ActiveProfiles(flyway-test) without profile file [FlywayBaselineIntegrationTest.java] — misleading annotation — **Resolved (v1s-15-11):** annotation removed; the test is now plain JUnit on the Flyway API.
- [x] [Deferred-from: CR v1s-13-1, 2026-07-21] No real-EntityManager test for @Modifying + @Version [StockTransferSpringRepository.java:31-36] — needs Postgres/Testcontainers — **Resolved (v1s-15-11):** `TransferPersistenceIntegrationTest.modifyingClearsContextAndVersionRejectsStaleSave` (same-tx re-read sees COMPLETED/v+1, stale v0 save → ObjectOptimisticLockingFailureException; fails if clearAutomatically is removed).
- [x] [Deferred-from: CR v1s-13-1, 2026-07-21] No multi-thread test on CompleteTransferService [CompleteTransferService.java:50-104] — CountDownLatch concurrent complete — **Resolved (v1s-15-11):** `TransferPersistenceIntegrationTest.concurrentCompleteCreditsDestinationOnce` (8 threads, CountDownLatch start gate, destination credited once, 1 TRANSFER_IN, version 1).
- [x] [Deferred-from: CR v1s-13-5, 2026-07-22] JPA merge-in-place test never executed [SaleRepositoryAdapterTest.java] — needs real Postgres (JSONB) — **Resolved (v1s-15-11):** `SaleRepositoryAdapterTest` runs on the shared Testcontainers PostgreSQL (H2 and product-schema.sql removed) and is green.
- [x] [Deferred-from: legacy CR 10.1 flyway-baseline, 2026-06-18] FlywayBaselineIntegrationTest uses existing PG, not Testcontainers [FlywayBaselineIntegrationTest.java:32,51] — not CI-portable — **Resolved (v1s-15-11):** runs on `freshDatabase("keevo_flyway_baseline")` of the shared container, dropped @AfterAll; Hibernate validate is proven by `KeevoApplicationContextIntegrationTest`.
- [x] [Deferred-from: CR spec-stock-transfer-offline-sync-flow-fix, 2026-08-11] No persistence-level test of idempotent transfer replay [TransferSyncHandler.java, ExecuteTransferService.java] — real DB test that replay returns same id — **Resolved (v1s-15-11):** `TransferPersistenceIntegrationTest.replayReturnsSameTransferAndDecrementsOnce` (sequential replay + concurrent double-fire through TransferSyncHandler: same id, source decremented once per operation; fails without the advisory lock).
- [x] [Deferred-from: CR v1s-12-12, 2026-10-11] Auth/session concurrency only tested sequentially [AuthSessionPostgresIntegrationTest.java] — tryClaimAttempt cap, markConsumed once and the guarded user_sync_state upsert are single-statement atomic UPDATEs verified sequentially on real Postgres; add multi-connection (CountDownLatch) tests: 10 parallel wrong OTPs leave attempts ≤ 5, 2 parallel correct OTPs → one success, concurrent device claim never reassigns user_id — **Resolved (v1s-15-11):** `AuthSessionConcurrencyPostgresIntegrationTest` (10 parallel wrong OTPs → exactly 5 claims/attempts=5, 2 parallel correct OTPs → one consume, 8 concurrent device claims → one owner, never reassigned).
- [x] [Deferred-from: CR v1s-12-12, 2026-10-11] No running context-load test resolves the new `Clock` bean [ClockConfig.java, CachedTokenRevocationAdapter/PasswordResetRateLimiter/AuthTokenCleanupJob constructors] — the only @SpringBootTest classes (SectorTemplateFactoryTest, FlywayBaselineIntegrationTest) fail in the baseline (localhost:5444); once the Testcontainers base exists, a context-load test proves the auth beans wire — **Resolved (v1s-15-11):** `KeevoApplicationContextIntegrationTest` boots the full context on Testcontainers and asserts exactly one Clock bean exists (so every constructor-injected Clock is it; field identity checked on AuthTokenCleanupJob), the auth beans, Flyway V1..V13, ShedLock and tenant provisioning.

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 15.7: Trous de tests unitaires/slice backend — **[C]**

As a Toor, I want the unit/slice test gaps listed by the 12.x / 14.12 / 15.3 reviews closed (security slice, rate limiter with Clock, adapters, @MockitoBean), so that documented rules have real coverage.

**Scope:** Unit/slice test gaps listed by v1s-15-3, v1s-12-2/12-3/12-5 reviews, rate-limiter test, controller security slice, @MockBean->@MockitoBean. Tracker key: `v1s-15-7-backend-unit-test-gaps`.

**Refs:** ARCH11/13, Stories 12.1 / 12.2 / 12.3 / 12.5 / 14.12 / 15.3. **Refonte absorption:** native — les tests suivent les modules.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] @PreAuthorize never exercised in controller tests [CategoryControllerTest.java] — standaloneSetup mounts no security chain (40/41 controller tests); add security-slice tests — **Resolved v1s-15-7: new `CategoryControllerSecurityTest` (@WebMvcTest + real SecurityConfig): anonymous → 401, SUPER_ADMIN → 403 FORBIDDEN (class-level @PreAuthorize), OWNER/EMPLOYEE GET → 200, EMPLOYEE toggle/delete → 403 with use case/repository never called, OWNER delete → 204. CurrentActor follow-up (v1s-15-9 review) also covered: `SubscriptionControllerTest` (+anonymous 401, EMPLOYEE 200, no-authority 403), `AdminSubscriptionControllerTest` (+anonymous 401, EMPLOYEE 403), new `DeviceTokenControllerSecurityTest` and `ProductImportControllerSecurityTest`.**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] Malformed UUID param never tested on CategoryController [CategoryControllerTest.java] — add 400 MethodArgumentTypeMismatch test — **Resolved v1s-15-7: `CategoryControllerTest.toggleCategory_withMalformedUuid_returns400ValidationError` (400 `VALIDATION_ERROR`, use case never called).**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] StockMovementRepositoryAdapterTest variantId non-null mapping [StockMovementRepositoryAdapterTest.java] — add non-null variantId case — **Resolved v1s-15-7: `StockMovementRepositoryAdapterTest.save_withVariantId_mapsVariantIdBothWays` (entity passed to `save` and mapped domain both carry the variantId).**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] EmployeeRepositoryAdapterTest findByUserId empty branch [EmployeeRepositoryAdapterTest.java] — add Optional.empty case — **Resolved v1s-15-7: `EmployeeRepositoryAdapterTest.findByUserId_whenNotFound_returnsEmpty`.**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] EmployeeStatus.valueOf on corrupt data [EmployeeRepositoryAdapterTest.java] — unhandled IAE on unknown status; test + decide handling — **Decision v1s-15-7 (D2): keep fail-fast `EmployeeStatus.valueOf` — the tenant `employees.status` CHECK constraint (TenantSchemaProvisioner) makes an unknown value unstorable, and silently mapping it to ACTIVE/INACTIVE would grant or deny access on corrupt data. Contract pinned by `EmployeeRepositoryAdapterTest.findById_whenStatusUnknown_throwsIllegalArgumentException`.**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] FailoverWhatsAppAdapterTest untested combinations [FailoverWhatsAppAdapterTest.java] — Wassender blank + Twilio fails; Twilio unconfigured during failover — **Resolved v1s-15-7: `FailoverWhatsAppAdapterTest.sendReport_whenWassenderBlankAndTwilioFails_throwsWhatsAppDeliveryException` and `sendReport_whenWassenderFailsAndTwilioUnconfigured_throwsWithoutCallingTwilio`.**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] sanitizePhone '+'-prefix branch untested [TwilioWhatsAppAdapterTest.java] — add number without '+' — **Resolved v1s-15-7: `TwilioWhatsAppAdapterTest.sendReport_addsPlusPrefix_whenNumberHasNone` (`237699000080` → `whatsapp:+237699000080`).**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] JdbcProfitabilityRepositoryTest RowMapper never asserted [JdbcProfitabilityRepositoryTest.java] — invoke/assert the RowMapper — **Resolved v1s-15-7: the findRawByPeriod / findRawStoreByPeriod / findProductCosts / findTopStoreByUnits mappers were already captured and asserted; the only gap, findDailyMarginLast7, is covered by `findDailyMarginLast7_rowMapper_mapsDayAndDailyMargin`.**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] resendReport with null/blank owner phone untested [ReportHistoryServiceTest.java] — add case — **Resolved v1s-15-7 (D1, production fix): `ReportHistoryService.resendReport` no longer falls back to the hardcoded `+243000000000`; a missing owner or null/blank phone throws `WHATSAPP_DELIVERY_FAILED` before `resetForResend()` (no WhatsApp call, no save). Tests `resendReport_whenOwnerMissing/OwnerPhoneNull/OwnerPhoneBlank_throwsDeliveryFailed_andNeverSendsOrResets`. Same leak in DayClosureWhatsAppListener routed to `v1s-14-16-day-closure-owner-phone`.**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] resend of SENT/FAILED report untested [ReportHistoryServiceTest.java / ReportHistoryService.java:65] — documented intentional rule has zero coverage — **Resolved v1s-15-7: `ReportHistoryServiceTest.resendReport_whenAlreadySent_resendsAndResetsAttemptsToOne` and `resendReport_whenFailedAfterThreeAttempts_resendsAndResetsAttemptsToOne`.**
- [x] [Deferred-from: CR v1s-15-3, 2026-07-23] Reflection on JpaBaseEntity.createdAt in test [EmployeeRepositoryAdapterTest.java] — fragile coupling; use a builder/fixture — **Resolved v1s-15-7: `EmployeeRepositoryAdapterTest` uses a test-local `TimestampedEmployeeJpaEntity` subclass overriding `getCreatedAt()/getUpdatedAt()`; reflection removed.**
- [x] [Deferred-from: CR v1s-14-12, 2026-07-23] shouldBlockSixthRequestWithinOneHour tests nothing [PasswordResetRateLimiterTest.java:37] — inject Clock to test same-phone hourly cap — **Already resolved by v1s-12-12: `PasswordResetRateLimiterTest.shouldBlockSixthRequestWithinOneHour` drives a `MutableTestClock` (5 requests 61 s apart, 6th blocked, allowed again after the hour). No change.**
- [x] [Deferred-from: CR v1s-12-5, 2026-07-21] D4 default filter has jdbcTemplate=null in test setUp [JwtAuthFilterTest.java:50] — NPE trap for future EMPLOYEE tests — **Resolved v1s-15-7: `JwtAuthFilterTest.setUp()` builds the filter with the mocked JdbcTemplate; `filterWithJdbc()` returns that same instance.**
- [x] [Deferred-from: CR v1s-12-5, 2026-07-21] D6 no EMPLOYEE INACTIVE test with validate() [JwtAuthFilterTest.java] — add INACTIVE case — **Resolved v1s-15-7: `JwtAuthFilterTest.shouldRejectInactiveEmployeeOnValidatedSchema` (401 `ACCOUNT_INACTIVE`, chain not called, SQL on `"kv_abc123".employees`).**
- [x] [Deferred-from: CR v1s-12-1, 2026-07-21] PosixFilePermission tests fail on non-POSIX runners [ProdSecretsValidatorTest] — assumeTrue(POSIX) guard — **Resolved v1s-15-7: `ProdSecretsValidatorTest` sets the fixture's POSIX mode only when the `posix` attribute view is supported (the validator itself skips the check elsewhere), and `JwtKeyPermissionsValidation` has an `assumeTrue(posix)` @BeforeEach so it skips instead of failing.**
- [x] [Deferred-from: CR v1s-12-3, 2026-07-21] SecurityConfigAdminPathEnforcementTest coverage holes [SecurityConfigAdminPathEnforcementTest.java] — EMPLOYEE, anonymous->401, no-role token, non-GET — **Resolved v1s-15-7: `employeeBlockedAtPathLevel` (403), `anonymousRejectedWith401` (401), `authenticatedWithoutAuthorityBlocked` (403), `ownerPostBlockedAtPathLevel` (403) and `superAdminPostAllowed` (200, CSRF disabled) via a new `@PostMapping` on the stub controller.**
- [x] [Deferred-from: CR v1s-12-3, 2026-07-21] SecurityContextHolder not cleared between tests [admin controller tests] — @AfterEach clearContext — **Resolved v1s-15-7: `@AfterEach SecurityContextHolder.clearContext()` added to every @WebMvcTest class (AdminTenant, AdminCatalog, AdminSubscription, AdminSyncMonitoring, Audit, Subscription, Auth, Onboarding, SecurityConfigAdminPathEnforcement, plus the new security slices) and to the standalone DeviceTokenControllerTest / ProductImportControllerTest; AdminFeedbackControllerTest already cleared. After review: also added to the 16 standalone controller tests and JwtAuthFilterTest that set an authentication — no test class under src/test sets one without clearing it.**
- [x] [Deferred-from: CR v1s-12-3, 2026-07-21] @MockBean deprecated (Boot 3.4+) [backend tests] — migrate to @MockitoBean — **Resolved v1s-15-7: all 9 files migrated to `org.springframework.test.context.bean.override.mockito.MockitoBean`; `grep boot.test.mock.mockito.MockBean` on src/test returns nothing.**
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] JdbcTokenRevocationAdapterTest bypasses RowMapper/SQL [JdbcTokenRevocationAdapterTest.java:25-44] — capture RowMapper, assert SQL — **Resolved v1s-15-7: `shouldQueryMembershipJoinedOnSchemaNameAndMapTimestamp` captures SQL + RowMapper (join on `public.tenants` by `schema_name = ?`; Timestamp → Instant, null → null); `revokeAllSessionsSqlScopesToTenantBySchemaName` and `revokeAllSessionsEverywhereSqlHasNoTenantFilter` assert the UPDATE statements.**
- [x] [Deferred-from: CR v1s-12-2, 2026-07-21] Tests missing: tva==null and iat==tva boundary [JwtAuthFilterTest.java:345-408] — guard AC2 '>=' contract — **Resolved v1s-15-7: `JwtAuthFilterTest.shouldProceedWhenNoRevocationCutoff` (iat never read), `shouldAcceptTokenIssuedExactlyAtCutoff`, `shouldAcceptTokenIssuedInSameSecondAsSubSecondCutoff`, `shouldRejectTokenIssuedOneSecondBeforeCutoff` (401 `SESSION_REVOKED`).**
- [x] [Added 2026-10-11, v1s-15-9 review] Access-denial tests for controllers migrated to CurrentActor (DeviceToken, Subscription, ProductImport, AdminSubscription) — **Resolved v1s-15-7: `SubscriptionControllerTest` (anonymous 401, no-authority 403, EMPLOYEE 200), `AdminSubscriptionControllerTest` (anonymous 401, EMPLOYEE 403), new `DeviceTokenControllerSecurityTest` (anonymous 401, no-authority → OWNER fallback pinned, OWNER POST/DELETE 200), new `ProductImportControllerSecurityTest` (anonymous 401, no-role 403, OWNER 200).**

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 15.8: Hygiène des tests Flutter (tests rouges, mocks, widget tests manquants) — **[C]**

As a Toor, I want the remaining red Flutter tests fixed and the missing widget/provider tests added, so that `flutter test` is green and meaningful.

**Scope:** Remaining red tests (offline_gate_banner colour, category_provider), mocktail SyncService, POS checkout/draft tests, OWNER logout, EMPLOYEE todaySummary, duplicate-route guard test, ResetPassword type test. Tracker key: `v1s-15-8-flutter-test-hygiene`.

**Refs:** ARCH14, Story 15.2, Stories 13.5 / 13.6 / 16.1 / 16.4 / 16.8 / 16.9, HF-2. **Refonte absorption:** re-appliquer Flutter.

**Items (routed 2026-10-11 from deferred-work):**
- [ ] [Deferred-from: CR v1s-16-9, 2026-07-27] No guard against duplicate GoRoute paths [app_router.dart] — only one instance fixed; add a test walking appRouter routes asserting unique full paths
- [ ] [Deferred-from: CR v1s-16-4, 2026-07-25] Hand-rolled _NoOpSyncService in tests [local_product_datasource_test.dart, product_provider_test.dart] — replace with mocktail MockSyncService
- [ ] [Deferred-from: CR v1s-16-1, 2026-07-25] No OWNER logout widget test [settings_page_test.dart] — add OWNER variant of FCM-cleanup logout test
- [ ] [Deferred-from: CR v1s-13-6, 2026-07-22] Missing test: mixed DRAFT+ACTIVE cart label [checkout_page_test.dart] — assert 'Vente brouillon' label
- [ ] [Deferred-from: CR v1s-13-6, 2026-07-22] Missing failure-path tests for draft flow [record_sale_notifier_test.dart] — applyInitialStockEntries/promoteToActive throw -> rollback/consistency
- [ ] [Deferred-from: CR v1s-13-6, 2026-07-22] addTearDown(container.dispose) missing [record_sale_notifier_test.dart:182,235] — dispose containers
- [ ] [Deferred-from: CR v1s-13-6, 2026-07-22] Missing test: empty cart on CheckoutPage [checkout_page_test.dart] — label + disabled button
- [ ] [Deferred-from: dev-story v1s-13-5, 2026-07-22] 12 unmasked pre-existing Flutter test failures [test/core/sync/offline_gate_banner_test.dart:56; test/features/catalog/provider/category_provider_test.dart] — backend_first contact/product/transfer files were deleted (612539b, 1041827); remaining: banner test still expects #FFF3BF after rebrand, category_provider_test
- [ ] [Deferred-from: legacy bugfix HF-2 ghost-badge, 2026-04-28] No test for EMPLOYEE-scoped todaySummaryProvider [day_summary_bottom_sheet_test.dart] — only employeeId:null covered (day_close_button_test removed in v1s-16-10)
- [ ] [Deferred-from: CR v1s-16-8, 2026-07-27] ResetPassword build() type fix bundled without test [auth_provider.dart:443] — add regression test

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 15.9: Hygiène de refactor backend (helpers dupliqués, constantes, casts) — **[C]**

As a Toor, I want duplicated auth helpers, magic strings and unsafe casts factored out, so that the RBAC and delta code has a single source of truth.

**Scope:** Factor extractAssignedStoreId/isOwnerRole/actor extraction, QuickAdd storeName, SUPER_ADMIN constant + requireSuperAdmin decision, delta provider casts, paginated() validation, WhatsAppPort.isConfigured abstract, minor order/regex tidy. Tracker key: `v1s-15-9-backend-refactor-hygiene`.

**Refs:** Stories 12.3 / 12.5 / 12.6 / 12.9 / 14.8 / 14.10 / 14.13, legacy 7-5. **Refonte absorption:** native — helpers `identity.api`.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-14-13, 2026-07-24] Validation order CreateProduct vs CreateDraftProduct [CreateProductUseCase.java] — align plan check position across both use cases — **Resolved v1s-15-9: CreateProductUseCase now runs lock → idempotency → validateInput → name uniqueness → plan limit → SKU, the same plan-check position as CreateDraftProductUseCase (test: plan limit rejected before the SKU-uniqueness check).**
- [x] [Deferred-from: CR v1s-12-9, 2026-07-24] extractAssignedStoreId() duplicated in controllers [StockController.java, PendingSaleController, StockTransferController, DayClosureController, InventorySessionController] — extract into a shared helper (AuthDetails/SecurityHelper) — **Resolved v1s-15-9: shared `CurrentActor.assignedStoreId()` (fail-closed) in `shared.infrastructure.security`; Stock, StockTransfer, DayClosure, PendingSale, InventorySession, InventoryCount controllers and AbstractSyncOperationHandler delegate to it; private copies deleted.**
- [x] [Deferred-from: CR v1s-14-10, 2026-07-22] Actor extraction logic duplicated [ProductController.java, StockOperationService.java, QuickAddProductService.java] — centralise in AuthDetails — **Resolved v1s-15-9: ProductController, StockOperationService and QuickAddProductService use `CurrentActor.role()/actorLabel()/actorId()`.**
- [x] [Deferred-from: CR v1s-14-10, 2026-07-22] Raw UUID shown when firstName claim absent [ProductController.java, StockOperationService.java] — fallback label + test, together with actor refactor — **Resolved v1s-15-9: `CurrentActor.actorLabel()` falls back to "Un employé" / "Le propriétaire" / "Un utilisateur", never the UUID (CurrentActorTest, ProductControllerTest, QuickAddProductServiceTest).**
- [x] [Deferred-from: CR v1s-14-10, 2026-07-22] QuickAddProductService publishes ProductCreatedEvent with storeName=null [QuickAddProductService.java:132] — session.getStoreId() is known; resolve storeName like ProductController — **Resolved v1s-15-9: storeName resolved from `session.getStoreId()` via StoreRepository (QuickAddProductServiceTest asserts it).**
- [x] [Deferred-from: CR v1s-14-8, 2026-07-22] ApiResponseWrapper.paginated() no input validation [ApiResponseWrapper.java:48] — validate page/size/totals when first real caller appears — **Resolved v1s-15-9: page/size/totals validated (IllegalArgumentException), negatives rejected and totalPages must be 0 iff totalElements is 0; a page past the end stays valid since Spring returns it empty (ApiResponseWrapperTest).**
- [x] [Deferred-from: CR v1s-14-8, 2026-07-22] ReportsDeltaProvider.bool() ClassCastException risk [ReportsDeltaProvider.java:55-57] — cast Number without type check; defensive handling (also DayClosureDeltaProvider) — **Resolved v1s-15-9: package-private `DeltaValues.bool()` type-checks Boolean/Number/PostgreSQL text, unknown → IllegalArgumentException; Reports and DayClosure providers delegate (DeltaValuesTest).**
- [x] [Deferred-from: CR v1s-14-8, 2026-07-22] ReportsDeltaProvider.num() truncates via intValue [ReportsDeltaProvider.java:61] — INTEGER columns today so no impact; use longValue/BigDecimal — **Resolved v1s-15-9: `DeltaValues.num()` — int when it fits, long otherwise, non-integral returned unchanged; all 7 providers delegate, private copies deleted.**
- [x] [Deferred-from: CR v1s-12-5, 2026-07-21] D11 String.matches instead of compiled Pattern [TenantSchemaSyncService.java:116] — negligible perf; tidy — **Already resolved by v1s-13-12: TenantSchemaSyncService gates through `TenantSchema.isValid` with a precompiled `SCHEMA_PATTERN`. No code change.**
- [x] [Deferred-from: CR v1s-12-3, 2026-07-21] requireSuperAdmin() now dead for rejection [AdminTenantController / SecurityConfig.java] — decide: keep as second barrier (with test) or remove — **Decision v1s-15-9: kept as a second barrier behind the `/api/v1/admin/**` URL rule, now one implementation (`CurrentActor.requireSuperAdmin()`); covered by CurrentActorTest, the admin controller 403 tests and SecurityConfigAdminPathEnforcementTest.**
- [x] [Deferred-from: CR v1s-12-3, 2026-07-21] Magic string SUPER_ADMIN duplicated [SecurityConfig.java:96, JwtAuthFilter.java:181, AdminAccountInitializer] — reference Role enum — **Resolved v1s-15-9: SecurityConfig uses `hasRole(Role.SUPER_ADMIN.name())`, AdminAccountInitializer uses `Role.SUPER_ADMIN.name()`, role authorities in CurrentActor derive from `Role`. The JwtAuthFilter:181 reference was stale (the filter no longer compares role names).**
- [x] [Deferred-from: CR v1s-12-6, 2026-07-21] isOwnerRole()/requireOwner() duplicated in 4 controllers [PendingSaleController, DayClosureController, StoreController, StockTransferController] — factorise with extractAssignedStoreId helper — **Resolved v1s-15-9: `CurrentActor.isOwner()/requireOwner(msg)` in PendingSale, DayClosure, Store, StockTransfer, Account, Category, InventorySession, Employee and TenantPreferences controllers; ReportController keeps its UNAUTHORIZED pre-check and delegates the role check.**
- [x] [Deferred-from: legacy CR 7-5, 2026-04-02] WhatsAppPort.isConfigured() default true [WhatsAppPort.java] — make abstract so new adapters can't silently send — **Resolved v1s-15-9: `isConfigured()` is abstract; every adapter (NoOp, Wassender, Twilio, Failover) declares it explicitly.**
- [x] [Deferred-from: CR v1s-14-5, 2026-07-23] AdminFeedbackController bypasses application layer [AdminFeedbackController.java] — inject a use case instead of FeedbackRepository — **Resolved v1s-15-9: new `ListFeedbackUseCase` / `ListFeedbackService` (read-only tx, size capped at 100, priority then screenContext); the controller injects the use case.**
- [x] [Deferred-from: CR v1s-14-5, 2026-07-23] AccountDeletionScheduler.findAll() loads every tenant [AccountDeletionScheduler.java] — findByStatusAndDeletionScheduledAtBefore — **Resolved v1s-15-9: `TenantRepository.findDeletionsDueBy(now)` (derived `findByStatusAndDeletionScheduledAtLessThanEqual`), checked on PostgreSQL.**
- [x] [Deferred-from: CR v1s-14-5, 2026-07-23] Feedback domain model has no null-safety [Feedback.java] — requireNonNull like Tenant/User — **Resolved v1s-15-9: `requireNonNull` on id, type, description, tenantId, userId, submittedAt, priority (the V7 NOT NULL columns); FeedbackTest.**
- [x] [Deferred-from: CR v1s-15-11, 2026-10-11] cancelDeletion blind-writes ACTIVE after the scheduler commit [AccountDeletionService.java] — findBySchemaName + save with no conditional update; the scheduler now locks the tenant row (FOR UPDATE) and writes DELETED last, but a cancel that read DELETION_PENDING before that lock can still overwrite DELETED with ACTIVE on a dropped schema; use a conditional UPDATE `WHERE status = 'DELETION_PENDING'` and fail when 0 rows — **Resolved v1s-15-9: owner cancel is one conditional native UPDATE (`TenantRepository.cancelPendingDeletion`: DELETION_PENDING, grace period not over, schema present); 0 rows → DELETION_NOT_PENDING, event only on success. Proven on PostgreSQL: the UPDATE waits on the scheduler row lock (pg_stat_activity) then matches 0 rows (AccountDeletionConcurrencyPostgresIntegrationTest).**
- [x] [Deferred-from: CR v1s-15-11, 2026-10-11] cancelDeletion can reactivate a tenant whose schema is already dropped [AccountDeletionService.java, AdminTenantService.cancelDeletion] — if a post-DROP step of AccountDeletionScheduler fails, the tenant stays DELETION_PENDING without schema until the next run; cancel must refuse when to_regnamespace(schema_name) IS NULL (or the scheduler must commit a DELETING marker before the DROP) — **Resolved v1s-15-9: owner and admin cancel UPDATEs require `to_regnamespace(schema_name) IS NOT NULL`; the tenant stays DELETION_PENDING and the next run completes it (PostgreSQL test).**
- [x] [Deferred-from: CR v1s-15-11, 2026-10-11] AdminTenantService.forceDelete sets DELETED without dropping the schema or purging memberships/tokens [AdminTenantService.java:169-177] — breaks "DELETED means schema gone"; the scheduler never revisits DELETED tenants so the schema leaks; route forceDelete through the scheduler path (set DELETION_PENDING with deletion_scheduled_at = now) or call the same deletion routine — **Resolved v1s-15-9: forceDelete auto-commits a DELETION_PENDING marker (date = now(), excludes DELETED and the system tenant) then calls `AccountDeletionScheduler.deleteNow` — the same per-tenant routine (lock, DROP, purge, DELETED last, WhatsApp after commit). PostgreSQL test for ACTIVE/SUSPENDED/DELETION_PENDING and invalid targets.**
- [x] [Deferred-from: build-auto v1s-13-8, 2026-10-11] Silent `((Number) x).intValue()` truncation left in SaleSyncHandler (quantity, prices, discountAmount), TransferSyncHandler, InventoryCountSyncHandler, ValidateSaleSyncHandler, ProductSyncHandler — hoist StockAdjustSyncHandler.requireIntegralInt into AbstractSyncOperationHandler and apply it everywhere — **Resolved v1s-15-9: `requireIntegralInt` / `optionalIntegralInt` hoisted into AbstractSyncOperationHandler; Sale, Transfer, InventoryCount, ValidateSale, Product and StockAdjust handlers use them (fractional / out-of-range tests per handler).**

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 15.10: Hygiène de code Flutter & docs obsolètes — **[C]**

As a Toor, I want the Flutter code smells (magic strings, duplicated routing/HTTP helpers, stale Ref capture) and the stale DayCloseButton docs cleaned up.

**Scope:** Status enums, routeForRole, debug HttpClient helper, session cache for router, registration role from server, SyncService contract doc, Ref capture, DayCloseButtonState rename + stale DayCloseButton docs. Tracker key: `v1s-15-10-flutter-code-hygiene-docs`.

**Refs:** Stories 12.7 / 13.4 / 13.6 / 16.10, HF-1, HF-2, legacy 5-6. **Refonte absorption:** re-appliquer Flutter (keevo_core).

**Items (routed 2026-10-11 from deferred-work):**
- [ ] [Deferred-from: CR v1s-13-6, 2026-07-22] Magic strings 'ACTIVE'/'DRAFT'/'COMPLETED' [cart_item.dart:14,25,32; record_sale_notifier.dart:108] — introduce ProductStatus/SaleStatus enums
- [ ] [Deferred-from: CR v1s-12-7, 2026-07-21] Secure storage read on every navigation [app_router.dart:216-224] — cache session state in a provider synced on login/logout/refresh
- [ ] [Deferred-from: CR v1s-12-7, 2026-07-21] badCertificateCallback block duplicated [auth_provider.dart:68,105] — extract _createDebugHttpClient()
- [ ] [Deferred-from: CR v1s-13-4, 2026-07-22] SyncService strategy swap not guaranteed to share Drift connection [sync_service.dart] — document contract on interface; integration test if a 2nd impl appears
- [ ] [Deferred-from: legacy bugfix HF-2 ghost-badge, 2026-04-28] computeTodaySummary null employeeId semantics undocumented [day closure repository] — document null = all sales at repository/domain level
- [ ] [Deferred-from: legacy CR HF-1, 2026-04-19] kUserRoleKey hardcoded 'OWNER' at registration [auth_provider.dart:258] — read role from RegistrationResult
- [ ] [Deferred-from: legacy CR HF-1, 2026-04-19] role ternary '/dashboard' : '/pos' duplicated [app_router.dart:245, startup_route.dart:76, shop_name_page.dart:80] — extract routeForRole()
- [ ] [Deferred-from: legacy CR 5-6, 2026-04-23] RiverpodSyncTriggerDispatcher captures Ref [RiverpodSyncTriggerDispatcher] — stale Ref on scope recreation
- [ ] [Deferred-from: CR v1s-16-10, 2026-07-27] ~11 docs still reference DayCloseButton [ux-design-specification.md, epic-4, 4-4 story, requirements-inventory, etc.] — doc cleanup chore
- [ ] [Deferred-from: CR v1s-16-10, 2026-07-27] Rename DayCloseButtonState -> DayClosureState [day_closure_providers.dart:17-27, reports_page.dart, pos_speed_dial.dart] — name refers to deleted widget

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

---

# Epic 16: Mobile Frontend Hardening & Parity (Piste A/C)

Findings pure-mobile issus des tranches d'audit Flutter (FCM, iOS, dark mode, debounce, sync indicator, typo mouvement, store offline). Aucune contrepartie backend — c'est du 100% Flutter. **Tous tag A** (V1-shippable) sauf 16.6 partiellement [C].

## Story 16.1: Cleanup du token FCM au logout — **[A]**

As a Toor, I want the FCM device token removed on logout, so that a shared/revoked device stops receiving the previous user's pushes (8-0 AC10, FR61 hygiene).

**Refs:** frontend F-1, 8-0 AC10. **Refonte absorption:** re-appliquer Flutter.

**AC:**
**Given** `settings_page.dart:399-411` logout only clears secure storage + prefs
**When** logout is invoked
**Then** before clearing storage: `fcmService.getToken()` → `RemoteDeviceTokenDataSource.deleteToken(token)` (`DELETE /api/v1/devices/token`) + `fcmService.deleteToken()` (guard desktop)
**And** on a shared device, the revoked/logged-out employee stops receiving pushes

**Doc:** Story 8-0 AC10. **TDD:** `shouldDeleteFcmTokenOnLogout()`, `shouldGuardDesktopDeleteToken()`.

## Story 16.2: Notifications iOS (Darwin settings) + foreground display — **[A]**

As a Toor, I want foreground FCM notifications displayed on iOS, so that stock/trend/EOD/employee-action pushes show as OS notifications (8-0 AC4).

**Refs:** frontend F-3, 8-0 AC4. **Refonte absorption:** re-appliquer Flutter.

**AC:**
**Given** `fcm_service.dart:121-148` configures only `AndroidInitializationSettings`
**When** iOS support added
**Then** `DarwinInitializationSettings` + `InitializationSettings(iOS: darwinSettings)`; `requestPermissions(alert/badge/sound)` via `flutter_local_notifications`
**And** foreground FCM (stock alerts, trends, EOD, employee-action, global push) display on iOS

**Doc:** Story 8-0 AC4. **TDD:** `shouldInitializeDarwinSettingsOnIos()`, `shouldRequestAlertBadgeSoundPermissions()`.

**Items (routed 2026-10-11 from deferred-work):**
- [ ] [Deferred-from: CR v1s-16-1, 2026-07-25] FCM token freshness not verified at deleteToken [fcm_service.dart:102-110] — getToken() may differ from backend-registered token; settle when FcmService registration is reworked

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 16.3: Dark mode — remplacer les `Colors.white` hardcodés + contraste bannière offline — **[A]**

As a Toor, I want dark mode to render correctly, so that white panels don't appear on the navy surface and the offline banner is readable (UX20, NFR23-26).

**Refs:** frontend F-4/F-5, UX20, UX2. **Refonte absorption:** re-appliquer Flutter.

**AC:**
**Given** 41 `Colors.white` across dashboard/reports/profitability/notifications/settings + offline banner amber-on-amber
**When** dark mode fixed
**Then** all `Colors.white` → `Theme.of(context).colorScheme.surface`/`cardBg`; offline banner uses `AppTheme.onWarning` for icon/text; `AppTheme.dark()` sets the spec tokens (`onSurface #F0F4F8`, `onSurfaceVariant #ADB5BD`, `surfaceVariant #1B2838`, `primaryContainer #1B3A5C`)

**Doc:** UX2/UX20. **TDD:** widget test asserting no `Colors.white` literal; `shouldRenderReadableOfflineBannerInDarkMode()`.

## Story 16.4: Recherche catalogue — debounce 300ms + fuzzy (UX9) — **[A]**

As a Toor, I want the product search debounced and typo-tolerant, so that it doesn't re-query per keystroke and finds products despite typos (<500ms, UX9).

**Refs:** frontend F-6, UX9, FR (search). **Refonte absorption:** re-appliquer Flutter.

**AC:**
**Given** `catalog_page.dart:54-57` sets the query per keystroke (no debounce) + LIKE substring (no category, no fuzzy)
**When** search fixed
**Then** 300ms `Timer` debounce (pattern from `create_draft_product_bottom_sheet.dart:60`); SQL matches name/SKU/category; "Aucun résultat → Créer" present; the false "debounce 300ms" comment removed

**Doc:** UX9. **TDD:** `shouldDebounceSearch300ms()`, `shouldMatchCategoryInSearch()`.

## Story 16.5: SyncIndicator — état online-stale + daysSinceLastSync — **[A]**

As a Toor, I want the sync indicator to warn when online-but-stale (day5/day6), so that the AppBar reflects urgency (UX15, 5-4 AC1).

**Refs:** frontend M2/L1/L2, UX15, 5-4 AC1. **Refonte absorption:** re-appliquer Flutter.

**AC:**
**Given** `sync_indicator.dart:54-55` ignores gate warning/critical when online; `daysOfflineProvider` reads `kFirstOfflineDateKey` not `kLastSyncAtKey`
**When** fixed
**Then** indicator consults `syncGateStateProvider` → online+warning (day5) "En ligne (sync J-X)", online+critical (day6) "⚠ Sync urgente"; `daysSinceLastSyncProvider` based on `kLastSyncAtKey`; day5 = yellow (not red), day6 = red (align with `SyncGateState`)

**Doc:** 5-4 AC1/AC2, UX15. **TDD:** `shouldShowWarningWhenOnlineAndStaleDay5()`, `shouldShowCriticalDay6()`.

## Story 16.6: Typo `STOCK_ADJUST` + store offline sync_queue — **[A/C]**

As a Toor, I want local stock movements typed correctly and offline store writes queued, so that movement history filters work and offline store changes sync (FR27, FR30).

**Refs:** frontend F-3 (catalog), F-4 (stores), FR27, FR30. **Refonte absorption:** re-appliquer Flutter (ties 13.4).

**AC:**
**Given** `stock_repository_impl.dart:259,279` writes `STOCK_ADJUST` (should be `ADJUSTMENT`); `store_repository_impl.dart` bypasses `sync_queue`
**When** fixed
**Then** local movement type → `ADJUSTMENT` (model field + sync operation label); the filter "Ajustements" matches; `StoreRepositoryImpl` injects `SyncService` + `SyncTriggerDispatcher` and queues `CREATE_STORE`/`UPDATE_STORE`/`DEACTIVATE_STORE` (mirrors clients/suppliers)

**Doc:** Story 2.3 AC5/AC6 + Story 3.1 AC1/AC5 (ties 13.4). **TDD:** `shouldWriteAdjustmentMovementType()`, `shouldQueueStoreCreateOffline()`.

## Story 16.11: Boutons « Réessayer » sur les écrans d'erreur de l'app — **[A]**

As a Toor, I want every full-page / section error state of the app to offer a « Réessayer » action that re-runs the failed load, so that a transient network or database failure never leaves me stuck on a dead screen (UX hygiene, audit M4).

**Scope:** Factoriser l'état d'erreur réutilisable (`AppErrorWidget`, design system post-UI-3) avec une action « Réessayer » qui invalide/rafraîchit le provider Riverpod concerné, et le brancher sur tous les écrans d'erreur recensés (y compris les états d'erreur ad hoc qui n'utilisent pas encore le widget commun). Tracker key: `v1s-16-11-app-error-retry`.

**Refs:** audit-coherence-2026-10-11.md M4 (et #11 de l'audit 2026-07-23), sprint-change-proposal-2026-10-11.md §4, ux-design-specification.md (états d'erreur), Story UI-3. **Refonte absorption:** re-appliquer Flutter.

**Items:**
- [x] [audit M4, 2026-10-11] `AppErrorWidget` : 4 usages sur 23 passent `onRetry` — brancher `onRetry` (invalidate/refresh du provider) sur les 19 autres [`sync_settings_page.dart:260,338,456`, `pos_page.dart:354,472`, `sales_history_page.dart:85,116`, `catalog_page.dart:878`, `categories_page.dart:209`, `notifications_page.dart:67`, `sale_detail_page.dart:50`, `report_detail_page.dart:51`, `stores_list_page.dart:234`, `sync_conflict_log_page.dart`, `inventory_*_page.dart`, `global_stock_overview_page.dart`, `pending_sales_page.dart`] — **done 2026-10-11 (v1s-16-11, spec-v1s-16-11-app-error-retry.md)** : `onRetry` désormais obligatoire, les 19 sites invalident leur provider exact (recherche POS : relance de la dernière requête).
- [x] [audit M4, 2026-10-11] États d'erreur pleine page ad hoc (Text/Center maison, sans retry) — migrer vers l'état d'erreur commun avec « Réessayer » [contacts, profitability, team, dashboard, subscription, transfer history, stock history, …] — **done 2026-10-11 (v1s-16-11)** : 14 états migrés vers `AppErrorWidget`. Hors scope justifié : sélecteurs/dropdowns, `AppErrorInline` des bottom sheets, lignes d'une ligne (`sync_settings_page.dart:159`, `sync_detail_bottom_sheet`), point de statut `sync_indicator`, repli offline volontaire `cross_store_availability_bottom_sheet`, bannières de formulaire auth, `account_page` (retry déjà présent), page debug ; et les branches `SizedBox.shrink()` d'éléments secondaires d'un écran déjà chargé (sections du store dashboard, filtre employé de l'historique des ventes, état de clôture des rapports, filtre de l'historique des rapports, checkout, `pos_page.dart:647`, `draft_validation_banner`) — décorations optionnelles, pas des écrans d'erreur.

**AC (interim):**
**Given** un écran dont le chargement principal (FutureProvider/StreamProvider/AsyncNotifier) échoue
**When** l'état d'erreur est affiché
**Then** il utilise le widget d'erreur commun (tokens du design system, pas de couleur codée en dur) avec un bouton « Réessayer »
**And** taper « Réessayer » invalide/rafraîchit le provider concerné et relance le chargement (test widget : erreur → tap → données affichées)
**And** les états d'erreur inline/compacts (dropdowns, bottom sheets) hors scope sont listés et justifiés dans la spec.

## Story 16.12: Retour utilisateur sur les rejets de sync — **[A]**

As a Toor, I want REJECTED push operations surfaced to the user (and their ghost local rows rolled back), so that an offline create that the server refused does not silently survive on the device.

**Scope:** Surface REJECTED push ops to the user (rollback ghost rows), define PLAN_LIMIT_EXCEEDED on sync, don't drop startup pull when a sync runs. Tracker key: `v1s-16-12-sync-rejection-feedback`.

**Refs:** FR27/FR30, UX15, Stories 14.13 / 16.6 / 16.8. **Refonte absorption:** re-appliquer Flutter + protocole `sync`.

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-14-13, 2026-07-24] ProductSyncHandler now rejects with PLAN_LIMIT_EXCEEDED, undocumented [ProductSyncHandler.java] — sync protocol has no handling for plan-limit rejection; define + test expected client behaviour — **resolved v1s-16-12**: terminal reason, ghost product/store rolled back + "passez à un forfait supérieur" (`sync_rejection_policy.dart`), backend contract test `ProductSyncHandlerTest.handle_createProduct_planLimitExceeded_*`
- [x] [Deferred-from: CR v1s-16-6, 2026-07-27] No rollback/notice for REJECTED offline CREATE_STORE (all entity types) [rest_sync_service.dart:106-113] — push->UI rejection channel; ghost local rows — **resolved v1s-16-12**: `_reconcileRejection` rolls back store/product/draft/category/client/supplier ghosts and their follow-ups; SnackBar "… — annulé sur cet appareil"; money ops kept (stuck-sale case → v1s-16-15)
- [x] [Deferred-from: CR v1s-16-8, 2026-07-27] onAppStartup() silently no-op if a sync is running [sync_trigger_notifier.dart:205] — queue a follow-up pull instead of dropping — **resolved v1s-16-12**: a full sync (push+pull) is chained after the running cycle
- [x] [Deferred-from: build-auto v1s-13-8, 2026-10-11] CREATE_INVENTORY_SESSION can now be REJECTED (INVENTORY_SESSION_ALREADY_ACTIVE / INVENTORY_STORE_NOT_FOUND) [InventorySessionSyncHandler.java] — the local session stays IN_PROGRESS and its queued SAVE_INVENTORY_COUNT / VALIDATE_INVENTORY fail in cascade; cancel/flag the local session and its dependent ops on rejection — **resolved v1s-16-12**: session CANCELLED locally, offline adjustments reverted, counts/validation ops dropped; online create no longer falls back offline on a server refusal

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 16.13: Bugs UX résiduels de l'app (auth/router, bannières, listes, pickers) — **[C]**

As a Toor, I want the pre-existing app UX defects (auth/router exits and redirects, banner stacking, SnackBar flood, list/picker glitches) fixed in one sweep.

**Scope:** Pre-existing app UX bugs: auth/router exits and redirects (role null, /auth/login bypass, tenant picker/password change exits, CGU links, back nav, country code, French-only), banners (stacking, stale subscription, SnackBar flood), SyncIndicator details, logout re-entrancy, employee limit label, csv import, product card, contacts header/search, date picker, legacy pending-sale flow. Also receives the two v1s-14-12 items (OTP single field — AC7 amended 2026-10-11 — and reset-page deep link without phone). Tracker key: `v1s-16-13-app-ux-residual`.

**Refs:** UX9/15/20, FR20, Stories 12.7 / 14.9 / 14.12 / 14.13 / 16.1 / 16.5, HF-1, HF-2, UI-3 lots 2-5 review. **Refonte absorption:** re-appliquer Flutter.

**Items (routed 2026-10-11 from deferred-work):**
- [ ] [Deferred-from: CR v1s-16-1, 2026-07-25] Logout double-tap re-entrancy [settings_page.dart:377-425] — disable tile / show loading while logout runs
- [ ] [Deferred-from: CR v1s-14-13, 2026-07-24] create_employee_page shows 'Limite : 5' instead of 3 [create_employee_page.dart:113] — FREE plan is 3 employees (PlanType.FREE); read the value from the enum/API
- [ ] [Deferred-from: CR v1s-14-12, 2026-07-23] ResetPasswordPage without phone extra (deep link) broken [reset_password_page.dart:37 / app_router.dart:271] — phone falls back to '' ("au ."); redirect to /auth/forgot-password when extra missing (also UI-3 L2-15) (AC7 amended 2026-10-11: a single 6-digit OTP field suffices; owner decision, v1s-14-12 stays done)
- [ ] [Deferred-from: CR v1s-14-12, 2026-07-23] OTP entry is one field, AC7 asks 6 separate auto-focus inputs [reset_password_page.dart:141-145] — AC7 marked 'PASS avec écarts'; implement separate inputs or amend AC7 with PO (AC7 amended 2026-10-11: a single 6-digit OTP field suffices; owner decision, v1s-14-12 stays done)
- [ ] [Deferred-from: CR v1s-14-9, 2026-07-22] subscriptionInfoProvider not invalidated on auth/tenant change [subscription_info_provider.dart:31] — shared POS device may show previous tenant's trial banner; autoDispose or deferred invalidation
- [ ] [Deferred-from: CR v1s-14-9, 2026-07-22] No priority between stacked banners [main_shell.dart:356-358] — up to 3 full-width banners; define precedence
- [ ] [Deferred-from: CR v1s-12-7, 2026-07-21] SnackBar flood on concurrent SESSION_REVOKED [auth_provider.dart:124-131] — debounce / shown-once flag
- [ ] [Deferred-from: legacy bugfix HF-2 ghost-badge, 2026-04-28] currentUserIdProvider error -> silent store-wide view for EMPLOYEE [todaySummaryProvider callers] — AsyncError -> null -> all sales; show error instead
- [ ] [Deferred-from: legacy CR HF-1, 2026-04-19] CGU links not tappable [auth_page.dart:445-455] — add TapGestureRecognizer to /docs/politique
- [ ] [Deferred-from: legacy CR HF-1, 2026-04-19] Router lets role==null through OWNER-only routes [app_router.dart:186] — only 'EMPLOYEE' is redirected; null should go to login
- [ ] [Deferred-from: legacy CR HF-1, 2026-04-19] Back in login mode without history -> /auth/register [auth_page.dart] — navigation review
- [ ] [Deferred-from: legacy CR HF-1, 2026-04-19] initialCountryCode 'CM' hardcoded [auth_page.dart:334, forgot_password_page.dart:157, create_employee_page.dart:167] — locale/SIM detection (i18n pass)
- [ ] [Deferred-from: CR v1s-16-5, 2026-07-26] SyncIndicator loading branch flashes online-stale label [sync_indicator.dart:100] — neutral loading state
- [ ] [Deferred-from: CR v1s-16-5, 2026-07-26] daysSinceLastSyncProvider no low clamp [sync_gate_provider.dart:16] — clamp negative days from clock skew
- [ ] [Deferred-from: CR v1s-16-5, 2026-07-26] daysSinceLastSyncProvider not time-reactive [sync_gate_provider.dart:11-17] — periodic re-evaluation
- [ ] [Deferred-from: CR v1s-16-5, 2026-07-26] No Semantics on '⚠ Sync urgente' [sync_indicator.dart:123] — add label (also blocked branch)
- [ ] [Deferred-from: CR UI-3 lots 2-5 app, 2026-10-11] Legacy pending-sale validation flow defects [pending_sales_page.dart, validate flow] — 5xx not network, timeout re-queues VALIDATE_SALE, non-atomic promotions; only matters if PENDING_VALIDATION is reactivated
- [ ] [Deferred-from: CR UI-3 lots 2-5 app, 2026-10-11] csv_import_page _mapping not cleared on cancel/new file [csv_import_page.dart] — stale dropdown value
- [ ] [Deferred-from: CR UI-3 lots 2-5 app, 2026-10-11] ProductCard draft promotion: invalid stock input becomes 0 [product_card.dart] — validate input
- [ ] [Deferred-from: CR UI-3 lots 2-5 app, 2026-10-11] Contact list header buttons scroll away; search ignores archived [client/supplier list pages] — pin actions; search respects 'Voir les archivés', sync refresh keeps results
- [ ] [Deferred-from: CR UI-3 lots 2-5 app, 2026-10-11] Custom period picker asserts after New Year [period_filter_chips.dart] — clamp initial range to firstDate
- [ ] [Deferred-from: CR UI-3 lots 2-5 app, 2026-10-11] /auth/login with session bypasses wizard / forced pw change [app_router.dart:236-247] — route through splash/startup_route logic
- [ ] [Deferred-from: CR UI-3 lots 2-5 app, 2026-10-11] TenantPicker/forced PasswordChange no exit; auth pages French-only [tenant_picker_page.dart:88, password change page, auth pages] — add back/sign-out; i18n (reset-without-phone part goes to v1s-14-12)

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 16.14: Qualité de la liste catalogue (échappement recherche, dédup, pagination) — **[C]**

As a Toor, I want the catalog list to search safely, deduplicate by id/SKU and scale, so that distinct products are never hidden and the list stays fast.

**Scope:** Catalog search escaping, dedup by id/SKU not name, never-stocked vs out-of-stock, client-side full-catalog load. Tracker key: `v1s-16-14-catalog-list-quality`.

**Refs:** UX9, Story 16.4, spec-catalog-product-visibility-other-stores. **Refonte absorption:** re-appliquer Flutter (`catalog`).

**Items (routed 2026-10-11 from deferred-work):**
- [x] [Deferred-from: CR v1s-16-4, 2026-07-25] LIKE %/_ not escaped in catalog search [local_product_datasource.dart:51-60] — user typing % or _ gets over-broad matches; escape + ESCAPE clause — **resolved v1s-16-14**: shared `core/storage/sql_like.dart` (`likeContains` + `ESCAPE '\'`) applied to catalog name/SKU/category, POS, clients, suppliers, multi-store search — spec-v1s-16-14-catalog-list-quality.md
- [x] [Deferred-from: CR spec-catalog-product-visibility-other-stores, 2026-09-08] Product dedup by name can hide distinct products [product_provider.dart (seenNames)] — dedup by sync id/SKU instead of name — **resolved v1s-16-14**: dedup by id (sync identity); SKU not used (local/offline SKUs may collide → would hide rows); name collisions are rejected/merged server-side (`uq_products_name`, `_dropMergedLocalProduct`)
- [x] [Deferred-from: CR spec-catalog-product-visibility-other-stores, 2026-09-08] No UI difference 'never stocked' vs 'out of stock' [catalog / out-of-stock tabs] — PO/UX decision — **resolved v1s-16-14** (decision from UX spec: red = rupture totale): ProductCard shows a neutral « Jamais stocké » badge when no stock row exists in the displayed scope, red « 0 » for real stock-outs; Rupture tab membership unchanged (visibility spec)
- [x] [Deferred-from: CR spec-catalog-product-visibility-other-stores, 2026-09-08] Whole tenant catalog loaded client-side, no pagination [product list use case] — perf at scale — **rejected v1s-16-14 with reason**: offline-first keeps the full catalog local (POS offline), NFR5/FR16 size it at 500 products, list is a lazy `ListView.builder`; NFR5 regression test added (search over 500 products < 500 ms)

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 16.15: Ventes hors ligne bloquées dans la file de sync — **[A]**

As a Toor, I want an offline sale that the server refuses for good (unknown product/store/client, FORBIDDEN, INSUFFICIENT_STOCK…) to reach a defined end state, so that it neither blocks the sync queue forever nor gets lost.

**Scope:** Since v1s-16-12, money/stock-ledger operations (`CREATE_SALE`, `VALIDATE_SALE`, `CANCEL_SALE`, `CREATE_DAY_CLOSURE`) are never dropped on a terminal rejection: they stay queued, flagged and surfaced once, and keep `hasPendingOperations()` true (which disables catalog/stock refreshes from the server). Decide with the PO the end state: server accepts sale lines whose product no longer exists (ad-hoc line) and/or the app offers an OWNER action "exporter puis retirer" on a rejected sale in Paramètres › Synchronisation › File; then implement it. Tracker key: `v1s-16-15-sync-stuck-money-ops`.

**Refs:** FR27/FR30, NFR zéro vente perdue, Story 16.12, epic 5 (`sync_error_log` 30 days).

**Items (routed 2026-10-11 from build-auto v1s-16-12):**
- [x] [Deferred-from: build-auto v1s-16-12, 2026-10-11] Queued CREATE_SALE referencing a rolled-back product/store is refused forever [rest_sync_service.dart `_reconcileRejection`] — pins `hasPendingOperations()`; needs a PO decision on the end state (ad-hoc sale lines server-side or discard/export action) — **resolved v1s-16-15** (decision PRD FR73a): terminal refusal → quarantine (`sync_queue.quarantined_at`), excluded from push/`hasPendingOperations()`; « Bloquées » list (Paramètres › Synchronisation › File, `/sync/blocked`) with Renvoyer/Exporter (all roles), Rattacher/Abandonner après export (OWNER); server-side ad-hoc lines rejected (no stock movement, FR27); backend refuses unknown store `STORE_NOT_FOUND` — spec-v1s-16-15-sync-stuck-money-ops.md
- [x] [Deferred-from: build-auto v1s-16-12 residual risks, 2026-10-11] Local `stock_transfers` rows of a rolled-back store are not cleaned [rest_sync_service.dart `_rollbackGhostEntity`] — **resolved v1s-16-15**: CREATE_STORE rollback deletes transfers whose source or destination is the ghost store

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).


## Story 16.16: Recherche locale insensible à la casse et aux accents (Unicode) — **[C]**

As a Toor, I want local searches to ignore case and accents beyond ASCII, so that typing "epicerie" or "épicerie" finds "Épicerie" in Catalogue, POS and Contacts.

**Scope:** Local SQLite searches lower-case the query in Dart (Unicode) but compare it with SQLite `LOWER()`/drift `.lower()`, which only fold ASCII; the multi-store stock search does not lower-case at all. Introduce one normalization (e.g. a normalized search column maintained on write, or a registered SQLite function) shared by catalog (name/SKU/category), POS, clients/suppliers and multi-store search, and reuse it for the local duplicate-name check. Keep NFR5 (<500 ms on 500 products) and the 16.14 LIKE escaping. Tracker key: `v1s-16-16-search-unicode-folding`.

**Refs:** UX9, NFR5, Story 16.4 (fuzzy scoped out), Story 16.14 (`core/storage/sql_like.dart`).

**Items (routed 2026-10-12 from build-auto v1s-16-14 review):**
- [x] [Deferred-from: build-auto v1s-16-14 review, 2026-10-12] Non-ASCII case-insensitive search fails ("épicerie" vs "Épicerie") [local_product_datasource.dart search(), pos_search_provider.dart, local_client/supplier_datasource.dart, local_multi_store_stock_datasource.dart] — SQLite LOWER folds ASCII only; also weakens the local duplicate-name check in ProductRepositoryImpl.create/createDraft — ✅ 2026-10-12 resolved (spec-v1s-16-16-search-unicode-folding.md): shared fold `core/storage/search_fold.dart` (SQL `replace()` over `lower()` + exact Dart mirror) on every listed site plus in-memory filters; dedup candidates folded, equality case-only (backend `lower(name)`). Explicitly rejected with reason: case folding for scripts outside the Latin/French/Cameroonian table (Cyrillic, Greek…) stays same-case-only in SQL — full ICU folding needs a registered function (rejected: every DB opener and test DB would need it); no schema migration needed.

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

---

# Suivi audit 2026-10-11 — Dashboard Super Admin

## Story 17.1: Rétablir le build du dashboard + CI dashboard (`v1s-17-1-dashboard-build-ci`) — **[A]**

As a Toor, I want the Next.js Super Admin dashboard to build and to be guarded by CI, so that it can be deployed again.

**Refs:** audit-coherence-2026-10-11 C1 ; sprint-change-proposal-2026-10-11. **Scope:** les pages `monitoring/activity` (9-7) et `monitoring/sync` (9-8) importaient 10 modules inexistants et cassaient `next build`. Réparer plutôt que retirer : livrer le frontend 9-8 (backend `AdminSyncMonitoringController` prêt), masquer proprement la page 9-7 (pas de backend) et router le reste vers 9-7 ; ajouter un workflow GitHub Actions dashboard (lint + typecheck + build).

- [x] Frontend 9-8 livré (types, client API, `useSyncMonitoring`, `SyncOverviewCards` / `SyncHealthTable` / `SyncTenantDetailDrawer`, sidebar active) — spec-v1s-17-1-dashboard-build-ci.md
- [x] Page 9-7 remplacée par un placeholder « bientôt disponible » ; restauration + backend routés dans la story 9-7
- [x] Erreur lint `react-hooks/set-state-in-effect` corrigée (`lib/auth/auth-context.tsx`)
- [x] `.github/workflows/dashboard-ci.yml` (npm ci, lint, tsc --noEmit, build)

**AC (interim):** `npm run lint`, `npx tsc --noEmit` et `npm run build` passent dans `keevo/dashboard` ; la CI dashboard existe ; les éléments reportés sont routés dans les story files 9-7/9-8 sous `### Deferred items routed 2026-10-11`. ✅ 2026-10-11

---

# Epic 18: Export de données & Audit détaillé (Piste V1, tag C)

Le propriétaire peut récupérer toutes ses données dans le format de son choix et comprendre chaque action tracée.

**Refs:** FR55, FR84, FR94–FR98 ; sprint-change-proposal-2026-10-11. **Refonte absorption:** native — `reporting/export` et les `ExportDatasetProvider` sont repris tels quels. Remplace 17-6 et v1s-14-7.
**Ordre:** 18.6 → 18.7 (valeur immédiate, sans dépendance) ; puis 18.1 → 18.2 → 18.4 → 18.3 → 18.5.

## Story 18.1: Socle du module export (backend) — **[C]**

As an owner, I want to export my data as CSV or Excel, so that I can use it outside Keevo.

**Scope:** ports `ExportDatasetProvider` (clé, libellé, colonnes typées, lecture paginée filtrée tenant/boutique/période) et `ExportRenderer` + registre ; `CsvRenderer` (UTF-8 BOM, `;`), `XlsxRenderer` (POI SXSSF, une feuille par jeu, en-têtes figés, formats XAF/date) ; `POST /api/v1/exports` OWNER-only (synchrone sous le seuil, sinon `ExportJob` en tâche de fond + lien signé 24 h + notification FCM), `GET /api/v1/exports` (Mes exports), `GET /api/v1/exports/{id}/file` ; gating CSV tous plans / reste Premium (`PLAN_FEATURE_REQUIRED`) ; audit `DATA_EXPORTED` ; limite de débit ; purge des fichiers expirés. Confirmer les librairies Excel/PDF et le stockage temporaire en début de story.

**AC:**
**Given** an EMPLOYEE token **When** calling `POST /api/v1/exports` **Then** 403
**And** no export ever contains another tenant's rows
**And** a 50,000-row export completes without exhausting memory (streaming)
**And** downloading an expired export returns 410

## Story 18.2: Jeux de données exportables — **[C]**

As an owner, I want every business dataset exportable, so that nothing stays locked in the app.

**Scope:** un `ExportDatasetProvider` par domaine : produits (variantes, prix), niveaux de stock, mouvements, ventes + lignes, clôtures, sessions et comptages d'inventaire, clients, fournisseurs, équipe (sans données sensibles), journal d'audit.

**AC:**
**Given** a store and period filter **When** exporting any dataset **Then** only matching rows are included
**And** columns are stable and documented (same schema as the local export, story 18.5)
**And** amounts are integer XAF and dates are WAT

## Story 18.3: Modèles PDF — **[C]**

As an owner, I want branded PDF documents, so that I can share or print clean reports.

**Scope:** `PdfRenderer` (Thymeleaf → OpenHTMLtoPDF), en-tête de marque commun (boutique, logo si présent, période, date de génération) ; modèles Fin de journée, Rapport d'inventaire (écarts), État de stock, Liste des ventes.

**AC:**
**Given** a closure **When** generating its end-of-day PDF **Then** its figures equal the EOD report of the same closure (same `ClosureWindow`)
**And** accents render correctly, multi-page documents repeat headers and number pages, format A4

## Story 18.4: Écran « Exporter » (app) — **[C]**

As an owner, I want to pick what, which period and which format to export, then share the file.

**Scope:** parcours UX « Exporter mes données » (ux-design-specification.md) : quoi / filtres / format (badge Premium + feuille d'offre), téléchargement puis feuille de partage (`share_plus`), écran **Mes exports**, notification « export prêt » qui ouvre le fichier, boutons Exporter pré-remplis depuis Rapports, Stock, Ventes, Historique des actions.

**AC:**
**Given** a Free plan **When** choosing Excel or PDF **Then** the offer sheet opens
**And** a background export survives closing the app and is listed in Mes exports
**And** an export error shows a clear message with a retry

## Story 18.5: Export local hors ligne (CSV) — **[C]**

As a user without network, I want to export what is on my device as CSV.

**Scope:** génération CSV depuis Drift avec le schéma de colonnes de 18.2, pour les jeux disponibles localement ; partage.

**AC:**
**Given** airplane mode **When** exporting **Then** a CSV is produced and shared
**And** the file is marked « données de cet appareil » and opens directly in French-locale Excel
**And** 10,000 rows are generated without UI jank (isolate)

## Story 18.6: Audit enrichi (backend) — **[C]**

As an owner, I want each audit entry to say who, what, on what, where and how, so that the history is self-explanatory.

**Scope:** migration (DDL tenant + Flyway) : `store_id`, `entity_label`, `device_id`, `source` (`ONLINE`|`SYNC`|`SYSTEM`), `metadata` jsonb + index (`occurred_at`, `store_id`, `user_id`, `action`) ; tous les producteurs d'audit remplissent ces champs ; `value_before`/`value_after` en JSON champ par champ ; entrées de sync avec `source=SYNC` et l'appareil ; `GET /api/v1/audit` filtré (période, boutique, employé, action, objet), pagination par curseur, nom de l'acteur résolu ; `GET /api/v1/audit/{id}`.

**AC:**
**Given** entries written before the migration **When** listing or opening them **Then** they stay readable
**And** immutability is unchanged (NFR13) and an EMPLOYEE gets 403

**Items (routed 2026-10-11 from deferred-work):**
- [ ] [Deferred-from: CR v1s-13-5, 2026-07-22] Audit snapshot silently '[]' on JSON serialization failure [ValidateSaleService.java:212-217, CorrectSaleService.java:127, RecordSaleService.java] — log/alert on failure across commerce/sale serializers
- [ ] [Deferred-from: v1s-15-5 new defers, 2026-07-23] D3 audit_log has no DB-level immutability [V1__baseline_public.sql:22-30] — story title promises append-only DB; add trigger BEFORE UPDATE/DELETE + REVOKE (Flyway V10) (owner decision 2026-10-11: 11-3 stays done, DB-level protection delivered here)

**AC (interim):** every item above is resolved or explicitly rejected with a reason recorded next to it (tick the box and note the commit / decision).

## Story 18.7: Journal d'audit consultable (app) — **[C]**

As an owner, I want to tap an audit line and see its full detail.

**Scope:** écran « Historique des actions » (ux-design-specification.md) : liste aux phrases lisibles, filtres, défilement infini ; détail qui / quoi / sur quoi (lien) / quand et où / source / tableau avant-après / contexte ; bouton Exporter. L'écran « Audit » actuel est renommé « Mouvements de stock ».

**AC:**
**Given** an owner **When** tapping an audit line **Then** the detail shows actor, action, object, time, store, source and the field-by-field changes
**And** offline the list shows the last known state, and unknown action types use a generic rendering
**And** the screen is hidden from employees

---

## Mises à jour documentation transverses (cadence continue, piste C)

- `requirements-inventory.md` : FR21 (edit) + FR65b (new) — Story 14.10.
- `epics/epic-1..9` : ajouter/éditer les AC référencés (Story 12.6, 13.5, 14.x).
- `deferred-work.md` + `sprint-status.yaml` : réconcilier après chaque story résolue (Story 15.5 cadence continue).
- `AUDIT_CONFORMITE_BMAD.md` : marquer chaque finding corrigé au fur et à mesure (cross-ref story + piste).

---

## Final validation checklist (à valider avant exécution)

- [ ] Confirmer la **stratégie deux pistes** : Piste A sur `deploy` (V1 utilisable pendant la refonte), Piste B sur `refractoring`.
- [ ] Confirmer la **branche de hotfix** : `v1-stabilization` issue de `deploy` (proposition), merges vers `deploy` + `staging`.
- [ ] Confirmer Epic 12 + 13 + 14.8 + 14.10 = **Sprint 1 bloquant V1** (tous tag A).
- [ ] Story 12.6 ProductController decision (EMPLOYEE can create/edit catalogue ?) avec le PO.
- [ ] Story 13.3 EOD timing decision (restore 20h fallback vs retire AC).
- [ ] Story 14.1 variant scope (Vêtements-first vs all sectors).
- [ ] Story 14.10 batching window default (5 min).
- [ ] Décider cherry-pick vs native re-implémentation par story pour la **refonte absorption** (noté par story).
