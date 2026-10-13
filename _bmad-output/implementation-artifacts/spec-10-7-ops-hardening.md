---
title: '10-7 — Ops hardening: backups and staging (repository side + runbooks)'
type: 'chore'
created: '2026-10-13'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
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

## Verification

**Commands:**
- `bash -n keevo/backend/scripts/*.sh` -- expected: no output
- `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]" .github/workflows/deploy-staging.yml .github/workflows/deploy-backend.yml` -- expected: no error
- `command -v actionlint && actionlint .github/workflows/deploy-staging.yml .github/workflows/deploy-backend.yml` -- expected: no error when available
- `command -v shellcheck && shellcheck keevo/backend/scripts/*.sh` -- expected: no error when available
