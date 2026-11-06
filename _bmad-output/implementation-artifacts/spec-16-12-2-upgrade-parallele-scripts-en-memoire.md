---
title: '16-12 (2) Parallel boot upgrade safe from the packaged jar — module scripts read once, served to Flyway from memory'
type: 'bugfix'
created: '2026-11-06'
status: 'done'
baseline_revision: '41288a1216c65059d3d1f8f3731c8a9bde1639f9'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-16-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-16-12-conversion-ddl-tenant-modules.md'
warnings: []
deferred:
  - summary: >-
      Each Flyway.configure() may still read META-INF/services from the nested jar via ServiceLoader, concurrently across parallel runs.
    evidence: |-
      Edge-case review claim (medium confidence). Not observed: 4 packaged-jar runs x 362 schemas at parallelism 4 gave 0 failures. Settled by the go-live dry-run on the prod image (16-7), watching for Stream closed / Zip file closed.
    location: >-
      keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/FlywayModuleMigrations.java (migrate)
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** When the backend runs from the packaged Spring Boot jar (the prod Docker image), the parallel boot upgrade (`keevo.tenant-schema.upgrade-parallelism`, default 4) fails a random share of tenants: each Flyway run scans `classpath:db/modules/<id>` and reads the scripts from the same nested jar, and one thread closes the shared jar handle under another (`IOException: Stream closed`, `Zip file closed`; 4 to 297 of 362 tenants per run, and once the app class loader itself broke, so every request answered 500 until restart). This blocks any production deploy of the refonte (16-12 reopened by the 19.11 measurement).

**Approach:** Read every module's migration scripts from the classpath **once per JVM** (per root and module, under a lock), keep their text in memory, and give each Flyway run a custom `ResourceProvider` over those in-memory scripts plus an empty `ClassProvider` for Java migrations, so no Flyway run, `hasMigrations` or `latestVersion` touches the jar after the first read. Then prove it with the real packaged jar at parallelism 4 on the 363-schema clone.

## Boundaries & Constraints

**Always:**
- Checksums stay identical to those Flyway computed from the classpath (existing `flyway_history_<module>` rows in prod and keevo_dev must validate: `validateOnMigrate(true)` stays on). The in-memory text is the exact UTF-8 decoding of the file bytes, and the resource reports the same file name and the same relative path as the classpath scan (`V1__core_baseline.sql`, sub-folders kept).
- Same Flyway behaviour as today: `validateMigrationNaming(true)` still fails a misnamed `.sql` next to valid ones; a folder holding only misnamed files is still a module without migrations; several roots are still merged (test seam with `db/modules-next`); a version shipped in two roots with different text still fails as Flyway fails today (duplicate version).
- The cache is filled under a lock (one classpath read per root and module, even when 4+ threads ask first at the same time); a failed read is never cached (the next call retries) and fails with the same `UncheckedIOException` messages as today.
- Default `keevo.tenant-schema.upgrade-parallelism` stays 4.
- Memory is bounded: the module scripts are small text files (tens of KB in total); no per-tenant copy is made.

