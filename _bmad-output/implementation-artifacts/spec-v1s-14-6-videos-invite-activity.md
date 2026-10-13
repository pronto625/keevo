---
title: 'v1s-14-6 — Video tutorials (FR7), WhatsApp invite (FR65), employee activity (FR67)'
type: 'feature'
created: '2026-10-13'
status: 'done'
baseline_revision: '8ac3f19b935cd0db76e14b87eb4e9a0a9226f633'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: [multiple-goals, oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** The « Aide & Tutoriels » tile is a dead `Bientôt` stub (FR7); a new employee's access can only be handed over by reading out the one-time temporary password (FR65); the owner cannot see whether an employee logs in or sells (FR67).

**Approach:** (1) a backend-configured list of short French tutorial videos (external links, e.g. YouTube) shown on an « Aide & Tutoriels » page and opened outside the app; (2) a pre-filled WhatsApp (`wa.me`) invitation from the creation sheet and the employee card — never containing the password; (3) an OWNER-only activity read model built from the existing audit log, sales and refresh-token tables, surfaced on the employee card, an activity sheet, an « inactif 48 h » badge and a WhatsApp reminder.

## Boundaries & Constraints

**Always:**
- Activity endpoints are OWNER-only (`CurrentActor.requireOwner`): EMPLOYEE → 403, anonymous → 401, unknown employee id → 404 `EMPLOYEE_NOT_FOUND` (domain exception). Response envelope `ApiResponseWrapper.ok`.
- Activity is computed from existing storage only: `audit_log` (column `user_id` = actor; `USER_AUTHENTICATED` = login; already indexed by `(user_id, occurred_at)`), tenant `sales` (`employee_id` = user id, `status='COMPLETED'`, `total_amount`, `occurred_at`) and `public.refresh_tokens` (`user_id`, `tenant_id` = tenant schema name = `TenantContext.getCurrentTenant()`, `revoked`, `expires_at`). No new table, column, migration or event.
- Windows in WAT (`Africa/Lagos`, as `JdbcSellerLeaderboardRepository.WAT`) from the injected `Clock`: today = `[today 00:00 WAT, now]`, week = `[Monday of current WAT week 00:00, now]`. Amounts are XAF `long`.
- `lastLoginAt` = latest `USER_AUTHENTICATED` audit entry of the user; `lastActivityAt` = greatest of the user's latest audit entry (any action) and latest sale `occurred_at`; `activeSession` = a non-revoked, unexpired refresh token exists for (user, current tenant schema).
- `inactive` = employee status `ACTIVE` AND `(lastActivityAt ?? employee.createdAt)` is more than 48 h before now. Deactivated employees are never `inactive`.
- The WhatsApp invite and reminder texts NEVER contain a password; the temp-password sheet keeps showing/copying the password and tells the owner to communicate it separately (first-login password change already exists).
- Tutorials: only `http`/`https` URLs are exposed; a blank/invalid URL means « Bientôt disponible » (tile disabled). Videos open with `launchUrl(..., mode: LaunchMode.externalApplication)`; the app hosts no video and adds no video dependency.
- Flutter UI-3: `AppTheme`/`ColorScheme` tokens only (no hard-coded colors), `SoftIconBadge`, `AppErrorWidget` for blocking errors, French copy, `flutter analyze` 0 warning. Bump `keevo/app/pubspec.yaml` 1.1.37+36 → 1.1.38+37.

**Never:** in-app video player (`video_player`/`chewie`) or self-hosted video; password in any WhatsApp/share text; activity visible to EMPLOYEE (no route, no card stats, no endpoint access); new persistence for activity; blocking the team list when activity cannot load (cards simply show no stats).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Activity, owner | OWNER GETs `/api/v1/employees/{id}/activity`; employee sold 2×5 000 today, 1×3 000 on Monday, logged in yesterday | 200: todayCount 2 / todayAmount 10 000, weekCount 3 / weekAmount 13 000, `lastLoginAt` = yesterday's login, `inactive` false | — |
| Activity, employee caller | EMPLOYEE token | 403 | envelope |
| Activity, unknown id | random UUID | 404 `EMPLOYEE_NOT_FOUND` | envelope |
| Inactive | ACTIVE employee, last audit/sale 49 h ago | `inactive` true; card badge « Sans activité 48 h+ »; sheet offers « Relancer via WhatsApp » | — |
| Fresh employee | created 2 h ago, no activity | `inactive` false, `lastActivityAt` null (« Aucune activité ») | — |
| Deactivated employee | status INACTIVE, idle 10 days | `inactive` false | — |
| Cancelled sale | today sale with status CANCELLED | not counted | — |
| All activity | OWNER GETs `/api/v1/employees/activity` | 200 list, one entry per employee row, same fields | — |
| Team page offline | activity request fails | list renders as before, cards without stats | no error UI on the list |
| Activity sheet offline/error | `getActivity` throws | `AppErrorWidget` « Réessayer » re-fetches | — |
| Invite after creation | create succeeds with phone `+237690000001`, store « Boutique A » | sheet button « Inviter via WhatsApp » opens `https://wa.me/237690000001?text=…` containing first name, store name, phone; text contains no password | launch failure → snackbar « Impossible d'ouvrir WhatsApp sur cet appareil. » |
| Tutorials online | GET `/api/v1/help/tutorials` returns entries | page lists them (title, module, ~duration); tap opens URL externally; list cached locally | — |
| Tutorials offline with cache | request fails, cache present | cached list + discreet offline notice; tapping a video while offline → snackbar « Connexion internet requise pour regarder la vidéo. » | — |
| Tutorials offline, no cache | request fails, no cache | `AppErrorWidget` with « Réessayer » | — |
| Tutorial without URL | entry url blank | tile disabled, subtitle « Bientôt disponible » | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/identity/employee/adapter/in/rest/EmployeeController.java` -- add the two GET endpoints (`/activity` and `/{employeeId}/activity`), mirror `CurrentActor.requireOwner` + `ApiResponseWrapper.ok` style.
- `keevo/backend/src/main/java/com/keevo/identity/employee/domain/port/out/EmployeeRepository.java` -- `findById`, `findAllOrderByStatusAndCreatedAt` (employee rows: id, userId, status, createdAt) — reuse for 404 and the "all" list.
- `keevo/backend/src/main/java/com/keevo/reporting/leaderboard/adapter/out/persistence/JdbcSellerLeaderboardRepository.java` -- JDBC + WAT `Timestamp` pattern to mirror; tenant tables resolve through the request search_path; qualify `public.refresh_tokens` explicitly.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/AuditEventListener.java:158` -- login writes action `USER_AUTHENTICATED` with `user_id` = the user.
- `keevo/backend/src/main/java/com/keevo/identity/auth/application/service/SessionTokenIssuer.java:50-80` -- refresh token `tenant_id` = tenant schema name.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ClockConfig.java` -- injectable UTC `Clock`.
- `keevo/backend/src/test/java/com/keevo/identity/employee/adapter/in/rest/EmployeeControllerTest.java` -- controller test style to extend; `keevo/backend/src/test/java/com/keevo/reporting/export/adapter/in/rest/ExportControllerSecurityTest.java` -- `@WebMvcTest` + `SecurityConfig` slice for 401/403; `keevo/backend/src/test/java/com/keevo/shared/infrastructure/persistence/PostgresIntegrationTest.java` -- base for the real-SQL test (create missing tenant tables in the test schema if the baseline lacks them, e.g. `audit_log`).
- `keevo/backend/src/main/java/com/keevo/feedback/` -- support module (`@ApplicationModule(allowedDependencies = {"identity"})`); host the tutorial catalog here as a new `feedback/tutorial` hexagonal subpackage. `keevo/backend/src/main/resources/application.yml:79` -- `keevo:` config root.
- `keevo/app/lib/features/settings/presentation/page/settings_page.dart:320-335` -- « Aide & Tutoriels » stub tile (`onTap: () {}`, `badge: 'Bientôt'`), visible to both roles.
- `keevo/app/lib/core/router/app_router.dart:617-678` -- team and `/settings/feedback` routes; add `/settings/help` (not in the OWNER-only prefix list at ~L158).
- `keevo/app/lib/core/di/providers.dart` -- `dioProvider`, `sharedPreferencesProvider`, `connectivityServiceProvider`.
- `keevo/app/lib/features/inventory/presentation/page/inventory_gap_report_page.dart:222-240` -- `wa.me` + `launchUrl(externalApplication)` + failure snackbar pattern; its test (`test/features/inventory/presentation/page/inventory_gap_report_page_test.dart:102`) mocks the `plugins.flutter.io/url_launcher` channel.
- `keevo/app/lib/features/team/` -- `employee_card.dart` (card + action sheet, badges `_StatusBadge`/`_PwdBadge`), `temp_password_bottom_sheet.dart`, `create_employee_page.dart` (`_completePhone`, `_selectedStore`), `team_page.dart`, `employee_provider.dart`, `employee_repository(_impl).dart`, `remote_employee_datasource.dart`, `employee_model.dart` (`phoneNumber` null when from local cache), `employee_action_requires_connection_exception.dart`.
- `keevo/app/lib/features/profitability/presentation/widget/seller_leaderboard_row.dart:15` -- XAF `NumberFormat` style to reuse.
- `keevo/app/test/features/team/**`, `test/features/settings/presentation/page/settings_page_test.dart` -- existing tests to keep green (team page/card tests mock `EmployeeRepository` with mocktail: stub the new methods).

## Tasks & Acceptance

**Execution:**
- `identity/employee/domain/model/EmployeeActivity.java`, `domain/port/in/GetEmployeeActivityUseCase.java` (`forEmployee(UUID employeeId)`, `forAll()`), `domain/port/out/EmployeeActivityRepository.java` (raw per-user aggregates for a set of user ids given window instants + tenant schema), `application/service/GetEmployeeActivityService.java` (Clock, WAT windows, 48 h rule, 404), `adapter/out/persistence/JdbcEmployeeActivityRepository.java`, `adapter/in/rest/dto/EmployeeActivityResponseDto.java` + controller endpoints -- FR67 backend read model.
- Backend tests -- service unit test (`shouldFlagEmployeeInactiveAfter48h`, fresh-employee and deactivated cases, WAT windows), controller/security tests (`shouldReturnEmployeeActivityForOwner`, `shouldReturn403ForEmployee`, 401, 404), Postgres IT for the JDBC adapter (cancelled sale excluded, week vs today, login vs other audit, active vs revoked/expired token, other tenant's token ignored).
- `feedback/tutorial/**` + `application.yml` `keevo.help.tutorials` (≈6 French modules: Vendre, Catalogue & stock, Inventaire, Rapports, Équipe, Hors ligne & synchro; each `id, module, title, description, durationSeconds≈120, url` with `url: ${KEEVO_HELP_TUTORIAL_<ID>_URL:}`) -- `GET /api/v1/help/tutorials` for any authenticated user, non-http(s) URLs returned blank; controller + service unit tests.
- `keevo/app/lib/features/help/**` (model, remote datasource, repository with SharedPreferences cache, provider, `help_tutorials_page.dart`) + `/settings/help` route + settings tile wired (badge removed) -- FR7 app; tests incl. `shouldOpenTutorialVideoExternally` and the offline rows.
- `keevo/app/lib/features/team/presentation/util/employee_whatsapp.dart` -- pure builders `buildInviteMessage`, `buildReminderMessage`, `whatsAppUri(phone, text)` (digits only), `formatLastSeen(at, now)`, plus an `openWhatsApp` helper with the failure snackbar; optional `kAppDownloadUrl` from `String.fromEnvironment('KEEVO_APP_DOWNLOAD_URL')` (blank → the invite says « Installez l'application Keevo » without link).
- `temp_password_bottom_sheet.dart` + `create_employee_page.dart` -- optional `invitePhone`/`firstName`; « Inviter via WhatsApp » button; warning text says the password is never in the invitation; test `shouldOpenWhatsAppShareLinkForInvite` (asserts URL and absence of the password).
- `employee_activity.dart` model, repository/remote (`listActivity`, `getActivity`; offline → `EmployeeActionRequiresConnectionException`), providers (`employeeActivityMapProvider`, `employeeActivityProvider` family), `team_page.dart` (passes `activity` to cards, invalidates on refresh), `employee_card.dart` (stats line, « Sans activité 48 h+ » badge, actions « Voir l'activité » and « Renvoyer l'invitation WhatsApp » when phone known, owner only), new `employee_activity_sheet.dart` (spinner / `AppErrorWidget` / stats + « Relancer via WhatsApp » when inactive) -- FR67 app; tests incl. `shouldShowEmployeeActivityStats`.
- `keevo/app/pubspec.yaml` -- version 1.1.38+37.

**Acceptance Criteria:**
- Given an OWNER on Paramètres, when they tap « Aide & Tutoriels », then the tutorials page opens (no `Bientôt` badge); an EMPLOYEE reaches the same page from the Support section.
- Given an EMPLOYEE session, when the app renders the team surfaces or calls the activity endpoints, then no activity data is shown or returned (403).
- Given the activity data, when the owner opens Équipe, then each active employee card shows today's sales count/amount and last activity, without delaying the list.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 34 findings — high 0, medium 3, low 21, false 10, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) FR7 test name `shouldPlayEmbeddedVideoTutorial` replaced by `shouldOpenTutorialVideoExternally` — deliberate per orchestrator directive (no in-app player), recorded as D1.
  - `[low]` `[reject]` (intent) tutorial URLs ship blank (« Bientôt disponible ») — no real videos exist yet; URLs are ops config (now documented in `.env.example` / `ENV_REFERENCE.md`).
  - `[false]` `[reject]` (intent) invite button lives in the temp-password sheet opened by `create_employee_page`, not on the form — the flow starts from the page and the end-to-end page test covers it.
  - `[false]` `[reject]` (intent) card fed by an extra bulk endpoint — D3: one call for N cards; the named `/{id}/activity` endpoint exists and feeds the sheet.
  - `[false]` `[reject]` (intent) card shows only today + last activity; login/week/session in the sheet — the card stays readable on small screens; all FR67 fields are one tap away.
  - `[false]` `[reject]` (intent) reminder in the sheet, not on the card — the badge is on the card; reminder in the sheet keeps the card tap target unique.
  - `[false]` `[reject]` (intent) `refresh_tokens` used for « session active » — existing storage, no new persistence; meaning documented (holds a valid session on a device).
  - `[false]` `[reject]` (intent) OWNER-only enforced client + server — matches intent.
  - `[false]` `[reject]` (intent) version 1.1.38+37 vs literal 1.1.37+36 — the brief gives the current version to bump from.
  - `[false]` `[reject]` (intent) tutorials open to EMPLOYEE — UX places Aide under Paramètres for both roles; settings page already shows Support to employees.
  - `[low]` `[patch]` (verif-gap) regenerate → invite path untested — added card test (wa.me target, store name, no password).
  - `[low]` `[patch]` (verif-gap) remote datasource paths/envelope untested — added mock-Dio tests for employee activity and tutorials datasources.
  - `[low]` `[patch]` (verif-gap) EMPLOYEE `/settings/help` via the real guard untested — assertion added to `global_auth_redirect_test.dart`.
  - `[low]` `[reject]` (verif-gap) no binding test for `keevo.help.tutorials` in application.yml — `PropertiesTutorialCatalogTest` covers mapping; a mis-bound config is visible on first manual check of the page; full-context binding test disproportionate.
  - `[low]` `[patch]` (edge) placeholder store name (« Chargement... » / « Boutique inconnue ») could enter invite/reminder — resolved name or '' passed (also on the regenerate path via `inviteStoreName`).
  - `[low]` `[reject]` (edge) phone with no digits → `wa.me/?text=` — phone numbers are validated E.164 at creation/edit; unreachable in practice.
  - `[medium]` `[patch]` (edge) future-dated sale sets a future lastActivityAt and hides inactivity — `last_sale_at` capped at now; IT `futureSaleIgnoredForLastSale` added.
  - `[low]` `[reject]` (edge) `formatLastSeen` negative diff → « à l'instant » — the server cap removes the source; acceptable display.
  - `[low]` `[reject]` (edge) SharedPreferences write failure discards fresh tutorials — unlikely; fix adds a branch.
  - `[low]` `[reject]` (edge) activity map not refreshed on reconnect/after create — new employee simply has no stats until the page is reopened; adding listeners adds complexity.
  - `[low]` `[reject]` (edge, claim) « invalidates on refresh » with no pull-to-refresh — the refresh paths (retry, card actions) do invalidate; no gesture was promised.
  - `[medium]` `[patch]` (blind) future sale hides idle employee — same root cause as the edge finding above; patched there.
  - `[medium]` `[patch]` (blind) resent invitation tells an employee who already changed the password to use a temporary one — « Renvoyer l'invitation » shown only while `passwordChangeRequired`; test added.
  - `[low]` `[patch]` (blind) download link blank by default and undocumented — `--dart-define=KEEVO_APP_DOWNLOAD_URL` documented in `docs/ENV_REFERENCE.md` (no public store listing exists yet: applicationId is still `com.example.keevo`).
  - `[low]` `[patch]` (blind) `KEEVO_HELP_TUTORIAL_*_URL` undocumented — added to `.env.example` and `ENV_REFERENCE.md`.
  - `[low]` `[reject]` (blind) WhatsApp failure snackbar hidden behind modal sheets — only when WhatsApp is absent (rare on target devices); same pattern as the existing inventory share; fix needs a nested Scaffold.
  - `[low]` `[reject]` (blind) card activity stale while the page stays open — negligible; the sheet always loads fresh data.
  - `[low]` `[patch]` (blind) tutorial description not displayed — shown as a second subtitle line; page test checks it. `fetchedAt` not displayed — kept for cache logic only (rejected part).
  - `[low]` `[reject]` (blind) tutorials not filtered by role — harmless for employees to see owner topics; no role field in scope.
  - `[low]` `[reject]` (blind) duplicate root with edge cache-write finding — see above.
  - `[low]` `[reject]` (blind) activity query scans all sales of an employee for MAX — employees are plan-bounded and `sales` is indexed by employee; acceptable for V1 volumes (residual risk noted).
  - `[false]` `[reject]` (blind) browsing-only employee flagged inactive — intended (D4): « inactif » = no audited action and no sale for 48 h; the reminder targets exactly that.
  - `[low]` `[reject]` (blind) idle badge/reminder on owner's own or co-owner rows — owner rows are rare and a reminder to a co-owner is harmless.
  - `[low]` `[reject]` (blind) weak `isNot(contains('mot de passe :'))` assertion — the sheet and card tests assert the actual password string is absent.

## Design Notes

- D1 FR7 « tutoriels intégrés » = listed in-app, played by the external YouTube/browser app (orchestrator directive: no self-hosting; avoids a 2 MB+ player and video bandwidth on low-end devices). The brief's TDD name `shouldPlayEmbeddedVideoTutorial` becomes `shouldOpenTutorialVideoExternally`. URLs are backend configuration so videos can be published without an app release; until set, entries read « Bientôt disponible ».
- D2 FR65 invite = wa.me message with store, phone and app link; the temporary password is communicated separately (shown once in the sheet) and changed at first login — the brief's « wa.me with temp-password » is rejected for security.
- D3 FR67 activity lives in `identity/employee` (endpoint under `/api/v1/employees`), read through a JDBC read-model over existing tables (same approach as the 14-2 leaderboard). A batch `/activity` endpoint feeds the card list (one call instead of N); `/{id}/activity` feeds the sheet.
- D4 « inactif » uses last activity (any audited action or sale), not last login: refresh tokens last 360 days, so a working employee may never log in again.
- D5 « Cette semaine » = ISO week starting Monday 00:00 WAT.
- D6 No technical data shown to end users (user rule, 2026-10-13): the activity card/sheet, help page and WhatsApp texts show only French labels, names, readable relative dates, amounts and the login phone — no UUID, raw code (status/action/source), module id, device id or JSON. Checked on the final diff. Cleanup of existing screens (exports, audit detail) is a separate story.

## Verification

**Commands (one at a time, foreground, never a full suite):**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -o test -Dtest='<new/changed test classes>' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green.
- `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh backend identity feedback shared architecture` -- expected: 0 failure / 0 error.
- `cd keevo/app && flutter test --concurrency=4 <new/changed test files>` then `/home/toor/.cache/keevo-orchestration/run-tests-chunked.sh app test/core test/features/team test/features/settings test/features/help` -- expected: 0 failure (incl. `no_hardcoded_colors_test`).
- `cd keevo/app && flutter analyze` -- expected: 0 error, 0 warning.

## Auto Run Result

Status: done

**Summary:** FR7 « Aide & Tutoriels » page (backend-configured external video links, opened outside the app, cached offline), FR65 WhatsApp invitation without any password (after creation, after password regeneration, and « Renvoyer l'invitation » while the temporary password is pending), FR67 OWNER-only employee activity (`GET /api/v1/employees/activity` and `/{id}/activity`) read from audit_log, sales and refresh_tokens — card stats, « Sans activité 48 h+ » badge, activity sheet with WhatsApp reminder. No new storage. App 1.1.37+36 → 1.1.38+37.

**Files changed (main):**
- `keevo/backend/src/main/java/com/keevo/identity/employee/**` — activity model, ports, service (WAT windows, 48 h rule), JDBC read model, DTO, controller endpoints.
- `keevo/backend/src/main/java/com/keevo/feedback/tutorial/**` + `application.yml` (`keevo.help.tutorials`) — tutorial catalog endpoint `GET /api/v1/help/tutorials`.
- `keevo/backend/.env.example`, `docs/ENV_REFERENCE.md` — tutorial URL variables, app download-link build flag.
- `keevo/app/lib/features/help/**`, `core/router/app_router.dart`, `settings_page.dart` — tutorials page and route.
- `keevo/app/lib/features/team/**` — activity model/repo/providers, `employee_whatsapp.dart`, card, activity sheet, temp-password sheet, create page.
- Tests: backend service/controller/security/Postgres IT/tutorial tests; app card, sheet, page, repository, datasource, router, whatsapp-helper tests.
- `_bmad-output/implementation-artifacts/epic-14-context.md` — recompiled (14.6 focus), then aligned with D1/D2.

**Review:** 1 pass, 34 findings (medium 3, low 21, false 10). Patched: 3 medium entries (2 distinct roots: future-dated sale cap; resend-invite wording) and 8 low (store-name placeholder, regenerate→invite test, datasource tests, route-guard test, tutorial description, env/build docs). Rejected: 23, reasons in the triage log. Nothing deferred. Follow-up review recommendation: false — no high patched; the two medium roots are each covered by a dedicated test (Postgres IT `futureSaleIgnoredForLastSale`, card test for the hidden resend action).

**Verification:**
- Targeted backend tests green; `run-tests-chunked.sh backend identity feedback shared architecture`: 361 / 37 / 452 / 6 — 0 failures, 0 errors.
- `run-tests-chunked.sh app test/core test/features/team test/features/help test/features/settings`: 470 / 121 / 9 / 50 — all passed (incl. `no_hardcoded_colors_test`).
- `flutter analyze`: 0 errors, 0 warnings (625 infos).

**Residual risks:**
- All tutorials read « Bientôt disponible » until `KEEVO_HELP_TUTORIAL_*_URL` are set on the (manual) backend deploy.
- The invite has no download link until builds pass `--dart-define=KEEVO_APP_DOWNLOAD_URL` (no public store listing yet).
- Activity query reads an employee's whole sales history for the last-sale MAX (fine at V1 volumes).
- WhatsApp-missing snackbar may be hidden behind the bottom sheets (rare).
- An employee who only browses (no audited action, no sale) for 48 h is flagged inactive — intended (D4).
