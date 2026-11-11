---
title: '21-7 — Module client-portal: end clients find all their invoices by phone (OTP), across merchants'
type: 'feature'
created: '2026-11-10'
status: done
baseline_revision: '517b2c42200d2883a169bf48ef93dfc52dae8ebf'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-5-whatsapp-cloud.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-9-serveur-mcp-externe.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref's « retrouver mes factures » portal (FUS-FR29) has no Keevo equivalent: an end client cannot sign in with his phone and list the invoices every merchant issued to that number, and a merchant cannot turn that exposure off. Without it a migrated Bref client loses the portal.

**Approach:** New free, owner-toggleable module `client-portal` (package `com.keevo.clientportal`, depends on `shared` + `identity :: api`). Global accounts `public.portal_clients` (separate from merchant `users`), opaque portal sessions `public.portal_sessions` (SHA-256 of a 256-bit token, 30 days, revocable), sign-in by the 19.2 OTP machinery under a separate challenge purpose (a portal code never opens a merchant session and vice versa). `public.invoice_phone_index(phone_normalized, tenant_id, invoice_id, issued_at)` is fed in the issue transaction of every invoice (shared port implemented by the module, called by invoicing) and back-filled once for existing invoices. Listing = one keyset query on the index filtered by tenant ACTIVE + portal switch, then every document read in its own tenant schema in one `UNION ALL` statement (shared port implemented by invoicing) returning the minimum fields and a fresh 24 h signed link to the existing public PDF.

## Boundaries & Constraints

