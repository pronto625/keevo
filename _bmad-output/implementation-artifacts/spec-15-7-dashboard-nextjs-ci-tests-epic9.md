---
title: '15.7 — Next.js dashboard: CI/tests + Epic 9 completion'
type: 'chore'
created: '2026-10-31'
status: 'done'
baseline_revision: '6533ad099282ad0c9819c486f80eed5dbce05955'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 15.7 asked for a tested dashboard (lint + tests blocking in CI) and working Revenus (9-3), Analytics (9-4) and Santé système (9-5) screens. Investigation (2026-10-31) shows this already exists: `.github/workflows/dashboard-ci.yml` runs lint, typecheck, `vitest run` and build on main/develop; 56 test files / 286 tests pass; the three pages call `/api/v1/admin/revenue/*`, `/admin/analytics/*`, `/admin/health[/status]`, all present in the backend. One routed item remains: route pages are `'use client'` modules (`src/app/tenants/page.tsx` named in the item; also `alertes`, `analytics`, `notifications`, `retours`, `revenus`, `sante`), whereas the spec wanted a server shell + client component (route metadata, smaller client boundary).

**Approach:** Turn each `'use client'` route page into a server component shell that exports French route `metadata` (page title « Keevo Admin — <écran> ») and renders a client component moved next to it (e.g. `TenantsPageClient.tsx`); no behaviour change. Close the story by recording the existing CI/tests/pages as the delivery evidence.

## Boundaries & Constraints

**Always:**
- Behaviour, data fetching (client-side, token in the browser) and markup unchanged; existing tests keep passing (adapt imports only).
- Each route page file has no `'use client'` and exports `metadata` with a French title; the client component keeps `'use client'`.
- A guard test (Vitest, reading `src/app/**/page.tsx`) fails if a route page contains `'use client'`.
- CI unchanged except if a step is missing; `npm run lint`, `npx tsc --noEmit`, `npm test`, `npm run build` pass locally.

**Never:**
- No new screens, no backend change, no auth change, no Server-side data fetching (the admin token is client-side).
- No dependency upgrades.

</intent-contract>

## Code Map

- `keevo/dashboard/src/app/{tenants,alertes,analytics,notifications,retours,revenus,sante}/page.tsx` -- `'use client'` route pages to split.
- `keevo/dashboard/src/app/layout.tsx` -- root layout (existing metadata pattern).
- `keevo/dashboard/src/**/__tests__/` and `*.test.tsx` -- existing tests importing the pages.
- `keevo/dashboard/vitest.config.mts`, `.github/workflows/dashboard-ci.yml`.

## Tasks & Acceptance

**Execution:**
- each listed page -- server shell with `metadata` + `<XxxPageClient />`; move the current body to `XxxPageClient.tsx` (same folder).
- `src/app/__tests__/route-pages-server.test.ts` (new) -- guard test.
- tests -- adapt imports.

**Acceptance Criteria:**
- Given the dashboard repo, when CI runs, then lint + typecheck + tests + build run and block on failure (already true; verified).
- Given the Revenus, Analytics and Santé pages, then they call existing backend admin endpoints (verified) and render as before.
- Given any route page, when the guard test runs, then no page module is a client module.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 24 findings — high 0, medium 1, low 15, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` (vg/blind) route shells never rendered by a test (a shell returning the wrong client or null passes CI) — test imports each `page.tsx` default export and asserts it returns its own `*PageClient`.
  - `[low]` `[patch]` (blind) `login/page.tsx` has no French title — « Keevo Admin — Connexion ».
  - `[low]` `[patch]` (blind) root layout English (`Keevo Admin Dashboard`, `lang="en"`) — French title template « Keevo Admin — %s », French description, `lang="fr"`; pages set only their label.
  - `[low]` `[patch]` (blind/edge) metadata check limited to a hand-kept list; sanity check counts files only; text regex — assert on the imported module's `metadata.title` for every discovered page (explicit exception for the redirect-only root page).
  - `[low]` `[patch]` (blind/edge) guard only scans `page.tsx` — also `.ts/.jsx/.js` and `layout`/`template` segment files.
  - `[low]` `[patch]` (blind) titles differ from the screen headings/sidebar — align on the sidebar labels.
  - `[low]` `[patch]` (blind) test import names still say `Page` — renamed to the client component names.
  - `[low]` `[patch]` (blind) Tenants and Products screens have no test — smoke render tests.
  - `[low]` `[reject]` (blind) client boundary not smaller (AdminLayout inside the client) — moving the layout changes rendering/auth flow; out of a behaviour-preserving split.
  - `[low]` `[reject]` (blind) shells lack doc comments — two-line shells; the client file keeps the story comment.
  - `[low]` `[reject]` (edge) BOM / other directive before `"use client"` — not used in the code base.
  - `[low]` `[reject]` (edge) symlinked route folders — none in the repo.
  - `[low]` `[reject]` (edge claim) "any route page" wording vs `page.tsx` only — fixed by the extension patch.
  - `[low]` `[reject]` (blind) spec omits the monitoring routes — recorded here: the guard required splitting them too.
  - `[low]` `[reject]` (edge) other extension — same as extension patch row.
  - `[false]` `[reject]` (intent) CI blocking not re-verified in the diff — workflow unchanged and verified (lint, tsc, test, build steps; failing step fails the job).
  - `[false]` `[reject]` (intent) screens not exercised against the real backend — the 12 admin endpoints exist (route comparison 2026-10-31); a live session needs super-admin credentials, which the operator did not provide.
  - `[false]` `[reject]` (intent) no backend completion — none missing.
  - `[false]` `[reject]` (intent) widened to ten pages — the guard covers all route pages.
  - `[false]` `[reject]` (intent) routed item record missing — recorded in the epics doc at finalisation.
  - `[false]` `[reject]` (intent) tests no longer render route entries — patched (shell test).
  - `[false]` `[reject]` (intent) Tenants/Products untested — patched.
  - `[false]` `[reject]` (vg other) client moves byte-identical — confirmation, no defect.

## Verification

**Commands:**
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npx vitest run && npm run build` -- expected: all succeed.

## Auto Run Result

Status: done

**Summary:** Story 15.7's main deliverables already existed and were verified on 2026-10-31: `.github/workflows/dashboard-ci.yml` (npm ci, lint, `tsc --noEmit`, `vitest run`, build — a failing step fails the job), Vitest/RTL suites, and the Revenus / Statistiques / Santé système screens wired to the 12 backend admin endpoints (`/api/v1/admin/revenue/*`, `/analytics/*`, `/health[/status]`), all present. Routed item resolved: the ten `'use client'` route pages (tenants, alertes, analytics, notifications, retours, revenus, sante, monitoring/activity|products|sync) are now server shells exporting French `metadata` and rendering a `*PageClient` (verbatim move); root layout French (`lang="fr"`, title template « Keevo Admin — %s »), login titled « Connexion »; guard test checks every page/layout/template has no `'use client'`, each page's real `metadata.title`, and that each shell renders its own client component; smoke tests added for Tenants and Catalogue Produits.

**Review:** 24 findings — 1 medium patched (shells never rendered by a test), 7 lows patched, rest rejected with reasons.

**Follow-up review recommended:** false.

**Verification:** lint OK, `tsc --noEmit` OK, Vitest 59 files all pass, `npm run build` OK (static prerender, French titles in the HTML). Live dashboard session not run: needs super-admin credentials (not available to the agent).
