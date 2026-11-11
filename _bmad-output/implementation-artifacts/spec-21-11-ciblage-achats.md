---
title: '21-11 — Purchase-behaviour targeting of client WhatsApp campaigns (inactive, recent buyers, total spent, number of purchases)'
type: 'feature'
created: '2026-11-11'
status: 'done'
baseline_revision: 'c47b3b96ee003f6fb8d160ac0fa46cc07c8cf5ab'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-21-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-21-6-campagnes.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Bref owners target their client campaigns on purchase behaviour (`inactiveForDays`, `purchasedWithinDays`, `totalSpentMin/Max`, `totalInvoicesMin/Max`, Bref `customer-campaign.service.ts` `recipients`); Keevo 21.6 targets only by store and consent (FUS-FR28, item routed by the 21.6 review).

**Approach:** Add the six optional Bref criteria to the preview body and to the campaign (create / PATCH / response, persisted on the campaign, applied when the audience is counted and when it is frozen at start). The purchase figures come from the single revenue source (`RevenueQueryPort`: COMPLETED POS sales ∪ issued/paid invoices, a sale bridged to an invoice counted once, credit notes deducted from the total) — the same figures as the clients export — aggregated per phone number inside the one audience query of crm.

## Boundaries & Constraints

**Always:**
- Additive only: every criterion is optional; a preview / campaign without any criterion runs exactly today's queries (same SQL, same results). New response fields only; V1 / 21.6 clients unaffected.
- Purchase of a number = every client record with that `phone_normalized` (any store, archived included) → its revenue documents. Count = sales + counted invoices (`SUM(doc_count)`), total = net of credit notes (`SUM(total_amount)`), last purchase = `MAX(COALESCE(recorded_at, created_at)) FILTER (doc_count > 0)` (export definition, Story 20.14). No purchase = count 0, total 0, last null.
- Campaign targeting stores (`storeIds` non-empty): only documents of those stores count (store-scoped relations); no store targeted: every document of the company.
- Semantics (Bref): `inactiveForDays N` → no purchase since now − N days (never-buyers included); `purchasedWithinDays N` → last purchase ≥ now − N days; min/max bounds inclusive. Cut-offs computed from the service clock at the moment of the count (preview, save, start).
- One SQL statement per count / audience load (eligible clients in phone order, a `LEFT JOIN LATERAL` aggregate per number pushed into each revenue branch by `client_id` index), no per-client query; consent re-check per batch unchanged (criteria are not re-applied at send: the audience is frozen at start, as Bref).
- Validation 400 with French messages: days integer 0..3650 (0 = criterion not set, Bref), amounts 0..1 000 000 000 000 FCFA (decimals accepted: min rounded up, max rounded down), counts integer 0..10 000 000, min > max refused, `purchasedWithinDays ≤ inactiveForDays` refused (no client can match).
- Response: the six fields (null when unset) + `purchaseCriteriaLabels` (French sentences, e.g. « Sans achat depuis 30 jours »). No technical data.

**Never:** No new endpoint; no denormalized counters on clients (the revenue source stays the only truth); no public Flyway migration (V80 unused); no change to platform (super-admin) campaigns; no re-evaluation of criteria per batch.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Inactive | consenting A bought 5 days ago, B 40 days ago, C never; `{inactiveForDays:30}` | preview 2 (B, C); start freezes B, C | — |
| Recent | same; `{purchasedWithinDays:30}` | 1 (A) | — |
| Amount / count | A spent 15 000 in 2 sales, B 3 000 in 1 invoice; `{totalSpentMin:10000}` / `{totalInvoicesMin:2}` | 1 (A) / 1 (A) | — |
| Credit note | invoice 10 000 then credit note 4 000 | total 6 000, count 1 | — |
| Cancelled sale / quote / draft | not COMPLETED, quote, draft | not a purchase | — |
| Same number, two stores | record in store S1 bought yesterday, record in S2 never; no store targeted, `inactiveForDays:30` | not targeted (number active) | — |
| Store targeted | campaign on S2 only; same data | targeted (no purchase in S2) | — |
| No criterion | `{}` / `{storeIds}` | today's result | — |
| Invalid | `inactiveForDays: 4000`, `totalSpentMin: 5000, totalSpentMax: 1000`, `purchasedWithinDays:10, inactiveForDays:30`, `"abc"` | — | 400 French message |
| PATCH | `{"inactiveForDays": null}` | criterion removed, others kept; absent fields unchanged | — |

