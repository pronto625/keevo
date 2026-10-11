# Epic 14 Context: Récupération scope V1 — Gaps fonctionnels (Pistes A/B/C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Deliver on the V1 production line (`deploy`) the functional requirements that were documented and promised but never shipped (around 22 FRs), plus the runtime API breaks and quality gaps found by the post-launch audits. V1 must stay usable in production while the modular refactor ("refonte") proceeds separately. Each story carries a track tag. **A** is a V1 patch shipped now. **C** is a V1 patch that the refactor re-applies later. **B** is refactor-native and excluded from the V1 track; B stories have no V1 tracker key. The epic exists so that features the PRD and UX promise (leaderboard, employee activity, tutorials, account deletion, feedback, password reset, employee edit) actually exist for real users. It also covers integrity fixes that keep one tenant's data from leaking or vanishing.

## Stories

- Story 14.1: Product variants (size/colour), FR22. Tag B, out of V1 scope.
- Story 14.2: Sales leaderboard, FR44. Tag C, backlog.
- Story 14.3: Revenue dashboard (MRR/churn), FR79. Tag B, out of V1 scope.
- Story 14.4: Global push and system health, FR82/83. Tag B, out of V1 scope.
- Story 14.5: Account deletion (GDPR) and feedback, FR91/92. Done.
- Story 14.6: Video tutorials, WhatsApp invite and employee activity, FR7/65/67. Tag C, backlog.
- Story 14.7: PDF/Excel export, FR55. Superseded by Epic 18.
- Story 14.8: API contract breaks. Done.
- Story 14.9: Trial/suspension banners and session-revoked teardown, FR20. Done.
- Story 14.10: Owner notification on employee product/stock actions. Done.
- Story 14.11: Full employee edit by the owner, FR66. Done.
- Story 14.12: Forgot-password flow via WhatsApp OTP. Done.
- Story 14.13: Enforcing the Free plan limits. Done. This story is in the tracker only.
- Story 14.14: Category delete sync gap. Done. This story is in the tracker only.
- Story 14.15: Category hardening (404 instead of 500, audit events, Flutter atomicity). Backlog.
- Story 14.16: Day-closure WhatsApp report sent to the real owner. In progress.
- Story 14.17: Single, reliable owner-recipient resolution (shared resolver). Backlog.

## Requirements & Constraints

- **Tenant isolation is absolute.** A report, notification or message must never go to a hardcoded or guessed recipient. Resolve the owner's phone per tenant. If no phone exists, skip the send and log it instead of using a fallback number.
- **WhatsApp and other external sends are best-effort.** A failure in WhatsApp, FCM or another external service must never roll back or block the business operation that triggered it.
- **Owner-only features:**
  - The leaderboard ranks employees by revenue, number of sales and average basket. Employees get 403 and never see it. In the UI, the owner turns it on with a toggle.
  - Employee activity shows last login, sales today and this week, and whether a session is active. Show an "inactive" badge after 48h, with a WhatsApp reminder.
- **Invitations (FR65)** are shared through a WhatsApp link (`wa.me`) carrying the temporary password.
- **Tutorials (FR7)** are French videos of about 2 minutes per key module, on an "Aide & Tutoriels" page.
- **Audit trail:**
  - Every state change emits a domain event that is written to the immutable audit log, including category toggle and custom-category creation.
  - Audit entries record actor, timestamp, store, a readable label for the object, device, source (online or sync), and before/after values.
- **Error handling:**
  - Errors follow the `{ error, code, domainCode, details }` envelope, and every exception carries a `domainCode`.
  - A not-found case must return 404 through a domain exception, never a generic 500 from an `IllegalArgumentException`.
- **Interim AC for routed-items stories (14.15, 14.16, 14.17):** handle every listed item one of two ways:
  - Fix it and tick the box, noting the commit.
  - Reject it explicitly and record the reason next to the item.
- **Testing:** tests are written first (TDD red). Each story names its expected test methods.

