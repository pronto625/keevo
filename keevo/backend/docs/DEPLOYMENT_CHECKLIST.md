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
| `POSTGRES_PASSWORD` | ✅ | *(strong password)* | Must be different from dev |

### 1.3 Super Admin Bootstrap

| Variable | Required | Example | Notes |
|---|---|---|---|
| `ADMIN_PHONE` | ✅ | `+237600000000` | Phone number for system super-admin |
| `ADMIN_PASSWORD` | ✅ | *(strong, unique password)* | **NEVER use `Admin@1234!`** — rejected by `ProdSecretsValidator` |

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
| `WASSENDER_API_URL` | If wassender | `https://www.wasenderapi.com` | Wassender API base URL |
| `WASSENDER_API_TOKEN` | If wassender | *(API token)* | Session API key from WassenderAPI Dashboard |
| `TWILIO_ACCOUNT_SID` | If twilio | `ACxxxx...` | From Twilio Console |
| `TWILIO_AUTH_TOKEN` | If twilio | *(auth token)* | From Twilio Console |
| `TWILIO_FROM_NUMBER` | If twilio | `+14155238886` | WhatsApp sender number (E.164) |

### 1.7 Application

| Variable | Required | Example | Notes |
|---|---|---|---|
| `APP_PORT` | Optional | `4500` | Default: 4500 |
| `LOG_LEVEL` | Optional | `INFO` | Default: INFO |

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
checks at boot time (`@PostConstruct`):

1. **ADMIN_PASSWORD** — must not be blank, null, or `Admin@1234!`
2. **JWT private key path** — must be an absolute file path (not `classpath:`)
3. **JWT public key path** — must be an absolute file path (not `classpath:`)

If any check fails, the application **refuses to start** with an explicit error:
```
[FailFast] ADMIN_PASSWORD is required in production — do not use the default value.
```

In `dev` profile, no validation occurs (permissive mode).

---

## 4. Secret Rotation Procedures

### 4.1 Rotate ADMIN_PASSWORD

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
#   [FailFast] ADMIN_PASSWORD ✓
#   [FailFast] JWT key paths ✓
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
