---
title: '21-7 — Module client-portal: end clients find all their invoices by phone (OTP), across merchants'
type: 'feature'
created: '2026-11-10'
status: 'in-progress'
baseline_revision: '517b2c42200d2883a169bf48ef93dfc52dae8ebf'
review_loop_iteration: 0
followup_review_recommended: false
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

## Design Notes

- **Patterns.** Ports & adapters across Modulith boundaries: `shared.capability.portal.InvoicePhoneIndex` (Observer-free direct port, called in the issue transaction so index and invoice commit together — no event to lose) implemented by `clientportal`; `PortalDocuments` (Repository/Facade over another module's tables) implemented by invoicing, so the portal never touches invoice tables and invoicing never depends on the portal. OTP: Template Method — one core (`issue` / `check` by challenge key) shared by the merchant and portal purposes (`OtpPurpose` value object builds the key `portal:` + phone; merchant key unchanged = no migration, no behaviour change). Sessions: opaque bearer token as 21.9 (hash lookup, revocation by delete, no cache). Keyset pagination with an opaque base64url cursor.
- **Costs.** Issue: +1 `INSERT … SELECT … ON CONFLICT` (tenant id by unique `schema_name`). Sign-in: 19.2 cost + 1 upsert client + 1 insert session + 1 bounded delete (≤ 10 kept). Listing (page P ≤ 50): 1 PK session read; 1 index range scan `(phone, issued_at DESC, …) LIMIT P+1` joined to `tenants` (PK) and `tenant_module_activation` (PK, anti-join); per distinct merchant on the page D ≤ P: cached `isEnabled` + warm `ensureProvisioned` (in-memory once warm); 1 `UNION ALL` statement over D schemas (`id = ANY(?)` on each invoices PK); P HMACs. Memory O(P). Back-fill: per tenant keyset pages of 500, one batch insert per page (one-shot, boot, ShedLock). Purge: one DELETE on the `expires_at` index.
- **Decisions.** Default off for existing tenants, on for new ones (README rule; ex-Bref tenants are created by the import and so have it on, like Bref). Link validity 24 h (the portal re-signs on every listing; a forwarded link dies quickly). Cancelled invoices are listed (legal document, « Annulée ») without a link. Sessions are fixed 30 days (Bref: 1-day access + refresh; one token is simpler, revocable, re-sign-in costs one SMS a month).

## Verification

**Commands:**
- `kmvn.sh keevo/backend clean compile` -- expected: BUILD SUCCESS
- `wt-pkgs.sh keevo/backend fix217 clientportal identity invoicing architecture admin shared subscription` -- expected: all green
- `-Dkeevo.entitlements.regenerate=true` on `EndpointEntitlementCoverageTest` -- expected: `docs/entitlements/endpoints.md` lists the portal endpoints as exempt

**Manual checks:**
- Live on `keevo_dev_217`, port 4517 (WhatsApp noop, FCM off): two fresh test companies, invoices issued to one phone, portal sign-in (known code by hash), listing, owner switch, link opens the PDF, logout.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `__COMMIT__`, clé `m21-7-client-portal` dans `sprint-status.yaml`). Contrat : `keevo/contracts/client-portal.json`. Conventions d'erreur et d'affichage : spec-16-11 ; protocole de synchro : spec-16-10 (rien à synchroniser : portail en ligne uniquement).

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

**Statut :** API livrée (commit `__COMMIT__`, clé `d21-7-client-portal`). Aucun nouvel endpoint super-admin.

**Ce qui est fait côté API :** le module `client-portal` « Portail client » (gratuit, catégorie business) apparaît dans le catalogue des modules et dans les états de modules d'une entreprise, comme les autres modules ; sa coupure par l'owner est l'activation owner existante (19.13).

**À utiliser :** endpoints admin existants du catalogue de modules et des modules d'une entreprise (`/api/v1/admin/**`, inchangés) ; libellé `displayName` « Portail client ».

**À implémenter côté dashboard :** 1. afficher le module avec son libellé (jamais l'identifiant) dans le catalogue et la fiche entreprise ; 2. aucun écran de gestion des clients du portail (données personnelles des clients finaux, pas d'administration prévue).

**Critères d'acceptation :** Given la fiche d'une entreprise, when ses modules s'affichent, then « Portail client » apparaît avec son état (activé / coupé par l'owner).

**Clé de suivi dashboard :** `d21-7-client-portal`.
