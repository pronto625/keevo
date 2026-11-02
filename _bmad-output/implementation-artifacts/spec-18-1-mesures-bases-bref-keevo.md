---
title: '18-1 Measure the Bref and Keevo databases (Q1–Q9, Q13) — read-only SQL kit, runner and dated aggregate report'
type: 'chore'
created: '2026-11-02'
status: 'done'
baseline_revision: '8956e440c3f3f8e3c0862f0bfc6f03a522b58c89'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-18-context.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The fusion plan (epics 18–25) sizes tenants, orders migration waves and settles product decisions on numbers nobody has yet: owners, volumes, identity collisions, non-CM shops, credits, inconsistent invoice totals, owners paid-in-name-only, plus two facts outside the databases (date `develop` reached api-bref production, real published Keevo app identity). Gate G0 (story 18.2) cannot be held without them.

**Approach:** Ship a versioned, rerunnable, read-only measurement kit under `_bmad-output/planning-artifacts/research/fusion-mesures/`: one SQL file per question group for the Bref base (api-bref, single schema) and one for the Keevo `public` schema, a bash runner that executes them in read-only transactions against connection strings the operator supplies (replica or locally restored dump, never prod directly) and writes a dated Markdown report of aggregates only, a privacy-preserving Q4 intersection (hashed normalised phones compared locally, hash files never committed), and a "repository facts" section for Q9/Q13 with the console checks left to the user. Validate the kit locally on `keevo_dev` (port 5444, 344 tenants) and on a throwaway Postgres carrying the Bref schema built from the TypeORM entities.

## Boundaries & Constraints

