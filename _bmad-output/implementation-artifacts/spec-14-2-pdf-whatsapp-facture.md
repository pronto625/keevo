---
title: '14.2 — Invoice PDF + WhatsApp sending, company designation & logo (shared by invoices and exports)'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_revision: 'e7cbf73ee8a57e604ef8fa6235bc90e6a6493c4d'
route: 'dispatch'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-14-1-module-facturation-autonome.md'
warnings: ['oversized', 'multiple-goals']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 14.2 (MOD-FR17, UX-DR5): an invoice must become a readable PDF (company logo, lines, totals, XAF) that the merchant sends to the client by WhatsApp through the existing sender, publishing `invoice.sent`. Today PDF rendering lives inside `reporting` (unreachable from `invoicing`), WhatsApp can only send text, and no company logo or company designation exists. The user also asked (2026-10-30): **exports must show the company designation when it is filled in, else the shop name** — the same identity must serve invoices and exports.

**Approach:** (1) A core **company identity** owned by the tenant: optional `designation` + optional logo, edited by the OWNER, exposed to every module through a `shared` port; display rule `designation` if not blank, else the tenant name (= the shop name typed at onboarding). Export PDF headers and the invoice use it. (2) Move the PDF engine (`PdfDocumentFactory`, fonts, `PdfFormats`) from `reporting` to `shared` so `invoicing` renders its own template. (3) Invoice PDF on demand (no storage; issued invoices are immutable), authenticated preview + a **signed public link** sent as text by WhatsApp. (4) Small app screen for the designation and logo.

