# Epic 9: Super Admin Dashboard (Toor)

Toor pilote l'ensemble de la plateforme Keevo depuis un tableau de bord dédié : gestion des tenants, activation des plans payants, suivi des revenus et analytics, surveillance de la santé système, alertes proactives, et diffusion de notifications push globales.

**FRs couverts :** FR77–FR83

---

## Story 9.1: Gestion des Tenants

As Toor (super admin),
I want to view, search, and manage all registered tenants from a central dashboard,
So that I can monitor platform adoption, handle support requests, and take operational actions on any tenant.

**Acceptance Criteria:**

**Given** Toor logs in to the Super Admin dashboard (separate web interface, same backend, role `SUPER_ADMIN`)
**When** he navigates to the Tenants section
**Then** a paginated table lists all tenants with columns: Tenant ID, Business Name, Owner Phone, Plan (FREE/PAID), Status (ACTIVE / DELETION_PENDING / SUSPENDED), Registration Date, Last Activity Date, Store Count, Employee Count
**And** pagination is 25 rows per page with total count displayed: "Affichage 1–25 sur [N] tenants"
**And** the table loads in < 2 seconds for up to 10 000 tenants (server-side pagination + indexed queries)

**Given** Toor wants to find a specific tenant
**When** he uses the search bar
**Then** he can search by: business name (partial match), owner phone (exact), tenant ID (exact)
**And** results update within 300ms of typing (debounced)
**And** active filters are shown as chips above the table with individual × clear buttons

**Given** Toor applies filters
**When** he uses the filter panel
**Then** he can filter by: Plan (FREE / PAID / ALL), Status (ACTIVE / DELETION_PENDING / SUSPENDED / ALL), Registration date range (date picker), Last activity range
**And** filters compose (AND logic): only tenants matching all active filters are shown

**Given** Toor clicks on a tenant row
**When** the tenant detail panel opens (right-side drawer)
**Then** he sees: full tenant profile, owner contact, store list with CA per store, employee list with last login, and a complete audit log for the last 30 days (all `*Event` records for this tenant)
**And** the audit log is paginated: 50 events per page, most recent first

**Given** a tenant status is `DELETION_PENDING`
**When** Toor views the tenant list
**Then** the row is highlighted in orange with a countdown: "Suppression dans [N] jours"
**And** Toor can force-cancel the deletion or force-execute it immediately from the detail panel (with admin confirmation dialog)

---

## Story 9.2: Activation Plan Payant

As Toor (super admin),
I want to manually activate or deactivate the paid plan for any tenant,
So that I can handle off-platform payments (mobile money, cash, invoices) and unlock premium features for paying clients.

**Acceptance Criteria:**

**Given** Toor views a tenant detail panel
**When** the tenant is on Plan FREE
**Then** a "Passer au Plan Payant" button is visible with a date picker for the expiry date
**And** on confirmation: the tenant's `plan` is set to `PAID`, `planExpiresAt` is set to the chosen date, and a `PlanActivatedEvent` is emitted with `actorId` (Toor), `tenantId`, `activatedAt`, `expiresAt`
**And** the tenant immediately gains access to all Plan Payant features (FR64 shipping alerts, advanced reports)
**And** a WhatsApp notification is sent to the owner: "🎉 Votre compte Keevo Premium est activé jusqu'au [date]. Profitez de toutes les fonctionnalités !"

**Given** a tenant's paid plan approaches expiry
**When** `planExpiresAt` is within 7 days
**Then** an alert appears in Toor's admin dashboard: "⚠️ [Business Name] — plan expire dans [N] jours"
**And** the tenant owner receives a WhatsApp reminder 7 days and 1 day before expiry: "⏰ Votre abonnement Keevo Premium expire le [date]. Contactez-nous pour renouveler."

**Given** a paid plan expires (`planExpiresAt` < now)
**When** the expiry job runs (daily at 01:00 UTC)
**Then** the tenant's `plan` is automatically reverted to `FREE`
**And** Plan Payant features are gated again (FR64, advanced reports)
**And** a `PlanExpiredEvent` is emitted and flagged in Toor's alerts

**Given** Toor needs to suspend a tenant (fraud, abuse, non-payment dispute)
**When** he sets the tenant status to `SUSPENDED`
**Then** all API calls from that tenant return HTTP 403 `{ "domainCode": "TENANT_SUSPENDED" }`
**And** the Flutter app shows a full-screen blocking message: "Votre compte est suspendu. Contactez le support Keevo."
**And** a `TenantSuspendedEvent` is emitted with reason (free text, required)

---

## Story 9.3: Dashboard Revenus

As Toor (super admin),
I want to visualize the platform's financial performance metrics in one screen,
So that I can track revenue growth, identify churn, and make informed business decisions.

**Acceptance Criteria:**

**Given** Toor navigates to the Revenus section of the admin dashboard
**When** the page loads
**Then** a summary card row displays: MRR (Monthly Recurring Revenue in XAF), ARR (MRR × 12), Active Paid Tenants, Churn Rate current month (%), New Paid Tenants this month
**And** all values reflect the state as of the last admin page load (no auto-refresh needed — manual refresh button available)

