---
title: '19.9c — Meta templates for Keevo platform WhatsApp messages (catalogue, routing with safe fallback, submission checklist)'
type: 'feature'
created: '2026-11-10'
status: 'done'
baseline_revision: 'cf9402ba20aa80e6cbf096de59e3d01b2d71810c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-19-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-19-9b-canaux-bref-whatsapp-meta-web-push.md'
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Every Keevo platform WhatsApp message except sign-in codes (reports, day closure, stock and team alerts, sale follow-up, module / plan / account notices, sync silence, member added, deletion, the invoice link fallback) is free text through Wassender / Twilio. With the Meta Cloud API (preset `meta`, 19.9b) free text only works inside the 24-hour window, so these messages need approved Meta templates (UTILITY) — whose list and wording the user must validate before submitting them in Meta Business Manager.

**Approach:** (1) A template catalogue in code (name, category, French body with `{{n}}`, footer, one example per variable) and a mapping from every platform message kind to its template. (2) A new port method `WhatsAppSender.sendNotification(phone, PlatformWhatsAppMessage)` (kind + template parameters + the existing French text): the platform chain sends the Meta template only when that template is declared approved in config, otherwise — and on any certain Meta failure — the existing free text through Wassender → Twilio (behaviour unchanged by default). (3) A generated French deploy document (catalogue + submission checklist + mapping) kept in sync with the code by a test.

## Boundaries & Constraints

