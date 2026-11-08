---
title: '20-8 Sending documents (invoice or quote) by SMS and email, besides WhatsApp; offline SEND_INVOICE'
type: 'feature'
created: '2026-11-08'
status: 'done'
baseline_revision: 'dc300775083743469e0b6577abdd258be535e2ca'
review_loop_iteration: 1
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-20-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-7-modeles-signatures-liens-publics.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-9-fournisseurs-sms-email-notifications.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Keevo sends a document to its client by WhatsApp only (14.2, quotes since 20.7). Bref merchants also send by SMS and email (`InvoiceOutboxProcessor`, channels `email|sms|whatsapp`), and many clients have no WhatsApp. The app also cannot queue a sending offline. FUS-FR22.

**Approach:**
- `POST /invoices/{id}/send` gains an optional `channel` (`WHATSAPP` by default, `SMS`, `EMAIL`) and an optional `email`. The body without `channel` behaves exactly as today.
- Each channel is a strategy: it resolves the recipient, checks availability, builds the message (Bref content plus the 20.7 public link) and delivers it.
- SMS and email go through `TenantMessaging` (19.9). It probes the quota, sends with no transaction open, then counts the `SMS` / `EMAILS` metric after provider success, in its own short transaction.
- Recording the sending and the audit stays in one short transaction on the locked row, after delivery. A failure records nothing on the document.
- Bref's durable outbox (`invoice_outbox`) becomes `invoice_send_requests`. The offline `SEND_INVOICE` only queues a request in the push transaction; an after-commit worker or a ShedLock poller sends it, with up to 5 attempts. The online `POST /send` stays synchronous and logs every attempt in the same table.
- Bref's `channels: [..]` is accepted next to `channel`, each channel processed independently.

## Boundaries & Constraints

**Always:**
- **Additive API.**
  - `channel` absent or `null` means WhatsApp, with the same codes and messages as 14.2/20.7.
  - `phone` stays the WhatsApp/SMS override.
  - `email` is the email override.
  - The response gains `lastSentChannel` and `lastSentChannelLabel` (« WhatsApp », « SMS », « E-mail »).
  - `lastSentTo` stays masked: a phone as today; an email as « a\*\*\*@domaine.cm ».
  - `channels` (Bref) returns `SendResultsDto {invoice, results[{channel, channelLabel, status, statusLabel, errorCode, error}]}` with 200, even when some channels failed.
  - The response gains `lastSendStatus` (PENDING | SENT | FAILED | UNKNOWN) with `lastSendStatusLabel`, `lastSendStatusChannel`, `lastSendErrorCode` and the French `lastSendError`: the outcome of a queued sending, read through the pull.
  - Invoicing V18 only (merge order 20-14 V16, 20-13 V17, then this V18).
    - Widens `last_sent_to` to 255 characters, a metadata-only change.
    - Adds nullable `last_sent_channel` (NULL with `send_count > 0` reads as WhatsApp), `last_send_status`, `last_send_status_channel` and `last_send_error_code`. The CHECK constraint is added `NOT VALID`, then validated.
    - Adds the `invoice_send_requests` table (outbox and send log).
- **Cross-field checks** (422 `VALIDATION_ERROR`, French): an `email` without the EMAIL channel; a `phone` when EMAIL is the only channel; an empty `channels`. A blank `channel` means WhatsApp.
- **Public base.** A link sent outside the app must be absolute. REST derives it from the request. The poller has no request, so it uses `keevo.public-base-url` (`KEEVO_PUBLIC_BASE_URL`, **required in production**). Without it the request fails with `PUBLIC_BASE_URL_MISSING` « Adresse publique de l'application non configurée » and the log line `INVOICE_SEND_NO_PUBLIC_BASE`.
- **Sendable documents.** Same as 20.7: an issued or paid invoice, or a quote waiting for validation. Anything else gives 409, checked before anything else.
- **Recipient.**
  - Comes from the body override, else from the document's client snapshot.
  - SMS uses the client's phone, normalised to E.164 (+237 added to a 9-digit number).
  - EMAIL uses the client's email, trimmed and checked as `local@domain.tld`.
  - A missing or invalid recipient gives 422 with a French message per channel. Nothing is sent or counted.
- **Order of checks.** Sendable → recipient → channel available (503) → rate limit (30 per tenant per hour, shared by all channels; 429) → quota probe (`QUOTA_EXCEEDED` 403, nothing sent) → send → count → record.
- **Failure.**
  - Covers a WhatsApp port exception (its failure contract, as 14.2), or SMS / email not delivered by every provider of the failover chain. Any other exception (programming error, PDF rendering) propagates unmapped.
  - Returns 502 with a French message.
  - **Uncertain outcome** (the provider received the request but did not answer, failover `UNKNOWN`): 409 `INVOICE_SEND_UNCERTAIN` « Envoi incertain : vérifiez auprès du client avant de renvoyer ». Not counted, not recorded, never retried automatically, and the rate-limit slot stays used.
  - **After delivery**, a recording failure never surfaces as an error: `INVOICE_SENT_UNRECORDED` is logged and the answer is a success (a retry would send twice).
  - Nothing changes on the document (no `send_count`, no `updated_at`), no quota unit is counted, no audit row is written, and the rate-limit slot is released.
  - A WARN log line records every refused attempt with its code (`INVOICE_SEND_FAILED channel tenant code`). Never the recipient or the message. Every attempt also leaves an `invoice_send_requests` row (SENT, FAILED or UNKNOWN).
