---
title: '20-3 Quotes (kind = QUOTE) — create, edit with conflict check, cancel, convert to an issued invoice'
type: 'feature'
created: '2026-11-06'
status: 'done'
baseline_revision: 'bc57e665dd82a31eabc37fd8717c85a7bc097639'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-2-crm-etendu-clients-par-etablissement.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Who may end a quote: delete needs INVOICE_CREATE while cancel needs INVOICE_MANAGE.
    evidence: |-
      Product decision on roles. Routed to 19-5b.
    severity: low
  - summary: >-
      No way forward once the invoice converted from a quote is cancelled (re-quote or duplicate).
    evidence: |-
      New feature, not in FUS-FR18. Routed to 20-12.
    severity: low
  - summary: >-
      A quote cannot be sent to the client (WhatsApp, link, SMS, email).
    evidence: |-
      assertSendable refuses quotes. Sending belongs to 20-7 (public links) and 20-8 (SMS/email). Routed to 20-7.
    severity: medium
---

<intent-contract>

## Intent

**Problem:** Bref merchants make quotes, edit them, then validate them into invoices (FUS-FR18). Keevo invoicing only knows invoices (DRAFT → ISSUED → PAID / CANCELLED) with a legal gapless `FAC-` numbering and a DOCUMENTS usage quota consumed at issue. A quote must never take a legal number nor consume the quota; its conversion must.

**Approach:** Add a `kind` (`INVOICE` | `QUOTE`) to `invoices` (invoicing module V8). A quote lives in status `DRAFT` (editable), and ends `CONVERTED` (linked to the new invoice) or `CANCELLED` (no number). Conversion creates a **new** invoice row with the quote's client and lines, issued through the existing issue sequence (lock, quota DOCUMENTS, gapless `FAC-` number, `issue_date` = conversion date in WAT), and links both rows, in one transaction, idempotently. Edits accept an optional `expectedUpdatedAt` (409 on mismatch). REST + sync (`CONVERT_QUOTE`) + pull + PDF + audit follow.

## Boundaries & Constraints

