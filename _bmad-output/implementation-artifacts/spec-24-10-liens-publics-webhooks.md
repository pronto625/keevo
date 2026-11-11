---
title: '24-10 Redirect old Bref public links to Keevo (one-shot cutover, ADR-10) + runbook actions for proxy and webhooks'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: 'f5e864a8a9fea4540a96d5a5c5402d0c0eae7b42'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-20-7-modeles-signatures-liens-publics.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-24-4-import-documents-paiements-depenses.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** After the one-shot cutover (ADR-10) api-bref is stopped, yet clients still hold Bref public invoice links. Today Keevo answers Bref's path `GET /api/v1/invoices/public/{uuid}` (20.7) with the PDF itself and JSON errors; the epic wants a 302 to Keevo's public page (reconciliation 24.8 checks « 302 → 200 »), a clean French page for unknown/expired links, and a runbook telling the user how to point the Bref domain and the RevenueCat / MyCoolPay / Meta webhooks at Keevo.

**Approach:** The legacy endpoint resolves the Bref token exactly as today (registry, expiry, tenant active, invoice still holds the token) and answers **302** to the document's signed Keevo link (`/api/v1/invoices/shared?token=…`, current link version, expiry capped by Bref's own expiry). Browsers (`Accept: text/html`) get a static French HTML page on every failure of the two public-link endpoints; API clients keep today's JSON. A versioned Traefik file (plus nginx equivalent) and a cutover runbook section (24.13) describe the proxy rule and the exact webhook URLs to change.

## Boundaries & Constraints

**Always:** same resolution rules and statuses as 20.7 (unknown/malformed/retired/tenant not active → 404 `INVOICE_LINK_INVALID`, past Bref expiry → 410 `INVOICE_LINK_EXPIRED`, 429 limits, 503 on unexpected failure); the redirect never extends access beyond Bref's expiry; `Location` is absolute on `keevo.public-base-url`; `Cache-Control: no-store` and `Referrer-Policy: no-referrer` on the redirect and pages; HTML pages are static French text (no token, no tenant, no number, no code); per-address miss counting uses the real client IP behind the proxy (`ClientIps`); JSON answers unchanged for non-HTML clients (V1 2.0.1 compatibility, additive only); stay in `invoicing` + docs/ops.

**Never:** touch the `migration` module (24.6/24.8 in parallel); code a webhook router or dual delivery; change servers, DNS or provider dashboards (user actions); a Flyway migration (none needed — V89 unused); a 301 at the proxy before the « go » (a cached permanent redirect would survive a rollback).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Imported link | registered token, tenant ACTIVE, invoice holds token | 302 `Location: <base>/api/v1/invoices/shared?token=<signed>`; following it → 200 PDF with `BRF-…` | — |
| Expiry cap | Bref expiry in 10 days, tenant links 90 days | signed link expires at Bref's expiry | — |
| No Bref expiry | `expires_at` null | signed link = now + tenant link days | — |
| Unknown / malformed / retired / tenant suspended | — | 404 | browser: « Lien introuvable » page; API: JSON `INVOICE_LINK_INVALID` |
| Expired | Bref expiry past | 410 | browser: « Lien expiré » page; API: JSON |
| Too many | 31st opening / 21st miss | 429 | browser: « Trop de tentatives » page |
| Rotated since redirect | signed link of an old version | /shared → 404 | browser page as above |
| Shared link without token | `/api/v1/invoices/shared` | 400 | status 400 kept; browser: « Lien introuvable » page |

</intent-contract>

## Code Map