**Decisions (autonomous run; user delegated all choices 2026-10-28; designation rule asked by the user 2026-10-30):**
- **Company identity**: new nullable `public.tenants.designation varchar(150)` (migration `V31__tenant_designation.sql`, `ADD COLUMN IF NOT EXISTS`, no data written) + logo stored on local disk like product photos (`keevo.company-logo.storage-dir`, PNG/JPEG, ≤ 200 KB after the app's compression, max 600 px, one file per tenant, replaced on upload, deletable). Endpoints (OWNER for writes, OWNER+EMPLOYEE read, `@EntitlementExempt("core — identité de l'entreprise")`): `GET/PUT /api/v1/account/company` `{designation}` → `{designation, displayName, hasLogo}`; `PUT/DELETE /api/v1/account/company/logo` (multipart), `GET /api/v1/account/company/logo`. Blank designation stored as null. Audited `COMPANY_PROFILE_UPDATED` (« Identité de l'entreprise modifiée »).
- **Shared port** `shared.capability.business.CompanyIdentity` (implemented in `identity`): `String displayName(String tenantSchema)` (designation › tenant name › « Mon commerce »), `Optional<byte[]> logo(String tenantSchema)` (+ content type). `invoicing` and `reporting` depend on `shared` only.
- **Exports**: `PdfExportRenderer.businessName()` uses `CompanyIdentity.displayName`; the store scope line is unchanged. Excel/CSV unchanged (no business header today). Export PDF header shows the logo when present (small, left of the title).
- **Invoice seller name**: `invoicing_settings.legal_name` stays the legal name printed; when blank, the issue snapshot takes `CompanyIdentity.displayName` (so issuing is no longer blocked by a missing legal name) and `complianceWarnings` adds « La raison sociale n'est pas renseignée : le nom affiché de votre entreprise est utilisé. ». The snapshot also freezes whether a logo existed (the PDF shows the current logo — logo is not a legal mention).
- **PDF engine move**: `PdfDocumentFactory` → `shared.infrastructure.pdf` (public `byte[] render(String template, Map<String,Object> vars)` + stream variant), fonts/`layout.html` stay under `resources/pdf/`, `PdfFormats` → `shared` (public). Reporting templates and output unchanged (existing export PDF tests stay green).
- **Invoice PDF** (`resources/pdf/templates/invoice.html`, A4, French, Liberation Sans): logo + seller block (name, address, phone, NIU, RCCM), « FACTURE » + number + issue date, client block (name, NIU, address, phone), lines table (désignation, qté, PU, taux/mention, HT, TVA, TTC), totals HT / TVA / TTC in XAF, « TVA non applicable » when applicable, payment details, footer, status stamp: DRAFT « BROUILLON — sans valeur », CANCELLED « ANNULÉE » + reason, PAID « PAYÉE le … ». All values escaped; no UUID anywhere.
- **Endpoints** (invoicing, `@RequiresEntitlement("invoicing")`): `GET /api/v1/invoices/{id}/pdf` (READ, `INVOICE_VIEW`, `application/pdf`, `Content-Disposition: inline; filename="FAC-2026-000001.pdf"` / `brouillon.pdf`); `POST /api/v1/invoices/{id}/send` `{phone?}` (WRITE, `INVOICE_SEND`) — not for DRAFT (409 `INVOICE_INVALID_TRANSITION`); phone defaults to the client phone, normalized with `PhoneNumbers.normalize`, missing/invalid → 422 French; WhatsApp not configured → 503 `WHATSAPP_UNAVAILABLE` « L'envoi WhatsApp n'est pas disponible pour le moment »; provider failure → 502/503 French, nothing recorded; rate limit 30 sends / tenant / hour → 429.
- **Public link**: `GET /api/v1/invoices/shared?token=` (permitAll, `@EntitlementExempt`, no JWT, sets/clears `TenantContext` itself) returns the PDF; token = HMAC-SHA256 over `schema|invoiceId|exp` (constant-time check), validity **30 days**; refused (French 410/404) when expired, tampered, invoice absent, or tenant not ACTIVE. Secret `keevo.invoicing.link-secret` (≥ 32 chars) defaulting to `keevo.export.link-secret`; if both blank → random per boot + WARN (go-live requirement routed to 16.7). Absolute URL base = `keevo.public-base-url` if set, else derived from the current request (`ServletUriComponentsBuilder.fromCurrentContextPath()`, forwarded headers honoured).
- **Message** (French, plain text): « Bonjour <client>, voici votre facture <number> de <displayName> d'un montant de <TTC> XAF : <link> ». ISSUED and PAID invoices can be sent (and re-sent); DRAFT and CANCELLED are refused (409).
- **Persistence**: `db/modules/invoicing/V2__invoice_sending.sql` adds `last_sent_at timestamptz`, `last_sent_to varchar(20)`, `send_count int not null default 0` (applied through lazy provisioning — first real V1→V2 upgrade path). Response DTO exposes `lastSentAt`, `lastSentTo` (masked « +237 6•• ••• 111 »), `sendCount`.
- **Event**: `InvoiceSentEvent(invoiceId, number, clientName, totalTtc, maskedPhone, actorId, tenantId, occurredAt)` in `shared/event/catalog/invoicing`; manifest `publishes` += `invoice.sent`; audited `INVOICE_SENT` (« Facture envoyée », label = number, detail masked phone); contract + app labels mirrored (phrase 'envoyé la facture').
- **App (Flutter, small)**: Settings › « Entreprise » (OWNER only): designation field + logo pick/replace/remove (reuse the product photo picker/compressor), online only with French offline message; shows « Nom affiché : … » preview; pubspec bump. No invoicing screens (15.5).

## Boundaries & Constraints

**Always:** invoicing depends on `shared` only; existing export PDFs identical except the business name rule and optional logo; designation/logo never required; no UUID in PDF, message, audit; French texts; prod-safe migration (additive nullable column, no data change, no tenant-schema DDL at boot); every new handler annotated and in `docs/entitlements/endpoints.md`; all backend packages + prod-classpath + touched Flutter tests green.

**Never:** no S3/new storage service; no media/document WhatsApp API; no PDF storage; no client portal; no invoicing UI; no deploy.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected | Error |
|---|---|---|---|
| Designation set | « Tech Centre SARL » | export PDF header + invoice seller fallback use it | — |
| Designation blank | null | tenant name (shop name) | — |
| Logo upload | PNG 150 KB (OWNER) | stored; `hasLogo` true; shown on invoice + export PDF | > limit / wrong type: 422 French; EMPLOYEE: 403 |
| PDF issued | GET /pdf | `application/pdf`, number, lines, totals, XAF, seller snapshot | unknown: 404 |
| PDF draft / cancelled / paid | GET /pdf | stamp BROUILLON / ANNULÉE / PAYÉE | — |
| Send ok | ISSUED, client phone set, WhatsApp configured | message with absolute link sent; lastSentAt/sendCount updated; `INVOICE_SENT` audit | — |
| Send override phone | `{phone:"677 00 01 11"}` | normalized `+237677000111` | invalid: 422 |
| Send without phone | no client phone, no body | — | 422 « Indiquez le numéro WhatsApp du client » |
| Send draft / cancelled | DRAFT or CANCELLED | — | 409 |
| WhatsApp off | NoOp provider | nothing recorded | 503 `WHATSAPP_UNAVAILABLE` |
| Provider fails | exception | nothing recorded, no event | 502 French |
| Rate limit | 31st send in an hour | — | 429 |
| Public link | valid token | PDF without login | — |
| Link expired / tampered / tenant suspended | token | — | 410 / 404 French, no detail leak |
| Free tenant / module disabled | /pdf, /send | — | 403 MODULE_NOT_ENTITLED |
| Upgrade V1→V2 | tenant provisioned at V1 (14.1) | first call migrates to V2, data kept | — |

</intent-contract>

## Code Map

Paths relative to `keevo/backend/`.
- PDF engine to move: `src/main/java/com/keevo/reporting/export/adapter/out/render/pdf/PdfDocumentFactory.java` (~L51 `html`, ~L58 `render`; fonts « Keevo Sans »), `PdfFormats.java` (`xaf`, `date`, WAT), `PdfHeader.java` (no-logo note to update), `PdfExportRenderer.java:142` `businessName()` (TenantView::name → CompanyIdentity), templates `src/main/resources/pdf/templates/{layout,end-of-day,inventory,sales,stock}.html`, fonts `src/main/resources/pdf/fonts/LiberationSans-*.ttf`.
- WhatsApp: `src/main/java/com/keevo/shared/capability/notification/WhatsAppSender.java` (`isConfigured`, `sendReport(phone, text)`); adapters in `messaging/whatsapp/adapter/out/**` (NoOp default); `shared/domain/PhoneNumbers.java:20` `normalize`.
- Signed link pattern: `reporting/export/application/service/ExportLinkSigner.java` (HMAC, constant-time, secret ≥ 32), `ExportDownloadController.java` (public, TenantContext set/cleared), `shared/infrastructure/security/SecurityConfig.java:68` `PUBLIC_PATHS`; `application.yml:145-155` (`keevo.export.link-secret`).
- Rate limiter pattern: `reporting/export/application/service/ExportRateLimiter.java` (Caffeine, 429 `RATE_LIMIT_EXCEEDED`).
- Photo storage pattern: `catalog/product/domain/port/out/ProductPhotoStorage.java`, `LocalDiskProductPhotoStorage`, `ProductPhotoController.java:43`.
- Tenant: `V1__baseline_public.sql:356` (`tenants`), JPA tenant entity + `identity/api/TenantView.java` / `TenantQueryPort`; `identity/auth/adapter/in/rest/AccountController.java` (`/api/v1/account`); onboarding sets tenant name from the shop name (`identity/onboarding/application/service/OnboardingService.java:88-90`). Check `TenantSchemaSyncService` `GLOBAL_ONLY_TABLES` so the new column causes no tenant-schema DDL.
- Invoicing (14.1): `invoicing/invoice/**` (`Invoice.assertIssuable`, `SellerSnapshot`, `InvoiceLifecycleUseCase`, `JdbcInvoiceRepository`, `InvoiceController`, `InvoiceResponseDto`), `resources/db/modules/invoicing/V1__invoicing.sql`, manifest `keevo/modules/invoicing/module.json` (publishes pinned by `ClasspathModuleManifestsTest.java:62`).
- Audit: `shared/infrastructure/web/AuditEventListener.java` (invoicing handlers ~L762-808), `shared/infrastructure/export/AuditLabels.java`, `keevo/contracts/export-datasets.json` (`ExportContractFileTest`, regen `-Dexport.contract.write=true`); app mirrors `keevo/app/lib/features/audit/domain/audit_labels.dart`, `.../presentation/audit_presenter.dart`, test `audit_presenter_test.dart` ('invoice entries').
- App: `keevo/app/lib/features/settings/presentation/page/settings_page.dart` (owner sections), `keevo/app/lib/core/photo/product_photo_compressor.dart` / `product_photo_providers.dart` (picker + compression).

## Tasks & Acceptance

**Execution:**
- [ ] `V31__tenant_designation.sql` + tenant entity/view + `CompanyIdentity` port (shared) + identity implementation + `/api/v1/account/company` (+ logo) + logo storage + audit `COMPANY_PROFILE_UPDATED` -- core company identity.
- [ ] Move `PdfDocumentFactory` / `PdfFormats` to `shared.infrastructure.pdf`; reporting uses them; `PdfExportRenderer` business name via `CompanyIdentity`; optional logo in `layout.html` header -- exports follow the designation rule.
- [ ] `invoicing`: `invoice.html` template + `InvoicePdfRenderer`; `V2__invoice_sending.sql`; send use case (phone resolution, link signer, rate limiter, `WhatsAppSender`), `InvoiceSentEvent`; seller-name fallback + warning; endpoints `/pdf`, `/send`, public `/shared?token=` (+ `PUBLIC_PATHS`); manifest `publishes`; ErrorCodes + French messages.
- [ ] Audit `INVOICE_SENT`, `COMPANY_PROFILE_UPDATED`; `AuditLabels`; regenerate contract; app label mirrors + presenter tests.
- [ ] App « Entreprise » settings screen (designation + logo) + widget tests; pubspec bump.
- [ ] Tests: PDF text extraction (PDFBox) asserts number, client, totals « 28 850 XAF », stamps, no UUID; export PDF header uses designation / shop name; full-context tests for every matrix row (fake `WhatsAppSender` capturing the message, link fetch without JWT, expired/tampered tokens, V1→V2 upgrade, rate limit); regenerate `docs/entitlements/endpoints.md`.

**Acceptance Criteria:**
- Given an issued invoice, when the owner requests its PDF, then a readable A4 PDF with logo (if any), seller identity, client, lines, totals in XAF is returned.
- Given an issued invoice and a WhatsApp number, when it is sent, then the client receives a French message with a working link to the PDF, the invoice records the send and `invoice.sent` is published and audited.
- Given a designation, when any export PDF is generated, then its header shows the designation; given none, the shop name.
- Given each backend test package run one at a time + `-Pprod-classpath`, and the touched Flutter test folders, then all pass.

## Spec Change Log

## Review Triage Log

### 2026-10-30 — Review pass
- verdicts: 43 findings — high 0, medium 6, low 33, false 4, maybe-false 0
- findings:
  - `[medium]` `[patch]` (blind) issued invoices never show the logo (`seller_has_logo` false for 14.1 invoices) — confirmed live on FAC-2026-000001; patched: current logo always rendered, test with logo uploaded after issue
  - `[low]` `[patch]` (blind) send then concurrent cancel → message delivered, 409, nothing recorded — patched: a delivered send is always recorded
  - `[low]` `[patch]` (blind) failed provider send consumes the hourly quota — patched: slot released on provider failure
  - `[medium]` `[patch]` (blind) public link: unlimited PDF renders by any link holder — patched: per-token limiter (429 French); access after a Free downgrade kept on purpose (an issued invoice stays available to its client)
  - `[low]` `[patch]` (blind) phone mask wrong for +1 / 3-digit country codes — patched rule + tests
  - `[low]` `[patch]` (blind) « Motif : null » possible — patched: line omitted when blank
  - `[low]` `[patch]` (blind) logo size unbounded after re-encoding — patched: 300 KB cap after normalisation (JPEG fallback or 422)
  - `[medium]` `[patch]` (blind) issue reads the whole logo in the locked transaction; I/O error fails issuing — patched: `CompanyIdentity.hasLogo` (storage exists), logo errors tolerated in the invoice PDF
  - `[low]` `[patch]` (blind) application service imports adapter config — patched: plain value
  - `[low]` `[patch]` (blind) app cannot preview the shop name once a designation exists — patched: `shopName` in the DTO and the preview
  - `[low]` `[reject]` (blind) missing unit tests for limiter/normalizer/storage/base URL — exercised end to end by the full-context tests; no defect named
  - `[low]` `[reject]` (blind) forwarded headers trusted when `keevo.public-base-url` is blank — headers come from the authenticated merchant's own request or the proxy; prod must set the base URL → go-live item routed to 16.7
  - `[low]` `[reject]` (blind) spec checkboxes / notes not updated — finalised by the orchestrator (Auto Run Result)
  - `[low]` `[reject]` (blind) duplicated header markup in `layout.html` — maintainability only; restructuring the shared template is not worth the risk now
  - `[low]` `[patch]` (edge) send/cancel race — same root as the blind finding, patched there
  - `[low]` `[patch]` (edge) quota on failure — same root, patched there
  - `[low]` `[reject]` (edge) double tap sends twice — re-sending is an allowed action; the 15.5 screen disables the button while sending
  - `[low]` `[reject]` (edge) spoofed forwarded host — same as blind, routed to 16.7
  - `[medium]` `[patch]` (edge) public link without limit — same root, patched there
  - `[low]` `[patch]` (edge) public link non-domain exception → generic 500 — patched: French 503
  - `[medium]` `[patch]` (edge) issue fails on logo I/O — same root, patched there
  - `[medium]` `[patch]` (edge) invoice PDF fails on logo I/O — same root, patched there
  - `[low]` `[patch]` (edge) both logo.png and logo.jpg present → arbitrary pick — patched: newest file
  - `[low]` `[patch]` (edge) orphan `.part` file when the move fails — patched: deleted
  - `[low]` `[patch]` (edge) `updateDesignation` false still reports success — patched: TENANT_NOT_FOUND
  - `[low]` `[reject]` (edge) logo stored but transaction fails → no audit — rare double failure, the logo itself is correct
  - `[low]` `[reject]` (edge) app compressor can exceed the target at the lowest quality — the server answers a French 422; no data issue
  - `[low]` `[patch]` (edge) `pickImage` exception unhandled — patched: French snackbar
  - `[low]` `[patch]` (edge) shop name preview — same root as blind, patched there
  - `[low]` `[patch]` (edge claim) quota consumed on failure — same root, patched
  - `[low]` `[patch]` (edge claim) cancelled invoice delivered — same root, patched
  - `[low]` `[patch]` (verification-gap) logo on issued invoice untested — test added with the fix
  - `[low]` `[patch]` (verification-gap) legal-name fallback flag never read back — GET assertion added
  - `[low]` `[patch]` (verification-gap) app `CompanyProfileApi` wire untested — datasource test with mocked Dio added
  - `[low]` `[patch]` (verification-gap other) quota — same root, patched
  - `[low]` `[patch]` (verification-gap other) send/cancel race — same root, patched
  - `[false]` `[reject]` (intent) no merchant UI for PDF/send — the invoicing screens are Story 15.5 by the epic plan
  - `[false]` `[reject]` (intent) a link is sent, not the PDF document — the AC says « PDF/lien »; the sender is text-only (spec decision)
  - `[false]` `[reject]` (intent) only `invoice.sent` added — `invoice.created` already exists since 14.1
  - `[low]` `[reject]` (intent) dashboard greeting / WhatsApp reports keep the tenant name — the user asked for exports; greeting shows the shop name, as before
  - `[false]` `[reject]` (intent) « boutique » read as tenant name, not per-store name — the tenant name is the shop name typed at onboarding (`OnboardingService.java:88-90`), what the export header showed before; the store scope line still names the store
  - `[low]` `[patch]` (intent) WhatsApp message uses the current display name while the PDF uses the frozen seller name — patched: message uses the snapshot
  - `[low]` `[reject]` (intent) code-equals-name branch untested on the export path — unit-tested in `CompanyProfileServiceTest`; the export uses the same port

## Verification

**Commands (from `keevo/backend`, one package at a time):**
- `mvn -q test -Dtest='com.keevo.invoicing.**'`, `com.keevo.reporting.**`, `com.keevo.identity.**`, `com.keevo.shared.**`, `com.keevo.architecture.**` -- 0 failures
- `mvn -q -Pprod-classpath test` -- rc 0
- `flutter test test/features/settings test/features/audit test/features/export` (from `keevo/app`) + `flutter analyze` on touched folders -- pass

**Manual checks:**
- Live on :4500 with `kv_zggt7g`: set designation → export PDF header; invoice PDF opened from the public link without login; send with the NoOp provider → 503; logo upload and PDF render.

## Auto Run Result

**Summary.** Core company identity (`public.tenants.designation` V31 + logo on local disk, `/api/v1/account/company[/logo]`, shared port `CompanyIdentity`: designation › shop name (tenant name) › « Mon commerce »); export PDF headers use it (+ logo). PDF engine moved to `shared.infrastructure.pdf`. Invoicing: `invoice.html` A4 PDF (logo, seller snapshot, client, lines HT/TVA/TTC, XAF, stamps BROUILLON / ANNULÉE / PAYÉE), `GET /invoices/{id}/pdf`, `POST /invoices/{id}/send` (WhatsApp text with a signed 30-day public link, phone normalisation, 30/h quota, 503 when WhatsApp is off, 502 on provider failure), public `GET /invoices/shared?token=` (per-token limiter), `V2__invoice_sending.sql` (send tracking + seller flags), `InvoiceSentEvent` / `INVOICE_SENT` and `COMPANY_PROFILE_UPDATED` audits; missing legal name now falls back to the display name with a warning. App 2.0.6+78: Paramètres › « Entreprise » (designation, logo, « Nom affiché » preview), audit label mirrors.

**Deviation recorded.** V2 also stores `seller_name_from_display` and `seller_has_logo` (snapshot flags); the logo flag is informative only — the PDF always shows the current logo.

**Review.** 43 findings (medium 6, low 33, false 4): patched 26 rows (6 medium: old invoices without logo, public-link render flood ×2, logo I/O failing issue/PDF ×3; low: send/cancel race, quota on failure, phone mask, « Motif : null », logo size after re-encoding, layering, shopName preview, link 503, stale logo file, orphan `.part`, lost designation row, picker error, message seller name, 3 test gaps); 0 deferred; 17 rejected with reasons; go-live config + pre-existing 415→500 routed to 16.7.

**Follow-up review recommended:** true — 6 medium entries patched on a first pass; unverified risk: the new public-link limiter and the send path's release-on-failure are covered by unit/full-context tests only, not by a live provider.

**Verification.** Backend packages one at a time, 0 failures: invoicing 60, architecture 32, shared 1013, identity 446, reporting 403, sync 533, crm 127, admin 517, catalog 247, commerce 251, inventory 376, store 60, messaging 168, subscription 70, feedback 52; `mvn -Pprod-classpath test` rc 0; Flutter `test/features/settings test/core/photo test/features/audit test/features/export test/core/router` 412 passed; analyze: 4 pre-existing infos only. Live (dev :4500, `kv_zggt7g`, WhatsApp forced to noop — dev has a real Wassender provider, no real message sent): designation + logo upload; invoice PDF rendered and inspected (logo, NIU/RCCM, 41 929 / 8 071 / 50 000 XAF); stock export PDF header « Tech Centre SARL » + logo, blank designation → « Tech Centre »; send → 422 without phone, 503 WhatsApp off; public link valid 200 without login, tampered 404, expired 410 (French); FAC-2026-000001 (issued in 14.1) shows the logo after the fix; no `designation` column in any tenant schema; audits recorded.

**Residual risks.** Real WhatsApp delivery not exercised live (would message a real number); limiters are in-memory per instance; go-live config (link secret, public base URL, persistent logo dir) is required — routed to 16.7.