**Always:**
- Offer as data: `client-portal` is `free: true`, category `business`, owner-toggleable (« Mes modules » is the merchant's switch, no new endpoint). V70 owner-disables it for every existing tenant (README rule « Modules introduced after the go-live »; existing Keevo clients never agreed to a cross-merchant portal); tenants created afterwards have it on (no activation row = activated). `ModuleIdsAtGoLivePinTest` + `db/modules/README.md` updated. No tenant table (no `db/modules/client-portal/`).
- Visibility of an invoice = indexed phone equals the session's phone AND tenant `ACTIVE` AND module `client-portal` usable for the tenant (owner switch + entitlement through `EntitlementService.isEnabled`) AND the document in its schema is a non-deleted `INVOICE` in `ISSUED | PAID | CANCELLED`. Disabling the module hides the merchant's invoices at the next request; re-enabling shows them again (index kept).
- Portal fields only: `merchantName` (seller snapshot printed on the invoice), `reference` (`FAC-…`, or the Bref reference of an imported invoice), `issueDate`, `dueDate`, `totalTtc` (XAF), `paymentState` + French `paymentStateLabel` (Non payée / Partiellement payée / Payée / En retard / Annulée), `link {url, expiresAt}` (ISSUED / PAID only; null for a cancelled invoice). No UUID, tenant code, client name, lines or payments (the PDF behind the link has the details).
- Tokens: portal session token `kvc_` + 43 base64url chars (32 bytes `SecureRandom`), only its SHA-256 stored (PK lookup), 30-day fixed lifetime, at most 10 live sessions per client (oldest deleted), logout deletes it, nightly purge of expired rows. Links: `InvoiceLinkSigner` HMAC, valid 24 h, bound to the document's current link version (a rotation kills them).
- Rate limits: code request = 19.2 limits (per phone 3 / 10 min, 5 / h; per IP 10 / h; DB daily failure cap) shared with merchant codes (one SMS budget per phone); code check = 19.2 per-IP limit + 5 attempts per code; listing = 60 / min per session and 30 failed authentications / min per IP (429 `PORTAL_TOO_MANY_REQUESTS`, nothing read).
- Phone only (E.164 of an allowed country, 19.2 `OtpIdentifier`); email refused 422. Index phone = `PhoneNumbers.toE164(client_phone, +237)`; an invoice without a valid phone is not indexed.
- Public paths `/api/v1/portal/**` (JWT filter skipped; `@EntitlementExempt`); a merchant JWT is refused there (401 `PORTAL_SESSION_INVALID`), a portal token is refused everywhere else (JWT filter 401). French messages, additive only (V1 2.0.1 untouched).

**Never:** Bref's password register / login for clients (OTP only), Bref's public `GET /v1/invoices/public/count?identifier=` (enumerates who has invoices — not ported), quotes or credit notes in the portal, any write by the client, offline sync, an index fill by the Bref import (24.6 owns it, through the same port), a per-row or per-tenant query loop on the listing path.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Request code | `POST /api/v1/portal/auth/otp/request {phone:"677000111"}` | 200 `{data:{expiresIn:300}}`, same bytes whether invoices exist or not; SMS (+ WhatsApp) sent | other country 422 `PHONE_COUNTRY_NOT_SUPPORTED`; email / garbage 422 `VALIDATION_FAILED`; limits 429 `OTP_TOO_MANY_REQUESTS` |
| Verify code | `POST …/otp/verify {phone, code}` right code | 200 `{data:{accessToken:"kvc_…", expiresAt}}`; `portal_clients` row created once; no merchant account created | wrong 401 `OTP_INVALID` (attempt kept); 5th 429 `OTP_TOO_MANY_ATTEMPTS` |
| Cross purpose | portal code typed on `/auth/otp/verify` or merchant code on portal | 401 `OTP_INVALID` | — |
| List | `GET /api/v1/portal/invoices` Bearer session | `{data:{items:[…], nextCursor}}` newest first, ≤ 20 per page (`limit` 1–50) | bad cursor 400 `VALIDATION_FAILED` |
| Next page | `?cursor=<nextCursor>` | following items, no duplicate / gap (keyset on `(issued_at, tenant_id, invoice_id)`) | — |
| Portal off | owner disables `client-portal` | that merchant's invoices absent | — |
| Tenant suspended / deleted | status ≠ ACTIVE | absent | — |
| Other phone | invoice of `+237690…` | never listed to `+237677…` | — |
| No / bad / expired token, merchant JWT | — | 401 `PORTAL_SESSION_INVALID` | — |
| Logout | `POST …/auth/logout` Bearer | 204, token refused afterwards; idempotent | — |
| Flood | > 60 list calls / min / session | 429 `PORTAL_TOO_MANY_REQUESTS` | — |

</intent-contract>

## Code Map

- Bref (read-only): `api-bref/src/clients/clients.{controller,service}.ts` (OTP + password register/login, `GET /v1/clients/invoices` → `InvoiceService.findByCustomerPhone`), `invoice.repository.ts:263` (`WHERE customerPhone = ?` — no index, every field); app `bref/features/client-auth/api/client-auth.api.ts`, `bref/features/find-invoice/screens/*` (list: reference, date, total, status; detail from cache).
- `identity/auth/application/service/RequestOtpService.java` / `VerifyOtpService.java` -- 19.2 code issue / check; extract the core (challenge key) so a purpose can reuse it; `OtpIdentifier`, `OtpSettings`, `OtpRateLimiter`, `JdbcOtpChallengeRepository` (`identifier` column = challenge key, VARCHAR 254) unchanged.
- `identity/api/` -- new `ClientPortalCodePort` (request / verify a portal code → normalized phone); `identity/package-info.java` exposes `api` already.
- `invoicing/invoice/application/usecase/InvoiceLifecycleUseCase.java:96-119` -- single issue path (manual, POS bridge `InvoiceSaleUseCase:144`, quote conversion `ConvertQuoteUseCase:133`, offline sync `InvoiceSyncHandler:212`): index the client phone in the same transaction.
- `invoicing/invoice/application/service/InvoiceLinkSigner.java` (`sign(schema, id, expiresAt, version)`), `InvoicePublicLinkUseCase.SHARED_PATH`, `adapter/in/rest/InvoiceSharedLinkController` (link opening, unchanged), `adapter/in/rest/PublicBaseUrl.java` -- moved to `shared/infrastructure/web/` (portal and invoicing both build absolute links).
- `invoicing/invoice/domain/model/PaymentState.java` labels; invoice columns `kind, status, deleted_at, number, legacy_reference, seller_legal_name, issue_date, due_date, total_ttc, payment_state, public_link_version, client_phone, issued_at` (module `invoicing` V1–V18).
- `crm/contact/adapter/in/bootstrap/ClientPhoneBackfillJob.java` -- template of the one-shot boot back-fill (ShedLock, `public.data_migrations` marker, keyset pages, Java normalization).
- `shared/capability/notification/TenantWhatsAppBusiness.java` -- template of a shared capability port implemented by a module.
- `mcp/token/domain/model/McpTokenSecrets.java`, `mcp/token/application/service/McpTokenPurgeJob.java`, `mcp/server/McpRateLimiter.java` -- token, purge, limiter templates.
- `shared/infrastructure/security/SecurityConfig.PUBLIC_PATHS` (+ `JwtAuthFilter.shouldNotFilter`), `shared/infrastructure/web/ClientIps`, `ErrorCode` / `ErrorMessages` / `GlobalExceptionHandler.domainCodeToHttpStatus`.
- `shared/module/EntitlementService.isEnabled`, `ModuleProvisioner.ensureProvisioned` (invoicing tables at latest version before the cross-schema read), `KeevoModule`, `EntitlementExempt`; module template `mcp/module/McpAccessModule.java`, `resources/keevo/modules/mcp-access/module.json`, `db/migration/V68__whatsapp_cloud.sql`.
- Tests: `whatsappcloud/WhatsAppCloudFullContextTest.java` (tenant / owner / invoice helpers), `identity/auth/session/OtpSignInFullContextTest.java` (known code by hash overwrite, distinct `X-Forwarded-For`), `architecture/ModuleIdsAtGoLivePinTest.java`, `architecture/EndpointEntitlementCoverageTest.java` (regenerates `docs/entitlements/endpoints.md`).

## Tasks & Acceptance

**Execution:**
- `resources/db/migration/V70__client_portal.sql` -- `portal_clients` (id, phone UNIQUE E.164 check, created_at, last_login_at), `portal_sessions` (token_hash BYTEA(32) PK, client_id FK CASCADE, created_at, expires_at; index (client_id, created_at DESC), index expires_at), `invoice_phone_index` (PK (tenant_id, invoice_id), tenant FK CASCADE, phone_normalized, issued_at; index (phone_normalized, issued_at DESC, tenant_id, invoice_id)); owner-disable for existing tenants.
- `resources/keevo/modules/client-portal/module.json`, `clientportal/package-info.java`, `clientportal/module/ClientPortalModule.java` -- module « Portail client ».
- `shared/capability/portal/` -- `InvoicePhoneIndex` (index one / a batch, called by invoicing) and `PortalDocuments` + `PortalDocument` (read documents of several schemas, implemented by invoicing).
- `identity/api/ClientPortalCodePort.java`, `identity/auth/application/service/ClientPortalCodeService.java`, refactor `RequestOtpService` / `VerifyOtpService` (challenge key; `OtpPurpose`) -- portal code on the 19.2 machinery.
- `invoicing/.../InvoiceLifecycleUseCase.java` -- index the phone after `invoices.issue`; `invoicing/.../adapter/out/portal/JdbcPortalDocuments.java` -- `UNION ALL` reader + 24 h signed links; `invoicing/.../adapter/in/bootstrap/InvoicePhoneIndexBackfillJob.java` -- one-shot back-fill.
- `shared/infrastructure/web/PublicBaseUrl.java` -- moved from invoicing (imports updated).
- `clientportal/index/` (JDBC index adapter), `clientportal/auth/` (secrets, client / session repository, `PortalAuthService`, `PortalSessionPurgeJob`, `PortalRateLimiter`, controller `/api/v1/portal/auth/*`), `clientportal/invoice/` (`PortalInvoiceService`, cursor, controller `/api/v1/portal/invoices`).
- `ErrorCode` / `ErrorMessages` / `GlobalExceptionHandler` / `SecurityConfig` -- `PORTAL_SESSION_INVALID` 401, `PORTAL_TOO_MANY_REQUESTS` 429, public path.
- `keevo/contracts/client-portal.json`, `db/modules/README.md`, `ModuleIdsAtGoLivePinTest`, `docs/entitlements/endpoints.md` (regenerated).
- Tests: `PortalTokenSecretsTest`, `PortalCursorTest`, `ClientPortalFullContextTest` (OTP sign-in + purposes isolated, listing across two merchants with keyset, owner switch, suspended tenant, other phone never visible, link opens the PDF, logout, token / JWT refusals, rate limit, back-fill job), identity unit tests kept green.

**Acceptance Criteria:**
- Given invoices issued by two merchants to `+237677000111` and one to `+237690000222`, when the client of `+237677000111` lists, then he sees exactly his two invoices (each read in its own schema, minimum fields) and never the third, in one index query plus one cross-schema read.
- Given the first merchant disables « Portail client », when the client lists again, then only the second merchant's invoice appears; re-enabled, it is back.
- Given an invoice issued before this release, when the back-fill runs at boot, then it is indexed once (rerun writes nothing).
- Given a portal code, when it is submitted to `/api/v1/auth/otp/verify`, then 401 and no merchant account is created.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 33 findings — high 1, medium 5, low 19, false 8, maybe-false 0 (duplicates across layers share one row)
- findings:
  - `[medium]` `[patch]` (blind 1 / edge 5) the per-IP failure brake ran before the token check: one IP over 30 failures (carrier NAT) blocked valid sessions — malformed tokens still refused without DB; a well-formed token is looked up and a valid session always served; the brake only turns failures into 429 (`PortalAuthService.failed`); test `brakes`.
  - `[low]` `[patch]` (blind 2 / edge 6) logout had no brake — `signOut(authorization, ip)`: braked IP deletes nothing, an unknown token counts as a failure; still 204.
  - `[false]` `[reject]` (blind 3) unknown `payment_state` → 500 — the column has a CHECK on the four `PaymentState` values (invoicing V10); the import writes the same column.
  - `[low]` `[reject]` (blind 4 / edge 2) index write inside the issue transaction can roll back an issue — chosen on purpose (index and invoice commit together, no lost row); one PK upsert on a small table, no lock contention path.
  - `[false]` `[reject]` (blind 4b / edge 3) `INSERT … SELECT` silently writes nothing for an unknown schema — every caller passes the schema of a live tenant (issue path, back-fill reading `public.tenants`).
  - `[low]` `[reject]` (blind 5) reassigned / mistyped phone exposes invoices forever — same behaviour as Bref's portal (no age limit, no removal); product decision, reported for the user (see Auto Run Result).
  - `[low]` `[patch]` (blind 6) contract missing errors — contract + `@Operation` list 422 `PHONE_COUNTRY_NOT_SUPPORTED` / `VALIDATION_FAILED` / `VALIDATION_ERROR` on verify and request.
  - `[medium]` `[patch]` (blind 7) empty page with a next cursor — entitlement filtering moved before the LIMIT (NFR11 F1); contract now says to keep paging while `nextCursor` is not null.
  - `[medium]` `[patch]` (blind 8 / VG 1-3) untested mechanisms — tests added: `wrongCodesAreCounted` (attempts kept, 5th = 429, right code dead), `brakes` (30 made-up tokens from one IP → 429, valid session still 200, 61st call of a session → 429), `sessionCapAndPurge` (10 kept, oldest refused; purge keeps the live one).
  - `[low]` `[patch]` (blind 9) test names over-claim — comment fixed, `activationRows` asserts « on » before the row, `JdbcPortalDocumentsTest` asserts `IllegalArgumentException` from the schema check.
  - `[low]` `[reject]` (blind 10a) invoices shown when the merchant's invoicing module is off — they are issued legal documents whose public link already works without the module (14.2); the portal switch is the merchant's choice.
  - `[low]` `[reject]` (blind 10b / NFR11 F2) a portal read may provision invoicing tables — in-memory no-op once warm, bounded by D merchants of one page, same path as the public link controller.
  - `[low]` `[reject]` (blind 11) slash-stripping duplicated / `tenantId` naming in `InvoiceLifecycleUseCase` — cosmetic, the pre-existing parameter name of the whole class.
  - `[low]` `[reject]` (blind 12 / edge 4) code consumed then session insert fails → 500 — only on a database failure; the contract tells the app to offer a new code.
  - `[false]` `[reject]` (blind 13) portal lets anyone exhaust a merchant's OTP budget — the public merchant `/auth/otp/request` already allows exactly that; the shared budget is the SMS-cost bound.
  - `[low]` `[reject]` (intent D1) existing companies start with the portal off — README rule for new modules (consent of existing clients); reported as a user decision.
  - `[false]` `[reject]` (intent D2) extra hiding conditions (suspended tenant, module row, provisioning failure) — natural consequences of the tenant and module states, not a divergence from « disable ».
  - `[false]` `[reject]` (intent D3) import not wired — 24.6 owns the import and its AC already fills `invoice_phone_index` (through `InvoicePhoneIndex.indexAll`).
  - `[false]` `[reject]` (intent D4) one shared statement instead of per-tenant reads — each document is read from its own schema (schema-qualified), as the AC asks.
  - `[low]` `[reject]` (intent D5) cursor tampering untested — the page query is always bound to the session's phone; a tampered position only moves within the client's own list.
  - `[false]` `[reject]` (intent D6) rate limits only unit-tested — now covered over HTTP (`brakes`).
  - `[low]` `[reject]` (intent D8) invoice without a valid phone silently not indexed — by design (nothing to match); same rule as the WhatsApp channel.
  - `[medium]` `[patch]` (NFR11 F1) per-merchant `isEnabled` = 2-3 queries per merchant per page (request-scoped cache) — replaced by a PK anti-join on `tenant_modules` in the page query (free module: row status / expiry / grace is its whole state); test `moduleRowDisabled`.
  - `[low]` `[patch]` (NFR11 F3) `indexAll` resolved the tenant per row — tenant id read once, batch of `VALUES` upserts.
  - `[low]` `[reject]` (NFR11 F4) unbounded purge DELETE — at most 10 sessions per client, nightly, indexed.
  - `[low]` `[reject]` (NFR11 F5) UNION ALL text varies per merchant mix — one round trip, PK probes; plan reuse not worth a different shape.
  - `[low]` `[patch]` (edge 1) forged cursor with an extreme date → SQL error 500 — decode refuses epoch micros outside 2000–9999 (400); tests unit + `forgedCursor`.
  - `[low]` `[reject]` (edge 7) store-review code opens the portal for the review phone — a dedicated reviewer number configured by the environment, secret code; no merchant invoices to it.
  - `[false]` `[reject]` (edge 8) back-fill marker skips later imports — later invoices go through the issue path or the 24.6 import, which calls the index port itself.
  - `[low]` `[reject]` (edge 9) spec names `ClientPortalCodeService`, delivered as `ClientPortalCodeAdapter` — fix would edit this build's spec.
  - `[low]` `[reject]` (edge 10) spec test file names differ (`ClientPortalUnitTest`) — fix would edit this build's spec.
  - `[high]` `[patch]` (VG 4 / VG other 1) V70 owner-disables client-portal for pre-existing tenants but `ModuleOwnerActivationMigrationPostgresIntegrationTest` still pinned the old rows (would fail; the migration default untested) — `client-portal=false` added to its four assertions (pins the V70 privacy default).
  - `[medium]` `[patch]` (VG other 2) invoices replaced after a POS sale correction (`superseded_at`) were listed — filtered in `JdbcPortalDocuments` and the back-fill; asserted in `switchSuspensionAndCancelled`.

### 2026-11-11 — Review pass (reprise 11/11/2026, alignment on Bref)
- verdicts: 44 findings (rows merged where layers reported the same defect) — high 0, medium 6, low 24, false 9, maybe-false 5
- findings:
  - `[medium]` `[patch]` (NFR11 1 / blind 11 / edge 7) first-page totals moved up to 10 000 ids each way and silently capped `invoiceCount` / `totalTtc` — `PortalIndexReader.merchantsOf` (one `SELECT DISTINCT` row per visible merchant) + `PortalDocuments.summarize(tenantIdBySchema, phone)` joining the phone's index rows to each merchant's invoices by PK in one `UNION ALL` aggregate: exact, no id transferred, memory O(D); `allBySchema` / `SUMMARY_CAP` removed.
  - `[low]` `[reject]` (NFR11 2 / edge 8) provisioning now runs for every merchant of the phone — bounded by the client's merchants, in-memory no-op once warm (`ModuleProvisionerService.ensured`), one call per schema per request (shared `shown` map).
  - `[low]` `[patch]` (NFR11 3 / blind 12) `query()` chose its binds from the SQL text — every `find` template selects the schema column and always binds it; the summary has its own explicit binder.
  - `[medium]` `[patch]` (VG 1 / blind 3) credit notes in `amountRemaining` / legacy payment untested — `detailsAndSummary` inserts a credit note and asserts remaining = total − credit − paid and the legacy payment = total − credit; contract states the header sums printed totals (credit notes per invoice).
  - `[medium]` `[patch]` (VG 2) BCrypt 72-byte guard untested — 40 × « é » → register 422 `VALIDATION_FAILED`, login 401 `INVALID_CREDENTIALS`.
  - `[low]` `[patch]` (VG 3) session cap on the password path untested — `registerThenPasswordSignIn` opens 10 sessions then logs in: 10 kept, oldest refused.
  - `[medium]` `[patch]` (VG 4) summary not checked against a switched-off merchant — `detailsAndSummary` switches merchant B off: summary counts 1, amount of A only.
  - `[low]` `[patch]` (VG 5) line order untested — a second line (position 2, quantity 10.000) asserted after the first, and printed « 10 ».
  - `[maybe-false]` `[reject]` (VG 6) `DuplicateKeyException` race path untested — needs a concurrent registration; catch narrowed to the e-mail index name (any other duplicate rethrown); if wrong only a rare 500 (low).
  - `[medium]` `[patch]` (VG other / blind 8) failed password sign-ins blocked the « mot de passe oublié » path (register shared the per-identifier bucket) — registration counts under its own key (`register:` + phone); test: 11 failed logins → 429, then register with a code → 201.
  - `[low]` `[reject]` (intent 1) Bref app wire shape (paths, `{data}`, `code` vs `otpCode`, no `refreshToken`, paged object) not compatible as is — reading C of the intent is not required ("additive and compatible" = compatible with Keevo's V1 apps); the Mobile section maps every Bref call.
  - `[low]` `[reject]` (intent 2) public count / locked screen not ported — security gap, justified in table row 11.
  - `[low]` `[reject]` (intent 3) gaps kept for non-security reasons (re-register resets password, code sign-in kept, keyset pages, totals without cancelled, quotes absent, owner switch) — each justified in the table and listed under « Décisions » for the user.
  - `[false]` `[reject]` (intent 4) sprint-status not updated — done at finalize (21-7 comment, m21-7 / d21-7 keys).
  - `[false]` `[reject]` (intent 5) `docs/entitlements/endpoints.md` not regenerated — regenerated at verification.
  - `[false]` `[reject]` (intent 6) spec / review write-up not in the diff — the spec is tracked outside `keevo/` and committed at finalize.
  - `[low]` `[patch]` (blind 1) `stripTrailingZeros` could print `1E+1` — `plainQuantity` (scale ≥ 0); unit + full-context assertions.
  - `[low]` `[patch]` (blind 2 / edge 12) a PAID invoice could show a remainder — remaining forced to 0 when the shown state is PAID.
  - `[low]` `[reject]` (blind 4) lines lack a VAT flag / breakdown — the PDF behind the link carries the VAT breakdown; Bref's screen shows `quantity × unitPrice` and `total` only; contract documents `unitPrice` semantics.
  - `[low]` `[patch]` (blind 5) e-mail uniqueness relied on application lower-casing — V84 adds `CHECK (email IS NULL OR email = lower(email))`.
  - `[low]` `[patch]` (blind 6) any duplicate turned into « e-mail taken » — catch limited to `uq_portal_clients_email`.
  - `[false]` `[reject]` (blind 7) two phone normalizations in register — the e-mail check now uses the verified phone, after the code.
  - `[low]` `[reject]` (blind 8b) no delete-account / change-e-mail endpoint — Bref has none either; out of the alignment scope.
  - `[false]` `[reject]` (blind 9) security logic lacks tests — decoy path, multi-byte password, brakes now covered (`registerThenPasswordSignIn`, `loginBrake`, unit `loginBrake`); remaining race path see VG 6.
  - `[false]` `[reject]` (blind 10) integration tests share one per-IP login bucket — `call()` rotates `X-Forwarded-For` over 250 addresses (`ClientIps` honours it, as the 21.7 brake tests rely on).
  - `[maybe-false]` `[reject]` (blind 13 / edge 9) V84 silently re-enables nothing without a V70 history row — every environment (prod baseline 0, dev, tests) runs V70 through Flyway's default table; the migration test asserts the re-enable.
  - `[low]` `[reject]` (blind 14) new configuration keys only in `@Value` defaults — same convention as the 21.7 portal keys; defaults are the product values.
  - `[low]` `[reject]` (blind 14b) `Credentials.phone` unused — cheap, kept for a future « signed in as » need; harmless.
  - `[maybe-false]` `[reject]` (blind 13b) no audit row / merchant notice for the re-enable — the user's decision is to behave like Bref (no switch at all there); documented in README and spec.
  - `[medium]` `[patch]` (edge 1) 409 before the code let anyone probe registered e-mails — the e-mail check runs after a valid code; wrong code → 401 whatever the e-mail (test `probe`).
  - `[low]` `[reject]` (edge 2) code burned on a 409 / failed transaction — consequence of edge 1's fix (security first); contract tells the app to ask a new code.
  - `[low]` `[patch]` (edge 3) lower-casing could push an e-mail past 254 → 500 — length checked after lower-casing (422).
  - `[low]` `[reject]` (edge 4) phone and e-mail of one account have separate 10-attempt buckets — Bref behaves the same; BCrypt cost 12 keeps 20 attempts / 15 min harmless.
  - `[low]` `[reject]` (edge 5) limits configured to 0 refuse every login — operator misconfiguration only, defaults are Bref's.
  - `[low]` `[reject]` (edge 6) a failing summary fails the page — only on a database error, which fails the page anyway.
  - `[maybe-false]` `[reject]` (edge 10) a tenant created while V70 runs loses its rule row — migrations run at boot before the API serves; impossible in practice.
  - `[low]` `[patch]` (edge 11) fully credited PAID invoice showed a 0 FCFA payment — legacy payment only when the amount due is positive.
  - `[false]` `[reject]` (edge 12b) PAID with only unpaid instalments shows remaining — patched with blind 2 (PAID → remaining 0).
  - `[low]` `[reject]` (edge 13) Intent says 30-day sessions — superseded by the « Reprise 11/11/2026 » section (user decision); fix would edit this build's spec intent.
  - `[low]` `[reject]` (edge 14) AC1 « minimum fields / one cross-schema read » no longer holds — superseded by the reprise (Bref's screens); still no identifier, client data or note.
  - `[low]` `[reject]` (edge 15) existing merchants exposed without opt-in — the user's explicit decision (behave like Bref); V84 keeps an owner's own switch-off.
  - `[maybe-false]` `[reject]` (blind 13c / edge 9b) timestamp vs timestamptz comparison of `installed_on` — same JVM/session time zone for Flyway runs; the migration test passes on it.
  - `[false]` `[reject]` (blind 2b) `PaymentStateCalculator` called the single source while sums are re-done — the adapter applies its exact rules (due, paid rows, legacy row); javadoc says so.
  - `[false]` `[reject]` (edge 12c) summary ignores credit notes « inconsistently » — documented choice (printed totals); per-invoice remaining carries the credit.

## Design Notes

- **Patterns.** Ports & adapters across Modulith boundaries: `shared.capability.portal.InvoicePhoneIndex` (Observer-free direct port, called in the issue transaction so index and invoice commit together — no event to lose) implemented by `clientportal`; `PortalDocuments` (Repository/Facade over another module's tables) implemented by invoicing, so the portal never touches invoice tables and invoicing never depends on the portal. OTP: Template Method — one core (`issue` / `check` by challenge key) shared by the merchant and portal purposes (`OtpPurpose` value object builds the key `portal:` + phone; merchant key unchanged = no migration, no behaviour change). Sessions: opaque bearer token as 21.9 (hash lookup, revocation by delete, no cache). Keyset pagination with an opaque base64url cursor.
- **Costs.** Issue: +1 `INSERT … SELECT … ON CONFLICT` (tenant id by unique `schema_name`). Sign-in: 19.2 cost + 1 upsert client + 1 insert session + 1 bounded delete (≤ 10 kept). Listing (page P ≤ 50): 1 PK session read; 1 index range scan `(phone, issued_at DESC, …) LIMIT P+1` joined to `tenants` (PK) and anti-joined to `tenant_module_activation` and `tenant_modules` (PK each: the whole effective state of a free module that requires only core — no per-merchant entitlement lookup, the page is filtered before its LIMIT); per distinct merchant on the page D ≤ P: warm `ensureProvisioned` (in-memory once warm); 1 `UNION ALL` statement over D schemas (`id = ANY(?)` on each invoices PK); P HMACs. Memory O(P). Back-fill: tenants with an invoices table in one catalog join, per tenant keyset pages of 500, tenant id resolved once, one batch of plain upserts per page (unchanged rows not rewritten) (one-shot, boot, ShedLock). Purge: one DELETE on the `expires_at` index.
- **Decisions.** Default off for existing tenants, on for new ones (README rule; ex-Bref tenants are created by the import and so have it on, like Bref). Link validity 24 h (the portal re-signs on every listing; a forwarded link dies quickly). Cancelled invoices are listed (legal document, « Annulée ») without a link. Sessions are fixed 30 days (Bref: 1-day access + refresh; one token is simpler, revocable, re-sign-in costs one SMS a month).

## Décisions (prises par défaut, à confirmer par l'utilisateur)

- **Portail coupé pour les entreprises existantes** (V70, règle « Modules introduced after the go-live ») : leurs clients n'ont jamais accepté un portail inter-commerçants ; l'owner l'active dans « Mes modules ». Les nouvelles entreprises (dont celles importées de Bref) l'ont activé. Lecture inverse possible (portail actif par défaut, le commerçant le coupe) : une ligne de migration à retirer.
- **Connexion par code seulement** : le couple inscription / mot de passe des clients Bref n'est pas porté (une session de 30 jours, un SMS par mois au plus).
- **`GET /v1/invoices/public/count` non porté** : il révélait à n'importe qui si un numéro a des factures.
- **Aucune limite d'ancienneté ni retrait d'une facture mal attribuée** (numéro réattribué ou mal saisi) : comportement de Bref conservé ; à trancher si l'utilisateur veut une limite (ex. 24 mois) ou une action « ne plus montrer au portail » côté commerçant.
- **Liens PDF de 24 h** re-signés à chaque liste ; factures annulées listées sans lien ; factures remplacées après correction de vente exclues ; devis et avoirs absents.

> **Remplacées le 11/11/2026** par la section « Reprise 11/11/2026 » ci-dessous (décision explicite de l'utilisateur : le portail se comporte comme prévu par Bref). Les trois premières décisions par défaut ci-dessus ne s'appliquent plus.

## Reprise 11/11/2026 — alignement sur Bref

**Trigger.** User decision (11/11/2026): « le portail client doit se comporter comme prévu par Bref ». The 21.7 defaults (portal off for existing companies, code-only sign-in, 30-day session, minimum fields) are revisited point by point against Bref `develop` (api-bref `src/clients/clients.{controller,service}.ts`, `src/invoice/invoice-public.controller.ts`, `invoice.repository.ts:263 findByCustomerPhone`, `docs/clients.md`, `docs/otp.md`; app bref `features/client-auth/**`, `features/find-invoice/screens/{my-phone,locked,client-register,client-login,my-invoices,find-invoice-detail}-screen.tsx`, `docs/client-auth.md`, `docs/find-invoice.md`), read-only (`git show develop:<path>`). Baseline `717aacdc`. Public migration **V84**. Additive only: no field removed or renamed, the 21.7 endpoints keep their bodies.

### Bref ↔ Keevo, point by point

| # | Point | Bref (develop) | Keevo after this reprise | Kept gap (why) |
|---|---|---|---|---|
| 1 | Activation | No merchant switch: every shop's invoices are visible to whoever owns the phone. | **On for every company.** V84 deletes V70's own rows (owner-disabled, `updated_by IS NULL`, tenant created before V70 ran); new companies already had it on. | The owner can still switch it off in « Mes modules » (Keevo extra, required by FUS-FR29's AC « un commerçant qui désactive le portail »); an owner who switched it off himself keeps his choice. |
| 2 | Account | `POST /v1/clients/otp/request` (1 SMS) → `POST /v1/clients/register {name, phone, email?, password ≥ 6, otpCode}` → tokens; `client` table (phone unique, email unique nullable, name, bcrypt 12). | `POST /api/v1/portal/auth/register {name, phone, code, password, email?}` → 201 session; `portal_clients` + `name`, `email` (unique, lower-cased), `password_hash` (BCrypt 12, the platform encoder). | Password **8–72** (Keevo's merchant rule, stronger than Bref's 6; > 72 bytes refused instead of silently truncated). A phone that already has an account is **not refused** (Bref: 422 `phoneAlreadyExists`): the code proves the phone, so the password is replaced and the other sessions end — Bref had no way back for a client who forgot his password. An e-mail of another client → 409 `EMAIL_ALREADY_EXISTS`, answered only after a valid code (no e-mail probing; the code is then used). Registration has its own brake bucket, so failed password sign-ins never block the way back. |
| 3 | Sign-in | `POST /v1/clients/login {identifier: phone or e-mail, password}` — 0 SMS; 401 `invalidCredentials`. | `POST /api/v1/portal/auth/login {identifier, password}` — phone normalized like the invoices (`+237` for 9 digits) or e-mail lower-cased; one 401 `INVALID_CREDENTIALS` « Numéro, e-mail ou mot de passe incorrect. ». | An unknown identifier costs the same BCrypt check as a wrong password (no account enumeration by timing; Bref answers faster for an unknown client). The 21.7 code sign-in (`/otp/verify`) stays: FUS-FR29 says « par OTP », and it is the path of a client without password. |
| 4 | Brakes | `otp-send` 10/IP, 5/identifier per hour; `login` (login + register) 30/IP, 10/identifier per 15 min, every request counted; in-memory per instance. | Code request = 19.2 limits (stricter: 3/10 min, 5/h per phone, 10/h per IP, daily failure cap); register + login = Bref's `login` limit (30/IP, 10/identifier, 15 min, every attempt), checked before any hash → 429 `PORTAL_TOO_MANY_REQUESTS` « Trop de tentatives de connexion : réessayez dans quelques minutes. ». | In-memory per instance, like Bref. |
| 5 | Session | JWT `role: client`, access **1 day**; a refresh token is returned but no client refresh route exists → 1 day in practice. | Opaque `kvc_` token, **24 h** (`keevo.portal.session-ttl`, default `PT24H`); V84 cuts live sessions to 24 h after their creation. | Opaque, revocable token (logout, password change) instead of a JWT; no useless refresh token returned. 10 live sessions per client. |
| 6 | Visible invoices | `WHERE customerPhone = :phone` (exact string), every type (sales and quotes `DV-`), every status, no tenant filter, `ORDER BY date DESC`, no limit. | Phone normalized E.164 index; issued / paid / cancelled invoices, newest first; tenant ACTIVE; merchant switch. | Normalization finds « 677… » and « +237677… » alike (Bref misses one of them). **Quotes not listed**: a Keevo quote is never issued, numbered nor sent to the client (20.3: it becomes an invoice, which is listed) — Bref quotes are handed out with a link. Drafts do not exist in Bref (every Bref document is final). Credit notes have no Bref equivalent (Bref cancels; a cancelled invoice is listed « Annulée »). A suspended / deleted company is hidden. No age limit and no per-invoice removal: same as Bref. |
| 7 | Statuses | `pending / partial / paid / overdue / cancelled` + badge. | `UNPAID / PARTIAL / PAID / OVERDUE / CANCELLED` + French label — 1:1. | — |
| 8 | PDF / link | None in the client portal (in-app detail only; public link `/invoices/public/:token` shared by the merchant, 90 days). | In-app detail data now in each item (row 10) **and** the 24 h signed link to the PDF (kept, additive). | Short link lifetime (a forwarded link dies quickly); cancelled invoice: no link. |
| 9 | Pagination | None: the whole list at once (unbounded). | Keyset pages (`limit` 1–50, `nextCursor`), plus the totals Bref's header shows on the first page (`summary {invoiceCount, totalTtc}`). | Bounded responses (a phone typed on every sale of a shop would otherwise return thousands of rows). The app pages with `nextCursor` (infinite scroll); the header reads `summary` instead of summing the list. `totalTtc` leaves out cancelled invoices (Bref summed them too — a cancelled invoice is not owed). Totals exact (aggregated in SQL per merchant, no cap). |
| 10 | What the client sees | List: short id, date, total, status badge; header: count + total; detail (from the list cache): shop name + phone (only on the enriched path — not on `/clients/invoices`), payments (paid, remaining, % settled, method, schedule `amount / dueDate / isPaid / paidDate`), items (`reference, name, quantity × unitPrice, total`), notes. The whole invoice entity is returned (ids, user id, shop id, public token, customer data…). | Each item adds `merchantPhone`, `amountPaid`, `amountRemaining`, `lines [{reference, description, quantity, unitPrice, totalTtc}]`, `payments [{amount, dueDate, paid, paidDate, method}]` to the 21.7 fields (`merchantName`, `reference` = invoice number, dates, `totalTtc`, state + label, `link`). | Never an identifier, the client's own data, an internal note nor the seller's user (Bref leaked them). Keevo invoices have no free « notes » field: the printed footer is on the PDF. A payment's internal note is not shown. |
| 11 | Public count | `GET /v1/invoices/public/count?identifier=` (locked screen with the number of invoices before sign-in). | **Not ported.** | It tells anyone whether — and how much — a phone has invoices (enumeration). The app goes straight from the phone screen to sign-in / register. |

### Décisions (prises par défaut, à valider par l'utilisateur)

- **Le commerçant garde son interrupteur** (Bref n'en a pas) : exigé par l'AC de FUS-FR29 ; un owner qui l'avait coupé lui-même le garde coupé.
- **Réinscription = nouveau mot de passe** (Bref : refus 422) : le code SMS prouve le numéro ; c'est le chemin « mot de passe oublié » qui manquait à Bref. Les autres appareils sont déconnectés.
- **Mot de passe 8–72 caractères** (Bref : 6 minimum) : règle des comptes commerçants Keevo.
- **Connexion par code seule conservée** (`/otp/verify`) à côté du mot de passe.
- **Totaux sans les factures annulées** ; **devis non listés** (jamais remis au client dans Keevo).
- **Session 24 h** comme Bref (au lieu de 30 jours) : la reconnexion par mot de passe ne coûte aucun SMS.

### Tasks (reprise)

- `resources/db/migration/V84__client_portal_bref_alignment.sql` -- `portal_clients` + `name`, `email`, `password_hash` (+ partial unique index on `email`); delete V70's own `client-portal` owner-disabled rows; cut live sessions to 24 h.
- `clientportal/auth/domain/port/out/PortalSessionRepository.java`, `adapter/out/persistence/JdbcPortalSessionRepository.java` -- `Credentials`, `findByPhone` / `findByEmail` (unique indexes), `emailTakenByOther`, `register` (one upsert), `touchLogin`, `deleteSessionsOf`.
- `clientportal/auth/application/PortalRateLimiter.java` -- Bref's `login` window (30/IP, 10/identifier, 15 min), `tryLogin`.
- `clientportal/auth/application/PortalAuthService.java` -- `register`, `login`, `identifierKey`, configurable TTL (24 h), decoy BCrypt for unknown identifiers.
- `clientportal/auth/adapter/in/rest/PortalAuthController.java` -- `POST /api/v1/portal/auth/register` (201), `POST /api/v1/portal/auth/login`.
- `shared/capability/portal/PortalDocuments.java` -- `PortalDocument` + `merchantPhone`, `amountPaid`, `amountRemaining`, `lines`, `payments`; `Line`, `Payment`, `Summary`; `summarize`.
- `invoicing/invoice/adapter/out/portal/JdbcPortalDocuments.java` -- seller phone + credited total in the documents statement; lines and payments in one `UNION ALL` each; collection figures by `PaymentStateCalculator` rules (legacy paid invoice = one payment); `summarize` (one aggregate statement).
- `clientportal/invoice/domain/port/out/PortalIndexReader.java`, `adapter/out/persistence/JdbcInvoicePhoneIndex.java` -- `merchantsOf(phone)` (same filter, one row per visible merchant).
- `clientportal/invoice/application/PortalInvoiceService.java`, `adapter/in/rest/PortalInvoiceController.java` -- `summary` on the first page; new item fields.
- `shared/infrastructure/web/ErrorMessages.java` -- `PORTAL_SESSION_INVALID` « Votre session a expiré : reconnectez-vous. » (no longer code-only).
- `keevo/contracts/client-portal.json`, `db/modules/README.md` (exception documented), `ModuleIdsAtGoLivePinTest`, `ModuleOwnerActivationMigrationPostgresIntegrationTest` (client-portal no longer off after V84), `docs/entitlements/endpoints.md` (regenerated).
- Tests: `ClientPortalFullContextTest` (`registerThenPasswordSignIn`, `loginBrake`, `detailsAndSummary`), `ClientPortalBrefAlignmentMigrationPostgresIntegrationTest` (V84), `ClientPortalUnitTest` (`loginBrake`, `identifierKeys`), `JdbcPortalDocumentsTest` (`summarize` empty).

### Acceptance (reprise)

- Given a company that existed before V70 with the V70 row, when V84 runs, then the portal is on for it; given an owner who switched it off himself, then it stays off; a re-run changes nothing.
- Given a client who registers with the code, a name, a password and an e-mail, when he later signs in with his phone (typed locally) or his e-mail and the password, then he gets a 24-hour session and no code is sent; a wrong password, an unknown identifier and a code-only account all answer the same 401.
- Given an e-mail already used by another client, when a registration sends it with a wrong code, then 401 (nothing revealed); with a valid code, then 409.
- Given a phone that already has an account, when it registers again with a new code, then the new password works, the old one does not, and its previous sessions are refused.
- Given 10 sign-in attempts on one identifier within 15 minutes, when an 11th arrives, then 429 before any password check.
- Given an invoice with a payment received and an instalment, when the client lists, then the item shows its lines, both payments, the amount paid and remaining, and the merchant phone; the first page carries `summary` (count of listed invoices, amount without the cancelled ones), the next pages do not.

### Design Notes (reprise)

- **Patterns.** Façade (`PortalAuthService`) over the code port, the repository and the limiter — the two new sign-ins reuse the same session opening as the code sign-in. Repository (`PortalSessionRepository`) with one statement per method. Template / single source for collection figures: the adapter applies `PaymentStateCalculator.amountDue` and the « paid = rows with a payment date, legacy PAID = one payment » rule, so the portal never disagrees with the merchant's screens. Fixed-window limiter (Caffeine, Clock ticker) like 21.7.
- **Costs.** Register: 1 limiter check, the 19.2 check, 1 `EXISTS` on the e-mail index (after the code: no e-mail probing), 1 BCrypt (cost 12), 1 upsert + 1 delete (client's sessions, ≤ 10) + 1 insert. Login: 1 limiter check, 1 lookup on a unique index, 1 BCrypt (also on a miss), 1 update + 1 insert + 1 bounded delete. Listing page P over D merchants: 21.7 cost + the documents statement now reads one credited sum per row (`idx_credit_notes_invoice`), + 1 `UNION ALL` for the lines (`(invoice_id, position)` key) + 1 for the payments (`idx_invoice_payments_invoice`): 3 round trips, memory O(P + lines + payments). First page only: + 1 `SELECT DISTINCT` of the phone's visible merchants (range of the phone index, D rows) + 1 aggregate `UNION ALL` joining, per merchant, the phone's index rows to its invoices by PK — no id transferred, memory O(D), exact totals; DB work O(N) index rows for a client with N invoices. No per-row or per-merchant query.
- **Migration.** V84 is set-based (one `DELETE … USING tenants` with one scalar sub-query on `flyway_schema_history`, one `UPDATE`), idempotent; no tenant DDL.

### Mobile / dashboard (reprise)

The Mobile section below is rewritten for Bref's flow (register / login / code sign-in, new item fields, summary). Dashboard: no super-admin API change; the module's default changes (on for every company) — the `d21-7` key is updated. Flutter `keevo/app`: nothing (test client, no portal screen).

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- expected: BUILD SUCCESS
- `wt-pkgs.sh keevo/backend fix217 clientportal identity invoicing architecture admin shared subscription` -- expected: all green
- Bad cursor / limit answer 400 `VALIDATION_ERROR` (`InvalidRequestParameterException`, the cursor convention of the API) — the matrix row « bad cursor 400 » is met with that domain code.
- `-Dkeevo.entitlements.regenerate=true` on `EndpointEntitlementCoverageTest` -- expected: `docs/entitlements/endpoints.md` lists the portal endpoints as exempt

- Reprise: `wt-pkgs.sh keevo/backend t217r clientportal invoicing identity architecture shared admin` -- expected: all green (incl. `ClientPortalBrefAlignmentMigrationPostgresIntegrationTest`, `ModuleOwnerActivationMigrationPostgresIntegrationTest`).

**Manual checks:**
- Live on `keevo_dev_217`, port 4517 (WhatsApp noop, FCM off): two fresh test companies, invoices issued to one phone, portal sign-in (known code by hash), listing, owner switch, link opens the PDF, logout.
- Reprise: live on `keevo_dev_t217r`, port 4627 (WhatsApp noop, FCM off): V84 applied, register (known code by hash) → login by phone and by e-mail → listing with lines / payments / summary → PDF link → wrong password 401 → logout; database dropped afterwards.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (21.7 : commit `8f575524` ; reprise du 11/11/2026, alignement sur Bref : commit `cc8b8530`, clé `m21-7-client-portal` dans `sprint-status.yaml`). Contrat : `keevo/contracts/client-portal.json`. Conventions d'erreur et d'affichage : spec-16-11 ; protocole de synchro : spec-16-10 (rien à synchroniser : portail en ligne uniquement).

**Ce qui est fait côté API :** module gratuit `client-portal` « Portail client », **qui se comporte comme le portail de Bref** (« Je suis client » / « retrouver mes factures »). Un client final (jamais un compte Keevo) crée son compte une fois : code reçu par SMS (et WhatsApp si configuré) + nom + mot de passe + e-mail facultatif ; ensuite il se connecte par numéro ou e-mail + mot de passe, sans SMS. La connexion par code seul reste possible (client sans mot de passe, ou mot de passe oublié : il refait l'inscription avec un nouveau code, ce qui remplace le mot de passe). Une session dure 24 h (comme le jeton client de Bref). Il voit toutes les factures que les commerçants ont émises à ce numéro, du plus récent au plus ancien, avec tout ce que l'écran de détail de Bref affiche (lignes, paiements et échéances, payé / reste à payer, téléphone du commerçant) et un lien vers le PDF valable 24 h ; la première page porte les totaux de l'en-tête (nombre de factures, montant). Comme Bref, le portail est actif pour toutes les entreprises ; un commerçant peut encore le couper dans « Mes modules » (module « Portail client ») : ses factures disparaissent aussitôt.

**À utiliser** (code : `keevo/backend/src/main/java/com/keevo/clientportal/**/adapter/in/rest/*Controller.java`) — correspondance Bref → Keevo :
- `POST /v1/clients/otp/request {phone}` → `POST /api/v1/portal/auth/otp/request {phone}` → `{data:{expiresIn:300}}`. Erreurs : 422 `PHONE_COUNTRY_NOT_SUPPORTED` (message à afficher), 422 `VALIDATION_FAILED` (pas un numéro de téléphone), 429 `OTP_TOO_MANY_REQUESTS` « Trop de demandes de code. Réessayez plus tard » → bloquer « Renvoyer le code » quelques minutes.
- `POST /v1/clients/register {name, phone, email?, password, otpCode}` → `POST /api/v1/portal/auth/register {name, phone, code, password, email?}` → **201** `{data:{accessToken:"kvc_…", expiresAt}}` (pas de `refreshToken` : Bref n'avait pas de route de renouvellement pour les clients). Mot de passe **8 à 72 caractères** (Bref : 6) — adapter la validation de l'écran. Erreurs : 401 `OTP_INVALID` « Code incorrect ou expiré » ; 429 `OTP_TOO_MANY_ATTEMPTS` → redemander un code ; 429 `OTP_TOO_MANY_REQUESTS` / `PORTAL_TOO_MANY_REQUESTS` « Trop de tentatives de connexion : réessayez dans quelques minutes. » ; 409 `EMAIL_ALREADY_EXISTS` « Cette adresse e-mail est déjà utilisée par un autre client. » → message sous le champ e-mail ; le code est alors consommé (l'erreur n'est donnée qu'après un code valide, pour que personne ne puisse sonder les e-mails) : redemander un code et renvoyer sans e-mail ou avec un autre ; 422 `VALIDATION_ERROR` (champ manquant, mot de passe trop court) / `VALIDATION_FAILED` « Adresse e-mail invalide. » ou mot de passe trop long → message sous le champ. Différence avec Bref : un numéro qui a déjà un compte n'est pas refusé ; son mot de passe est remplacé et ses autres appareils sont déconnectés (c'est le « mot de passe oublié »).
- `POST /v1/clients/login {identifier, password}` → `POST /api/v1/portal/auth/login {identifier, password}` (numéro, avec ou sans +237, ou e-mail) → `{data:{accessToken, expiresAt}}`. Erreurs : 401 `INVALID_CREDENTIALS` « Numéro, e-mail ou mot de passe incorrect. » (même réponse pour un compte inconnu, sans mot de passe ou un mauvais mot de passe) → proposer « Recevoir un code » (inscription) ; 429 `PORTAL_TOO_MANY_REQUESTS` (30 essais par adresse, 10 par numéro / e-mail en 15 min) → message, réessayer plus tard.
- Connexion par code seul (sans compte) : `POST /api/v1/portal/auth/otp/verify {phone, code}` → `{data:{accessToken, expiresAt}}` ; mêmes erreurs que l'inscription côté code.
- `GET /v1/clients/invoices` → `GET /api/v1/portal/invoices?limit=20&cursor=…` avec `Authorization: Bearer kvc_…` → `{data:{items:[{merchantName, reference, issueDate, dueDate, totalTtc, paymentState, paymentStateLabel, link:{url, expiresAt}|null, merchantPhone, amountPaid, amountRemaining, lines:[{reference, description, quantity, unitPrice, totalTtc}], payments:[{amount, dueDate, paid, paidDate, method}]}], nextCursor, summary:{invoiceCount, totalTtc}}}` (`summary` sur la première page seulement). Erreurs : 401 `PORTAL_SESSION_INVALID` « Votre session a expiré : reconnectez-vous. » → effacer le jeton, retour à l'écran de connexion ; 429 `PORTAL_TOO_MANY_REQUESTS` « Trop de demandes : patientez une minute avant de réessayer. » ; 400 `VALIDATION_ERROR` (curseur ou taille de page) → recharger depuis la première page.
- `GET /v1/invoices/public/count?identifier=` (écran « verrouillé ») → **non porté** : il révélait à n'importe qui si un numéro a des factures. L'écran « verrouillé » disparaît ; après le numéro, on propose directement « J'ai déjà un compte » (connexion) ou « Recevoir un code » (inscription).
- `POST /api/v1/portal/auth/logout` (Bearer) → 204, même si le jeton est inconnu.

**À implémenter côté mobile :**
1. Parcours « Je suis client » (23.8), comme Bref : écran numéro → connexion (identifiant + mot de passe) ou inscription (nom, mot de passe, e-mail facultatif, puis code à 6 chiffres avec compte à rebours et « Renvoyer le code ») → liste. Le jeton `kvc_…` va dans le stockage sécurisé client (distinct de la session commerçant), jamais dans les journaux. Lien « Mot de passe oublié ? » → inscription avec un nouveau code.
2. Liste : `merchantName`, `reference`, `issueDate` (format local), `totalTtc` en FCFA, badge `paymentStateLabel` (couleur selon `paymentState`, code jamais affiché). En-tête : `summary.invoiceCount` et `summary.totalTtc` de la première page (ne pas additionner la liste). Pagination par `nextCursor` (défilement infini, continuer tant qu'il n'est pas null). Aucun identifiant à afficher ni à stocker.
3. Détail (lu depuis l'élément de liste, comme Bref lisait son cache) : commerçant et `merchantPhone`, total, payé (`amountPaid`), reste (`amountRemaining`), pourcentage réglé, `payments` (reçus : montant + `paidDate` + `method` ; échéances : montant + `dueDate`, « Payée » si `paid`), `lines` (référence, désignation, quantité × prix unitaire, total TTC). Bouton « Voir la facture (PDF) » → `link.url` ; facture annulée : pas de lien, badge « Annulée », reste à payer 0. Le lien expire en 24 h : ne pas le mettre en cache, relister pour un lien neuf.
4. Déconnexion : appeler `logout` puis effacer le jeton quoi qu'il arrive. 401 à tout moment (session de 24 h écoulée) → même chose, retour à la connexion par mot de passe (aucun SMS).
5. En ligne uniquement : hors connexion, message « Connexion requise pour voir vos factures ».

**Implémentation de référence :** aucune dans `keevo/app` (Flutter = client de test). Écrans Bref à adapter : `bref/features/find-invoice/screens/*`, `bref/features/client-auth/api/client-auth.api.ts`.

**Critères d'acceptation mobile / scénarios de test :**
- Given un numéro sans compte, when le client s'inscrit (code + nom + mot de passe), then il arrive sur la liste ; when il se déconnecte puis se reconnecte avec son numéro et son mot de passe, then aucune SMS n'est envoyé.
- Given une facture émise au `+237677000111` par deux commerçants, when le client se connecte, then il voit les deux factures (plus récente en premier), l'en-tête « 2 factures », le détail avec lignes et paiements, et ouvre le PDF de chacune.
- Given le commerçant A coupe « Portail client », when le client rafraîchit, then seule la facture de B reste.
- Given un jeton expiré (24 h) ou révoqué, when la liste est demandée, then 401 → retour à l'écran de connexion.
- Mise en place (curl) : `curl -X POST $API/api/v1/portal/auth/otp/request -H 'Content-Type: application/json' -d '{"phone":"677000111"}'` puis `…/register -d '{"name":"Awa","phone":"677000111","code":"<code SMS>","password":"motdepasse1"}'` puis `…/login -d '{"identifier":"677000111","password":"motdepasse1"}'` puis `curl $API/api/v1/portal/invoices -H "Authorization: Bearer kvc_…"`.

**Clé de suivi mobile :** `m21-7-client-portal`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée (commit `8f575524` ; reprise du 11/11/2026 : commit `cc8b8530`, clé `d21-7-client-portal`). Aucun nouvel endpoint super-admin.

**Ce qui est fait côté API :** le module `client-portal` « Portail client » (gratuit, catégorie business) apparaît dans le catalogue des modules et dans les états de modules d'une entreprise, comme les autres modules ; sa coupure par l'owner est l'activation owner existante (19.13). Reprise : comme Bref, il est désormais **activé pour toutes les entreprises** (V84 retire la coupure posée par V70 sur les entreprises existantes, sauf si l'owner l'avait coupé lui-même).

**À utiliser :** endpoints admin existants du catalogue de modules et des modules d'une entreprise (`/api/v1/admin/**`, inchangés) ; libellé `displayName` « Portail client ».

**À implémenter côté dashboard :** 1. afficher le module avec son libellé (jamais l'identifiant) dans le catalogue et la fiche entreprise ; 2. aucun écran de gestion des clients du portail (données personnelles des clients finaux, pas d'administration prévue).

**Critères d'acceptation :** Given la fiche d'une entreprise existante, when ses modules s'affichent, then « Portail client » apparaît activé (sauf coupure par l'owner).

**Clé de suivi dashboard :** `d21-7-client-portal`.

## Auto Run Result

**Résumé :** module gratuit `client-portal` « Portail client » : clients finaux (`public.portal_clients`, distincts des comptes commerçants) connectés par un code SMS sous un usage OTP propre (`OtpPurpose.CLIENT_PORTAL`), session opaque `kvc_` (SHA-256 seulement, 30 jours, 10 par client, purge nocturne), liste keyset de leurs factures toutes entreprises confondues depuis `public.invoice_phone_index` (alimenté dans la transaction d'émission + rattrapage unique au démarrage), filtrée en SQL (entreprise active, portail actif côté owner et ligne de module), documents lus chacun dans le schéma de leur entreprise en une requête `UNION ALL`, champs minimum + lien PDF signé 24 h. Migration publique V70.

**Fichiers :** `clientportal/**` (module, auth, invoice), `identity/api/ClientPortalCodePort` + `ClientPortalCodeAdapter` + `OtpPurpose` + refactor `RequestOtpService.issue` / `VerifyOtpService.check`, `invoicing` (`InvoiceLifecycleUseCase` indexe à l'émission, `JdbcPortalDocuments`, `InvoicePhoneIndexBackfillJob`), `shared/capability/portal/*`, `PublicBaseUrl` déplacé dans `shared/infrastructure/web`, codes `PORTAL_SESSION_INVALID` (401) / `PORTAL_TOO_MANY_REQUESTS` (429), chemin public `/api/v1/portal/**`, `V70__client_portal.sql`, manifeste, contrat `keevo/contracts/client-portal.json`, `docs/entitlements/endpoints.md` régénéré, épinglages de modules (architecture, shared).

**Revue :** 33 constats (5 couches) — 11 corrigés (high 1, medium 5, low 5), 22 rejetés avec motif (Review Triage Log), 0 reporté. Revue de suivi recommandée : un `high` corrigé (défaut de confidentialité de V70 non épinglé) et cinq `medium` (frein par adresse, filtrage avant LIMIT, factures remplacées, couverture de tests) — risque restant non vérifié : comportement des freins par instance derrière plusieurs instances, et l'EXPLAIN de la comparaison de ligne sur l'index DESC en volume réel.

**Vérification :** compilation OK ; tests par paquet (après correctifs) : clientportal 14/14, identity 866/866, invoicing 471/471, architecture 146/146, admin 690/690, shared 1 354/1 354, subscription 153/153. Live (base `keevo_dev_217`, port 4517, WhatsApp noop, FCM off, deux entreprises neuves) : code portail refusé sur `/auth/otp/verify` (401), session `kvc_` (47 caractères), liste des 2 factures du téléphone chez 2 entreprises (pas celle de l'autre numéro), lien PDF 200 `application/pdf`, JWT commerçant refusé au portail et jeton portail refusé côté commerçant (401), coupure par l'owner → 1 facture, déconnexion → 401 en français, rattrapage marqué ; base supprimée ensuite.

**Risques résiduels :** freins en mémoire par instance ; un numéro réattribué voit les factures de l'ancien titulaire (décision ci-dessus) ; l'import Bref (24.6) doit appeler `InvoicePhoneIndex.indexAll` pour les factures importées.

### Reprise 11/11/2026 — résultat

**Résumé :** portail client aligné sur Bref (décision utilisateur du 11/11/2026). V84 : portail réactivé pour les entreprises existantes (seules les lignes posées par V70 sont retirées ; un owner qui l'avait coupé lui-même le garde coupé), `portal_clients` + `name` / `email` (minuscules, unique) / `password_hash` (BCrypt 12), sessions vivantes ramenées à 24 h. Nouveaux `POST /api/v1/portal/auth/register` (code une fois + nom + mot de passe 8–72 + e-mail facultatif ; un numéro déjà inscrit reçoit le nouveau mot de passe et ses autres sessions tombent) et `POST /api/v1/portal/auth/login` (numéro ou e-mail + mot de passe, sans SMS, freins Bref 30/IP et 10/identifiant par 15 min, BCrypt leurre pour un identifiant inconnu). Session 24 h (`keevo.portal.session-ttl`). Chaque facture listée porte en plus `merchantPhone`, `amountPaid`, `amountRemaining`, `lines`, `payments` ; la première page porte `summary {invoiceCount, totalTtc}` exact. Écarts gardés et justifiés : tableau Bref ↔ Keevo ci-dessus.

**Fichiers :** `V84__client_portal_bref_alignment.sql` ; `clientportal/auth/**` (service, limiteur, dépôt, contrôleur) ; `clientportal/invoice/**` (service, lecteur d'index `merchantsOf`, contrôleur) ; `shared/capability/portal/PortalDocuments` ; `invoicing/.../JdbcPortalDocuments` ; `ErrorMessages` ; `db/modules/README.md` ; contrat `keevo/contracts/client-portal.json` ; `docs/entitlements/endpoints.md` régénéré ; tests (`ClientPortalFullContextTest`, `ClientPortalBrefAlignmentMigrationPostgresIntegrationTest`, `ClientPortalUnitTest`, `JdbcPortalDocumentsTest`, `ModuleOwnerActivationMigrationPostgresIntegrationTest`, `ModuleIdsAtGoLivePinTest`).

**Revue :** 44 constats (5 couches) — 15 corrigés (medium 6, low 9), 29 rejetés avec motif (journal ci-dessus), 0 reporté. Revue de suivi recommandée (6 `medium` corrigés) : risque non vérifié — le chemin de course `DuplicateKeyException` de l'inscription (catch restreint à l'index e-mail, non testé) et le coût du `SELECT DISTINCT` des commerçants d'un téléphone en très gros volume.

**Vérification :** tests par paquet (après correctifs) : clientportal 20/20, invoicing 475/475, identity 966/966, shared 1 397/1 397, admin 721/721, architecture 171 (1 ignoré, préexistant) ; `docs/entitlements/endpoints.md` régénéré (406 handlers, +register/+login exemptés). Live (base `keevo_dev_t217r`, port 4627, WhatsApp noop, FCM off, deux entreprises neuves) : V84 appliquée ; inscription avec code connu → jeton `kvc_` 47 caractères expirant à +24 h, nom / e-mail en minuscules / hash `$2a$` en base ; connexion par numéro local et par e-mail 200 ; mauvais mot de passe 401 « Numéro, e-mail ou mot de passe incorrect. » ; liste : `summary` 2 factures / 56 700, lignes dans l'ordre, paiement Mobile Money, payé 5 000 / reste 23 350 ; aucun identifiant technique dans la réponse ; lien PDF 200 `application/pdf` ; déconnexion 204 puis 401 « Votre session a expiré : reconnectez-vous. » ; base supprimée ensuite.

**Risques résiduels :** freins en mémoire par instance (comme Bref) ; le module de l'app (m21-7) doit passer au parcours inscription / connexion ; les comptes clients n'ont pas encore de suppression (Bref non plus).
