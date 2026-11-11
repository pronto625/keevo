---
title: '21-7 — Module client-portal: end clients find all their invoices by phone (OTP), across merchants'
type: 'feature'
created: '2026-11-10'
status: 'done'
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

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- expected: BUILD SUCCESS
- `wt-pkgs.sh keevo/backend fix217 clientportal identity invoicing architecture admin shared subscription` -- expected: all green
- Bad cursor / limit answer 400 `VALIDATION_ERROR` (`InvalidRequestParameterException`, the cursor convention of the API) — the matrix row « bad cursor 400 » is met with that domain code.
- `-Dkeevo.entitlements.regenerate=true` on `EndpointEntitlementCoverageTest` -- expected: `docs/entitlements/endpoints.md` lists the portal endpoints as exempt

**Manual checks:**
- Live on `keevo_dev_217`, port 4517 (WhatsApp noop, FCM off): two fresh test companies, invoices issued to one phone, portal sign-in (known code by hash), listing, owner switch, link opens the PDF, logout.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `8f575524`, clé `m21-7-client-portal` dans `sprint-status.yaml`). Contrat : `keevo/contracts/client-portal.json`. Conventions d'erreur et d'affichage : spec-16-11 ; protocole de synchro : spec-16-10 (rien à synchroniser : portail en ligne uniquement).

**Ce qui est fait côté API :** module gratuit `client-portal` « Portail client ». Un client final (jamais un compte Keevo) reçoit un code par SMS (et WhatsApp si configuré) sur son téléphone, le saisit et obtient une session portail de 30 jours ; il voit alors toutes les factures que les commerçants ont émises à ce numéro, du plus récent au plus ancien, avec un lien vers le PDF valable 24 h. Le commerçant coupe le portail dans « Mes modules » (module « Portail client ») : ses factures disparaissent aussitôt. Les entreprises qui existaient avant le portail démarrent avec le portail coupé ; les nouvelles (dont les entreprises importées de Bref) l'ont activé.

**À utiliser** (code : `keevo/backend/src/main/java/com/keevo/clientportal/**/adapter/in/rest/*Controller.java`) — correspondance Bref → Keevo :
- `POST /v1/clients/otp/request {phone}` → `POST /api/v1/portal/auth/otp/request {phone}` → `{data:{expiresIn:300}}`. Erreurs : 422 `PHONE_COUNTRY_NOT_SUPPORTED` (message à afficher), 422 `VALIDATION_FAILED` (pas un numéro de téléphone), 429 `OTP_TOO_MANY_REQUESTS` « Trop de demandes de code. Réessayez plus tard » → bloquer « Renvoyer le code » quelques minutes.
- `POST /v1/clients/register` et `POST /v1/clients/login` (mot de passe) → **remplacés** par `POST /api/v1/portal/auth/otp/verify {phone, code}` → `{data:{accessToken:"kvc_…", expiresAt}}`. Plus de mot de passe ni d'inscription : le code suffit à chaque connexion (une session dure 30 jours). Erreurs : 401 `OTP_INVALID` « Code incorrect ou expiré » → laisser ressaisir ou redemander un code ; 429 `OTP_TOO_MANY_ATTEMPTS` → redemander un code ; 429 `OTP_TOO_MANY_REQUESTS`.
- `GET /v1/clients/invoices` → `GET /api/v1/portal/invoices?limit=20&cursor=…` avec `Authorization: Bearer kvc_…` → `{data:{items:[{merchantName, reference, issueDate, dueDate, totalTtc, paymentState, paymentStateLabel, link:{url, expiresAt}|null}], nextCursor}}`. Erreurs : 401 `PORTAL_SESSION_INVALID` « Votre session a expiré : reconnectez-vous avec le code reçu par SMS. » → effacer le jeton, retour à l'écran du numéro ; 429 `PORTAL_TOO_MANY_REQUESTS` « Trop de demandes : patientez une minute avant de réessayer. » ; 400 `VALIDATION_ERROR` (curseur ou taille de page) → recharger depuis la première page.
- `GET /v1/invoices/public/count?identifier=` (écran « verrouillé » avec le nombre de factures avant connexion) → **non porté** : il révélait à n'importe qui si un numéro a des factures. L'écran « verrouillé » disparaît ; on va directement au code.
- Nouveau : `POST /api/v1/portal/auth/logout` (Bearer) → 204, même si le jeton est inconnu.

**À implémenter côté mobile :**
1. Entrée « Je suis client » (23.8) : écran numéro → écran code → liste ; le jeton `kvc_…` va dans le stockage sécurisé, jamais dans les journaux.
2. Liste : `merchantName`, `reference`, `issueDate` (format local), `totalTtc` en FCFA, badge `paymentStateLabel` (couleur selon `paymentState`, code jamais affiché), échéance si `dueDate`. Pagination par `nextCursor` (défilement infini) ; aucun identifiant à afficher ni à stocker.
3. Détail : ouvrir `link.url` (PDF, navigateur intégré ou téléchargement). Facture annulée : pas de lien, badge « Annulée ». Le lien expire en 24 h : ne pas le mettre en cache, relister pour un lien neuf.
4. Déconnexion : appeler `logout` puis effacer le jeton quoi qu'il arrive. 401 à tout moment → même chose.
5. En ligne uniquement : hors connexion, message « Connexion requise pour voir vos factures ».

