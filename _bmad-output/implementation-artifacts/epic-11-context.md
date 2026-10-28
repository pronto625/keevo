# Epic 11 Context: Stabilisation du Socle & Dette (Phase 1 allégée)

<!-- Compiled from planning artifacts. Edit freely. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Serve the real tenants (fewer than 100) reliably and make incidents diagnosable before the modular refactor (Epics 12–16) builds on top of the platform. The epic adds basic observability: structured JSON logs, OpenTelemetry traces, and technical plus business metrics, all correlated by tenant and trace. It also fixes the security, performance and correctness debt the audits agreed on, enforces audit immutability in the database, and guarantees session revocation within 5 minutes. Redis, HA and canary releases are deliberately deferred because they are not needed below about 500 tenants. Status as of the V1 freeze (2026-10-28, tag `v2.0.1`, branch `release/2.0.x`): stories 11.3 to 11.6 are done on `develop`. Stories 11.1 and 11.2 were built only on the old, diverged `refractoring` branch (starting at commit `d8d61d0d`, 346 commits behind `develop`). Their code is not on `develop` and must be ported there.

## Stories

- Story 11.1: Structured JSON logs correlated by `tenantId`/`traceId` (to port)
- Story 11.2: OpenTelemetry tracing and Micrometer/Prometheus metrics, including business metrics (to port)
- Story 11.3: Append-only audit trail at the database level (done)
- Story 11.4: Access-control fixes, RBAC and exposure (done)
- Story 11.5: Performance and correctness fixes, N+1 and index bug (done)
- Story 11.6: Session revocation in under 5 minutes without Redis (done)

## Requirements & Constraints

- **Logs:** every log line of an authenticated request is JSON and carries `tenantId`, `traceId`, `level`, `logger` and `message`, plus `userId` when known. Logs never contain secrets such as passwords or tokens. Production runs at INFO, and the root logger stays at WARN.
- **Metrics:** `/actuator/prometheus` exposes HTTP, JVM and Hikari metrics plus business metrics, for example `keevo_sales_total{tenant}`, `keevo_sync_lag_seconds` and `keevo_errors_total{domainCode}`.
- **Traces:** traces can be exported over OTLP and share the same `traceId` as the logs.
- **Audit immutability:** `audit_log` rejects UPDATE and DELETE, even through direct SQL by the application role. INSERT keeps working.
- **Access control:**
  - Cancelling a draft sale is OWNER-only. An EMPLOYEE gets 403 but can still validate a draft.
  - Deleting a device token checks that the caller owns it.
  - Production CORS uses an env allow-list, with no hardcoded `localhost:3000`.
  - Schema names are validated against `^kv_[a-z0-9]+$` before they are interpolated into SQL.
- **Performance and correctness:**
  - The admin tenant list does not look up `schema_name` once per row.
  - The draft-sale list fetches its items in a single query.
  - The `day_closure` sync payload maps `totalTransactions` to the right column.
- **Revocation:** a deactivated employee's next request is rejected with 401 within 5 minutes.
- **Porting rule:** the port must not undo anything `develop` delivered after the branches split. `refractoring` lacks that work, so cherry-pick or re-apply only the observability pieces and never merge the branch wholesale.
- **Service continuity:** production tenants keep working throughout. OTLP export must be off by default, so the app boots and runs without a collector.
- **Testing:** run Maven tests one package at a time, never the full suite. Test SQL and Flyway against real PostgreSQL (Testcontainers).
- **Deferred items:** each one goes to the story that owns it, never to `deferred-work.md`.

## Technical Decisions

- **Stack:** Spring Boot 3.5, Java 21, PostgreSQL with one schema per tenant (`kv_xxxxxx`). `JwtAuthFilter` resolves the tenant into `TenantContext`.
- **11.1 on `refractoring`:**
  - Uses `logstash-logback-encoder` (8.1) and a `logback-spring.xml`.
  - A `CorrelationIdFilter` (in `shared/infrastructure/observability`) handles an `X-Request-Id` correlation ID.
  - `JwtAuthFilter` fills the MDC after it resolves the tenant and clears it in `finally`.
  - `GlobalExceptionHandler` was touched for error logging.
- **11.2 on `refractoring`:**
  - Dependencies: `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel` (which puts `traceId`/`spanId` into the MDC) and `opentelemetry-exporter-otlp`.
  - The management exposure list adds `prometheus`, and every meter gets the common tag `application=keevo`.
  - Configuration comes from env: `KEEVO_TRACING_SAMPLING_PROBABILITY` (default 0.1), `KEEVO_OTLP_EXPORT_ENABLED` (default false) and `KEEVO_OTLP_ENDPOINT`. Staging samples at 1.0, and both prod and staging enable export.
  - Business counters live in `BusinessMetricsListener` and `SyncLagMetrics`, which register through `MeterRegistry` in the existing event listeners.
- **Existing Micrometer use on `develop`:** the admin runtime-metrics adapter (`MicrometerRuntimeMetricsAdapter`) already uses Micrometer. Keep it working and keep the two meter registries consistent.
- **Not decided yet:** whether the Grafana/Prometheus stack is self-hosted (light) or Grafana Cloud. The planning docs leave this to budget, so do not assume either.
- **Already in place on `develop`, for reference:**
  - An append-only trigger plus REVOKE in every schema that holds `audit_log`.
  - DB-backed token revocation behind `TokenRevocationPort`, checked by `JwtAuthFilter` through a short cache.
  - A `TenantSchema.validate` helper.
- **Schema changes:** use Flyway versioned migrations only, with `ddl-auto=validate`. A change to a tenant table also needs the tenant provisioning DDL.
- **API errors:** the error body is `{ error, code, domainCode, details }`, and the `domainCode` metric tag should reuse it. A revoked or invalid token gets 401, and missing authorization gets 403.

## Cross-Story Dependencies

- 11.2 shares its `traceId` with the logs from 11.1, through the OTel bridge into the MDC. Port 11.1 first or port both together, so the app never runs two competing correlation mechanisms. The `X-Request-Id` filter and the tracing `traceId` must agree on one correlation key in the logs.
- The business metric `keevo_errors_total{domainCode}` depends on the domain error codes that the global exception handler emits.
- `keevo_sync_lag_seconds` relates to the sync engine (Epic 5). Epic 12.7 later makes sync module-aware, so keep the metric generic.
- Epic 12 adds per-module observability (`spring-modulith-observability`) on top of 11.2. Epic 10 (Flyway versioning, secrets fail-fast, staging) is a prerequisite: new OTLP and env variables must follow its externalised-secrets approach.
- 11.6 builds on the existing employee-deactivation flow. 11.3's immutability must survive later audit enrichment.
