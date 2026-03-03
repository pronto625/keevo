# Epic 7: Rapports, Dashboard & Communication WhatsApp

Simon consulte un dashboard matinal ultra-compact au démarrage de l'app, reçoit automatiquement ses rapports end-of-day et hebdomadaires sur WhatsApp, suit la rentabilité par produit, compare les performances entre boutiques, configure ses préférences de rapport, et reçoit un message motivationnel quotidien.

**FRs couverts :** FR50–FR60, FR63

---

## Story 7.1: Dashboard Matinal & Message Motivationnel

As a proprietor (Simon),
I want to see a powerful summary of my business the moment I open the app each morning,
So that I can make informed decisions in under 10 seconds without navigating through multiple screens.

**Acceptance Criteria:**

**Given** Simon opens the app as a proprietor (OWNER role)
**When** the app loads
**Then** the default landing screen is the Dashboard (not POS — POS is Loïc's default)
**And** the `MorningSummaryHeroCard` (UX26) is displayed at the top with: CA d'hier (Display 32sp, bold), tendance vs avant-hier (e.g., "↑ +12%"), sparkline des 7 derniers jours (mini chart), all with glassmorphism effect (`BackdropFilter`, blur 10–20, border opacity 30%)
**And** the hero card data loads from local Drift aggregations — available offline, under 3 seconds

**Given** the dashboard loads
**When** Simon views the full screen
**Then** below the hero card, he sees a row of `MetricBadgeCard` (UX28) components: Stock Global (total products across all stores), Alertes Actives (count of products below threshold), Ventes Aujourd'hui (count), CA Aujourd'hui (XAF)
**And** a "Top Produits" section shows the 5 best-selling products of the current week (name, qty sold, revenue)
**And** a `ShopStatusCard` (UX27) per active store: store name, today's CA, status indicator (green/orange/red based on sales trend), trend %
**And** the entire dashboard renders in < 3 seconds from local Drift data — no API call needed

**Given** it is the first app open of the day (after midnight)
**When** the dashboard loads
**Then** a motivational message is displayed in a dismissible card below the hero: one of a rotating set of French messages (e.g., "💪 Bonne journée Simon ! Hier vous avez fait 125 000 FCFA. Visez plus haut aujourd'hui !")
**And** the message changes daily (keyed by date) — same message all day even if app is reopened
**And** tapping the X dismisses it for the rest of the day; it reappears the next morning

**Given** Simon has multiple stores
**When** he taps a `ShopStatusCard`
**Then** he navigates to the store-specific sub-dashboard showing: today's sales list for that store, stock alerts for that store, top products for that store
**And** the sub-dashboard is also fully offline-capable

**Given** Simon is on desktop (expanded breakpoint ≥ 840dp)
**When** the dashboard loads
**Then** the layout uses a 3-column responsive grid: hero card full width, metrics row, then stores grid (2–3 per row)
**And** no information is hidden on desktop — the expanded layout shows more data than mobile without requiring scroll

---

## Story 7.2: Rapport End-of-Day — Génération & Envoi WhatsApp

As a proprietor (Simon),
I want to receive a concise, emoji-rich daily report on WhatsApp at the end of each business day,
So that I can review performance instantly on my phone without opening the app.

**Acceptance Criteria:**

**Given** the configurable end-of-day auto-trigger time for a store (default 20:00 local time, configurable in Story 7.5)
**When** the scheduled time is reached and no manual `DayClosedEvent` has been emitted for that store today
**Then** the backend triggers the day-close automatically via a Spring `@Scheduled` cron job (expression evaluated server-side per-tenant, defaulting to `0 0 20 * * ?` local time)
**And** the auto-generated closure emits a synthetic `DayClosedEvent` with `source: AUTO` and notifies Simon in-app: "Votre journée du [date] a été clôturée automatiquement."
**And** Loïc sees a subtle notification the next morning: "Votre journée du [date] a été clôturée automatiquement."
**And** the scheduler respects the tenant's configured timezone (stored in tenant preferences, defaults to UTC+1 WAT)

**Given** Loïc closes his day manually (Story 4.3) or the automatic 20h fallback triggers
**When** the `DayClosedEvent` is processed
**Then** the system generates the end-of-day report using the `DailyReport` template (Template Method GoF pattern, extending `AbstractReport`)
**And** the report aggregates for that `storeId` + `date`: total sales count, total CA (XAF), CA by payment mode (Cash / MoMo), top 3 products by quantity, average basket size, employee breakdown (name + CA)
**And** the report is sent via `WhatsAppPort.send()` to Simon's WhatsApp number

**Given** the WhatsApp message is generated
**When** it is formatted
**Then** the format is emoji-rich, French, readable in ≤ 5 seconds, no link, no PDF:
```
📊 Rapport du jour — [Store Name]
📅 [Date] | ⏰ [Close time] ([Manuel/Auto])

💰 CA Total : [X] FCFA
🛍 Ventes : [N] | 🧺 Panier moyen : [X] FCFA
💵 Cash : [X] FCFA | 📱 MoMo : [X] FCFA

🏆 Top produits :
1. [Product] — [qty] vendu(s) — [X] FCFA
2. [Product] — [qty] vendu(s) — [X] FCFA
3. [Product] — [qty] vendu(s) — [X] FCFA

👤 Équipe :
• [Employee 1] : [N] ventes — [X] FCFA
• [Employee 2] : [N] ventes — [X] FCFA

📦 Alertes stock : [N] produit(s) en rupture
```
**And** if Simon has multiple stores and all have closed, a combined multi-store summary is appended

**Given** the WhatsApp delivery fails (WhatsApp port unavailable or rate limited)
**When** the send attempt fails
**Then** the report is queued for retry (max 3 attempts, 5-minute intervals)
**And** if all retries fail, the report is stored locally and a push notification is sent to Simon in-app: "Rapport WhatsApp non envoyé — consultez le rapport dans l'app"
**And** the report remains accessible in Rapports > Historique regardless of WhatsApp delivery status

**Given** Simon navigates to Rapports > Historique
**When** the screen loads
**Then** all past end-of-day reports are listed in reverse chronological order: date, store, CA, delivery status (WhatsApp ✅ / ❌ / En app)
**And** tapping any report shows the full formatted WhatsApp text as an in-app preview
**And** a "Renvoyer sur WhatsApp" action is available for failed reports (OWNER only)
**And** the history loads offline from local Drift data

---

## Story 7.3: Rapport Hebdomadaire Automatique

As a proprietor (Simon),
I want to receive a weekly summary every Sunday evening with my full week's performance,
So that I can review business trends, top performers, and profitability at a glance without any manual action.

**Acceptance Criteria:**

**Given** every Sunday at 20:00 local time
**When** the backend weekly scheduler triggers
**Then** the `WeeklyReport` (extends `AbstractReport` via Template Method) is generated for each active tenant with: date range (Monday–Sunday), total CA across all stores, CA by store, CA by employee, top 5 products for the week by revenue, top 5 products by quantity, week-over-week CA comparison (vs previous Sunday report)

**Given** the weekly report is generated
**When** it is sent
**Then** it is delivered via `WhatsAppPort` to Simon's WhatsApp number AND via SMS fallback if WhatsApp fails (using the same `WhatsAppPort` adapter with SMS fallback — Chain of Responsibility pattern)
**And** the format is the same emoji-rich compact style as the daily report, with a "📅 Rapport Hebdo" header
**And** the report is stored in Drift locally on the next pull sync

**Given** Simon has not closed any day during the week (no `DayClosedEvent`)
**When** the weekly report is generated
**Then** it still runs using the aggregated `sales` data from the server — it does not depend on day-close events
**And** the report header notes: "⏰ Rapport auto-généré (aucune clôture manuelle cette semaine)"

**Given** Simon configures a different weekly report day/time
**When** he navigates to Paramètres > Rapports > Hebdomadaire
**Then** he can change the day of week (default: Dimanche) and the time (default: 20:00)
**And** the setting is saved in tenant preferences and respected by the backend scheduler

---

## Story 7.4: Dashboard Rentabilité & Analyse par Produit/Boutique

As a proprietor (Simon),
I want to see profitability per product and comparative performance per store,
So that I can identify my best margins, cut underperforming products, and understand which store drives the most value.

**Acceptance Criteria:**

**Given** Simon navigates to Rapports > Rentabilité
**When** the screen loads
**Then** a product profitability list is shown: each product with: name, total units sold (selected period), total revenue, total cost (purchase price + transport × units sold), gross margin in XAF and %
**And** products are sorted by margin % descending by default
**And** Simon can sort by: Marge % / Marge XAF / CA / Unités vendues
**And** the margin % color coding matches the product form (green ≥ 20%, orange 10–19%, red < 10%)

**Given** Simon selects a period filter
**When** he changes the period (Aujourd'hui / 7 jours / 30 jours / Personnalisé)
**Then** all profitability data updates to reflect the selected range
**And** the filter is applied locally against Drift `sales` + `sale_items` + `products` data — no API call needed
**And** the screen renders in < 3 seconds for up to 500 products

**Given** Simon taps a product in the rentabilité list
**When** the product detail profitability screen opens
**Then** he sees: total units sold per period, applied price range (min / max / average — detects price overrides), margin trend (sparkline 7 days), top store by sales volume for this product

**Given** Simon navigates to Rapports > Boutiques
**When** the comparative store performance screen loads
**Then** a ranked list of stores is shown by CA (current period): store name, CA, number of sales, average basket, top product
**And** a delta badge shows week-over-week change per store: "+8%" in green or "−3%" in red
**And** Simon can filter by period and switch between CA / Sales count / Avg basket as the ranking metric

**Given** Simon is on Plan Payant (FR55)
**When** he taps "Exporter" on any report screen
**Then** a PDF is generated client-side (or server-side) with the full report data, formatted cleanly
**And** an Excel (.xlsx) export is available for raw data tables (sales list, product list with margins)
**And** the export is shared via the native Flutter share sheet (save to device / send via app)
**And** for Plan Free users, the export button shows a lock icon and "Disponible sur Plan Premium" tooltip

---

## Story 7.5: Configuration des Rapports & Préférences WhatsApp

As a proprietor (Simon),
I want to configure which reports I receive, when, and via which channel,
So that I get exactly the information I need without being overwhelmed by notifications.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Rapports
**When** the configuration screen loads
**Then** he sees toggles and settings for each report type:
  - **Rapport end-of-day** : Activé/Désactivé, heure d'envoi auto (default: 20:00), canal (WhatsApp / In-app uniquement)
  - **Rapport hebdomadaire** : Activé/Désactivé, jour (default: Dimanche), heure (default: 20:00), canal
  - **Rapport d'inventaire** : Activé/Désactivé (envoi auto post-inventaire), canal
  - **Alertes stock** : Activé/Désactivé (configured per-product in Epic 2), canal (Push / WhatsApp / Les deux)

**Given** Simon disables the WhatsApp daily report
**When** the setting is saved
**Then** day-close events still trigger local report generation and storage
**And** no WhatsApp message is sent — only in-app notification
**And** Simon can still view all reports in Rapports > Historique

**Given** Simon changes the end-of-day auto-trigger time from 20:00 to 22:00
**When** the setting is saved via `PUT /api/v1/tenants/{tenantId}/preferences`
**Then** the backend scheduler updates the cron job for that tenant
**And** the change takes effect from the next day
**And** a confirmation SnackBar: "Heure de clôture automatique mise à jour : 22h00"

**Given** Simon wants to test his WhatsApp report config
**When** he taps "Envoyer un rapport test"
**Then** a test report is immediately generated with today's data and sent via `WhatsAppPort`
**And** a success confirmation: "Rapport test envoyé sur WhatsApp ✅" or an error message if delivery failed
**And** the test report is NOT saved in the report history (clearly labelled "TEST" in the message)

---
