---
title: '11.2 — OpenTelemetry tracing + Micrometer/Prometheus metrics, technical and business (port to develop)'
type: 'feature'
created: '2026-10-28'
status: 'done'
baseline_commit: '472e421f1dc0f4c833ce7551b285b6eb62a1028c'  # shared with 11-1 (batched review; 11-1 changes are uncommitted on top)
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-11-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-11-1-logs-json-structures-mdc.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 11.2 exists only on the diverged `refractoring` branch; `develop` exposes no Prometheus scrape endpoint, no traces and no business metrics, so latency, errors and business health cannot be measured (MOD-NFR4).

**Approach:** Re-implement on `develop` (built on 11.1, never merging `refractoring`): Prometheus registry + Micrometer Tracing OTel bridge with OTLP export off by default; business counters/gauge; a scrape endpoint protected by a static token; log `traceId` = OTel trace id whenever a span is current.

**Decisions (autonomous run, user delegated all choices 2026-10-28):**
- `/actuator/prometheus` is reachable only with `Authorization: Bearer ${KEEVO_PROMETHEUS_TOKEN}` (constant-time compare). Token unset → endpoint answers 404. Prod is publicly routed by Traefik, so no anonymous scrape.
- `keevo_sync_lag_seconds` = seconds since the most recent `public.user_sync_state.last_push_at` (platform-wide sync outage detector). The refractoring definition (oldest push across all devices) grows forever with abandoned devices.
- Business counters are incremented after commit only.
- Grafana/Prometheus hosting stays out of scope (ops, Story 10-8 owner).

## Boundaries & Constraints

**Always:**
- App boots and serves with no collector: `management.otlp.tracing.export.enabled: ${KEEVO_OTLP_EXPORT_ENABLED:false}`, endpoint `${KEEVO_OTLP_ENDPOINT:http://localhost:4318/v1/traces}`, `management.tracing.sampling.probability: ${KEEVO_TRACING_SAMPLING_PROBABILITY:0.1}`.
- Existing `MicrometerRuntimeMetricsAdapter` (Story 9-5 health sampler) keeps reading `http.server.requests` percentiles and Hikari gauges unchanged.
- `X-Request-Id` contract from 11.1 kept: client-supplied id echoed back and logged as MDC `requestId`; when absent, response echoes the effective `traceId`.
- Metric tag `tenant` = tenant schema name (< 100 tenants, bounded); `domainCode` tag = `DomainException.getDomainCode()` (bounded enum set).

**Never:**
- No anonymous exposure of `/actuator/prometheus` or `/actuator/metrics`; `/actuator/health` stays public as today.
- No `spring-modulith-observability` (Epic 12), no Grafana/Prometheus deployment, no docker-compose service.
- No per-user, per-sale or per-product metric tags.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Scrape OK | token set, correct Bearer | 200, text exposition incl. `http_server_requests`, `jvm_`, `hikaricp_`, `keevo_sales_total` | N/A |
| Scrape wrong/missing token | token set, bad or no header | 401, no metrics body | never parsed as JWT |
| Scrape disabled | `KEEVO_PROMETHEUS_TOKEN` unset/blank | 404 | N/A |
| Sale committed | `SaleCompletedEvent` then commit | `keevo_sales_total{tenant}` +1, `keevo_sales_revenue_total{tenant}` + totalAmount | N/A |
| Sale rolled back | event published, tx rolls back | counters unchanged | N/A |
| Domain error | handler maps a `DomainException` | `keevo_errors_total{domainCode}` +1 | null code → `UNKNOWN` |
| No pushes yet | `user_sync_state` empty | gauge 0 | query failure → keep last value, WARN log |
| Traced request | span current | log `traceId` = span trace id; header echo per contract | no span → 11.1 fallback id |

</frozen-after-approval>

## Code Map

- `keevo/backend/pom.xml` -- add `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp` (Boot-managed versions, no explicit `<version>`).
- `keevo/backend/src/main/resources/application.yml:76` -- `management.endpoints.web.exposure.include: health,info,metrics` → add `prometheus`; add tracing/otlp keys above. Keep the `metrics.distribution.percentiles` block.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/security/SecurityConfig.java:56` -- `PUBLIC_PATHS` (read by `JwtAuthFilter` too). Add `/actuator/prometheus` there so JWT parsing is skipped; protection is the new token filter.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/observability/CorrelationIdFilter.java` -- from 11.1. Must run after Boot's `ServerHttpObservationFilter` so the span is current; prefer `Tracer` (`ObjectProvider<Tracer>`) `currentSpan().context().traceId()` over the generated id; MDC `requestId` for the client id.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/web/GlobalExceptionHandler.java:125` -- `handleDomainException`: increment `keevo_errors_total`.
- `keevo/backend/src/main/java/com/keevo/commerce/sale/domain/model/SaleCompletedEvent.java` -- published once per completed sale by `RecordSaleService:120`, `ValidateSaleService:141`, `SaleValidationCascadeService:122` (pending sales publish `SalePendingValidationEvent` instead — no double count).
- `keevo/backend/src/main/java/com/keevo/admin/platform/adapter/out/metrics/MicrometerRuntimeMetricsAdapter.java` -- do not change; regression-test it still finds its meters.
- Reference only: `git show refractoring:keevo/backend/src/main/java/com/keevo/shared/infrastructure/observability/{BusinessMetricsListener,SyncLagMetrics}.java` and `…/test/…/observability/PrometheusEndpointTest.java`.

