---
title: '10-7 — Ops hardening: backups and staging (repository side + runbooks)'
type: 'chore'
created: '2026-10-13'
status: 'done'
baseline_revision: 'c70a7f884ea5845033801a6e009c44e21fa9740f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics-refonte-modulaire.md'
warnings: [multiple-goals]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 10-7 (epic 10, « Durcissement ops — backups & staging », brief in `epics-refonte-modulaire.md` §Story 10.7) holds 13 routed items from the 10-4/10-5 reviews and the v1s-15-9 follow-up: the staging workflow can be dispatched from any branch, cancelled mid-deploy, reuses prod secrets and bind-mounts Firebase unconditionally; the backup unit hardcodes `/home/ubuntu`, has no failure alert and no `.env.backup` check; S3 backups have no least-privilege/immutability guidance; two deployment checklists disagree; restores lose grants; staging TLS through the prod Traefik is an owner decision never applied; legacy `DELETED` tenants may still hold their schema.

**Approach:** Resolve every item that lives in the repository (workflows, systemd units, scripts, Traefik config, docs) and turn each item that can only be executed on the live servers (DNS, IAM, prod SQL, first staging bring-up) into an exact, copy-pasteable runbook step in the single deployment checklist; tick each item in the brief with the commit or the runbook section that settles it. The backend deploy stays manual (no automatic prod change from this story).

## Boundaries & Constraints

**Always:**
- `deploy-staging.yml`: `workflow_dispatch` runs only when `github.ref == 'refs/heads/staging'` (job-level `if`); `concurrency.cancel-in-progress: false`; secrets read from dedicated `STAGING_*` names (`STAGING_JWT_PRIVATE_KEY`, `STAGING_JWT_PUBLIC_KEY`, `STAGING_DB_PASSWORD`, `STAGING_FIREBASE_CREDENTIALS`, … mirroring the prod names used today) and the job fails fast with a clear message when one is missing; Firebase bind-mount only when the credentials file exists; remote script does `[ -f .env ] || { echo "missing .env"; exit 1; }`; staging no longer runs its own Traefik/ACME nor publishes `:8080/:8443`; backend attached to the external network `keevo_edge`.
- Prod Traefik: its dynamic config is not versioned (written on the server by `deploy-backend.yml` / kept by hand). Ship the staging router as a versioned template `keevo/ops/traefik/staging-router.yml` (router `staging`, `Host(\`staging.api.keevo.cm\`)`, entrypoint `websecure`, the prod cert resolver name used today, service `http://keevo_staging_backend:4500`) and a server-side procedure in the checklist (create `keevo_edge` with `docker network create keevo_edge || true`, attach prod Traefik and the staging backend, copy the template next to the prod dynamic config, DNS record, verification `curl -I https://staging.api.keevo.cm`, rollback = remove the file). Do NOT change `deploy-backend.yml`'s prod Traefik generation.
- Backups: `keevo-backup.service` templated (`keevo-backup@.service` with `%i` = deploy user, or install-time `sed` of `__KEEVO_HOME__`), `EnvironmentFile=-…/.env.backup`, `OnFailure=keevo-backup-alert@%n.service` that runs a script posting to a webhook (`BACKUP_ALERT_WEBHOOK_URL`, e.g. WhatsApp/Slack-compatible JSON) and logs to journald; deploy workflow warns when `.env.backup` is missing; `backup-db.sh` exits non-zero on any failure (`set -euo pipefail`) so `OnFailure` fires.
- Docs: one checklist only — `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md`; root `docs/DEPLOYMENT_CHECKLIST.md` replaced by a one-line pointer; checklist gains sections: S3 least-privilege IAM policy JSON (PutObject/GetObject/ListBucket on the backup prefix only, no Delete), bucket versioning + Object Lock (governance, 30 days) commands, restore re-grant step (`GRANT` statements for the app role after `pg_restore --no-owner --no-acl`), staging bring-up (DNS, `keevo_edge`, secrets list), legacy DELETED tenants cleanup (the detection query from the brief, the `UPDATE … SET status='DELETION_PENDING', deletion_scheduled_at=now()` statement, and how to confirm the daily purge ran).
- Brief in `epics-refonte-modulaire.md` §10.7: every checkbox ticked with « ✅ <commit-ish or file> » or « 📋 runbook <section> »; sprint-status comment handled by the orchestrator.
- YAML and shell validated locally: `actionlint` if available (else `python -c 'import yaml…'` parse), `bash -n`, `shellcheck` if available, `systemd-analyze verify` if available.