**Never:**
- No change to any `db/modules/**` or `db/migration/**` script, to history tables or to the Flyway configuration flags other than the resource and class providers.
- No global lock around `migrate()` (that would serialise the upgrade: the fix must keep parallelism).
- No change to `FoundationScripts` registration semantics (it already reads once; it may reuse the same loader only if that is a pure refactor).
- Never run anything against prod. Live checks use the throwaway clone made by `scripts/load/measure-tenants.sh` only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Parallel boot from jar | packaged jar, 363 schemas, one module history row deleted everywhere, parallelism 4 | every schema re-applies it; 0 failures; app serves requests | none expected |
| Existing history | schema migrated earlier with classpath-read scripts | `migrate()` validates checksums and applies nothing | none expected |
| First concurrent access | 8 threads call `migrate()` for the same module on a cold cache | classpath read exactly once per root for that module | none expected |
| Misnamed script beside valid ones | `V1__ok.sql` + `bad.sql` in a module folder | Flyway fails as today (`ModuleProvisioningException`, naming validation) | provisioning fails loudly |
| Only misnamed files | module folder with `notes.sql` only | `hasMigrations` false, `migrate` no-op | none |
| Unreadable resource | `IOException` while reading | `UncheckedIOException("Cannot scan the migrations of a module")`, not cached | next call re-reads |
| Two roots | `db/modules` V1–V2 + `db/modules-next` V3 | both merged, V3 applied | duplicate version with different text fails as today |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/FlywayModuleMigrations.java` -- the fix. `migrate()` L367-395 builds `Flyway.configure()...locations(classpath:<root>/<id>)` per call (jar scan per tenant). `hasMigrations` L153 and `latestVersion` L175 also call `resources.getResources("classpath*:" + root + "/" + id + "/**/*.sql")` per call. `FLYWAY_SCRIPT` / `VERSIONED_SCRIPT` patterns, `validateModuleId`, `failureCode`, the three constructors (test seam with roots L105, prod with parallelism L95) are reused.
- `keevo/backend/src/main/java/com/keevo/shared/module/FoundationScripts.java` -- reference for the read-once pattern (double-checked volatile, UTF-8 `readAllBytes`, "never cached as a failure"). Not changed functionally.
- `keevo/backend/src/main/java/com/keevo/shared/module/application/ModuleProvisionerService.java` L89-95 and `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaMigrationRunner.java` L18-43 -- the parallel callers (`foundation-upgrade-` threads); read-only context.
- Flyway 11.7.2 API: `FluentConfiguration.resourceProvider(ResourceProvider)` and `.javaMigrationClassProvider(ClassProvider<JavaMigration>)`; when both are set Flyway does not create its classpath `Scanner`. `ResourceProvider#getResource(String name)` and `#getResources(String prefix, String[] suffixes)` (prefix such as `V`, `R`, or a callback name; match on the file name). `LoadableResource` (abstract: `read()` → `Reader`, `getAbsolutePath()`, `getAbsolutePathOnDisk()`, `getFilename()`, `getRelativePath()`).
- Tests: `keevo/backend/src/test/java/com/keevo/shared/module/adapter/out/persistence/FlywayModuleMigrationsTest.java` (unit), `ModuleProvisionerPostgresIntegrationTest`, `FoundationMigrationsPostgresIntegrationTest`, `ModuleLifecyclePostgresIntegrationTest` (Testcontainers, roots `db/modules`, `db/modules-next` under `src/test/resources/db/`).
- Live harness: `keevo/backend/scripts/load/measure-tenants.sh` (`--parallelism N`, packaged jar by default, clone `keevo_load` of keevo_dev, port 4600; refuses if keevo_dev has open connections, so the local backend must be stopped with `fuser -k 4500/tcp`). Report: `_bmad-output/planning-artifacts/research/fusion-mesures/rapport-charge-tenants-2026-11-05.md` (§ upgrade failures).

## Tasks & Acceptance

**Execution:**
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/ModuleScriptCache.java` (new, package-private or public in the same package) -- holds `Map<String root+moduleId, List<ModuleScript>>` filled once under a lock from a `ResourcePatternResolver` (`classpath*:<root>/<id>/**/*.sql`), each script = file name, relative path under `<root>/<id>/`, exact UTF-8 text; failure not cached; exposes the scripts per module across the given roots -- single read of the jar.
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/InMemoryModuleResourceProvider.java` (new) -- `ResourceProvider` over a module's cached scripts (+ a `LoadableResource` subclass returning a `StringReader`), `getResources(prefix, suffixes)` filters on file name prefix/suffix like Flyway's scanner; plus an empty `ClassProvider<JavaMigration>` -- Flyway never scans the classpath.
- `FlywayModuleMigrations.java` -- `migrate()` uses `.resourceProvider(...)` and `.javaMigrationClassProvider(...)` from the cache (drop `.locations(...)` only if Flyway requires nothing else from it; keep naming validation working); `hasMigrations` and `latestVersion` read the cache -- no per-call classpath scan.
- `keevo/backend/src/test/java/com/keevo/shared/module/adapter/out/persistence/ModuleScriptCacheTest.java` (new) -- counting resolver: N concurrent first calls → one `getResources` per root and module; failure not cached; misnamed-only folder; two roots merged; UTF-8 text with accents exact.
- Testcontainers test (extend `ModuleProvisionerPostgresIntegrationTest` or a new `*PostgresIntegrationTest`) -- (a) a schema migrated by a classpath-configured Flyway (the pre-fix configuration) is re-run through the new provider: 0 migrations, checksum validation passes; (b) misnamed script beside valid ones still fails; (c) `db/modules-next` V3 still applied; (d) 4 threads × several schemas migrate in parallel without error.
- `_bmad-output/planning-artifacts/research/fusion-mesures/rapport-charge-tenants-2026-11-05.md` -- add a dated section with the post-fix runs (no personal data).