## Tasks & Acceptance

**Execution:**
- [x] `keevo/backend/pom.xml`, `application.yml` -- dependencies + management/tracing config.
- [x] `…/shared/infrastructure/observability/PrometheusScrapeAuthFilter.java` -- `OncePerRequestFilter` limited to `/actuator/prometheus`: 404 when token blank, 401 on mismatch (`MessageDigest.isEqual`), else continue; register so it runs inside the security chain before authorization.
- [x] `SecurityConfig.java` -- add path to `PUBLIC_PATHS`, wire the filter.
- [x] `…/observability/BusinessMetricsListener.java` -- `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` on `SaleCompletedEvent`.
- [x] `…/observability/SyncLagMetrics.java` -- gauge refreshed every 60 s per Decisions.
- [x] `GlobalExceptionHandler.java` -- error counter.
- [x] `CorrelationIdFilter.java` -- ordering + trace id preference + `requestId`; update its 11.1 tests.
- [x] Tests under `keevo/backend/src/test/java/com/keevo/shared/infrastructure/observability/` -- one per matrix row (filter unit tests with `MockHttpServletRequest`; listener with `SimpleMeterRegistry`; a `@SpringBootTest` slice or MockMvc test for scrape 200/401/404).

**Acceptance Criteria:**
- Given the app starts with default env (no collector), when it boots, then no OTLP connection error is logged and `/actuator/health` is UP.
- Given the 9-5 health sampler, when it runs, then `MicrometerRuntimeMetricsAdapter` tests pass unchanged.
- Given packages `com.keevo.shared` and `com.keevo.admin`, when tests run, then no new failures besides pre-existing `ProdSecretsFailFastContextTest` (v1s-12-13).

## Implementation Notes

- Meters use Micrometer dot names (`keevo.sales`, `keevo.sales.revenue`, `keevo.errors`, `keevo.sync.lag` + base unit `seconds`); the Prometheus registry renders them as `keevo_sales_total`, `keevo_sales_revenue_total`, `keevo_errors_total`, `keevo_sync_lag_seconds` (asserted on a real `PrometheusMeterRegistry` scrape).
- Token property: `keevo.observability.prometheus-token: ${KEEVO_PROMETHEUS_TOKEN:}` (application.yml), documented in `.env.example`. `PrometheusScrapeAuthFilter` is not a `@Component` (no duplicate servlet registration); `SecurityConfig` builds it and adds it before `UsernamePasswordAuthenticationFilter` ahead of `JwtAuthFilter` (same anchor, insertion order). A trailing slash is guarded too.
- `CorrelationIdFilter` order = `HIGHEST_PRECEDENCE + 2` (Boot 3.5 registers `ServerHttpObservationFilter` at `HIGHEST_PRECEDENCE + 1`, checked in the jar). A no-op span or a blank trace id falls back to the 11.1 id. Stale `requestId` is cleared when no client id is sent.
- `GlobalExceptionHandler` keeps its no-arg constructor (≈30 MockMvc tests use `new GlobalExceptionHandler()` → counter skipped); Spring uses the `@Autowired` `ObjectProvider<MeterRegistry>` constructor. A counter failure never alters the response.
- `tenant` tag falls back to `unknown` when the event carries no tenant (Micrometer rejects null tag values).
- Side effect of tracing on the classpath: Boot adds its correlation block `[traceId-spanId]` to the human-readable dev/test console pattern (JSON prod/staging unchanged apart from the `traceId`/`spanId` keys the bridge puts in the MDC).
- `Span.isNoop()` is NOT used to reject a span: the OTel bridge returns `!isRecording()`, i.e. every unsampled span (90 % at 0.1) looks no-op while carrying a valid trace id (the one the bridge writes to MDC). Only blank/all-zero ids fall back. Caught by the full-context test; guarded by a `@RepeatedTest(10)`.
- `SchedulerLockCoverageTest` classifies `SyncLagMetrics` as a per-instance (unlocked) sweep: each instance refreshes its own registry's gauge.
- Review fixes (2026-10-28): every `GlobalExceptionHandler` handler counts the `domainCode` it returns (incl. `INTERNAL_ERROR` 500); `BusinessMetricsListener` swallows metric failures (WARN) and adds revenue only when `totalAmount > 0` (gross, cancellations not subtracted); `CorrelationIdFilter` restores the previous MDC `traceId`/`requestId` (outer observation scope) instead of removing them, and keeps printable ASCII only (`[\x21-\x7E]`); scrape 401 carries `WWW-Authenticate: Bearer`.
- Inbound propagation is not consumed: `management.tracing.propagation.consume: ""` (Boot 3.5 binds it to an empty list → the composite propagator has no extractor), `produce: W3C`. A public client cannot choose the trace id or force sampling. The full-context test sends `traceparent`/`b3`/`X-B3-*` and asserts a server-generated id; it fails with the default consume list (checked).
- No prod/staging yml change: export, endpoint and sampling stay env-driven (`KEEVO_OTLP_EXPORT_ENABLED`, `KEEVO_OTLP_ENDPOINT`, `KEEVO_TRACING_SAMPLING_PROBABILITY`) per the Boundaries; the epic's "staging samples at 1.0 / export on" is an ops env setting.