- `keevo/backend/src/main/java/com/keevo/invoicing/invoice/adapter/in/rest/InvoiceLegacyLinkController.java` -- 20.7 legacy endpoint; today returns the PDF; uses `request.getRemoteAddr()` for misses (wrong behind Traefik).
- `.../invoicing/invoice/adapter/in/rest/InvoiceSharedLinkController.java` -- signed link PDF (`/api/v1/invoices/shared?token=`), target of the redirect.
- `.../invoicing/invoice/application/usecase/InvoicePublicLinkUseCase.java` -- `resolveLegacy`, `assertLegacyCurrent`, `signedLink`, `SHARED_PATH`; add the redirect computation here.
- `.../invoicing/invoice/application/service/InvoiceLinkSigner.java#sign(schema, id, expiresAt, version)` -- arbitrary expiry supported.
- `.../invoicing/invoice/adapter/out/persistence/JdbcInvoicePresentationRepository.java#find` -- one PK read (deleted rows excluded) giving `legacyToken` + `linkVersion`.
- `.../shared/infrastructure/web/PublicBaseUrl.java`, `ClientIps.java`, `GlobalExceptionHandler.java#handleDomainException/#handleMissingParam` -- reuse (status mapping and JSON body kept by delegation).
- `.../shared/infrastructure/security/SecurityConfig.java` -- both paths already in `PUBLIC_PATHS`.
- Tests: `src/test/java/com/keevo/invoicing/InvoiceTemplatesLinksFullContextTest.java` (`legacyBrefLink`, `legacyLimits` expect PDF today), `src/test/java/com/keevo/invoicing/invoice/application/usecase/InvoicePublicLinkUseCaseTest.java`.
- `keevo/ops/traefik/staging-router.yml` -- shape of a Traefik file-provider template.
- Bref (read-only, `git show develop:`): api-bref `src/main.ts` (prefix `api`, URI versioning; `webhook/whatsapp`, `webhook/revenuecat` excluded from the prefix), `src/invoice/invoice-public.controller.ts` (`GET /api/v1/invoices/public/:token` JSON, UUID, 401 when expired; `count` sub-path is not a link), `src/payment-gateway/payment-gateway-webhook.controller.ts` (`POST /api/webhook/pay/:provider/:hash`), `src/storekit/storekit-webhook.controller.ts`, `src/whatsapp/whatsapp.controller.ts`; bref app: no screen or message carries the token link (outbox sends the PDF + `brefpoint.app/download`).
- Keevo webhooks: `payments/appstore/.../RevenueCatWebhookController` (`POST /api/v1/payments/webhook/revenuecat`), `payments/checkout/.../PaymentWebhookController` (`POST /api/v1/payments/webhook/mycoolpay/{hash}`), `whatsappcloud/webhook/.../MetaWebhookController` (`GET|POST /api/v1/whatsapp/webhook`); secrets in `application.yml` (`REVENUECAT_WEBHOOK_SECRET`, `MYCOOLPAY_CALLBACK_HASH`, `KEEVO_WHATSAPP_CLOUD_VERIFY_TOKEN`, `KEEVO_WHATSAPP_CLOUD_APP_SECRET`).

## Tasks & Acceptance