- **Message content.** French, Bref's wording, with the public link signed by `InvoicePublicLinkUseCase` (the document's current link version, the tenant's lifetime).
  - **SMS:**
    - The text is GSM-7: accents are transliterated (Bref writes « Telechargez ») and newlines are kept.
    - At most 306 characters, so 2 concatenated segments, because the link alone is about 170 characters.
    - The shop name is shortened first, then dropped, then the thanks line; an abnormally long reference is shortened last. The link is never cut (a link alone over 306 characters is sent whole, with a warning). The backtick becomes an apostrophe.
    - Invoice: « Facture FAC-2026-000001 - Boutique Awa SARL⏎Merci pour votre achat !⏎Votre facture : https://… ».
    - Quote: « Devis DV-1A2B3C4D - Boutique Awa SARL⏎Merci ! Nous revenons vers vous pour confirmer votre commande.⏎Votre devis : https://… ».
  - **Email:**
    - Subject: « Votre facture FAC-2026-000001 — Boutique Awa SARL » or « Votre devis DV-1A2B3C4D — Boutique Awa SARL ». The Keevo reference replaces Bref's « #shortId », which is a piece of UUID.
    - Text body: « Bonjour {client},⏎⏎Veuillez trouver ci-joint votre facture FAC-… de {boutique}.⏎⏎Montant : 28 850 XAF⏎⏎Consultez votre facture en ligne : {lien}⏎⏎Merci de votre confiance. ».
    - HTML body: Bref's card, with header (shop, address, phone), N° FACTURE/DEVIS, STATUT, MONTANT TOTAL, a « Voir la facture en ligne » button and the link validity. All values are HTML-escaped.
    - Status shown: « En attente de validation » (quote), « Payée ✓ », « Partiellement payée », « En retard », else « En attente de paiement ».
    - The PDF is attached (`FAC-….pdf` / `DV-….pdf`), as in Bref, rendered only after the quota probe passed. `fromName` is the shop name.
- **Audit.**
  - `InvoiceSentEvent` gains `channel`. A registry replay of an older event reads `null`, which means WhatsApp.
  - The audit row (`INVOICE_SENT` / `QUOTE_SENT`) carries `channel` in its after values, with French labels: field « Canal d'envoi », values « WhatsApp » / « SMS » / « E-mail ».
  - `sentTo` holds the masked phone or email.