</intent-contract>

## Code Map

- Bref (read-only): `api-bref/src/customer-campaign/customer-campaign.service.ts:124-171` (criteria SQL), `dto/customer-campaign.dto.ts:25-70` (bounds), `customer/.../customer.repository.ts` (`totalInvoices` / `totalSpent` / `lastPurchaseAt` maintained from sale invoices, decremented on cancellation).
- `shared/capability/revenue/RevenueQueryPort.java` -- single revenue source (`current()`, `storeScoped()`, `documents` columns `client_id`, `doc_count`, `total_amount`, `recorded_at`, `store_id`); resolve inside a transaction (tenant search_path).
- `crm/contact/adapter/out/export/ClientsExportDatasetProvider.java:94-99` -- golden example of the per-client aggregate (same definitions to reuse).
- `crm/api/ClientQueryPort.java` -- add `marketingAudience(schema, storeIds, PurchaseFilter, limit)` / `countMarketingAudience(schema, storeIds, PurchaseFilter, cap)`; new `crm/api/PurchaseFilter.java` (absolute cut-offs + bounds, `isEmpty()`).
- `crm/contact/adapter/in/api/ClientApiAdapter.java:83-94` -- empty filter → existing repository methods; otherwise the new JDBC query.
- `crm/contact/adapter/out/persistence/ClientSpringRepository.java:110-125` -- today's audience SQL (eligibility rule, DISTINCT ON phone, `idx_clients_marketing_audience`) to mirror.
- New `crm/contact/adapter/out/persistence/JdbcMarketingAudienceQuery.java` -- the filtered audience / count (JdbcTemplate + RevenueQueryPort, `@Transactional(readOnly = true)` so the source resolves in the tenant schema; no schema interpolation).
- Indexes already present: `idx_clients_marketing_audience`, `idx_clients_phone_normalized` (crm V4), `idx_sales_client_id` (pos V1), `idx_invoices_client` (invoicing V16), `idx_credit_notes_invoice` (invoicing V4) — no new index.
- `whatsappcloud/campaign/domain/model/Campaign.java` (+ `PurchaseCriteria criteria`), new `domain/model/PurchaseCriteria.java` (six fields, validation, merge, French labels, `toFilter(now)`).
- `whatsappcloud/campaign/application/CampaignService.java` -- `Draft`/`Presence` gain criteria; `preview`, `validated` (targetedCount), `start` pass the filter.
- `whatsappcloud/campaign/adapter/in/rest/WhatsAppCampaignController.java` -- parse the six fields (preview, create, PATCH presence per field), `CampaignView` + six fields + `purchaseCriteriaLabels`.
- `whatsappcloud/campaign/adapter/out/persistence/JdbcCampaignRepository.java` -- columns in insert / updateEditable / select.
- New `resources/db/modules/whatsapp-cloud/V3__campaign_purchase_criteria.sql` -- six nullable columns with CHECKs.
- `keevo/contracts/whatsapp-cloud.json` -- preview / create / PATCH / response shapes.

## Tasks & Acceptance

