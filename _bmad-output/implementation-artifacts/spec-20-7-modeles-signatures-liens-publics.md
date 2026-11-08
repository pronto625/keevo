---
title: '20-7 Rendering templates (8 Bref layouts, tickets 58/80/A4), signatures, lasting and rotatable public links, legacy Bref links; quote sending; payments on documents'
type: 'feature'
created: '2026-11-07'
status: 'done'
baseline_revision: '72839aef13a2cc3c85e4a611aadedf1f8b831a6f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-5-remise-livraison-tva-echeance.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-4-encaissements-echeancier-retard.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-3-devis.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref merchants print and share their documents in one of 8 layouts (3 A4, a receipt and 4 tickets on 53/80/110 mm paper), sign them (client and seller pads) and share a public link valid 90 days that can be regenerated; Keevo has one A4 layout, no signature, a fixed 30-day HMAC link that cannot be revoked, no way to open an old Bref link, cannot send a quote (refused by `assertSendable`, routed from 20.3), and its PDF / public link show neither payments, amount paid nor amount due (routed from 20.4). FUS-FR21/22/23.

**Approach:** Keep the legal invoice aggregate untouched and add a separate *presentation* of each document (invoicing V15: template, ticket width, signature files, link version, legacy Bref token / reference) plus tenant document preferences (default layout, paper, link lifetime). Render the 8 Bref layouts as Thymeleaf templates chosen by a strategy registry, roll tickets on a page fitted to their content. Links carry a version (rotation = bump) and use the tenant's lifetime; old Bref links resolve through a public PK-indexed registry (public V57). Quotes waiting for validation become sendable. The 8 Bref layouts print the 20.4 payment block; the Keevo layout prints it only when payments were recorded (V1 parity).

## Boundaries & Constraints

