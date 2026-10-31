---
title: '15.6 — « Mes modules » screen (owner)'
type: 'feature'
created: '2026-10-31'
status: 'done'
baseline_revision: 'db393abc50dc90b73f0e326e9e458f312dc73c67'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-15-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-15-1-entitlements-provider-flutter.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** An owner cannot see which modules their business has, which are included for free, which are on trial, active, expired (read-only grace) or not subscribed, nor when they end. The upgrade buttons say « Passer à Premium » even for a free module that was switched off (review 15.2).

**Approach:** An owner-only « Mes modules » screen built on `GET /api/v1/tenants/me/modules` (states + dates already there) enriched with the subscription status (`TRIAL` / `ACTIVE` / `EXPIRED` / …, already in `TenantModuleView.status`) and whether the module is free (manifest `free`, category). Modules are grouped (« Inclus », « Mes modules payants », « Disponibles ») with French status badges and dates; the call to action asks the owner to contact Keevo (no self-service activation in V1 — activation is manual after Mobile Money payment). The app's upgrade buttons and the « module non activé » screen lead to « Mes modules ».

## Boundaries & Constraints

**Always:**
- Backend: `TenantModuleDto` gains `status` (stored subscription status name, null when no row), `free` (boolean from the manifest) and `category` — for every caller role, dates stay owner-only (15.1 rule). No new endpoint; the `platform` category (admin) is never returned to tenants.
- Statuses shown in French: « Inclus » (free module), « Essai » (TRIAL, with « jusqu'au <date> »), « Actif » (ACTIVE, « jusqu'au <date> » when an end exists), « Expiré — lecture seule jusqu'au <date> » (READ_ONLY grace), « Expiré » (EXPIRED / DISABLED after a subscription), « Non activé » (never subscribed). Dates in Africa/Douala, `dd MMM yyyy` French. Display names from the server; no module ids, codes or enum names on screen.
- CTA « Demander l'activation » on a paid module not active: opens WhatsApp to Keevo support with a prefilled French message naming the business and the module (`wa.me` pattern of `features/team/presentation/util/employee_whatsapp.dart`); the support number is a single constant/config value (`--dart-define=SUPPORT_WHATSAPP`, with the dev/prod default already used elsewhere if one exists — otherwise a constant in keevo_core with a TODO-free comment explaining where it is set). For a free module that is off: « Contactez le support pour le réactiver » (same link).
- Route `/settings/modules` (owner-only, in `_ownerOnlyPrefixes`); entry « Mes modules » in the owner settings « Gestion » section next to « Abonnement »; pull-to-refresh calls `entitlementsProvider.refresh()`.
- `PremiumUpgradeButton` / `openPremiumUpgrade` and the « module non activé » owner CTA open « Mes modules » (label « Voir mes modules »); employee wording unchanged.
- Bump `keevo/app/pubspec.yaml` 2.0.15+87 → 2.0.16+88.