**Never:** No push, no deployment, no change on any server, no secret values in the repo.

</intent-contract>

## Code Map

- `.github/workflows/deploy-staging.yml` (lines 1-30 trigger/concurrency; ~231 firebase mount) -- staging pipeline.
- `.github/workflows/deploy-backend.yml` -- prod pipeline (Traefik config location, `.env.backup` warning).
- `keevo/backend/scripts/keevo-backup.service`, `keevo/backend/scripts/backup-db.sh` -- backup unit and script.
- `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md`, `docs/DEPLOYMENT_CHECKLIST.md`, `keevo/backend/docs/BACKUP_RESTORE.md` -- docs.
- `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` §Story 10.7 -- item list.

## Tasks & Acceptance

**Execution:**
- `.github/workflows/deploy-staging.yml` -- guards, secrets, network, no local Traefik.
- `keevo/ops/traefik/staging-router.yml` (new template) and `deploy-backend.yml` (only the `.env.backup` warning) -- staging router as a server procedure.
- `keevo/backend/scripts/{keevo-backup@.service,keevo-backup-alert@.service,backup-alert.sh,backup-db.sh}` -- templating, alerting, strict mode; remove the old non-templated unit or keep it as a thin alias documented in the checklist.
- `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md`, `docs/DEPLOYMENT_CHECKLIST.md`, `BACKUP_RESTORE.md` -- consolidation and runbooks.
- `epics-refonte-modulaire.md` -- tick items.

