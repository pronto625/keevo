# Keevo Backend — Deployment Checklist

> Story 10.3 — Externalisation des secrets et fail-fast en production.

This document lists all required environment variables for production deployment
and provides rotation procedures for secrets.

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
