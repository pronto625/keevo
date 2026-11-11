---
title: '24-13 Cutover runbook (J-7 → J+7) and communication, per-owner rollback before the « go », cutover WhatsApp templates'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: 'c8431039067e94967420619b390caf62c7ddab46'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-9-pilotage-vagues-rollback.md'
  - '{project-root}/docs/RUNBOOK_BASCULE_BREF.md'
warnings: ['oversized']
deferred:
  - summary: >-
      An owner accepted out of the cutover has no import and activation path once the cutover is ACTIVATED.
    evidence: |-
      resume() and rollbackOwner() are refused after the « go »; routed to story 24-9 (sprint-status, spec-24-9 « Deferred items routed »).
    location: >-
      keevo/backend/src/main/java/com/keevo/migration/application/BrefCutoverService.java
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The one-shot cutover (ADR-10) has a tool (24.2–24.10) but no complete day plan: `docs/RUNBOOK_BASCULE_BREF.md` only has §1 (public links), §2 (webhooks) and §3 (cutover command). Owners need announcements (J-7, J-1, « c'est fait », incident) in fr and en, and two routed items have no executable procedure: a `RECONCILIATION_FAILED` owner (24.8 — « rollback that owner, re-import from the frozen dump ») and the Apple / identity-block cases (24.7). The 24.9 tool can only roll back the whole cutover, so « rollback that owner » is not executable today.

**Approach:** (1) Complete the runbook into a timed J-7 / J-1 / J0 / J+1 / J+7 plan with exact commands (docker, psql, curl with placeholders), written go/no-go criteria, rollback, contacts placeholders and a user-actions list. (2) Add one super-admin endpoint `POST /api/v1/admin/migration/cutovers/{cutoverId}/owners/{ownerId}/rollback` (before the « go » only) that removes one owner's imported company exactly like the global rollback and puts its line back to « En attente », so `POST …/runs` re-imports it from the frozen copy. (3) Add the cutover WhatsApp templates (fr + en) to `PlatformTemplateCatalog` as a separate campaign list, regenerate `docs/whatsapp/modeles-meta-plateforme.md`, and give the ready-to-paste texts (SMS / e-mail / notification) and campaign bodies in the runbook. Nothing is sent automatically.

## Boundaries & Constraints

**Always:** no secret, token, key, real host credentials or real date anywhere (placeholders `<…>`); timings come from 24.9 measures (≈ 6.8 s/owner, parallelism 4) and are marked « à confirmer par 24.12 »; the owner rollback reuses `removeOwner` (same compensation, same refusals: an active company is never removed) and is serialized with the coordinator through the cutover status transition (READY/FAILED → RUNNING → FAILED with a French label); French labels, no technical data in user-facing text; additive API only (V1 2.0.1); campaign templates are UTILITY, Meta rules (no variable at start/end, examples, ≤1024), never mapped to a `PlatformMessageKind` nor accepted in `WHATSAPP_APPROVED_TEMPLATES`; the export file and the restored copy hold Meta tokens in clear → protect, delete after import / at J+7.

**Never:** send or schedule any message from code or seed data; touch api-bref or prod (ssh, deploy); a Flyway migration (V92 unused); per-owner operations after the « go » (routed to 24-9); a language segment in campaigns (en goes to an explicit list).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Owner rollback ok | cutover READY or FAILED, owner GAP/BLOCKED/FAILED/RECONCILED | company + created accounts + traced identities/Web Push + records/batches removed; line PENDING (batch, tenant, figures, times cleared); cutover FAILED « Compte retiré et remis en attente : relancez la bascule » ; 200 with the cutover | — |
| Wrong phrase | confirmation ≠ « RETOUR ARRIERE » | 400 | VALIDATION_FAILED « Tapez « RETOUR ARRIERE » … » |
| Owner not in cutover | unknown owner id | 400 | VALIDATION_FAILED « Ce compte ne fait pas partie de cette bascule » |
| Nothing to remove | line PENDING / NOT_MIGRATED / ACTIVATED / ROLLED_BACK | 409 | INVALID_STATE French label |
| Cutover busy / decided | RUNNING, ROLLING_BACK, ACTIVATED, ROLLED_BACK | 409, nothing removed | INVALID_STATE `refusal(status)` (after go: « … corrigez sur Keevo (runbook) ») |
| Global rollback started | a line ROLLED_BACK | 409 | INVALID_STATE ROLLBACK_STARTED |
| Removal refused | company active or not pending deletion | line FAILED with the reason, cutover FAILED | 409 with that French reason |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/migration/application/BrefCutoverService.java` -- `rollback`/`rollbackRun`/`removeOwner` (L266, L466, L527): global compensation to reuse; `resume` (L160) refuses once a line is ROLLED_BACK, processes PENDING/GAP/BLOCKED/FAILED; `refusal()`; constants block L81–103.
- `keevo/backend/src/main/java/com/keevo/migration/domain/port/in/BrefCutoverUseCase.java` -- add `rollbackOwner(cutoverId, ownerId, confirmation, actorId)`.
- `keevo/backend/src/main/java/com/keevo/migration/domain/port/out/CutoverStore.java` + `adapter/out/persistence/JdbcCutoverStore.java` -- add `owner(cutoverId, ownerId)` (PK lookup) and `resetOwner(cutoverId, ownerId)` (status PENDING, clears batch/tenant/reason/durations/figures/times); `transition` SQL L64; `finish` only from RUNNING/ROLLING_BACK.
- `keevo/backend/src/main/java/com/keevo/migration/adapter/in/rest/AdminBrefCutoverController.java` -- new route `POST /{cutoverId}/owners/{ownerId}/rollback` (body `RollbackRequest`).
- `keevo/backend/src/test/java/com/keevo/migration/application/BrefCutoverServiceTest.java`, `BrefCutoverFullContextTest.java` -- unit + full-context coverage.
- `keevo/backend/src/main/java/com/keevo/messaging/whatsapp/domain/model/PlatformTemplateCatalog.java` -- add `CUTOVER_CAMPAIGN` list (4 templates × fr/en) + `campaignTemplates()`; `all()`/`BY_NAME`/`kindTemplateByName` unchanged.
- `keevo/backend/src/test/java/com/keevo/messaging/whatsapp/domain/model/PlatformTemplateCatalogTest.java`, `PlatformTemplateCatalogDocTest.java` -- Meta rules for the campaign list; new doc section 5; regenerate `keevo/backend/docs/whatsapp/modeles-meta-plateforme.md`.
- `keevo/backend/src/main/java/com/keevo/admin/campaign/**` -- read-only: campaigns reach live OWNER memberships only (SUSPENDED excluded), `segment.tenantIds` ≤ 100, SMS ≤ 480 chars, title ≤ 100, WhatsApp = any template approved on the platform number (name + language + bodyText + STATIC variables).
- `/home/toor/Project/FreeLance/AI/api-bref` (read-only): `docker-compose.prod.yml` (containers `api-bref`, `bref_postgres`, postgres 16), `src/campaign` (`POST /api/v1/campaigns`, one `type` per campaign, `targetingCriteria.roles`, `metadata.whatsapp`, `POST /:id/send`).
- `docs/RUNBOOK_BASCULE_BREF.md` -- complete; `keevo/backend/docs/entitlements/endpoints.md` -- regenerate.

## Tasks & Acceptance

**Execution:**
- `CutoverStore` / `JdbcCutoverStore` -- add `owner` and `resetOwner` -- single-row PK statements.
- `BrefCutoverService` / `BrefCutoverUseCase` -- add `rollbackOwner`; parametrize `removeOwner` with the line status to set (ROLLED_BACK for global, PENDING via `resetOwner` for one owner) -- reuse, no duplicated compensation.
- `AdminBrefCutoverController` -- route + OpenAPI summary.
- `BrefCutoverServiceTest` -- matrix rows; `BrefCutoverFullContextTest` -- owner rollback then resume re-imports and reconciles.
- `PlatformTemplateCatalog` + tests + regenerate doc -- cutover templates.
- `docs/RUNBOOK_BASCULE_BREF.md` -- §0 overview/timeline, §4 J-7, §5 J-1, §6 J0 (stop, dump, transfer, restore, indexes, read-only role, config, cutover, reconciliation, identity cases, Apple, go/no-go, go, webhooks/proxy order, RN publish, « c'est fait »), §7 owner in gap, §8 rollback, §9 J+1, §10 J+7 (clean-up, key revocation), §11 messages fr/en, §12 contacts, §13 user actions.
- `endpoints.md` regenerate; spec Mobile + Dashboard sections; sprint-status (24-13 done, d24-13), route after-go item to 24-9.

**Acceptance Criteria:**
- Given the runbook, when read on the dress rehearsal (24.12), then every J0 step has a command or screen action, an expected result and an estimated duration; the final downtime figure is explicitly pending 24.12 (needs a prod dump from the user).
- Given the integrations, then the runbook lists the user actions (webhooks, proxy, Meta templates, Apple Team ID/bundle, prod dump for 24.12, key revocation) and their revert.
- Given the four messages, then each exists in fr and en for WhatsApp (catalog templates), SMS, e-mail and notification, with the campaign tool that sends it, and nothing is sent without a manual « Envoyer ».
- Given a `RECONCILIATION_FAILED` owner before the « go », when the runbook procedure is followed, then the owner is rolled back alone and re-imported from the frozen copy.

## Design Notes

- **Pattern:** the owner rollback is a *Command* on the existing cutover aggregate, reusing the compensation method (`removeOwner`, Template-Method style with the end status as parameter) and the existing optimistic state lock (conditional UPDATE transition) instead of a new lock table. Cost: 1 PK read, 1 transition, 1 `targets` query, the per-company compensation of 24.9 (constant statements per owner), 1 reset, 1 aggregate (set-based, as 24.9) — O(1) queries in the number of owners.
- **Why FAILED after the reset:** a PENDING line means « not ready for the decision »; `run()` already maps a PENDING owner to FAILED + « relancez-la », so the operator's next action (`POST …/runs`) is the same. The ROLLBACK_STARTED guard stays meaningful because the reset never writes ROLLED_BACK.
- **Audience reality:** before the « go » Bref owners exist only in Bref → J-7, J-1 and a pre-go incident go through **Bref's own campaigns** (admin Bref, same Meta number, so the same approved templates); after the « go », « c'est fait » and a post-go incident go through Keevo platform campaigns (21.6) targeted with `segment.tenantIds` (≤ 100 per campaign) of the activated companies.
- Catalog: campaign templates share the WABA of the platform number (Bref's number reused by Keevo, runbook §2.2); names `bref_bascule_annonce|rappel|terminee|incident`, one name per message with two languages (Meta allows a name in several languages).

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t2413 migration messaging/whatsapp architecture admin/campaign` -- all green.
- `mvn test -Dtest=PlatformTemplateCatalogDocTest -Dkeevo.whatsapp.templates.regenerate=true` and `-Dtest=EndpointEntitlementCoverageTest -Dkeevo.entitlements.regenerate=true` -- docs regenerated, then green without the flag.

**Manual checks:**
- Live on `keevo_dev_t2413` :4651 with a restored Bref copy: cutover → owner rollback → resume → owner reconciled again; campaign bodies of the runbook accepted as DRAFT by `POST /api/v1/admin/campaigns` (never sent).

## Spec Change Log

(no bad_spec loopback)

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 35 findings — high 0, medium 12, low 18, false 5, maybe-false 0
- findings:
  - `[low]` `[reject]` (design) `rollbackOwner` recomputes the whole cutover report (O(N owners)) — rare, manual, synchronous super-admin action; every query is filtered by the indexed `cutover_id`; keeps the report exact after the reset.
  - `[low]` `[reject]` (design) repeated `cutover(cutoverId)` reads — PK lookup + one GROUP BY on an index, a handful per manual call; negligible.
  - `[low]` `[reject]` (design) boolean flag on `removeOwner` — two callers, documented in the Javadoc; an enum/consumer adds surface for no named harm.
  - `[false]` `[reject]` (intent) new backend endpoint beyond a docs story — the routed 24.8 item asks to « retour arrière de cet owner (24.9) et nouvel import »; 24.9 has no per-owner rollback, so the procedure was not executable as written (intent: « every step must be executable »). Decision D1.
  - `[false]` `[reject]` (intent) J-7 / J-1 / pre-go incident sent by the Bref admin campaigns instead of 21.6 — before the « go » Bref owners have no live Keevo company (audience = active OWNER memberships, SUSPENDED excluded, `JdbcPlatformAudienceAdapter`); 21.6 cannot reach them. Decision D2.
  - `[false]` `[reject]` (intent) no preset inside the campaign module — the intent allows « documented content … the most conservative way »; WhatsApp bodies are in the catalog, texts and ready JSON bodies in the runbook. Decision D4.
  - `[low]` `[reject]` (intent) runbook commands not covered by tests — they were replayed live on `keevo_dev_t2413` / `bref_src_t2413` (§0.4 login, §6.6 role SQL, §6.7 env names, §6.8, §6.10, §7, §6.14, §11.6), which found and fixed the two-step login; figures in runbook §3.4. A shell test harness would add surface for a one-shot document.
  - `[false]` `[reject]` (intent) published timing estimates — labelled estimates, the AC is explicitly pending 24.12 (header, §0.2, §14).
  - `[medium]` `[patch]` (verification) exception path of `rollbackOwner` untested — the release moved to `releaseAfterOwnerRollback` (line marked failed in its own guard, finish guarded); new test `ownerRollbackReleasesTheLockWhenAStoreCallThrows`.
  - `[low]` `[patch]` (verification) reset columns not asserted — full-context test now asserts `figures = {}` and batch/tenant/reason/durations/times NULL, plus the linked account keeps its 2 companies.
  - `[medium]` `[patch]` (blind) refusal found under the lock forced a READY cutover to FAILED — now restores the previous status and label (`finish(before.status(), before.errorLabel())`); test `ownerRollbackRefusedUnderTheLockRestoresThePreviousState`.
  - `[low]` `[patch]` (blind) owner not in cutover returned 422 before the lock, 409 after — same code both sides now (refusal kept as its `DomainException`); same test.
  - `[medium]` `[patch]` (blind) ROLLED_BACK check not repeated after the lock — re-read under the lock; test `ownerRollbackRefusedWhenAGlobalRollbackStartedMeanwhile`.
  - `[low]` `[patch]` (blind) untested paths (FAILED cutover, post-lock change, exception, finally failure) — covered by the three new tests; finally failure guarded and logged.
  - `[medium]` `[patch]` (blind) §11.6 e-mail sent twice — first campaign without `EMAIL`, second EMAIL-only campaign created by the same script.
  - `[medium]` `[patch]` (blind) members never get « c'est fait » (21.6 = owners only) — runbook says so, adds the « prévenez votre équipe » sentence and the support relay.
  - `[medium]` `[patch]` (blind) commands mix poste and server shells; `read -p` fails in zsh — every block tagged (poste)/(serveur), token never on the server, tenant list moved by `scp`, « lancer bash » note.
  - `[low]` `[patch]` (blind) §6.8 loop never ends, `null` id — `suivre()` stops on stable states, relogs on 401; launch refusal printed, empty id checked.
  - `[medium]` `[patch]` (blind) incident template mostly variable — more fixed text around `{{1}}` (fr/en), test asserts ≥ 60 fixed characters per variable; doc regenerated.
  - `[low]` `[patch]` (blind) no dashboard follow-up — `d24-13` key and « Dashboard super-admin » section.
  - `[low]` `[patch]` (blind) J-1 checks: `APPLE_TEAM_ID`, `env_file`/`postgres` host, PostgreSQL versions — added to §5.2 (compose generated by `deploy-backend.yml` verified: `env_file: .env`, `POSTGRES_HOST: postgres`).
  - `[medium]` `[patch]` (blind) owner accepted out of the « go » has no path back — §6.13 point 3 states the consequence, §8.3 and §10 keep the frozen copy until it is taken back; tool routed to 24-9 (see Deferred items).
  - `[medium]` `[patch]` (edge) failure inside `finally` leaves the cutover RUNNING and masks the error — release guarded and logged (relaunch after STALE).
  - `[medium]` `[patch]` (edge) global rollback between check and lock — same fix as the blind finding (re-check under the lock).
  - `[low]` `[patch]` (edge) exception path left the line without reason — line marked FAILED with `ROLLBACK_OWNER_FAILED` in its own guard.
  - `[false]` `[reject]` (edge) no heartbeat during a long synchronous removal — one owner's removal measured 0.3 s live (34 s for 202 owners in 24.9); the global rollback beats at the same per-owner granularity; STALE is 30 min.
  - `[medium]` `[patch]` (edge) incident template ratio — same fix as the blind finding.
  - `[low]` `[patch]` (edge) `null` token / cutover id — login returns 1 on failure; empty cutover id checked.
  - `[low]` `[patch]` (edge) polling loop / token expiry — same fix as the blind finding (`suivre()`).
  - `[low]` `[patch]` (edge) owners listing without position for `after=` — `position` column added.
  - `[low]` `[patch]` (edge) re-attempt within 48 h: database / role exist — `DROP DATABASE IF EXISTS`, `DROP ROLE IF EXISTS` before re-creating.
  - `[low]` `[patch]` (edge) duplicate `.env` keys on a re-attempt — `sed -i` removes previous lines before appending.
  - `[medium]` `[patch]` (edge) §11.6 block run on the wrong host — same fix as the blind finding (host tags, `scp`).
  - `[low]` `[patch]` (edge) stale `lot_*` / empty glob — fresh working folder, `rm -f`, empty-list check, `shopt -s nullglob`.
  - `[low]` `[patch]` (edge) §6.11/6.12/6.16/6.17 without duration or expected result — added.

### Deferred items routed 2026-11-11
- [ ] [Deferred-from: revue 24.13, 2026-11-11] Reprise d'un owner après le « go » [keevo/backend/src/main/java/com/keevo/migration/application/BrefCutoverService.java `resume`/`rollbackOwner`] → **24-9** — routed: an owner accepted out of the cutover has no import + activation path once the cutover is ACTIVATED (`resume` and `rollbackOwner` refused after the « go »).

## Décisions (prises par défaut, autonomie complète)

- **D1** Retour arrière d'UN owner : nouvel endpoint super-admin `POST /api/v1/admin/migration/cutovers/{cutoverId}/owners/{ownerId}/rollback` (`{"confirmation":"RETOUR ARRIERE"}`), avant le « go » seulement, même compensation que le retour arrière global, ligne remise « En attente », puis la relance existante réimporte depuis la copie figée. Sans lui, la procédure routée par 24.8 n'était pas exécutable. Additif, aucune migration.
- **D2** Canal des messages : avant le « go » (J-7, J-1, incident « reportée »), les owners n'existent que dans Bref → campagnes de l'admin Bref (même numéro WhatsApp, mêmes modèles) ; après le « go » (« c'est fait », incident « en cours ») → campagnes plateforme Keevo 21.6 ciblées par `segment.tenantIds` (lots de 100).
- **D3** Langue : pas de critère de langue dans les campagnes → la version française part à tous, l'anglaise en plus à une liste explicite fournie par le support.
- **D4** Forme des messages : modèles WhatsApp dans `PlatformTemplateCatalog` (liste séparée `campaignTemplates()`, jamais liée à un type de message ni à `WHATSAPP_APPROVED_TEMPLATES`) + textes SMS / notification / e-mail et corps JSON prêts dans le runbook. Aucun envoi automatique, aucune donnée amorcée.
- **D5** Modèles en catégorie Utilitaire, nom unique par message en fr et en, pied de page « Bref Point ».
- **D6** Critères « go » écrits : au plus 1 % d'owners hors bascule, aucun parmi les 20 plus gros, écarts acceptés seulement s'ils sont documentés par 24.12, heure limite de décision = retour annoncé − (retour arrière + 30 min).
- **D7** Durées : estimations tirées de 24.9 ; validation pour la production en attente de 24.12 (dump de prod à fournir).
- **D8** Données sensibles : dump, copie et fichier d'export contiennent les jetons Meta en clair → `chmod 600`, jamais sur un poste, suppression (`shred`) à J+7, une archive chiffrée au plus.

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

Sans objet pour l'app : aucun endpoint ni réponse vus par l'app ne changent. Pas de clé `m24-13`.

### Ce qui est fait côté API

Rien de nouveau pour l'app. Le runbook (§4.3, §6.16) fixe le calendrier de publication de l'app React Native (23.12) : soumise à J-7 en publication manuelle, publiée après le « go », message « c'est fait » envoyé une fois la version visible.

### À utiliser / À implémenter côté mobile

Rien. Rappel du runbook : un owner Apple retrouve son compte seulement si l'app est publiée sous la même équipe Apple et le même bundle que Bref Point (§4.2) ; sinon connexion par téléphone + code puis reliaison Apple depuis le profil (19.3b, existant).

## Dashboard super-admin (autre dépôt)

### Statut

API livrée (commit 4d4414fc). Clé de suivi : `d24-13-runbook-communication`.

### Ce qui est fait côté API

Retour arrière d'un seul owner d'une bascule, avant le « go », synchrone (≈ 0,3 s mesuré) ; la ligne revient « En attente » et la bascule passe « Interrompue : relancez-la » avec le libellé « Compte retiré et remis en attente : relancez la bascule pour le réimporter ».

### À utiliser

- `POST /api/v1/admin/migration/cutovers/{cutoverId}/owners/{ownerId}/rollback`, corps `{"confirmation":"RETOUR ARRIERE"}` → 200 `data` = la bascule (même forme que `GET …/cutovers/{id}`). Code : `AdminBrefCutoverController#rollbackOwner`, `BrefCutoverService#rollbackOwner`.
- Erreurs (afficher `error`, jamais l'identifiant) :
  - 422 `VALIDATION_FAILED` « Tapez « RETOUR ARRIERE » pour confirmer le retour arrière » ; « Ce compte ne fait pas partie de cette bascule ».
  - 409 `INVALID_STATE` « Ce compte n'a rien à retirer : il est en attente, non repris, activé ou déjà retiré » ; « La bascule est en cours : attendez la fin » ; « Après le « go », plus de retour arrière automatique : corrigez sur Keevo (runbook) » ; « Un retour arrière a commencé : terminez-le (relancez-le), puis ouvrez une nouvelle bascule » ; « Entreprise déjà active ou suspendue pour un autre motif : non supprimée » ; « Retour arrière impossible pour ce compte : relancez-le ».
  - 404 `MIGRATION_CUTOVER_NOT_FOUND`.

### À implémenter côté dashboard

1. Écran « Bascule », liste des owners : sur une ligne « Écart de réconciliation », « Import bloqué », « Import en échec » ou « Importé et réconcilié », action « Retirer ce compte » visible seulement quand la bascule est « Prête » ou « Interrompue ».
2. Confirmation par saisie de « RETOUR ARRIERE », texte d'aide : « Son entreprise importée est supprimée ; relancez ensuite la bascule pour le réimporter depuis la copie. »
3. Après succès : rafraîchir la bascule, proposer « Relancer » (`POST …/runs`).
4. Aucune donnée technique à l'écran (« Compte n », nom d'entreprise).

### Critères d'acceptation dashboard

- Given une bascule « Prête » avec un owner en écart, when « Retirer ce compte » est confirmé, then la ligne passe « En attente » et la bascule « Interrompue : relancez-la » ; when « Relancer », then l'owner redevient « Importé et réconcilié ».
- Given une bascule « Activée », then l'action n'est pas proposée (et l'API répond 409).

### Clé de suivi

`d24-13-runbook-communication`.

## Auto Run Result

Status: done.

**Summary.** The cutover runbook is complete (`docs/RUNBOOK_BASCULE_BREF.md` §0–§14: calendar J-10 → J+7, timed estimates, J0 commands with expected results, go/no-go criteria, owner-in-gap procedure, rollback before and after the « go », J+1 / J+7 checks and clean-up, fr/en messages with ready campaign bodies, contacts, user actions, timing sheet pending 24.12). One additive super-admin endpoint rolls back a single owner before the « go ». Eight WhatsApp templates (4 messages × fr/en) join `PlatformTemplateCatalog` as a separate campaign list, with the Meta document regenerated.

**Files.**
- `docs/RUNBOOK_BASCULE_BREF.md` — completed runbook.
- `migration/application/BrefCutoverService.java` — `rollbackOwner`, `releaseAfterOwnerRollback`, `removeOwner(…, global)`, `ROLLBACK_TABLES`.
- `migration/domain/port/in/BrefCutoverUseCase.java` — `rollbackOwner`.
- `migration/domain/port/out/CutoverStore.java`, `adapter/out/persistence/JdbcCutoverStore.java` — `owner`, `resetOwner` (PK statements).
- `migration/adapter/in/rest/AdminBrefCutoverController.java` — new route.
- `messaging/whatsapp/domain/model/PlatformTemplateCatalog.java` — `campaignTemplates()` (8 templates).
- Tests: `BrefCutoverServiceTest` (+8), `BrefCutoverFullContextTest` (owner rollback → relaunch → reconciled, cleared columns, refusals), `PlatformTemplateCatalogTest` (+1), `PlatformTemplateCatalogDocTest` (§5).
- Generated: `keevo/backend/docs/whatsapp/modeles-meta-plateforme.md`, `keevo/backend/docs/entitlements/endpoints.md` (420 handlers).

**Review.** 35 findings: 12 medium + 13 low patched (incl. duplicates), 5 false and 5 low rejected with reasons (triage log), 1 item routed to 24-9. No Flyway migration (V92 unused).

**Verification.** Tests green: migration 175, messaging/whatsapp 92, architecture 173 (1 skipped, pre-existing), admin/campaign 7. Live (`bref_src_t2413` synthetic copy 21 owners + read-only role from runbook §6.6, `keevo_dev_t2413`, port 4651, WhatsApp noop, FCM off): runbook replayed — cutover 48.8 s, provoked gap → « go » refused, owner rollback 0.3 s, relaunch 4.1 s → reconciled, « go » 0.1 s, rollback after « go » 409, « c'est fait » drafts in lots then deleted, nothing sent; databases and role dropped.

**Follow-up review recommended:** `true` — 12 medium entries patched, several on the lock release of `rollbackOwner` (refusal under the lock, global rollback race, failure in the release) covered by unit tests and one full-context pass only; the runbook itself is unvalidated against a real Bref copy until 24.12.

**Residual risks.** Final downtime and parallelism unknown until 24.12 (prod dump needed); Meta may still reclassify or refuse a template; owners accepted out of the « go » have no tool path yet (routed 24-9).
