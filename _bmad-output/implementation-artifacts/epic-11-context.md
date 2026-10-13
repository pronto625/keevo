# Epic 11 Context: Stabilisation du Socle & Dette (Phase 1 allégée)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Serve the real tenants (fewer than 100) reliably and make incidents diagnosable. The epic adds basic observability (structured JSON logs, traces, technical and business metrics, all correlated by tenant and trace) and fixes the security, performance and correctness debt that the audits agreed on. It also enforces audit immutability and the session-revocation delay. Redis, HA and canary are deliberately deferred: they are not needed below about 500 tenants. This stabilisation must come before the modular refactor (Epics 12–16), so that later work does not build on an unobservable, leaky base.

## Stories

- Story 11.1: Structured JSON logs correlated by `tenantId`/`traceId`
- Story 11.2: OpenTelemetry tracing and Micrometer/Prometheus metrics (including business metrics)
- Story 11.3: Append-only audit trail at the database level
- Story 11.4: Access-control fixes (RBAC and exposure)
- Story 11.5: Performance and correctness fixes (N+1, index bug)
- Story 11.6: Session revocation in under 5 minutes without Redis

## Requirements & Constraints

- **Observability:** every log line from an authenticated request is JSON and carries `tenantId`, `traceId`, `level`, `logger` and `message`. Logs never contain secrets (passwords, tokens). Production level is INFO, and the root logger stays at WARN. `/actuator/prometheus` exposes HTTP, JVM and Hikari metrics plus business metrics (for example sales total per tenant, sync lag and errors by domain code). OTel traces can be exported over OTLP and share the `traceId` with the logs.
- **Audit immutability:** `audit_log` must reject UPDATE and DELETE, even through direct SQL by the application role. INSERT keeps working.
- **Access control:** cancelling a pending (draft) sale is OWNER-only; an EMPLOYEE gets 403, but validation is still allowed. Deleting a device token checks that the caller owns it (user ID from the JWT). Production CORS uses an allow-list taken from env (`DASHBOARD_ORIGIN`), with no hardcoded `localhost:3000`. Every schema name interpolated into `JdbcTemplate` SQL is first validated against `^kv_[a-z0-9]+$`.
- **Performance and correctness:** the admin tenant list must not run one `schema_name` lookup per row. The pending-sale list fetches its items in one query, without lazy N+1. The `day_closure` sync payload maps `totalTransactions` to the correct column.
- **Session revocation:** a deactivated employee's next request is rejected with 401 within 5 minutes or less.
- **Current state on `v1s/stabilisation` (the working branch):**
  - 11-1 and 11-2: code exists only on the `refractoring` branch, which is out of scope. Nothing here has logback JSON, MDC, OTel or Prometheus.
  - 11-3: done. 11-6: done (delivered via v1s-12-2, revocation cache TTL 300 s, which still meets the under-5-minutes rule).
  - 11-4: partially done. Pending-sale cancel is OWNER-only, device-token ownership is in, and `TenantSchema.validate` exists. Remaining: confirm the CORS prod allow-list and complete coverage.
  - 11-5: the day-closure index bug is already fixed. The remaining scope is the admin tenant-list N+1 (`AdminTenantService` cross-schema enrichment) and the pending-sales N+1.
- **Testing:** this machine has 15 GB of RAM. Run Maven and Flutter tests one package or folder at a time, never the full suite at once.
- **Deferred items:** none go to `deferred-work.md`. Each one goes to the story that owns it.

## Technical Decisions

- **Stack:** Spring Boot 3.5 / Java 21, JPA, Flyway, PostgreSQL, schema-per-tenant (`kv_xxxxxx`), tenant resolved in `JwtAuthFilter` via `TenantContext` (ThreadLocal).
- **Logs:** use the logstash-logback-encoder. MDC is filled in `JwtAuthFilter` after the tenant is resolved and cleared in `finally`.
- **Metrics and tracing:** `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`. Business counters go through `MeterRegistry` in the existing event listeners. Per-module observability through Spring Modulith comes later, in Epic 12.
- **Audit append-only:** a Flyway migration that revokes UPDATE and DELETE from the application role and adds a `BEFORE UPDATE OR DELETE` trigger that raises an exception. The same rule applies in every schema that holds `audit_log`, including the tenant provisioning DDL. Prove it with a Testcontainers test.
- **Revocation:** no Redis. Use a DB-backed source (revoked JTIs or a `tokens_valid_after` timestamp) checked by `JwtAuthFilter` through a short cache. Keep it behind a `TokenRevocationPort` interface so it can move to Redis later, and purge expired entries with a job.
- **Schema changes:** all go through Flyway versioned migrations, and Hibernate validates the schema at boot (`ddl-auto=validate`). A tenant-table change also needs the tenant provisioning/sync DDL.
- **JdbcTemplate rule:** SQL outside Hibernate that touches a tenant table must qualify it with the validated tenant schema name.
- **Errors:** the API returns `{ error, code, domainCode, details }`. Validation errors are HTTP 422 `VALIDATION_ERROR`. Authorization failures are 403. A revoked or invalid token gets 401.
- **SQL correctness:** test Flyway and SQL against a real PostgreSQL (Testcontainers), not with mocks.
- **No technical data in the UI:** screens, exports and audit details show French labels only, never UUIDs or raw codes.

## Cross-Story Dependencies

- 11.2 shares its `traceId` with 11.1's logs. Doing 11.1 first, or both together, avoids having two correlation mechanisms.
- 11.6 depends on the existing employee-deactivation flow (Epic 3 / 8-3, `DeactivateEmployeeService`). 11.6 only adds the delay guarantee.
- 11.3 keeps the audit immutability required by the enriched audit (Epic 18.6 adds columns, but immutability does not change).
- Epic 12 enables Spring Modulith observability on top of 11.2. Epic 10 (Flyway versioning, secrets fail-fast) is a prerequisite for the 11.3 migrations.
