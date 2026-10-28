# Modular refactor — inconsistencies & improvements log

Started 2026-10-28 at the V1 freeze (`v2.0.1` / `release/2.0.x`). Running log kept during the autonomous
`bmad-build-auto` run on `refonte/modulaire`. Each entry: what, where, impact, action taken / owner.
Status: `fixed` (done in the run), `routed` (assigned to a story), `open` (needs a decision or later look).

## Inconsistencies

| # | Date | What | Where | Impact | Status / action |
|---|------|------|-------|--------|-----------------|
| I1 | 2026-10-28 | The two authority docs of the refactor (`plan-refonte-modulaire.md`, `spec-socle-modulaire.md`) and the audits they consolidate existed only on the diverged `refractoring` branch, not on `develop`. Epic contexts compiled before this date ignored them. | repo root | Plans built without the target architecture | fixed — restored on `refonte/modulaire`; `epic-12-context.md` recompiled from them |
| I2 | 2026-10-28 | `sprint-status.yaml` marked 11-1 `done` / 11-2 `review` although their code never reached `develop` (status read on `refractoring`). | sprint-status | False "done" | fixed — set `in-progress`, ported in specs 11-1 / 11-2 |
| I3 | 2026-10-28 | Story 12.1 lists 10 packages and a `core` module; the code has 12 modules (`feedback` missing from the epic) and the shared module is `shared`. | epics-refonte-modulaire.md §12.1 | Wrong module list in AC | fixed in spec-12-1 (real list); `core` extraction stays Phase 2 (12.2d/12.5) |
| I4 | 2026-10-28 | Story 12.1 asks for docs in `target/modulith-docs`; the Documenter default and CI artifact are `target/spring-modulith-docs`. | §12.1 vs backend-ci.yml | AC unreachable as written | fixed — spec-12-1 keeps the CI path |
| I5 | 2026-10-28 | Plan/epics cite "1429 backend tests" as the strangler safety net; the suite now has ~3,300 `@Test`. | plan §8 Phase 2, epics §12.2 | Outdated metric only | open — informational |
| I6 | 2026-10-28 | Epics frontmatter `scope` says "Epics 10-16" but Epic 17 exists in the same doc and in sprint-status. | epics-refonte-modulaire.md frontmatter | Cosmetic | open |
| I7 | 2026-10-28 | `ProdSecretsFailFastContextTest` fails in CI since 2026-10-18 but passes locally (596-test `com.keevo.shared` run). Likely environment-dependent (env vars / file presence on the runner). | backend CI | Red CI hides regressions | routed → v1s-12-13 (reopened) |
| I8 | 2026-10-28 | Spec 11-1 YAML rename `"@timestamp": timestamp` is a no-op: relaxed binding strips `@`; must be `"[@timestamp]"`. | spec-11-1 Design Notes | Wrong field name in prod logs | fixed by implementation (test caught it) |
| I9 | 2026-10-28 | 10-6 review noted ArchUnit "pinned 1.3.0" skew; pom still pins `archunit-junit5` 1.3.0 explicitly. | keevo/backend/pom.xml | Version skew risk | routed → 12-6 (already listed) |

## Improvements / research

| # | Date | Proposal | Source | Status |
|---|------|----------|--------|--------|
| A1 | 2026-10-28 | Use Spring Boot 3.5 native structured logging (`logging.structured.*`) instead of `logstash-logback-encoder` + `logback-spring.xml` (keeps dev file logging, no extra dependency). | Spring Boot 3.5 metadata | applied in 11-1 |
| A2 | 2026-10-28 | **Schema-per-tenant trap for the event registry (Story 12.4):** the JDBC registry table is not schema-qualified unless `spring.modulith.events.jdbc.schema` is set. Keevo's connection provider switches `search_path` per tenant, so an unqualified `event_publication` would resolve inside the tenant schema (or fail). Set `spring.modulith.events.jdbc.schema=public` and create the table via a Flyway `public` migration (`schema-initialization.enabled=false`, default). | [Spring Modulith 1.4 events](https://docs.spring.io/spring-modulith/reference/1.4/events.html), [1.4 appendix](https://docs.spring.io/spring-modulith/reference/1.4/appendix.html) | routed → 12-4 (add to its spec) |
| A3 | 2026-10-28 | Bump Spring Modulith BOM 1.4.0 → latest 1.4.x patch (1.4.x is the line for Boot 3.5; 2.x targets Boot 4 — do not jump). | [Modulith 2.0 appendix (compat table)](https://docs.spring.io/spring-modulith/reference/2.0/appendix.html), [1.4.12 API](https://docs.spring.io/spring-modulith/docs/1.4.12/api/) | routed → 12-3 (when adding `spring-modulith-starter-jdbc`) |
| A4 | 2026-10-28 | `spring.modulith.events.completion-mode`: spec-socle §7.2 mentions `UPDATE`/`ARCHIVE`; 1.4 also offers `DELETE`. With < 100 tenants keep `UPDATE` + periodic purge of completed rows, or `ARCHIVE` for audit. | 1.4 appendix | routed → 12-3 |
| A6 | 2026-10-28 | Execution order for Epic 12: run 12.5 (realign `catalog/stock` → `inventory`, transfers module, `crm` out of `catalog/contact`, drop `payment`/`warehouse` placeholders) right after 12.1 and **before** 12.2a/12.2b — otherwise the catalog/inventory APIs are published then moved. Batches: [12.1] → [12.5] → [12.2a+12.2b] → [12.2c+12.2d] → [12.3+12.4] → [12.2e+12.6] → 12.7 after 13.2. | epic-12-context § Cross-Story Dependencies | applied to the run plan |
| A7 | 2026-10-28 | Inbound trace propagation (`traceparent`, B3) disabled (`management.tracing.propagation.consume: ""`): public clients could otherwise force sampling and pick the trace id used in logs. Re-enable selectively if Keevo ever calls itself across services. | review 11-1/11-2 E1 | applied in 11-2 |
| A8 | 2026-10-28 | With the OTel bridge on the classpath, Boot adds `[traceId-spanId]` to the dev/test plain console pattern — a small visible change to dev logs (11-1 wanted them unchanged). Accepted. | 11-2 implementation | accepted |
| A5 | 2026-10-28 | 11-1 MDC key `traceId` will collide with Micrometer Tracing's own `traceId` MDC key once the OTel bridge is on classpath. | 11-1 / 11-2 | handled in spec-11-2 (span trace id wins, client id → `requestId`) |