## Spec Change Log

## Review Triage Log

Batched review (11-1 + 11-2, one diff, 2026-10-28). Layers: blind-hunter (B), edge-case-hunter (E), verification-gap (V).

| # | Finding | Verdict | Evidence | Route |
|---|---------|---------|----------|-------|
| V1 | errors metric never verified on the Spring-built handler bean | medium | only unit test builds the handler by hand; scrape IT never asserts `keevo_errors_total` | patch |
| B1 | `BusinessMetricsListener` lacks try/catch, can fail the sale call stack | low | `fallbackExecution=true` runs synchronously in publisher stack; fix is a direct guard | patch |
| B2 | revenue counter never decreases on cancellation | low | counter semantics = gross completed amount; description clarified | patch |
| B3/E3 | `keevo_errors_total` counts only `DomainException`, misses 409/400/403/500 | medium | other `@ExceptionHandler`s at GlobalExceptionHandler:159-262 do not count | patch |
| B4 | `domainCode` tag unbounded via String constructor | false | all call sites pass literal codes (`PRODUCT_NOT_FOUND`, `INVALID_AMOUNT`, ErrorCode refs); no user input reaches the code | reject |
| B5 | scrape filter hardcodes path vs base-path/management port | false | no `management.endpoints.web.base-path` nor `management.server.port` configured | reject |
| B6a | 401 lacks `WWW-Authenticate: Bearer` | low | direct header addition | patch |
| B6b | token length leak / no rate limit / min length | low | `isEqual` length leak negligible for a random token; not met in everyday use, fix adds branches | reject |
| B7 | blank token silent in prod; no deploy wiring (env, scrape job) | medium | `.env.example` only; server `~/keevo/.env` and Prometheus job are ops-owned | defer → 10-8 |
| B8 | filter removes `traceId` while observation scope open | low | `finally` removes key the bridge owns; restore previous value | patch |
| B9 | server trace id not returned when client sends `X-Request-Id` | false | frozen contract (spec-11-2 Always: client id echoed) — fix would edit intent | reject |
| B10 | tracing-off fallback lets client set `traceId` | low | tracing bridge always on classpath; with E1 fix trace id is server-generated | reject |
| B11/E2 | sync-lag gauge freezes on failure / 0 when empty | false | frozen decision (matrix: "query failure → keep last value, WARN"; "No pushes yet → 0") | reject |
| B12/E8 | MDC not propagated to `@Async` executors | medium | spec Never excludes async propagation; real gap for notification executor logs | defer → 12-4 (context-propagating TaskDecorator alongside TenantEventContextDecorator) |
| B13a | `noSpanFallsBackToClientId` misses traceId assert | low | display name vs assertions | patch |
| B13b | other test nits (requestId in JSON, dev file assert, IT 404, scheduled race) | false/low | 404 covered by `PrometheusScrapeSecurityChainTest`; dev file assert encodes spec-11-1 Always; race maybe-false low | reject |
| B14 | FQN usage / constant placement / mid-class `@AfterEach` | low | direct corrections | patch |
| B14b | public no-arg ctor only for tests | low | ~30 slice tests use it; removal is wider than a correction | reject |
| E1 | inbound `traceparent`/B3 consumed from public clients (force sampling, choose trace id) | medium | `management.tracing.propagation.consume` defaults W3C,B3,B3_MULTI | patch |
| E4 | sanitizer lets C1/U+2028/non-Latin-1 through | low | direct regex correction to printable ASCII | patch |
| E5 | ERROR dispatch runs without correlation keys | low | OncePerRequestFilter skips `/error`; rare, fix adds dispatch handling | reject |
| E6 | negative `totalAmount` diverges between registries | low | direct `> 0` guard | patch |
| E7 | JwtAuthFilter comment overstates "rejected token never reaches MDC" | low | SUSPENDED/employee rejections come after seeding | patch (comment) |
| E9 | `fallbackExecution` would count non-transactional publishers | low | all three publishers are `@Transactional` today | reject |

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.infrastructure.observability.**,GlobalExceptionHandler*Test,SecurityConfig*Test,JwtAuthFilter*Test' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.admin.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q test -Dtest='com.keevo.shared.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: green except `ProdSecretsFailFastContextTest`