**Always:**
- Every SQL statement is a `SELECT`; the runner sets `PGOPTIONS='-c default_transaction_read_only=on'` and `psql -X -v ON_ERROR_STOP=1`; no temp tables, no extensions required (use `md5()` for hashing, not pgcrypto).
- Report = aggregates only (counts, sums, min/max/percentiles, buckets, country prefixes). Never a phone, name, email, id, invoice reference or free text. The Q4 hash exports (`*.hashes.csv`) live in the run output dir, are listed in a `.gitignore` inside `fusion-mesures/`, and the README says to delete them after the run.
- Bref SQL targets TypeORM naming: singular tables, camelCase quoted identifiers (`"user"`, `"createdAt"`, `"ownerId"`, `"paymentMethod"`), soft deletes via `"deletedAt"` (report deleted rows separately, never silently excluded).
- Plan in force uses exactly the `SUBSCRIPTION_IN_FORCE` predicate of `src/entitlement/entitlement.service.ts` (`status='active'`, `startedAt` null or ≤ now, `endsAt` null or > now or `gracePeriodEndsAt` > now, `paymentMethod` not null), evaluated at run time; `user.plan` is only reported as a comparison column (Q8).
- "Active < 90 days" has no source column: report three stated proxies per owner (any `refresh_token.createdAt` of the owner or their members ≥ now−90 d; any `invoice.createdAt` in the owner's shops ≥ now−90 d; any `push_token.updatedAt` ≥ now−90 d) and label them as proxies in the report.
- Payment-method buckets: `apple`, `admin_grant`, MyCoolPay = {`orange_money`,`momo`,`card`,`wave`}.
- Phone normalisation (both sides, SQL): `regexp_replace(btrim(x), '[[:space:]\-.()]', '', 'g')`, then prefix `+` when the result does not start with `+` — the exact `PhoneNumbers.normalize` rule. Q4 exports `md5(normalised)` one per line, sorted, distinct; the runner computes `|Bref owners ∩ Keevo|`, `|Bref members ∩ Keevo|` with `comm -12 | wc -l`.
- Q7 recalculation = `("subtotal" - "discount") + "tax" + "deliveryFee"` vs `"total"` (tolerance 1 XAF) and `"subtotal"` vs `sum(invoice_item."total")`; counts split by `createdAt` before/after the psql variable `develop_deploy_date` (default `2026-10-26`, see Q13), by `type` and `status`. Q5 VAT check compares `"tax"` with `round(("subtotal"-"discount") * rate(country))` using the rate table of `src/invoice/constants/vat-rates.constant.ts` (CM 19.25 %, SN/CI/BJ/ML/BF/TG/GN/GA 18 %, CD/KE 16 %, NG 7.5 %, GH 15 %, else 0) and reports `tax = 0` with `subtotal > 0` as "VAT skipped".
- Non-integer amounts: count rows whose `value <> trunc(value)` for `invoice.subtotal/tax/discount/deliveryFee/total/initialPayment`, `invoice_item.unitPrice/total`, `invoice_payment.amount`, `expense.amount`, `product.unitPrice`; `invoice_item.quantity` decimals reported separately (legitimate since `develop`).
- Keevo side reads only `public` (`tenants` by `plan_type`×`status`, count for FUS-NFR3 capacity ×2, `deletion_scheduled_at` not null; `users` by `role`/`is_active`; `user_tenant_memberships` by `role`/`is_active`, users with > 1 membership; phone prefix distribution by first 4 characters and count of phones not matching `^\+[0-9]{8,15}$`; `pg_namespace` count of `kv_%` schemas; database size).
- Q9/Q13 section: computed from the repos by the runner (api-bref: last `develop`→`main` merge hash+date and number of `develop` commits absent from `main`; the GHCR workflow builds only `main` and deploys `ghcr.io/dsrdesign/api-bref:latest`; Keevo: `applicationId`/`namespace` from `keevo/app/android/app/build.gradle.kts`) followed by an explicit "To confirm by the operator" checklist: GHCR `sha-*` tag running on the VPS, Play Console package name and Play App Signing status, Google/Apple developer accounts available. The report never presents these as measured.
- Deliver the checked-in `rapport-mesures-2026-11-02-preparatoire.md`: the Q9/Q13 repository facts, the local validation summary (keevo_dev counts are dev data and say so), the open checklist, and the exact command to produce the production report. Production figures are produced by the user and are out of this story's control.
- Sprint tracking: `18-1-mesures-bases-bref-keevo` → `done` with a dated comment in `sprint-status.yaml` (epic-18 block). No mobile/dashboard section (no API change).

**Never:** no product code, no change under `keevo/backend`, `keevo/app`, `AI/api-bref` or `AI/bref` (local `node_modules`/throwaway containers are fine); no write SQL, no `CREATE EXTENSION`; no access to production hosts; no personal data in any committed file or in the report; no story files for epics 19+; no edit of the fusion epics document (the `status` flip belongs to 18.2).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Full run | `BREF_DSN` + `KEEVO_DSN` set, both reachable | `out/<date>/rapport-mesures-<date>.md` with sections Q1–Q8, Keevo, Q4 intersection, Q9/Q13 facts + checklist; exit 0 | — |
| Keevo only | `BREF_DSN` unset | Bref sections and Q4 intersection marked « non mesuré (BREF_DSN absent) », Keevo measured; exit 0 | warning on stderr |
| Read-only guard | a SQL file accidentally contains a write | psql aborts (`cannot execute … in a read-only transaction`), runner exits non-zero, report marked incomplete | ON_ERROR_STOP |
| Empty Bref schema | tables exist, no rows | every query returns 0 rows/0 sums without division errors (`NULLIF`) | — |
| Fixture check (local) | synthetic rows: paid owner, expired-paid owner with `plan='pro'`, owner without phone, member with username, invoice with wrong total, non-CM shop, decimal amount | Q1 counts the paid owner only; Q8 flags the expired one; Q3 counts 1+1; Q7 flags 1 (bucket by date); Q5 flags 1 shop + 1 decimal | — |
| Q4 collision | same phone written `+237 6 99 01 77 17` (Bref) and `+237699017717` (Keevo) | counted once in the intersection; hash files absent from git status | — |
| Unreachable DSN | wrong host | runner prints the psql error, no partial report claimed as complete | exit non-zero |

</intent-contract>

## Code Map

**Bref (read-only reference, `/home/toor/Project/FreeLance/AI/api-bref`, branch `develop`, 376ad70):**
- `src/users/infrastructure/persistence/relational/entities/user.entity.ts` -- `"user"`: `role` (owner/admin/seller/viewer/superadmin), nullable unique `phone`/`email`/`username`, `provider`, `socialId`, `plan`, `isActive`, `emailVerified`, `shopId` (members), `deletedAt`, `scheduledDeletionAt`. No last-login column.
- `src/shop/.../shop.entity.ts` -- `shop`: `userId` (owner), `country` default `'CM'`, `isActive`, `deletedAt`.
- `src/entitlement/infrastructure/entities/subscription.entity.ts` -- `subscription`: `ownerId`, `plan`, `status`, `startedAt`, `endsAt`, `gracePeriodEndsAt`, `paymentMethod`, `amountPaid`, `appleOriginalTransactionId`.
- `src/entitlement/entitlement.service.ts:40-43` -- `SUBSCRIPTION_IN_FORCE` predicate (copy verbatim).
- `src/entitlement/infrastructure/entities/{account-credit,usage-counter,plan-payment-request}.entity.ts` -- `account_credit(ownerId unique, amount)`, `usage_counter(ownerId, metric in invoices|ai_credits|sms|email|sms_free, periodMonth, used)`, `plan_payment_request(status, paymentMethod, amount, provider mycoolpay)`.
- `src/entitlement/quota.service.ts:6,31-42,93-97` -- free SMS lifetime limit 5 via metric `sms_free`; monthly `sms` quota; `src/shop/shop.service.ts:130-155` + `sms-recharge-request.entity.ts` (`smsQuantity`, `amountDue`, `status`, `completedAt`) -- implementer confirms whether completed recharges feed a balance column/counter and reports packs as « achetés (complétés) » vs « consommés » with the proxy named.
- `src/invoice/.../invoice.entity.ts`, `invoice-item.entity.ts`, `invoice-payment.entity.ts` -- `invoice(type sale|quote, status pending|partial|paid|overdue|cancelled, subtotal, tax, discount, deliveryFee, total, initialPayment, date text, deletedAt)`, `invoice_item(quantity numeric(12,3), unitPrice, total)`, `invoice_payment(amount, isPaid)`.
- `src/invoice/invoice.service.ts:66-85` -- total formula (`netHT = subtotal - discount`, `tax = round(netHT*vatRate)`, `total = netHT + tax + deliveryFee`); `src/invoice/constants/vat-rates.constant.ts` -- rate table.
- `src/customer/.../customer.entity.ts` (`shopId`, `phone` NOT NULL), `src/clients/.../client.entity.ts` (portal accounts), `src/product/.../product.entity.ts` (`unitPrice`, `isArchived`, `mergedIntoId`), `src/expense/.../expense.entity.ts` (`ownerId`, `shopId`, `amount`, `deletedAt`), `src/auth/refresh-token.entity.ts` (`userId`, `createdAt`), `src/users/.../push-token.entity.ts` (`userId`, `updatedAt`) -- volumes and activity proxies.
- Other tables for Q2 volumes: `apple_iap_transaction`, `apple_iap_verification_request`, `revenuecat_webhook_event`, `whatsapp_account`, `whatsapp_message`, `whatsapp_chat_message`, `customer_campaign`, `owner_whatsapp_template`, `invoice_outbox`, `notification`, `ai_conversation`, `web_push_subscription`, `otp`, `campaign`, `app_version`.
- `src/database/data-source.ts` -- env `DATABASE_URL|HOST|PORT|USERNAME|PASSWORD|NAME`, entities glob, `synchronize:false`; `.github/workflows/docker-publish.yml` -- builds on push to `main` only, tags `sha-<sha>` + `latest`, deploys `/opt/api-bref` with `docker-compose.prod.yml` (`ghcr.io/dsrdesign/api-bref:latest`). `git log main --merges`: last `develop` merge a3faeb2 on 2026-10-26 (PR #16, up to daeb28c); 7 `develop` commits after it (statuses server-side b957867, decimal quantities 2fdf20b, clients NUI/RCCM, emailVerified) are not on `main`.

**Keevo:**
- `keevo/backend/src/main/resources/db/migration/V1__baseline_public.sql` -- `public.users(phone_number NOT NULL unique, role, is_active)`, `tenants(code, schema_name, plan_type, status, deletion_scheduled_at)`, `user_tenant_memberships(user_id, tenant_id, role, is_active)`, `refresh_tokens`, `user_sync_state`; `subscriptions` is a tenant-schema table (not read here).
- `keevo/backend/src/main/java/com/keevo/shared/domain/PhoneNumbers.java:normalize` -- rule to mirror in SQL. `identity/api/TenantStatus.java` (ACTIVE, DELETION_PENDING, SUSPENDED, DELETED), `identity/api/PlanType.java` (FREE, PREMIUM_TRIAL, PREMIUM).
- `keevo/app/android/app/build.gradle.kts:23,39` -- `namespace`/`applicationId` `com.example.keevo`; `.github/workflows/release-flutter.yml:59-73` -- signs with `keevo-release.keystore` from secrets, no flavor.
- Local DB: container `keevo_postgres` (started, `localhost:5444`, db `keevo_dev`, user `keevo`; `docker exec keevo_postgres psql -U keevo -d keevo_dev` needs no password).
- `_bmad-output/planning-artifacts/epics-fusion-keevo-bref.md:330-348` -- story AC; `fusion-keevo-bref-analyse-2026-10-31.md:782-800` -- Q1–Q13 definitions; `:212-230` §3.7 develop delta; `:554-568` §7.8/§7.10.
- `_bmad-output/implementation-artifacts/sprint-status.yaml:395` -- `18-1-mesures-bases-bref-keevo: backlog`.
- `_bmad-output/planning-artifacts/research/` -- existing research files (`market-…`, `technical-…`); create `fusion-mesures/` beside them.

## Tasks & Acceptance

**Execution:**
- `_bmad-output/planning-artifacts/research/fusion-mesures/README.md` -- purpose, privacy rules, prerequisites (`psql`, read-only role, replica/dump), env (`BREF_DSN`, `KEEVO_DSN`, optional `DEVELOP_DEPLOY_DATE`), run command, where the report lands, what to delete afterwards, how epic 24 reruns it per wave.
- `.../fusion-mesures/.gitignore` -- `out/`, `*.hashes.csv`.
- `.../fusion-mesures/bref/q1-owners-plans-paiements.sql`, `q2-volumes.sql`, `q3-identites-sans-telephone.sql`, `q4-export-hashes-telephones.sql`, `q5-pays-tva-montants.sql`, `q6-credits-sms.sql`, `q7-totaux-incoherents.sql`, `q8-plan-sans-abonnement.sql` -- one file per group, each query preceded by a `\echo` title line and a comment stating the definition/proxy used.
- `.../fusion-mesures/keevo/k1-tenants-users-memberships.sql`, `k2-telephones.sql`, `k4-export-hashes-telephones.sql` -- Keevo public-schema aggregates and hash export.
- `.../fusion-mesures/run-mesures.sh` -- bash runner (set -euo pipefail): read-only PGOPTIONS, per-file psql into the report with fenced blocks, Q4 intersection via `comm`, repo facts via `git -C ../../../../../api-bref` fallback-safe (skip with a note when the repo is absent), "To confirm" checklist, dated filename `out/<date>/rapport-mesures-<date>.md`.
- `.../fusion-mesures/rapport-mesures-2026-11-02-preparatoire.md` -- committed preparatory report (repo facts, local validation summary labelled dev data, open checklist, production command).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- `18-1-mesures-bases-bref-keevo: done  # 2026-11-02 build-auto done (spec-18-1-mesures-bases-bref-keevo.md) — …`.

**Acceptance Criteria:**
- Given the kit and a Bref schema with the fixture rows of the matrix, when `run-mesures.sh` runs, then the report contains Q1–Q8 with the expected counts, each proxy labelled, and no personal value (grep of the report for the fixture phone/name/email returns nothing).
- Given `keevo_dev`, when the Keevo part runs, then tenants by plan/status, users, memberships, phone prefixes and schema count appear and `git status` shows no `*.hashes.csv` or `out/` files.
- Given the committed scripts, when grepped for `INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|COPY .* FROM` (case-insensitive, outside comments), then nothing matches (`\copy … TO` for the hash export is allowed).
- Given the preparatory report, when read, then Q13 states "main last received develop on 2026-10-26 (a3faeb2); the 7 later develop commits are not deployed" as a repository fact with the GHCR confirmation still open, and Q9 states `com.example.keevo` from the repo with the Play Console check still open.

## Design Notes

- Build the local Bref schema from the entities, not from migrations (the first tables have no migration): throwaway `docker run -d --name bref-mesures-pg -p 5499:5432 -e POSTGRES_PASSWORD=bref postgres:16-alpine`, then in `/home/toor/Project/FreeLance/AI/api-bref`: `pnpm install --frozen-lockfile` (node 24, pnpm 10 present) and `DATABASE_HOST=localhost DATABASE_PORT=5499 DATABASE_NAME=bref_db pnpm exec typeorm-ts-node-commonjs schema:sync -d src/database/data-source.ts`. If `pnpm install` is impossible (network), hand-write the fixture DDL in the scratchpad from the entities — never commit it. Remove the container at the end.
- The fixture SQL (inserts) lives in the scratchpad only; the committed kit stays read-only.
- Keep each report table short: percentiles via `percentile_cont(array[0.5,0.9,0.99])` on per-owner counts give wave sizing without listing owners.

## Verification

**Commands:**
- `bash -n _bmad-output/planning-artifacts/research/fusion-mesures/run-mesures.sh` -- expected: syntax OK.
- `grep -rniE '^\s*(insert|update|delete|create|alter|drop|truncate)\b' _bmad-output/planning-artifacts/research/fusion-mesures/{bref,keevo}` -- expected: no output.
- `KEEVO_DSN='postgresql://keevo:<dev password from keevo/docker-compose.yml>@localhost:5444/keevo_dev' BREF_DSN='postgresql://postgres:bref@localhost:5499/bref_db' bash run-mesures.sh` (with fixture loaded) -- expected: exit 0, report present with all sections, fixture counts match the matrix.
- `cd /home/toor/Project/FreeLance/AI/Keevo && git status --short _bmad-output/planning-artifacts/research/fusion-mesures` -- expected: only the kit files and the preparatory report, no `out/` or hashes.

**Manual checks (if no CLI):**
- Open the generated report and confirm no phone, email, name or UUID appears anywhere.

### 2026-11-03 — Review pass
- verdicts: 47 findings — high 1, medium 16, low 27, false 3, maybe-false 0
- findings:
  - `[medium]` `[patch]` (intent) kit is an instrument, no production figures; `18-1: done` while 18.2's Given (« le rapport 18.1 ») has no referent — sprint-status set back to `in-progress` « kit livré ; reste : run sur réplica prod + checklist GHCR/Play Console par l'utilisateur ».
  - `[high]` `[patch]` (intent) Q3.a/Q3.b read `"user"."emailVerified"`, a `develop`-only column (migration 1753780000000, absent from `main` = prod) → Q3 aborts under ON_ERROR_STOP, run INCOMPLET. Verified: `git diff main develop -- '*.entity.ts'`; also found `refresh_token` (table) is `develop`-only (1753650000000) → Q1.b PROXY 1 and Q2.a would fail too. Fix: schema-aware guards (`\gset` + `\if`) for every `develop`-only object, marked « non mesuré (schéma main) » when absent.
  - `[low]` `[reject]` (intent) Q13 read from git graph not GHCR — GHCR/VPS needs operator credentials; the report labels it a repo fact with the confirmation in the checklist.
  - `[low]` `[reject]` (intent) Q9 from repo not Play Console — console access is the operator's; 18.4 owns it; labelled « à confirmer ».
  - `[false]` `[reject]` (intent) Q6 « packs SMS restants » not computed — no SMS balance exists in api-bref (`sms_recharge_request` has no completion handler, no counter); the kit names the gap instead of inventing a number.
  - `[low]` `[reject]` (intent) three activity proxies instead of one figure — no last-login column; proxies labelled; the choice belongs to 18.2.
  - `[false]` `[reject]` (intent) Keevo K1/K2 beyond the AC — extra aggregates cause no bad outcome; the tenant count feeds FUS-NFR3.
  - `[low]` `[reject]` (intent) `epic-18-context.md` replaced wholesale — the V1 export epic (v1s-18-*) is fully done; the file name is fixed by the workflow; history keeps the old text.
  - `[false]` `[reject]` (intent) spec not in the diff — the spec is committed with the change at finalize.
  - `[low]` `[patch]` (intent) Q4 asymmetry on blank phones (Keevo hashes `'+'`) — grouped with the edge-case blank finding: exclude empty normalised values on both sides.
  - `[medium]` `[patch]` (verification-gap) `SUBSCRIPTION_IN_FORCE` copied 8× with no parity check — factor into `bref/_definitions.psql` (`\set IN_FORCE`, `\ir`), runner compares it to `entitlement.service.ts` when the clone is present.
  - `[low]` `[reject]` (verification-gap) phone normalisation has no Java↔SQL parity test — rules verified identical today, Keevo rule stable since 12.12; a cross-language harness is more than a direct correction.
  - `[medium]` `[patch]` (verification-gap) only behavioural validation is a scratchpad fixture — commit `validation/` (synthetic fixture with fake numbers only, `validate-local.sh` asserting the matrix counts), outside the write-grep path.
  - `[low]` `[patch]` (verification-gap) README says psql ≥ 10 but `:{?var}` needs 11 — README floor raised to 11.
  - `[medium]` `[patch]` (blind) Keevo activity proxied although `public.user_daily_activity` exists — add exact 30/90-day active users/tenants.
  - `[medium]` `[patch]` (blind) Keevo payments/plan events/deletion log never measured — add `tenant_payments` by method (period in force, sums), `tenant_plan_events` by cause × to_plan, `account_deletion_log` by event (columns verified in V17/V40).
  - `[medium]` `[patch]` (blind) unsalted md5 of phones is enumerable — per-run random salt passed by the runner to both exports (grouped with the edge-case duplicate).
  - `[medium]` `[patch]` (blind) Q4 cannot tell collision types — Keevo export split OWNER/EMPLOYEE, active users only; 2×2 matrix in the report.
  - `[medium]` `[patch]` (blind) Q8.a uses `MIN(s.plan)` — API `activePlanOf` orders by `COALESCE(startedAt, createdAt) DESC LIMIT 1` (`entitlement.service.ts:215-223`); use `DISTINCT ON`.
  - `[low]` `[patch]` (blind) Q5.c flags net = 0 as « TVA ignorée » — filter on `(subtotal - discount) > 0` (grouped with the edge-case duplicate).
  - `[medium]` `[patch]` (blind) Q2.a one 36-way UNION ALL fails whole section on a missing table — catalog estimate (`pg_stat_user_tables`) first, exact counts guarded for `develop`-only tables.
  - `[medium]` `[patch]` (blind) runner validates nothing; no-DSN run exits 0 « complet » — exit 2 when no DSN, validate `DEVELOP_DEPLOY_DATE` and `REPORT_DATE`.
  - `[low]` `[patch]` (blind) no provenance for replays — header gets kit revision + dirty flag, `SELECT version()` per DSN, `pg_last_xact_replay_timestamp()`.
  - `[low]` `[patch]` (blind) Q13 relies on local `main`/`develop` and a message grep; Bref app.json hard-coded — `merge-base`, `origin/*` fallback, read `bref/app.json` when present.
  - `[low]` `[patch]` (blind) Q7 boundary « timezone-dependent » — tz part refuted (`createdAt` is `TIMESTAMP` without tz, api-bref forces UTC); deploy-day ambiguity real → third bucket « jour de la bascule ».
  - `[low]` `[patch]` (blind) live + soft-deleted merged silently in Q5.e/f, Q7.e, Q6.g — label the `\echo` lines; restrict Q6.g to live owners.
  - `[low]` `[patch]` (blind) Q6.c month boundary vs `currentPeriod()` server TZ — document the UTC assumption (`utils/force-utc`), implementer verifies.
  - `[low]` `[patch]` (blind) epic context says status rules reached prod — reworded: only what is on `main` by a3faeb2 is deployed; status rules (b957867) are not.
  - `[low]` `[patch]` (blind) exit codes documented inconsistently — README states psql code in block, runner 1, prerequisites/invalid input 2.
  - `[medium]` `[patch]` (blind) `options=` in a DSN overrides PGOPTIONS (read-only guard bypass) → reject such DSNs (grouped with the edge-case duplicate); `statement_timeout` part: `[low]` `[reject]` — operator's call, adds a parameter.
  - `[low]` `[patch]` (edge) `grep -m1 | sed` under `set -e -o pipefail` kills the run when gradle lacks the line — `|| true`.
  - `[low]` `[patch]` (edge) signing fact says « workflow absent » when the file exists without the keystore line — three-way `if/elif`.
  - `[low]` `[reject]` (edge) space in `OUT_DIR` — documented limitation, unlikely, guard adds complexity.
  - `[medium]` `[patch]` (edge) DSN `options=` disables the read-only guard — reject with exit 2.
  - `[low]` `[reject]` (edge) libpq error text (host, db user) copied into the report — infra identifiers, not personal data; internal report.
  - `[low]` `[patch]` (edge) partial `*.hashes.csv` left without reminder on failure — reminder/removal in the failure branch.
  - `[low]` `[reject]` (edge) running q4/k4 directly without `-v` writes `:out_owners` — misuse outside the documented runner entry point.
  - `[low]` `[patch]` (edge) blank-after-strip phone hashed as `'+'` on both sides — exclude (grouped above).
  - `[medium]` `[patch]` (edge) unsalted md5 — grouped with the blind duplicate.
  - `[medium]` `[patch]` (edge) Bref phones without country code pass `^\+[0-9]{8,15}$` — add « préfixe pays connu » (237/241/235/243/221) count and a count of numbers without it; Q4 limitation noted.
  - `[low]` `[patch]` (edge) `superadmin` silently excluded from members/Q4 — explicit comment and count.
  - `[low]` `[patch]` (edge) Q5.c net = 0 — grouped with the blind duplicate.
  - `[low]` `[patch]` (edge) Q6.c TZ — grouped with the blind duplicate.
  - `[low]` `[patch]` (edge) Q7 deploy-day documents — grouped with the blind duplicate.
  - `[medium]` `[patch]` (edge) K1.b capacity counts DELETED tenants — `status <> 'DELETED'`.
  - `[medium]` `[patch]` (edge) K1.h reports DELETED tenants (schema dropped by 16-6) as « sans schéma » — exclude DELETED, report them separately.
  - `[low]` `[reject]` (edge) AC3 grep wording would match `deletedAt`/`createdAt` — fix would edit this build's spec; the Verification command is `^\s*`-anchored and passes.

## Auto Run Result

Status: done (build-auto run complete; the story key stays `in-progress` in sprint-status until the user runs the kit on the production replicas and closes the GHCR / Play Console checklist).

**Implemented:** read-only measurement kit `_bmad-output/planning-artifacts/research/fusion-mesures/` — 8 Bref SQL files (Q1–Q8) sharing one `SUBSCRIPTION_IN_FORCE` definition (`bref/_definitions.psql`), schema-aware guards for the objects that exist only on api-bref `develop` (`refresh_token`, `"user"."emailVerified"`), 3 Keevo `public`-schema files (tenants/users/memberships/activity/payments, phones, salted hash export split OWNER/EMPLOYEE), a bash runner (`run-mesures.sh`: read-only PGOPTIONS, ON_ERROR_STOP, input validation, per-run salt, Q4 intersection with 2×2 matrix, provenance header, Q13/Q9 repository facts via `merge-base`, operator checklist, INCOMPLET status), a committed synthetic validation harness (`validation/`: fake-number fixture + `validate-local.sh`, 34 assertions incl. a `main`-schema pass and the read-only guard), README, and the preparatory report `rapport-mesures-2026-11-02-preparatoire.md` (Q13: `develop` last reached `main` on 2026-10-26, a3faeb2, 7 later commits undeployed; Q9: repo declares `com.example.keevo`; both to be confirmed by the operator).

**Files changed:**
- `_bmad-output/planning-artifacts/research/fusion-mesures/README.md`, `.gitignore`, `run-mesures.sh` -- kit entry point and rules.
- `.../bref/_definitions.psql`, `q1`…`q8*.sql` -- Bref measurements.
- `.../keevo/k1-tenants-users-memberships.sql`, `k2-telephones.sql`, `k4-export-hashes-telephones.sql` -- Keevo measurements and hash export.
- `.../validation/fixture-bref-synthetique.sql`, `validate-local.sh` -- rerunnable local validation.
- `.../rapport-mesures-2026-11-02-preparatoire.md` -- committed preparatory report.
- `_bmad-output/implementation-artifacts/epic-18-context.md` -- recompiled for the fusion epic 18 (old V1 export context superseded; Q13 wording corrected).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` -- `18-1` in-progress (kit delivered, production run pending), `epic-18` in-progress.

**Review findings:** 47 findings (high 1, medium 16, low 27, false 3). Patched: 33 rows (grouped duplicates included) — develop-only objects guarded, catalog-based Q2, single IN_FORCE definition + runner parity warning, Q8.a aligned with `activePlanOf`, Q5.c net>0, known-prefix and blank-phone handling, salted hashes, Keevo OWNER/EMPLOYEE split, DELETED tenants excluded from capacity/schema checks, exact Keevo activity and payments/plan events/deletion log, deploy-day bucket, soft-delete labelling, UTC note, DSN `options=` rejection, input validation, `|| true` on extraction greps, signing three-way branch, hash cleanup on failure, provenance, merge-base Q13 + app.json Q9, README floor psql 11 + exit codes, committed validation harness, report/context/sprint-status wording. Deferred: none. Rejected (with reason): Q13 via GHCR and Q9 via Play Console (operator credentials; labelled + checklist), Q6 « restant » (no balance exists in api-bref), three activity proxies (no source column; 18.2 decides), Keevo scope beyond AC (no harm), epic-context replacement (V1 epic closed), spec untracked (committed at finalize), Java↔SQL phone parity harness (rules identical, beyond a direct fix), `OUT_DIR` with space (documented), libpq error text in report (infra identifiers only), direct psql use without `-v` (misuse), `statement_timeout` (operator's call), AC3 grep wording (would edit the spec).

**Follow-up review recommendation:** true — first pass patched 1 high and ≥ 2 medium entries. Specific unverified risk: the kit has only run against a TypeORM-generated `develop` schema (with the develop-only objects dropped for the `main` pass) and `keevo_dev`; the first run against a real production-restored api-bref `main` dump may still hit column/type differences not present in the entity model (tables created by `synchronize` before migrations existed). Patched counts by verdict: high 1, medium 14, low 18.

**Verification performed:** `bash -n run-mesures.sh` / `validation/validate-local.sh` OK; write-grep on `bref/` and `keevo/` empty; `KEEVO_DSN=<keevo_dev> validation/validate-local.sh` → 34 OK, 0 failures (develop pass, main pass, read-only guard refuses UPDATE, exit 2 on invalid inputs, exit 1 on unreachable DSN, no fixture data or salt in the report); earlier manual matrix run (report inspected: Q1 2 paid, Q3 1+1, Q5 1 shop + decimals, Q7 1 before develop, Q8 1 expired < 30 d, Q4 collision counted once); throwaway container removed, no `out/` left, `git status` shows only the kit files. Fixture numbers checked against `keevo_dev`: only the spec's matrix number (dev test employee) exists there.

**Residual risks:** production figures not produced (user action on replica); GHCR/Play Console/developer-account facts open; Q1 « actif < 90 j » and Q6 SMS « restant » are declared proxies pending 18.2.