**Given** Toor views the revenue chart
**When** the 12-month MRR trend chart is displayed
**Then** a bar chart shows MRR per calendar month for the last 12 months
**And** hovering a bar shows: month label, MRR total, number of paying tenants, number of new conversions, number of churned tenants
**And** a trend indicator next to the current MRR card shows: ▲ +[X]% vs last month (green) or ▼ −[X]% vs last month (red)

**Given** Toor wants to analyze churn
**When** he views the Churn section
**Then** a table lists all tenants whose plan reverted from PAID to FREE in the selected month, with: business name, plan end date, tenure (how long they were paid), last CA before churn
**And** the data can be exported as CSV: "Exporter CSV" button downloads `keevo-churn-YYYY-MM.csv`

**Given** Toor selects a date range filter
**When** he applies Start Date / End Date
**Then** all revenue metrics and charts update to reflect the selected period
**And** the date range defaults to: current calendar month

---

## Story 9.4: Analytics Plateforme

As Toor (super admin),
I want to monitor platform usage metrics — active users, retention, and feature adoption — to prioritize development and validate product-market fit.

**Acceptance Criteria:**

**Given** Toor navigates to the Analytics section
**When** the page loads
**Then** a KPI card row shows: DAU (Distinct Active Users today), MAU (last 30 days), DAU/MAU ratio (engagement index), Total Tenants, Total Stores, Total Sales Processed (count, all time), Total GMV (Gross Merchandise Value in XAF, all time)
**And** "Active" is defined as: a user who made ≥ 1 authenticated API call within the period

**Given** Toor views user retention
**When** he opens the Rétention tab
**Then** a weekly cohort retention table is displayed: each row = a signup week cohort, columns = Week 1, Week 2, ..., Week 8 retention percentages
**And** cells are colour-coded: green (> 60%), yellow (30–60%), red (< 30%)

**Given** Toor wants to see top performing stores
**When** he opens the Top Boutiques tab
**Then** a table lists the top 20 stores by GMV for the selected period with: store name, tenant name, CA, transaction count, average basket, period-over-period growth %
**And** the table is sortable by any column header (click to sort ASC, click again DESC)

**Given** Toor wants to monitor feature adoption
**When** he views the Adoption section
**Then** a horizontal bar chart shows: % of active tenants using each major feature in the last 30 days — POS, Inventory Sessions, Stock Transfers, WhatsApp Reports, Alerts, Offline Sync
**And** this data helps Toor prioritize roadmap decisions

---

## Story 9.5: Santé Système, Alertes & Notifications Push Globales

As Toor (super admin),
I want to monitor the health of the Keevo platform in real-time and send global announcements to all users,
So that I can detect incidents before users report them and communicate planned maintenance or new features proactively.

**Acceptance Criteria:**

**Given** Toor navigates to the Santé Système section
**When** the page loads
**Then** a status card grid displays: API Uptime (last 30 days %), Current API Latency (p50, p95, p99 in ms), Error Rate (last 1h, % of 5xx), Active DB Connections, Sync Queue Depth (total unprocessed sync records across all tenants), Last Flyway migration status (✅ / ❌)
**And** all metrics are sourced from Spring Actuator (`/actuator/health`, `/actuator/metrics`) and displayed without caching (live on page load)

**Given** any health metric breaches a threshold
**When** the admin page is loaded and a threshold is exceeded (API error rate > 2%, p99 > 2s, queue depth > 1000)
**Then** the affected card turns red with an alert icon
**And** a sticky alert banner appears at the top of all admin pages: "🔴 Incident en cours — [metric name] dépasse le seuil. Vérifiez les logs CloudWatch."

**Given** Toor's alerts panel (FR82)
**When** he opens the Alertes section
**Then** a unified feed shows all platform-level alerts in reverse chronological order: HIGH priority feedback items (Story 8.4), tenants in DELETION_PENDING, expired paid plans still showing as PAID (data integrity check), failed WhatsApp delivery batches (> 10 failures in 1h), sync conflicts unresolved > 24h per tenant
**And** each alert has: severity badge (HIGH/MEDIUM/LOW), description, timestamp, and a "Marquer comme traité" action

**Given** Toor wants to send a global notification to all users (FR83)
**When** he navigates to Notifications > Diffusion Globale
**Then** a form allows: Title (max 80 chars), Body (max 200 chars), Target (ALL / PAID_ONLY / FREE_ONLY / SPECIFIC_TENANT), Schedule (Now / Scheduled date-time picker)
**And** a live preview shows how the notification will appear on Android and iOS
**And** on confirmation: the notification is queued and sent via Firebase Cloud Messaging to all matching device tokens
**And** a `GlobalPushSentEvent` is emitted with: `actorId` (Toor), `targetSegment`, `recipientCount`, `sentAt`, `title`

**Given** Toor submits a global notification
**When** the send is confirmed
**Then** a delivery status card appears: "Envoi en cours — [N] appareils ciblés"
**And** within 5 minutes the card updates to: "✅ Envoyé — [N] livrés / [M] échecs"
**And** the notification appears in each targeted user's in-app notification history (Story 8.1)
**And** the global notification history is preserved in the admin panel for 90 days: date, title, target segment, delivered count, failure count