**Execution:**
- `.../InvoicePublicLinkUseCase.java` -- add `legacyRedirect(token, target, baseUrl)`: one presentation read (must hold the token, else 404), version, expiry = min(Bref expiry, now + tenant days), signed link; `assertLegacyCurrent` replaced by it (remove if unused).
- `.../InvoiceLegacyLinkController.java` -- 302 with `Location`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`; `ClientIps.of(request)`; base from `PublicBaseUrl`.
- `.../adapter/in/rest/PublicLinkErrorPages.java` (new) -- `@RestControllerAdvice(assignableTypes = {legacy, shared})`, highest order: `DomainException` / missing parameter → HTML page when `Accept` contains `text/html`, else delegate to `GlobalExceptionHandler`.
- Tests: use-case unit tests (cap, null expiry, not holder, deleted, version); advice unit tests (HTML vs JSON, each status, no leak); full-context: 302 → 200 PDF, browser pages 404/410/429, JSON kept, misses counted per `X-Forwarded-For`.
- `keevo/ops/traefik/bref-legacy-links.yml` (new) -- Traefik routers + redirectRegex middleware (302) for the Bref API host, nginx equivalent in the runbook.
- `docs/RUNBOOK_BASCULE_BREF.md` (new, French) -- section « Actions utilisateur — liens publics et webhooks » (proxy rule, checks with curl, webhook URL table old → new with secrets to carry over, test events, rollback); the rest of the runbook stays for 24.13.
- `docs/entitlements/endpoints.md` regenerated; sprint-status + epics (24.13 item) updated.

**Acceptance Criteria:**
- Given an imported invoice with a Bref token, when `GET /api/v1/invoices/public/{token}` is called, then the answer is 302 to the signed Keevo link and following it returns the PDF (200) showing `BRF-…`.
- Given a browser opening an unknown, expired or over-limit link, when the page is served, then it is French HTML with the right status and contains no token, number, tenant or error code.
- Given the runbook, when the user prepares the cutover, then it lists the proxy rule and each webhook URL (Bref old → Keevo new) for RevenueCat, MyCoolPay and Meta, with the secrets to carry over, a test per provider and the rollback.

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 28 findings — high 0, medium 3, low 22, false 3, maybe-false 0 (5 intent-alignment observations triaged below as findings)
- findings:
  - `[medium]` `[patch]` (blind) Traefik `redirectRegex` anchored with `$` on the full URL: a link with `?fbclid=…`/`?utm_…` matches the router but not the middleware and falls to `noop@internal` — regex now ends `(\\?.*)?$`, query dropped; covered by `BrefLegacyProxyRuleTest`.
  - `[low]` `[patch]` (blind) Template rollback said « remove this file » while the Keevo install merges into the single `dynamic.yml` — template and runbook §1.6 now restore `dynamic.yml.bak-24-10` in place.
  - `[low]` `[patch]` (blind) Traefik v2 alternative dropped the unprefixed `/v1/...` variant — both `Path(...)` alternatives given (template + runbook).
  - `[low]` `[patch]` (blind) `priority: 100` lower than Traefik's default (rule length) — set to 10000 with a comment; asserted in `BrefLegacyProxyRuleTest`.
  - `[low]` `[patch]` (blind) No `Vary: Accept` on content-negotiated errors, JSON branch cacheable — both branches send `Vary: Accept` and `no-store`; unit tests assert it.
  - `[low]` `[reject]` (blind) 405 / exceptions outside `DomainException` reach a browser as JSON — both controllers wrap every `RuntimeException` into `DomainException`; a 405 is resolved without a handler (scoped advice cannot apply); rare, a catch-all would add surface.
  - `[low]` `[patch]` (blind) Per-address miss limit trusts the right-most `X-Forwarded-For` (`ClientIps`, pre-existing shared pattern) — only safe if the backend port is reachable through Traefik only: runbook §1.2 network prerequisite + external check added.
  - `[low]` `[patch]` (blind) `validityDays` kept the tenant value when capped by Bref's expiry — real days left, rounded up; unit test.
  - `[low]` `[patch]` (blind) Retired token / deleted document not counted as a miss — `recordMiss` on `INVOICE_LINK_INVALID` from the tenant-bound step.
  - `[low]` `[patch]` (blind) Runbook sampling query may pick a token retired or deleted in Keevo → false no-go — query returns schema and invoice, runbook explains how to read a 404 and redraw.
  - `[low]` `[reject]` (blind) Base-URL fallback lets `Host`/`X-Forwarded-Host` choose the redirect host — only the requester can set his own headers (he redirects himself), the 302 is `no-store` (no cache poisoning); prod sets `KEEVO_PUBLIC_BASE_URL` (runbook prerequisite).
  - `[false]` `[reject]` (blind) Preview bots (WhatsApp, Gmail) consume the 30-openings budget — unchanged from 20.7 (same per-token window, counted once per opening); 30 per 10 min per link is far above previews.
  - `[medium]` `[patch]` (edge) Query string breaks the Traefik redirect — same root cause as the first blind finding, same fix.
  - `[low]` `[patch]` (edge) `http://` links not covered by a websecure-only router — Keevo Traefik redirects :80 → https globally (`deploy-backend.yml`); template and runbook tell to check / add `web` on the Bref Traefik.
  - `[false]` `[reject]` (edge) `IllegalArgumentException` from `TenantSchema.validate` escapes the pages — `JdbcLegacyInvoiceLinkRegistry.find` already filters invalid schemas (`TenantSchema.isValid`), the call cannot throw.
  - `[low]` `[reject]` (edge) 400/401/403 domain errors shown as « Service indisponible » — the two controllers raise only 404/410/429/503 domain errors; the 400 missing-token case has its own mapping.
  - `[low]` `[patch]` (edge) Sub-second Bref expiry: returned `expiresAt` differs from the signed second — capped expiry truncated to the second; unit test.
  - `[low]` `[patch]` (edge) Retired token not counted as a miss — same as blind finding, same fix.
  - `[low]` `[reject]` (edge) Location host from headers when base URL unset — same as blind finding, same reason.
  - `[medium]` `[patch]` (verification-gap, other) Query string breaks the Traefik redirect — same root cause, same fix.
  - `[low]` `[patch]` (intent) Proxy hop untested: no test on host, regex, prefix variant, `count` excluded — `BrefLegacyProxyRuleTest` parses the versioned template and checks router and middleware against the Bref link shapes.
  - `[low]` `[reject]` (intent) « ≥ 5 real links 302 → 200 » is a runbook procedure, not a test — real Bref tokens exist only on the prod copy (24.12) and on J0; the synthetic chain is tested end to end (MockMvc) and live.
  - `[false]` `[reject]` (intent) Live check not recorded in the diff — the live run is recorded in this spec (Auto Run Result) and the scratch script is not a deliverable.
  - `[low]` `[reject]` (intent) Two redirects and the « public page » is the signed PDF endpoint — Keevo has no other public invoice page; the intent allows « directement sa page/PDF »; D1 explains.
  - `[low]` `[reject]` (intent) API clients still get JSON with `domainCode` — D3 (V1 compatibility); the code names the error, no token/document/company data.
  - `[low]` `[patch]` (nfr11) Link signing duplicated between `signedLink` and `legacyRedirect` — one private `signedLink(…, cap)`; `validityDays` follows the cap.
  - `[low]` `[patch]` (nfr11) HTML pages formatted on every error — rendered once into a static map.
  - `[low]` `[reject]` (nfr11) Two backend requests per legacy open — inherent to D1 (redirect to the signed link); constant cost, recorded in Design Notes.

