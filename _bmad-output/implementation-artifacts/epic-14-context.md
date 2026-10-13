# Epic 14 Context: Récupération scope V1 — Gaps fonctionnels (Pistes A/B/C)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Ship on the V1 production line (`deploy`) the functional requirements that were documented and promised but never built (about 22 FRs), plus the runtime API breaks and integrity gaps found by the post-launch audits. V1 must stay usable in production while the modular refactor ("refonte") proceeds separately. Each story has a track tag. **A** means a V1 patch shipped now. **C** means a V1 patch that the refactor re-applies later. **B** means refactor-native work that is excluded from the V1 track and has no V1 tracker key. The epic turns features that the PRD and UX promised (seller leaderboard, employee activity, tutorials, invitations, account deletion, feedback, password reset, employee edit) into working features. It also covers the fixes that stop one tenant's data from leaking or being lost.

## Stories

- Story 14.1: Product variants (FR22). Tag B, outside V1.
- Story 14.2: Seller leaderboard (FR44). Done.
- Story 14.3: Revenue dashboard (FR79). Tag B, outside V1.
- Story 14.4: Global push and system health (FR82/83). Tag B, outside V1.
- Story 14.5: Account deletion (GDPR) and feedback (FR91/92). Done.
- Story 14.6: Video tutorials, WhatsApp invite, employee activity (FR7/65/67). Tag C, **in progress (current)**. Refonte 17-3 covers FR67.
- Story 14.7: PDF/Excel export (FR55). Done through Epic 18.
- Stories 14.8 to 14.17: API contract breaks, FR20 banners, owner notifications, employee edit, forgot password, free plan limits, category sync and hardening, day-closure owner phone, owner-recipient resolution. All done.

## Requirements & Constraints

- **The three requirements of the current story (14.6):**
  - **FR7, video tutorials:** the system can show built-in video tutorials in French, about 2 minutes per key module.
  - **FR65, invitations:** an owner can invite employees by WhatsApp number or by a unique link.
  - **FR67, employee activity:** an owner can see each employee's activity: last login and sales made.
- **What 14.6 must deliver:**
  - **FR7:** an "Aide & Tutoriels" page listing the modules; videos are external links (backend-configured URLs) opened outside the app — no self-hosting, no in-app player (v1s-14-6 D1). Tutorials are never mandatory. Onboarding goes straight to the POS screen, and the target is that more than 90% of first uses succeed without a tutorial.
  - **FR65:** today an invitation is only a temporary password. Add a WhatsApp share from `create_employee_page`, using `url_launcher` to open a `wa.me` link carrying the store, login phone and app link — never the password, which the owner communicates separately (v1s-14-6 D2). The employee must change the password at first login; that rule is unchanged.
  - **FR67:** `GET /api/v1/employees/{id}/activity`, OWNER only. It returns last login, sales today and this week, and whether a session is active. The employee card shows these stats, plus an "inactive" badge after 48h with no activity and a WhatsApp reminder action.
  - **Tests written first (red):** `shouldPlayEmbeddedVideoTutorial()`, `shouldOpenWhatsAppShareLinkForInvite()`, `shouldShowEmployeeActivityStats()`.
- **Access:**
  - Employee activity is owner-only data. An EMPLOYEE gets 403, and comparative or individual stats never appear on employee screens.
  - The UX frames tracking as "confiance" for the owner but "surveillance" for the employee. Keep activity stats inside owner views only.
- **Time windows:** "today" and "this week" use WAT, a fixed UTC+01:00 offset with no daylight saving. Never use the server's time zone for backend aggregation.
- **Money:** amounts are XAF integers with no decimals.
- **Language:** the UI is French only (V1).
- **Performance:** a local (offline) read completes in under 2 s.
- **Tenant isolation is absolute.** Data from one tenant never appears in another tenant's view or message.
- **Owner recipients:** any owner-directed WhatsApp send goes through the shared `OwnerRecipientResolver`. Ad-hoc owner-phone lookups are blocked by an architecture test.
- **WhatsApp sends:**
  - Sends go through the provider-agnostic `WhatsAppPort`. They are best-effort and never roll back the business operation.
  - Client-side shares (`wa.me`) open the user's own WhatsApp; no backend send is involved.
