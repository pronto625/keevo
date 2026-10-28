---
title: '11.1 — Structured JSON logs correlated by tenantId/traceId (port to develop)'
type: 'feature'
created: '2026-10-28'
status: 'done'
baseline_commit: '472e421f1dc0f4c833ce7551b285b6eb62a1028c'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 11.1 was delivered only on the diverged `refractoring` branch; on `develop` (base of the modular refactor) prod/staging logs are plain text with no `tenantId`, `userId` or correlation id, so an incident cannot be traced per tenant or per request (MOD-NFR4).

**Approach:** Re-implement 11.1 on `develop` (do not merge `refractoring`): emit JSON console logs in `prod` and `staging` using Spring Boot 3.5 native structured logging, add a request correlation filter (`X-Request-Id` → MDC `traceId`), and populate `tenantId`/`userId` in MDC from `JwtAuthFilter`, always cleaned up per request.

## Boundaries & Constraints

**Always:**
- Every prod/staging log line is one JSON object carrying at least `timestamp`, `level`, `logger`, `message`, plus `traceId` for HTTP request threads and `tenantId`/`userId` once authenticated.
- MDC keys are removed in `finally` on every exit path (401/403 early returns, exceptions) — no leak across pooled threads.
- Inbound `X-Request-Id` is sanitized (control chars stripped, length capped) and echoed back on the response; generated as a UUID when absent or blank.
- Dev and test profiles keep the current human-readable console output and `application-dev.yml`'s `logging.file.name`.
- Existing `docker logs … | grep -E "ERROR|FailFast|Started"` procedures in `docs/MOP_*.md` keep working (substring match on JSON lines).

**Never:**
- No `logback-spring.xml` and no `logstash-logback-encoder` dependency — native `logging.structured.*` covers it and keeps Spring's file/console handling.
- No OpenTelemetry/Micrometer tracing, Prometheus or business metrics — that is Story 11.2.
- No MDC propagation to `@Async`/`@Scheduled` threads (11.2 tracing context propagation).
- No change to log levels, to existing log statements, or to `TenantContext` semantics.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Authenticated request | valid JWT, no `X-Request-Id` | log lines carry generated `traceId`, `tenantId`, `userId`; response has `X-Request-Id` = that `traceId` | N/A |
| Client-supplied id | `X-Request-Id: abc-123` | MDC `traceId` = `abc-123`, echoed back | N/A |
| Malicious id | header with CR/LF or > 256 chars | control chars stripped, truncated to 256 | never rejected |
| Public / unauthenticated path | no token or rejected token (401) | `traceId` present, no `tenantId`/`userId` | MDC cleared after response |
| Thread reuse | two sequential requests on same thread | second request never sees first request's MDC values | `finally` cleanup |

</frozen-after-approval>

## Code Map

- `keevo/backend/src/main/resources/application-prod.yml`, `application-staging.yml` -- add `logging.structured.format.console: logstash` and `logging.structured.json.rename` (`logger_name` → `logger`, `@timestamp` → `timestamp`); leave `logging.level` as is.
- `keevo/backend/src/main/resources/application.yml:195` -- shared `logging.level`; do not touch.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java` -- `@Component`, registered in `SecurityConfig:139` before `UsernamePasswordAuthenticationFilter`. Tenant/user resolved ~L131–133, `TenantContext.setCurrentTenant` L173, `finally` L265 clears `TenantContext` + `SecurityContextHolder`. Put MDC `tenantId`/`userId` next to `TenantContext.setCurrentTenant` (after revocation check, so rejected tokens never seed MDC) and remove them in the same `finally`.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/RateLimitFilter.java` -- existing `@Order(2)` filter, pattern reference for a `@Component` servlet filter.
- Reference only (do not merge): `git show refractoring:keevo/backend/src/main/java/com/keevo/shared/infrastructure/observability/CorrelationIdFilter.java` and `…/test/…/observability/CorrelationIdFilterTest.java` — reuse the sanitize logic and tests; DROP its "defer to existing MDC traceId" branch (11.2 will redesign it; on 11.1 it only reads stale values).
- `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java` -- existing unit tests; extend for MDC.

## Tasks & Acceptance