**Execution:**
- `db/modules/whatsapp-cloud/V3__campaign_purchase_criteria.sql` -- add `inactive_for_days INT`, `purchased_within_days INT`, `total_spent_min BIGINT`, `total_spent_max BIGINT`, `total_invoices_min INT`, `total_invoices_max INT` (nullable, range CHECKs) -- persist criteria.
- `crm/api/PurchaseFilter.java`, `ClientQueryPort`, `ClientApiAdapter`, `JdbcMarketingAudienceQuery` -- filtered audience + count, one statement each -- set-based purchase targeting owned by crm over the shared revenue source.
- `whatsappcloud/campaign/domain/model/PurchaseCriteria.java`, `Campaign`, `CampaignService`, `JdbcCampaignRepository`, `WhatsAppCampaignController` -- criteria end to end -- API.
- `src/test/.../whatsappcloud/campaign/domain/model/PurchaseCriteriaTest.java` -- validation, rounding, merge, labels, cut-offs.
- `src/test/.../whatsappcloud/WhatsAppCampaignFullContextTest.java` -- purchase targeting test over the matrix (sales, invoice + credit note, cancelled sale, quote, two stores, store targeting, PATCH clear, frozen audience at send, 400s, no-criterion unchanged).
- `keevo/contracts/whatsapp-cloud.json`, `docs/entitlements/endpoints.md` (regenerate) -- contract.

**Acceptance Criteria:**
- Given consenting clients with and without a purchase in the last 30 days, when the owner targets « inactifs depuis 30 jours », then only the latter are counted and receive the campaign, with one audience query.
- Given a campaign created without any criterion, when it is previewed, saved and sent, then the counts and recipients are those of 21.6.
- Given a campaign with criteria, when it is read, then the six fields and the French labels are returned.

## Spec Change Log

## Review Triage Log

### 2026-11-11 — Review pass
- verdicts: 29 findings — high 0, medium 5, low 19, false 5, maybe-false 0
- findings:
  - `[low]` `[patch]` (blind) days 400 message said « entre 1 et 3 650 » while 0 is accepted — message now « entre 0 et 3 650 (0 : aucun critère) ».
  - `[medium]` `[patch]` (blind) decimal bounds in order but holding no whole franc (min 0,3 / max 0,7) refused as « minimum above maximum » — explicit 400 « Aucun total d'achats en francs entiers… » (rounding kept: min up, max down, as the contract says); test added.
  - `[low]` `[patch]` (blind) amount above 1e12 reached `problem()` with « doit être positif » — message gives the full range.
  - `[low]` `[patch]` (blind) a minimum of 0 counted as a criterion (filtered path, label « Au moins 0 achat ») — `totalSpentMin` / `totalInvoicesMin` of 0 = not set (like 0 days); test « 0 minimums = not set ».
  - `[low]` `[patch]` (blind) old 3-argument crm port methods left without callers — removed from `ClientQueryPort` / `ClientApiAdapter` (the filter overloads take `null` / empty = 21.6 query).
  - `[medium]` `[patch]` (blind) eligibility rule copied in a third place — one constant `ClientSpringRepository.MARKETING_ELIGIBLE` used by both 21.6 queries and `JdbcMarketingAudienceQuery`.
  - `[low]` `[patch]` (blind) cost comment of `CampaignService` outdated for filtered audiences — rewritten with the measured cost (credit-note index exists: `idx_credit_notes_invoice`, invoicing V4).
  - `[low]` `[reject]` (blind) DB CHECK lacks cross-field rules — the only writer is `CampaignService` (validated on create / update); adding a cross-field CHECK guards no reachable state.
  - `[low]` `[reject]` (blind) bridged sale, PAID invoice, store-scoped credit note, archived record not tested here — those semantics belong to the revenue source and are covered by `RevenueSourceAcceptancePostgresIntegrationTest`; never-buyers under a max are asserted (`totalInvoicesMax 0` → C).
  - `[false]` `[reject]` (blind) test leaves data that breaks other tests — every test creates its own tenant schema in `setUp` (`newTenant()`).
  - `[low]` `[reject]` (blind) fully qualified types / constructor assignment order in `ClientApiAdapter` — cosmetic, file style already uses FQNs.
  - `[low]` `[reject]` (blind) adapter unit test does not assert the filtered return value — the delegation is verified; the full-context test asserts the results.
  - `[low]` `[patch]` (blind) days are 24-hour periods (UTC instant) and a scheduled campaign is evaluated at send — stated in the contract notes and the service javadoc.
  - `[medium]` `[patch]` (verification gap) no test where a recent credit note sits on an old invoice — client G added (invoice 50 days ago, credit note yesterday): counted inactive, not recent.
  - `[false]` `[reject]` (intent) per-number aggregation instead of Bref's per-record — the intent asks for the most faithful source with a documented choice; decision D2 (a buyer of yesterday in one store is not « inactif »).
  - `[false]` `[reject]` (intent) last purchase computed at read instead of a maintained column — same definition as the clients export (20.14), no drift possible.
  - `[false]` `[reject]` (intent) aggregates read from crm instead of a reporting port — the documents relation is the reporting-owned shared capability `RevenueQueryPort`; crm only joins its own clients to it (no new module edge).
  - `[false]` `[reject]` (intent) no plan evidence — EXPLAIN ANALYZE run live on 20 000 clients / 60 000 sales: parameterised `idx_sales_client_id`, `idx_invoices_client`, `idx_credit_notes_invoice` probes, 82 ms (Design Notes).
  - `[low]` `[patch]` (intent) contract 0..3650 vs domain 1..3650 — same as the first row (0 = not set, message and contract aligned).
  - `[low]` `[reject]` (intent) scheduled start has no dedicated purchase test — same `start()` path as POST /send, which is tested.
  - `[low]` `[reject]` (NFR11) the lateral aggregate cannot stop early on a selective filter — bounded by the company's eligible numbers, measured 82 ms for 5 400; a grouped plan scans all documents instead (Design Notes).
  - `[low]` `[reject]` (NFR11) two-level lateral could be one level — verified plan already probes indexes per client; no measurable gain.
  - `[low]` `[reject]` (NFR11) each PATCH recounts the audience — pre-existing 21.6 behaviour, and the count is time-relative (a cached value would be wrong).
  - `[medium]` `[patch]` (edge) `1e400` amount → `DoubleNode.decimalValue()` NumberFormatException → 500 — non-finite numbers refused with the French 400; test added.
  - `[medium]` `[patch]` (edge) 0,3 / 0,7 misleading « minimum above maximum » — same fix as the blind rounding row.
  - `[low]` `[patch]` (edge) label « Au moins 0 achat » — same fix as the zero-minimum row.
  - `[low]` `[patch]` (edge) `totalSpentMin 0` silently excluded negative nets — 0 minimum = not set.
  - `[low]` `[patch]` (edge) too-large amount message — same fix as the blind message row.
  - `[low]` `[reject]` (edge, claim) spec says PurchaseCriteriaTest covers rounding / cut-offs — fix would only edit the spec; cut-offs are tested in `CampaignRulesTest`, rounding in the full-context test.