**Never:**
- No self-service purchase or activation, no payment flow, no change to module lifecycle rules.
- No super-admin console (Story 16.4).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Free module | `catalog`, free | « Inclus » section, no CTA | — |
| Trial | `invoicing` TRIAL, expiresAt in 5 days | « Essai jusqu'au … » | — |
| Grace | `multi-store` READ_ONLY, graceUntil | « Expiré — lecture seule jusqu'au … » + « Demander l'activation » | — |
| Not subscribed | `profitability` DISABLED, no row | « Disponibles » section, « Non activé » + CTA | — |
| Employee | employee opens `/settings/modules` | redirected (owner-only) | — |
| Offline | no network | last known states (15.1 snapshot) with a French « Hors ligne » note | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/in/rest/TenantModulesController.java` (`TenantModuleDto`), `shared/module/ModuleLifecycle.java:105` (`TenantModuleView` has `status`), module manifests `keevo/backend/src/main/resources/keevo/modules/*/module.json` (`free`, `category`), registry/manifest accessors in `shared/module/` (`ModuleRegistry`).
- App: `keevo/app/packages/keevo_core/lib/entitlements/entitlements.dart` (parse new fields, keep snapshot compatibility), `keevo/app/lib/core/entitlements/entitlements_provider.dart` (`refresh`), `keevo/app/packages/keevo_core/lib/widget/entitlement_upgrade.dart` (`PremiumUpgradeButton`, `openPremiumUpgrade`, `canOfferPremiumUpgrade`), `keevo/app/lib/core/router/module_unavailable_page.dart`, `keevo/app/lib/core/router/app_router.dart` (`_ownerOnlyPrefixes`, routes), `keevo/app/lib/features/settings/presentation/page/settings_page.dart`, `subscription_page.dart`, `keevo/app/lib/features/team/presentation/util/employee_whatsapp.dart` (wa.me pattern).

## Tasks & Acceptance

**Execution:**
- backend DTO fields + tests (owner/employee, admin module excluded).
- app model/snapshot parsing, `MyModulesPage` (`lib/features/settings/presentation/page/my_modules_page.dart`), route, settings entry, CTA links, support number constant/define, tests for each I/O row.
- pubspec 2.0.16+88.

**Acceptance Criteria:**
- Given an owner, when they open « Mes modules », then they see included, paid and available modules with French status and end dates, and a CTA to request activation from support.
- Given a premium screen blocked for a Free tenant, when the owner taps the upgrade action, then « Mes modules » opens.

## Review Triage Log

### 2026-10-31 — Review pass
- verdicts: 30 findings — high 0, medium 3, low 19, false 8, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind/edge) 2.0.15 snapshot without details mislabels free/core modules as paid « Actif » / « Non activé » — when details are missing, show « Informations à confirmer » and confirm with the server before grouping.
  - `[medium]` `[patch]` (blind/edge) token-claim seed without names shows « Impossible de charger vos modules » while the request runs — spinner while unconfirmed and not offline.
  - `[medium]` `[patch]` (blind/edge, confirmed on the Redmi) a paid module switched off (stored DISABLED, or TRIAL/ACTIVE with DISABLED state) shows « Expiré » — « Désactivé » unless the stored status is EXPIRED.
  - `[low]` `[patch]` (blind) `AsyncError` with no value spins forever — error branch with « Réessayer ».
  - `[low]` `[patch]` (blind/edge) failed pull-to-refresh shows no « Hors ligne » note — compare `fetchedAt` before/after.
  - `[low]` `[patch]` (edge) included module in READ_ONLY shown « Inclus » active — « Lecture seule » + reactivate action.
  - `[low]` `[patch]` (edge) unknown state treated as disabled with an activation CTA — neutral row, no CTA.
  - `[low]` `[patch]` (blind/edge) `SUPPORT_WHATSAPP` `00` prefix / empty release variable — strip `00`; release workflow warns when the variable is empty.
  - `[low]` `[patch]` (blind/edge) subscription `status` sent to employees though dates are owner-only — null for non-owners.
  - `[low]` `[patch]` (blind/vg other) redundant catalogue filter in the controller — removed; `free`/`category` looked up without filtering (the service already excludes platform modules).
  - `[low]` `[patch]` (vg) real `appRouter` route `/settings/modules` untested — route table assertion building `MyModulesPage`.
  - `[low]` `[patch]` (blind) support message lacks an account identifier — include the owner's phone.
  - `[low]` `[patch]` (blind) « Fonctionnalité Premium » title above « Voir mes modules » — neutral « Fonctionnalité non incluse ».
  - `[low]` `[patch]` (blind) settings tile hard-codes the title — use `MyModulesPage.title`.
  - `[low]` `[reject]` (blind) old comments / API names mention Premium — cosmetic in code comments; renaming the API touches many call sites.
  - `[low]` `[reject]` (blind) subscription page still offers « Passez au Premium » — plan limits are a separate flow (16.x licences).
  - `[low]` `[reject]` (blind) compact button without icon — matches the existing inline style.
  - `[low]` `[reject]` (blind) `ModuleDetails.toString` without dates / unused `kSubscriptionActive` — cosmetic.
  - `[low]` `[reject]` (blind) no end-to-end core-module test — covered on the device: « Socle » and « Synchronisation hors ligne » listed under « Inclus ».
  - `[low]` `[reject]` (edge) « Hors ligne » shown when signed out — the screen is owner-only behind the session guard.
  - `[false]` `[reject]` (intent) CTA does not create an in-system request — the AC says the CTA tells the owner to contact support (V1 manual activation).
  - `[false]` `[reject]` (intent) no date for « Expiré » — expiry date shown while it matters (trial/active/grace); stored rows keep no end after expiry.
  - `[false]` `[reject]` (intent) status vocabulary richer than TRIAL/ACTIVE/EXPIRED — French labels covering the real states.
  - `[false]` `[reject]` (intent) all upgrade CTAs retargeted — the 15.2 review asked to revisit them with « Mes modules ».
  - `[false]` `[reject]` (intent) 15.2 note only neutralised on refusal surfaces — neutral label + free/paid distinction on « Mes modules » resolves it.
  - `[false]` `[reject]` (intent) employee response widened — patched (status null for non-owners).
  - `[false]` `[reject]` (intent) no device verification — done on the Redmi (screen, refresh, CTA opening WhatsApp, nothing sent).
  - `[false]` `[reject]` (intent) scope additions (offline note, refresh, CI define) — needed by the screen.

## Verification

**Commands:**
- `runpkg.sh shared` then `architecture` -- expected: 0 failures.
- `cd keevo/app && flutter analyze lib test` and the touched test folders (`test/features/settings`, `test/core`, keevo_core tests) one at a time -- expected: pass.

## Auto Run Result

Status: done

**Summary:** Owner-only « Mes modules » screen (`/settings/modules`, tile in « Gestion » after « Abonnement »): groups « Inclus » / « Mes modules payants » / « Disponibles » / « À confirmer »; French statuses (« Inclus », « Essai jusqu'au … », « Actif [jusqu'au …] », « Expiré — lecture seule jusqu'au … », « Expiré », « Désactivé », « Lecture seule », « Non activé », « Informations à confirmer »), Douala dates; CTA « Demander l'activation » / « Contactez le support pour le réactiver » opens WhatsApp to support (`SUPPORT_WHATSAPP` define, owner phone + business + module in the message; release warns when unset); offline note, pull-to-refresh, error + retry. Backend `GET /tenants/me/modules` adds `status` (owner only), `free`, `category`. All upgrade actions now say « Voir mes modules » and open the screen; refusal title « Fonctionnalité non incluse ». App 2.0.16+88.

**Review:** 30 findings — 3 medium patched (missing details mislabelled, claim seed showing an error, « Expiré » for a switched-off module — confirmed on the device), 11 lows patched, rest rejected with reasons.

**Follow-up review recommended:** true — 3 medium patches; open item: the support WhatsApp number is not set yet (owner picks the recipient until `SUPPORT_WHATSAPP` is defined).

**Verification:** backend shared 1054 / admin 517 / architecture 32 — 0 failures; app analyze 0 errors/warnings; keevo_core and app chunked tests all pass. Device (Redmi, dev backend): screen lists included and paid modules; a temporarily DISABLED « Rentabilité » appears with « Demander l'activation » after pull-to-refresh, the CTA opens WhatsApp (nothing sent); row removed (tenant_modules 331).
