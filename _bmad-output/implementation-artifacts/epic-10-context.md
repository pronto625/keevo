# Epic 10 Context: Préparation & Garde-fous (Phase 0)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Make the existing Keevo backend "production-safe" so the modular rewrite can proceed without risk: versioned schema migrations, externalised secrets with fail-fast, restorable PostgreSQL backups, an iso-prod staging environment, and a non-blocking architecture safety net. The epic adds no product features; it is the precondition for every later refactoring epic. Stories 10.1–10.6 are done; the only open work is 10.7, which hardens the backup and staging setup delivered by 10.4/10.5.

## Stories

- Story 10.1: Flyway enabled, baseline of the `public` schema
- Story 10.2: `public` schema via versioned migrations (end of `ddl-auto=update`)
- Story 10.3: Externalised secrets and fail-fast in production
- Story 10.4: Automated PostgreSQL backups and restore test
- Story 10.5: Iso-prod staging environment
- Story 10.6: Architecture safety nets (Spring Modulith `verify` and ArchUnit, warning baseline)
- Story 10.7: Ops hardening (backups and staging)

## Requirements & Constraints

- Schema changes reach prod only through Flyway migrations. The prod profile runs Hibernate `validate`. Tenant schemas (`kv_*`) stay outside Flyway-public until the module provisioner arrives (Epic 13).
- No default secrets in prod. A missing admin password, JWT key or Firebase credential stops the boot. Secrets are injected at runtime and never stored in the image or the repo, and storage credentials are never committed.
- Backups are a daily full `pg_dump -Fc` of all schemas (`public` and every `kv_*`), stored off-server, with 30-day retention. A restore must be proven on staging: the restored DB passes `/actuator/health` UP and a login works.
- Staging gets its own database and the `staging` profile (a clone of `prod`), and it must never touch prod.
- 10.7 acceptance:
  - `https://staging.api.keevo.cm` serves a valid certificate through the **prod Traefik**, and the staging backend is not exposed publicly.
  - A backup failure, or a missing `.env.backup`, raises an alert (`OnFailure=`) and makes the deploy warn.
  - The backup unit path is templated and S3 backups are protected (least-privilege IAM, versioning or Object-Lock).
  - Staging uses dedicated JWT, Firebase and DB secrets. `workflow_dispatch` is guarded by branch, the Firebase mount is conditional, and mutating deploy steps cannot be cancelled midway.
  - Every routed item in 10.7 is either resolved or explicitly rejected with a reason (checkbox plus commit or decision).
- 10.7 also carries a medium-severity check that has not been verified on prod. Legacy `DELETED` tenants may still hold their schema, memberships and tokens. Detect them with `SELECT id, schema_name FROM public.tenants WHERE status='DELETED' AND to_regnamespace(schema_name) IS NOT NULL`. If any rows exist, set them to `DELETION_PENDING` with `deletion_scheduled_at = now()` so the daily `AccountDeletionScheduler` purges them.

## Technical Decisions

- **Hard ops rules:**
  - The backend deploy is **manual**: pushing does not deploy (Actions hit `startup_failure`), and prod repairs go through the ops agent.
  - **Nothing may be applied to servers from repo work.** Changes that touch prod (Traefik routing, DNS, IAM, systemd on the host) are written as reviewed artefacts plus a documented deploy-time procedure, and are validated at the first real staging bring-up, never blindly.
- **Prod pipeline:** `.github/workflows/deploy-backend.yml` runs on push to `deploy`.
  - It builds the GHCR image and generates `~/keevo/docker-compose.prod.yml` over SSH (`keevo_postgres`, `keevo_backend` on `keevo_net`). The non-root container uses UID/GID 2001, and JWT keys are `chgrp 2001` and `640`, with pre-flight assertions.
  - It then scp's the backup files and installs the systemd units with `sudo cp` into `/etc/systemd/system`.
  - It currently uses `cancel-in-progress: true`.
