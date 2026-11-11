---
title: '24.6 — Bref import: annex data (WhatsApp Business, AI conversations, notifications), client-portal index, module provisioning and audit'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: 'f5e864a8a9fea4540a96d5a5c5402d0c0eae7b42'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-24-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-5-import-monetisation.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** After 24.2–24.5 an imported company has its identity, catalogue, documents and plan, but nothing of what lived around them in Bref: the owner's WhatsApp Business account (WABA), its message templates, its customer campaigns and their recipients, the WhatsApp inbox, the AI assistant conversations and the in-app notification inbox. Imported invoices are also missing from the client portal (`public.invoice_phone_index`: the 21.7 back-fill runs only once — item routed by the 21.7 review), the modules' tables are not provisioned after the plan change and the company's audit has no trace of the import.

**Approach:** Three new pipeline steps of the `migration` module in the slots the epic reserved: **120 annexes** (own read-only section source — restored Bref database and JSON export — loaded and validated in the `check` hook, pure planner, writes through new import ports owned by `whatsappcloud`, `assistant` and `messaging`, one tenant transaction with the records), **130 portal index** (`InvoicePhoneIndex.indexAll` for every imported invoice, every run, idempotent) and **140 provisioning + audit** (`ModuleProvisioner.provisionActiveModules`, then one `TENANT_IMPORTED {source: bref, counts}` audit entry per company).

## Boundaries & Constraints