**Always:**
- Additive for V1 apps (FUS-NFR1): an absent `kind` means `INVOICE`; existing request bodies and responses keep working (responses only gain `kind`, `quoteReference`, `convertedInvoiceId`, `convertedAt`, `sourceQuoteId`); `GET /api/v1/invoices` without `kind` returns invoices only (unchanged list for V1); `kind=QUOTE` returns quotes only; `kind=ALL` returns both. The sync pull carries quotes with `kind` (V1 apps ignore unknown fields; any action they attempt on a quote is refused by the server with the codes below).
- A quote never has `number`, `issue_date`, `issued_at`; never consumes `invoice_counters` nor the DOCUMENTS quota; never publishes `InvoiceIssuedEvent`. Its display reference is `quoteReference = "DV-" + first 8 hex chars of its id, upper case` (Bref parity: computed, not stored, no counter).
- Quote lifecycle: create (`kind: "QUOTE"`) → `DRAFT`; edit/delete as a draft (`assertEditable`, soft delete unchanged); `cancel` a DRAFT quote → `CANCELLED` (reason required ≤ 500, like invoices, `cancelled_at` set, no number); `convert` a DRAFT quote → `CONVERTED`. Issue, pay, send, credit-note on a quote → 409 `INVOICE_INVALID_TRANSITION` (French message: « Un devis doit être converti en facture »). Edit/cancel/convert of a CONVERTED or CANCELLED quote → 409 `INVOICE_NOT_EDITABLE` / `INVOICE_INVALID_TRANSITION` as today's state rules.
- Conversion (`POST /api/v1/invoices/{quoteId}/convert`, optional body `{invoiceId}`; sync `CONVERT_QUOTE {quoteId, invoiceId}`), one transaction: lock the quote (`FOR UPDATE`); if it is already `CONVERTED` → return the existing invoice (200, idempotent, no second number, no second quota use); if not DRAFT/QUOTE → 409; quota DOCUMENTS `assertAndIncrement` (counted) — `QUOTA_EXCEEDED` leaves the quote untouched; insert the new invoice (`kind INVOICE`, given `invoiceId` or a new UUID, client and lines copied, `source_quote_id = quoteId`) and issue it through the existing issue path (seller snapshot, VAT, number, `issue_date` = today WAT, `InvoiceIssuedEvent`); set quote `status CONVERTED`, `converted_invoice_id`, `converted_at`. A unique partial index on `source_quote_id` makes a concurrent double conversion fail instead of producing two invoices. Permission: same as issue (`INVOICE_SEND`), entitlement `invoicing`.
- Optimistic edit check: `PUT /api/v1/invoices/{id}` and sync `UPDATE_INVOICE` accept optional `expectedUpdatedAt` (ISO instant); when present and different from the locked row's `updated_at` (compared at microsecond precision) → 409 `OPTIMISTIC_LOCK` (existing code, French message) with no write; absent → today's behaviour. Applies to quotes and draft invoices alike.
- An issued invoice stays non-editable (existing `assertEditable` refusal kept and tested).
- PDF of a quote: title « DEVIS DV-XXXXXXXX », no legal number line, mention « Devis — en attente de validation », file name `devis-DV-XXXXXXXX.pdf`. Quotes cannot be sent (WhatsApp/link) in this story.
- Audit (French labels, no UUIDs): quote created, converted (« Devis converti en facture FAC-… »), cancelled; labels mirrored in `keevo/contracts/export-datasets.json` and the Flutter test-client mirror.
- Sync contract `keevo/contracts/invoice-sync-payloads.json` gains: create quote, update with `expectedUpdatedAt`, convert, cancel quote; the backend contract test reads them.
- FUS-NFR11: list by kind uses an index `(kind, status, created_at)`; conversion is a constant number of queries (no per-line query loop beyond today's issue path).

**Never:**
- No change to the `FAC-` numbering, the counter table, POS automatic invoices, credit notes, or reporting rules; no quote numbering series.
- No public mirror (invoice tables are tenant-only, no JPA entity). No dashboard API. No deliverable Flutter feature (test-client mirrors only, `pubspec` bump if touched).
- No in-place mutation of a quote into an invoice (Bref does that; Keevo keeps two rows).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create quote | `kind:"QUOTE"`, client, lines | 201 DRAFT quote, no number, `quoteReference DV-…`, counter and quota untouched | — |
| V1 create | body without `kind` | invoice draft as today | — |
| Edit with stale check | `expectedUpdatedAt` ≠ row | 409 `OPTIMISTIC_LOCK`, nothing written | — |
| Edit without check | no `expectedUpdatedAt` | applied | — |
| Convert | DRAFT quote, quota available | 201/200 invoice ISSUED, next gapless `FAC-` number, `issue_date` today WAT, quote CONVERTED + links, quota +1 | — |
| Convert replay | quote already CONVERTED (REST or `CONVERT_QUOTE` replay) | same invoice returned, no new number, quota unchanged | — |
| Convert over quota | DOCUMENTS exhausted | quote unchanged, no number taken | 403 `QUOTA_EXCEEDED` |
| Issue/pay/send a quote | DRAFT quote | refused | 409 `INVOICE_INVALID_TRANSITION` |
| Cancel quote | DRAFT quote + reason | CANCELLED, no number | missing reason 400 |
| Convert cancelled quote | CANCELLED | refused | 409 |
| Edit issued invoice | ISSUED | refused as today | 409 `INVOICE_NOT_EDITABLE` |
| Lists | default / `kind=QUOTE` / `kind=ALL` | invoices only / quotes only / both | bad kind 400 |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/`; `R` = `resources/db/modules/invoicing`, `J` = `java/com/keevo/invoicing/invoice`.
- `R/V1__invoicing.sql:34-73` -- `invoices` (status CHECK `:37-38` `IN ('DRAFT','ISSUED','PAID','CANCELLED')`; row CHECKs `:70-72` unnamed: non-DRAFT requires number + issue_date + issued_at; CANCELLED requires cancelled_at + reason), `invoice_items` `:78-91`, `invoice_counters` `:94-97`. V2–V7 add columns (V7 `deleted_at`). New `R/V8__invoices_kind_quote.sql`: `kind`, `converted_invoice_id`, `converted_at`, `source_quote_id`, status CHECK + row CHECK relaxed for `kind='QUOTE'` (drop the unnamed constraints by catalog lookup, idempotent, `SET LOCAL lock_timeout`), index `(kind, status, created_at)`, unique partial index on `source_quote_id`. Keep `TenantSchemaProvisioner.SYNC_KEYSET_INDEXES` untouched.
- Domain: `J/domain/model/InvoiceStatus.java:9-13` (labels; add CONVERTED « Converti »), `J/domain/model/Invoice.java` (`draft()` :91, `withDraftContent` :102, `assertEditable` :110-114, `assertIssuable` :138, `issue()` :153, `pay` :177, `cancel` :216-226, `assertSendable` :306) -- add kind, quote rules, convert.
- Persistence: `J/domain/port/out/InvoiceRepository.java` (`Filter` :85), `J/adapter/out/persistence/JdbcInvoiceRepository.java` (status-guarded updates :128/:145/:198/:158/:166 + `expectOne`; list SQL :203-239; counter upsert :244-253; `findByIdForUpdate` :65; `delta()` :263-289).
- Use cases: `J/application/usecase/ManageDraftInvoiceUseCase.java` (create :208-232 idempotent, update :235, delete :246), `J/application/usecase/InvoiceLifecycleUseCase.java` (issue :79-102: lock, assertIssuable, quota :93, number :59-61 `FAC-%04d-%06d`, issue_date WAT :90, `InvoiceIssuedEvent`) -- conversion reuses this path inside one transaction.
- REST: `J/adapter/in/rest/InvoiceController.java` (endpoints table: POST :99, GET list :115-153 + `parseStatus` :280, PUT :182, DELETE :191, issue :200, pay :213, cancel :227, pdf :238, send :249; `@RequiresPermission` + `@RequiresEntitlement(InvoicingModule.ID)`), DTOs `J/adapter/in/rest/dto/InvoiceRequestDto.java:21-27`, `CancelInvoiceRequestDto`, `InvoiceResponseDto.java:30-60`. Permissions `J/../module/InvoicingModule.java:28-31`.
- Errors: `shared/domain/exception/ErrorCode.java:92-93` (OPTIMISTIC_LOCK 409), `:186-196` (INVOICE_*), `shared/infrastructure/web/ErrorMessages.java:131-140,153`, `GlobalExceptionHandler.java:179-190`.
- Concurrency pattern to mirror: `commerce/sale/adapter/out/persistence/jpa/SaleSpringRepository.java:109-112`, `ValidateSaleService.java:239-247`. Bref: `/home/toor/Project/FreeLance/AI/api-bref/src/utils/assert-fresh-update.ts:12-19`.
- Quota: `shared/capability/entitlement/UsageQuotaPort.java:52/58`, impl `subscription/plan/application/service/UsageQuotaService.java:72-84` (`Propagation.MANDATORY`).
- Sync: `J/adapter/in/sync/InvoiceSyncHandler.java` (ops :53-57, permission map :60-65, validate :96-102, switch :110-130), `sync/sync/application/strategy/ConflictStrategyRegistry.java` (invoice ops are AppendOnly — keep), `J/adapter/in/sync/InvoiceDeltaProvider.java` (rows = `InvoiceResponseDto` :87-91), contract `keevo/contracts/invoice-sync-payloads.json`, test `src/test/java/com/keevo/invoicing/invoice/adapter/in/sync/InvoiceSyncHandlerTest.java:110,246-249`.
- PDF: `J/adapter/out/pdf/InvoicePdfRenderer.java:58-60`, file name `J/application/usecase/InvoiceDocumentUseCase.java:101-103`.
- Audit: `shared/infrastructure/web/AuditEventListener.java:935-990`, `shared/infrastructure/export/AuditLabels.java:107-112,137`, events `shared/event/catalog/invoicing/`, contract `keevo/contracts/export-datasets.json:1074-1078,1132`, test `src/test/java/com/keevo/reporting/export/ExportContractFileTest.java`.
- Bref reference (read-only): `api-bref/src/invoice/domain/invoice.ts:15-31` (QUOTE/SALE, `DV-` display ref), `invoice.controller.ts:257-260,280-294`, `invoice.service.ts:200-245,558-595`, `invoice-pdf.service.ts:82-101`.

## Tasks & Acceptance

**Execution:**
- `R/V8__invoices_kind_quote.sql` -- columns, constraints, indexes as above.
- Domain (`Invoice`, `InvoiceStatus`, a `InvoiceKind` enum, quote reference helper) -- quote rules and `convert`, pure and unit-tested.
- Repository port + JDBC adapter -- kind in insert/select/list filter, convert writes (status-guarded), `expectedUpdatedAt` compare under lock.
- Use cases -- create quote, update with optional check, cancel quote, new `ConvertQuoteUseCase` (single transaction, idempotent).
- REST -- `kind` on create, `kind` list filter (default INVOICE, `QUOTE`, `ALL`), `expectedUpdatedAt` on PUT, `POST /{id}/convert`, response fields; PDF branch; `docs/entitlements/endpoints.md` regenerated.
- Sync -- `kind` on CREATE_INVOICE, `expectedUpdatedAt` on UPDATE_INVOICE, `CONVERT_QUOTE` (permission as ISSUE), contract file entries + test.
- Events/audit/labels + export contract + Flutter test-client mirror (bump `pubspec.yaml` if changed).
- Tests: unit (domain rules, quote reference), use-case (each matrix row), REST controller, sync handler (incl. replay), Postgres integration (V8 on an existing schema with ISSUED/CANCELLED invoices: constraints still enforced for invoices, quotes allowed; gapless numbering across a conversion and an ordinary issue; concurrent double conversion → one invoice).
- Spec section `## Auto Run Result

Status: done

**Change:** quotes as `kind = QUOTE` on `invoices` (invoicing V8 + V9 list index): create, edit with optional `expectedUpdatedAt` (409 French), cancel without number, convert into a NEW issued invoice through the existing issue path (DOCUMENTS quota, gapless `FAC-` number, `issue_date` = conversion day WAT) with links both ways, idempotent and race-safe (row lock + `ON CONFLICT DO NOTHING` + unique `source_quote_id`), refused when VAT settings changed since the quote or when the quote is incomplete; display reference `DV-XXXXXXXX` (computed, searchable); lists `?kind=` (default invoices only, `QUOTE`, `ALL`), quote date filter on creation day; PDF « DEVIS DV-… »; audit QUOTE_CREATED/CONVERTED/CANCELLED; sync `CONVERT_QUOTE`, `kind` / `expectedUpdatedAt` on existing ops, contract `quoteOperations`.

**Files (main):** `db/modules/invoicing/V8__invoices_kind_quote.sql`, `V9__invoices_kind_list_index.sql`; invoicing domain (`InvoiceKind`, `InvoiceStatus.CONVERTED`, `Invoice` quote rules), `JdbcInvoiceRepository`, `ManageDraftInvoiceUseCase`, new `ConvertQuoteUseCase`, `InvoiceLifecycleUseCase` (quote cancel), `InvoiceController` + DTOs, `InvoiceSyncHandler`, `InvoicePdfRenderer`, audit listener/labels, `keevo/contracts/invoice-sync-payloads.json`, `export-datasets.json`, `docs/entitlements/endpoints.md`; Flutter test client audit mirror 2.0.26+98.

**Review:** 31 triage rows (40 reviewer findings): 18 patched (medium: list index V9, conversion race → idempotent, VAT-changed refusal, optimistic check on convert, DV- search, quote date filter, other invoice readers guarded + test, V1 sync actions on quotes tested, cancel-quote event test; low: kind mismatch 409, one kind rule, CONVERT_QUOTE validation, French optimistic-lock message, incomplete-quote wording), 3 deferred (quote end permissions → 19-5b, cancelled converted invoice → 20-12, sending a quote → 20-7), 10 rejected with reasons in the triage log.

**Follow-up review recommended:** true — several medium entries patched; named risk: the conversion path now has three outcomes on the same insert (created, replay via source_quote conflict, taken id) and a VAT-drift refusal, covered by unit and full-context tests but new.

**Verification:**
- Full backend suite package by package: 6,131 tests, 0 failures.
- Live on keevo_dev (WhatsApp noop, FCM/Expo off), test tenant with invoicing activated for the check then deactivated again: quote created `DV-DF466E29` without number; `?kind=QUOTE&search=DV-DF466E` → 1, default list → 0; convert with stale `expectedUpdatedAt` → 409 French; convert → 201 `FAC-2026-000002` ISSUED dated 2026-11-06; replay → 200; quote CONVERTED with link. Implementer's earlier live pass: PDF `devis-DV-….pdf`, issue on a quote refused.

**Residual risks:** V8/V9 apply lazily per tenant on first invoicing call (upgrade tested on V1–V7 data, not yet on real prod copies); V1 apps receive quotes in the pull as DRAFT rows (refused server-side if acted on).

## Mobile (React Native — dépôt AI/bref)` and sprint key `m20-3-devis` (finalize).

**Acceptance Criteria:**
- Given a tenant with the invoicing module active, when the matrix scenarios run over REST and sync, then every row holds and responses are supersets of today's.
- Given existing tenants, when the new build boots, then V8 applies on every schema without altering existing invoices, and the full backend suite passes package by package.
- Given a live local backend on keevo_dev (WhatsApp noop, FCM/Expo off), when a quote is created, edited with a stale `expectedUpdatedAt`, converted twice and its PDF downloaded, then 409 / one invoice with the next `FAC-` number / « DEVIS » PDF are observed.

## Design Notes

Pattern: **state machine on the aggregate + application service for the cross-row operation**. `Invoice` gains kind-aware guards (a quote cannot issue/pay/send; an invoice cannot convert); `ConvertQuoteUseCase` orchestrates lock → quota → create+issue → link in one transaction, reusing the issue path so numbering and quota stay single-sourced. Idempotency rests on the quote's `CONVERTED` state read under the row lock, with the unique `source_quote_id` index as the last guard.

Cost: convert = 1 locked read + 1 quota upsert + 1 counter upsert + invoice insert + items insert (batch) + 2 updates — constant; list by kind = one indexed query with the existing pagination; edit check adds no query (compare on the already-locked row).

## Verification

**Commands:**
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/invoicing/**/*Test*'` -- expected: pass
- `cd keevo/backend && mvn -o -q test -Dtest='com/keevo/reporting/export/**/*Test*,com/keevo/sync/**/*Test*,com/keevo/architecture/**/*Test*'` -- expected: pass
- Full suite package by package (`run-pkgs-wait.sh`) -- expected: 0 failures
- Live: local backend on keevo_dev with WhatsApp noop, FCM/Expo off; curl create/edit/convert/pdf on a test tenant with invoicing activated -- expected: as in the AC.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée sur `refonte/modulaire` / `develop`, commit `62b28275`. Conventions générales : spec-16-11 ; protocole de synchro : spec-16-10.

**Ce qui est fait côté API :** un devis est un document à part entière : on le crée, on le modifie, on l'annule ou on le valide en facture. Il n'a jamais de numéro légal et ne consomme pas le quota de documents. Sa validation crée une nouvelle facture émise (prochain numéro `FAC-…`, date du jour, quota consommé) et relie les deux documents ; valider deux fois un devis rend la même facture. Toute modification peut transmettre la date de dernière modification connue : si le document a changé entre-temps (autre appareil, autre utilisateur), la modification est refusée.

**À utiliser :**
1. Créer un devis : `POST /api/v1/invoices` avec `"kind": "QUOTE"` en plus du corps habituel (`id` facultatif choisi par l'appareil, `client`, `lines`) ; synchro `CREATE_INVOICE` avec `kind: "QUOTE"`. Sans `kind` : une facture, comme aujourd'hui.
2. Champs ajoutés à chaque document (REST, pull `invoices`) : `kind` (`INVOICE` | `QUOTE`), `quoteReference` (référence d'affichage `DV-XXXXXXXX`, seulement pour un devis), `convertedInvoiceId` et `convertedAt` (devis validé), `sourceQuoteId` (sur la facture issue d'un devis). Statuts d'un devis : `DRAFT` (en cours, modifiable), `CONVERTED` (« Converti »), `CANCELLED` (« Annulé »).
3. Listes : `GET /api/v1/invoices` = factures seulement (inchangé) ; `?kind=QUOTE` = devis seulement ; `?kind=ALL` = tout ; autre valeur → 400. Les autres filtres s'appliquent aussi : pour un devis, `from`/`to` portent sur son jour de création et `search` trouve aussi sa référence `DV-…`.
4. Modifier : `PUT /api/v1/invoices/{id}` (synchro `UPDATE_INVOICE`) avec `expectedUpdatedAt` facultatif = le `updatedAt` reçu en dernier (tel quel). Document modifié depuis → 409 `OPTIMISTIC_LOCK` (synchro `REJECTED OPTIMISTIC_LOCK`), rien n'est écrit : recharger le document et proposer de refaire la modification.
5. Valider en facture : `POST /api/v1/invoices/{quoteId}/convert` avec corps facultatif `{"invoiceId": "<uuid choisi par l'appareil>", "expectedUpdatedAt": "<updatedAt du devis>"}` → 201 et la facture émise ; déjà validé → 200 et la même facture. Synchro `CONVERT_QUOTE` `{quoteId, invoiceId, expectedUpdatedAt?}` (entityId de l'opération = le devis) → `serverEntityId` = l'id de la facture. Devis modifié depuis → 409 `OPTIMISTIC_LOCK`. Mêmes droits que l'émission (employé autorisé, lecteur non).
6. Annuler un devis : `POST /api/v1/invoices/{id}/cancel` `{"reason": "…"}` (propriétaire, motif obligatoire ≤ 500) ; synchro `CANCEL_INVOICE`. Supprimer un devis en cours : `DELETE /api/v1/invoices/{id}` comme un brouillon.
7. PDF : `GET /api/v1/invoices/{id}/pdf` → « DEVIS DV-XXXXXXXX », mention « Devis — en attente de validation », fichier `devis-DV-XXXXXXXX.pdf`. Un devis ne s'envoie pas (WhatsApp/lien) dans cette version.
8. Erreurs : émettre, encaisser, envoyer ou faire un avoir sur un devis → 409 `INVOICE_INVALID_TRANSITION` « Un devis doit être converti en facture » ; valider ou annuler un devis déjà validé/annulé → 409 `INVOICE_INVALID_TRANSITION` ; modifier un devis validé/annulé → 409 `INVOICE_NOT_EDITABLE` ; quota de documents épuisé à la validation → 403 `QUOTA_EXCEEDED` (le devis reste en cours, aucun numéro pris) ; devis incomplet (sans ligne ou sans nom de client) → 422 `INVOICE_NOT_ISSUABLE` « Ce devis est incomplet : … » ; réglages de TVA changés depuis le devis → 409 `INVOICE_INVALID_TRANSITION` (enregistrer le devis à nouveau, puis le valider) ; `kind` différent de celui du document (création renvoyée ou modification) → 409 `INVOICE_INVALID_TRANSITION` ; motif d'annulation manquant → 422 `VALIDATION_ERROR` ; `invoiceId` déjà utilisé par un autre document → 422 `VALIDATION_ERROR`.
Contrat de synchro : `keevo/contracts/invoice-sync-payloads.json` (`quoteOperations`). Code : `keevo/backend/src/main/java/com/keevo/invoicing/invoice/adapter/in/rest/InvoiceController.java`, `ConvertQuoteUseCase.java`, `ManageDraftInvoiceUseCase.java`, `adapter/in/sync/InvoiceSyncHandler.java`.

**À implémenter côté mobile :**
1. Écran « Nouveau devis » (même saisie qu'une facture) et liste « Devis » (`kind=QUOTE`) avec la référence `DV-…`, le client, le total et le statut en français.
2. Sur un devis en cours : « Modifier », « Valider en facture » (confirmation : « Une facture numérotée sera émise »), « Annuler » (motif), « Télécharger le PDF ». Après validation, ouvrir la facture obtenue ; sur un devis validé, lien « Voir la facture ».
3. Hors ligne : générer l'`invoiceId` sur l'appareil et mettre `CONVERT_QUOTE` en file ; afficher la facture en attente de numéro jusqu'à la synchro.
4. Envoyer `expectedUpdatedAt` à chaque modification ; sur `OPTIMISTIC_LOCK`, message « Ce devis a été modifié ailleurs, rechargez-le ».
5. Aucune donnée technique à l'écran (ni identifiants, ni codes).

**Critères d'acceptation mobile :**
- Given un devis en cours, when l'utilisateur le valide, then une facture numérotée `FAC-…` datée du jour s'ouvre et le devis passe « Converti ».
- Given deux appareils qui modifient le même devis, when le second enregistre, then il est prévenu et rien n'est écrasé.
- Given le quota de documents épuisé, when l'utilisateur valide un devis, then le message de quota s'affiche et le devis reste modifiable.
- Mise en place : `curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"kind":"QUOTE","client":{"name":"Awa"},"lines":[{"description":"Riz","quantity":1,"unitPrice":10000}]}' $API/api/v1/invoices`.

**Clé de suivi mobile :** `m20-3-devis`.
