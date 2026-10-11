---
title: 'v1s-12-13 — Prod secrets & config hardening (ProdSecretsValidator extension)'
type: 'bugfix'
created: '2026-10-11'
status: 'done'
baseline_revision: 'b85e44a860da86143f802006178777d1d2b18b88'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Under `prod`/`staging`, `ProdSecretsValidator` only rejects a blank admin password or the exact `Admin@1234!`; `POSTGRES_PASSWORD` silently falls back to the dev default `keevo_local_pwd` (`application.yml:17`), WhatsApp provider tokens are never checked, the `.env.example` placeholders pass, and the fail-fast is only unit-tested — nothing proves a prod-profile Spring context refuses to start (and the validator may run after the DataSource/Flyway already connected).

**Approach:** Extend the validator with (1) a DB-password check, (2) provider-token checks for the selected WhatsApp provider, (3) an admin-password strength policy, all sharing one placeholder/default denylist; force the validator to run before the `DataSource` bean is created; and add a Spring-Boot-level test booting `KeevoApplication` under the `prod` profile that proves startup aborts with the `[FailFast]` error.

## Boundaries & Constraints

**Always:** Error messages name the offending env var and never echo the secret value. Validator stays `@Profile({"prod","staging"})` — dev/test behaviour unchanged. Existing JWT-key checks and their tests keep passing unchanged. Dev defaults in `application.yml` stay (local dev must keep working).

