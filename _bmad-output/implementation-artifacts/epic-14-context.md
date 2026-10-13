# Epic 14 Context: Récupération scope V1 — Gaps fonctionnels (Pistes A/B/C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Ship on the V1 production line (`deploy`) the functional requirements that were documented and promised but never built (about 22 FRs), plus the runtime API breaks and integrity gaps found by the post-launch audits. V1 must stay usable in production while the modular refactor ("refonte") proceeds separately. Each story has a track tag. **A** means a V1 patch shipped now. **C** means a V1 patch that the refactor re-applies later. **B** means refactor-native work that is excluded from the V1 track and has no V1 tracker key. The epic makes the features that the PRD and UX promised (seller leaderboard, employee activity, tutorials, account deletion, feedback, password reset, employee edit) real for users. It also covers the fixes that stop one tenant's data from leaking or being lost.

## Stories

- Story 14.1: Product variants (FR22). Tag B, outside V1.
- Story 14.2: Seller leaderboard (FR44). Tag C, **in progress**. The refactor-native version is 17-2.
- Story 14.3: Revenue dashboard (FR79). Tag B, outside V1.
- Story 14.4: Global push and system health (FR82/83). Tag B, outside V1.
- Story 14.5: Account deletion (GDPR) and feedback (FR91/92). Done.
- Story 14.6: Video tutorials, WhatsApp invite, employee activity (FR7/65/67). Tag C, backlog.
- Story 14.7: PDF/Excel export (FR55). Done through Epic 18.
- Story 14.8: API contract breaks. Done.
- Story 14.9: Trial/suspension banners and session-revoked teardown (FR20). Done.
- Story 14.10: Owner notified of employee product/stock actions. Done.
- Story 14.11: Full employee edit by the owner. Done.
- Story 14.12: Forgot password via WhatsApp OTP. Done.
- Story 14.13: Free plan limits enforcement. Done.
- Story 14.14: Category delete sync gap. Done.
- Story 14.15: Category hardening. Done.
- Story 14.16: Day-closure WhatsApp report sent to the real owner. Done.
- Story 14.17: Single owner-recipient resolution. Done.

## Requirements & Constraints

- **Seller leaderboard (FR44, the current story):**
  - **Access:** OWNER only. An EMPLOYEE gets 403 on the endpoint (`GET /api/v1/reporting/leaderboard`) and never sees any ranking, another employee's stats or any comparative data. The Équipe/Leaderboard navigation entry is not rendered for employees.
  - **Ranking criteria:** the owner can rank employees by revenue (CA, the default), by number of sales or by average basket, with a toggle between the three.
  - **Row content:** rank badge (top 3 highlighted), employee name, number of sales, total revenue in XAF and average basket.
  - **Period:** Aujourd'hui (the default); Story 4.6 named « Cette semaine / Ce mois », v1s-14-2 ships the existing `PeriodFilterChips` presets instead (D2).
  - **Data source:** sales aggregated by the seller (`actorId`).
- **Leaderboard feature gate (UX16):** in V1 the leaderboard lives only in the owner's views. The original Story 4.6 also describes an owner setting "Afficher le leaderboard aux employés" (OFF by default, with a confirmation, and anonymised rankings for employees once enabled). That setting is beyond the remediation story's acceptance criteria, so include it only if the story spec says so.
- **Expected tests:** `shouldRankEmployeesByRevenue()` and `shouldReturn403ForEmployee()`, written first (red, TDD).
- **Time windows:** business days and periods are in WAT, a fixed UTC+01:00 offset with no daylight saving. Never use the server's or the device's time zone for backend aggregation. Report instants are truncated to microseconds.
- **Day and week rules:**
  - There is one closure per store per day. A closure covers everything received since the previous closure, including offline sales synced late. Those sales keep their real `occurredAt`.
  - A sale cancelled or corrected after closure (FR90) does not change a report that was already sent.
- **Money:** amounts are XAF integers with no decimals.
- **Language:** the UI is French only (V1).
- **Performance:** a local (offline) read completes in under 2 s.
- **Tenant isolation is absolute.** Data from one tenant never appears in another tenant's view or message. Owner-directed WhatsApp sends go through the shared owner-recipient resolver.
- **Errors:**
  - Responses use the `{ error, code, domainCode, details }` envelope.
  - A not-found case returns 404 through a domain exception, never a generic 500.
  - Paginated responses carry `meta{page,total}`.
- **Routed-items stories (14.15–14.17):** every item is either fixed and ticked with the commit, or rejected with the reason written next to it.
- **Owner-recipient rule (all owner-directed sends):** recipient = WhatsApp phone of the tenant's active OWNER, resolved through the shared `OwnerRecipientResolver` port (oldest active OWNER membership, active user only, phone normalised to `+<digits>`; blank → skip/in-app only, never a fallback number). An architecture test blocks ad-hoc owner-phone lookups. All sends go through the provider-agnostic `WhatsAppPort`; WhatsApp/FCM sends are best-effort and never roll back the business operation.
- **Story 14.6 (still backlog) requirements:**
  - Employee activity (FR67): last login, sales today and this week, active session; an "inactive" badge after 48h with a WhatsApp reminder (overlaps refonte 17-3).
  - Invitations (FR65): shared through a WhatsApp `wa.me` link carrying the temporary password.
  - Tutorials (FR7): French videos of about 2 minutes per key module, on an "Aide & Tutoriels" page (Plus > Paramètres > Aide, next to feedback); never mandatory at onboarding.
  - Employee administration lives under Paramètres > Employés; deactivation asks for confirmation.