**Acceptance Criteria:**
- Given the packaged jar (`measure-tenants.sh`, default packaged mode) on the 363-schema clone with `--parallelism 4`, when the boot upgrade re-applies the measured module on every schema, then 0 tenants fail on 3 consecutive runs and the app answers requests (no 500 from a broken class loader).
- Given schemas whose histories were written by the previous code (keevo_dev clone), when the new build boots, then no checksum mismatch (`Validate failed`) is logged and no migration is re-applied.
- Given the full backend suite run package by package, when it completes, then 0 failures.

## Design Notes

Pattern: **read-once cache + adapter**. `ModuleScriptCache` is a lazily filled registry of immutable entries: `ConcurrentHashMap.computeIfAbsent` keyed by (root, module), whose loader throws on I/O failure so the key stays absent and the next call retries (a failure is never cached). `InMemoryModuleResourceProvider` adapts it to Flyway's `ResourceProvider` port, so Flyway's own parser, checksum and validation are untouched.

Cost: the jar is read once per (root, module) per JVM: about 10 modules × a few files. Each `migrate()` call then does zero classpath I/O; `getResources(prefix, suffixes)` is a linear filter over a module's handful of scripts (O(k), k < 10). Memory: the text of all module scripts once (tens of KB). The per-tenant cost is unchanged otherwise (one Flyway run per schema and module, bounded by the existing parallelism).

Checksum: Flyway's checksum is computed from the `Reader` lines of the resource, so the same text gives the same checksum whatever the provider; the integration test (a) pins it against a history written by the classpath configuration.

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='FlywayModuleMigrationsTest,ModuleScriptCacheTest'` -- expected: pass
- `cd keevo/backend && mvn -o -q test -Dtest='ModuleProvisionerPostgresIntegrationTest,FoundationMigrationsPostgresIntegrationTest,ModuleLifecyclePostgresIntegrationTest'` -- expected: pass
- Full suite package by package with `run-pkgs-wait.sh` -- expected: 0 failures
- `fuser -k 4500/tcp; keevo/backend/scripts/load/measure-tenants.sh --parallelism 4` three times -- expected: `0 failed` in each upgrade summary

## Auto Run Result

Status: done

**Change:** module migration scripts are read from the classpath once per (root, module) per JVM under a cache-wide lock (one jar read at a time, failures never cached) and served to every Flyway run through an in-memory `ResourceProvider` plus an empty Java-migration `ClassProvider`; `hasMigrations` / `latestVersion` read the same cache. No Flyway run touches the jar any more; parallelism default stays 4.

**Files:**
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/ModuleScriptCache.java` — new: read-once cache, strict UTF-8, relative path kept, script outside its location fails loudly.
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/InMemoryModuleResourceProvider.java` — new: Flyway `ResourceProvider` over the cache, `NO_JAVA_MIGRATIONS`.
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/FlywayModuleMigrations.java` — `migrate()` uses the providers (unreadable scripts → `ModuleProvisioningException` `SCRIPTS/…`), `hasMigrations` / `latestVersion` on the cache, test seam with a cache.
- `keevo/backend/src/test/java/com/keevo/shared/module/adapter/out/persistence/ModuleScriptCacheTest.java` — new, 9 tests.
- `keevo/backend/src/test/java/com/keevo/shared/module/adapter/out/persistence/FlywayModuleMigrationsInMemoryPostgresIntegrationTest.java` — new, 6 tests (classpath histories validate both ways, misnamed, next root, parallel, own cold cache read once, duplicate version across roots).
- `_bmad-output/planning-artifacts/research/fusion-mesures/rapport-charge-tenants-2026-11-05.md` — post-fix runs (Constat 1 fixed).

**Review:** 28 findings — 13 patched (3 medium entries grouped into 2: regression test through the cache seam, duplicate-version test; low: relative-path fallback now throws, `SCRIPTS/` failure code + test, parallel test timeout and provbatch checks, javadoc), 1 deferred (Flyway plugin `ServiceLoader` per run, medium unverified → routed to 16-7 dry-run), 14 rejected with reasons in the triage log.

**Follow-up review recommended:** true — 2 medium entries patched; named risk: the nested-jar surface (Spring Boot `jar:nested:` URLs, per-run plugin loading) is covered only by the manual harness, not by an automated test.

**Verification:**
- Full backend suite package by package: 5,980 tests, 0 failures, 0 errors.
- Packaged jar, parallelism 4, 363-schema clone: 4 runs before the review patches and 1 after: first boot 362 checked / 0 failed (no checksum mismatch), migration `messaging` 362/362 in 3.6–4.7 s, 0 failed; registrations 0 HTTP errors.

**Residual risks:** single `pg_dump` still fails with out of shared memory (routed to 10-8 already); the deferred plugin-loading item above.
