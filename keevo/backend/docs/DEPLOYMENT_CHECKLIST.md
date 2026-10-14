# Keevo Backend — Deployment Checklist

> **Single deployment checklist** (Story 10.7). The former root `docs/DEPLOYMENT_CHECKLIST.md`
> now only points here. Sections 1–6 come from Story 10.3 (secrets, fail-fast); sections 7–17
> cover the server, backups, staging and one-off prod operations.
>
> Nothing in this repository applies these server steps automatically: the backend deploy is
> run by hand, and every step marked **(server)** is executed by the operator on the VPS.

This document lists all required environment variables for production deployment
and provides rotation procedures for secrets.

## Contents

1. Required environment variables · 2. Docker image secrets · 3. Fail-fast · 4. Secret rotation ·
5. Post-deployment verification · 6. Git security audit · 7. GitHub Actions secrets (prod) ·
8. Server setup (files, prerequisites, DNS) · 9. Prod Traefik — known issue · 10. Stack checks ·
11. Backups · 12. S3 protection (IAM, versioning, Object Lock) · 13. Restore — re-grant step ·
14. Staging bring-up · 15. Staging TLS through the prod Traefik · 16. Legacy `DELETED` tenants
cleanup · 17. Single backend instance · 18. Mise en production du lot « V1 stabilisation »

---

## 1. Required Environment Variables (Production)

### 1.1 Spring Profile

| Variable | Required | Example | Notes |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | ✅ | `prod` | Activates `ProdSecretsValidator` fail-fast |

### 1.2 PostgreSQL

| Variable | Required | Example | Notes |
|---|---|---|---|
| `POSTGRES_HOST` | ✅ | `db.prod.internal` | Internal DB host |
| `POSTGRES_PORT` | ✅ | `5432` | Default PostgreSQL port |
| `POSTGRES_DB` | ✅ | `keevo_prod` | Database name |
| `POSTGRES_USER` | ✅ | `keevo_app` | Application DB user (limited privileges) |
| `POSTGRES_PASSWORD` | ✅ | *(`openssl rand -hex 16`)* | ≥ 12 chars, no dev/CI default, no placeholder — rejected by `ProdSecretsValidator` |

### 1.3 Super Admin Bootstrap

| Variable | Required | Example | Notes |
|---|---|---|---|
| `ADMIN_PHONE` | ✅ | `+237600000000` | Phone number for system super-admin |
| `ADMIN_PASSWORD` | ✅ | *(strong, unique password)* | ≥ 12 chars, 3 of 4 classes, ≤ 72 UTF-8 bytes; **never `Admin@1234!`** nor the `.env.example` placeholder — rejected by `ProdSecretsValidator` |

### 1.4 JWT RSA Keys

| Variable | Required | Example | Notes |
|---|---|---|---|
| `KEEVO_JWT_PRIVATE_KEY_PATH` | ✅ | `/run/secrets/private_key.pem` | **Must be absolute path** — `classpath:` rejected in prod |
| `KEEVO_JWT_PUBLIC_KEY_PATH` | ✅ | `/run/secrets/public_key.pem` | **Must be absolute path** — `classpath:` rejected in prod |

Generate a new RSA keypair:
```bash
openssl genrsa -out private_key.pem 4096
openssl rsa -in private_key.pem -pubout -out public_key.pem
chmod 400 private_key.pem public_key.pem
```

### 1.5 Firebase Cloud Messaging (FCM)

| Variable | Required | Example | Notes |
|---|---|---|---|
| `KEEVO_FCM_ENABLED` | Optional | `true` | Set to `true` only if FCM is configured |
| `GOOGLE_APPLICATION_CREDENTIALS` | If FCM enabled | `/run/secrets/firebase-adminsdk.json` | Path to service account JSON — mount as volume |

### 1.6 WhatsApp Provider

| Variable | Required | Example | Notes |
|---|---|---|---|
| `KEEVO_WHATSAPP_PROVIDER` | Optional | `wassender` | `noop` (default) / `wassender` / `twilio` / `failover` |
| `WASSENDER_API_URL` | If wassender/failover | `https://www.wasenderapi.com` | Wassender API base URL |
| `WASSENDER_API_TOKEN` | If wassender/failover | *(API token)* | Session API key from WassenderAPI Dashboard |
| `TWILIO_ACCOUNT_SID` | If twilio/failover | *(real `AC…` SID)* | From Twilio Console |
| `TWILIO_AUTH_TOKEN` | If twilio/failover | *(auth token)* | From Twilio Console |
| `TWILIO_FROM_NUMBER` | If twilio/failover | `+14155238886` | WhatsApp sender number (E.164) |

### 1.7 Application

| Variable | Required | Example | Notes |
|---|---|---|---|
| `APP_PORT` | Optional | `4500` | Default: 4500 |
| `LOG_LEVEL` | Optional | `INFO` | Default: INFO |
| `DASHBOARD_ORIGIN` | Recommended | `https://admin.keevo.cm` | CORS allow-list, comma-separated. In prod/staging it is the **only** allowed browser origin (no `localhost:3000`, no `*`); unset → every browser origin refused and `ProdSecretsValidator` logs a WARN (boot continues). Mobile app and server-side dashboard proxy are unaffected (Story 11.4) |

---

## 2. Docker Image — Secrets Exclusion

The `.dockerignore` file excludes the following from the Docker image:
- `*.json` — Firebase service account keys
- `**/keys/*.pem` — JWT PEM key files
- `.env`, `.env.*` — Environment files

**All secrets must be mounted as volumes at runtime**, never baked into the image:

```yaml
# docker-compose.prod.yml example
services:
  keevo-backend:
    image: ghcr.io/keevo/backend:latest
    volumes:
      - ./secrets/private_key.pem:/run/secrets/private_key.pem:ro
      - ./secrets/public_key.pem:/run/secrets/public_key.pem:ro
      - ./secrets/firebase-adminsdk.json:/run/secrets/firebase-adminsdk.json:ro
    env_file:
      - .env.prod
```

---

## 3. Fail-Fast Validation

When `SPRING_PROFILES_ACTIVE=prod` or `staging`, the `ProdSecretsValidator` bean
checks at boot time (`@PostConstruct`). `ProdSecretsValidatorOrdering` makes every `DataSource`
depend on it, so the checks run **before** any DB connection or Flyway migration.