**Execution:**
- [x] `keevo/backend/src/main/java/com/keevo/shared/infrastructure/observability/CorrelationIdFilter.java` -- new `@Component @Order(Ordered.HIGHEST_PRECEDENCE)` `OncePerRequestFilter`: resolve/sanitize/generate id, `MDC.put("traceId")`, set `X-Request-Id` response header before `doFilter`, `MDC.remove` in `finally` -- correlation exists even for 401s produced later in the chain.
- [x] `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/JwtAuthFilter.java` -- put `tenantId` (when non-null) and `userId` in MDC alongside `TenantContext.setCurrentTenant`; remove both in the existing `finally` -- per-tenant log correlation.
- [x] `keevo/backend/src/main/resources/application-prod.yml`, `application-staging.yml` -- structured logstash console format + renames -- JSON in deployed environments only.
- [x] `keevo/backend/src/test/java/com/keevo/shared/infrastructure/observability/CorrelationIdFilterTest.java` -- unit tests for the matrix rows (generated, supplied, sanitized/truncated, cleanup after chain throws).
- [x] `keevo/backend/src/test/java/com/keevo/shared/infrastructure/security/JwtAuthFilterTest.java` -- MDC populated during `doFilter` for a valid token, absent for login_pending/revoked/invalid tokens, cleared after return.
- [x] `keevo/backend/src/test/java/com/keevo/shared/infrastructure/observability/StructuredLoggingFormatTest.java` -- encode a real event with Spring's logstash structured formatter configured from the same rename properties (or boot a minimal context with `prod`-like properties) and assert JSON keys `timestamp`, `level`, `logger`, `message`, `traceId`, `tenantId`.

**Acceptance Criteria:**
- Given the backend runs with profile `prod` or `staging`, when any request is served, then each console log line parses as JSON with the keys above and no `Authorization` header or token value appears.
- Given profile `dev` or `test`, when the app logs, then output is the unchanged human-readable pattern.
- Given the full `com.keevo.shared` test package, when run, then it passes with no new failures besides the pre-existing `ProdSecretsFailFastContextTest` (owned by v1s-12-13).

## Implementation Notes

- `CorrelationIdFilter` also falls back to a generated UUID when the header is non-blank but becomes blank once control chars are stripped (e.g. `"\r\n"`), and always overwrites a stale MDC `traceId`.
- `StructuredLoggingFormatTest` loads the real `application.yml` + `application-<profile>.yml` from the classpath and encodes an event through Spring's `StructuredLogEncoder`, so it fails if the yml drifts.
- JwtAuthFilter "never seeds MDC" tests use `Mockito.mockStatic(MDC, CALLS_REAL_METHODS)` to verify no `MDC.put` occurs (post-call absence alone would not prove it, since `finally` clears).

## Spec Change Log

- 2026-10-28 (dev): rename key for `@timestamp` must be bracketed (`"[@timestamp]": timestamp`). Without brackets Spring Boot relaxed binding normalises the map key to `timestamp`, the rename becomes a silent no-op and lines keep `@timestamp` — caught by `StructuredLoggingFormatTest`. `logger_name` needs no brackets.

## Review Triage Log

Reviewed together with 11-2 (single batched diff) — full triage table in `spec-11-2-tracing-otel-metriques-prometheus.md` § Review Triage Log (rows B8, B13a, B14, E4, E7 touch 11-1 code; B12/E8 async MDC → 12-4).

## Design Notes

Native structured logging (Boot ≥ 3.4) was chosen over the `refractoring` branch's `logback-spring.xml` + `LogstashEncoder`: a custom `logback-spring.xml` replaces Spring's default config, which would silently drop `application-dev.yml`'s `logging.file.name` and add a dependency. The logstash format already writes every MDC entry as a root-level field.

```yaml
logging:
  structured:
    format:
      console: logstash
    json:
      rename:
        logger_name: logger
        "[@timestamp]": timestamp   # brackets mandatory, see Spec Change Log
```

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='CorrelationIdFilterTest,JwtAuthFilter*Test,StructuredLoggingFormatTest' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: all green
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green except pre-existing `ProdSecretsFailFastContextTest`

**Manual checks (if no CLI):**
- Run the jar with `SPRING_PROFILES_ACTIVE=staging`-like local overrides if feasible and confirm one JSON object per line on stdout.