## Design Notes

**Pattern.** *Post/Redirect to a capability URL*: the legacy UUID is a lookup key only; the redirect hands the browser the existing signed-link capability (single place that renders public PDFs, rotation and rate-limit rules). *Scoped exception advice* (`assignableTypes`) instead of touching the global handler: HTML only for these two browser-facing endpoints, JSON path reused by delegation (same status table, same metrics). Proxy: *redirect, not pass-through*, so the Bref host needs no Keevo backend route and works from another VPS.

**Cost.** Legacy open: 1 PK read `public.invoice_legacy_links` + 1 tenant status read + 1 PK read `invoices` (presentation) + 1 read preferences (single row) + HMAC; no PDF render on this hop (render happens once on `/shared`). Previously: same reads + `findByLegacyToken` (unique index) + render. Error page: constant strings, O(1). No loop, no new index (all PK). End to end a legacy open is two requests (302, then the PDF on `/shared`, which re-reads invoice and preferences): constant, accepted with D1.

**Decisions.** D1 302 (not 301) at Keevo and at the proxy until 25.1 (rollback safe). D2 redirect expiry capped by Bref's expiry (no extension of access). D3 HTML only on `Accept: text/html`, JSON otherwise (V1 compatibility). D4 expired keeps its own page (410) as 20.7/Bref distinguished it. D5 Bref domain taken from the 31/10 analysis (`api.brefpoint.app`), to be confirmed by the user from Bref prod `.env` (`BACKEND_DOMAIN`). D6 MyCoolPay old URL is `/api/webhook/pay/mycoolpay/<hash>` (the prefix applies: only `webhook/whatsapp` and `webhook/revenuecat` are excluded in `main.ts`; api-bref docs say otherwise) — the runbook says to copy the URL shown in the MyCoolPay dashboard.

## Verification

**Commands:**
- `wt-pkgs.sh <worktree>/keevo/backend t2410 invoicing architecture` -- expected: all green (shared untouched).

**Manual checks (if no CLI):**
- Live on port 4650 / `keevo_dev_t2410`: an issued test invoice with an attached legacy token answers 302 then 200 PDF; unknown token with `Accept: text/html` → French 404 page.

## Mobile (React Native — dépôt AI/bref)

**Statut :** rien à faire côté app (pas de clé `m24-10`). API livrée dans ce dépôt (commit de la story 24-10).

**Ce qui est fait côté API :** l'ancien lien public Bref d'une facture importée, `GET /api/v1/invoices/public/{jeton}`, répond désormais **302** vers le lien signé Keevo (`/api/v1/invoices/shared?token=…`, le PDF) au lieu de renvoyer le PDF lui-même ; dans un navigateur, un lien inconnu, expiré ou trop sollicité affiche une page française. Ces adresses sont ouvertes par les clients finaux dans leur navigateur, jamais par l'app.

