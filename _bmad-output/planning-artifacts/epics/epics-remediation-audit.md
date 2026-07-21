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
| **Epic 12** | Stabilisation V1 — Sécurité & Hardening (Piste A) | S1–S7, B-HIGH-5/6/9/10 | OUI (V1) | 7 |
| **Epic 13** | Stabilisation V1 — Concurrence & Intégrité données (Piste A) | B-CRIT-1/2/3, F-CRIT-1/2/3, F-HIGH-4 | OUI (V1) | 7 |
| **Epic 14** | Récupération scope V1 — Gaps fonctionnels (Pistes A/B/C) | FR22/44/55/64/65/66/67/79/82/83/90/91/92, X-* | Partiel | 12 |
| **Epic 15** | Discipline de tests & dette technique (Pistes B/C) | ARCH11–14, dette | Non (hygiène) | 6 |
| **Epic 16** | Mobile Frontend Hardening & Parity (Piste A/C) | frontend F-1..F-6, UX9/15/20, FR27/30 | OUI (V1) | 6 |

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