**Never:** No change to deploy workflows, Docker volumes or running containers. No new secret store/library. No enforcement of a specific WhatsApp provider (noop stays legal, with a WARN). No length/class policy on provider tokens (formats differ per vendor).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DB pwd missing | `spring.datasource.password` blank/null | boot aborts | ISE mentions `POSTGRES_PASSWORD` |
| DB pwd dev default | `keevo_local_pwd`, `change_me_in_dot_env`, `ci_password`, `postgres`, `keevo`, `password` (case-insensitive) | boot aborts | ISE `POSTGRES_PASSWORD` |
| DB pwd short | < 12 chars | boot aborts | ISE `POSTGRES_PASSWORD` |
| DB pwd OK | ≥ 12 chars, not denylisted/placeholder (e.g. `openssl rand -hex 16`) | passes | — |
| Admin pwd placeholder | `change_me_strong_password` (.env.example) | boot aborts | ISE `ADMIN_PASSWORD` |
| Admin pwd weak | < 12 chars, or < 3 of 4 classes (lower/upper/digit/symbol) | boot aborts | ISE `ADMIN_PASSWORD` |
| Admin pwd > 72 UTF-8 bytes | 73+ bytes | boot aborts (bcrypt would throw in `AdminAccountInitializer`) | ISE `ADMIN_PASSWORD` |
| Admin pwd OK | `Str0ngPr0dP@ss!` | passes | — |
| Provider `wassender` | `WASSENDER_API_TOKEN` or `WASSENDER_API_URL` blank / placeholder | boot aborts | ISE names the var |
| Provider `twilio` | `TWILIO_ACCOUNT_SID` or `TWILIO_AUTH_TOKEN` blank / placeholder (`your_…`, `ACxxxx…`) | boot aborts | ISE names the var |
| Provider `failover` | union of wassender + twilio rules | boot aborts on first bad var | ISE names the var |
| Provider `noop`/unset | tokens blank | passes, WARN logged | — |
| Placeholder markers | value contains (case-insensitive) `change_me`, `changeme`, `your_`, `replace_with`, `placeholder`, `xxxxxxxx` | treated as unset | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidator.java` -- the guard (`@PostConstruct validate()`, ctor `(AdminProperties, privKeyPath, pubKeyPath)` used by the unit test); `isInsecurePassword` L95-96 is the only admin rule today.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/config/AdminProperties.java` -- record `(phone, password)`, prefix `keevo.admin`.
- `keevo/backend/src/main/resources/application.yml` L17 `password: ${POSTGRES_PASSWORD:keevo_local_pwd}`; L89 admin default; L93-104 `keevo.whatsapp.provider` + `wassender.api-url/api-token`, `twilio.account-sid/auth-token/from-number` (all default empty).
- `keevo/backend/src/main/java/com/keevo/messaging/whatsapp/adapter/out/external/*Adapter.java` -- `@ConditionalOnProperty keevo.whatsapp.provider` values `noop|wassender|twilio|failover` (read-only reference).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/RsaKeyConfig.java` -- parses the PEMs at boot: the Spring-context test must feed real keys (`src/test/resources/keys/*.pem`, copied to a temp dir with mode 600).
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/config/ProdSecretsValidatorTest.java` -- unit tests; build validators through a small helper so the new ctor args don't bloat every test.
- `keevo/backend/.env` exists locally and is imported via `spring.config.import: optional:file:.env` → the context test must pass its properties as command-line args (highest precedence), never default properties.
- `keevo/backend/.env.example`, `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` -- operator-facing docs of the rules.
- `keevo/backend/src/main/java/com/keevo/shared/domain/PasswordLimits.java` -- reuse `exceedsBcryptLimit` for the admin 72-byte cap.
- Spring Boot 3.5.0: `org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor(Class<?> beanClass, String... dependsOn)` for the ordering hook.

## Tasks & Acceptance

**Execution:**
- `.../config/ProdSecretsValidator.java` -- add ctor params `@Value("${spring.datasource.password:}") dbPassword`, `@Value("${keevo.whatsapp.provider:noop}") provider`, and the four provider values (`wassender.api-url`, `wassender.api-token`, `twilio.account-sid`, `twilio.auth-token`, all `:` empty default); single constructor, tests use a builder helper. Add `validateDatabasePassword`, `validateWhatsAppProvider`, strength policy in `validateAdminPassword`; shared `isPlaceholderOrDefault`. Unknown provider value → ISE naming `KEEVO_WHATSAPP_PROVIDER`. Update Javadoc (story v1s-12-13).
- `.../config/ProdSecretsValidatorOrdering.java` (new) -- `@Configuration @Profile({"prod","staging"})` with a `static @Bean` `AbstractDependsOnBeanFactoryPostProcessor` subclass making every `DataSource` bean depend on `prodSecretsValidator`, so secrets are validated before any DB connection/Flyway.
- `.../config/ProdSecretsValidatorTest.java` -- cover every I/O-matrix row; keep existing JWT/permission tests green.
- `.../config/ProdSecretsFailFastContextTest.java` (new) -- boots `KeevoApplication` via `SpringApplicationBuilder` (`WebApplicationType.NONE`, args `--spring.profiles.active=prod`, valid temp JWT keys, unreachable datasource `jdbc:postgresql://127.0.0.1:1/x` with short Hikari/Flyway timeouts): (a) weak `POSTGRES_PASSWORD` → `run()` throws, cause chain contains `IllegalStateException` with `[FailFast]` and `POSTGRES_PASSWORD`; (b) weak `ADMIN_PASSWORD` → same with `ADMIN_PASSWORD`; (c) all secrets valid → `run()` still throws (no DB) but no `[FailFast]` in the cause chain, proving the validator passed in-context and ran before the DB failure path. No Testcontainers needed.
- `keevo/backend/.env.example` -- comment the prod rules next to `POSTGRES_PASSWORD`, `ADMIN_PASSWORD`, provider tokens.
- `keevo/backend/docs/DEPLOYMENT_CHECKLIST.md` -- document the new fail-fast rules (DB ≥12 + denylist, admin policy, provider tokens) and that a non-compliant prod `.env` blocks the next deploy (rotate before deploying).

**Acceptance Criteria:**
- Given the `prod` profile and any weak/defaulted secret from the matrix, when the application context starts, then startup aborts before any DataSource is created, with a `[FailFast]` message naming the env var and not containing the secret.
- Given the `dev` profile (or tests without profile), when the app starts with dev defaults, then no validator bean exists and behaviour is unchanged.
- Given the three epic items, when the story closes, then each is ticked in `epics-remediation-audit.md` with its resolution.

## Spec Change Log

## Review Triage Log

### 2026-10-11 — Review pass
- verdicts: 33 findings — high 0, medium 7, low 20, false 6, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) Context test uses SpringApplicationBuilder + cause chain instead of @SpringBootTest/ApplicationContextException — same observable contract; `@SpringBootTest` cannot assert a failing context and a @PostConstruct failure is a BeanCreationException; declared in Design Notes and epic tick.
  - `[low]` `[reject]` (intent) Env-var → yml-fallback path not exercised end to end; rejection of `keevo_local_pwd` relies on the denylist matching the yml default — the local `.env` import makes an "unset" run non-deterministic; denylist unit test pins the yml value; cost > benefit.
  - `[medium]` `[patch]` (intent) Provider-token rejection proven only at unit level — grouped with the verification-gap finding; context case added (see below).
  - `[false]` `[reject]` (intent) Staging also covered — validator was already `@Profile({"prod","staging"})` since Story 10.3; unchanged scope.
  - `[low]` `[reject]` (intent) Live prod `.env` may block next deploy — accepted operational risk, documented in DEPLOYMENT_CHECKLIST and reported.
  - `[false]` `[reject]` (intent) `noop` legal in prod (reading 1c) — item names "Twilio/Wassender tokens"; noop has no token; WARN logged; decision in Design Notes.
  - `[false]` `[reject]` (intent) Admin policy only at boot — item anchors ProdSecretsValidator.java:95-96 (boot guard); runtime password DTOs already capped/min'd by 12-12.
  - `[false]` `[reject]` (intent) Other defaulted values (ADMIN_PHONE, POSTGRES_USER, FCM, pgAdmin) — not secrets / outside the Scope line; pgAdmin is a dev-only compose service.
  - `[false]` `[reject]` (intent) Unrequested ordering bean — required for the fail-fast message to win over the DB failure (proved by removing it: context tests fail).
  - `[false]` `[reject]` (intent/blind) Spec untracked, no commit in epic ticks, unrouted items — spec is committed with the story; commit SHA noted at finalize; no item deferred.
  - `[medium]` `[patch]` (edge/blind/vg) Empty or padded `KEEVO_WHATSAPP_PROVIDER` passes the validator but matches no adapter — validator no longer trims nor accepts "", unknown → FailFast; tests added.
  - `[medium]` `[patch]` (edge/blind) `TWILIO_FROM_NUMBER` not checked for twilio/failover though `TwilioProperties.isConfigured()` needs it — now required; checklist says "if twilio/failover".
  - `[low]` `[reject]` (edge/blind) WASSENDER_API_URL format not validated — misconfig surfaces on first send; adding URL parsing is a new guard, unlikely in practice.
  - `[low]` `[patch]` (edge) Whitespace counts toward the 12-char minimum — length measured on `strip()`.
  - `[low]` `[reject]` (edge/blind) Non-cased letters (CJK…) count as symbols in the class count — unlikely for an ops-chosen admin password; fix adds a branch.
  - `[low]` `[reject]` (edge) DB password via JDBC URL / hikari.password bypasses the check — deploy sets POSTGRES_PASSWORD only (deploy-backend.yml); unlikely.
  - `[low]` `[reject]` (edge/blind) Context test needs POSIX FS — existing ProdSecretsValidatorTest has the same dependency; CI and prod are Linux.
  - `[low]` `[patch]` (edge/blind) Valid-secrets context case does not prove the validator ran — asserts the "validation passed" log via OutputCapture.
  - `[medium]` `[patch]` (blind) Checklist implies rotating ADMIN_PASSWORD rotates the live admin, but AdminAccountInitializer skips an existing admin — checklist now says the env only gates boot; change the live password via the app.
  - `[low]` `[reject]` (blind) Validator stops at the first violation — pre-existing behaviour; aggregating is new structure; each message names its var.
  - `[low]` `[reject]` (blind) No pre-deploy dry-run tool — feature, not a defect of this change.
  - `[low]` `[reject]` (blind) DB password has length check only (`aaaaaaaaaaaa` passes) — deliberate policy (generated secrets, Design Notes); extra entropy rule is new complexity.
  - `[low]` `[reject]` (blind) Twilio SID format unchecked — vendor format guard, out of the "presence/non-placeholder" policy; unlikely.
  - `[low]` `[reject]` (blind) Several KNOWN_DEFAULTS entries are shadowed by the length/marker rules — harmless defence-in-depth if the minimum changes.
  - `[low]` `[reject]` (blind) Placeholder substring markers could reject a real secret — hex/base64 generated secrets cannot plausibly contain `your_`/`placeholder`/`xxxxxxxx`; accepted.
  - `[low]` `[reject]` (blind) Hard-coded bean name `prodSecretsValidator` in the ordering hook — a rename breaks prod-profile boot, which ProdSecretsFailFastContextTest catches.
  - `[low]` `[reject]` (blind) Only DataSource is ordered; other beans may start first — no other bean opens a connection with these secrets at startup; WhatsApp adapters do not send at boot.
  - `[low]` `[reject]` (blind) Weak-admin context case only hits the placeholder branch — branch coverage is at unit level; the context test proves wiring, not policy.
  - `[low]` `[reject]` (blind) Context test relies on 127.0.0.1:1 closed — port 1 (tcpmux) is never bound on dev/CI hosts.
  - `[low]` `[reject]` (blind) Context test boots the app 3+ times with no tag — ~4 s total, acceptable.
  - `[medium]` `[patch]` (vg) Provider `@Value` wiring never exercised through Spring property resolution — context case with provider=wassender + placeholder token asserting WASSENDER_API_TOKEN.
  - `[medium]` `[patch]` (vg other) Empty/padded provider matches no adapter — same root cause as the empty/padded provider entry; fixed there.
  - `[medium]` `[patch]` (blind) Checklist says TWILIO_FROM_NUMBER needed "if twilio" only though failover uses it — same root cause as the TWILIO_FROM_NUMBER entry; fixed there.

## Design Notes

- **"ApplicationContextException" in the item is approximate:** a `@PostConstruct` failure surfaces from `SpringApplication.run` as `BeanCreationException` (root `IllegalStateException`), not `ApplicationContextException`. The test asserts on the cause chain, which is the observable contract. `@SpringBootTest` cannot assert a failing context, hence `SpringApplicationBuilder`.
- **Ordering hook:** without it, component-scanned beans in `com.keevo.identity…` that need JPA are created before `com.keevo.shared…`, so Flyway would connect (or fail on auth) before the validator — the fail-fast message would be lost.
- **Policy choices:** 12-char floor for DB and admin (NIST-style length over complexity for the DB, generated secrets); admin additionally 3-of-4 classes because it is a human-chosen interactive credential exposed through `/auth/login`; 72-byte cap mirrors 12-12's `PasswordLimits`. Provider tokens: presence + non-placeholder only. `noop` remains legal in prod (OTP delivery choice is ops-level), logged WARN.
- **Operational risk accepted:** if the live prod `.env` holds a weak DB/admin password, the next (manual) deploy will refuse to boot — that is the requested fail-fast; documented in the checklist.

## Verification

**Commands:**
- `cd keevo/backend && mvn -q test -Dtest='ProdSecretsValidatorTest,ProdSecretsFailFastContextTest'` -- expected: all green.
- `cd keevo/backend && mvn test` -- expected: no failures beyond the known baseline (7 failures + 29 errors in the 10 listed persistence classes).

## Auto Run Result

Status: done

**Summary:** Under `prod`/`staging`, `ProdSecretsValidator` now fails fast on a weak/defaulted `POSTGRES_PASSWORD` (blank, placeholder, dev/CI denylist, < 12 chars after strip), a weak `ADMIN_PASSWORD` (placeholder/default incl. `.env.example`, < 12 chars, < 3 of 4 classes, > 72 UTF-8 bytes), and missing/placeholder tokens of the selected WhatsApp provider (wassender: URL+token; twilio: SID+token+from-number; failover: all; noop/unset legal with WARN; empty, padded or unknown provider rejected). `ProdSecretsValidatorOrdering` makes every `DataSource` depend on the validator so the `[FailFast]` message wins over DB/Flyway errors. A Spring-Boot-level test boots `KeevoApplication` under `prod` and proves startup aborts.

**Files changed (keevo/backend/):**
- `src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidator.java` -- DB password, admin policy, provider-token checks (10-arg ctor).
- `src/main/java/com/keevo/shared/infrastructure/config/ProdSecretsValidatorOrdering.java` -- new; DataSource dependsOn validator (prod/staging).
- `src/test/java/com/keevo/shared/infrastructure/config/ProdSecretsValidatorTest.java` -- builder helper + every I/O-matrix row.
- `src/test/java/com/keevo/shared/infrastructure/config/ProdSecretsFailFastContextTest.java` -- new; 4 prod-profile boot cases (weak DB, weak admin, placeholder Wassender token via property binding, valid secrets → validator passed log + DB failure).
- `.env.example`, `docs/DEPLOYMENT_CHECKLIST.md` -- prod rules, rotation guidance (admin env only seeds the account on first boot).
- `_bmad-output/planning-artifacts/epics/epics-remediation-audit.md` -- 3 items ticked with resolution.

**Review:** 33 findings (medium 7, low 20, false 6). Patched: 4 medium entries (empty/padded provider, TWILIO_FROM_NUMBER, admin rotation doc, provider wiring context test) + 2 low (strip before length, valid-case proof of validator run). Deferred: none. Rejected: every other row with its reason in the Review Triage Log.

**Follow-up review recommended: true** -- patched medium count 4 (high 0, low 2). Unverified risk: the provider-value semantics were changed after review (no trim, empty/padded/unknown → FailFast; TWILIO_FROM_NUMBER now mandatory) and these patched lines have not been seen by a reviewer.

**Verification:** `mvn -q test -Dtest='ProdSecretsValidatorTest,ProdSecretsFailFastContextTest'` → 84 tests, 0 failures. Full `mvn test` → 2067 tests, 7 failures + 29 errors, all in the 10 known baseline persistence classes (no new failure). `target/` removed afterwards (disk).

**Residual risks:** the live prod/staging `.env` may hold a DB/admin password or provider config that now fails the policy → the next manual deploy will not boot until secrets are rotated (DB: ALTER ROLE + `.env`; admin: env value only gates boot, the live password is changed in-app). A prod `.env` with `twilio`/`failover` and no `TWILIO_FROM_NUMBER`, or an empty `KEEVO_WHATSAPP_PROVIDER=`, will now also fail fast.