## Design Notes

- **Patterns.** Specification pattern: `PurchaseCriteria` (whatsappcloud domain, relative days) is translated to an absolute `PurchaseFilter` (crm API) evaluated in SQL — one place decides eligibility, the same predicate serves count and load. Hexagonal: whatsappcloud never reads sales / invoices; crm reads purchases only through the shared `RevenueQueryPort` (no module edge to pos / invoicing / reporting). Null-object: empty filter = the untouched 21.6 path.
- **Source choice.** Bref's `totalInvoices/totalSpent/lastPurchaseAt` are maintained from every non-cancelled sale document (direct sale or validated quote), not from payment status. Keevo's equivalent is the revenue source (sales COMPLETED + issued / paid invoices not bridged from a sale, credit notes deducted): no double count of a POS sale bridged to an invoice, the same numbers as the clients export. Paid-only figures would diverge from Bref (an unpaid issued invoice is a purchase).
- **SQL shape (filtered).** `SELECT … FROM (DISTINCT ON (phone) eligible clients [stores]) a LEFT JOIN LATERAL (SELECT SUM(d.n), SUM(d.spent), MAX(d.last) FROM clients pc CROSS JOIN LATERAL (SELECT … FROM <documents> r WHERE r.client_id = pc.id [AND r.store_id = ANY(?)]) d WHERE pc.phone_normalized = a.phone_normalized) p ON TRUE WHERE <criteria> ORDER BY a.phone_normalized LIMIT ?`; count = `count(*)` over the same with `LIMIT cap`. The inner `r.client_id = pc.id` is an outer reference pushed into every `UNION ALL` branch (export pattern).
- **Costs.** One statement; E = eligible numbers scanned in phone order (stops at `limit` matches), each: 1 probe `idx_clients_phone_normalized` (k records) + per record 1 probe per revenue branch (`idx_sales_client_id`, `idx_invoices_client`, credit notes via `idx_credit_notes_invoice`) → O(E·k·(log N + d)) with d = documents of the client; no full scan of sales. Plus 1 cached catalogue lookup of the revenue source. Memory: rows streamed by the driver, ≤ 5 001 loaded (unchanged). Chosen over a `GROUP BY` of all documents (O(all documents) per preview) because the audience is a subset of clients with consent. Measured live (EXPLAIN ANALYZE, 20 000 clients, 6 670 eligible, 60 000 sales): parameterised `idx_sales_client_id` / `idx_invoices_client` / `idx_credit_notes_invoice` probes under the Append, 82 ms for the capped count.
- **Shared rule.** The eligibility predicate is one constant (`ClientSpringRepository.MARKETING_ELIGIBLE`) used by the 21.6 queries and the filtered query.