**Always:**
- **Additive** (FUS-NFR1): new nullable columns / new table (invoicing V15, numbered after 20-9's V14), public V57 (one registry table). Existing `/api/v1` responses only gain fields; a document without presentation prints exactly as before except the payment block when payments were recorded; links sent before 20.7 (3-part tokens) read as version 0 and keep opening until the first rotation of their document.
- **Layouts:** `invoiceTemplate ∈ {CLASSIC, MODERN, MINIMAL, RECEIPT, TICKET_CLASSIC, TICKET_COMPACT, TICKET_DETAILED, TICKET_MODERN}` (Bref codes `ticket-classic`… accepted on input), `null` = tenant default, else the Keevo standard layout. `ticketWidth ∈ {"58","80","A4"}` (Bref 53 → 58; Bref 110 → A4 = 110 mm column centred on an A4 sheet), default 80; applies to the 5 narrow layouts only. Reproduce Bref's content and order: shop header, title, number (legal `FAC-…`, `DV-…` quote, `BROUILLON`), Bref reference `BRF-XXXXXXXX` next to the number when present, client block (NIU/RCCM/phone/email), lines (qty, name, ref, unit price, total), Sous-total / Remise / TVA / Livraison / Total, status badge (Payée, En attente, Partiellement payée, En retard, Annulée, En attente de validation, Brouillon), payment block, notes (seller footer), signatures, thanks, ticket bars. No flexbox (PDF engine): tables. Liberation Sans / Liberation Mono embedded.
- **Payment block (Bref `renderPaymentSection`)**: nothing for a quote or an invoice paid at once (only the legacy single payment); cancelled → « Motif d'annulation »; else each received payment (« Versement du dd/MM/yyyy — méthode ») and instalment (« Échéance dd/MM/yyyy (payée) »), « Montant payé », « Reste à payer » = amount due − paid. Compact ticket: no instalment detail. Keevo layout: the block only when at least one non-legacy payment row exists.
- **Signatures:** `PUT /invoices/{id}/signatures/{client|seller}` multipart `file`, PNG/JPEG recognised by bytes, raw upload ≤ 2 MB (`keevo.invoice-signature.max-upload-bytes`), re-encoded without metadata and downscaled (shared `ImageReencoder`, 800 px), stored ≤ 200 KB (`keevo.invoice-signature.max-bytes`; single source `InvoiceSignatureProperties`), stored like the store logo (local disk per tenant, unique file name, row keeps the name, swap after commit, purge on account deletion). Cancelled invoice / converted or cancelled quote → 409; an issued/paid document's signature is set once (replace → 409, delete → 409) unless its stored file is missing; a sent quote's (WhatsApp or public link handed out, `link_shared_at`) client signature is frozen the same way; a draft's can be replaced or removed; a deleted draft's files are removed after commit; a quote conversion carries layout and signatures to the invoice. Every change is audited (« Document signé », « Signature retirée », « Modèle d'impression modifié », « Lien public régénéré », « Modèles et liens des documents modifiés »; a sent quote « Devis envoyé »). API exposes only `clientSigned` / `sellerSigned`.
- **Public links:** token payload `schema|invoiceId|exp|version` (HMAC-SHA256 as 14.2); lifetime = tenant `publicLinkDays` (null → 30; import sets 90 — Story 24.4); `POST /invoices/{id}/public-link` and `POST …/public-link/rotate` (sendable docs only, else 409 like the sending; rotation bumps the version, clears and unregisters the legacy Bref token). Layout and document-settings PUTs are partial (absent = unchanged, `null` = default). A rotated link → 404 `INVOICE_LINK_INVALID`.
- **Legacy Bref links:** `GET /api/v1/invoices/public/{uuid}` (Bref's path) → PDF; registry `public.invoice_legacy_links(token PK, tenant_schema, invoice_id, expires_at)`; unknown/malformed → 404, past Bref expiry → 410, tenant not ACTIVE → 404, the invoice must still hold the token → else 404; 30 openings per resolving token, 20 misses per address per 10 min (429). A token is never moved to another tenant / invoice (`register` refuses); a new token for the same invoice retires the old one. Import port: `InvoicePublicLinkUseCase.attachLegacy(invoiceId, token, "BRF-…", expiresAt, schema)`.
- **Quote sending:** a DRAFT quote is sendable (link + WhatsApp, message « voici votre devis DV-… »); converted / cancelled quote → 409.
- No technical data printed (no UUID, no file name); French labels and messages.

**Never:**
- No change to issued invoices' legal content, numbering or totals; no new sync operation (presentation is set online; pull carries the new fields).
- No SMS / email sending (20.8), no quota on sending (20.8), no import code (24.4), no RN screens (m20-7), no dashboard API.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Choose layout | `PUT /invoices/{id}/layout {"invoiceTemplate":"ticket-classic","ticketWidth":"58"}` on issued invoice | 200, `invoiceTemplate: TICKET_CLASSIC`, PDF page 58 mm wide, one page | unknown value → 400 French |
| Preview | `GET /invoices/{id}/pdf?template=CLASSIC` | CLASSIC A4, nothing saved | unknown → 400 |
| Tenant default | `PUT /invoicing/settings/documents {"invoiceTemplate":"TICKET_MODERN"}` | documents without own layout print TICKET_MODERN | days ∉ 1–365 → 422 |
| Sign | PNG ≤ 200 KB on draft | `clientSigned: true`, image on PDF | not an image → 422 `INVOICE_SIGNATURE_INVALID`; > 200 KB → 422 `INVOICE_SIGNATURE_TOO_LARGE` |
| Re-sign issued | issued, signature present | — | 409 `INVOICE_INVALID_TRANSITION` « déjà signé » |
| Link lifetime | prefs `publicLinkDays: 90` | `expiresAt` = now + 90 d | — |
| Rotation | `POST …/public-link/rotate` | new link opens; every older one → 404 | — |
| Old 14.2 link | 3-part token, version 0 | opens | — |
| Legacy Bref link | registered UUID | PDF with `BRF-…` | unknown → 404, expired → 410 |
| Quote send | DRAFT quote | WhatsApp « voici votre devis DV-… », link opens « DEVIS DV-… » | cancelled quote → 409 |
| Payments | issued, 10 000 paid + instalment | « Montant payé 10 000 XAF », « Reste à payer 13 850 XAF », « Échéance … » | — |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/` unless stated.
- `resources/db/modules/invoicing/V15__invoice_templates_signatures_public_links.sql`, `resources/db/migration/V57__invoice_legacy_links.sql`.
- Domain `java/com/keevo/invoicing/invoice/domain/model/`: `InvoiceTemplate`, `TicketWidth`, `SignatureRole`, `InvoicePresentation`, `DocumentPreferences`; `Invoice.assertSendable` / `documentReference`.
- Ports `domain/port/out/`: `InvoicePresentationRepository`, `LegacyInvoiceLinkRegistry`, `InvoiceSignatureStorage`, `InvoiceSignatureNormalizer`, `InvoiceDocumentRenderer.RenderRequest`.
- Adapters: `adapter/out/persistence/JdbcInvoicePresentationRepository`, `JdbcLegacyInvoiceLinkRegistry`; `adapter/out/storage/LocalDiskInvoiceSignatureStorage`, `ImageIoInvoiceSignatureNormalizer`, `InvoiceSignatureProperties`; `adapter/out/pdf/InvoicePdfRenderer` (registry `LAYOUTS`), `BrefLayoutView`; `resources/pdf/templates/invoice-{classic,modern,minimal,receipt,ticket-classic,ticket-compact,ticket-detailed,ticket-modern}.html`, `invoice.html`; `shared/infrastructure/pdf/PdfDocumentFactory.renderFitted` + Keevo Mono.
- Use cases: `InvoicePublicLinkUseCase`, `InvoicePresentationUseCase`, `InvoiceDocumentUseCase`, `SendInvoiceUseCase`, `InvoiceQueryUseCase.presentations`; `application/service/InvoiceLinkSigner` (version).
- REST: `InvoicePresentationController`, `InvoiceLegacyLinkController`, `InvoiceSharedLinkController` (version check), `InvoiceController` (`/pdf?template=&ticketWidth=`), `InvoiceResponseAssembler`, DTOs `InvoiceResponseDto` (+5 fields), `InvoiceLayoutRequestDto`, `PublicLinkDto`, `DocumentPreferencesDto`; `InvoiceDeltaProvider` (pull).
- Core: `SecurityConfig.PUBLIC_PATHS`, `ErrorCode` / `ErrorMessages` / `GlobalExceptionHandler` (3 signature codes), `TenantDeletionService` (registry rows), `application.yml` (`keevo.invoice-signature.*`).
- Bref (read-only): `bref/features/invoice/components/invoice-templates/*.ts`, `api-bref/src/invoice/invoice-public.controller.ts`, `…/repositories/invoice.repository.ts` (`rotatePublicToken`, 90 days), migration `1750000000001-create-client-table-and-invoice-token.ts`.

## Tasks & Acceptance

**Execution:**
- V15 + V57 migrations -- presentation columns, preferences table, legacy registry -- additive, PK lookups.
- Domain types + `Invoice.assertSendable` for DRAFT quotes -- routed item from 20.3.
- Presentation / registry / signature adapters, normalizer, properties -- same storage pattern as the store logo.
- Renderer strategy registry, view model, 8 Thymeleaf layouts + payment block in `invoice.html`, fitted roll pages -- FUS-FR21 and routed item from 20.4.
- Link signer versions, public-link use case (lifetime, rotation, legacy), shared / legacy controllers -- FUS-FR23.
- Presentation controller (layout, signatures, links, preferences), response/pull fields, error codes, public path, tenant deletion cleanup.
- Tests: rendering of every layout × paper on 3 typical documents (+ signatures, Keevo layout), unit tests (presentation types, signer versions, link use case), full context (layouts, signatures, links + rotation + lifetime, quote sending, legacy link, payments on PDF), migration pins.

**Acceptance Criteria:**
- Given each of the 8 templates (narrow ones on 58, 80 and A4), when an issued partially paid signed invoice, a Bref-like HT invoice with discount/delivery/due date and BRF reference, and a quote are rendered, then each PDF shows number, client, lines, totals, payment block and no identifier, roll tickets are one page of the roll width, and the PNG renderings were inspected against Bref's layouts.
- Given a tenant lifetime (default 30 d, 90 d set), when a link is signed, then it expires accordingly; after a rotation older links answer 404 and the new one opens.
- Given an imported invoice with `legacy_public_token` and `BRF-` reference, when its old Bref link is opened, then the PDF displays with the BRF reference next to the legal number.

## Spec Change Log

## Review Triage Log

### 2026-11-08 — Review pass (5 layers: blind, edge case, verification gap, intent, patterns)
- verdicts: 43 findings — high 1, medium 16, low 16, false 5, maybe-false 5 (grouped by root cause)
- findings:
  - `[high]` `[patch]` Edge: rotate / public-link on a draft, cancelled or converted document produced a working link — `assertSendable`, 409.
  - `[medium]` `[patch]` Edge/blind: legacy registry `ON CONFLICT` could move a token to another tenant; re-attach left the old token live; controller trusted the registry row alone — refused move, unregister, `assertLegacyCurrent`.
  - `[medium]` `[patch]` Blind/patterns: legacy route rate-limited before resolution, random UUIDs evicted real counters — resolved tokens only + per-IP failure limiter.
  - `[low]` `[patch]` Edge: imported invoice without number sent as « null » — `reference()` fallback.
  - `[medium]` `[patch]` Edge/blind: very long tickets unbounded — fitted height capped, paginated beyond 1 000 mm, test.
  - `[low]` `[patch]` Edge: frozen signature with a missing file could never be re-added — allowed when the file is absent.
  - `[low]` `[patch]` Edge/blind: draft deletion left signature files — deleted after commit.
  - `[medium]` `[patch]` Blind: quote conversion dropped layout and signatures — copied in one UPDATE … FROM.
  - `[medium]` `[patch]` Blind: a sent quote's client signature could be swapped — locked once sent (`send_count` / `link_shared_at`).
  - `[medium]` `[patch]` Blind: sign / layout / rotate / settings not audited; quote sending audited as an invoice — four events + French audit labels, `InvoiceSentEvent.quote`.
  - `[medium]` `[patch]` Blind: PUT layout / settings reset absent fields — partial request DTOs.
  - `[low]` `[patch]` Blind: size check before downscale, three config sources — 2 MB raw, 200 KB after re-encode, one properties source.
  - `[low]` `[patch]` Blind/patterns: duplicated response assembly and parsers; findAll without deleted_at — assembler reused, `InvoiceLayoutParams`, `= ANY(?)`.
  - `[low]` `[patch]` Patterns: double reads on shared link and rotate — version checked in the PDF read, `RETURNING` reused.
  - `[low]` `[reject]` Patterns: Thymeleaf runs twice for fitted tickets — bounded by document size, documented.
  - `[low]` `[reject]` Patterns: presentation row read separately from the invoice row — O(1) primary-key reads.
  - `[low]` `[patch]` Edge (claim): every layout prints the payment block — spec corrected (Keevo layout only with payments, V1 parity).
  - `[medium]` `[patch]` Verification: RGPD deletion of `invoice_legacy_links` untested — test added.
  - `[medium]` `[patch]` Verification: list and sync pull presentation fields untested — list + pull-from-cursor test.
  - `[medium]` `[patch]` Verification: signing cancelled / converted documents untested — 409 tests.
  - `[medium]` `[patch]` Blind: role checks, cross-tenant isolation, 14.2 token end to end, legacy 404/429, signed quote conversion untested — tests added.
  - `[maybe-false]` `[defer]` Intent: automatic 90-day lifetime for imported tenants — set by the import, routed to 24.4.
  - `[false]` `[reject]` Intent: old Bref link returns a PDF where Bref returned JSON — the redirected browser link displays the document; Bref's JSON was for its own web viewer.
  - `[maybe-false]` `[reject]` Intent: visual comparison not automated — 3 documents × 8 layouts rendered and checked by eye (pdftoppm), text/size assertions in tests.
  - `[false]` `[reject]` Intent: merchant UI — API-only repo, mobile key m20-7.
  - `[low]` `[reject]` Intent: BRF reference when no legal number — `reference()` falls back to BRF-, covered by the sending fallback.
  - `[maybe-false]` `[defer]` Patches: EMPLOYEE may rotate a link (INVOICE_SEND) — permission decision, 19-5b.
  - `[maybe-false]` `[reject]` Patches: per-IP limiter behind the proxy — relies on forwarded headers already applied by the existing filters.


## Design Notes

**Patterns.** *Strategy + registry* for rendering: `InvoicePdfRenderer.LAYOUTS` maps each `InvoiceTemplate` to its Thymeleaf file; one shared view model (`BrefLayoutView`, Bref's `InvoiceData` + `FR_TRANSLATIONS`) feeds all 8 layouts, so business rules (status, payment block, number/BRF) live once in Java and templates are pure layout; `null` template = the unchanged Keevo legal layout (no regression for V1). *Separate presentation aggregate* (`InvoicePresentation`, own repository on the same row) instead of growing the 28-component legal `Invoice` record: no legal copy-constructor touched, layout/signature changes never pass through the issue/immutability rules. *Ports & Adapters + post-commit file swap* for signatures (copy of the store-logo pattern). *Versioned stateless token* for links: rotation is a counter bump (no token table), legacy compatibility by reading 3-part tokens as version 0. *Cross-tenant index* for Bref UUID links (public table keyed by token) instead of scanning tenant schemas.

**Hot-path costs (FUS-NFR11, no N+1).**
- List / get / pull: +1 query per page (`InvoicePresentationRepository.findAll`, `id IN (…)` on the PK), like the 20.4 ledgers; never per invoice.
- PDF (download, `/shared`, legacy): invoice by PK, presentation by PK, preferences (1-row table), credit notes (1 query), ledger (1 query, `idx_invoice_payments_invoice`), ≤ 2 signature file reads; rendering O(lines + payments). Roll tickets do two layouts (measure, then render) of a small document.
- Signed link: 1 presentation + 1 preferences read; verification: HMAC + 1 PK read for the version.
- Legacy link: 1 PK read in `public.invoice_legacy_links` (PK `token`), then the PDF path; account deletion: `DELETE … WHERE tenant_schema = ?` on `ix_invoice_legacy_links_tenant`. Per-tenant uniqueness/lookup of `legacy_public_token`: partial unique index `uq_invoices_legacy_public_token`.
- Rotation: 1 UPDATE … RETURNING on the PK (+ 1 DELETE by PK in public when a Bref token existed).

**Decisions.** Ticket widths follow the epic (`58/80/A4`): Bref's 53 mm maps to 58, 110 mm to A4 (110 mm column on an A4 sheet). Presentation is set online only (REST); offline creation keeps the tenant default until the app calls `PUT …/layout` (no new sync operation). Signatures are evidence: once on an issued document they are frozen. The public link of a cancelled invoice keeps opening (shows « ANNULÉE ») unless rotated — Keevo 14.2 behaviour kept (Bref rotated automatically on cancel: the app can call rotate).

## Verification

**Commands:**
- `bash kmvn.sh -o -q test -Dtest='com/keevo/invoicing/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false` -- expected: 0 failures.
- Same for `com/keevo/identity/**`, `com/keevo/shared/**`, `com/keevo/architecture/**` -- expected: 0 failures.
- Visual: `KEEVO_PDF_PREVIEWS=<dir>` + `InvoiceTemplatesRenderingTest`, then `pdftoppm -png` -- inspect each layout against Bref's templates.

## Auto Run Result

Status: done

**Change:** documents get a presentation outside their legal content (invoicing V15): one of Bref's 8 layouts (CLASSIC, MODERN, MINIMAL, RECEIPT, TICKET_CLASSIC/COMPACT/DETAILED/MODERN) and a ticket paper (58 / 80 mm roll fitted to the content, or A4 = 110 mm column), per document (`PUT /invoices/{id}/layout`, any status), per tenant (`/invoicing/settings/documents`) or per download (`/pdf?template=&ticketWidth=`); `null` keeps the Keevo legal layout. Client / seller signatures (PNG/JPEG ≤ 200 KB, re-encoded, stored like the store logo, frozen once on an issued document) printed on every layout. Public links carry a version (rotation = bump → older links 404; 14.2 links read as version 0) and the tenant lifetime (30 d default, 90 d for imported tenants); `POST …/public-link` and `…/public-link/rotate`. Old Bref links `GET /api/v1/invoices/public/{uuid}` resolve through `public.invoice_legacy_links` (V57) and print `BRF-XXXXXXXX` next to the number; import port `InvoicePublicLinkUseCase.attachLegacy` for 24.4. Quotes waiting for validation are sendable (link + WhatsApp « voici votre devis DV-… », seller = settings legal name). The 8 Bref layouts print the payment block (versements, échéances, Montant payé, Reste à payer); the Keevo layout keeps its V1 behaviour and prints it only when real payment rows exist. Responses and pull gain `invoiceTemplate`, `ticketWidth`, `clientSigned`, `sellerSigned`, `legacyReference`.

**Files (main):** `db/modules/invoicing/V15__invoice_templates_signatures_public_links.sql`, `db/migration/V57__invoice_legacy_links.sql`; domain `InvoiceTemplate`, `TicketWidth`, `SignatureRole`, `InvoicePresentation`, `DocumentPreferences`, `Invoice.assertSendable`/`documentReference`; ports `InvoicePresentationRepository`, `LegacyInvoiceLinkRegistry`, `InvoiceSignatureStorage`, `InvoiceSignatureNormalizer`, `InvoiceDocumentRenderer.RenderRequest`; adapters `JdbcInvoicePresentationRepository`, `JdbcLegacyInvoiceLinkRegistry`, `LocalDiskInvoiceSignatureStorage`, `ImageIoInvoiceSignatureNormalizer`, `InvoicePdfRenderer` (layout registry), `BrefLayoutView`, 8 templates `pdf/templates/invoice-*.html`, `invoice.html`, `PdfDocumentFactory.renderFitted` + Liberation Mono fonts; use cases `InvoicePublicLinkUseCase`, `InvoicePresentationUseCase`, `InvoiceDocumentUseCase`, `SendInvoiceUseCase`; REST `InvoicePresentationController`, `InvoiceLegacyLinkController`, `InvoiceSharedLinkController`, `InvoiceResponseAssembler`, DTOs; core `SecurityConfig`, `ErrorCode`/`ErrorMessages`/`GlobalExceptionHandler`, `TenantDeletionService`, `application.yml`; `docs/entitlements/endpoints.md` regenerated.

**Review:** not run in this unattended build (left to the orchestrator's review pass).

**Verification:**
- `com/keevo/invoicing/**`: 368 tests, 0 failures (after fixing 2: migration pin of the sync upgrade test, quote message seller name). New: `InvoiceTemplatesRenderingTest` (27 cases: 8 layouts × papers × 3 typical documents, signatures, Keevo layout), `InvoicePresentationTest`, `InvoicePublicLinkUseCaseTest`, signer version test, `InvoiceTemplatesLinksFullContextTest` (6 scenarios).
- `com/keevo/identity/**`: 862 tests, 0 failures. `com/keevo/shared/**`: 1 320 tests, 0 failures. `com/keevo/architecture/**`: 128 tests, 0 failures after regenerating `docs/entitlements/endpoints.md`.
- Visual: every PDF of the rendering test converted with `pdftoppm` and inspected against Bref's templates (classic, modern, minimal, ticket-classic 58/80, ticket-modern 80, ticket-detailed 58, ticket-compact A4, quote and BRF-imported invoice); synthetic data only.

**Residual risks:** public V57 follows V56 (20-10): merge 20-10 first or run Flyway `outOfOrder`. Migration pins use V15 (V14 = 20-9); `InvoicingMigrationPostgresIntegrationTest` counts the scripts on the classpath. Bref 53 / 110 mm papers map to 58 mm / A4 (import converts). PDF fonts are Liberation Sans/Mono instead of Bref's web fonts. Long words on 58 mm detailed tickets can crowd the columns (as Bref at 53 mm). A cancelled invoice's link still opens (shows « ANNULÉE ») unless rotated.

**Routed (out of scope):** import of `legacy_public_token` / `legacy_reference` / `publicLinkDays = 90` / paper 53→58, 110→A4 → 24-4; SMS / email sending of the link → 20-8; RN screens → m20-7-modeles-signatures-liens-publics.

**Verification (orchestrator):** full suite package by package on the rebased branch (clean build): 6 389 tests, 0 failures; Flutter test client: audit labels added (2.0.28+100), `local_export_contract_test` and `test/features/audit` green. Live on keevo_dev (WhatsApp noop, FCM/Expo off), invoicing activated for the check then deactivated: `FAC-2026-000008` rendered as classic / modern / ticket-classic 80 mm PDFs, layout set to modern, public link (30 days) opens the PDF, rotation → old link 404, a quote gets a link, rotate on a draft → 409 French message, document settings readable.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commits `487a8ec5` + `51d426dc`, refonte/modulaire / develop). Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** chaque facture ou devis peut être imprimé dans l'un des 8 modèles de Bref (Classique, Moderne, Minimal, Reçu, Ticket classique / compact / détaillé / moderne) sur papier 58 mm, 80 mm ou A4 ; le PDF est fait par le serveur. Le client et le vendeur peuvent signer ; les signatures sont imprimées. Le lien public dure 30 jours (90 pour un compte importé de Bref), peut être régénéré (les anciens liens cessent de fonctionner), et les anciens liens Bref des factures importées s'ouvrent toujours. Un devis en attente peut être envoyé au client (lien / WhatsApp). Les PDF et le lien public montrent les versements, les échéances, le montant payé et le reste à payer.

**À utiliser :**
1. Modèle du document — `PUT /api/v1/invoices/{id}/layout` corps `{"invoiceTemplate":"TICKET_CLASSIC","ticketWidth":"80"}` (valeurs : `CLASSIC`, `MODERN`, `MINIMAL`, `RECEIPT`, `TICKET_CLASSIC`, `TICKET_COMPACT`, `TICKET_DETAILED`, `TICKET_MODERN` ; les codes Bref `ticket-classic`… sont acceptés ; `ticketWidth` : `"58"`, `"80"`, `"A4"` ; `null` = choix du compte). Tout statut. Mise à jour partielle : champ absent = inchangé, `null` = choix du compte. 200 = la facture. Valeur inconnue → 400 `VALIDATION_ERROR` « Modèle inconnu : choisissez Classique, Moderne, Minimal, Reçu ou l'un des 4 tickets » / « Largeur de ticket inconnue : 58, 80 ou A4 » → l'app affiche le message.
2. Aperçu sans enregistrer — `GET /api/v1/invoices/{id}/pdf?template=CLASSIC&ticketWidth=58` (PDF ; sans paramètre : modèle du document, sinon du compte, sinon modèle Keevo).
3. Choix du compte — `GET /api/v1/invoicing/settings/documents` → `{invoiceTemplate, ticketWidth, publicLinkDays, effectiveTicketWidth, effectivePublicLinkDays}` ; `PUT {invoiceTemplate?, ticketWidth?, publicLinkDays?}` partiel (absent = inchangé, `null` = valeur par défaut ; propriétaire seulement, sinon 403) ; `publicLinkDays` hors 1–365 → 422 `VALIDATION_ERROR` « La durée d'un lien doit être comprise entre 1 et 365 jours ».
4. Signatures — `PUT /api/v1/invoices/{id}/signatures/client` ou `/seller`, multipart, partie `file` (PNG ou JPEG, 2 Mo max à l'envoi, réduite côté serveur, 200 Ko max une fois réduite ; la donnée `data:image/png;base64,…` du pad est envoyée comme fichier). 200 = la facture avec `clientSigned` / `sellerSigned`. Erreurs : 422 `INVOICE_SIGNATURE_INVALID` « Format de signature non pris en charge (PNG ou JPEG) », 422 `INVOICE_SIGNATURE_TOO_LARGE` « La signature est trop lourde : choisissez une image plus légère » → réduire l'image et réessayer ; 409 `INVOICE_INVALID_TRANSITION` « Ce document émis est déjà signé : sa signature ne peut pas être remplacée » , « Ce devis a été envoyé au client : sa signature ne peut plus être remplacée » (signature client d'un devis déjà envoyé), « Cette facture est annulée : elle ne peut plus être signée » ou « Ce devis a été converti ou annulé : il ne peut plus être signé » → masquer le bouton. La conversion d'un devis signé donne une facture signée, avec le même modèle. `DELETE …/signatures/{role}` : brouillon seulement (sinon 409 « La signature d'un document émis ne peut pas être retirée »). `GET …/signatures/{role}` → l'image (404 `INVOICE_SIGNATURE_NOT_FOUND` « Ce document n'a pas cette signature »). Rôle inconnu → 400 « Signature inconnue : « client » ou « seller » ».
5. Lien public — `POST /api/v1/invoices/{id}/public-link` → `{url, expiresAt, validityDays}` (facture émise / payée ou devis en attente ; sinon 409 `INVOICE_INVALID_TRANSITION`, même message que l'envoi ; vaut aussi pour `rotate`). À partager par la feuille de partage du téléphone. `POST …/public-link/rotate` → nouveau lien ; tous les précédents répondent 404 `INVOICE_LINK_INVALID` « Ce lien de facture n'est pas valide ». Lien expiré → 410 `INVOICE_LINK_EXPIRED`.
6. Envoi WhatsApp d'un devis — `POST /api/v1/invoices/{id}/send {"phone":"+2376…"}` accepté pour un devis en attente (« voici votre devis DV-… ») ; devis converti ou annulé → 409.
7. Lecture (REST et pull `invoices`) : nouveaux champs `invoiceTemplate`, `ticketWidth`, `clientSigned`, `sellerSigned`, `legacyReference` (référence `BRF-…` à afficher à côté du numéro d'une facture importée).
8. Hors ligne : modèle et signatures ne passent pas par la synchro ; les envoyer en ligne après la synchro de la facture (sinon le modèle du compte s'applique).

**À implémenter côté mobile :**
1. Récapitulatif : choix du modèle et du papier → `PUT …/layout` ; aperçu par `GET …/pdf?template=…`.
2. Pad de signature client / vendeur → upload multipart ; état signé depuis `clientSigned` / `sellerSigned`.
3. Paramètres : modèle, papier par défaut, durée du lien (propriétaire).
4. Partage : « Partager le lien » (`public-link`), « Régénérer le lien » (`rotate`), envoi d'un devis.
5. Afficher `legacyReference` à côté du numéro ; aucune donnée technique à l'écran.

**Critères d'acceptation mobile :**
- Given une facture émise, when l'utilisateur choisit « Ticket classique 58 mm » puis ouvre le PDF, then le PDF est un ticket de 58 mm d'une seule page.
- Given une facture brouillon, when le client signe sur le pad, then la facture affiche « signée » et le PDF montre la signature.
- Given un lien partagé, when l'utilisateur régénère le lien, then l'ancien lien affiche une erreur et le nouveau ouvre la facture.
- Given un devis en attente, when l'utilisateur l'envoie par WhatsApp, then le client reçoit « voici votre devis DV-… » avec un lien qui ouvre le devis.
- Mise en place : `curl -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"invoiceTemplate":"TICKET_CLASSIC","ticketWidth":"58"}' $API/api/v1/invoices/$ID/layout` ; `curl -X PUT -H "Authorization: Bearer $TOKEN" -F file=@signature.png $API/api/v1/invoices/$ID/signatures/client` ; `curl -X POST -H "Authorization: Bearer $TOKEN" $API/api/v1/invoices/$ID/public-link`.

**Clé de suivi mobile :** `m20-7-modeles-signatures-liens-publics`.