- **Other shared rules:** show a readable French message instead of a raw Drift `StateError`; check the domain event registry before creating an event; any change to the owner lookup query needs a database-level test.
- **Notifications:** owner notifications use FCM plus WhatsApp (`channel=BOTH`); bursts batched per employee in a time window (`ConcurrentHashMap` pattern of `StockAlertNotificationListener`).
- **Audit trail:** every state change emits a domain event written to the immutable audit log (actor, timestamp, store, readable label, device, source online/sync, before/after values).
- **v1s-14-2 decisions (spec-v1s-14-2-leaderboard.md):** the leaderboard reuses the existing `PeriodFilterChips` presets (Aujourd'hui default / 7 jours / 30 jours / Personnalisé) instead of « Cette semaine / Ce mois » (D2); loading uses the same spinner as the sibling Boutiques tab (D5, no shimmer widget exists); the employee opt-in toggle is routed to refonte 17-2 (D1).

## Technical Decisions

- **Backend structure:**
  - Spring Modulith with a hexagonal layout (domain, then port, then adapter). Write the port before the implementation.
  - API payloads are DTOs; never expose domain models.
  - Domains never import each other. They talk only through shared ports or domain events.
- **RBAC:**
  - The actor is passed explicitly into commands and queries.
  - Role checks and actor resolution use the shared `CurrentActor` helper, which fails closed. Use cases never read `SecurityContextHolder`.
  - REST and sync paths enforce the same guards.
- **Leaderboard pattern:**
  - The ranking metric is a Strategy, mirroring the existing `StoreRankingStrategy`, so a new criterion is added without modifying existing code.
  - It belongs in the `reporting` domain.
  - Sale aggregation should reuse the reporting conventions: WAT boundaries and the existing period handling, rather than a new date logic.
- **Flutter reads:**
  - Offline is the normal mode. A report screen must not break without network.
  - When the backend is unreachable, fall back to local Drift data (`sales` aggregated by `actorId`) or the cached result, and show a discreet freshness label rather than a blocking error.
  - Online: the backend is the source of truth and the result is cached locally.
  - After any write, call `refresh()` or `invalidate()` on the Riverpod notifier.
- **Flutter writes:** follow the existing write-path convention.
  - Online: backend first, then cache locally with `synced:true`.
  - Offline: write to Drift plus `sync_queue` in the same Drift transaction.
- **App periods:** reuse `period_filter_chips` and its date-range clamp. App screens currently compute period presets from the device time zone, which is accepted because the target devices run in WAT. Exports convert explicitly to UTC+1.
- **All-stores mode:** report screens can run with no specific store selected. The decision on how closures behave in this mode is still open, so don't invent store semantics. Scope by the active store or by all stores the way existing report screens do.
- **Repo convention:** every commit touching `keevo/app/` bumps the version in `pubspec.yaml`.

## UX & Interaction Patterns

- **Where it lives:** the leaderboard sits under Rapports > Équipe, inside the 4-tab navigation (Vendre / Stock / Rapports / Plus). Only owners can reach it.
- **Tone:** the UX treats the leaderboard as an emotional feature gate. Sensitive comparative stats appear only in the owner's dashboard, and the product moves from "surveillance" toward "healthy competition" at the owner's pace.
- **UI-3 "Soft/Modern" design system:**
  - Neutral headers, pastille actions (`SoftIconButton`), soft cards (`AppTheme.softCardDecoration` / `softShadow`) and round diluted badges (`SoftIconBadge`).
  - No saturated gradients and no decorative emoji in app UI. Top-3 badges must follow the soft badge style, not emoji.
  - Colours must be dark-mode safe: use `AppTheme.softTint(brightness)` and never hardcoded `Colors.white`.
  - Widgets live in `core/widget/`.
- **Screen states:**
  - Loading: shimmer skeletons are the target pattern; no shared shimmer widget exists yet, so report tabs (Boutiques, Équipe — v1s-14-2 D5) use a spinner.
  - Empty, first time: an encouraging text.
  - Empty because of filters: "Aucun résultat".
  - Network error: the shared `AppErrorWidget` with « Réessayer », which invalidates the provider.
  - Stale data: a discreet label such as "Dernière MAJ: il y a 2h".
- **Exporter button:** report screens may offer **Exporter**, pre-filled with the current view (Epic 18).

## Cross-Story Dependencies

- **14.2:** reuses the reporting module, the `StoreRankingStrategy` pattern, the WAT and closure-window rules from v1s-13-11, `CurrentActor` from v1s-15-9, the error/retry widget from v1s-16-11 and the period chips from v1s-16-13. The refactor re-applies it as 17-2.
- **14.6 (FR67, employee activity):** will build on the enriched audit log from Epic 18 and shares the owner-only team-stats surface with 14.2.
- **14.12 and 14.11:** depend on session revocation from 12.2.
- **14.17:** supersedes the per-sender owner-phone lookups. An architecture test blocks new ad-hoc lookups.