**Always:**
- Shared capability (callers in identity, admin, subscription, reporting, invoicing, messaging): enum `PlatformMessageKind` (each kind declares its parameter count) and record `PlatformWhatsAppMessage(kind, parameters, text)` validating the count; port default `sendNotification` = `sendReport(phone, text)` (test doubles, other implementations).
- Every current `WhatsAppSender.sendReport` caller switches to `sendNotification` with its kind; the free text stays byte-identical to today's (`text`). `sendReport` stays on the port (invoice fallback contract, V1 behaviour) but no platform caller keeps using it except through the default.
- Catalogue (messaging, `PlatformTemplateCatalog`): every kind maps to exactly one template; a template's variable count equals the kind's parameter count; names `^[a-z0-9_]{1,512}$`, language `fr`, category UTILITY (no MARKETING: campaigns are the owner's WABA, 21.6); body ≤ 1024 chars, no variable at the very start or end, no adjacent variables; footer ≤ 60; one example per variable. Bref's existing `bref_point_otp` (AUTHENTICATION, sendCode path unchanged) is listed in the document as already approved.
- Approval switch: `keevo.whatsapp.meta.approved-templates` (env `WHATSAPP_APPROVED_TEMPLATES`, comma separated, default empty). A kind goes to Meta only when the chain has a configured template provider AND its template name is in that list. Unknown names in the list → WARN at boot, ignored.
- Routing in `ChainWhatsAppSender.sendNotification`: approved → Meta template (language `fr`, body parameters sanitized: line breaks → ` · `, tabs / runs of spaces collapsed, each detail parameter truncated with `…` so the rendered body stays ≤ 1024 chars); DELIVERED → done; UNKNOWN (read timeout) → stop, no free text (no duplicate); FAILED / exception / not configured → free text via the text providers (same exception as `sendReport` when they all fail). Not approved → free text directly (exactly today's path).
- Meta request: `POST …/{phoneNumberId}/messages`, `type=template`, `template{name, language{code:"fr"}, components:[{type:body, parameters:[{type:text,text}…]}]}` (no component when the template has no variable). Logs: template name, HTTP status, masked phone; never the parameters or text.
- Document `keevo/backend/docs/whatsapp/modeles-meta-plateforme.md` generated from the catalogue (`-Dkeevo.whatsapp.templates.regenerate=true`), checked by a test like `docs/entitlements/endpoints.md`; it carries the « à valider par l'utilisateur » banner, the step-by-step Business Manager checklist, each template (name, category, language, body, footer, examples), the kind → template → sending code mapping, and the post-approval config.

**Never:** a real Meta call (tests use mocked HTTP; no admin submission endpoint — the user submits in Business Manager, decision D2); a change to sign-in / reset codes (19.9b), to the owner's WABA module `whatsappcloud` or campaign code (21.6, built in parallel); a change of any free-text wording; a new endpoint or migration; marking a template approved by default.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Not approved (default) | `sendNotification(DAILY_REPORT, …)`, list empty | free text Wassender→Twilio, identical to today | all fail → `WhatsAppDeliveryException` |
| Approved, Meta OK | `keevo_rapport_journalier` in list, preset `meta` | 1 Meta template call, no free text | — |
| Approved, Meta 4xx/5xx | | free text fallback | WARN template name + status |
| Approved, Meta read timeout | | nothing more sent | WARN « unknown outcome » |
| Approved, preset `failover` (no Meta provider) | | free text | — |
| Multi-line / long detail | report of 3 000 chars with newlines | one-line parameter, body ≤ 1024, ends with `…` | — |
| Fixed template | `ACCOUNT_SUSPENDED` | template without body component | — |
| Wrong parameter count | `PlatformWhatsAppMessage` built with 2 params for a 1-param kind | `IllegalArgumentException` at construction | programming error |

</intent-contract>

## Code Map

- `shared/capability/notification/WhatsAppSender.java` -- port; add `sendNotification` default.
- `messaging/whatsapp/application/ChainWhatsAppSender.java` -- chain (sendText/sendCode); add template routing; constructor gains the approved set.
- `messaging/whatsapp/domain/port/out/WhatsAppProvider.java` -- add `sendTemplate(phone, name, language, params)` default FAILED.
- `messaging/whatsapp/adapter/out/external/MetaWhatsAppProvider.java` -- `post`, `otpBody` style; add template body builder.
- `messaging/whatsapp/adapter/out/config/{MetaWhatsAppProperties,WhatsAppChainConfig}.java` -- add `approvedTemplates`, pass to chain, WARN unknown names.
- Callers (`sendReport` → `sendNotification`): `messaging/notification/application/listener/{DayClosureWhatsApp,ModuleExpiryNotification,PriceChangeNotification,ProductCreationNotification,SaleDraftValidatedNotification,StockAlertNotification,StockModificationNotification}Listener.java`; `admin/alerts/application/service/SyncSilenceAlertService.java:99`; `admin/subscription/application/service/TenantStatusWhatsAppNotificationListener.java:73`; `subscription/plan/application/service/{PlanWhatsAppNotificationListener:105,PlanExpiryReminderService:153}.java`; `identity/employee/application/service/MemberAddedNotifier.java:93`; `identity/auth/application/service/{AccountDeletionService:170,TenantDeletionService:371}.java`; `identity/onboarding/application/service/SendTestReportService.java:77`; `reporting/report/application/service/{AbstractReportGenerator:121,ReportHistoryService:99,ReportDeliveryRetryService:97,MultiStoreSummaryService:108}.java` (kind from `ReportType`); `invoicing/invoice/application/service/channel/WhatsAppInvoiceChannel.java:101`.
- Tests mocking `WhatsAppSender` and verifying `sendReport` (33 files) -- add `lenient().doCallRealMethod()` on `sendNotification` so the text contract stays verified; add kind assertions where the kind is chosen.
- `test/.../architecture/EndpointEntitlementCoverageTest.java:42-70` -- regenerate-property pattern for the document test.
- `resources/application.yml:278-281`, `keevo/backend/.env.example:103-105` -- Meta keys.

## Tasks & Acceptance

**Execution:**
- `shared/capability/notification/PlatformMessageKind.java`, `PlatformWhatsAppMessage.java`, `WhatsAppSender.java` -- kinds, message record, default method.
- `messaging/whatsapp/domain/model/{PlatformTemplate,PlatformTemplateCatalog,TemplateParameters}.java` -- catalogue (Factory/Registry), sanitizer and budget.
- `WhatsAppProvider`, `MetaWhatsAppProvider`, `ChainWhatsAppSender`, `MetaWhatsAppProperties`, `WhatsAppChainConfig` -- routing per the rules.
- The 20 caller files above -- one `sendNotification` per former `sendReport`; reporting gets `ReportWhatsAppMessages.of(report)`.
- `application.yml`, `.env.example`, `docs/ENV_REFERENCE.md` if it lists WhatsApp keys -- new key.
- `keevo/backend/docs/whatsapp/modeles-meta-plateforme.md` + `PlatformTemplateCatalogDocTest` -- generated deploy document.
- Tests: `PlatformTemplateCatalogTest` (every kind mapped, Meta rules, examples, counts), `TemplateParametersTest`, `ChainWhatsAppSenderTest` (matrix rows), `MetaWhatsAppProviderTest` (template body), `PlatformWhatsAppMessageTest`; existing caller tests adapted.

**Acceptance Criteria:**
- Given default config, when any platform message is sent, then the free text and provider calls are identical to before (caller tests green unchanged in their text assertions).
- Given `WHATSAPP_APPROVED_TEMPLATES=keevo_alerte_stock` and preset `meta`, when a stock alert goes out, then Meta receives template `keevo_alerte_stock` (fr) with the alert as parameter, and other kinds still go as free text.
- Given the catalogue changes, when the document is not regenerated, then `PlatformTemplateCatalogDocTest` fails.

## Spec Change Log

## Review Triage Log

### 2026-11-10 — Review pass
- verdicts: 35 findings — high 0, medium 6, low 18, false 8, maybe-false 3 (intent auditor: descriptive, implements reading « catalogue + doc + runtime routing », no defect raised beyond the wiring gap below)
- findings:
  - `[low]` `[patch]` (blind 1) Meta 200 only means « accepted »; later delivery failures are not re-sent as free text — same contract as every existing provider (Wassender/Twilio 200); deploy document now states it and tells the operator to remove a paused template from the list.
  - `[false]` `[reject]` (blind 2) UNKNOWN outcome now marks reports SENT — the free-text path already returned normally on a read timeout (`sendText` UNKNOWN, 19.9b); no behaviour change for reports.
  - `[low]` `[reject]` (blind 3, patterns 4) no circuit breaker for a template Meta paused — the approved list is set by hand; one failed call then fallback per message; a breaker adds state for a rare operator error (documented: remove it from the list).
  - `[medium]` `[patch]` (blind 4, edge 3, edge 9) `fit` could cut the link / reference of `keevo_document_lien` — only single-variable « detail » templates are cut now; an oversized structured template returns empty and the message goes as free text (`structuredNeverCut`, `notificationStructuredTooLongIsFreeText`).
  - `[low]` `[patch]` (blind 5) reactivation template wording differed from the free text — aligned to « Votre compte Keevo est réactivé. », test `isEqualTo`.
  - `[medium]` `[patch]` (blind 6) promotional examples risk Meta recategorising to MARKETING — examples cleaned (« Profitez… », « Contactez-nous pour renouveler »), test `noPromotionalExamples`, checklist warns about promotional wording and generic detail templates.
  - `[false]` `[reject]` (blind 7) spec / sprint-status / Mobile-Dashboard sections not updated — done at finalisation of this run; the spec Code Map line on `MetaWhatsAppProperties` is a spec edit (rejected by rule).
  - `[medium]` `[patch]` (blind 8, VG 1) bean test did not prove the approved list reaches the chain — `senderBeanApprovedTemplates` now builds the bean with preset `meta` and a recording Meta provider and asserts `keevo_alerte_stock` is sent.
  - `[low]` `[patch]` (blind 9, edge 5, edge 6) `render` with `{{0}}` → index -1; `variableCount` Javadoc said « distinct » — guard `index >= 0`, Javadoc corrected (numbering 1..n checked by the catalogue test).
  - `[low]` `[patch]` (blind 10, edge 2, edge 4) minimum-cut branch could split an emoji / exit above 1024 — branch removed with the single-variable rule; the only cut keeps surrogate pairs.
  - `[low]` `[reject]` (blind 11) blank detail sent as « - » in an approved template — reports always have content (NOT NULL in practice); previously an empty free text would have been sent too.
  - `[false]` `[reject]` (blind 12) doc test depends on the working directory — same relative-path convention as `EndpointEntitlementCoverageTest` (Maven runs from the module dir).
  - `[low]` `[reject]` (blind 13) fully-qualified names in adapted tests — mechanical one-liners, readable enough; no defect.
  - `[low]` `[patch]` (blind 14, edge 12) `bref_point_otp` accepted in the approved list without effect — validated against kind templates only (`kindTemplateByName`), test `otpNotApprovable`.
  - `[medium]` `[reject]` (edge 1) preset meta with only Meta keys: `isConfigured()` false so some callers skip — inherited 19.9b rule (boot WARN « free-text WhatsApp off »); the checklist (step 9) requires a text provider until every template is approved; a template-aware availability check would change 6 callers' gates for a configuration prod does not use.
  - `[low]` `[reject]` (edge 7) `of(kind, text, (String[]) null)` NPE — programming error, never written by a caller.
  - `[low]` `[reject]` (edge 8) « free text fallback » log when no text provider is configured — the following exception names the providers; cosmetic.
  - `[maybe-false]` `[reject]` (edge 10) null report content now sent as empty text — content column is filled by every generator; would only be low.
  - `[false]` `[reject]` (edge 11) `{{2}} {{3}}` adjacent — separated by a space exactly like Bref's approved `bref_facture` (`{{2}} {{3}}`).
  - `[maybe-false]` `[reject]` (edge 12b) approved set read from `Environment` not the properties bean — design choice, tested; no harm named.
  - `[medium]` `[patch]` (VG 2) `ReportWhatsAppMessages.kind` untested — `ReportWhatsAppMessagesTest` (3 types + null) and a WEEKLY_REPORT assertion in `WeeklyReportGeneratorTest`.
  - `[medium]` `[patch]` (VG 3) most callers' kind unverified (fixed templates carry their own wording) — kind assertions added for ACCOUNT_REACTIVATED, PLAN_CHANGE, PLAN_REMINDER, WEEKLY_REPORT (plus existing SUSPENDED, MEMBER_ADDED, SYNC_SILENCE, DELETION_SCHEDULED, DOCUMENT_LINK, DAY_CLOSURE); the remaining detail kinds share free text = parameter, a swap only changes the frame.
  - `[low]` `[patch]` (VG 4) structured parameter order only partly checked — full list asserted for MEMBER_ADDED and DOCUMENT_LINK, quote case gives « devis » (`quoteParameters`).
  - `[low]` `[patch]` (patterns 1) flatten regex could backtrack — single linear `[\s ]+` pass choosing « · » when the run holds a line break.
  - `[false]` `[reject]` (patterns 2) O(k²) fit loop — loop removed; k ≤ 6 anyway.
  - `[low]` `[reject]` (patterns 3) `fixedLength()` regex per send — a few hundred characters per message; caching adds fields for nothing measurable.
  - `[false]` `[reject]` (patterns outside) `ReportDeliveryRetryService` per-report save — pre-existing, not touched by this story.
  - `[false]` `[reject]` (intent) no admin submission through the Graph port — the intent offers it as an alternative (« or »); doc/checklist chosen (decision D2).
  - `[false]` `[reject]` (intent) no MARKETING template — no platform message is promotional; campaigns are the owner's WABA (21.6).
  - `[maybe-false]` `[reject]` (intent) caller tests only exercise the default path — routing tested on the chain, wiring on the bean, kinds on callers; an end-to-end test adds a context for the same assertions.
  - `[low]` `[reject]` (edge 9 claim) structured values could be cut contrary to the spec — superseded by the patch above (structured never cut).
  - `[low]` `[reject]` (blind 9b) record does not validate Meta rules at construction — rules checked by `PlatformTemplateCatalogTest` over the whole catalogue, the only producer.
  - `[low]` `[reject]` (patterns 3b) `variableCount()` recomputed per call — only in tests and doc generation.
  - `[low]` `[reject]` (VG note) no test that the « preset without meta » WARN is logged — log-only branch.
  - `[low]` `[reject]` (blind 10b) UTF-16 length vs Meta characters — safe direction (cuts earlier), now stated in the Javadoc.

## Design Notes

**Patterns.** Registry / Factory (`PlatformTemplateCatalog`: one immutable map kind → template, built once) + the existing Chain of Responsibility (19.9b) extended with a template-first branch guarded by a feature switch (the approved set) — Strategy per provider (`sendTemplate` only Meta). Parameter object `PlatformWhatsAppMessage` keeps callers ignorant of templates (they name a kind, not a Meta name), so renaming a template after Meta review touches the catalogue only. Most templates take one « detail » variable carrying today's French text (flattened), so no caller logic changes; structured variables only where the data is already at hand (member added: company, role; deletion date; sync silence days; invoice link fields).

**Complexity (FUS-NFR11).** Per message: O(1) catalogue lookup (EnumMap), O(len) sanitization, ≤ 1 Meta call + ≤ 2 fallback calls (unchanged bound). No query, no loop added; callers' loops untouched.

## Verification

**Commands:**
- `wt-pkgs.sh <wt>/keevo/backend t199c messaging shared identity admin subscription reporting invoicing architecture` -- expected: green.

**Manual checks:**
- Local backend on 4599 with `keevo_dev_t199c` (WhatsApp noop): boot OK, an OTP still 200; the generated document lists every template with « à valider par l'utilisateur ».

## Décisions (prises par défaut, à confirmer par l'utilisateur)

- **D1 — Libellés « à valider par l'utilisateur ».** Les 17 modèles proposés (section 3 de `keevo/backend/docs/whatsapp/modeles-meta-plateforme.md`) ne partent jamais tant que leur nom n'est pas listé dans `WHATSAPP_APPROVED_TEMPLATES` (vide par défaut) : aucun changement de comportement en production avant validation et approbation Meta.
- **D2 — Soumission manuelle dans Meta Business Manager** (check-list générée), pas d'endpoint super-admin de soumission : le port Graph existant (21.5) est celui des WABA des entreprises, le numéro plateforme n'a pas de compte WABA stocké ; aucun appel Meta réel, aucune nouvelle surface d'API.
- **D3 — Tout en UTILITY**, aucun MARKETING : aucun message plateforme n'est promotionnel (les campagnes relèvent du WABA de l'entreprise, 21.6). `bref_point_otp` (AUTHENTICATION) reste celui de Bref, déjà approuvé.
- **D4 — Modèles « détail »** : la plupart encadrent une variable qui porte le texte français actuel (aplati sur une ligne, coupé par « … » au-delà de 1 024 caractères) ; variables structurées seulement là où les données existent déjà (entreprise et rôle, date de suppression, nombre de jours, champs du lien de facture). Un modèle structuré n'est jamais coupé (texte libre s'il est trop long).
- **D5 — Regroupements** : produits créés, stock modifié et prix modifiés partagent `keevo_activite_equipe` ; vente en attente / validée / complétée partagent `keevo_suivi_vente` ; le rapport test de l'inscription utilise `keevo_rapport_journalier`.
- **D6 — Repli** : modèle non approuvé, refusé (HTTP) ou trop long → texte libre Wassender puis Twilio comme avant ; délai dépassé → rien de plus (pas de doublon) ; un message accepté puis non remis par Meta n'est pas renvoyé (même contrat que les autres fournisseurs).
- **D7 — Contrat d'export régénéré** (`keevo/contracts/export-datasets.json`) : dérive préexistante laissée par la 21.5 (libellés d'audit WhatsApp Business), qui faisait échouer `ExportContractFileTest` dans le paquet `reporting` ; commit séparé.

