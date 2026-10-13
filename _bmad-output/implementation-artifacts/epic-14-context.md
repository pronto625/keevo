# Epic 14 Context: Récupération scope V1 — Gaps fonctionnels (Pistes A/B/C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Ship on the V1 production line the functional requirements that were documented but never built (about 22 FRs), plus the integrity and enforcement gaps found by the post-launch audits. V1 must stay usable in production while the modular refactor ("refonte") proceeds separately. Each story has a track tag: **A** is a V1 patch shipped now, **C** is a V1 patch the refactor re-applies later, **B** is refactor-native work excluded from the V1 track (no V1 tracker key). Almost every V1 story is done. The remaining open work is the reopened Free-plan enforcement story (14.13): a deactivated employee can currently be reactivated past the Free-plan employee cap, which undermines the Free→Premium monetisation the limits exist for.

## Stories

- Story 14.1: Product variants (FR22). Tag B, outside V1.
- Story 14.2: Seller leaderboard (FR44). Done.
- Story 14.3: Revenue dashboard (FR79). Tag B, outside V1.
- Story 14.4: Global push and system health (FR82/83). Tag B, outside V1.
- Story 14.5: Account deletion (GDPR) and feedback (FR91/92). Done.
- Story 14.6: Video tutorials, WhatsApp invite, employee activity (FR7/65/67). Done.
- Story 14.7: PDF/Excel export (FR55). Done through Epic 18.
- Story 14.8: API contract breaks. Done.
- Story 14.9: FR20 trial/suspension banners and revoked-session teardown. Done.
- Story 14.10: Owner notification on employee actions. Done.
- Story 14.11: Full employee edit by the owner. Done.
- Story 14.12: Forgot password via WhatsApp OTP. Done.
- Story 14.13: Free-plan limits enforcement (FR16). **In progress (current)**, reopened for the employee-reactivation limit bypass.
- Story 14.14: Category delete sync gap. Done.
- Stories 14.15 to 14.17: Category hardening, day-closure owner phone, owner-recipient resolution. Done.

## Requirements & Constraints

- **Free-plan limits:** at most 1 store, 500 products and 3 employees. The values live in the plan enum and are read live for enforcement, so every Free tenant is covered without a data migration. Persisted subscription rows of tenants downgraded earlier may still display old values; that display mismatch was accepted (no backfill).
- **Every path that grows a limited count must check the limit**, not just the main create path. Products (standard create, draft create, CSV import, sync create), stores and employees (create and now reactivate) all go through the shared plan-limit guard.
- **Limit reached:** fail with `PLAN_LIMIT_EXCEEDED` (403) before any write. The app shows a clear French message with an upgrade CTA. Upgrade happens by contacting Toor or paying by MoMo, after which Toor activates Premium manually and limits lift.
- **Downgrade never deletes data.** A tenant moved back to Free keeps everything and keeps working within Free limits. Existing employees above the cap are not removed; the cap only blocks adding or reactivating.
- **Current item (14.13):** reactivating an employee must take the employee-limit lock, then check the employee limit against the current active count, before switching the status to active. A concurrent create and reactivate at max−1 must leave exactly one success.
- **Errors:** responses use the `{ error, code, domainCode, details }` envelope; not-found is a domain 404, never a generic 500.
- **Audit:** every state change emits a domain event written to the immutable audit log; check the event registry before adding a new event.
- **Language:** UI is French only (V1).

## Technical Decisions

- **Backend structure:** Spring Modulith with hexagonal layout. Domains never import each other; they talk through shared ports or domain events. The plan-limit guard and count ports belong to the subscription module and are consumed through ports.
- **Check-then-act races** are closed with a PostgreSQL transaction-scoped advisory lock through the shared transaction-lock port (mandatory propagation, so the caller must be transactional). The lock is taken **before** the plan read and the count, inside the caller's transaction, and released at commit.
- **Lock keys:** `store-limit:<schema>`, `employee-limit:<schema>`. Employee creation already uses `employee-limit:<schema>`; reactivation must use the same key so both paths serialise against each other.
- **Lock coverage test:** every new lock-taking entry point must be listed in the scheduler lock coverage test that asserts lock-taking use cases are transactional.
- **Tests:** unit tests assert the order lock → count/guard → write with `InOrder`; a refusal test verifies no write happens. Real-PostgreSQL race tests are the established way to prove a race is closed.
- **RBAC:** the actor is passed explicitly into use cases via the shared `CurrentActor` helper (fails closed); REST and sync paths enforce the same guards.
- **Testing discipline:** run backend tests one package at a time, never the full suite at once.
- **Repo convention:** any commit touching `keevo/app/` bumps the version in `pubspec.yaml`.

## UX & Interaction Patterns

- **Limit reached:** message plus upgrade CTA (« Disponible sur Plan Premium »), never a raw error. The employee screens already intercept `PLAN_LIMIT_EXCEEDED`; reactivation errors should surface the same way.
- **Employee lifecycle:** Paramètres > Employés > select an employee > activity / Désactiver or Réactiver. Deactivation asks for confirmation, revokes sessions immediately and keeps sales history.

## Cross-Story Dependencies

- **14.13 builds on** the Epic 13 concurrency work: the transaction-lock port (13.9) and the store/employee limit locks added to creation (13.13), from whose review the reactivation item was routed.
- **Employee management** spans 14.11 (full edit, role changes with session revocation) and 14.6 (activity, invites); session revocation comes from 12.2/12.12.
- **Refonte:** limit enforcement is re-applied in the modular refactor through the same plan-gate port.
