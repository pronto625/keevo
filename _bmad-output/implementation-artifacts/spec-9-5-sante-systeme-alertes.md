---
title: '9-5 (1/2) — System health, platform alerts feed and proactive owner alerts (super admin)'
type: 'feature'
created: '2026-10-13'
status: 'done'
baseline_revision: '658466af91431b33f1fcdcc6b7bb774e387b54a2'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-9-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-2-activation-plan-payant.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Toor learns about incidents from merchants: there is no system-health view (FR83), no unified alert feed (epic 9 story 9.5), and no proactive message to an owner whose devices stopped syncing (FR81 « tenant J+5 sans sync »).

**Approach:** Sample platform health every 5 minutes into a table, expose a « Santé système » page (status cards with thresholds, sticky incident banner on every admin page), an « Alertes » page with a computed, acknowledgeable feed, and a daily job that WhatsApps owners whose tenant has not synced for 5 days. The global push part of story 9.5 is a separate spec (2/2).

## Boundaries & Constraints

**Always:**
- Health sampling: Flyway table `public.platform_health_samples (sampled_at timestamptz pk, db_ok boolean, requests_total bigint, errors_5xx bigint, p50_ms int, p95_ms int, p99_ms int, db_active_connections int)`; a ShedLock job every 5 min writes one row with counters of the last 5 min taken from the Micrometer `MeterRegistry` (`http.server.requests` timer — enable `percentiles: 0.5,0.95,0.99` for it in `application.yml`; 5xx = `outcome=SERVER_ERROR`; delta of cumulative counts kept in memory) and Hikari `hikaricp.connections.active`; DB check = `SELECT 1 FROM public.tenants LIMIT 1`. Rows older than 90 days purged.
- `GET /api/v1/admin/health` (live): uptime 30 d = samples with `db_ok` / expected samples since max(30 d ago, first sample) (a missing sample = down), current p50/p95/p99 (last sample), error rate last 1 h = Σ5xx / Σrequests of the last 12 samples, active DB connections (live gauge), DB health (live check ✅/❌), sync refusals last 1 h = count of `sync_error_log` rows across tenant schemas created in the last hour (replaces the epic's « sync queue depth », which lives on devices). Each metric has a `breached` flag: error rate > 2 %, p99 > 2000 ms, sync refusals > 1000/h, DB ❌, uptime < 99 %.
- `GET /api/v1/admin/health/status` (cheap, polled every 60 s by the dashboard layout): `{ incident: boolean, breachedMetrics: [label] }` → sticky red banner on all admin pages: « 🔴 Incident en cours — <métrique> dépasse le seuil. Vérifiez les journaux du serveur. ».
- Alerts feed `GET /api/v1/admin/alerts?includeHandled=false`, computed on each call, each alert `{ key, severity HIGH|MEDIUM|LOW, title, description, occurredAt, handled }` with a deterministic `key` (e.g. `feedback:<feedbackId>`), sorted newest first: HIGH-priority feedback (`public.feedback.priority = 'HIGH'`, last 30 d) — HIGH; tenants `DELETION_PENDING` with days left — MEDIUM; Premium/Trial subscriptions expired but still not FREE (integrity, `expires_at < now()` and plan ≠ FREE) — HIGH; Premium expiring within 7 days (from 9-2) — LOW; WhatsApp report delivery failures > 10 in the last hour (`reports.delivery_status = 'FAILED'`, any tenant) — MEDIUM; tenants whose last device push is older than 5 days (`public.user_sync_state`, tenants with ≥ 1 device) — MEDIUM; health metric breached now — HIGH. Description texts in French with tenant names, never ids/codes. `POST /api/v1/admin/alerts/{key}/handle` stores `public.admin_alert_acks (alert_key pk, handled_at, handled_by)`; handled alerts hidden unless `includeHandled=true`. Keys are opaque to the UI (used only in the request path, never displayed).
- FR81 proactive owner alert: daily ShedLock job (10:00 WAT) WhatsApps the owner of each tenant whose devices have all been silent ≥ 5 days: « Bonjour, votre boutique Keevo n'a pas été synchronisée depuis <N> jours. Ouvrez l'application avec une connexion internet pour sauvegarder vos ventes. » — at most once per silence streak (`public.sync_silence_notices (tenant_id pk, notified_at, last_push_at_at_notice)`; reset when a newer push exists). Goes through `OwnerRecipientResolver` + `WhatsAppPort`, failures logged only.
- All admin endpoints guarded by `CurrentActor.requireSuperAdmin()`.
- Dashboard: `/sante` page (status card grid, red card + icon on breach, « Actualiser »), `/alertes` page (feed with severity badges « Haute / Moyenne / Basse », « Marquer comme traité », toggle « Afficher les alertes traitées »), sidebar entries « Santé système » and « Alertes » (with count badge of unhandled HIGH), banner in `AdminLayout`. French labels only, no ids.

**Never:** No external monitoring SaaS; no CloudWatch reference in texts; no change to sync engine behaviour.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Healthy | samples all ok, 0.5 % errors, p99 800 ms | no breach, no banner | — |
| Error spike | last hour 3 % 5xx | errorRate breached, banner, HIGH alert | — |
| Server down 2 h | 24 missing samples in 30 d | uptime reflects missing samples as down | — |
| No samples yet | fresh deploy | uptime « — » (null), no breach on uptime | — |
| DB check fails | `SELECT 1` throws | DB ❌, breached, endpoint still 200 | — |
| Alert handled | POST handle key | disappears from default feed, visible with includeHandled | unknown key → 204 idempotent |
| Silent tenant 6 days | last push 6 d ago | one WhatsApp; next day nothing; after a new push and 5 more silent days → new WhatsApp | no owner phone → skip + log |
| Tenant never synced | no device row | no WhatsApp, no alert | — |
| Non-admin | OWNER | 403 `FORBIDDEN` | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/resources/application.yml:69-76` -- actuator exposure `health,info,metrics`; add timer percentiles config.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/scheduling/` -- ShedLock scheduler pattern (`SubscriptionExpiryScheduler`).
- `keevo/backend/src/main/resources/db/migration/V7__feedback.sql` -- `public.feedback.priority`; `admin/feedback/**` for feedback reads.
- `keevo/backend/src/main/java/com/keevo/shared/infrastructure/persistence/TenantSchemaProvisioner.java` -- `sync_error_log`, `reports.delivery_status`, `subscriptions` per schema.
- `public.user_sync_state` (V1 baseline) -- device last push per tenant schema (`tenant_id` = schema name).
- `keevo/backend/src/main/java/com/keevo/admin/sync_monitoring/**` -- cross-schema pattern; `identity/auth/domain/port/out/OwnerRecipientResolver.java`, `messaging/whatsapp/domain/port/out/WhatsAppPort.java`.
- `keevo/dashboard/src/components/layout/{AdminLayout,Sidebar}.tsx` -- banner + entries; `lib/labels.ts`.

## Tasks & Acceptance

**Execution:**
- `db/migration/V20__platform_health_alerts.sql` (next free version) -- `platform_health_samples`, `admin_alert_acks`, `sync_silence_notices`.
- `admin/platform/**` (health: sampler job, service, controller, DTOs) and `admin/alerts/**` (feed service, controller, acks adapter) -- per Boundaries.
- `admin/alerts` or `messaging/whatsapp` -- `SyncSilenceAlertService` + scheduler (FR81).
- Tests: sampler (delta computation, DB failure), health service (uptime with gaps, thresholds), alerts (each source, keys stable, ack filtering), silence job (once per streak, reset, skip without phone), controllers (403, `jsonPath`).
- Dashboard: `types/health.ts`, `types/alerts.ts`, `lib/api/admin-{health,alerts}.ts`, hooks, `components/health/*`, `components/alerts/*`, `app/sante/page.tsx`, `app/alertes/page.tsx`, `AdminLayout.tsx` banner, `Sidebar.tsx`; component tests (red card, banner shown/hidden, mark handled, severity labels, no ids).

**Acceptance Criteria:**
- Given a breached threshold, when Toor opens any admin page, then the red incident banner is visible within 60 s.
- Given the alert feed, when Toor marks an alert handled, then it no longer shows by default and its trace survives a reload.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass
- verdicts: 45 findings — high 2, medium 11, low 23, false 2, maybe-false 0 (+ intent rows descriptive)
- findings:
  - `[high]` `[patch]` (edge) alert feed 500 when the DB is down, so the database alert is never visible — sources isolated per try/catch, acks unreadable = none handled.
  - `[medium]` `[patch]` (edge) `/health` 500 when a DB-derived read throws after a passing check — caught, DB ❌, metrics null.
  - `[medium]` `[patch]` (edge) expired-not-FREE alert fires on every normal nightly expiry — `expires_at < now − 26 h`.
  - `[medium]` `[patch]` (edge) non-ACTIVE expired rows alert forever — `status = 'ACTIVE'`.
  - `[medium]` `[patch]` (edge) send then recordNotice failure re-sends daily — notice recorded first, deleted on send failure.
  - `[low]` `[patch]` (edge) non-DataAccess exception aborts the daily silence run — per-company catch.
  - `[low]` `[patch]` (edge) multi-instance sampler deltas wrong — single-instance assumption documented (javadoc + checklist).
  - `[low]` `[patch]` (edge) expected sample period vs configurable cron — fixed 5-minute period.
  - `[low]` `[patch]` (edge) expiring alert occurredAt in the future — clamped to now.
  - `[low]` `[patch]` (edge) excerpt cut inside a surrogate pair — code-point cut.
  - `[low]` `[patch]` (edge) new device without push flags the tenant silent — recent null-push devices count as active.
  - `[low]` `[patch]` (edge) system tenant feedback not excluded — excluded.
  - `[low]` `[patch]` (edge) acks grow forever — 90-day purge + cleared-condition removal.
  - `[medium]` `[patch]` (edge) badge polls the full feed per tab — `/alerts/count` + 30 s single-flight server cache.
  - `[medium]` `[patch]` (edge, claim) banner within 60 s not guaranteed — status polled every 30 s.
  - `[medium]` `[patch]` (edge, claim) handled alert reappears hourly — condition keys without time bucket, ack removed when the condition clears.
  - `[medium]` `[patch]` (blind) expired-plan false HIGH — same as edge row 3.
  - `[medium]` `[patch]` (blind) status ignored — same as edge row 4.
  - `[high]` `[patch]` (blind) database alert unreachable in the feed — same as edge row 1.
  - `[medium]` `[patch]` (blind) banner hidden when status unreadable — « État du serveur inconnu ».
  - `[low]` `[patch]` (blind) wrong « dépasse le seuil » wording for uptime/DB — per-metric phrases.
  - `[medium]` `[patch]` (blind) silence notice recorded although WhatsApp unconfigured — skipped without recording.
  - `[low]` `[patch]` (blind) sampler state per instance — same as edge row 7.
  - `[low]` `[patch]` (blind) percentile decay window shorter than the sample period — expiry 5 m, buffer 5.
  - `[medium]` `[patch]` (blind) feed cost / badge polling — same as edge row 14.
  - `[low]` `[patch]` (blind) acks never purged — same as edge row 13.
  - `[medium]` `[patch]` (blind) health alerts back every hour — same as edge claim row.
  - `[low]` `[patch]` (blind) HIGH feedback handled in Alertes stays unhandled in the inbox — handling the alert marks the feedback handled.
  - `[low]` `[patch]` (blind) deletion grace magic number — shared constant.
  - `[low]` `[patch]` (blind) `dbActiveConnectionsBreached` always false — 80 % of maximumPoolSize.
  - `[low]` `[patch]` (blind) Micrometer adapter / schedulers / probe timeout untested — adapter test (registry), probe/adapters rethrow tests; scheduler wiring rejected (lock coverage test + services tested).
  - `[low]` `[patch]` (blind) double padding — removed.
  - `[false]` `[reject]` (blind) handled_by never shown — single super admin in V1 (epic context: Toor is the sole operator).
  - `[low]` `[patch]` (gap) Micrometer adapter and yml percentiles never exercised — `MicrometerRuntimeMetricsAdapterTest`.
  - `[low]` `[patch]` (gap) rethrow branches untested — Mockito adapter tests.
  - `[low]` `[patch]` (gap) alert texts for 4 metrics and 2 deletion branches — tests added.
  - `[low]` `[patch]` (gap) dashboard API clients untested — `admin-alerts.test.ts`, `admin-health.test.ts`.
  - `[false]` `[reject]` (intent) R1 vs R2 surfaces — descriptive; outage paths now tested end to end at service level; key-lifetime reading now (a) condition-stable.

## Verification

**Commands:**
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.admin.**,com.keevo.architecture.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/backend && MAVEN_OPTS=-Xmx1g mvn -q -o test -Dtest='com.keevo.messaging.**,com.keevo.shared.infrastructure.scheduling.**' -DforkCount=1 -DargLine=-Xmx1536m` -- expected: 0 failures
- `cd keevo/dashboard && npm run lint && npx tsc --noEmit && npm test && npm run build` -- expected: all succeed

## Auto Run Result

Status: done

**Summary:** Story 9-5 part 1 delivered. Platform health sampled every 5 min (V20 `platform_health_samples`, Micrometer percentiles with a 5-min window, 5xx, Hikari connections vs pool size, DB probe, sync refusals) and exposed on `/api/v1/admin/health` (+ cheap `/status`, single-flight 30 s cache) with thresholds; « Santé système » page and an incident banner on every admin page (polled every 30 s, « État du serveur inconnu » when unreachable). « Alertes » feed computed from unhandled HIGH feedback, pending deletions, expired-but-not-downgraded plans (26 h grace, ACTIVE only), expiring plans, WhatsApp delivery failures, silent companies (5 days) and health breaches — each source isolated so the feed survives a DB outage; acks per condition (cleared when the condition ends, 90-day purge), handling a feedback alert also handles the feedback; count endpoint for the badge. FR81: daily 10:00 WAT WhatsApp to owners of companies silent for 5 days, once per silence streak, not recorded when WhatsApp is unconfigured, retried on failure.

**Files changed:** backend `V20__platform_health_alerts.sql`, `admin/platform/**`, `admin/alerts/**`, `identity/auth/domain/model/AccountDeletionPolicy.java`, `AccountDeletionService`, `TenantSchemaSyncService` global tables, `application.yml` metrics, `docs/DEPLOYMENT_CHECKLIST.md` §9 (single-instance sampling); tests (unit, adapters, controller, Postgres IT, `SchedulerLockCoverageTest`); dashboard `types/{health,alerts}.ts`, `lib/api/admin-{health,alerts}.ts`, `hooks/{useHealth,useAlerts}.ts`, `components/{health,alerts}/*`, `app/{sante,alertes}/page.tsx`, `AdminLayout.tsx`, `Sidebar.tsx`, `lib/labels.ts` + tests.

**Review:** 45 findings — patched 2 high (feed unusable during a DB outage; same root as the unreachable database alert), 11 medium and 23 low entries (grouped); deferred 0; rejected 3.

**Follow-up review recommended:** true — two highs were patched; unverified risk: outage behaviour is tested with throwing mocks at service level, not with a real database outage against the running app.

**Verification:** dashboard lint, tsc, `npm test` (48 files, 221 tests), build — green. Backend admin 415, architecture 6, messaging 126, shared 525, identity 373, feedback 44 — 0 failures/errors.

**Residual risks:** health sampling assumes a single backend instance (documented); feed computation remains O(companies) every 30 s at most.