## Décisions (prises par défaut, à valider par l'utilisateur)

- D1 — Source des achats : la source unique du chiffre d'affaires (ventes POS terminées + factures émises ou payées non issues d'une vente, avoirs déduits du total), comme l'export clients ; statut de paiement ignoré (comme Bref).
- D2 — Agrégation par numéro (toutes les fiches du même numéro), pas par fiche : un client qui a acheté hier dans une boutique n'est pas « inactif » parce qu'une autre fiche du même numéro n'a rien acheté.
- D3 — Campagne ciblant des boutiques : seuls les achats faits dans ces boutiques comptent (comme Bref, statistiques par boutique) ; documents anciens sans boutique ignorés dans ce cas.
- D4 — Noms de champs de Bref (`totalInvoicesMin/Max` = nombre d'achats) à plat dans le corps, comme `storeIds` en 21.6 ; 0 jour = critère absent (Bref).
- D5 — Refus 400 si min > max ou si `purchasedWithinDays ≤ inactiveForDays` (aucun client possible) — Bref acceptait silencieusement.
- D7 — Un minimum à 0 (montant ou nombre d'achats) vaut « pas de critère », comme 0 jour ; des bornes de montant décimales sans franc entier entre elles (0,3 … 0,7) sont refusées (400) ; les jours sont des périodes de 24 h comptées depuis le moment du calcul.
- D6 — Critères appliqués à l'aperçu, à l'enregistrement (compte ciblé) et au lancement (audience figée) ; pas réévalués à chaque lot (seul l'accord l'est).

## Verification

**Commands:**
- `scratchpad/wt-pkgs.sh <worktree>/keevo/backend t2111 whatsappcloud crm architecture` -- expected: green (whatsappcloud 65, crm 241, architecture 171 + 1 skipped).
- Live: `keevo_dev_t2111` on port 4621 (dev profile, WhatsApp noop, FCM off, fake Graph): preview with / without criteria after POS sales and an invoice, create / PATCH / send, check recipients.

## Mobile (React Native — dépôt AI/bref)

**Statut :** API livrée (commit `56fbc9f3`, clé `m21-11-ciblage-achats` dans `sprint-status.yaml`). Contrat : `keevo/contracts/whatsapp-cloud.json` (entrées 21.11). Conventions d'erreur et d'affichage : spec-16-11 ; synchro : rien (campagnes en ligne uniquement, spec-16-10). S'ajoute à l'écran « Campagnes WhatsApp » de `m21-6-campagnes`.

**Ce qui est fait côté API :** l'owner peut restreindre l'audience d'une campagne selon le comportement d'achat de chaque client, comme dans Bref : sans achat depuis N jours, achat au cours des N derniers jours, total des achats entre deux montants, nombre d'achats entre deux bornes. Un achat = une vente de caisse terminée ou une facture émise / payée (une vente facturée automatiquement compte une fois) ; les avoirs diminuent le total mais ne sont pas des achats ; devis, brouillons et ventes annulées ne comptent pas. Les achats d'un même numéro sont additionnés sur toutes ses fiches ; si la campagne vise des boutiques, seuls les achats faits dans ces boutiques comptent. Les critères sont recalculés à l'aperçu, à l'enregistrement et au moment du départ (l'audience est alors figée).

**À utiliser** (code : `keevo/backend/src/main/java/com/keevo/whatsappcloud/campaign/adapter/in/rest/WhatsAppCampaignController.java`, `domain/model/PurchaseCriteria.java`) :
- Champs facultatifs, à plat (noms Bref de `targetingCriteria`), dans `POST /api/v1/whatsapp/campaigns/preview-targeting`, `POST /api/v1/whatsapp/campaigns` et `PATCH /api/v1/whatsapp/campaigns/{id}` : `inactiveForDays` (entier 0..3650, 0 = aucun critère), `purchasedWithinDays` (0..3650), `totalSpentMin` / `totalSpentMax` (FCFA, 0..1 000 000 000 000 ; décimales : minimum arrondi au franc supérieur, maximum au franc inférieur ; minimum 0 = aucun critère), `totalInvoicesMin` / `totalInvoicesMax` (nombre d'achats, entier 0..10 000 000 ; minimum 0 = aucun critère). Sans ces champs : comportement 21.6 inchangé.
- PATCH : un critère présent change (`null` le retire), un critère absent est conservé ; `targetedCount` est recalculé.
- Réponse campagne (liste, détail, création, PATCH, envoi, annulation) : les six champs (`null` si non défini) + `purchaseCriteriaLabels` (phrases françaises à afficher telles quelles, ex. « Sans achat depuis 30 jours », « Total des achats d'au moins 5 000 FCFA », « Entre 2 et 5 achats »).
- Erreurs 400 (`error` en français, à afficher tel quel) : « Indiquez un nombre de jours entier entre 0 et 3 650 (0 : aucun critère) », « Indiquez un montant d'achats entre 0 et 1 000 000 000 000 FCFA », « Indiquez un nombre d'achats entier entre 0 et 10 000 000 », « Le montant minimum des achats ne peut pas dépasser le maximum », « Le nombre minimum d'achats ne peut pas dépasser le maximum », « Aucun client ne peut avoir acheté dans les N derniers jours sans achat depuis M jours » (`purchasedWithinDays` ≤ `inactiveForDays`), « Aucun total d'achats en francs entiers n'est compris entre ces deux montants ». À l'envoi, 422 `CAMPAIGN_NO_RECIPIENT` si plus personne ne correspond.

**À implémenter côté mobile :**
1. Formulaire de campagne : section facultative « Comportement d'achat » — « Sans achat depuis … jours », « A acheté au cours des … derniers jours », « Total des achats entre … et … FCFA », « Nombre d'achats entre … et … » ; champ vide = pas de critère.
2. Aperçu « N clients recevront ce message » rappelé à chaque changement de critère (`preview-targeting` avec `storeIds` + critères ; temporiser la saisie ~500 ms).
3. Liste et détail : afficher `purchaseCriteriaLabels` sous les boutiques ciblées.
4. Modification : n'envoyer que les champs changés ; vider un champ = envoyer `null`.
5. En ligne uniquement ; jamais d'identifiant ni de nom de champ technique à l'écran.

**Implémentation de référence :** aucune dans `keevo/app` ; écran Bref `bref/features/whatsapp-marketing` (ciblage clients), mêmes noms de champs.

**Critères d'acceptation mobile / scénarios de test :**
- Given 4 clients avec accord (A : 2 ventes cette semaine, B : 1 vente il y a 40 jours, C : aucun achat, D : facture 10 000 il y a 50 jours puis avoir 4 000), When l'owner choisit « Sans achat depuis 30 jours », Then l'aperçu affiche 3 clients (B, C, D) ; « Total des achats d'au moins 10 000 FCFA » → 1 (A) ; « Nombre d'achats au plus 0 » → 1 (C).
- Given cette campagne envoyée, Then seuls B, C et D la reçoivent, et le détail affiche « Sans achat depuis 30 jours ».
- Given minimum 9 000 et maximum 100, Then « Le montant minimum des achats ne peut pas dépasser le maximum » s'affiche.
- Setup curl local : ventes `POST /api/v1/sales` avec `clientId`, factures `POST /api/v1/invoices` puis émission.

**Clé de suivi mobile :** `m21-11-ciblage-achats`.

## Dashboard super-admin (autre dépôt)

Aucun changement : les critères d'achat ne concernent que les campagnes clients des entreprises (owner) ; les campagnes plateforme (`/api/v1/admin/campaigns`) sont inchangées. Pas de clé `d21-11`.

## Auto Run Result

Status: done

**Summary.** (commit `56fbc9f3`) Purchase-behaviour targeting of client WhatsApp campaigns: six optional Bref criteria on the preview, create, PATCH and read of `/api/v1/whatsapp/campaigns`, persisted on the campaign (module whatsapp-cloud V3, six nullable columns with a range CHECK; no public migration, V80 unused), French labels in the response. Purchases come from the single revenue source (`RevenueQueryPort`), aggregated per phone number over all its client records, store-scoped when the campaign targets stores; one SQL statement per count / audience (crm `JdbcMarketingAudienceQuery`); an empty filter runs the untouched 21.6 query. Contract `whatsapp-cloud.json` updated.

**Files.** whatsapp-cloud `V3__campaign_purchase_criteria.sql`; crm `api/PurchaseFilter`, `ClientQueryPort` (filter overloads replace the 21.6 signatures), `ClientApiAdapter`, `domain/port/out/MarketingAudienceQuery`, `adapter/out/persistence/JdbcMarketingAudienceQuery`, `ClientSpringRepository` (shared `MARKETING_ELIGIBLE`); whatsappcloud `PurchaseCriteria`, `Campaign`, `CampaignService`, `JdbcCampaignRepository`, `WhatsAppCampaignController`; tests `PurchaseCriteriaTest`, `CampaignRulesTest`, `ClientApiAdapterTest`, `WhatsAppCampaignFullContextTest.purchaseTargeting`; `keevo/contracts/whatsapp-cloud.json`.

**Review.** 5 layers (blind, edge case, verification gap, intent alignment, FUS-NFR11): 29 findings — high 0, medium 5, low 19, false 5. Patched 15 (5 medium, 10 low), rejected 14 with reasons (triage log), deferred 0, routed 0.

**Follow-up review:** recommended — 5 medium patched; named risk: the filtered audience query is measured at 82 ms for 5 400 eligible numbers / 60 000 sales, not on a tenant an order of magnitude larger.

**Verification.** Compile OK; tests whatsappcloud 65, crm 241, architecture 171 (+1 skipped) green; `docs/entitlements/endpoints.md` unchanged (no new route). Live `keevo_dev_t2111` on port 4621 (dev, WhatsApp noop confirmed in the boot log, FCM off, fake Graph; fresh company, kv_11b5zm untouched): preview 4 without criterion; inactive 30 days → 3, recent → 1, ≥ 10 000 → 1, 5 000–7 000 → 1 (invoice − credit note), ≥ 2 purchases → 1, ≤ 0 → 1, store-scoped inactive → 3; three French 400s; campaign « Brouillon » 3 targeted with its label, PATCH +min → 1, clear → 3, send → « Envoyée » to Ben, Carl, Dora only. EXPLAIN ANALYZE after loading 20 000 clients / 60 000 sales: index probes per client, 82 ms. Backend stopped (`fuser -k 4621/tcp`), database dropped.

**Residual risks.** Days are 24-hour periods back from the computing instant (not civil days in Africa/Douala); a scheduled campaign is evaluated when it leaves, so its count can differ from the saved preview.