## Mobile (React Native — dépôt AI/bref)

**Statut :** aucun travail mobile. Le changement ne touche que la façon dont le backend envoie ses messages WhatsApp (aucun endpoint, aucune réponse modifiée). Pas de clé `m19-9c`.

## Dashboard super-admin (autre dépôt)

**Statut :** aucun travail dashboard (pas d'API super-admin ajoutée ni modifiée ; la soumission des modèles se fait dans Meta Business Manager). Pas de clé `d19-9c`.

## Auto Run Result

Status: done

**Summary.** Every Keevo platform WhatsApp message (reports, closure, stock / team / sale alerts, module, plan and account notices, sync silence, member added, deletion, invoice-link fallback) now goes through `WhatsAppSender.sendNotification(phone, PlatformWhatsAppMessage)` with its `PlatformMessageKind`. `PlatformTemplateCatalog` holds 17 UTILITY templates in French (+ Bref's existing `bref_point_otp`, AUTHENTICATION), each kind mapped to one template. `ChainWhatsAppSender` sends the Meta template only when its name is in `keevo.whatsapp.meta.approved-templates` (`WHATSAPP_APPROVED_TEMPLATES`, empty by default) and Meta is configured; refusal, missing keys or a structured template too long → today's free text (Wassender → Twilio); read timeout → nothing more. Detail parameters are flattened and cut at Meta's 1 024 limit; structured ones are never cut. The French deploy document `keevo/backend/docs/whatsapp/modeles-meta-plateforme.md` (banner « À VALIDER PAR L'UTILISATEUR », Business Manager checklist, every template with examples, message → template mapping) is generated from the catalogue and pinned by `PlatformTemplateCatalogDocTest`. No migration (V75 unused), no endpoint.

**Files.** New: `shared/capability/notification/{PlatformMessageKind,PlatformWhatsAppMessage}`, `messaging/whatsapp/domain/model/{PlatformTemplate,PlatformTemplateCatalog,TemplateParameters}`, `reporting/report/application/service/ReportWhatsAppMessages`, `docs/whatsapp/modeles-meta-plateforme.md`, tests (`PlatformTemplateCatalogTest`, `PlatformTemplateCatalogDocTest`, `TemplateParametersTest`, `PlatformWhatsAppMessageTest`, `ReportWhatsAppMessagesTest`, helper `WhatsAppSenderMocks`). Changed: `WhatsAppSender` (default `sendNotification`), `WhatsAppProvider.sendTemplate`, `MetaWhatsAppProvider` (template body), `ChainWhatsAppSender`, `WhatsAppChainConfig` (approved list, WARNs), 20 caller files (one `sendNotification` per former `sendReport`, free text unchanged), `application.yml`, `.env.example`, caller tests (forwarding helper + kind / parameter assertions). Separate commit: `keevo/contracts/export-datasets.json` regenerated (pre-existing drift from 21.5).

**Review.** 35 findings (5 layers): 0 high, 6 medium, 18 low, 8 false, 3 maybe-false. Patched 13 entries (5 medium: structured templates never cut, promotional examples + checklist warnings, bean wiring test, report kind tests, caller kind assertions; plus full parameter order checks, `render` guard, emoji-safe cut, OTP not approvable, reactivation wording aligned, linear flatten, delivery-semantics note). Deferred: none. Rejected: 22, reasons in the triage log.

**Follow-up review: recommended (true)** — 5 medium patches on a first pass; unverified risk: the template wire format has never met the real Meta Graph API, and Meta's reaction to « detail » templates (one variable framing most of the text) is unknown until submission.

**Verification.** Package by package (`wt-pkgs.sh`, tag t199c): messaging 404, reporting 463, subscription 153, invoicing 470, admin 690, identity 866, shared 1 358, architecture 146 — 4 550 tests, 0 failure. Live: worktree backend on port 4599 with `keevo_dev_t199c` (WhatsApp noop, FCM off, `WHATSAPP_APPROVED_TEMPLATES="keevo_alerte_stock, keevo_typo_inconnu, bref_point_otp"`): boot OK, unknown name and OTP name dropped with WARN, `approved platform templates: [keevo_alerte_stock]`, « preset has no Meta provider » WARN, `POST /api/v1/auth/otp/request` 200, `GET /api/v1/web-push/vapid-public-key` 200; backend stopped, database dropped.

**Residual risks.** Meta may reject or recategorise generic detail templates (wording is the user's decision); a message accepted by Meta then undelivered is not re-sent as free text (same contract as the other providers); with preset `meta` and no Wassender/Twilio keys, callers gated on `isConfigured()` still skip WhatsApp (19.9b rule) — keep a text provider until every template is approved.
