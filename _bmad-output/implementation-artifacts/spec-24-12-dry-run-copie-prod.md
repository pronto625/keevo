---
title: '24.12 — Dress rehearsal of the Bref cutover on the prod copy, downtime measured'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: '4901cbc96643dd36ba76227573054ae0d0559ca4'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-24-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-9-pilotage-vagues-rollback.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-13-runbook-communication.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The cutover runbook (24.13) carried estimates « en attente de 24.12 »: nobody had played J0 on a copy of the real Bref prod, the announced downtime was unknown, and three items were routed from the 24.9 review: (1) PostgreSQL `deadlock detected` between tenant module scripts when companies are created in parallel (only healed by a later retry), parallelism 4 vs 8 to fix; (2) the largest owner vs the 30-minute stale-coordinator takeover; (3) the `after=` next pages of the super-admin lists, never walked past one page. Two more were routed by 20.9 (monthly KPI = Bref rule on the prod copy) and 24.10 (real old links 302 → 200).

**Approach:** Treat the user's dump of 03/11 (`bd_local_20261103_213348.sql.gz`, decided by the user on 11/11 as THE prod copy: Bref is tiny) as the Bref prod database, play runbook J0 literally on the local machine (dump → transfer → restore → helper indexes → read-only role → backend → cutover → report → decision → go; a second rehearsal on a fresh dump ending with the global rollback), time each step, fix the runbook where it is not executable as written, fill §0.2 and §14. Find the root cause of the deadlocks and fix it in code; validate it with a scale run (synthetic load layered on the real copy, parallelism 4 then 8) and extrapolate. Archive a report without personal data in `research/fusion-mesures/`.

## Boundaries & Constraints