A value is treated as **unset** when it is blank or contains (case-insensitive) one of the
placeholder markers `change_me`, `changeme`, `your_`, `replace_with`, `placeholder`, `xxxxxxxx`.

1. **POSTGRES_PASSWORD** — set, not a placeholder, not a known dev/CI default
   (`keevo_local_pwd`, `change_me_in_dot_env`, `ci_password`, `postgres`, `keevo`, `password`,
   case-insensitive), at least **12** characters. Use a generated secret (`openssl rand -hex 16`).
2. **ADMIN_PASSWORD** — set, not a placeholder (the `.env.example` value is rejected), not
   `Admin@1234!`, at least **12** characters with at least **3 of 4** classes
   (lowercase / uppercase / digit / symbol), at most **72 UTF-8 bytes** (BCrypt limit).
3. **JWT private/public key paths** — absolute file paths (not `classpath:`), readable and
   non-empty; the private key must not be world-readable (story 12.1).
4. **WhatsApp provider tokens** (`KEEVO_WHATSAPP_PROVIDER`):
   - `wassender` → `WASSENDER_API_URL` and `WASSENDER_API_TOKEN` set, not placeholders
   - `twilio` → `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` and `TWILIO_FROM_NUMBER` set, not placeholders (`ACxxxx…` rejected)
   - `failover` → all of the above
   - the value is matched exactly (case-insensitive, no trimming): an empty or space-padded value is rejected
   - `noop` / unset → allowed, a WARN is logged (no WhatsApp/OTP delivery)
   - any other value → rejected

If any check fails, the application **refuses to start** with an explicit error naming the
environment variable (the secret value is never logged), e.g.:
```
[FailFast] POSTGRES_PASSWORD is too short — at least 12 characters are required in production.
```

> ⚠️ **Rotate before deploying (story v1s-12-13).** These rules apply to the live prod `.env`
> on the next deploy. If the current `POSTGRES_PASSWORD` or `ADMIN_PASSWORD` does not comply,
> the new container will not boot. Rotate the non-compliant secret first (for the DB password:
> `ALTER USER … PASSWORD …` in PostgreSQL, then update `.env`), then deploy.

In `dev` profile (and tests without a profile), no validation occurs (permissive mode).

---

## 4. Secret Rotation Procedures

### 4.1 Rotate ADMIN_PASSWORD

> **Note:** `AdminAccountInitializer` uses `ADMIN_PASSWORD` only when the super-admin account does
> not exist yet (first boot on an empty DB). On an existing DB the env value only gates boot
> (`ProdSecretsValidator`); the live admin password is **not** changed by editing `.env` — it must
> be changed through the app's change-password flow (step 3 below).

1. Change `ADMIN_PASSWORD` in the server's environment/secrets manager
2. Redeploy the application — `AdminAccountInitializer` skips re-hashing (user already exists)
3. Use the API endpoint to update the hashed password:
   ```bash
   curl -X POST https://<domain>/api/v1/admin/change-password \
     -H "Authorization: Bearer <current-token>" \
     -H "Content-Type: application/json" \
     -d '{"oldPassword":"<old>","newPassword":"<new>"}'
   ```

### 4.2 Rotate JWT Keypair

1. Generate a new RSA 4096-bit keypair:
   ```bash
   openssl genrsa -out private_key.pem 4096
   openssl rsa -in private_key.pem -pubout -out public_key.pem
   chmod 400 private_key.pem public_key.pem
   ```
2. Replace the mounted PEM files on the server
3. Redeploy — **all existing tokens are invalidated** (new key = new tokens required)
4. Users must re-authenticate

### 4.3 Rotate Firebase Service Account JSON