- **Where the prod Traefik configuration lives:** it is **not committed** in the repo. `deploy-backend.yml` takes one of two paths:
  - If port 80 is free, it writes `~/keevo/traefik/dynamic.yml` on the server (router `backend`, `websecure`, `certResolver: letsencrypt`, service `http://backend:4500`) and runs its own `keevo_traefik` (v3.3, :80/:443).
  - Otherwise it assumes an external Traefik: backend labels with `certresolver=mytlschallenge` on the external network `traefik-public`.
  - Root `docs/DEPLOYMENT_CHECKLIST.md` §5 says the prod `docker-compose.prod.yml` is **maintained by hand on the server** (Traefik Docker API v1.24 workaround).
  - So the staging router (`Host(staging.api.keevo.cm)` → `http://keevo_staging_backend:4500` over a shared external network `keevo_edge`) must be delivered as a documented server-side change.
- **Staging pipeline:** `.github/workflows/deploy-staging.yml` runs on push to `staging` and on `workflow_dispatch` with no branch guard. It uses `concurrency` `cancel-in-progress: true` and image tag `staging-latest`.
  - It deploys to `~/keevo-staging` with its own `keevo_staging_traefik` on :8080/:8443 (ACME HTTP-01 on :8080 can never validate), plus `keevo_staging_postgres` and `keevo_staging_backend` on `keevo_staging_net`.
  - JWT comes from the `STAGING_JWT_*_PEM` secrets. DB and Firebase come from server files `~/keevo-staging/.env` and `firebase-credentials.json`.
  - The Firebase bind-mount is unconditional, there is no `.env` guard, and the private key uses `chmod 600`, which is not verified against the UID 2001 container. Prod's `chgrp 2001`/`640` strategy is the reference.
  - Docs: `keevo/backend/docs/STAGING.md`.
- **Backups:**
  - `keevo/backend/scripts/backup-db.sh`: `docker-exec` mode by default (container `keevo_postgres`), `--no-owner --no-acl`, `flock` lock, `pg_restore --list` validation, optional S3 upload (`STANDARD_IA`), local rotation in `/var/backups/keevo` after 30 days, exit 1 when S3 fails.
  - `keevo-backup.timer` runs daily at 02:00 UTC with `Persistent=true`.
  - `keevo-backup.service` hardcodes `/home/ubuntu/keevo/.env.backup` and `/home/ubuntu/keevo/scripts/backup-db.sh`, with no `OnFailure=`.
  - The S3 lifecycle (GLACIER after 30 days, expiry after 90 days) is documented but not enforced from the repo.
- **Docs, duplicated today:**
  - Root `docs/DEPLOYMENT_CHECKLIST.md` (French): server setup, `.env`, §7 backups, §9 single backend instance.
  - `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` (English, from 10.3): env vars, fail-fast, secret rotation.
  - `keevo/backend/docs/BACKUP_RESTORE.md`: architecture, restore, smoke test run on 2026-10-11. Restore with the **container's** `pg_restore` (same major version). The re-grant step after a `--no-owner --no-acl` restore is not documented yet.
  - 10.7 must pick **one** checklist location and fix the cross-references.
- **Invariants that must be preserved:**
  - Health sampling assumes a **single backend instance** (in-memory HTTP counter deltas, from 9-5 and checklist §9). Do not scale the backend or add replicas.
  - `DASHBOARD_ORIGIN` **must be set in prod** (CORS explicit list, from 11-4). If it is empty, no browser origin is allowed and the boot only warns. Staging needs its own value.
- **ArchUnit/Modulith (10.6):** the verification runs in warning mode, and making it blocking belongs to Epic 12.

## Cross-Story Dependencies

- 10.7 inherits its items from the 10.4 and 10.5 reviews. Its TLS work is a prerequisite for valid staging HTTPS, which later work depends on: migration dry-runs on a prod copy (Epic 16) and canary tests.
- The `DELETED`-tenant cleanup relies on the v1s-15-9 `forceDelete` behaviour (refuses `DELETED` tenants) and on the existing `AccountDeletionScheduler`.
- 10.2's dual regime (Flyway-public versus out-of-band tenant schemas) ends in Epic 13 (`ModuleProvisioner`). 10.6's baseline becomes blocking in Epic 12.