**Implémentation de référence :** aucune dans `keevo/app` (Flutter = client de test).

**Critères d'acceptation mobile / scénarios de test :**
- Given une facture émise au `+237677000111` par deux commerçants, when le client se connecte avec ce numéro, then il voit les deux factures (plus récente en premier) et ouvre le PDF de chacune.
- Given le commerçant A coupe « Portail client », when le client rafraîchit, then seule la facture de B reste.
- Given un jeton expiré ou révoqué, when la liste est demandée, then 401 → retour à l'écran du numéro.
- Mise en place (curl) : `curl -X POST $API/api/v1/portal/auth/otp/request -H 'Content-Type: application/json' -d '{"phone":"677000111"}'` puis `…/otp/verify -d '{"phone":"677000111","code":"<code SMS>"}'` puis `curl $API/api/v1/portal/invoices -H "Authorization: Bearer kvc_…"`.

**Clé de suivi mobile :** `m21-7-client-portal`.

## Dashboard super-admin (autre dépôt)

**Statut :** API livrée (commit `8f575524`, clé `d21-7-client-portal`). Aucun nouvel endpoint super-admin.

**Ce qui est fait côté API :** le module `client-portal` « Portail client » (gratuit, catégorie business) apparaît dans le catalogue des modules et dans les états de modules d'une entreprise, comme les autres modules ; sa coupure par l'owner est l'activation owner existante (19.13).

**À utiliser :** endpoints admin existants du catalogue de modules et des modules d'une entreprise (`/api/v1/admin/**`, inchangés) ; libellé `displayName` « Portail client ».

**À implémenter côté dashboard :** 1. afficher le module avec son libellé (jamais l'identifiant) dans le catalogue et la fiche entreprise ; 2. aucun écran de gestion des clients du portail (données personnelles des clients finaux, pas d'administration prévue).

**Critères d'acceptation :** Given la fiche d'une entreprise, when ses modules s'affichent, then « Portail client » apparaît avec son état (activé / coupé par l'owner).

**Clé de suivi dashboard :** `d21-7-client-portal`.

## Auto Run Result

**Résumé :** module gratuit `client-portal` « Portail client » : clients finaux (`public.portal_clients`, distincts des comptes commerçants) connectés par un code SMS sous un usage OTP propre (`OtpPurpose.CLIENT_PORTAL`), session opaque `kvc_` (SHA-256 seulement, 30 jours, 10 par client, purge nocturne), liste keyset de leurs factures toutes entreprises confondues depuis `public.invoice_phone_index` (alimenté dans la transaction d'émission + rattrapage unique au démarrage), filtrée en SQL (entreprise active, portail actif côté owner et ligne de module), documents lus chacun dans le schéma de leur entreprise en une requête `UNION ALL`, champs minimum + lien PDF signé 24 h. Migration publique V70.

**Fichiers :** `clientportal/**` (module, auth, invoice), `identity/api/ClientPortalCodePort` + `ClientPortalCodeAdapter` + `OtpPurpose` + refactor `RequestOtpService.issue` / `VerifyOtpService.check`, `invoicing` (`InvoiceLifecycleUseCase` indexe à l'émission, `JdbcPortalDocuments`, `InvoicePhoneIndexBackfillJob`), `shared/capability/portal/*`, `PublicBaseUrl` déplacé dans `shared/infrastructure/web`, codes `PORTAL_SESSION_INVALID` (401) / `PORTAL_TOO_MANY_REQUESTS` (429), chemin public `/api/v1/portal/**`, `V70__client_portal.sql`, manifeste, contrat `keevo/contracts/client-portal.json`, `docs/entitlements/endpoints.md` régénéré, épinglages de modules (architecture, shared).

**Revue :** 33 constats (5 couches) — 11 corrigés (high 1, medium 5, low 5), 22 rejetés avec motif (Review Triage Log), 0 reporté. Revue de suivi recommandée : un `high` corrigé (défaut de confidentialité de V70 non épinglé) et cinq `medium` (frein par adresse, filtrage avant LIMIT, factures remplacées, couverture de tests) — risque restant non vérifié : comportement des freins par instance derrière plusieurs instances, et l'EXPLAIN de la comparaison de ligne sur l'index DESC en volume réel.

**Vérification :** compilation OK ; tests par paquet (après correctifs) : clientportal 14/14, identity 866/866, invoicing 471/471, architecture 146/146, admin 690/690, shared 1 354/1 354, subscription 153/153. Live (base `keevo_dev_217`, port 4517, WhatsApp noop, FCM off, deux entreprises neuves) : code portail refusé sur `/auth/otp/verify` (401), session `kvc_` (47 caractères), liste des 2 factures du téléphone chez 2 entreprises (pas celle de l'autre numéro), lien PDF 200 `application/pdf`, JWT commerçant refusé au portail et jeton portail refusé côté commerçant (401), coupure par l'owner → 1 facture, déconnexion → 401 en français, rattrapage marqué ; base supprimée ensuite.

**Risques résiduels :** freins en mémoire par instance ; un numéro réattribué voit les factures de l'ancien titulaire (décision ci-dessus) ; l'import Bref (24.6) doit appeler `InvoicePhoneIndex.indexAll` pour les factures importées.