**Acceptance Criteria:**
- Given a `workflow_dispatch` of the staging deploy from a non-staging branch, when it starts, then no job runs.
- Given a failing backup, when systemd runs the unit, then the alert unit is triggered (checked by `systemd-analyze verify` and by reading `OnFailure=`), and the checklist explains how to test it on the server.
- Given the brief, when read after this story, then no item is left unticked.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 46 findings — high 1, medium 12, low 22, false 4, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[high]` `[patch]` (edge, claim) several backup failure modes raise no alert (Docker dependency failure, hang without timeout, stale lock exit 0) — `Wants=` + Docker check, `TimeoutStartSec=2h`, stale lock > 6 h → exit 1, optional success heartbeat, `BACKUP_REQUIRE_S3`.
  - `[medium]` `[patch]` (gap) failure → alert chain without automated verification — `backup-alert-test.sh` in CI (stub curl, JSON validity, unit rendering checks) + shellcheck/actionlint CI steps.
  - `[medium]` `[patch]` (gap other / blind / edge) each prod deploy wipes staging routing silently — `::warning::` + idempotent `keevo_edge` re-attach in `deploy-backend.yml`; full router persistence routed to 10-8 (server-side provider layout, owner decision).
  - `[low]` `[patch]` (gap other) prod-key guard depends on repository-level secrets — documented; fingerprint comparison.
  - `[low]` `[patch]` (gap other) local-only backup succeeds — `BACKUP_REQUIRE_S3=1` in prod.
  - `[medium]` `[patch]` (blind/edge) group check after chgrp — moved above.
  - `[medium]` `[patch]` (blind/edge) secrets briefly world-readable — `umask 077`.
  - `[medium]` `[patch]` (blind) staging stack not operable by hand (password only in the CI shell) — `.env.secrets` (600) + `compose.sh` wrapper.
  - `[medium]` `[patch]` (blind/edge) raw-string prod-key guard, no pub/priv match, DB password reuse unchecked — fingerprints, pair check, DB password guard.
  - `[medium]` `[patch]` (blind) staging deploy never verified — 120 s health wait, keevo_edge attachment report.
  - `[low]` `[patch]` (blind/edge) webhook warning by file existence, unreadable file — value check with sudo.
  - `[medium]` `[patch]` (blind) units installed before validation, sed escaping — temp render, verify, move; escaped DEPLOY_DIR.
  - `[low]` `[patch]` (blind/edge) token/payload in argv — stdin/config.
  - `[medium]` `[patch]` (blind/edge) incomplete JSON escaping — control characters / jq.
  - `[medium]` `[patch]` (blind) no alert when backups stop running — heartbeat (optional) documented.
  - `[low]` `[patch]` (blind) cron alternative paths/quoting — fixed in BACKUP_RESTORE §3.4.
  - `[low]` `[patch]` (blind) concurrency wording — corrected.
  - `[medium]` `[patch]` (blind) runbook-only items ticked as done — re-labelled « 📋 runbook §N — exécution serveur : 10-8 »; story 10-8 created (key + brief) for the server execution.
  - `[low]` `[patch]` (blind) unpinned alpine — tag + digest.
  - `[low]` `[patch]` (edge) `.env` without POSTGRES_DB/USER — exit 1.
  - `[low]` `[patch]` (edge) swallowed network create errors — inspect-or-create.
  - `[low]` `[patch]` (edge) §16 misses memberships of already-dropped schemas — query + cleanup added.
  - `[false]` `[reject]` (edge, claim) deploy-backend changes beyond the warning — required by the templated unit path (spec Boundaries « templated »); reviewed here.
  - `[false]` `[reject]` (edge, claim) no `@` instance unit — the spec allows install-time `sed` (« or install-time sed of __KEEVO_HOME__ »); docs checked for stale `@<user>` references.
  - `[false]` `[reject]` (intent) secret names `_PEM` vs spec example — spec says « mirroring the prod names used today ».
  - `[false]` `[reject]` (intent) Firebase optional / mount decided by the CI secret — the conditional mount is the spec's rule; deleting a stale prod file prevents identity reuse (the item's purpose).
  - `[low]` `[reject]` (intent) network created by the staging workflow — idempotent and staging-only; the runbook still documents it for prod Traefik.

## Verification

**Commands:**
- `bash -n keevo/backend/scripts/*.sh` -- expected: no output
- `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/deploy-staging.yml .github/workflows/deploy-backend.yml` -- expected: no error
- `command -v actionlint && actionlint .github/workflows/deploy-staging.yml .github/workflows/deploy-backend.yml` -- expected: no error when available
- `command -v shellcheck && shellcheck keevo/backend/scripts/*.sh` -- expected: no error when available

## Auto Run Result

Status: done

**Summary:** Story 10-7 done on the repository side. Staging deploy: branch guard, never cancelled mid-run, dedicated `STAGING_*` secrets checked before any SSH (fingerprint guard against prod keys, key-pair match, DB password reuse), `umask 077` secrets, group check before chgrp, `.env` sanity, Firebase mounted only with a staging secret, no own Traefik, `keevo_edge` network, `.env.secrets` + `compose.sh` for manual operation, 120 s health gate. Prod deploy: backup units rendered by `render-backup-units.sh`, validated then installed; webhook-value warning; Traefik re-attached to `keevo_edge`; warning that the staging router must be re-applied. Backups: `TimeoutStartSec`, Docker check, stale-lock alert, `BACKUP_REQUIRE_S3`, optional heartbeat, `OnFailure=` alert unit posting JSON via stdin-only curl; CI test + shellcheck + actionlint. One deployment checklist (§7–§17) with IAM/versioning/Object Lock, re-grant, staging bring-up, Traefik TLS procedure, legacy DELETED tenants cleanup. Server execution routed to new story 10-8 (key + brief).

**Files changed:** `.github/workflows/{deploy-staging,deploy-backend,backend-ci}.yml`, `keevo/backend/scripts/{keevo-backup.service,keevo-backup-alert@.service,backup-alert.sh,backup-db.sh,render-backup-units.sh,tests/backup-alert-test.sh}`, `keevo/ops/traefik/staging-router.yml`, `keevo/backend/docs/{DEPLOYMENT_CHECKLIST,BACKUP_RESTORE,STAGING}.md`, `docs/DEPLOYMENT_CHECKLIST.md` (pointer), `HealthSampleService` javadoc, `epics-refonte-modulaire.md` (§10.7 ticks, §10.8 brief).

**Review:** 46 findings — patched 1 high (silent backup failure modes), 12 medium and 22 low entries (grouped); deferred 0 (server execution routed to 10-8); rejected 5.

**Follow-up review recommended:** true — a high was patched; unverified risk: workflows and units are only validated locally (actionlint, shellcheck, systemd-analyze, CI shell test), never run on the real servers.

**Verification:** `bash -n` on all scripts, YAML parse of all workflows and the template, `backup-alert-test.sh` (all checks passed), actionlint 1.7.7 and shellcheck 0.10.0 clean.

**Residual risks:** every prod deploy rewrites the prod Traefik config (staging router to re-apply until 10-8 decides persistence); the DB-password reuse guard needs a repository-level `DB_PASSWORD` secret.

### Routed items
- TLS staging server procedure, staging secrets creation, S3 IAM/versioning/Object Lock, alert/heartbeat setup, legacy DELETED tenants cleanup, DASHBOARD_ORIGIN in prod → 10-8-ops-runbook-execution-serveur

