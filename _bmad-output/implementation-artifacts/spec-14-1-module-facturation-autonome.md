---
title: '14.1 — Standalone Invoicing module (domain, module-owned tables, CRUD, @RequiresPermission)'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: 'b32790f652299b298bfc299577a78addd46330a8'
route: 'dispatch'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/spec-socle-modulaire.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-9-keevomodule-crm-reporting.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-13-10a-gating-all-endpoints.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Keevo has no invoicing. Story 14.1 (MOD-FR7, MOD-FR17) must deliver the first paid module that works **alone**: a tenant with only `invoicing` (+ core) creates, edits, lists and issues invoices without pos / crm / catalog. It is also the pattern for every future module: its tables exist only through its own migrations, and it introduces per-module permissions (`@RequiresPermission`, routed from 13.5 / 13.10a).

**Approach:** New top-level Spring Modulith module `com.keevo.invoicing` (hexagonal, like crm) with manifest `keevo/modules/invoicing/module.json`, module-owned tables in `db/modules/invoicing/V1__invoicing.sql` accessed through **JDBC adapters (no JPA entity)**, REST under `/api/v1/invoices` and `/api/v1/invoicing/settings`, gated by `@RequiresEntitlement("invoicing")` + a new `@RequiresPermission`. Invoice content follows Cameroon's mandatory mentions (CGI art. 150 (5), restated by the 2025 finance law). Backend only — the app screens are 15.5.