**À utiliser / à implémenter :** rien. Pour partager une facture, l'app continue d'appeler `POST /api/v1/invoices/{id}/public-link` (20.7) ; elle ne construit jamais d'adresse `…/invoices/public/{jeton}` (ancien chemin Bref, réservé aux liens déjà envoyés). Conventions : spec-16-11, protocole : spec-16-10.

## Dashboard super-admin (autre dépôt)

Rien : aucune API super-admin ajoutée ou modifiée (pas de clé `d24-10`).

## Auto Run Result

Status: done (implemented directly by the story agent, as the orchestrator's common rules require; review layers ran as subagents).

**Change.** An old Bref public link `GET /api/v1/invoices/public/{uuid}` now answers 302 to the signed Keevo link of the imported document (current link version, expiry capped by Bref's own, `no-store`, `no-referrer`); browsers get static French pages (404 « Lien introuvable », 410 « Lien expiré », 429, 503) on both public-link endpoints, API clients keep the JSON (with `Vary: Accept`, `no-store`); misses are counted per real client address behind Traefik and include retired tokens / deleted documents. Versioned Traefik template (302, query strings tolerated) with its test; cutover runbook §1 (proxy, controls, rollback) and §2 (exact webhook URLs old → new, secrets, tests, rollback).

**Files.**
- `keevo/backend/.../invoicing/invoice/adapter/in/rest/InvoiceLegacyLinkController.java` -- 302 redirect, `ClientIps`, misses for retired tokens.
- `.../adapter/in/rest/PublicLinkErrorPages.java` (new) -- scoped advice: French HTML for browsers, JSON delegated to `GlobalExceptionHandler`.
- `.../application/usecase/InvoicePublicLinkUseCase.java` -- `legacyRedirect`, shared private `signedLink(…, cap)`; `assertLegacyCurrent` removed.
- `.../domain/port/out/InvoicePresentationRepository.java`, `.../adapter/out/persistence/JdbcInvoicePresentationRepository.java` -- dead `findByLegacyToken` removed.
- Tests: `InvoicePublicLinkUseCaseTest` (+3), `PublicLinkErrorPagesTest` (new), `InvoiceTemplatesLinksFullContextTest` (legacy tests rewritten for 302, +2), `BrefLegacyProxyRuleTest` (new, parses the Traefik template).
- `keevo/ops/traefik/bref-legacy-links.yml` (new), `docs/RUNBOOK_BASCULE_BREF.md` (new).
- Planning: sprint-status (24-10 done, 24-13 note), epics 24.13 note.

**Review.** 28 findings (high 0, medium 3, low 22, false 3): 17 patched (3 medium: query string in the Traefik redirect, reported by three layers), 11 rejected with reasons (see triage log), 0 deferred, 0 routed. Follow-up review: not recommended (no high, the only medium root cause is one regex, now tested).

**Verification.** `wt-pkgs.sh … t2410 invoicing architecture`: invoicing 491 run / 0 fail, architecture 173 run / 0 fail / 1 skipped (endpoints table unchanged: no new endpoint). Live on `keevo_dev_t2410`, port 4650, `keevo.public-base-url=https://keevo.ad2s.cm`, WhatsApp noop, FCM off: registered test owner, issued test invoice, legacy token attached → `302 Location: https://keevo.ad2s.cm/api/v1/invoices/shared?token=…`, `no-store`, `no-referrer`; following it → 200 `application/pdf` showing `BRF-AB12CD34` and `FAC-2026-000001`; unknown token with `Accept: text/html` → 404 French page, without → JSON 404; expired → 410 « Lien expiré »; `/public/count` → 404 (not a link). Backend stopped (`fuser -k 4650/tcp`), database dropped.

**Residual risks.** The Bref host (`api.brefpoint.app`) and the owner of the Meta app that receives the WABA webhooks must be confirmed by the user before J0 (runbook §1.3, §2.2). The MyCoolPay URL in the merchant dashboard prevails over the code reading. Real-link checks (5 tokens) happen on the prod copy (24.12) and on J0. No PDF is served on the legacy path any more: a non-browser client must follow redirects.