1. Generate a new service account key in the [Firebase Console](https://console.firebase.google.com)
2. Replace the mounted JSON file on the server
3. Redeploy — `FirebaseInitializer` reads credentials at boot time

---

## 5. Post-Deployment Verification

```bash
# 1. Health check
curl -s https://<domain>/actuator/health | jq .

# 2. Verify fail-fast is active (should NOT see this — only if misconfigured)
# Boot log should contain:
#   [FailFast] Validating production secrets...
#   [FailFast] POSTGRES_PASSWORD ✓
#   [FailFast] ADMIN_PASSWORD ✓
#   [FailFast] JWT key paths ✓
#   [FailFast] WhatsApp provider configuration ✓
#   [FailFast] Production secrets validation passed.

# 3. Verify no secrets in Docker image
docker run --rm <image> find /app -name "*.json" -o -name "*.pem"
# Expected: no output (secrets excluded by .dockerignore)

# 4. Verify Firebase JSON not in image
docker run --rm <image> find /app -name "*firebase*adminsdk*"
# Expected: no output
```

---

## 6. Git Security Audit

```bash
# Verify Firebase JSON is not tracked by Git
git ls-files | grep firebase
# Expected: no output

# Verify Firebase JSON is not in Git history
git log --all -- "*firebase*"
# Expected: no output

# Verify .env files are not tracked
git ls-files | grep "\.env"
# Expected: only .env.example
```

---

## 7. GitHub Actions Secrets (production)

**Settings → Secrets and variables → Actions → Repository secrets.** Only the secret *names* live
in the repository; values are set in GitHub.

| Secret | Required | Used by | Description |
|---|---|---|---|
| `DEPLOY_SSH_HOST` | ✅ | `deploy-backend.yml` | Server IP or hostname |
| `DEPLOY_SSH_USER` | ✅ | `deploy-backend.yml` | SSH user (the backup units are templated on its home, §11) |
| `DEPLOY_SSH_PRIVATE_KEY` | ✅ | `deploy-backend.yml` | Full SSH private key |
| `DEPLOY_SSH_PORT` | optional | `deploy-backend.yml` | Defaults to 22 |
| `GHCR_PAT` | ✅ | `deploy-backend.yml`, `deploy-staging.yml` | GitHub PAT, `read:packages` (`write:packages` if it also pushes) |
| `APP_DOMAIN` | ✅ | `deploy-backend.yml`, `release-flutter.yml` | Prod FQDN without `https://` |
| `ACME_EMAIL` | ✅ | `deploy-backend.yml` | Let's Encrypt account e-mail |
| `JWT_PRIVATE_KEY_PEM` | ✅ | `deploy-backend.yml` | Full content of `private_key.pem` |
| `JWT_PUBLIC_KEY_PEM` | ✅ | `deploy-backend.yml` | Full content of `public_key.pem` |
| `ANDROID_KEYSTORE_BASE64` | ✅ | `release-flutter.yml` | `base64 -w0 keevo-release.keystore` |
| `ANDROID_KEY_ALIAS` | ✅ | `release-flutter.yml` | Key alias in the keystore |
| `ANDROID_STORE_PASSWORD` | ✅ | `release-flutter.yml` | Keystore password |
| `ANDROID_KEY_PASSWORD` | ✅ | `release-flutter.yml` | Key password |

Staging secrets live in the GitHub **environment** `staging` (§14), never in the prod names.

JWT key pair (written to `~/keevo/keys/` by every deploy, `chgrp 2001` + `640`):

```bash
openssl genrsa -out private_key.pem 2048
openssl rsa -in private_key.pem -pubout -out public_key.pem
```

Android keystore, if none exists:

```bash
keytool -genkey -v -keystore keevo-release.keystore -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

---

## 8. Server setup (server)

### 8.1 Files under `~/keevo/`

| File | Mode | Content |
|---|---|---|
| `.env` | `600` | Backend variables (§1). `POSTGRES_HOST`/`POSTGRES_PORT` are overridden by compose (`postgres:5432`). Includes `ACME_EMAIL`, `SPRING_PROFILES_ACTIVE=prod`, `DASHBOARD_ORIGIN` (**must** be set in prod, Story 11.4), `FLYWAY_ENABLED=true`, `KEEVO_FCM_ENABLED`, `GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase`, WhatsApp provider, `ADMIN_PHONE`/`ADMIN_PASSWORD` |
| `firebase-credentials.json` | `640`, group 2001 | Prod Firebase service-account JSON (a **file**, not a directory: `file ~/keevo/firebase-credentials.json` → `JSON data`) |
| `keys/` | `750`, group 2001 | Written by the workflow — nothing to do by hand |
| `.env.backup` | `600` | Backup variables (§11.1) |
| `.env.backup-alert` | `600` | Backup alert webhook (§11.1) |

Strong PostgreSQL password: `openssl rand -hex 20`.

One-time host prerequisite (the container runs as UID/GID 2001 and reads the keys through the group):

```bash
sudo groupadd -g 2001 keevo-jwt && sudo usermod -aG keevo-jwt "$(id -un)"
```

### 8.2 Prerequisites

```bash
docker --version          # Docker Engine v24+
docker compose version    # Compose plugin v2.x
# Open ports (cloud security group / firewall): 22 (SSH), 80 (HTTP → HTTPS), 443 (HTTPS).
# 4500 (backend) is NOT exposed — traffic goes through Traefik. 8080/8443 are no longer used (§14).
```

### 8.3 DNS

Point the prod domain to the server **before** the first deploy (Let's Encrypt needs it):

| Record | Type | Value |
|---|---|---|
| `<APP_DOMAIN>` | A | `<server IP>` |
| `staging.api.keevo.cm` | A | `<server IP>` (same VPS, §15) |

```bash
dig <APP_DOMAIN> +short   # must return the server IP
```

---

## 9. Prod Traefik — known issue (Docker API version)

The workflow injects `DOCKER_API_VERSION: "1.44"`, but Traefik v3.3 ignores it and calls `/v1.24/`.
**Active workaround:** `~/keevo/docker-compose.prod.yml` is maintained by hand on the server. After
an automatic deploy, check that Traefik discovers the containers:

```bash
docker logs --tail 20 keevo_traefik
# expected: "Configuration loaded from Docker"; error "client version 1.24 is too old" → re-create:
BACKEND_IMAGE=ghcr.io/<owner>/<repo>/backend:latest \
  docker compose -f ~/keevo/docker-compose.prod.yml up -d --force-recreate traefik
```

---

## 10. Stack checks after a prod deploy

```bash
docker ps                               # all containers Up
docker logs --tail 20 keevo_traefik     # no errors
docker logs --tail 30 keevo_backend     # backend started
curl https://<APP_DOMAIN>/actuator/health                     # {"status":"UP"}
curl -vI https://<APP_DOMAIN> 2>&1 | grep "issuer\|subject"   # issuer: Let's Encrypt
```

---

## 11. Backups (PostgreSQL)

`deploy-backend.yml` copies `backup-db.sh`, `backup-alert.sh`, `render-backup-units.sh` and the units
to `~/keevo/scripts/`, renders the units into a temp directory (placeholder check +
`systemd-analyze verify`), installs them into `/etc/systemd/system/` and enables
`keevo-backup.timer` (daily 02:00 UTC).
Full architecture, restore and rollback: [`BACKUP_RESTORE.md`](BACKUP_RESTORE.md).

| File | Role |
|---|---|
| `keevo-backup.service` | Oneshot backup (one unit, not a `@` template). Path templated: `__KEEVO_HOME__` is replaced at install time by `${HOME}/keevo` of the deploy user (`render-backup-units.sh`, never `cp`). `EnvironmentFile=-…/.env.backup`, `OnFailure=keevo-backup-alert@%n.service`, `Wants=`/`After=docker.service` (Docker down → the script fails → alert), `TimeoutStartSec=2h` (hung dump/upload → killed → alert) |
| `keevo-backup-alert@.service` | Started on any failure of the backup unit, as `keevo-backup-alert@keevo-backup.service.service`; runs `backup-alert.sh <unit>` |
| `backup-alert.sh` | Logs the alert to journald (`keevo-backup-alert`) and POSTs it to `BACKUP_ALERT_WEBHOOK_URL` (URL, token and payload via stdin/temp file, never on the command line) |
| `backup-db.sh` | Strict mode (`set -euo pipefail` + ERR trap): every failure exits non-zero, so `OnFailure=` fires — including Docker down, `BACKUP_REQUIRE_S3=1` without bucket, and a **stale lock** (a previous run stuck > 6 h). Optional success heartbeat (§11.5) |
| `render-backup-units.sh` | Renders the units (escaped `sed`, placeholder check); also used by the CI test `scripts/tests/backup-alert-test.sh` |
| `keevo-backup.timer` | Daily 02:00 UTC, `Persistent=true` |

Manual install (same as the workflow), as the deploy user:

```bash
DEPLOY_DIR="${HOME}/keevo"
UNITS_TMP="$(mktemp -d)"
"${DEPLOY_DIR}/scripts/render-backup-units.sh" "${DEPLOY_DIR}" "${DEPLOY_DIR}/scripts" "${UNITS_TMP}"
sudo install -m 644 "${UNITS_TMP}"/keevo-backup.service "${UNITS_TMP}"/keevo-backup-alert@.service \
     "${UNITS_TMP}"/keevo-backup.timer /etc/systemd/system/
rm -rf "${UNITS_TMP}"
sudo systemctl daemon-reload && sudo systemctl enable --now keevo-backup.timer
sudo systemd-analyze verify /etc/systemd/system/keevo-backup.service   # no output expected
```

### 11.1 Environment files (server)

`~/keevo/.env.backup` (mode `600`, owner = deploy user):

```env
POSTGRES_USER=keevo
POSTGRES_PASSWORD=<prod password>
POSTGRES_DB=<prod database>
AWS_ACCESS_KEY_ID=<key of the backup IAM user, §12>
AWS_SECRET_ACCESS_KEY=<secret>
AWS_DEFAULT_REGION=<region>
BACKUP_S3_BUCKET=<bucket>
BACKUP_S3_PREFIX=keevo/
# prod: no bucket = failure (alert), never a silent local-only backup
BACKUP_REQUIRE_S3=1
# optional — success heartbeat, §11.5
BACKUP_HEARTBEAT_URL=<ping URL>
```

Values are read by systemd `EnvironmentFile=`: one `KEY=value` per line, unquoted (or in double
quotes), no `export`, comments only on their own line (a trailing `# …` becomes part of the value).

`~/keevo/.env.backup-alert` (mode `600`) — kept separate so that an alert still goes out when
`.env.backup` itself is missing:

```env
# Slack/Discord incoming webhook, or a WhatsApp gateway endpoint
BACKUP_ALERT_WEBHOOK_URL=<webhook URL>
# optional — sent as "Authorization: Bearer …"
BACKUP_ALERT_WEBHOOK_TOKEN=<token>
# optional — recipient for WhatsApp gateways (e.g. WasenderAPI send-message)
BACKUP_ALERT_TO=<+2376XXXXXXXX>
```

The JSON payload is `{"text": "...", "content": "..."}` (+ `"to"` when `BACKUP_ALERT_TO` is set).

```bash
chmod 600 ~/keevo/.env.backup ~/keevo/.env.backup-alert
```

The deploy prints a **warning** (not a failure) when `.env.backup` is missing or when neither
`.env.backup-alert` nor `.env.backup` holds a non-empty `BACKUP_ALERT_WEBHOOK_URL`.

### 11.2 Checks after a deploy (server)

```bash
systemctl status keevo-backup.timer                  # Active: active (waiting)
systemctl list-timers keevo-backup.timer             # NEXT = next 02:00 UTC
systemctl cat keevo-backup.service | grep -E 'OnFailure|EnvironmentFile|ExecStart'
#   OnFailure=keevo-backup-alert@%n.service, paths under /home/<deploy user>/keevo (no __KEEVO_HOME__)
sudo systemctl start keevo-backup.service
journalctl -u keevo-backup.service --since "5 minutes ago"
#   [BACKUP][INFO] Backup complete: keevo_backup_YYYY-MM-DDTHH-MM-SSZ.dump
```

### 11.3 Test the failure alert (server)

Run once after the first install, then after any change to the alert settings. It does not touch
the database or the bucket:

```bash
# Simulate a failure without editing the unit: run it with a wrong container name.
sudo systemctl set-environment DOCKER_CONTAINER=keevo_alert_test_missing
sudo systemctl start keevo-backup.service ; echo "exit=$?"     # exit=1 expected
sudo systemctl unset-environment DOCKER_CONTAINER
# The alert unit ran:
systemctl status 'keevo-backup-alert@keevo-backup.service.service' --no-pager
journalctl -t keevo-backup-alert --since "5 minutes ago"
#   [BACKUP-ALERT][ERROR] Keevo : échec de la sauvegarde PostgreSQL …
#   [BACKUP-ALERT][INFO] Alert posted to webhook        ← and the message arrived on the channel
sudo systemctl reset-failed keevo-backup.service 'keevo-backup-alert@keevo-backup.service.service'
```

> `systemctl set-environment` applies to the systemd manager, so it only works when
> `.env.backup` does not define `DOCKER_CONTAINER` (it does not by default). Exit code of the alert
> script: `0` posted, `1` webhook failed, `2` no webhook configured (the alert unit then shows as
> failed in `systemctl --failed`, on purpose).

### 11.4 Stale lock

`backup-db.sh` takes a `flock` on `/tmp/keevo-backup.lock`. A run that finds the lock held exits 0
(another backup is running) unless the lock is older than 6 h (`BACKUP_LOCK_STALE_SECONDS`): then it
logs `stale lock` and exits 1 (alert). Investigate the stuck process (`ps aux | grep -E 'pg_dump|aws s3'`)
before killing it; with `TimeoutStartSec=2h` a stuck run started by systemd is normally killed first.

### 11.5 Missing backups — success heartbeat (dead-man switch)

An `OnFailure=` alert cannot fire when the backup never runs (timer disabled, host down, systemd
broken). Configure a heartbeat monitor (healthchecks.io-style, or any "cron monitor"):

1. Create a check with period **24 h** and grace **2 h** — i.e. *alert if no ping within 26 h* — and
   point its notifications to the same channel as the backup alert.
2. Put its ping URL in `.env.backup`: `BACKUP_HEARTBEAT_URL=<ping URL>`.
3. `backup-db.sh` pings it (GET) only at the end of a **successful** run; a failed ping only logs a
   warning (the monitor then reports the missed backup itself).
4. Check: `sudo systemctl start keevo-backup.service` → `[BACKUP][INFO] Heartbeat sent` in the
   journal and a ping on the monitor.

### 11.6 S3 lifecycle

Apply the lifecycle policy of [`BACKUP_RESTORE.md` §8](BACKUP_RESTORE.md#8-politique-de-cycle-de-vie-s3)
(GLACIER after 30 days, expiry after 90 days, noncurrent versions cleaned once Object Lock has expired).

---

## 12. S3 protection — least privilege, versioning, Object Lock (AWS console / CLI)

Goal: an attacker with root on the VPS can read and add backups but cannot delete or overwrite them.
Run these commands from an **admin** AWS profile, never with the backup credentials of the server.

### 12.1 IAM policy of the backup user

Dedicated IAM user (e.g. `keevo-backup`) whose keys go into `.env.backup`. Replace `<bucket>`;
the prefix must match `BACKUP_S3_PREFIX`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "WriteAndReadBackupObjects",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::<bucket>/keevo/*"
    },
    {
      "Sid": "ListBackupPrefix",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<bucket>",
      "Condition": { "StringLike": { "s3:prefix": ["keevo/", "keevo/*"] } }
    }
  ]
}
```

No `s3:DeleteObject`, `s3:DeleteObjectVersion`, `s3:PutLifecycleConfiguration`,
`s3:PutBucketVersioning`, `s3:PutObjectRetention` or `s3:BypassGovernanceRetention`.

```bash
aws iam put-user-policy --user-name keevo-backup --policy-name keevo-backup-s3 \
  --policy-document file://keevo-backup-s3.json
# Check from the server (backup credentials): upload works, delete is refused.
aws s3 cp /etc/hostname "s3://<bucket>/keevo/iam-check.txt"     # OK
aws s3 rm "s3://<bucket>/keevo/iam-check.txt"                   # AccessDenied expected
```

### 12.2 Versioning + Object Lock (governance, 30 days)

```bash
aws s3api put-bucket-versioning --bucket <bucket> --versioning-configuration Status=Enabled
aws s3api put-object-lock-configuration --bucket <bucket> --object-lock-configuration \
  '{"ObjectLockEnabled":"Enabled","Rule":{"DefaultRetention":{"Mode":"GOVERNANCE","Days":30}}}'
# Check
aws s3api get-bucket-versioning --bucket <bucket>             # "Status": "Enabled"
aws s3api get-object-lock-configuration --bucket <bucket>     # GOVERNANCE, 30 days
```

- Object Lock on an existing bucket requires versioning (first command). Each new backup is then
  locked 30 days; only an admin holding `s3:BypassGovernanceRetention` can remove it earlier.
- Uploads to an Object Lock bucket need a checksum header: use AWS CLI v2 ≥ 2.23 on the server
  (`aws --version`), then run a manual backup (§11.2) and check the object retention:
  `aws s3api get-object-retention --bucket <bucket> --key keevo/<file>.dump`.
- The `iam-check.txt` object of §12.1 cannot be deleted by the backup user: the lifecycle of §11.6
  removes it with the backups (or delete it from the admin profile).

---

## 13. Restore — re-grant step after `--no-owner --no-acl`

Backups are taken with `pg_dump -Fc --no-owner --no-acl`, so a restore carries **no owners and no
grants**: every object belongs to the role that ran `pg_restore`. The full restore procedure is in
[`BACKUP_RESTORE.md` §5 and §7](BACKUP_RESTORE.md#7-rollback-production); after its restore step:

1. **Preferred — restore as the application role** (the `POSTGRES_USER` of the backend `.env`,
   `keevo` today). The objects are then owned by the app role: no grant is needed. Check:

   ```bash
   docker exec keevo_postgres psql -U keevo -d <db> -Atc "
     SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE (n.nspname = 'public' OR n.nspname LIKE 'kv\_%')
       AND pg_get_userbyid(c.relowner) <> 'keevo';"
   # expected: 0
   ```

2. **If the restore ran as another role** (e.g. the `postgres` superuser) — re-grant the app role
   (replace `<app_role>` and `<db>`), then check the grants:

   ```sql
   GRANT CONNECT, CREATE ON DATABASE <db> TO <app_role>;   -- CREATE: tenant schema provisioning
   DO $$
   DECLARE s text;
   BEGIN
     FOR s IN SELECT nspname FROM pg_namespace WHERE nspname = 'public' OR nspname LIKE 'kv\_%' LOOP
       EXECUTE format('GRANT USAGE, CREATE ON SCHEMA %I TO <app_role>', s);
       EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES ON ALL TABLES IN SCHEMA %I TO <app_role>', s);
       EXECUTE format('GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA %I TO <app_role>', s);
       EXECUTE format('GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA %I TO <app_role>', s);
     END LOOP;
   END $$;
   ```

   ```bash
   docker exec keevo_postgres psql -U postgres -d <db> -Atc "
     SELECT count(*) FROM pg_tables
     WHERE (schemaname = 'public' OR schemaname LIKE 'kv\_%')
       AND NOT has_table_privilege('<app_role>', format('%I.%I', schemaname, tablename), 'SELECT,INSERT,UPDATE,DELETE');"
   # expected: 0
   ```

   Grants do not let the app role run DDL on tables it does not own: Flyway migrations
   (`ALTER TABLE`) would then fail. Transfer ownership too, table by table
   (`ALTER TABLE … OWNER TO <app_role>`), or redo the restore as the app role (step 1).

3. Start the backend and check `/actuator/health` → `UP` and a login (BACKUP_RESTORE.md §7.2).

---

## 14. Staging bring-up

Staging runs on the same VPS in `~/keevo-staging/` (`keevo_staging_postgres`, `keevo_staging_backend`),
deployed by `deploy-staging.yml` **only from the `staging` branch** (a manual run from another branch
runs no job). The running deploy is never cancelled; only the most recent pending run is kept
(`cancel-in-progress: false`). The job fails when `keevo_staging_backend` is not healthy within
120 s, and reports (without failing) whether the prod Traefik is attached to `keevo_edge`. Staging
has **no Traefik and no published port**: it is served by the prod Traefik (§15). More detail:
[`STAGING.md`](STAGING.md).

### 14.1 Secrets — GitHub environment `staging` (Settings → Environments)

Dedicated values, never copies of the prod ones (a staging compromise must not forge prod tokens).
The workflow fails before touching the server when a required one is missing, when the staging
public key does not match the staging private key, when the staging JWT key is the prod one
(public-key fingerprints compared) and when `STAGING_DB_PASSWORD` equals the prod DB password.
The two "differs from prod" checks read the **repository-level** secrets `JWT_PRIVATE_KEY_PEM` and
`DB_PASSWORD` (optional, prod DB password); when one is not visible the check is skipped with a
warning.

| Secret | Required | Content |
|---|---|---|
| `STAGING_SSH_HOST` | ✅ | VPS IP / FQDN |
| `STAGING_SSH_USER` | ✅ | Deploy user (must be in group `keevo-jwt`, GID 2001 — §8.1) |
| `STAGING_SSH_PRIVATE_KEY` | ✅ | SSH private key |
| `STAGING_SSH_PORT` | optional | Defaults to 22 |
| `GHCR_PAT` | ✅ | `read:packages` (may be shared with prod) |
| `STAGING_JWT_PRIVATE_KEY_PEM` | ✅ | **New** RSA private key (`openssl genrsa -out staging_private_key.pem 2048`) |
| `STAGING_JWT_PUBLIC_KEY_PEM` | ✅ | Matching public key (`openssl rsa -in staging_private_key.pem -pubout`) |
| `STAGING_DB_PASSWORD` | ✅ | Staging PostgreSQL password (`openssl rand -hex 20`) |
| `STAGING_FIREBASE_CREDENTIALS` | optional | Staging Firebase service-account JSON (content). Unset → no Firebase mount, FCM off |
| `DB_PASSWORD` *(repository secret)* | optional | Prod DB password, only to refuse an identical staging value |

### 14.2 Server files (server)

`~/keevo-staging/.env` (mode `600`) — the deploy stops with `missing .env` when it is absent, and
when it does not define non-empty `POSTGRES_DB` and `POSTGRES_USER`:

```env
# never the prod database
POSTGRES_DB=keevo_staging
POSTGRES_USER=keevo
# POSTGRES_PASSWORD comes from the STAGING_DB_PASSWORD secret (.env.secrets, overrides .env)
ADMIN_PHONE=<staging admin phone>
# ProdSecretsValidator runs under the staging profile too
ADMIN_PASSWORD=<staging admin password>
DASHBOARD_ORIGIN=https://<staging dashboard origin>
KEEVO_WHATSAPP_PROVIDER=noop
LOG_LEVEL=WARN
```

- Files written by each deploy (do not edit): `keys/`, `.env.secrets` (mode `600`, staging DB
  password, read by compose `env_file`), `docker-compose.staging.yml`, `docker-compose.firebase.yml`
  (only with Firebase), and **`compose.sh`** — the full compose command (both files when Firebase is
  on). Every manual operation goes through it:
  `cd ~/keevo-staging`, then `./compose.sh ps`, `./compose.sh logs -f backend`, or
  `./compose.sh up -d backend` (after a `.env` change — `restart` does not reload it).
- Do **not** copy the prod `firebase-credentials.json`: the workflow writes the staging one from its
  secret, and deletes any leftover file when the secret is unset.
- First bring-up with an existing `postgres_staging_data` volume created with another password:
  either reset it (`docker volume rm keevo-staging_postgres_staging_data`, staging data lost) or run
  `ALTER USER keevo PASSWORD '<STAGING_DB_PASSWORD>'` inside `keevo_staging_postgres` first.
- Clean up what the pre-10-7 staging left: `docker rm -f keevo_staging_traefik`,
  `docker volume rm keevo-staging_traefik_staging_letsencrypt`, `rm -rf ~/keevo-staging/traefik`,
  and close the old ports: `sudo ufw delete allow 8080/tcp && sudo ufw delete allow 8443/tcp`.

### 14.3 First deploy

1. DNS `staging.api.keevo.cm` → VPS IP (§8.3).
2. Push to `staging` (or **Actions → Deploy Staging Backend → Run workflow** with branch `staging`).
3. The workflow creates the external network `keevo_edge` if missing, attaches the backend to it and
   waits for `/actuator/health` UP (120 s max).
4. Apply §15 once (prod Traefik side), then run the checks of §15 step 5.

---

## 15. Staging TLS through the prod Traefik (server — touches prod)

Owner decision (10-5 review): the prod Traefik (:80/:443) serves `staging.api.keevo.cm` with its
ACME resolver and proxies to `keevo_staging_backend:4500` over the shared external network
`keevo_edge`. The router ships as a reviewed template, **applied by hand at the first real staging
bring-up**: [`keevo/ops/traefik/staging-router.yml`](../../ops/traefik/staging-router.yml)
(router `staging`, entrypoint `websecure`, `certResolver: letsencrypt`, service
`http://keevo_staging_backend:4500`). `deploy-backend.yml` is unchanged.

1. **Identify the prod Traefik and its configuration.**

   ```bash
   TRAEFIK=$(docker ps --filter publish=443 --format '{{.Names}}' | head -1); echo "${TRAEFIK}"
   docker inspect "${TRAEFIK}" --format '{{json .Args}}' | tr ',' '\n' | grep -E 'providers\.file|certificatesresolvers\.[^.]+\.acme\.(email|storage)'
   ```

   Note the file provider form (`providers.file.filename=…` or `providers.file.directory=…`) and the
   resolver name (`certificatesresolvers.<name>.…`). If it is not `letsencrypt`, edit `certResolver`
   in your copy of the template.

2. **Shared network.**

   ```bash
   docker network create keevo_edge || true
   docker network connect keevo_edge "${TRAEFIK}"
   docker network inspect keevo_edge --format '{{range .Containers}}{{.Name}} {{end}}'
   # expected: the prod Traefik + keevo_staging_backend
   ```

3. **Install the router** (copy the template to the server first:
   `scp keevo/ops/traefik/staging-router.yml <user>@<host>:~/keevo/traefik/staging-router.yml`).
   - *Directory provider* (`providers.file.directory=<dir>`): copy the file into the host directory
     mounted on `<dir>`. Traefik reloads it (watch).
   - *Single-file provider* (`providers.file.filename=/etc/traefik/dynamic.yml` — the `keevo_traefik`
     generated by `deploy-backend.yml`, file `~/keevo/traefik/dynamic.yml`): back it up
     (`cp ~/keevo/traefik/dynamic.yml ~/keevo/traefik/dynamic.yml.bak-10-7`), then add the
     `staging` router under `http.routers` and the `staging` service under `http.services` of that
     file. Write it **in place** (`cat merged.yml > ~/keevo/traefik/dynamic.yml`): it is a single-file
     bind mount, and an editor that replaces the file (new inode) is not seen by the container — in
     that case run `docker restart "${TRAEFIK}"`.
   - *No file provider* (Docker labels only): stop here — enabling a file provider changes the prod
     Traefik command and needs the owner's sign-off.

4. **DNS**: `staging.api.keevo.cm` A → VPS IP; `dig staging.api.keevo.cm +short`.

5. **Verify.**

   ```bash
   curl -I https://staging.api.keevo.cm                        # HTTP response, no certificate error (no -k)
   curl -s https://staging.api.keevo.cm/actuator/health        # {"status":"UP"}
   curl -vI https://staging.api.keevo.cm 2>&1 | grep -i issuer # Let's Encrypt
   docker port keevo_staging_backend                           # empty — nothing published
   ss -tln | grep -E ':(8080|8443) '                           # empty
   docker logs --tail 50 "${TRAEFIK}" | grep -i -E 'staging|acme|error'
   ```

6. **After every prod deploy** (single-file mode): `deploy-backend.yml` rewrites
   `~/keevo/traefik/dynamic.yml` and re-creates `keevo_traefik` when it manages Traefik, which drops
   both the staging router and the `keevo_edge` attachment. Re-run steps 2 and 3, then step 5.

7. **Rollback**: remove the template file (directory mode) or restore `dynamic.yml.bak-10-7`
   (single-file mode), then `docker network disconnect keevo_edge "${TRAEFIK}"`. Prod routing is
   untouched by these steps.

---

## 16. Legacy `DELETED` tenants cleanup (server — prod data, one-off)

Before v1s-15-9, the admin force-delete only flipped the status to `DELETED`: such tenants may still
hold their `kv_*` schema, memberships and tokens, and the API (`forceDelete` refuses `DELETED`) can
no longer clean them. Hand them to the daily `AccountDeletionScheduler` (03:00 container time, UTC).

1. **Take a backup first**: `sudo systemctl start keevo-backup.service` and check the journal (§11.2).
2. **Detect** (`<db>` = `POSTGRES_DB` of the prod `.env`):

   ```bash
   docker exec -it keevo_postgres psql -U keevo -d <db> -c \
     "SELECT id, schema_name FROM public.tenants WHERE status = 'DELETED' AND to_regnamespace(schema_name) IS NOT NULL;"
   ```

   0 rows → nothing to do; record the date and result in the story/ops log. Otherwise note the rows.
3. **Hand them to the purge** (same filter, so only the detected rows change):

   ```sql
   BEGIN;
   UPDATE public.tenants
      SET status = 'DELETION_PENDING', deletion_scheduled_at = now()
    WHERE status = 'DELETED' AND to_regnamespace(schema_name) IS NOT NULL;
   -- "UPDATE n" must equal the row count of step 2, else ROLLBACK;
   COMMIT;
   ```

   While `DELETION_PENDING`, writes on these tenants are refused (`ACCOUNT_DELETION_PENDING`). Do not
   cancel them from the admin dashboard. Owners still listed in the memberships receive the final
   WhatsApp « Votre compte Keevo a été supprimé » when the purge runs.
4. **Confirm the purge** after the next 03:00 run:

   ```bash
   docker logs --since 24h keevo_backend 2>&1 | grep AccountDeletionScheduler
   #   "Tenant <id> (<schema>) fully deleted: …" for each row, then "Processed N tenant deletion(s)."
   ```

   Rerun the detection query of step 2 → **0 rows**, and:

   ```sql
   SELECT count(*) FROM public.user_tenant_memberships m
     JOIN public.tenants t ON t.id = m.tenant_id WHERE t.status = 'DELETED';   -- expected 0
   ```

   A tenant still `DELETION_PENDING` after the run means its deletion failed: the log line
   `Failed to delete tenant …` gives the cause; the next daily run retries it.

5. **Leftovers of `DELETED` tenants whose schema is already gone** (the step-2 query misses them:
   no schema, but memberships, refresh tokens or orphan users may remain). Detect:

   ```sql
   SELECT t.id, t.schema_name,
          (SELECT count(*) FROM public.user_tenant_memberships m WHERE m.tenant_id = t.id) AS memberships,
          (SELECT count(*) FROM public.refresh_tokens r WHERE r.tenant_id = t.schema_name)  AS refresh_tokens
     FROM public.tenants t
    WHERE t.status = 'DELETED' AND to_regnamespace(t.schema_name) IS NULL
      AND (EXISTS (SELECT 1 FROM public.user_tenant_memberships m WHERE m.tenant_id = t.id)
        OR EXISTS (SELECT 1 FROM public.refresh_tokens r WHERE r.tenant_id = t.schema_name));
   ```

   0 rows → nothing to do. Otherwise clean them exactly like the purge does (memberships, users left
   with no membership, their device and password-reset tokens, the tenant's refresh tokens), in one
   transaction, after a backup (step 1):

   ```sql
   BEGIN;
   CREATE TEMP TABLE legacy_tenants ON COMMIT DROP AS
     SELECT id, schema_name FROM public.tenants
      WHERE status = 'DELETED' AND to_regnamespace(schema_name) IS NULL;
   CREATE TEMP TABLE legacy_users ON COMMIT DROP AS
     SELECT DISTINCT m.user_id FROM public.user_tenant_memberships m
       JOIN legacy_tenants l ON l.id = m.tenant_id;
   DELETE FROM public.user_tenant_memberships m USING legacy_tenants l WHERE m.tenant_id = l.id;
   DELETE FROM public.refresh_tokens r USING legacy_tenants l WHERE r.tenant_id = l.schema_name;
   CREATE TEMP TABLE orphan_users ON COMMIT DROP AS
     SELECT u.user_id FROM legacy_users u
      WHERE NOT EXISTS (SELECT 1 FROM public.user_tenant_memberships m WHERE m.user_id = u.user_id);
   DELETE FROM public.device_tokens d          USING orphan_users o WHERE d.user_id = o.user_id;
   DELETE FROM public.password_reset_tokens p  USING orphan_users o WHERE p.user_id = o.user_id;
   DELETE FROM public.refresh_tokens r         USING orphan_users o WHERE r.user_id = o.user_id;
   DELETE FROM public.users x                  USING orphan_users o WHERE x.id = o.user_id;
   -- the membership / refresh-token counts deleted must match the detection query, else ROLLBACK;
   COMMIT;
   ```

   Rerun the detection query → **0 rows**. Users who still belong to another tenant are kept.

---

## 17. System health (Story 9-5) — single backend instance

The admin dashboard "Santé système" page relies on a sample every 5 minutes
(`PlatformHealthSampleScheduler`). HTTP counters of each window are computed as the difference with
the previous measurement **kept in memory** by the instance.

- ✅ Deploy **a single backend instance** (current case: one `backend` container).
- ⚠️ With several instances, ShedLock would run the sampling on one or the other: recorded requests,
  5xx errors and latencies would be wrong. Move to a shared metrics registry before scaling out.

---

## 18. Mise en production du lot « V1 stabilisation » (préparé le 2026-10-14, testé en local)

Prod observée : branche `deploy` = app **1.1.12+11**, schéma public **V7**. Ce lot applique **V8 → V26**
(19 migrations Flyway) et livre l'app **1.1.6x**. Ordre imposé : **backend avant app** (l'app 1.1.12 a
été testée contre le nouveau backend : connexion, synchro, ventes hors ligne — compatible).

### 18.1 Bloquants avant tout déploiement
- [x] **Connexion super-admin du tableau de bord (W1)** — corrigé : le compte `KV-ADMIN` (schéma `public`)
      est accepté uniquement avec le rôle `SUPER_ADMIN` (`TenantSchema.isPlatformAdminSession`), à l'émission
      du jeton et dans `JwtAuthFilter`. **Note** : le filtre de la version actuellement en production refuse
      déjà ce schéma pour toutes les requêtes — le tableau de bord de prod est probablement inutilisable
      après connexion tant que ce lot n'est pas déployé. Vérifié en local : connexion, 11 pages, Premium +
      paiement, retour Gratuit, annonce ciblée reçue sur téléphone, suspension / réactivation.
- [ ] Story **10-8** (runbooks §11–§16) : sauvegardes testées (restauration à blanc), Traefik/`keevo_edge`,
      secrets staging, S3 IAM/versioning/Object Lock.

### 18.2 Avant la mise à jour du backend
- [ ] Sauvegarde complète **vérifiée** (dump + restauration à blanc sur une base jetable, §11/§13).
- [ ] `.env` prod : `DASHBOARD_ORIGIN=https://<origine du tableau de bord>` (sinon tout navigateur refusé),
      `KEEVO_EXPORT_LINK_SECRET` défini (sinon liens d'export invalidés à chaque redémarrage),
      fournisseur WhatsApp réel configuré (`KEEVO_WHATSAPP_PROVIDER`), FCM activé.
- [ ] Une seule instance backend (§17).
- [ ] **Photos produit** (seule copie des photos) : l'image Docker fixe `KEEVO_PRODUCT_PHOTO_DIR=/app/data/product-photos`
      et `docker-compose.prod.yml` (généré par `deploy-backend.yml`) y monte le **volume nommé `product_photos`**
      (`keevo_product_photos` côté Docker) ; préproduction : `product_photos_staging`. Ne jamais lancer
      `docker compose down -v` / `docker volume prune` sur ces hôtes. Volume nommé obligatoire (pas un dossier
      de l'hôte) : il est initialisé avec le bon propriétaire (uid 2001) ; un dossier hôte devrait être `chown 2001:2001`.
- [ ] Sauvegarde : `backup-db.sh` archive aussi les photos (`keevo_photos_<date>.tar.gz`, depuis le conteneur
      `keevo_backend`, envoyées sur S3 avec le dump, rotation 30 jours). Si les photos ne peuvent pas être
      archivées (backend arrêté), la sauvegarde est signalée en échec (alerte). Restauration :
      `docker exec -i keevo_backend tar -xzf - -C /app/data/product-photos < keevo_photos_<date>.tar.gz`.
- [ ] Prévoir une fenêtre calme : au premier démarrage, Flyway applique V8→V26, puis
      `TenantSchemaSyncService` met à jour chaque schéma tenant (colonnes `reports.updated_at`,
      `sales.mobile_money_ref`, `sync_operations_log.device_id`, …) et `SyncKeysetIndexJob` construit les
      index de synchro en arrière-plan (budget 5 h, `CONCURRENTLY`).

### 18.2bis Répétition faite en local (2026-10-14)
Backend `deploy` (V7) démarré sur une base vide puis alimenté par son API (propriétaire, accueil, 3 produits
+ entrées de stock, client, employé, vente) ; backend de ce lot démarré sur la même base :
« Successfully applied 19 migrations … v26 » (< 1 s), aucune `ERROR`, nom d'entreprise rattrapé (V24),
colonnes ajoutées au schéma tenant au premier accès, données intactes (produits, stocks, ventes, clients,
employés), connexion / pull / push d'une vente Mobile Money appliqués. À refaire sur une copie de la base
de production avant le jour J (volumétrie réelle).

### 18.3 Déploiement backend puis vérifications
- [ ] Journal de démarrage : « Successfully applied 19 migrations … now at version v26 », aucune `ERROR`.
- [ ] `GET /actuator/health` → `UP` ; route inconnue → **404** (et non 500).
- [ ] `POST /api/v1/auth/forgot-password` sans jeton → **200** (était 401 avant ce lot).
- [ ] Connexion super-admin au tableau de bord, pages Entreprises / Revenus /
      Statistiques / Santé / Alertes / Retours chargées sans erreur.
- [ ] Un compte de test : inscription, produit, vente, clôture, rapport WhatsApp reçu.
- [ ] `V24` : les entreprises dont le nom était leur code prennent le nom de leur première boutique —
      contrôler `select code, name from public.tenants where name = code;` (reste = boutiques sans nom).

### 18.4 App mobile (release)
- [ ] Construire avec l'URL HTTPS : `flutter build appbundle --release --dart-define=API_BASE_URL=https://<domaine-api>`
      (par défaut l'URL vaut `http://localhost:4500` — une release sans ce paramètre est inutilisable).
- [ ] Signer avec la clé de production (`android/key.properties`, cf. `key.properties.example`) — sans ce
      fichier, Gradle signe en debug.
- [ ] Mise à jour par-dessus 1.1.12 testée en local : session, données et opérations hors ligne en attente
      conservées puis synchronisées (migration de la base locale 29→30).
- [ ] Vérifier sur un appareil réel : vente en ligne, vente hors ligne puis resynchro, mot de passe oublié,
      connexion employé sur un appareil déjà utilisé par le propriétaire.

### 18.5 Retour arrière
- App : republier la version précédente (les opérations en attente restent dans la file locale).
- Backend : les migrations V8→V26 sont additives (colonnes/tables/index) ; un retour au binaire précédent
  reste possible sur le même schéma, mais **ne pas** restaurer la sauvegarde après que des ventes ont été
  synchronisées (perte de données) — corriger en avant.

### 18.6 Non couvert par les tests locaux (à faire sur staging)
- Envois WhatsApp réels (OTP, rapports, messages propriétaire), notifications push réelles.
- HTTPS/Traefik, plusieurs appareils réels, iOS, volumétrie, réseau instable (et non coupé net).