- **Sync `SEND_INVOICE` (outbox).**
  - Payload: `{invoiceId, channel? | channels?, phone?, email?}`, validated like the REST body; `entityId` must equal `invoiceId`. Permission: `INVOICE_SEND`.
  - In the per-operation transaction the handler only validates (sendable document, recipient of each channel) and inserts one PENDING row per channel keyed on `(operation_id, channel)`, then answers APPLIED (= queued). Nothing is sent inside the push. A refusal at that point is REJECTED with its code, and nothing is queued.
  - The send happens after commit: an after-commit hook hands the rows to a small bounded worker pool (a full pool drops the task, and the poller picks the row up). A ShedLock poller runs every 30 s across tenants.
  - One send per request: a worker sends only a row it claimed with `UPDATE … SET status = 'SENDING' WHERE status = 'PENDING'`, and the unique key allows one row per operation and channel. A pushed again operation is DUPLICATE from the operation log and inserts nothing.
  - Outcome: SENT; UNKNOWN (never retried); a retryable failure (provider, channel off, rate limit, unexpected error) goes back to PENDING with backoff 30 s × 2^(n−1) until 5 attempts (Bref's `MAX_ATTEMPTS`), then FAILED; any other refusal (`QUOTA_EXCEEDED`, recipient, document no longer sendable, `PUBLIC_BASE_URL_MISSING`) is FAILED at once. A SENDING row older than 10 minutes (worker gone mid-send) ends UNKNOWN.
  - The final outcome is copied on the document (`last_send_status`, `updated_at` moved forward, legal content and send count untouched) so the app reads it in the pull.

**Never:**
- Never count a unit before or around the provider call, or for a failed or uncertain send. Never touch the invoice's legal content or send count on failure.
- Never send inside the sync push transaction, and never retry an UNKNOWN outcome or a refusal. Bref retries a whole outbox row and can send a channel twice; Keevo has one row per channel and claims it once.
- No new quota metric for WhatsApp.
- No real SMS, email or WhatsApp in tests (noop/fake providers). No change to keevo/app.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| WhatsApp, legacy body | `{"phone":"…"}` or no body | exactly 14.2/20.7; `lastSentChannel = WHATSAPP` | unchanged codes |
| SMS success | issued invoice, client phone `677000111`, SMS quota 2/5 | SMS to `+237677000111` with reference + link (≤ 306 GSM-7 chars); `send_count+1`; SMS counter 3; audit `channel=SMS` | none |
| Email success | quote, client email `compta@ndolo.cm` | email with Bref subject/body, link and PDF attached; EMAILS counter +1; audit `QUOTE_SENT` channel EMAIL | none |
| Override | `{"channel":"EMAIL","email":"x@y.cm"}` | sent to the override | invalid → 422 `INVOICE_EMAIL_INVALID` |
| No phone (SMS) | client without phone, no override | nothing sent | 422 `INVOICE_SMS_PHONE_REQUIRED` « Indiquez le numéro de téléphone du client pour l'envoi par SMS » |
| Bad phone (SMS) | `"6990"` | nothing sent | 422 `INVOICE_SMS_PHONE_INVALID` |
| No email | client without email | nothing sent | 422 `INVOICE_EMAIL_REQUIRED` « Indiquez l'adresse e-mail du client » |
| Channel off | no SMS/email provider configured | nothing sent | 503 `SMS_UNAVAILABLE` / `EMAIL_UNAVAILABLE` |
| Quota exhausted | 5/5 SMS | nothing sent, counter unchanged | 403 `QUOTA_EXCEEDED` (19.8 details) |
| Provider failure | every provider fails | invoice unchanged (`send_count`, `updated_at`), counter unchanged, no audit | 502 `SMS_DELIVERY_FAILED` / `EMAIL_DELIVERY_FAILED` |
| Unknown channel | `"channel":"FAX"` | nothing sent | 422 `VALIDATION_ERROR` « Canal d'envoi inconnu : choisissez WhatsApp, SMS ou e-mail » |
| Not sendable | draft invoice / converted quote | nothing sent | 409 as 20.7 |
| Uncertain | provider read timeout | nothing recorded, nothing counted, row UNKNOWN | 409 `INVOICE_SEND_UNCERTAIN` « Envoi incertain : vérifiez auprès du client avant de renvoyer » |
| Channels (Bref) | `{"channels":["sms","email"]}`, client without email | SMS sent, email refused | 200, `results` = SMS SENT, EMAIL FAILED « Indiquez l'adresse e-mail du client » |
| Cross-field | `{"channel":"SMS","email":"a@b.cm"}` | nothing sent | 422 `VALIDATION_ERROR` « L'adresse e-mail ne sert qu'à l'envoi par e-mail » |
| Sync queued | `SEND_INVOICE` pushed | APPLIED (queued), sent after commit; pushed again → DUPLICATE, still one send | push-time refusal → REJECTED with the code, nothing queued |
| Sync retry | provider fails | row PENDING, attempts 1, next in 30 s; pull `lastSendStatus` PENDING; sent by the poller once due | 5th failure → FAILED, pull `lastSendStatus` FAILED + French error |
| Sync uncertain / quota / no base | read timeout; quota 0; `KEEVO_PUBLIC_BASE_URL` unset | UNKNOWN (never retried) / FAILED `QUOTA_EXCEEDED` / FAILED `PUBLIC_BASE_URL_MISSING` | pull shows the status and the French error |

</intent-contract>

## Code Map

Paths under `keevo/backend/src/main/` unless stated.
- `java/com/keevo/invoicing/invoice/application/usecase/SendInvoiceUseCase.java` -- the 14.2 send path. It becomes the template: check, then strategy, then record.
- `java/com/keevo/invoicing/invoice/application/service/InvoicePhones.java` -- E.164 normalization and masking.
- `java/com/keevo/shared/capability/notification/{TenantMessaging,SmsSender,EmailSender,SmsMessage,EmailMessage,EmailAttachment,DeliveryResult}.java` -- 19.9 ports. `MeteredTenantMessaging` does the probe, send and count.
- `java/com/keevo/invoicing/invoice/domain/model/{Invoice,InvoiceDelivery}.java` -- `recordSending`, delivery info.
- `java/com/keevo/invoicing/invoice/adapter/out/persistence/JdbcInvoiceRepository.java` -- `markSent` and the row mapping.
- `java/com/keevo/invoicing/invoice/adapter/in/rest/{InvoiceController,PublicBaseUrl}.java`, `dto/{SendInvoiceRequestDto,InvoiceResponseDto}.java`.
- `java/com/keevo/invoicing/invoice/adapter/in/sync/InvoiceSyncHandler.java` -- new `SEND_INVOICE`.
- `java/com/keevo/shared/event/catalog/invoicing/InvoiceSentEvent.java`, `shared/infrastructure/web/AuditEventListener.java`, `shared/infrastructure/export/AuditLabels.java`.
- `java/com/keevo/shared/domain/exception/ErrorCode.java`, `shared/infrastructure/web/{ErrorMessages,GlobalExceptionHandler}.java`.
- `keevo/contracts/invoice-sync-payloads.json`, `keevo/contracts/export-datasets.json`.
- Bref (read-only): `api-bref/src/invoice/outbox/invoice-outbox.processor.ts` (SMS text sliced to 158, email subject/text/HTML, PDF attachment, quota checked then consumed after the send).

## Tasks & Acceptance

**Execution:**
- `resources/db/modules/invoicing/V18__invoice_send_channel.sql` -- widen `last_sent_to` to 255 characters and add `last_sent_channel` -- an email is longer than 20 characters.
- `domain/model/SendChannel.java` (new), `InvoiceDelivery`, `Invoice.recordSending(to, channel, now)` -- the channel is recorded with the sending.
- `application/service/InvoiceSendMessages.java` (new) -- pure builders for the SMS text (GSM-7, 306 characters) and the email (subject, text, HTML, escaping), plus `InvoiceRecipients` (email check and mask).
- `application/service/channel/{InvoiceSendChannel,WhatsAppInvoiceChannel,SmsInvoiceChannel,EmailInvoiceChannel}.java` (new) -- strategies.
- `SendInvoiceUseCase` -- `send(id, SendRequest, …)` dispatching to the strategies, with the transaction suspended around the send. The 14.2 signature delegates to WhatsApp.
- REST DTO `channel` / `email`, response `lastSentChannel(Label)`, sync `SEND_INVOICE` (+ `PublicBaseUrl.resolveCurrent()`), contract `sendOperations`.
- Event `channel`, audit after-values, labels (`channel` code key and values), 8 error codes with French messages and HTTP mapping.
- Tests:
  - Unit: messages, recipients, the use case per channel, the sync handler, labels.
  - Full context: every row of the matrix, with fake providers swapped into `MeteredTenantMessaging` and `SendInvoiceUseCase` and the real quota counters.
  - Baselines: `RegistryEventSerializationTest`, migration pins (V18), `EndpointEntitlementCoverageTest`, export contract.

- Review iteration 1: `V18` gains the outbox table and status columns; `InvoiceSendRequest`, `SendStatus`, `InvoiceSendRequestRepository` + JDBC adapter; `InvoiceSendOutbox` (after-commit worker, retry policy, lease) and `InvoiceSendOutboxJob` (ShedLock poller); `SendInvoiceUseCase.enqueue` / `deliverQueued` / `sendAll`; `SendResultsDto`; `DeliveryResult.uncertain`.

**Acceptance Criteria:**
- Given an issued invoice and quota left, when `POST /invoices/{id}/send {channel: SMS|EMAIL|WHATSAPP}` is called, then the message carries the public link, the sending is audited with its channel, the SMS/EMAILS counter grows by one only after success, and a failure leaves the invoice and the counter untouched.
- Given an offline sending, when `SEND_INVOICE` is pushed twice with the same `operationId`, then a single message leaves and the second answer is `DUPLICATE`.

## Spec Change Log

- **2026-11-08, coordinator review (iteration 1).** The first build sent `SEND_INVOICE` inside the push (transaction suspended), without retries; an uncertain provider outcome looked like a retryable 502; the poller-less link could be relative. The spec now requires Bref's durable outbox (`invoice_send_requests`, after-commit worker plus ShedLock poller, claim by status transition, 5 attempts with backoff), the UNKNOWN status, `channels[]`, the required `KEEVO_PUBLIC_BASE_URL` and the stricter validation. This avoids sends lost on a crash, double sends on a retried push and relative links. KEEP: the strategy registry, the probe/send/count rule, the pure message builders, REST synchronous.

## Review Triage Log

### 2026-11-08 — Coordinator review (10 items, all accepted and fixed)

| # | Finding | Verdict | Action |
|---|---|---|---|
| 1 | No durable outbox, sync sends inside the push, no retry, recording failure surfaced, uncertain mapped to 502, quota in background | high, patch | `invoice_send_requests` + `InvoiceSendOutbox` + `InvoiceSendOutboxJob`; REST logs every attempt; `channels[]`; `INVOICE_SENT_UNRECORDED`; UNKNOWN / 409 `INVOICE_SEND_UNCERTAIN`; FAILED `QUOTA_EXCEEDED` |
| 2 | Relative link when no request | high, patch | absolute base required, `PUBLIC_BASE_URL_MISSING`, `KEEVO_PUBLIC_BASE_URL` documented as required |
| 3 | Backtick not GSM-7; SMS length not guaranteed | medium, patch | backtick → apostrophe; reference shortened last; warning when the link alone exceeds 306 |
| 4 | PDF rendered before the quota probe; over-broad catch | medium, patch | `TenantMessaging.sendEmail(schema, Supplier)`; only provider outcomes mapped; every refusal logged with its code |
| 5 | Loose email regex; cross-field; blank channel; sync entityId | medium, patch | stricter regex + tests; French 422 cross-field; blank = WhatsApp; `entityId` = `invoiceId` |
| 6 | Channel registry unchecked | low, patch | exactly one strategy per channel, checked at startup |
| 7 | CHECK added validated at once | low, patch | `NOT VALID` then `VALIDATE` |
| 8 | Delegating helpers, wrong comment, duplicated slash helper, changed phone message | low, patch | removed; comment fixed; `InvoicePublicLinkUseCase.stripTrailingSlash` shared; message change noted |
| 9 | Missing tests | medium, patch | outbox (once per operation, backoff, UNKNOWN, two tenants, recording failure), long email, absolute sync link, email STATUT and snapshot, legacy channel, bad payloads, `channels[]` |
| 10 | Flutter labels | info | listed in the report and in Routed |

## Design Notes

**Patterns.**
- **Strategy for the channel.** `InvoiceSendChannel` defines `recipient(invoice, override)`, `assertAvailable()` and `deliver(message)`. It has three implementations, held in an `EnumMap`; the constructor requires exactly one per `SendChannel` and fails at startup otherwise. The use case is a *Template Method* per channel: check, then strategy, then record.
- **Transactional outbox** (Bref's `invoice_outbox`). The sync push writes the request in its own transaction (atomic with the operation log), and delivery happens after commit. *Claim-check by status transition*: `PENDING → SENDING` in one conditional UPDATE is the only lock, so two workers never send the same row. A unique key `(operation_id, channel)` makes queuing idempotent. Bounded retries with exponential backoff; a lease turns abandoned SENDING rows into UNKNOWN (never retried, because the message may have left).
- **Uncertain is a state, not an error.** The failover sender's `UNKNOWN` reaches the use case (`DeliveryResult.uncertain`) and becomes the UNKNOWN request status and the 409 `INVOICE_SEND_UNCERTAIN`. It is never mapped to a retryable 502.
- **Pure message builders** (`InvoiceSendMessages`). No I/O, so the SMS length and the email escaping are unit-tested.
- **Probe, send, count.** Quotas are reused from 19.9 (`TenantMessaging`); a supplier overload builds the email (and renders its PDF) only after the probe.
- **Transaction suspension.** Every send runs inside `PROPAGATION_NOT_SUPPORTED`, so no provider call ever holds a transaction, whoever the caller is.

**Hot-path costs (FUS-NFR11).** No loop over lines or documents, so there is no N+1.

| Step (one channel) | Cost |
|------|------|
| Invoice | 1 read by PK |
| Presentation | 1 read by PK |
| Preferences | 1 read of a 1-row table |
| Settings | at most 1 read, for the seller name of a quote |
| Rate limit | in memory |
| Quota probe | 1 short `REQUIRES_NEW` transaction, 1 guarded upsert, then rolled back |
| Providers | 1..N HTTP calls, with no transaction |
| Count | 1 short `REQUIRES_NEW` transaction, 1 guarded upsert |
| Record | 1 locked PK read + 1 UPDATE by PK + 1 audit insert, in one short transaction |
| Send log | 1 INSERT (online) or 1 UPDATE by PK (outbox) |
| Email only | the PDF render (same cost as `GET /pdf`) and 1 read of the stored payment state by PK |

- **Sync push.** 1 invoice read + 1 `INSERT … ON CONFLICT DO NOTHING` per channel + 1 UPDATE of the document status; a duplicate costs the operation-log PK lookup.
- **Poller tick.** 1 catalogue query (schemas holding the table), 1 live-tenant read, then per tenant 1 lease UPDATE (partial index on `updated_at` WHERE SENDING) and 1 set-based claim (`UPDATE … FROM (SELECT … WHERE status = 'PENDING' AND next_attempt_at <= now ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED)`, partial index on `next_attempt_at` WHERE PENDING). Beyond the claim, a claimed row costs only its send and 1–2 UPDATEs by PK. Idle cost: 1 query when no tenant has the table, otherwise 2 index-only statements per tenant.
- **Message size.** SMS O(1), at most 306 characters. Email O(1) HTML plus the PDF.

**Decisions.**
- **REST stays synchronous** for immediate feedback (Bref's app waits for nothing). REST logs every attempt but does not retry; the app offers « Réessayer ».
- **Unique key on `(operation_id, channel)`** rather than `operation_id` alone, because `channels[]` queues one row per channel for a single operation.
- **No retry of a refusal.** Bref retries any error, including a missing recipient or an exhausted quota. Keevo retries only provider, availability, rate-limit and unexpected errors.
- **SMS in 2 segments.** Bref sends 1 segment (158 characters) because its link is a short download URL. Keevo's signed link is about 170 characters, so the text uses 2 GSM-7 segments (306 characters), billed as 2 by some providers. The quota counts 1 SMS per sending, as Bref does (`consumeSms`).
- **Remaining balance not in the email.** Bref's « RESTE À PAYER » row is left out: the attached PDF and the link show the exact balance after credit notes (20.7). The status label is kept.
- **Platform sender address.** The configured `keevo.email.from` is used, with the shop name as display name.
- **WhatsApp outcome.** The WhatsApp port reports failure by throwing and has no uncertain outcome; any exception there stays `WHATSAPP_DELIVERY_FAILED`, as in 14.2.
- **Over-long phone message.** Since 20.8 it reads « Le numéro de téléphone est trop long » (14.2: « Le numéro WhatsApp est trop long »), because the number is also an SMS one.

## Verification

**Commands:**
- `cd keevo/backend && bash kmvn.sh -o -q test -Dtest='com/keevo/<pkg>/**/*Test*' -Dsurefire.failIfNoSpecifiedTests=false`, run for each of invoicing, messaging, reporting, architecture, shared and sync -- expected: 0 failures.

## Auto Run Result

Status: done (review fixes applied, ready for triage)

**Change:**
- **REST.** `POST /invoices/{id}/send` takes an optional `channel` (`WHATSAPP` default, blank = WhatsApp | `SMS` | `EMAIL`, case-insensitive), Bref's `channels: [..]` and `email`. Without `channel` the behaviour is exactly 14.2/20.7. With `channels`, the answer is `{invoice, results[]}`, one result per channel.
- **SMS.** Bref's text plus the signed public link, GSM-7 (backtick replaced), at most 306 characters.
- **Email.** Bref's subject, text and HTML card plus link; the PDF is attached, rendered after the quota probe.
- **Metering.** Both go through `TenantMessaging`, counted only after provider success.
- **Outcomes.** Uncertain gives 409 `INVOICE_SEND_UNCERTAIN` (not counted, never retried). A recording failure after delivery is logged `INVOICE_SENT_UNRECORDED` and answered as success. Every attempt is logged in `invoice_send_requests`.
- **Offline `SEND_INVOICE`.** Validated and queued in the push (APPLIED = queued), sent after commit by a bounded worker pool or by the ShedLock poller (`InvoiceSendOutboxJob`, 30 s). One send per operation and channel. Up to 5 attempts with backoff 30 s × 2^(n−1); UNKNOWN is never retried; refusals (quota, recipient, no public base) are FAILED at once; abandoned SENDING rows end UNKNOWN. The outcome reaches the pull through `lastSendStatus`.
- **Public base.** The link always has an absolute base: the request (REST) or `KEEVO_PUBLIC_BASE_URL` (poller); otherwise `PUBLIC_BASE_URL_MISSING`.
- **Validation.** Tightened email check, cross-field 422, `entityId` = `invoiceId` in sync, exactly one strategy per channel (checked at startup).

**Files (main):**
- Migration: `db/modules/invoicing/V18__invoice_send_channel.sql` (columns, `invoice_send_requests`, CHECK `NOT VALID` + `VALIDATE`).
- Domain: `SendChannel`, `SendStatus`, `InvoiceSendRequest`, `InvoiceDelivery`.
- Ports and adapters: `InvoiceSendRequestRepository` / `JdbcInvoiceSendRequestRepository`, `InvoiceRepository.markSendStatus`.
- Services: `InvoiceSendOutbox`, `InvoiceRecipients`, `InvoiceSendMessages`, the channel strategies plus `InvoiceDeliveryOutcomes`.
- Use case: `SendInvoiceUseCase`.
- Scheduling and adapters: `adapter/in/scheduling/InvoiceSendOutboxJob`, `InvoiceSyncHandler`, `InvoiceController`, `SendInvoiceRequestDto`, `SendResultsDto`, `InvoiceResponseDto`, `PublicBaseUrl`.
- Shared: `DeliveryResult.uncertain`, `TenantMessaging.sendEmail(schema, Supplier)`, failover senders (UNKNOWN reported), `ErrorCode` / `ErrorMessages` / `GlobalExceptionHandler` (10 codes), `InvoiceSentEvent`, `AuditEventListener`, `AuditLabels`.
- Contracts: `invoice-sync-payloads.json` (`sendOperations` incl. `channels`), `export-datasets.json`.
- Docs: `docs/ENV_REFERENCE.md` (`KEEVO_PUBLIC_BASE_URL` required).

**Verification (serialized wrapper, clean build, one package at a time, after the review fixes):**

| Package | Tests | Failures | Fixes along the way |
|---|---|---|---|
| invoicing | 432 | 0 | 2 test fixes, then the classes were re-run green |
| messaging | 368 | 0 | |
| sync | 675 | 0 | |
| architecture | 131 | 0 | no new endpoint, `docs/entitlements/endpoints.md` unchanged |
| shared | 1 320 | 0 | the new job added to `SchedulerLockCoverageTest` (`lockAtLeastFor` 30 s), re-run green |

The export contract is unchanged by the fixes; it was regenerated in the first build.

**Residual risks:**
- Merge order: 20-14 (V16), then 20-13 (V17), then this V18 (`outOfOrder=false`; enforced by the coordinator).
- `KEEVO_PUBLIC_BASE_URL` must be set in production, otherwise every queued sending fails.
- The worker pool is per instance and in memory: tasks lost on restart are recovered by the poller (at most 30 s late); a crash mid-send leaves a SENDING row that ends UNKNOWN after 10 min.
- SMS of 2 segments (billed 2 by some providers, counted 1).
- Email without « Reste à payer » (the PDF shows it).
- The online rate limit (30 per hour) also throttles the outbox, which retries later.

**Routed (out of scope):**
- RN screens → `m20-8-envoi-sms-email`.
- Bref acceptance journey with SMS sending → 20-12.
- Flutter test client audit labels → coordinator (field `channel` « Canal d'envoi », values `WHATSAPP` « WhatsApp », `SMS` « SMS », `EMAIL` « E-mail », code key `channel`).

**Verification (orchestrator):** full suite package by package on the rebased branch (clean build): 6 596 tests, 0 failures; Flutter test client: channel audit labels (2.0.29+101), export contract + audit tests green. Live on keevo_dev with no SMS/e-mail provider configured and WhatsApp noop (nothing can leave): `FAC-2026-000010` issued; SMS → 503 « L'envoi par SMS n'est pas disponible pour le moment », e-mail → 503, EMAIL with a phone → 422 « Le numéro de téléphone ne sert pas à l'envoi par e-mail », WhatsApp (noop) → 503 as before 20-8; invoice unchanged (sendCount 0, no send status).

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `26ab9513`, refonte/modulaire / develop).

**Ce qui est fait côté API :**
- Une facture émise ou payée, ou un devis en attente, s'envoie au client par WhatsApp, SMS ou e-mail, un ou plusieurs canaux à la fois (comme `sendChannels` de Bref).
- Le message reprend le texte de Bref avec le lien public ; l'e-mail joint le PDF.
- SMS et e-mails sont décomptés du quota seulement s'ils sont bien partis.
- Hors ligne, l'envoi est mis en file : le serveur l'envoie dès la synchro, une seule fois, et réessaie automatiquement jusqu'à 5 fois en cas de panne du fournisseur.
- Le résultat revient dans la facture (pull).

**À utiliser :**

1. **Envoi en ligne** — `POST /api/v1/invoices/{id}/send`.
   - Corps : `{"channel":"SMS"}` (ou `"EMAIL"`, `"WHATSAPP"`, vide = WhatsApp), ou `{"channels":["sms","email"]}`, plus les champs facultatifs `"phone"` (WhatsApp / SMS) et `"email"` (e-mail). Sans `phone` / `email`, ceux du client de la facture.
   - Avec `channel` ou sans corps : réponse 200 = la facture, ou l'erreur du canal (tableau ci-dessous).
   - Avec `channels` : réponse 200 `{"invoice":{…},"results":[{"channel":"SMS","channelLabel":"SMS","status":"SENT","statusLabel":"Envoyé","errorCode":null,"error":null},{"channel":"EMAIL","channelLabel":"E-mail","status":"FAILED","statusLabel":"Échec de l'envoi","errorCode":"INVOICE_EMAIL_REQUIRED","error":"Indiquez l'adresse e-mail du client"}]}`. Chaque canal est traité séparément : un échec n'arrête pas les autres.
   - La facture porte `sendCount`, `lastSentAt`, `lastSentTo` (masqué : « +237 6•• ••• 111 » ou « c***@ndolo.cm »), `lastSentChannel` / `lastSentChannelLabel`, et pour un envoi hors ligne `lastSendStatus` / `lastSendStatusLabel` / `lastSendStatusChannel` / `lastSendErrorCode` / `lastSendError`.

2. **Erreurs** (champ `error` = message français à afficher tel quel) :

| HTTP | Code | Message | Côté app |
|---|---|---|---|
| 422 | `INVOICE_SMS_PHONE_REQUIRED` | « Indiquez le numéro de téléphone du client pour l'envoi par SMS » | saisie du numéro, renvoyer avec `phone` |
| 422 | `INVOICE_SMS_PHONE_INVALID` | « Le numéro de téléphone du client n'est pas valide pour l'envoi par SMS » | idem |
| 422 | `INVOICE_EMAIL_REQUIRED` | « Indiquez l'adresse e-mail du client » | saisie de l'adresse, renvoyer avec `email` |
| 422 | `INVOICE_EMAIL_INVALID` | « L'adresse e-mail du client n'est pas valide » | idem |
| 422 | `VALIDATION_ERROR` | « Canal d'envoi inconnu : choisissez WhatsApp, SMS ou e-mail » / « Choisissez au moins un canal d'envoi » / « L'adresse e-mail ne sert qu'à l'envoi par e-mail » / « Le numéro de téléphone ne sert pas à l'envoi par e-mail » / « Le numéro de téléphone est trop long » / « L'adresse e-mail est trop longue » | corriger la saisie |
| 503 | `SMS_UNAVAILABLE` / `EMAIL_UNAVAILABLE` | « L'envoi par SMS (par e-mail) n'est pas disponible pour le moment » | proposer un autre canal |
| 503 | `PUBLIC_BASE_URL_MISSING` | « Adresse publique de l'application non configurée » | signaler au support |
| 502 | `SMS_DELIVERY_FAILED` / `EMAIL_DELIVERY_FAILED` | « Le SMS (L'e-mail) n'a pas pu être envoyé. Réessayez dans quelques instants. » | bouton « Réessayer » ; rien n'est décompté |
| 409 | `INVOICE_SEND_UNCERTAIN` | « Envoi incertain : vérifiez auprès du client avant de renvoyer » | **ne pas réessayer automatiquement** ; afficher le message, laisser l'utilisateur décider |
| 403 | `QUOTA_EXCEEDED` | « Quota de votre offre atteint » | lien « Mon offre » ; `details` (19.8) |
| 429 | `RATE_LIMIT_EXCEEDED` | « Trop de requêtes, veuillez patienter » | 30 envois par heure et par compte |
| 409 | `INVOICE_INVALID_TRANSITION` | message de 20.7 | brouillon, annulée, devis converti ou annulé |

   WhatsApp garde ses codes : `INVOICE_PHONE_REQUIRED` / `INVOICE_PHONE_INVALID` (422), `WHATSAPP_UNAVAILABLE` (503), `WHATSAPP_DELIVERY_FAILED` (502).

3. **Hors ligne** — opération `SEND_INVOICE`.
   - `entityId` = id de la facture (obligatoire et égal à `invoiceId`) ; payload `{"invoiceId":"…","channel":"SMS"}` ou `{"invoiceId":"…","channels":["sms","email"]}`, `phone` / `email` facultatifs. Exemples dans `keevo/contracts/invoice-sync-payloads.json` (`sendOperations`).
   - À mettre en file après `ISSUE_INVOICE` (ou la création du devis), avec un `operationId` stable.
   - **`APPLIED` veut dire « mis en file », pas « envoyé ».** Le serveur envoie juste après la synchro, ou au plus tard dans les 30 s.
   - Repoussée avec le même `operationId` → `DUPLICATE`, rien n'est renvoyé.
   - Refus immédiat (`REJECTED`, rien en file) si le destinataire manque ou est invalide, si le document n'est pas envoyable, ou si le canal est inconnu.
   - **Résultat** : au pull suivant de `invoices`, la facture porte `lastSendStatus` : `PENDING` « En attente d'envoi », `SENT` « Envoyé » (avec `sendCount`, `lastSentAt`, `lastSentChannel` à jour), `FAILED` « Échec de l'envoi » (avec `lastSendError`, par exemple « Quota de votre offre atteint » après 5 essais ratés ou un refus), `UNKNOWN` « Envoi incertain » (`lastSendError` « Envoi incertain : vérifiez auprès du client avant de renvoyer » ; jamais renvoyé automatiquement).

4. **Historique** — « Facture envoyée » / « Devis envoyé » avec « Canal d'envoi » (WhatsApp / SMS / E-mail) et « Envoyée au » (masqué).

**À implémenter côté mobile :**
1. Bouton « Envoyer » sur la facture et le devis, avec le choix WhatsApp / SMS / E-mail (cases multiples, comme Bref). Numéro et adresse préremplis depuis le client, modifiables.
2. En ligne : `POST …/send` (avec `channels` si plusieurs) et affichage du résultat de chaque canal. Hors ligne : `SEND_INVOICE` en file avec un `operationId` stable.
3. Afficher l'état d'envoi à partir de `lastSendStatusLabel` / `lastSentChannelLabel` / `lastSentAt` : « En attente d'envoi », « Envoyée par SMS le … », « Échec de l'envoi : {lastSendError} » (bouton « Renvoyer »), « Envoi incertain : vérifiez auprès du client avant de renvoyer » (pas de renvoi automatique).
4. Aucune donnée technique à l'écran (ni identifiant, ni code brut : utiliser les libellés).

**Critères d'acceptation mobile :**
- Given une facture émise et un client avec téléphone, when l'utilisateur choisit « SMS » puis « Envoyer », then le client reçoit « Facture FAC-… - {boutique} … Votre facture : {lien} », la facture affiche « Envoyée par SMS » et le quota SMS diminue d'un.
- Given un devis en attente, when l'utilisateur coche SMS et E-mail, then chaque canal affiche son résultat (envoyé, ou le message d'erreur du canal).
- Given l'app hors ligne, when l'utilisateur envoie par SMS puis se reconnecte, then la facture passe « En attente d'envoi » puis « Envoyée par SMS », un seul SMS part même si la synchro est rejouée.
- Given un fournisseur qui ne répond pas, when l'envoi revient « Envoi incertain », then l'app ne renvoie pas d'elle-même et affiche le message.
- Mise en place (fournisseurs noop en recette, aucun vrai envoi) : `curl -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"channels":["sms","email"]}' $API/api/v1/invoices/$ID/send`.

**Clé de suivi mobile :** `m20-8-envoi-sms-email`.