**Decisions (autonomous run; user delegated all choices 2026-10-28; legal research done 2026-10-30 at the user's request):**
- **Legal content (CGI art. 150 (5))**: seller name / legal name, full address, NIU, RCCM; invoice date; client full identity (+ NIU for B2B, optional); nature and detail per line; price excl. tax; tax rate and amount; total incl. tax; per-line « Exonérée » / « Prise en charge État » when applicable. Non-VAT-registered sellers (impôt libératoire, régime simplifié) print « TVA non applicable » and invoice without VAT. Numbering unique, continuous, chronological (OHADA practice); issued invoices are never deleted (10-year retention).
- **Seller profile** = module-owned single-row table `invoicing_settings` (legal name, address, phone, NIU, RCCM, VAT-registered flag, VAT rate default 19.25, prices-include-VAT default true, payment details / footer free text). **Snapshotted onto the invoice at issue** (later edits never change an issued invoice).
- **Lifecycle**: `DRAFT` (editable, deletable, no number) → `ISSUED` (number + issue date + seller snapshot assigned atomically, immutable) → `PAID` (paidAt, optional payment method label) ; `ISSUED` → `CANCELLED` (reason required, number kept). A `PAID` invoice is not cancellable here (credit notes routed). No other transition.
- **Numbering**: gapless per tenant per calendar year (Africa/Douala), `FAC-<yyyy>-<000001>`, via a counter table `invoice_counters(year, last_number)` incremented with `UPDATE … RETURNING` in the issue transaction (rollback ⇒ no gap); `UNIQUE(number)`.
- **Amounts**: XAF integers (`long`); quantity `numeric(12,3)` > 0; unit price ≥ 0. Line treatment `STANDARD` (settings rate) | `EXEMPT` (« Exonérée », 0 %) | `STATE_BORNE` (« Prise en charge État », 0 %); if the seller is not VAT-registered every line is computed at 0 % and the invoice carries `vatMention = "TVA non applicable"`. If `pricesIncludeVat`: line TTC = round(qty × price), HT = round(TTC / (1 + rate)), VAT = TTC − HT; else HT = round(qty × price), VAT = round(HT × rate), TTC = HT + VAT (HALF_UP to the franc). Totals = sums of lines. Computed in the domain, recomputed on every draft change and frozen at issue.
- **Client**: `clientName` required; `clientNiu`, `clientAddress`, `clientPhone` optional; `clientId` (UUID, nullable, **no FK**) reserved for 14.3.
- **Issue preconditions**: ≥ 1 line, client name, settings legal name present (else 422 `INVOICE_NOT_ISSUABLE` with French reason). Missing seller NIU / RCCM / address do **not** block but the response carries `complianceWarnings` (French sentences, e.g. « Le NIU de votre commerce n'est pas renseigné : la facture ne sera pas déductible pour un client professionnel. »).
- **Permissions**: manifest `permissions: ["INVOICE_CREATE","INVOICE_VIEW","INVOICE_SEND","INVOICE_MANAGE"]` (`INVOICE_MANAGE` added: settings + cancel are owner decisions). Role mapping declared by the module: OWNER = every permission of the module; EMPLOYEE = `INVOICE_VIEW`, `INVOICE_CREATE`, `INVOICE_SEND`; SUPER_ADMIN = none. Mapping exposed through `KeevoModule` (e.g. `default Set<String> employeePermissions()`), validated at boot (⊆ manifest permissions; every `@RequiresPermission` value declared by some manifest).
- **`@RequiresPermission`** (in `shared.module`, next to `@RequiresEntitlement`): enforced through Spring method security (`AuthorizationManagerBeforeMethodInterceptor` with a custom `AuthorizationManager`), ordered **after `@PreAuthorize` and before `EntitlementAspect` (1000)**; denial → existing 403 `FORBIDDEN` (« Vous n'avez pas les droits nécessaires »).
- **Tables appear lazily and only for tenants using the module**: no JPA entity (so nothing in `public`, nothing copied by `TenantSchemaSyncService`). Generic socle fix: on a passing `@RequiresEntitlement` check for a module that has migrations, ensure it is provisioned in the tenant schema (`ModuleProvisioner.ensureProvisioned(schema, id)`, in-memory cache per (schema, module, latest version) so warm calls do no DB work; concurrent first calls safe). Covers existing PREMIUM/trial tenants with no row (transition rule) that were never provisioned.
- **Socle bug fix**: `ModuleProvisionerService.recordVersion` inserts an `ACTIVE`/`FREE` row (no expiry) when none exists — for a **paid** module that would grant it forever (tenant creation provisions it for the trial). Paid module + no row ⇒ insert nothing (Flyway history is the record); free modules unchanged.
- **Events/audit**: `InvoiceCreated/Issued/Paid/Cancelled` events in `shared/event/catalog/invoicing`, audited by `AuditEventListener` with French labels (« Facture créée », …) and the invoice number, never a UUID, in the detail.
- **Already done (epic item « sync pull gating », 12.7)**: `SyncPushService.pull` already skips providers whose module is DISABLED (`DeltaEntityProvider.moduleId()`); tick it with this evidence. No invoice sync provider / sync ops here (offline app flows = 15.5, routed).

## Boundaries & Constraints

**Always:** works with pos, crm and catalog all DISABLED; issued invoices immutable and never deleted; gapless numbering under concurrency; every new handler annotated (entitlement + permission) and listed in `docs/entitlements/endpoints.md`; French user messages, no UUID or raw code in messages / audit detail; tenant SQL via `TenantSchema.validate`; Modulith `verify()` + ArchUnit + prod-classpath green; no change to existing tenants' tables or rows at deploy or boot.

**Never:** no JPA entity / `public` table for invoicing; no import of pos/crm/catalog code; no PDF / WhatsApp (14.2), CRM picker (14.3), sale bridge (14.4), checkout slot (14.5); no Flutter; no sync handlers/providers; no credit notes; no DGI e-invoicing / homologation claim; no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Standalone create | tenant with invoicing enabled, pos/crm/catalog DISABLED; POST draft (2 free lines, client name) | 201 DRAFT with computed totals; tables created on first call | — |
| Update / delete draft | PUT / DELETE on DRAFT | 200 / 204 | on ISSUED+: 409 `INVOICE_NOT_EDITABLE` |
| Issue | DRAFT valid | `FAC-2026-000001`, seller snapshot, issueDate; next → `…000002` | no line / no client / no legal name: 422 `INVOICE_NOT_ISSUABLE` |
| Concurrent issue | 20 drafts issued in parallel | numbers 1..20, no gap, no duplicate | — |
| Rolled-back issue | failure after counter increment | counter not consumed | — |
| VAT registered, TTC prices | 1 × 11 925 STANDARD 19.25 % | HT 10 000, VAT 1 925, TTC 11 925 | — |
| Exempt line | EXEMPT | 0 % VAT, label « Exonérée » | — |
| Not VAT-registered | settings vatRegistered=false | all lines 0 %, `vatMention` « TVA non applicable » | — |
| Missing NIU/RCCM | issue | 200 + `complianceWarnings` | — |
| Pay / cancel | ISSUED → pay; ISSUED → cancel(reason) | PAID / CANCELLED, number kept | PAID → cancel: 409 `INVOICE_INVALID_TRANSITION`; blank reason: 422 |
| Employee | EMPLOYEE create/issue/pay | 200 | cancel or PUT settings: 403 `FORBIDDEN` |
| Super-admin | any invoicing endpoint | — | 403 `FORBIDDEN` |
| Free tenant / module disabled | any endpoint | — | 403 `MODULE_NOT_ENTITLED` (no tables created) |
| Read-only (grace) | GET / POST | GET 200 | POST 403 `MODULE_READ_ONLY` |
| Role denied + module disabled | EMPLOYEE cancel, module disabled | — | 403 `FORBIDDEN` (permission evaluated first) |
| Unknown id | GET /invoices/{random} | — | 404 `INVOICE_NOT_FOUND` |
| New trial tenant | registration | invoicing tables provisioned, **no** `tenant_modules` row for invoicing | — |
| Module deactivated | super-admin deactivates | data kept; endpoints 403 | — |

</intent-contract>

## Code Map

Paths relative to `keevo/backend/`.
- Socle: `src/main/java/com/keevo/shared/module/KeevoModule.java` (add permission mapping default), `RequiresEntitlement.java`, `infrastructure/EntitlementAspect.java` (ORDER 1000; add ensure-provisioned after the check), `infrastructure/RequiresEntitlementValidator.java` (pattern for a boot validator), `ModuleManifest.java` / `ModuleManifestLoader.java` (`permissions` parsed, unused), `application/ModuleProvisionerService.java` (`provision` ~L93, `recordVersion` ~L131 → `JdbcTenantModuleStateStore.INSERT_ACTIVE_FREE_SQL` L76, `provisionActiveModules` ~L198), `adapter/out/persistence/FlywayModuleMigrations.java` (`hasMigrations` L123, `migrate` L141-169: unqualified SQL, `flyway_history_<id>`), `application/ModuleStateRules.java` (transition rule L43-101).
- Tenant creation calls provisioning: `identity/auth/application/service/TenantFactory.java:110`.
- Security: `shared/infrastructure/security/SecurityConfig.java:52` (`@EnableMethodSecurity`), `CurrentActor.java`, `identity/api/Role.java`, `JwtAuthFilter.java:258` (`ROLE_<role>` authority); 403 mapping `shared/infrastructure/web/GlobalExceptionHandler.java` L228; domainCode→status switch ~L316, `FR_MESSAGES` L51; `shared/domain/exception/ErrorCode.java`.
- Reference module: `crm/module/CrmModule.java`, `crm/package-info.java`, `crm/contact/**` (controller/use case/domain record/port layout), `db/modules/crm/V1__crm_baseline.sql` (style: `SET LOCAL lock_timeout`, `IF NOT EXISTS`, unqualified names).
- Envelope/pagination: `shared/infrastructure/web/ApiResponseWrapper.java` (`paginated` L55); pattern `inventory/counting/adapter/in/rest/InventorySessionController.java` ~L131.
- Audit: `shared/infrastructure/web/AuditEventListener.java` (~L330 pattern), `shared/event/catalog/contact/ClientCreatedEvent.java`.
- JDBC tenant rule (a): `TenantSchema.validate`; `src/test/java/com/keevo/architecture/KeevoArchitectureRules.java`.
- Sync pull gating evidence: `sync/sync/application/service/SyncPushService.java` L506-518, `sync/sync/domain/port/in/DeltaEntityProvider.java`.
- Tests to extend (18th module): `architecture/RequiresGraphAcyclicityTest.java` L37/L54; `shared/module/ModuleCatalogBootIntegrationTest.java` L46/L49; `shared/module/ClasspathModuleManifestsTest.java` L25-66 (paid list, versions 1.0.0, provides/fills rules, BUSINESS count — invoicing is `premium`); `shared/module/ModuleCatalogSeederPostgresIntegrationTest.java` L115/L126-134/L168/L211; `shared/module/ModuleRegistryPostgresIntegrationTest.java` L47-52; `architecture/ApplicationModulesBaselineTest.java` (`EXPECTED_MODULES` 13 → add `invoicing`); `architecture/EndpointEntitlementCoverageTest.java` + `docs/entitlements/endpoints.md` (regen `-Dkeevo.entitlements.regenerate=true`).
- Test bases: `SharedPostgresContainer`, `PostgresIntegrationTest`, `FullContextIntegrationTest`; JWT pattern `shared/module/PremiumGatingFullContextTest.java` ~L40-75 (`tenantFactory.create()`, `jwtTokenProvider.generateAccessToken`); slice pattern `shared/module/infrastructure/EntitlementGatingSliceTest.java`; module migration pattern `crm/module/CrmMigrationPostgresIntegrationTest.java`. Existing fake-catalog fixtures already use the id `invoicing` (`TestModuleCatalogs.java`) — keep them working.

## Tasks & Acceptance

**Execution:**
- [x] `src/main/resources/keevo/modules/invoicing/module.json` -- manifest (id `invoicing`, `1.0.0`, « Facturation », `premium`, `free:false`, requires `[core]`, enhances `[pos,crm]`, needs `["ClientLookupPort?"]`, publishes the 4 invoice events, permissions ×4, routes `/invoicing`, `/invoicing/:id`) + update the 18th-module tests listed above.
- [x] `src/main/resources/db/modules/invoicing/V1__invoicing.sql` -- `invoicing_settings`, `invoices` (status CHECK, number UNIQUE nullable, client + seller snapshot columns, totals, vat_mention, issued_at/paid_at/cancelled_at/reason, created_by, timestamps), `invoice_items` (position, description, quantity, unit_price, vat_treatment CHECK, vat_rate, ht/vat/ttc), `invoice_counters`; indexes on (status, created_at).
- [x] `shared/module/` -- `RequiresPermission`, permission authorization manager + interceptor (order between PreAuthorize and 1000), `KeevoModule` employee-permission mapping, boot validation; `ensureProvisioned` (cached) called by `EntitlementAspect`; `recordVersion` no row for paid modules. Unit + slice tests (order: role → permission → entitlement).
- [x] `src/main/java/com/keevo/invoicing/**` -- `package-info` (`allowedDependencies = {"shared"}`), `module/InvoicingModule`, domain (`Invoice`, `InvoiceLine`, `InvoiceStatus`, `VatTreatment`, `InvoicingSettings`, totals calculator, transitions), use cases, JDBC repository adapters (schema via `TenantSchema.validate`), REST controllers + DTOs (French validation messages), `ErrorCode` + `GlobalExceptionHandler` entries (`INVOICE_NOT_FOUND` 404, `INVOICE_NOT_EDITABLE`/`INVOICE_INVALID_TRANSITION` 409, `INVOICE_NOT_ISSUABLE` 422).
- [x] `shared/event/catalog/invoicing/*` + `AuditEventListener` -- 4 events audited with French labels and invoice number.
- [x] Tests -- domain unit tests (every VAT / rounding row of the matrix, transitions); `InvoicingMigrationPostgresIntegrationTest` (fresh DB, real provisioner); full-context test with real JWTs covering every matrix row incl. standalone tenant (pos/crm/catalog DISABLED rows), concurrency numbering (20 parallel issues), new trial tenant has tables and no invoicing row, lazy provisioning of an un-provisioned PREMIUM tenant; regenerate `docs/entitlements/endpoints.md`.
- [x] `_bmad-output/planning-artifacts/epics-refonte-modulaire.md` -- tick 14.1's `@RequiresPermission` item and the Epic 14 « sync pull gating » item with evidence.

**Acceptance Criteria:**
- Given a tenant with only core + invoicing enabled, when the owner creates, edits, issues, pays and lists invoices through REST, then everything works and no pos/crm/catalog code or table is touched.
- Given the manifest, when inspected, then requires `["core"]`, enhances `["pos","crm"]`, needs `["ClientLookupPort?"]`, permissions include `INVOICE_CREATE`, `INVOICE_VIEW`, `INVOICE_SEND`.
- Given an issued invoice, when its JSON is read, then it contains every CGI art. 150 (5) element available (seller identity snapshot, client identity, lines with HT/rate/VAT, totals HT/VAT/TTC, per-line exemption label or « TVA non applicable »).
- Given each backend test package run one at a time + `-Pprod-classpath`, then all pass.

## Implementation Notes

**Socle.** `RequiresPermission` (all-of) in `shared.module`; `PermissionMethodSecurityConfig` registers an `AuthorizationManagerBeforeMethodInterceptor` (method- or class-level pointcut) at `PRE_AUTHORIZE + 1` (< `EntitlementAspect.ORDER` 1000) with `PermissionAuthorizationManager` (roles from `ROLE_*` authorities, fail-closed). `application.ModulePermissions`: OWNER = every manifest permission, EMPLOYEE = union of `KeevoModule.employeePermissions()` (new default method, empty), SUPER_ADMIN none; boot validation: employee permissions ⊆ the module's own manifest, a permission declared by one manifest only. `RequiresPermissionValidator` (BeanPostProcessor): every value declared by a manifest, else startup fails. `ModuleProvisioner.ensureProvisioned(schema, id)` in `ModuleProvisionerService`: no-op for invalid/platform schema, unknown module, module without migrations; cache key (schema, module, latest shipped version) in memory, per-(schema, module) lock; cold path = `ModuleMigrationPort.currentVersion` (one history read; `FlywayModuleMigrations` reads `flyway_history_<id>` on a pooled connection, 0-baseline ignored) vs `latestVersion` (classpath scan, cached) → `provision` only when behind; any failure → `DomainException(SERVICE_UNAVAILABLE)` (503, French), ERROR log with the tenant code, not cached (next call retries). `EntitlementAspect` calls it after a passing check (`ObjectProvider`, absent in slices). `recordVersion`: no row + paid module → nothing inserted.

**Module.** `com.keevo.invoicing` (`allowedDependencies = {"shared"}`): `module.InvoicingModule` (employee = VIEW, CREATE, SEND); domain records `Invoice` (immutable transitions), `InvoiceLine`, `LineInput`, `ClientInfo`, `SellerSnapshot`, `InvoicingSettings`, `InvoiceTotals`, `VatCalculator`, enums `InvoiceStatus` (French labels) / `VatTreatment` (mentions); ports `InvoiceRepository` / `InvoicingSettingsRepository`; JDBC adapters (`InvoicingTables.qualified` → `"kv_xxxxxx".<table>` via `TenantSchema.validate`); use cases `ManageDraftInvoiceUseCase`, `InvoiceLifecycleUseCase` (issue = row `FOR UPDATE` + `INSERT … ON CONFLICT (year) DO UPDATE … RETURNING` counter in the same transaction), `InvoiceQueryUseCase`, `InvoicingSettingsUseCase`; controllers `InvoiceController` (`/api/v1/invoices`: POST, GET list paginated `?status=&page=&size≤100`, GET/PUT/DELETE `/{id}`, POST `/{id}/issue|pay|cancel`) and `InvoicingSettingsController` (`/api/v1/invoicing/settings` GET/PUT). Permission map: view → VIEW; create/edit/delete draft → CREATE; issue, pay → SEND; cancel, PUT settings → MANAGE. Every invoice response carries `complianceWarnings` (draft: current settings; issued: snapshot). ErrorCodes `INVOICE_NOT_FOUND` 404, `INVOICE_NOT_EDITABLE` / `INVOICE_INVALID_TRANSITION` 409 (`{status}` French label), `INVOICE_NOT_ISSUABLE` 422 (`{reason}`). Events `shared.event.catalog.invoicing.Invoice{Created,Issued,Paid,Cancelled}Event`; `AuditEventListener` writes `INVOICE_*` (entity `Invoice`, label « Facture FAC-… » / « Brouillon — <client> »); `AuditLabels` + `keevo/contracts/export-datasets.json` regenerated (actions « Facture créée / émise / payée / annulée », entity « Facture », fields `invoiceNumber`, `clientName`, `paymentMethod`).

**Decisions.** (1) `vat_registered` defaults to **false** (no VAT charged until the owner declares registration — the safe legal default for impôt libératoire shops). (2) Drafts may have 0 lines (blocked only at issue). (3) Counter as an upsert (`INSERT … ON CONFLICT DO UPDATE … RETURNING`) instead of a bare `UPDATE … RETURNING`, so the first number of a year needs no seed row; same lock semantics. (4) Lazy provisioning is generic but **skips legacy modules with an existing-schema baseline (crm)** — orchestrator change after implementation: their tables still come from the tenant provisioner until 16.5, so an update writes no `tenant_modules` row nor history table for them (test `ensureProvisionedSkipsLegacyModules`). (5) `EndpointEntitlementCoverageTest` / `docs/entitlements/endpoints.md` gained a Permission column (186 handlers, 100 gated).

**Verification (2026-10-30).** One package at a time, all rc=0, 0 failures: invoicing 33, architecture 32, shared 1012 (first run: `EntitlementAspectFullContextTest` 2 errors — a tenant row without its schema made lazy provisioning answer 503; fixed: `ModuleMigrationPort.schemaExists`, a missing schema is skipped and not cached), identity 437, sync 533, crm 127, admin 517, reporting 401, catalog 247, commerce 251, inventory 376, store 60, messaging 168, subscription 70, feedback 52; `mvn verify -Pprod-classpath -Dtest='com.keevo.architecture.**'` rc=0. Not done: the live check on :4500 / `kv_zggt7g` (no backend started in this run), nothing committed.

**App labels (orchestrator, after implementation).** The audit labels are a shared contract: `audit_labels.dart` + `AuditPresenter` mirror the invoice actions / noun / fields (app 2.0.5+77) so `local_export_contract_test.dart` stays green — label mirrors only, no invoicing screen (15.5).

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 35 findings — high 0, medium 3, low 20, false 12, maybe-false 0
- findings:
  - `[low]` `[reject]` (intent) "only invoicing active" approximated: other premium modules stay enabled in `standaloneLifecycle` — pos/crm/catalog DISABLED is the meaningful isolation; Modulith `allowedDependencies={"shared"}` proves no import; other premium modules cannot influence invoicing.
  - `[false]` `[reject]` (intent) 4 permissions instead of 3 — intent-contract decision (`INVOICE_MANAGE` for settings / cancel); the 3 required ones are present.
  - `[false]` `[reject]` (intent) counter table instead of `invoice_number_seq`, CANCELLED status — intent-contract decisions (a sequence leaves gaps on rollback; the story asks to handle gaps).
  - `[false]` `[reject]` (intent) lazy provisioning is socle-wide — intent-contract decision (covers PREMIUM tenants never provisioned).
  - `[low]` `[reject]` (intent) spec note said crm is adopted lazily, code skips it — stale note corrected by the orchestrator (Implementation Notes decision 4); no code change.
  - `[false]` `[reject]` (intent) no `Permission` enum — the item asks for a permission model mapped to roles; manifest-declared permission strings + `ModulePermissions` role mapping is that model, validated at boot.
  - `[false]` `[reject]` (intent) sync pull gating has no test in the diff — pre-existing behaviour with its tests (`SyncPullServiceTest.story1310a_*`, `PremiumGatingFullContextTest`); the item is a tick with evidence.
  - `[false]` `[reject]` (intent) no merchant UI — screens are Story 15.5 by the epic plan.
  - `[low]` `[reject]` (intent) spec « Not done / routed » contradicts the app label mirrors — stale note corrected by the orchestrator.
  - `[low]` `[reject]` (blind) spec says no Flutter yet app files changed — same stale note (corrected); the mirrors are contract labels, not invoicing screens.
  - `[low]` `[reject]` (blind) unrelated reflow in `audit_presenter.dart` — `dart format` output of the edited file, no behaviour change.
  - `[medium]` `[patch]` (blind) `InvoiceLifecycleUseCase.issue` fell back to `draft.issue("-")` — patched: domain `Invoice.assertIssuable` called before numbering; sentinel branch removed.
  - `[medium]` `[patch]` (blind) `JdbcInvoiceRepository.update` rewrote all columns and re-inserted lines on pay/cancel — patched: one targeted UPDATE per transition with expected-status check; full-context test asserts line ids / created_by / number unchanged after pay and cancel.
  - `[low]` `[reject]` (blind) totals recomputed at issue with current settings — by design (settings frozen at issue); the issue response returns the final totals; settings changes are now audited.
  - `[medium]` `[patch]` (blind) settings changes not audited (legal identity / VAT) — patched: `InvoicingSettingsUpdatedEvent` → `INVOICING_SETTINGS_UPDATED` audit, contract + app labels.
  - `[low]` `[reject]` (blind) draft edits / deletions not audited — drafts carry no number and no legal value; creation is audited.
  - `[low]` `[reject]` (blind) lost update on concurrent draft edits — single shop, rare; FOR UPDATE serialises; adding versioning is new surface.
  - `[low]` `[patch]` (blind) VAT-registered with 0 % rate accepted — patched: 422 « Un commerce assujetti à la TVA doit avoir un taux de TVA supérieur à 0 » (DTO + domain).
  - `[false]` `[reject]` (blind) ensure cache never invalidated / unbounded — keys bounded by tenants × modules with migrations (~331 × 1); a dropped schema belongs to a deleted tenant whose tokens are revoked; restore implies a restart.
  - `[low]` `[patch]` (blind) hand-rolled version comparison duplicates Flyway's — patched: `MigrationVersion.compareTo`, helper removed.
  - `[false]` `[reject]` (blind) READ_ONLY never-provisioned first GET would fail — `provision` only refuses when `!registry.isActive`, and `isActive` = `readable()` (ENABLED or READ_ONLY) (`ModuleRegistryService.java:75`).
  - `[low]` `[reject]` (blind) year rollover untested — number year and issue date both derive from the same `LocalDate.ofInstant(now, WAT)`; test needs clock plumbing.
  - `[low]` `[reject]` (blind) no back-dated payment / no search — feature scope for the app screens (15.5), not a defect of 14.1.
  - `[low]` `[patch]` (blind) audit sentence « créé une Brouillon — X » — patched: labels « brouillon de <client> » / « <number> », phrases « créé/émis/encaissé/annulé la facture ».
  - `[low]` `[patch]` (verification-gap) no app test pins invoice audit sentences — patched: 'invoice entries' group in `audit_presenter_test.dart`.
  - `[low]` `[patch]` (verification-gap other) « créé une Brouillon » — same root as the blind finding, patched there.
  - `[low]` `[reject]` (verification-gap other) spec/code disagree on crm lazy provisioning — stale note corrected.
  - `[low]` `[reject]` (verification-gap other) spec says app labels not done — stale note corrected.
  - `[low]` `[reject]` (edge) missing schema → silent return then 500 — only a tenant row whose schema is being created; no JWT can target it before registration commits.
  - `[false]` `[reject]` (edge) ensure maps grow without bound — bounded, see above.
  - `[false]` `[reject]` (edge) cache never invalidated — see above.
  - `[low]` `[patch]` (edge) `?status=draft` rejected — patched: case-insensitive parse, unknown → 400.
  - `[low]` `[reject]` (edge) zero-amount invoice consumes a number — a 0 F invoice (free goods) is legitimate.
  - `[false]` `[reject]` (edge) validator misses interface/superclass `@RequiresPermission` — no such placement exists; invoicing annotates concrete controller methods only (claim not reachable).
  - `[false]` `[reject]` (edge) counter is an upsert, not UPDATE…RETURNING — documented decision (3); same gapless lock semantics.

## Design Notes

Ensure-provisioned keeps "tables only where used" and avoids the 72 s boot provisioning seen in 13.9: the first gated call per (tenant, module) after a boot costs one cheap history-version query (plus Flyway only if behind); later calls are a map lookup. A failed provisioning surfaces as 503 with a French message, never a missing-table 500.

Permission interceptor sketch:
```java
@Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
static Advisor requiresPermission(PermissionAuthorizationManager m) {
  var i = AuthorizationManagerBeforeMethodInterceptor.... (pointcut on @RequiresPermission, m);
  i.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder() + 1); // < EntitlementAspect.ORDER
  return i;
}
```

## Verification

**Commands (from `keevo/backend`, one package at a time):**
- `mvn -q test -Dtest='com.keevo.invoicing.**'` -- 0 failures
- `mvn -q test -Dtest='com.keevo.shared.module.**'` -- 0 failures
- `mvn -q test -Dtest='com.keevo.architecture.**'` -- 0 failures (Modulith verify, ArchUnit, endpoint table)
- `mvn -q test -Dtest='com.keevo.identity.**'`, `com.keevo.sync.**`, `com.keevo.crm.**` -- 0 failures
- `mvn -q verify -Pprod-classpath -DskipTests=false -Dtest='com.keevo.architecture.**'` -- passes

**Manual checks:**
- Live: backend on :4500, test tenant `kv_zggt7g` (PREMIUM_TRIAL): curl create → issue → pay; tables appear only in `kv_zggt7g`; count of tenant tables in other schemas unchanged; a FREE tenant gets 403 `MODULE_NOT_ENTITLED`.

## Auto Run Result

**Summary.** New paid module `invoicing` (« Facturation »), standalone (requires core only): module-owned tables (`db/modules/invoicing/V1__invoicing.sql`, JDBC adapters, no JPA / no `public` table), invoices DRAFT → ISSUED (gapless `FAC-<yyyy>-<000001>`, seller snapshot, CGI art. 150 (5) content, VAT 19.25 % / « Exonérée » / « Prise en charge État » / « TVA non applicable ») → PAID, ISSUED → CANCELLED; seller fiscal profile `/api/v1/invoicing/settings`; `complianceWarnings`. Socle: `@RequiresPermission` (manifest-declared permissions, role mapping per module, Spring method security between `@PreAuthorize` and the entitlement aspect), lazy `ensureProvisioned` on gated calls (legacy baseline modules such as crm skipped), paid module without row no longer gets an ACTIVE/FREE row. Audit events + labels (server, export contract, app mirror 2.0.5+77). Epic items ticked: `@RequiresPermission`, sync pull gating (pre-existing, evidence).

**Files.** `keevo/backend/src/main/java/com/keevo/invoicing/**` (module, domain, use cases, JDBC adapters, REST); `shared/module/**` (RequiresPermission, ModulePermissions, permission interceptor + validator, ensureProvisioned, recordVersion fix); `shared/event/catalog/invoicing/*`, `AuditEventListener`, `AuditLabels`, `ErrorCode`, `GlobalExceptionHandler`; `db/modules/invoicing/V1__invoicing.sql`, `keevo/modules/invoicing/module.json`; `docs/entitlements/endpoints.md` (Permission column); `keevo/contracts/export-datasets.json`; app `audit_labels.dart`, `audit_presenter.dart`, `audit_presenter_test.dart`, `pubspec.yaml`; tests (domain, migration, slice, full-context, catalog counts 17 → 18).

**Review.** 35 findings (high 0, medium 3, low 20, false 12): 10 patched (3 medium: issue sentinel, targeted transition writes, settings audit; 7 low: 0 % rate for VAT-registered, Flyway version compare, case-insensitive status, audit wording ×2, app sentence tests, plus orchestrator fixes: crm skipped by lazy provisioning, app label mirrors); 0 deferred; 25 rejected with reasons in the triage log.

**Follow-up review recommended:** true — 3 medium entries patched on a first pass; the unverified risk is the rewritten persistence of transitions (`JdbcInvoiceRepository` targeted UPDATEs + issue path) which only the patch round's tests cover.

**Verification.** Backend packages one at a time (0 failures): invoicing 36, architecture 32, shared 1013, identity 437, sync 533, crm 127, admin 517, reporting 401, catalog 247, commerce 251, inventory 376, store 60, messaging 168, subscription 70, feedback 52; `mvn -Pprod-classpath test` rc=0; `flutter test test/features/audit test/features/export` 242 passed, `flutter analyze lib/features/audit` clean. Live (dev backend :4500, tenant `kv_zggt7g` PREMIUM_TRIAL): first call provisioned the 5 tables in `kv_zggt7g` only (snapshot of every other schema identical, `tenant_modules` 331 rows before/after, no crm row added); draft → 422 without legal name → settings → issue `FAC-2026-000001` (2 × 11 925 TTC + 5 000 exempt = HT 25 000 / VAT 3 850 / TTC 28 850) → edit refused 409 → pay → cancel-paid 409 → 404; `FAC-2026-000002` « Prise en charge État » cancelled (blank reason 422); `FAC-2026-000003` line ids unchanged after pay; `?status=paid` OK, `bogus` 400; 0 % rate refused 422; settings change audited; subscription switched to FREE → 403 MODULE_NOT_ENTITLED (clients still 200), restored to PREMIUM_TRIAL.

**Residual risks.** Lazy provisioning runs Flyway on the request thread of the first call per tenant (one-off ~100 ms); credit notes (avoirs), back-dated payments, invoice search, offline sync of invoices and DGI e-invoicing (L 8 sexies, FEN) are not built — see routing in the final summary.