## Technical Decisions

- **Backend architecture:**
  - Spring Modulith with a hexagonal structure: domain, then port, then adapter. Write the port interface before any implementation.
  - API payloads use DTOs; never expose domain models.
  - Pass the actor explicitly in commands and queries. Use the shared `CurrentActor` helper for role checks and actor resolution, which fails closed. Never call `SecurityContextHolder` inside a use case.
  - Domains never import each other directly. They communicate only through shared ports or domain events.
  - Before creating an event, check the domain event registry and extend an existing event rather than duplicating it.
- **WhatsApp:**
  - All sends go through `WhatsAppPort`, which is provider-agnostic and currently implemented by the Wassender adapter.
  - Day-closure and report recipients come from an owner lookup per tenant. `ReportHistoryService` already uses this pattern since v1s-15-7: it refuses and logs when there is no owner phone. `DayClosureWhatsAppListener` must follow the same pattern.
- **Notifications:**
  - Owner notifications use FCM plus WhatsApp (`channel=BOTH`).
  - Bursts are batched in a time window per employee, using the `ConcurrentHashMap` pattern from `StockAlertNotificationListener`.
- **Flutter write path:**
  - Online: write to the backend first, then cache locally with `synced:true` and no queue entry.
  - Offline: write to Drift and enqueue in `sync_queue`. Queue inserts happen in the repository layer and are atomic with the local write, in the same `_database.transaction()`.
  - After every write, call `refresh()` or `invalidate()` on the Riverpod notifier.
- **Category deletion** is a sync operation: `TOGGLE_CATEGORY` with `isActive:false`. There is no `DELETE_CATEGORY` operation.
- **Reuse existing patterns:**
  - Ranking uses the Strategy pattern, mirroring `StoreRankingStrategy`.
  - Plan limits go through `PlanLimitGuard`.
- **Repo convention:** any commit that touches `keevo/app/` must bump the version in `pubspec.yaml`.

## UX & Interaction Patterns

- **Leaderboard:** hidden from employees in V1. It appears only in the owner's dashboard until the owner switches the toggle on. The UX treats it as an emotional feature gate, moving from "surveillance" toward "healthy competition".
- **Employee administration:** the path is Paramètres > Employés > select an employee. The detail view shows activity (last login, sales). Deactivating an employee asks for confirmation and revokes sessions immediately; sales history is kept.
- **Settings menu (Plus > Paramètres):** Compte holds subscription and deletion. Aide holds tutorials and feedback. Tutorials are never mandatory at onboarding.
- **Confirmation dialogs** for destructive category actions must state the real impact. Whether deactivation cascades to sub-categories is an open PO decision.
- **User-facing errors:** show a readable French message instead of a raw exception such as a Drift `StateError`.

## Cross-Story Dependencies

- **14.16** comes from v1s-15-7 (D1, owner lookup in `ReportHistoryService`) and relates to Story 13.11. The refactor re-applies it in `messaging`.
- **14.17** comes from the 14.16 review: one shared owner-recipient resolver (deterministic, active owner only, trimmed non-blank phone) for every owner-directed WhatsApp send.
- **14.15** builds on 14.14 (deletion through `TOGGLE_CATEGORY`) and on 15.3, 15.5 and 13.4 (atomicity of Drift write plus queue).
- The audit flags that `TOGGLE_CATEGORY` has no role check, while the REST `DELETE` is owner-only.
- **14.6:** the FR67 activity part overlaps refactor Story 17-3.
- **14.2:** the refactor re-applies it in 17-2.
- **14.7** is superseded by Epic 18, which handles data export (FR55, FR94–FR98).
- **14.1** is tag B with no active owner. Variant-aware stock keys (`product_id, variant_id, store_id`) are deferred until it lands.
- **Shared session revocation:** 14.9, 14.11 and 14.12 all rely on Epic 12 revocation (12.2 `revokeAllSessions`, 12.7 client teardown).