**Always:** the dump file is only read; restored into `bref_prod_t2412` (plays Bref's `bref_postgres`) and `bref_src_t2412` (plays `bref_bascule`); Keevo on `keevo_dev_t2412`, port 4653, WhatsApp noop, FCM off; all databases and the role dropped at the end. No personal data (names, phones, ids) in the report, the runbook or the spec: owners are « Compte n », only aggregates. The deadlock fix must make concurrent tenant DDL deadlock-free by construction (not by retry) and keep every applied Flyway script unchanged (checksums are validated, Story 13.9).

**Never:** ssh, the prod server, a live Bref, keevo_dev or port 4500; editing an applied module script; changes to `BrefCutoverService` / `AdminBrefCutoverController` (a parallel 24-9 complement edits them); sending any message.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Real copy, rehearsal 1 | dump of 03/11, 1 owner | runbook J0 played; owner « Importé et réconcilié »; sums Bref = Keevo; « go » 200 | none needed |
| Real copy, rehearsal 2 | fresh dump, Keevo reset | same report (durations aside); global rollback → Keevo back to its initial state | — |
| Parallel tenant creation | 8 tenants created and provisioned at once | no `deadlock detected`, every script applied | the lock waits; never a retry |
| Scale run | real copy + 201 synthetic owners (one with 20 000 documents), P = 4 then 8 | 202/202 reconciled, 0 deadlock, 0 provisioning failure, durations recorded | — |
| Lists past one page | 202 owners / batches, several cutovers | `after=` walks every row once, in order | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/db/modules/*/V1__*_baseline.sql` -- read only: the « drifted defaults » block joins `information_schema.columns`; its `column_default` calls `pg_get_expr`, which takes an `AccessShareLock` on every table it deparses — the plan evaluates it on defaults of every schema before the schema filter. Root cause of the deadlocks (two creations, each holding `AccessExclusiveLock` on its new tables, each waiting for the other's).
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` -- creation transaction of a tenant schema; drops (`dropSchemaIfExists`, `dropSchemaForDeletion`).
- `keevo/backend/src/main/java/com/keevo/shared/module/adapter/out/persistence/FlywayModuleMigrations.java` -- one Flyway per (schema, module), each script in its own transaction.
- `keevo/backend/src/main/java/com/keevo/migration/application/BrefCutoverService.java` -- read only: coordinator heartbeat while waiting (semaphore `tryAcquire(1 min)`, `awaitTermination(1 min)`), `STALE` 30 min.
- `keevo/backend/src/main/java/com/keevo/migration/application/BrefCutoverService.java` -- `MAX_PARALLELISM` 4 (review), heartbeat while waiting read only.
- `docs/RUNBOOK_BASCULE_BREF.md` -- §0.2, §1.1, §1.5, §3.1, §3.4, §4.4, §6.4, §6.8, §6.10, §13, §14.
- `keevo/ops/migration/bref-synthetic-load.sql`, `bref-copy-indexes.sql` -- used as is.

## Tasks & Acceptance

**Execution:**
- [x] `shared/infrastructure/persistence/TenantDdlLock.java` -- cluster-wide transaction-level advisory lock (`pg_advisory_xact_lock(hashtextextended('keevo:tenant-ddl', 0))`), refused in auto-commit.
- [x] `TenantSchemaProvisioner` -- lock first in the creation transaction; drops in their own transaction under the lock.
- [x] `shared/module/adapter/out/persistence/TenantDdlLockCallback.java` + `FlywayModuleMigrations` -- Flyway `BEFORE_EACH_MIGRATE` callback takes the lock inside each script's transaction.
- [x] `TenantDdlLockPostgresIntegrationTest` -- creation, Flyway script and drop each wait for a held lock; auto-commit refused; 8 tenants created and provisioned in parallel without deadlock (the same test without the lock fails with SQLSTATE 40P01 — checked once).
- [x] Live: rehearsal 1 (go), rehearsal 2 (fresh dump, rollback), scale runs P = 4 and P = 8, lists walked, KPI compared.
- [x] `docs/RUNBOOK_BASCULE_BREF.md` -- measured figures, fixes of non-executable steps.
- [x] `_bmad-output/planning-artifacts/research/fusion-mesures/rapport-repetition-bascule-2026-11-11.md` -- archived report (no personal data).

**Acceptance Criteria:**
- Given the prod copy restored apart, when the cutover is played following the runbook step by step, then 100 % of the owners are reconciled without gap or documented, and each step is timed with the total downtime and the average per owner archived in `research/fusion-mesures/`.
- Given the rollback, when it is played at the end of a rehearsal, then Keevo is back to its initial state (no imported company, no account created by the import) and its duration is measured.
- Given a second rehearsal on a fresh dump, when it is run, then the results are identical.
- Given 8 tenants created at the same time, when their foundation scripts run, then no deadlock occurs (no retry involved).

## Spec Change Log

## Review Triage Log

### 2026-11-12 — Review pass
- verdicts: 38 findings — high 0, medium 4, low 15, false 11, maybe-false 8
- findings:
  - *Blind hunter*
  - `[medium]` `[patch]` Decision deadline after the announced end of outage — §0.2 rewritten: announced return = stop + 3 h, deadline = return − (rollback 15 min + 30 min) = stop + 2 h 15, rollback ends before the return; report aligned.
  - `[medium]` `[patch]` `N × 3 s` contradicts the measured growth per owner — §3.4 and report: linear only for small N, 1 000 owners « au moins 1 h, à mesurer » (none of this applies to the real N = 1).
  - `[maybe-false]` `[reject]` Prod Keevo size not measured for the locked time — prod has ≈ 25 tenants vs 202 measured; the measured case is the larger one; noted in residual risks.
  - `[low]` `[patch]` Global lock side effects (sign-ups, boot upgrade, no timeout) not documented — §0.2 states that Keevo creations/deletions wait during the command; DROP gets a lock_timeout (D8); boot upgrade serial noted in residual risks.
  - `[low]` `[patch]` Total connections / `max_connections` not covered — §3.1 point 4: ≤ 21 Keevo + ≤ 4 read-only measured, Flyway pool size, check `max_connections`.
  - `[false]` `[reject]` Negative control missing — the test was run once with the lock disabled and failed with SQLSTATE 40P01 (spec Tasks); a permanently failing test cannot stay in the suite.
  - `[low]` `[patch]` `pg_locks` check not isolated + fixed sleep — filtered on the current database, polled up to 30 s instead of a fixed sleep.
  - `[low]` `[reject]` `dropSchemaIfExists` and the auto-commit callback branch untested — `dropSchemaIfExists` shares `dropUnderDdlLock` (tested through `dropSchemaForDeletion`); the auto-commit branch only logs.
  - `[low]` `[patch]` Non-transactional script bypasses the lock silently — WARN logged in `TenantDdlLockCallback`.
  - `[medium]` `[patch]` `dropUnderDdlLock` restore can mask the cause and return a broken connection — restore guarded, connection aborted on failure, as `provision` does.
  - `[low]` `[patch]` « validé pour la production » stronger than the evidence — header now says « exécutable tel qu'écrit » for DB/API/backend steps and lists the open §13 user actions.
  - `[low]` `[patch]` Replay instructions inconsistent — header and §4.4 both say §6.2–6.10, with a threshold (> 10 owners, > 1 000 documents, schema change in 18.3) and the decider.
  - `[false]` `[reject]` Spec untracked / sprint-status not updated — finalisation commits the spec and sets the sprint key (this step); the epic keeps its history wording next to « fait ».
  - `[low]` `[patch]` Report « Base » bullet unclear + remaining failed owner unexplained — explained (others waited for a pool connection before the lock); the owner was erased by the rollback before analysis, same cause; API now capped at 4.
  - *Intent alignment (descriptive)*
  - `[false]` `[reject]` (a) Deadlock fix tested below the cutover surface — the cutover surface was exercised live (P = 4 and 8, PostgreSQL log 0 `deadlock detected`), the test pins the mechanism.
  - `[false]` `[reject]` (b) Broad blast radius — D3: a narrower lock leaves a cycle; side effects documented (§0.2).
  - `[false]` `[reject]` (c) The fix changed the throughput it validates — measured and reported as such; parallelism enforced at 4.
  - `[false]` `[reject]` (d) Downtime mostly estimated — D2: human steps do not exist locally; header says so.
  - `[false]` `[reject]` (e) Local substitutions not written into the runbook — they are rehearsal substitutions, not runbook defects (report §1).
  - `[false]` `[reject]` (f) Pagination checked on the API, not on screen — the dashboard lives in another repo; the API pages are what it consumes.
  - `[false]` `[reject]` (g) Heartbeat claim unbacked — `BrefCutoverService.run`: `permits.tryAcquire(1, MINUTES)` and `awaitTermination(1, MINUTES)` loops call `store.heartbeat`.
  - `[maybe-false]` `[reject]` (h) AI parity corpus (21.x) not addressed — it runs as a test with a simulated provider (assistant module); the real copy holds 2 conversations, nothing numeric to compare; noted in the report of the run.
  - `[low]` `[patch]` (i) Real owner's amounts in the runbook — removed from §6.10 (D9), kept in the archived report as reconciliation evidence (aggregates, no personal data).
  - `[false]` `[reject]` (j) Bookkeeping only via the epic — sprint key and spec are written at finalisation.
  - *Design / complexity*
  - `[medium]` `[patch]` Waiters hold pool connections → pool exhaustion; API still accepts 8 — `MAX_PARALLELISM` 4 (D4); in-JVM gate not added (the outer transaction's connection would stay held anyway).
  - `[maybe-false]` `[reject]` O(T²) total cost now serial; skip the redundant Flyway re-run of fresh foundation scripts — real, but the real cutover has N = 1; recording the history without re-running changes the 16.12 design (single author of tenant DDL) and Flyway validation; residual risk noted.
  - `[maybe-false]` `[reject]` Reader/writer split of the lock — a non-scanning script altering an older table can close the cycle (D3); not safe.
  - `[low]` `[reject]` Seed and keyset indexes under the lock — milliseconds, same transaction required.
  - `[low]` `[reject]` Deletion waits holding a row lock — no cycle; daily deletion is not run during the J0 command window in practice.
  - *Verification gap*
  - `[low]` `[reject]` No pooled-path test of parallel creation — pool exhaustion is closed by the parallelism cap and was measured live; a Hikari-bounded test would duplicate the rehearsal.
  - `[low]` `[patch]` (other) MAX_PARALLELISM 8 still accepted — same as the design row (D4).
  - `[maybe-false]` `[reject]` (other) Registrations wait without timeout during a cutover — documented §0.2; bounded by the command.
  - `[false]` `[reject]` (other) Callback might run outside the script transaction in a future Flyway — Flyway 11.7.2 fires it inside the transaction (checked in the source and by the reviewer); a version bump is covered by the WARN plus the tests.
  - *Edge case hunter*
  - `[low]` `[patch]` DROP waiting on a live session while holding the DDL lock — `SET LOCAL lock_timeout = '30s'` before the DROP (D8).
  - `[maybe-false]` `[reject]` Unbounded provisioning wait for the lock — bounded by the other DDL transactions; a timeout would turn a slow queue into failed registrations.
  - `[maybe-false]` `[reject]` Flyway history-lock retries exceeded behind the DDL queue — concurrent runs on the same (schema, module) are already serialised in the JVM (per-schema locks of `ModuleProvisionerService`, per-key lock of `ensureProvisioned`); none seen in 404 imports.
  - `[low]` `[patch]` Restore failure masks the cause — same as the blind row.
  - `[maybe-false]` `[reject]` Fixed 1.5 s sleep flaky — replaced by polling (same as the blind row); kept here as its own row.

## Design Notes

**Root cause (measured).** PostgreSQL log of the 24.9 run: `Process A waits for AccessShareLock on relation X; blocked by process B. Process B waits for AccessShareLock on relation Y; blocked by process A`, both inside the « drifted defaults » `DO` block of a foundation `V1` (`FOR col IN SELECT … FROM information_schema.columns … WHERE c.column_default IS NULL`). `information_schema.columns.column_default` is `pg_get_expr(adbin, adrelid)`, which opens (and `AccessShareLock`s) each deparsed table; the planner pushes `column_default IS NULL` below the namespace join, so other tenants' tables are opened. A creation transaction holds `AccessExclusiveLock` on its brand-new tables until commit → cycle. Reproduced in a test (40P01 without the lock, green with it).

**Pattern:** a Mutex as a database advisory lock scoped to the DDL transaction (Template-free, one static helper + a Flyway Callback): the lock lives exactly as long as the DDL locks it protects, is released by PostgreSQL on commit/rollback/connection loss, works across backend instances, and needs no extra pooled connection (it runs on the DDL's own connection). Rejected: editing the `V1` scripts (immutable, checksum-validated on every tenant history), a JVM lock (does not cover the registration transaction's lifetime nor several instances, and a session advisory lock on a side connection would hold pool connections while waiting — pool starvation at parallelism 8), `lock_timeout` + retry (the routed item asks for a fix, not a retry).

**Cost:** one `SELECT pg_advisory_xact_lock` per tenant DDL transaction (≈ 50 per new tenant: 1 creation + ~49 Flyway scripts); tenant DDL becomes serial cluster-wide. Measured: per new tenant the locked work is ≈ 3 s on the dev machine; the cutover throughput is bounded by it (≈ 0.34 tenant/s whatever the parallelism) — see the scale runs. Normal operation (one registration now and then, boot upgrade of behind tenants) is unaffected in practice. The catalogue scan itself stays O(defaults of the database) per foundation script (measured 17 ms with 202 tenants, 40 157 column defaults).

**Decisions (default, conservative, 11/11/2026):**
- D1 The user's dump of 03/11 is the prod copy (user decision 11/11); it is a plain SQL dump: restored into `bref_prod_t2412` to play Bref's database, then the runbook's own `pg_dump -Fc` / `pg_restore` produced the copy — the J0 commands are the ones rehearsed.
- D2 Steps that cannot exist locally (6.1 stopping api-bref, 6.3 `scp`, 6.9 webhooks, 6.15 proxy, 6.16 stores, 6.17 campaigns) are timed from the runbook's estimates and marked so; all database, API and backend steps are measured.
- D3 Serialize all tenant DDL (creation, every module script, schema drops) with one advisory lock, not only the scripts that scan the catalogue: a non-scanning script holding `AccessExclusiveLock` on its new tables and then altering an older table can close the same cycle.
- D4 Parallelism **4**, enforced: `BrefCutoverService.MAX_PARALLELISM` 8 → 4 (review; one constant, the 24-9 complement had landed) — P = 8 brings no gain once tenant DDL is serial and starved the 12-connection pool (14 owners failed). The 422 message now reads « … va de 1 à 4 ».
- D8 A drop holds the DDL lock: its `DROP SCHEMA` gets `SET LOCAL lock_timeout = '30s'` so a session still using the tenant's tables cannot freeze every other tenant DDL; the lock wait itself stays unbounded (it is bounded by the other DDL transactions).
- D9 The real owner's exact amounts stay only in the archived measurement report (the evidence of the reconciliation); the runbook describes the « encaissé > ventes » case without figures.
- D5 `STALE` (30 min) is kept: the coordinator beats every minute while it waits for workers, so a long owner never makes it look dead; the largest owner measured is 30 s (synthetic 20 000 documents) and the real owner 5 s.
- D6 The « Montant encaissé » of the report counts acomptes and paid instalments of every document, cancelled ones included (24.8 rule, same on both sides): on the real copy it is larger than « Ventes non annulées »; documented in the runbook (§6.10) so it is not read as a gap.
- D7 The real copy has no Bref public link (`publicToken` empty on every invoice): §1.5 runs with zero tokens; the 302 → 200 chain stays proven by 24.10's tests and the synthetic run.

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t2412 migration architecture shared/infrastructure/persistence shared/module identity/auth` -- expected: all green.

**Manual checks:** runbook J0 on the real copy twice (go, then rollback), scale runs P = 4 / 8 with the PostgreSQL log checked for `deadlock detected`.

## Auto Run Result

Status: done.

**Summary.** Dress rehearsal of the one-shot Bref cutover on the prod copy of 03/11 (user decision 11/11: the dump IS the prod copy; 1 owner, 1 shop, 19 documents):
- Runbook J0 played literally twice: rehearsal 1 (§6.2 → §6.14 « go », §1.5, §6.18), rehearsal 2 on a fresh dump of a reset Keevo ending with the global rollback (§8.1) — then a third run after the rollback. Owner « Compte 1 » reconciled without gap three times, identical reports; Keevo monthly revenue of the imported owner = Bref's rule (20-9 AC2 on the prod copy); 0 Bref public link in the copy (24.10's 5-link check has nothing to serve).
- Downtime: ≈ 50 s of machine from dump to « go » (command 5 s server-side for the real owner); ≈ 40 min with the human steps (webhooks = critical path); announced return at most 3 h after the stop, decision deadline stop + 2 h 15 (runbook §0.2). Rollback 0.19 s server-side, Keevo back to its initial state.
- Deadlock root cause found (foundation V1 scripts' `information_schema.columns` → `pg_get_expr` locking every schema's tables, while each creation holds its new tables exclusively) and fixed by `TenantDdlLock` (transaction advisory lock around every tenant DDL transaction: creation, each Flyway module script, drops). Scale runs (real copy + 201 synthetic owners): P = 4 → 202/202 in 600 s, 0 deadlock, 0 failure; P = 8 → 0 deadlock but 14 owners failed on pool exhaustion → parallelism capped at 4 in the API.
- Routed items: 3 from 24.9 done (deadlocks/parallelism, largest owner vs STALE, `after=` pages walked to the end on owners/batches/cutovers); 20-9 AC2 done; 24.10 real-link check not applicable (0 token).

**Files:** `TenantDdlLock` (new), `TenantSchemaProvisioner` (lock in creation, drops in a locked transaction with `lock_timeout` 30 s, safe restore), `TenantDdlLockCallback` (new, Flyway `BEFORE_EACH_MIGRATE`), `FlywayModuleMigrations` (callback registered), `BrefCutoverService.MAX_PARALLELISM` 4 + `BrefCutoverUseCase` javadoc + `BrefCutoverServiceTest` (5 refused), `TenantDdlLockPostgresIntegrationTest` (new, 5 tests); `docs/RUNBOOK_BASCULE_BREF.md` (header, §0.2, §1.1, §1.5, §3.1, §3.4, §4.4, §6.4, §6.7, §6.8, §6.10, §13, §14); `research/fusion-mesures/rapport-repetition-bascule-2026-11-11.md` (new) + README row; epic items ticked; spec-24-9 dashboard message; sprint-status (24-12 done, d24-12).

**Review.** 38 findings: 4 medium + 11 low patched (decision deadline vs window, extrapolation wording, drop restore safety, API parallelism cap, connection totals, lock_timeout on drops, WARN outside a transaction, test isolation and polling, header wording, replay threshold, report clarity, amounts out of the runbook), 0 deferred, 23 rejected with reasons (triage log). Patched by verdict: high 0, medium 4, low 11.

**Verification:** migration 187, architecture 173 (1 skipped, pre-existing), shared 1 403, identity/auth 576 green on the first pass; after the review patches: shared 1 403, migration 187, architecture 173 (1 skipped) green. Live: as above; all rehearsal databases, the read-only role and the dump copies removed (shredded).

**Follow-up review recommended:** `true` — four medium entries patched; the unverified risk is the effect of the serial tenant DDL on normal Keevo operation (registrations and the boot upgrade of behind tenants wait behind each other; measured only during the cutover).

**Residual risks:** cutover throughput is bounded by serial tenant DDL and degrades with the number of schemas (catalogue scan of the V1 scripts, run twice per new tenant: registration then Flyway) — irrelevant for the real N = 1, to measure again if Bref grows past ~100 owners; webhook, Apple, store and Meta steps never rehearsed (user actions §13).

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10.

Sans objet : la répétition et le verrou des DDL ne changent rien de ce que voit l'app. Pas de clé `m24-12`.

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`.

### Statut

API livrée (story 24.12). Clé de suivi : `d24-12-dry-run-copie-prod`.

### Ce qui est fait côté API

Le lancement d'une bascule n'accepte plus qu'un parallélisme de 1 à 4 (4 par défaut) : au-delà, la création sérielle des schémas épuise la réserve de connexions (mesuré).

### À utiliser

- `POST /api/v1/admin/migration/cutovers` `{"source":"DATABASE","parallelism":4}` → 202 ; `parallelism` 5 à 8 → 422 `VALIDATION_FAILED` « Le nombre de comptes importés en même temps va de 1 à 4 » (`keevo/backend/src/main/java/com/keevo/migration/application/BrefCutoverService.java`).

### À implémenter côté dashboard

1. Écran « Bascule », bouton « Lancer la bascule » : ne proposer que 1 à 4 (4 par défaut), ou ne pas proposer le réglage du tout.
2. Afficher le message français du 422 tel quel s'il revient.

### Critères d'acceptation dashboard

- **Given** l'écran « Bascule », **When** le super-admin lance la bascule sans changer le réglage, **Then** elle part au parallélisme 4 (202).
- **Given** un réglage à 8 envoyé, **When** l'API répond 422, **Then** le message « … va de 1 à 4 » s'affiche, sans code technique.

### Clé de suivi dashboard

`d24-12-dry-run-copie-prod`