**Always:**
- Bref ids kept (template, campaign, chat message, AI conversation, notification = the Bref row id); every Bref row read is traced in `migration_records` (the WABA → `whatsapp_accounts` with target = tenant id; a campaign recipient → `whatsapp_campaign_recipients` with target = the Bref `whatsapp_message` id). Idempotent: a re-run writes nothing, a partial re-run only the missing rows.
- The WABA token (clear text in Bref) is encrypted by the `whatsappcloud` module with the Keevo key (`SecretCipher`, context = the Keevo tenant id) before it is stored; it never appears in logs, notes, checksums or exceptions.
- Bref user references go through `ImportRun.accountId` (24.7): created account, linked account, else not imported (owner's account for authorship fields).
- Validation before any write: a connected WABA while the server has no encryption key blocks the owner (`ANNEX_INVALID`, nothing written).
- Tables of paid modules created whatever the plan (`ModuleTables.ensure`, 24.4 D9) before the tenant transaction.
- Set-based: one statement per target table + one records batch; constant source queries per owner; tables an older Bref schema lacks read as empty.
- French notes without values, no personal data in logs (ids and counts), additive API only.

**Never:**
- No message, push, e-mail or Meta call: imported campaigns never resume sending (no `whatsapp_campaign_jobs` row), notifications are inserted without push, the WABA is not re-subscribed (Meta webhooks are repointed by the runbook, 24.13).
- No import of platform-level Bref data: platform campaigns (`campaign`) and their `whatsapp_message` rows, platform-number chat (`ownerId` NULL), push tokens, web-push subscriptions (the new app registers its own).
- No change to api-bref, to the issue path of invoices, to the 21.7 back-fill or to the module activation rules (24.2 ProfileStep owns them).
- Reconciliation (24.8), cutover command (24.9), public links (24.10).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| WABA connected | `whatsapp_account` connected, numeric ids, token | `public.whatsapp_accounts` row (token decrypts with the tenant id), system template `bref_facture` PENDING | — |
| WABA disconnected / unreadable | status `disconnected`; or non-numeric ids / empty token | not imported, note | — |
| Number held | `phone_number_id` already connected to another live company | not imported, note (owner reconnects) | — |
| No key | connected WABA, `keevo.secrets.encryption-key` empty | nothing written | case `ANNEX_INVALID` |
| Templates | APPROVED / DRAFT / REJECTED rows; a name not `^[a-z0-9_]+$`; owner `bref_facture`/`fr` | imported with status, examples, Meta id; invalid → note; reserved → note | — |
| Campaigns | `sent`, `draft`, `scheduled`, `sending` | SENT, DRAFT, DRAFT (date cleared, note), SENT (note); shops → imported stores, template by name+language, variables, purchase criteria, counters | — |
| Recipients | owner campaign messages `sent`/`delivered`/`read`/`failed`/other; same phone twice | SENT/SENT/SENT/FAILED/UNCERTAIN; duplicate kept once (note) | — |
| Chat | in/out messages, one without Meta id, bad phone | `whatsapp_messages` (synthetic id `bref:<id>`), conversations rebuilt (last message, unread, last inbound); bad phone → note | — |
| AI | conversations of owner and members; one of an invited member; > 200 for a user | imported under the Keevo account; invited → note; 200 most recent kept (note) | — |
| Notifications | read and unread rows of owner and members | inbox rows, `read_at` = Bref `updatedAt` when read, Bref dates and type, no push | — |
| Portal | imported invoices pending/paid/cancelled with phone; quote; deleted; no phone | one index row per invoice with a valid phone; others none | — |
| Finish | any completed run | active modules provisioned; one `TENANT_IMPORTED` audit row per company | — |
| Re-run | same owner | nothing written, `alreadyImported` counts the records | — |
| Export | export of the same owner | same section and checksums as the database | — |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/migration/application/ImportStep.java` / `ImportRun.java` / `BrefOwnerImportService.java` -- pipeline contract (`check` hook, `attach`, `count`, `note`, `accountId`); nothing changes there.
- `.../migration/application/step/MonetisationStep.java` -- golden example (section source per kind, check → attach → run, records in the same transaction).
- `.../migration/application/step/DocumentsStep.java` `ownerAccount(run)` -- owner's Keevo account; `BrefDocumentPlanner.phoneKey` + issuedAt rule (`validatedAt ?: createdAt`, non-quote) -- portal entries.
- `.../migration/application/ModuleTables.java` -- `ensure(schema, "whatsapp-cloud" | "ai-assistant" | "messaging")`, outside transactions.
- `.../migration/adapter/out/source/{JdbcBrefMonetisationSource, JsonExportBrefMonetisationSource, BrefRows}.java` -- source pattern (read-only transaction `JdbcBrefSource.readOnly`, `jsonRow`, `expectedChecksum`, `sha256`).
- `.../migration/domain/model/{ImportNote, BlockedCase, MigrationRecord, Canonical}.java` -- append the 24.6 kinds and table names.
- `.../migration/package-info.java` -- add `whatsappcloud :: api`, `assistant :: api`, `messaging :: api`.
- `.../shared/infrastructure/security/crypto/SecretCipher.java` -- `isAvailable()`, `encrypt(clear, context)`; context = tenant id (`WhatsAppAccountService.connect:130`).
- `.../whatsappcloud/account/adapter/out/persistence/JdbcWhatsAppAccountRepository.java` -- columns of `public.whatsapp_accounts` (V68: PK tenant, unique `phone_number_id`, ids `^[0-9]{1,64}$`).
- `db/modules/whatsapp-cloud/V1..V3` -- `whatsapp_messages` (unique `wa_message_id`, phone `^[0-9]{6,20}$`), `whatsapp_conversations`, `whatsapp_templates` (unique name+language, CHECKs), `whatsapp_campaigns` (status, criteria CHECK), `whatsapp_campaign_recipients` (PK campaign+phone).
- `.../whatsappcloud/campaign/adapter/out/persistence/JdbcCampaignRepository.json` -- variables JSON `[{source, value}]`; `template/application/WhatsAppTemplateService.invoiceTemplateApproved` -- a PENDING `bref_facture` row is refreshed from Meta at first use; `template/domain/model/SystemTemplates`.
- `db/modules/ai-assistant/V1` -- `ai_conversations` (user, title ≤ 120, message_count 0..10000, JSON arrays); `assistant/conversation/application/AiConversationService` -- 200 per user.
- `db/modules/messaging/V2__notifications.sql` -- `notifications` (type ≤ 64, title ≤ 150, body ≤ 1000, `data` jsonb, `read_at`); `messaging/notification/adapter/out/persistence/impl/JdbcNotificationInboxAdapter` -- insert shape.
- `.../shared/capability/portal/InvoicePhoneIndex.java` `indexAll(schema, entries)` -- idempotent upsert, invalid phones skipped.
- `.../shared/module/ModuleProvisioner.java` `provisionActiveModules(schema)` -- no notification, per-module failures logged.
- `.../shared/application/port/AuditPort.java` `record(AuditEntry)` (tenant context, caller's transaction); `shared/infrastructure/export/AuditLabels.java` + `keevo/contracts/export-datasets.json` -- French action label.
- Bref (`api-bref` develop 6faa8b4, journal up to date 11/11/2026): `whatsapp/entities/*`, `customer-campaign/*` (statuses, `targetingCriteria`, `whatsapp` JSON), `ai/…/ai-conversation.entity.ts`, `notification/domain/notification.ts`. Dump of 03/11: `whatsapp_account`, `whatsapp_chat_message`, `customer_campaign` empty; indexes on notification (`userId`), campaign (`ownerId`), chat (`ownerId`…), message (`customerCampaignId`); none on `ai_conversation.userId`.

## Tasks & Acceptance

**Execution:**
- `whatsappcloud/api/{package-info, LegacyWhatsAppImportPort}.java` + `whatsappcloud/legacyimport/JdbcLegacyWhatsAppImport.java` -- account (encrypt, held number → `HELD`, insert on conflict nothing), system template, templates, campaigns, recipients, messages + conversations rebuilt for the touched phones (one `INSERT … SELECT … ON CONFLICT DO UPDATE`).
- `assistant/api/{package-info, AiConversationImportPort}.java` + adapter -- insert `ai_conversations` (unnest / batch, on conflict nothing).
- `messaging/api/{package-info, NotificationImportPort}.java` + adapter -- insert `notifications` without push.
- `migration/domain/model/BrefAnnexes.java` -- section records (token redacted in `toString`, excluded from canonical), canonical JSON (sorted keys) for JSON columns.
- `migration/domain/port/out/BrefAnnexesSourcePort.java` + `adapter/out/source/{JdbcBrefAnnexesSource, JsonExportBrefAnnexesSource, BrefAnnexRows}.java`.
- `migration/domain/service/BrefAnnexPlanner.java` -- pure mapping + notes + validation (every matrix row).
- `migration/application/step/{AnnexesStep (120), PortalIndexStep (130), FinalizeStep (140)}.java`.
- `ImportNote`, `BlockedCase` (`ANNEX_INVALID`), `MigrationRecord` (24.6 tables), `migration/package-info.java`, `ImportStep` Javadoc order list.
- `AuditLabels` + `export-datasets.json` -- `TENANT_IMPORTED` « Entreprise importée depuis Bref Point », `counts` hidden from the audit detail.
- Tests -- `BrefAnnexPlannerTest`, `JsonExportBrefAnnexesSourceTest`, `BrefAnnexesImportFullContextTest` (synthetic Bref DB from the versioned fixture `migration/bref-source-annexes-schema.sql` + `migration/bref-annexes-fixture.sql`; both sources, re-run, token, portal, audit, provisioning, held number), existing 24.2–24.5 count assertions updated if needed.
- `keevo/contracts/bref-export.json` (sections + `keevoMapping24_6`), `docs/entitlements/endpoints.md` (regenerated, no new endpoint).

**Acceptance Criteria:**
- Given an owner with a connected Bref WABA, when imported, then `GET /api/v1/whatsapp/account` of the company would report the number connected, the stored token decrypts with the tenant id to the Bref token, and the inbox lists its conversations with the Bref last message and unread count.
- Given imported invoices with client phones, when the import completes, then `public.invoice_phone_index` holds one row per issued/paid/cancelled imported invoice with a valid phone, and a re-run changes no row.
- Given a completed import, then the company's `audit_log` has exactly one `TENANT_IMPORTED` row (metadata `source = bref`, `counts`), even after a re-run, and the `ai_conversations` table exists.
- Given the export of the same owner, then it yields the same annex section as the database (same checksums).

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 45 findings — high 0, medium 7, low 35, false 3, maybe-false 0 (edge 11, blind 12, verification 8, design 8, intent 6; duplicates keep their own rows and share their group's route)
- findings:
  - **Edge-case hunter**
    - `[medium]` `[patch]` Blank Bref template category or status → `Set.of(...).contains(null)` NPE: the whole step fails. Fixed: null checks (`category == null || …`, `status == null → PENDING`); unit-tested (`unreadableValues`).
    - `[medium]` `[patch]` Template (name, language) or chat Meta id already in Keevo under another id: skipped by the untargeted `ON CONFLICT DO NOTHING` yet recorded against a missing target. Fixed: `importTemplates` / `importMessages` return `Written(inserted, present)` (`WITH ins AS (INSERT … RETURNING id)` + the given ids already there); only present rows are recorded, refused ones noted (`WHATSAPP_TEMPLATE_CONFLICT`, `CHAT_MESSAGE_DUPLICATE`) and retried by a re-run.
    - `[low]` `[patch]` Campaign `template_id` pointing to a template refused by that conflict. Fixed with the previous row: the link is kept only when the template is present (written now or recorded by an earlier run).
    - `[low]` `[patch]` `importAccount` race (number connected between the check and the insert) answered ALREADY_PRESENT. Fixed: on 0 rows, re-read the company's row → ALREADY_PRESENT or NUMBER_HELD.
    - `[medium]` `[patch]` `AuditPort.record` is `REQUIRES_NEW` (audit-on-attempt): a failure after it commits left the entry without its record → a second `TENANT_IMPORTED` on re-run. Fixed: `audit.count(action, Tenant, tenantId)` before writing; unit-tested (`FinalizeStepTest.auditAlreadyThere`).
    - `[low]` `[patch]` Browser endpoint already registered: silently not imported. Fixed: note `WEB_PUSH_ENDPOINT_HELD`, counted nowhere, never recorded; tested end to end.
    - `[low]` `[patch]` Non-object notification metadata dropped silently. Fixed: row note `NOTIFICATION_DATA_DROPPED`; unit-tested.
    - `[low]` `[reject]` Duplicate ids inside an export → the port refuses, the batch fails — a malformed export fails loudly (Bref rows have primary keys); not reachable from Bref data.
    - `[low]` `[reject]` Two endpoints equal after trimming — Bref's endpoint is unique as stored; whitespace-only variants of a push URL do not occur.
    - `[false]` `[reject]` 200-conversation cap broken across runs / by a linked account — `ai_conversations` is per tenant schema (a linked account's other company is another table) and the source is frozen at the cutover (ADR-10): the same 200 are chosen on every run.
    - `[medium]` `[patch]` (duplicate claim of the audit row above, kind=claim) — same fix.
  - **Blind hunter**
    - `[medium]` `[patch]` Template name/language conflict recorded as imported — same root cause and fix as the edge-case row.
    - `[medium]` `[patch]` Chat Meta id conflict recorded — same root cause and fix.
    - `[low]` `[patch]` `importAccount` race — same fix as above.
    - `[low]` `[patch]` Browser endpoint held silently — same fix as above.
    - `[false]` `[reject]` AI cap with a linked account's existing conversations — per-schema table (see above).
    - `[false]` `[reject]` Push-only Bref notifications should not reach the inbox — every Bref notification row is an inbox item; `channel` only decides whether an OS push is also sent (api-bref `notification.ts`).
    - `[low]` `[reject]` Bref types copied raw — decision D8 (the RN app is Bref's and knows its types); metadata part fixed above.
    - `[low]` `[reject]` Recipient whose client was not imported keeps a dangling `client_id` — no FK and no reader resolves it (the recipient row carries its name); Bref customers are imported unless invalid, which blocks the owner (CLIENT_INVALID). Unlikely, guard not worth it.
    - `[low]` `[patch]` Campaign losing its template link without a note. Fixed: `CAMPAIGN_FIELD_DROPPED` when the template name has no imported template; unit-tested.
    - `[low]` `[reject]` Audit counts of the writing run only, `counts` not shown, entity « Compte » — decision D12; a map is technical (hidden by `AuditChangesFormatter`), « Compte » is Keevo's label of every Tenant entry.
    - `[low]` `[reject]` Portal index re-adds an invoice deleted in Keevo after the import — re-runs happen before the go on a suspended company (no edits); after the go the cutover is not re-run.
    - `[low]` `[patch]` Missing tests — added: no-key block through the step (`AnnexesStepTest`), conflicts and held endpoint, deleted-company number, finalize step.
  - **Verification gap**
    - `[low]` `[patch]` Provisioning call not proven by the full-context test. Added `FinalizeStepTest` (called every run, counted, audit once).
    - `[low]` `[patch]` `ANNEX_INVALID` wiring not tested through the step. Added `AnnexesStepTest.noKeyBlocks` (BLOCKED case, no unit of work touched).
    - `[low]` `[patch]` Held browser endpoint untested. Added to `numberHeldElsewhere` (note, count 0, no record).
    - `[low]` `[patch]` Deleted company's number not exercised on the import path. Added (first company DELETED → third owner imports the number).
    - `[low]` `[patch]` System template absent in the held case not asserted. Added (`whatsapp_templates` empty).
    - `[low]` `[patch]` Portal `issued_at` = `validatedAt` rule unexercised. Fixture invoice validated later; asserted.
    - `[low]` `[reject]` Upper-case `userId` keys never read in tests — Bref writes `req.user.id` (lower-case UUID text); the extra key is a harmless superset as in 24.5.
    - `[medium]` `[patch]` (other finding) records for refused rows — same fix as the conflict row.
  - **Design layer (FUS-NFR11)**
    - `[low]` `[defer→routed 24-9]` `ai_conversation.userId` has no Bref index: one scan per owner; joins the existing 24.9 item (one pass / temporary indexes on the restored copy).
    - `[low]` `[patch]` Regex compiled per phone. Fixed: static `NON_DIGIT` pattern.
    - `[low]` `[patch]` `Set.of` built per outgoing message. Fixed: static `MESSAGE_STATUSES`.
    - `[low]` `[reject]` String allocation in the id tie-break — micro; determinism with PostgreSQL's uuid order is the point.
    - `[low]` `[patch]` `List.contains` for campaign shops. Fixed: `LinkedHashSet`.
    - `[low]` `[reject]` One statement per table for a large inbox — same pattern as 24.3–24.5; « écritures par blocs pour un gros owner » is already routed to 24.9 (24.4 review).
    - `[low]` `[reject]` Export rows filtered after materialisation — one owner's file, bounded.
    - `[low]` `[reject]` `indexAll` batch of single-row upserts + tenant lookup per 1000 — existing 21.7 code, ⌈n/1000⌉ lookups.
  - **Intent alignment** (descriptive, logged as rejects)
    - `[low]` `[reject]` Module activation passive (profile 24.2 + plan 24.5), tested as « not owner-disabled » — decision D11, the only reading consistent with « n'ajoute que ton étape ».
    - `[low]` `[reject]` Web Push subscriptions imported beyond the literal list — decision (19.9b keeps Bref's VAPID keys « so imported subscriptions stay valid »).
    - `[low]` `[reject]` Live run on the real dump not visible in the diff — done (Auto Run Result).
    - `[low]` `[reject]` « No message » asserted by construction only — the live log of import 1 + re-run shows no send (noop, FCM off); the ports have no publisher.
    - `[low]` `[reject]` Audit author = super-admin, source SYSTEM — D12 (the person who ran the import).
    - `[low]` `[reject]` Live `WhatsAppAccountService` not exercised on the imported row — same table, same cipher and context (`tenantId.toString()`), decrypt asserted.

## Design Notes

**Patterns.** Pipeline steps with the `check` hook (validation before any write stays the orchestrator's contract); Strategy for the section source (database / export, shared row mapping so they cannot drift); pure Domain Service (`BrefAnnexPlanner`); import ports owned by the writing modules (`whatsappcloud :: api`, `assistant :: api`, `messaging :: api`), encryption inside the module that owns the secret. Three steps instead of one so a failure names its stage (« données annexes », « index du portail client », « modules et journal »).

**Decisions (default, conservative, 11/11/2026):**
- **D1** Bref stores the WABA token in clear (api-bref has no cipher): the import encrypts it with `SecretCipher` (AES-256-GCM, context = tenant id) in the `whatsappcloud` adapter. No key → `ANNEX_INVALID` (fix the server, re-run). The export file carries the clear token as Bref holds it: the operator protects it (contract note).
- **D2** Only a `connected` account is imported. A number already connected to another live Keevo company is not moved (note): the owner reconnects. Webhook repointing = runbook 24.13 (already routed).
- **D3** The system template `bref_facture` (created on every Bref WABA by Bref) is inserted `PENDING`, `system = true`: the 21.5 rule refreshes it from Meta at the first invoice sending. An owner template named `bref_facture`/`fr` is not imported (note).
- **D4** Nothing resumes sending: `scheduled` → DRAFT with the date cleared (note « à reprogrammer »), `sending` → SENT (note); no job row. Template header media URL of Bref templates is not ported (21.5 SSRF rule); a campaign's `headerMediaUrl` is kept as the campaign's `header_media_link` only when it is an `https` link.
- **D5** Recipients = Bref `whatsapp_message` rows of the owner's campaigns (`customerCampaignId`); `sent|delivered|read` → SENT, `failed` → FAILED, anything else → UNCERTAIN; one per phone (latest kept). Delivered/read stay in the campaign counters.
- **D6** Chat messages: owner rows only; a message without Meta id gets `bref:<id>` (never matched by a webhook); the conversation row is rebuilt from `whatsapp_messages` of the touched phones (name = latest Bref contact name).
- **D7** AI conversations keep Bref's JSON as is (the RN app writes Bref's shapes, 21.4); a user keeps his 200 most recent (Keevo cap) — the rest is noted, not imported.
- **D8** Notifications: Bref type kept (lower-case Bref codes, the RN app knows them), metadata as `data`, `read_at` = `updatedAt` when read; Keevo's 90-day retention of read rows applies afterwards.
- **D9** Platform-level Bref data is not owner data (platform campaigns and their messages, platform-number chat) and the app's Expo push tokens are re-registered by the new app (analysis §9.6): not imported. Browser (Web Push) subscriptions of the owner and members ARE imported (`public.web_push_subscriptions`, same id): Keevo signs with Bref's VAPID keys (19.9b) « so imported subscriptions stay valid »; an endpoint already registered in Keevo is kept as it is (note).
- **D10** Portal index: computed every run from the snapshot for every invoice recorded as imported (non-quote, not deleted, phone present; `issued_at` = `validatedAt ?: createdAt`, 24.4 rule); `indexAll` upserts only what differs (re-run writes nothing).
- **D11** « Modules activés selon le profil »: 24.2's ProfileStep already keeps the Bref functions (`whatsapp-cloud`, `ai-assistant`, `client-portal`…) and the plan comes from 24.5; 24.6 calls `provisionActiveModules` at the end of every run (tables of the modules the plan now grants).
- **D12** `TENANT_IMPORTED` once per company (record owner → `tenant_imported`), actor = the super-admin, source SYSTEM, entity `Tenant`, metadata `{source: bref, counts}` = the counters of the run that wrote it; `counts` hidden from the audit detail (technical keys).

**Review decisions (11/11/2026):**
- **D13** A Bref row refused by another unique key than its id (a template of the same name and language, a message of the same Meta id, a browser endpoint already registered) is not in Keevo: it is noted, never recorded (a re-run retries it), and a campaign keeps its template link only when that template is in Keevo.
- **D14** `TENANT_IMPORTED` is guarded by its record AND by an audit count (the audit writes in its own transaction): never twice.
- **D15** A blank Bref category / status / metadata never fails the run: the row is left out or the value dropped, with a note.
- **D16** The 24.8 reconciliation counts none of the 24.6 tables: the annex counts are reported by the batch counters; the live run stays RECONCILED.

**Cost per owner:** source: 1 table-existence query + 8 queries (indexed except `ai_conversation.userId` and `owner_whatsapp_template.ownerId` unique constraint → index; AI scan routed to 24.9 with the other unindexed reads) or one streamed pass of the export; planner O(n log n); writes: ≤ 1 account insert, 1 statement per tenant table (templates, campaigns, recipients, messages, conversations rebuild, AI, notifications), 1 records batch; portal: ⌈n/1000⌉ `indexAll` batches; finish: provisioning (history reads when up to date), ≤ 1 audit insert + 1 record.

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t246 migration whatsappcloud assistant messaging architecture reporting/export shared` -- expected: all green.

**Manual checks:**
- Live: `bref_src_t246` (dump of 03/11 + versioned synthetic annex fixture, read-only role) → backend on 4646 with `keevo_dev_t246` (WhatsApp noop, FCM off): import, re-run; aggregates only; drop both databases.

## Auto Run Result

Status: done.

**Summary.** Three new steps of the Bref import pipeline (module `migration`), in the slots the epic reserved:
- **120 « WhatsApp, assistant et notifications »** — own read-only section (`BrefAnnexesSourcePort`: restored database, one read-only transaction, tables an older schema lacks read as empty; JSON export, same mapping and checksums, JSON columns in canonical form), loaded and validated in the `check` hook, planned by the pure `BrefAnnexPlanner`, written in ONE tenant transaction with the records through three new import ports: `whatsappcloud :: api` (`LegacyWhatsAppImportPort`: WABA with its clear Bref token encrypted by `SecretCipher` under the company id, number held elsewhere not moved, deleted company's number freed, system template `bref_facture` PENDING, templates, campaigns never resumed, recipients, inbox messages + conversations rebuilt in one statement), `assistant :: api` (AI conversations, 200 most recent per account) and `messaging :: api` (inbox notifications, never pushed; Web Push subscriptions). Blocking case `ANNEX_INVALID` when a connected WABA meets a server without encryption key.
- **130 « index du portail client »** — `InvoicePhoneIndex.indexAll` for every imported invoice (not a quote, not deleted, with a phone; `issued_at` = `validatedAt ?: createdAt`), every run, idempotent (item 21.7 done).
- **140 « modules et journal »** — `ModuleProvisioner.provisionActiveModules`, then one `TENANT_IMPORTED {source: bref, counts}` audit entry per company (label « Entreprise importée depuis Bref Point »).

**Files:** new — `migration/domain/model/BrefAnnexes`, `domain/port/out/BrefAnnexesSourcePort`, `adapter/out/source/{JdbcBrefAnnexesSource, JsonExportBrefAnnexesSource, BrefAnnexRows}`, `domain/service/BrefAnnexPlanner`, `application/step/{AnnexesStep, PortalIndexStep, FinalizeStep}`, `whatsappcloud/api/{package-info, LegacyWhatsAppImportPort}` + `whatsappcloud/legacyimport/…/JdbcLegacyWhatsAppImport`, `assistant/api/{package-info, AiConversationImportPort}` + `JdbcAiConversationImport`, `messaging/api/{package-info, NotificationImportPort}` + `JdbcNotificationImport`; test resources `migration/bref-source-annexes-schema.sql` (dump DDL) and `migration/bref-annexes-fixture.sql` (versioned synthetic owner covering the tables empty in the dump); tests `BrefAnnexPlannerTest`, `JsonExportBrefAnnexesSourceTest`, `AnnexesStepTest`, `FinalizeStepTest`, `BrefAnnexesImportFullContextTest`. Changed — `ImportNote` (24.6 notes), `BlockedCase` (`ANNEX_INVALID`), `MigrationRecord` (24.6 targets), `ImportStep` (Javadoc), `migration/package-info` (3 allowed dependencies), `AuditLabels` + `keevo/contracts/export-datasets.json` (`TENANT_IMPORTED`, value `bref`), `keevo/contracts/bref-export.json` (8 sections, mapping, example), `BrefOwnerImportFullContextTest` (+1 record: the audit). No Flyway migration (V87 unused), no new endpoint.

**Review.** 45 findings (5 layers): 7 medium + 17 low patched (refused rows never recorded nor linked, audit never doubled, account race, blank values, missing notes, tests), 2 routed to 24.9, the rest rejected with reasons (Review Triage Log).

**Verification (all green after the rebase on `origin/refonte/modulaire` 00d43c7c — 24-8, 24-10, 20-2):** migration 147, architecture 173 (1 skipped, pre-existing), whatsappcloud 65, assistant 55, messaging 404, reporting/export 128, shared 1 398.

**Live check** (port 4646, `keevo_dev_t246`, source `bref_src_t246` = 03/11 dump + `bref-annexes-fixture.sql`, SELECT-only role; WhatsApp noop, FCM off; aggregates only), run before and after the rebase:
- Import 1: both owners COMPLETED (≈ 4 s each) and RECONCILED (24.8). Synthetic owner: WABA 1 (token stored encrypted, clear token absent from the database), templates APPROVED / DRAFT / REJECTED + system PENDING, campaigns SENT + DRAFT (scheduled), 0 campaign job, recipients SENT + FAILED, 4 messages, 2 conversations (unread 1 / 0), 2 AI conversations, 3 notifications (1 read), 2 Web Push subscriptions, 3 portal index rows, 1 `TENANT_IMPORTED`. Dump owner: 69 notifications, 2 AI conversations, 1 template, 9 portal index rows, 1 audit entry.
- Import 2 (re-run): both COMPLETED in ≈ 0.3 s, every creation 0, `alreadyImported` = records (37, 179), RECONCILED.
- Log: no token, no fixture phone, no send (WhatsApp / push / module messages). Databases and role dropped afterwards.

**Follow-up review recommended:** `true` — seven medium entries were patched after the review; the new `WITH ins AS (INSERT …) … UNION ALL` « present » statements of `JdbcLegacyWhatsAppImport` (templates, messages) and the audit guard are covered by one end-to-end test and unit tests; a second pass on the `AnnexesStep.run` counting is worth it.

**Residual risks:**
- `ai_conversation.userId` has no Bref index: one scan per owner (routed to 24.9).
- The WABA works only once Meta's webhook and app subscription point to Keevo (runbook 24.13, already routed); the system template's real status is read from Meta at the first invoice sending.
- A Web Push subscription imported for an account linked to an existing Keevo user survives a rollback until 24.9 deletes recorded targets (routed).

## Mobile (React Native — dépôt AI/bref)

> Conventions : spec-16-11 ; protocole de synchronisation : spec-16-10 (non répétés ici).

### Statut

API livrée (commits 622a43a0 et 34a39dc6, branche de l'epic 24). **Aucun nouvel endpoint ni champ** : l'import remplit des tables existantes (WhatsApp Business 21.5/21.6, assistant 21.4, notifications 19.9/19.9b, portail 21.7).

### Ce qui est fait côté API

- Un compte Bref qui avait son numéro WhatsApp Business le retrouve connecté (même numéro, mêmes modèles, campagnes passées, conversations et messages).
- Les campagnes programmées dans Bref arrivent **en brouillon** (à reprogrammer) ; rien n'est envoyé par la migration.
- Les conversations de l'assistant (Bref Point) et la boîte de notifications de chaque membre sont reprises (lues / non lues comme dans Bref).
- Les factures importées sont visibles dans le portail client (connexion par code SMS) dès l'activation.
- Le journal de l'entreprise montre une entrée « Entreprise importée depuis Bref Point ».

### À utiliser

Rien de nouveau : `GET /api/v1/whatsapp/account`, `GET /api/v1/whatsapp/conversations`, `GET /api/v1/whatsapp/my-templates`, campagnes 21.6, conversations de l'assistant 21.4, notifications 19.9, journal d'audit (action `TENANT_IMPORTED`).

### À implémenter côté mobile

1. Journal : libellé de l'action `TENANT_IMPORTED` = « Entreprise importée depuis Bref Point » (valeur de `source` : `bref` = « Bref Point ») ; aucun compteur technique affiché.
2. Notifications : les types Bref (`invoice`, `payment`, `reminder`, `alert`, `system`, `campaign`) arrivent tels quels dans la boîte ; garder les icônes Bref.
3. Vérifier au premier lancement d'un compte importé : messagerie WhatsApp, modèles (le modèle de facture n'est pas listé), campagnes en brouillon, conversations de l'assistant.

### Critères d'acceptation mobile / scénarios de test

- **Given** un owner Bref avec son numéro WhatsApp Business, importé puis activé, **When** il ouvre la messagerie, **Then** il voit ses conversations Bref avec le nombre de messages non lus.
- **Given** une campagne programmée dans Bref, **When** il ouvre les campagnes, **Then** elle est en brouillon, à reprogrammer, et n'est jamais partie seule.
- **Given** le journal de l'entreprise, **Then** la première entrée lit « Entreprise importée depuis Bref Point ».

### Clé de suivi mobile

`m24-6-import-annexes-index-portail`

## Dashboard super-admin (autre dépôt)

> Conventions admin : section « Dashboard super-admin » de `spec-16-4-console-super-admin-modules.md`.

### Statut

API livrée (commits 622a43a0 et 34a39dc6). Routes inchangées (`POST /api/v1/admin/migration/bref/owners/{ownerId}/imports`, `GET /api/v1/admin/migration/batches/{batchId}`) ; ajouts additifs au lot.

### À utiliser

- Lot (`counts`, nouvelles clés) : `whatsappAccountsImported`, `whatsappTemplatesImported`, `whatsappCampaignsImported`, `whatsappRecipientsImported`, `whatsappMessagesImported`, `aiConversationsImported`, `notificationsImported`, `webPushSubscriptionsImported`, `portalInvoicesIndexed`, `modulesProvisioned`.
- Lot (`notes`, afficher `label (count)`) : `WHATSAPP_ACCOUNT_DISCONNECTED`, `WHATSAPP_ACCOUNT_UNREADABLE`, `WHATSAPP_NUMBER_HELD`, `WHATSAPP_FIELD_SHORTENED`, `WHATSAPP_TEMPLATE_UNREADABLE`, `WHATSAPP_TEMPLATE_RESERVED`, `WHATSAPP_TEMPLATE_MEDIA_DROPPED`, `WHATSAPP_TEMPLATE_CONFLICT`, `CAMPAIGN_SCHEDULE_DROPPED`, `CAMPAIGN_SENDING_CLOSED`, `CAMPAIGN_STATUS_UNKNOWN`, `CAMPAIGN_FIELD_DROPPED`, `CAMPAIGN_RECIPIENT_UNREADABLE`, `CAMPAIGN_RECIPIENT_DUPLICATE`, `CHAT_MESSAGE_UNREADABLE`, `CHAT_MESSAGE_DUPLICATE`, `AI_CONVERSATION_USER_UNKNOWN`, `AI_CONVERSATION_UNREADABLE`, `AI_CONVERSATION_OVER_LIMIT`, `NOTIFICATION_USER_UNKNOWN`, `NOTIFICATION_UNREADABLE`, `NOTIFICATION_SHORTENED`, `NOTIFICATION_DATA_DROPPED`, `WEB_PUSH_USER_UNKNOWN`, `WEB_PUSH_UNREADABLE`, `WEB_PUSH_ENDPOINT_HELD`.
- Lot (`cases`) : `ANNEX_INVALID` (« Compte WhatsApp Business non repris : la clé de chiffrement des secrets n'est pas configurée sur le serveur ») ; `sourceId` jamais affiché.

### À implémenter côté dashboard

1. Écran « Migration Bref » : libellés des nouveaux compteurs — « Compte WhatsApp Business », « Modèles WhatsApp », « Campagnes », « Destinataires de campagne », « Messages WhatsApp », « Conversations de l'assistant », « Notifications », « Navigateurs abonnés », « Factures au portail client », « Modules préparés ».
2. Journal d'une entreprise : action `TENANT_IMPORTED` libellée « Entreprise importée depuis Bref Point ».

### Critères d'acceptation dashboard

- **Given** un owner Bref avec WhatsApp Business, **When** le super-admin l'importe, **Then** le lot « Terminé » montre « Compte WhatsApp Business : 1 » et les messages repris.
- **Given** un serveur sans clé de chiffrement, **Then** le lot est « Bloqué » avec « Compte WhatsApp Business non repris : … ».

### Clé de suivi dashboard

`d24-6-import-annexes-index-portail`