- **Errors:**
  - Responses use the `{ error, code, domainCode, details }` envelope.
  - A not-found case returns 404 through a domain exception, never a generic 500.
  - Paginated responses carry `meta{page,total}`.
  - Show a readable French message instead of a raw Drift `StateError`.
- **Audit trail:**
  - Every state change emits a domain event written to the immutable audit log.
  - Check the domain event registry before creating a new event.

## Technical Decisions

- **Backend structure:**
  - Spring Modulith with a hexagonal layout (domain, then port, then adapter). Write the port before the implementation.
  - API payloads are DTOs; never expose domain models.
  - Domains never import each other. They talk only through shared ports or domain events. For example, employee activity needs sales counts from reporting/commerce, which must come through a port.
- **RBAC:**
  - The actor is passed explicitly into use cases, resolved by the shared `CurrentActor` helper, which fails closed. Use cases never read `SecurityContextHolder`.
  - REST and sync paths enforce the same guards.
- **Reuse from 14.2:** the leaderboard already aggregates COMPLETED sales by seller (`actorId`) within WAT windows and the store scope, with inactive employees shown at 0. Reuse that aggregation logic rather than writing new date logic.
- **Flutter reads:**
  - Offline is the normal mode. When the backend is unreachable, fall back to local Drift data (for example `sales` by `actorId`) or the cached result, with a discreet freshness label rather than a blocking error.
  - After any write, call `refresh()` or `invalidate()` on the Riverpod notifier.
- **Flutter writes:**
  - Online: backend first, then cache locally with `synced:true`.
  - Offline: write to Drift plus `sync_queue` in the same Drift transaction.
- **Repo convention:** every commit touching `keevo/app/` bumps the version in `pubspec.yaml`.

## UX & Interaction Patterns

- **Help:** "Aide & Tutoriels" sits under Plus > Paramètres > Aide, next to the feedback form.
- **Adding an employee:** Paramètres > Employés > "Ajouter", then phone number, store, role Employé and an auto-generated password. The owner shares the credentials (now via the WhatsApp share). Mandatory password change at first login.
- **Employee activity and deactivation:** Paramètres > Employés > select an employee > view activity (last login, sales) > Désactiver. Deactivation asks for confirmation, revokes sessions immediately and keeps sales history. Employee lists use ListTile rows; confirmations use dialogs.
- **UI-3 "Soft/Modern" design system:**
  - Neutral headers, pastille actions (`SoftIconButton`), soft cards (`AppTheme.softCardDecoration`) and round diluted badges (`SoftIconBadge`). The inactive badge follows this badge style.
  - No saturated gradients and no decorative emoji in app UI.
  - Colours must be dark-mode safe: use `AppTheme.softTint(brightness)` and never hardcoded `Colors.white`.
  - Shared widgets live in `core/widget/`.
- **Screen states:**
  - Loading: a spinner, because no shared shimmer widget exists.
  - Empty: an encouraging text.
  - Network error: the shared `AppErrorWidget` with « Réessayer », which invalidates the provider.
  - Stale data: a discreet label such as "Dernière MAJ: il y a 2h".

## Cross-Story Dependencies

- **14.6 (FR67) builds on:** the seller aggregation from 14.2, the enriched audit log from Epic 18 and the owner-only team surface. Refonte 17-3 re-applies FR67.
- **14.6 (FR65) builds on:** the employee create and edit flow from 14.11 (phone numbers normalised to `+<digits>`) and the free-plan employee limit from 14.13.
- **Session work:** "active session" and deactivation rely on session revocation from 12.2 and 12.12.
- **Owner-directed sends:** 14.17 replaced the per-sender owner-phone lookups.
