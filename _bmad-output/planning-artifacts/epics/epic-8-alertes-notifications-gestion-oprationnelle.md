# Epic 8: Alertes, Notifications & Gestion Opérationnelle

Simon reçoit des alertes proactives de stock critique et de tendances de ventes via push et WhatsApp, consulte l'activité de ses employés, désactive et révoque leurs sessions à distance. Les utilisateurs peuvent soumettre un feedback ou demander la suppression complète de leur compte.

**FRs couverts :** FR61–FR62, FR64, FR67–FR68, FR91–FR92

---

## Story 8.1: Alertes Stock Critique & Tendances de Ventes

As a proprietor (Simon),
I want to receive proactive alerts when stock reaches critical levels and when unusual sales trends are detected,
So that I can act before running out of stock and spot opportunities or problems before they escalate.

**Acceptance Criteria:**

**Given** a stock-modifying operation reduces a product's quantity to ≤ its configured threshold
**When** the `StockThresholdBreachedEvent` is emitted (from any stock write: sale, transfer, adjustment)
**Then** a push notification is sent to all of Simon's registered devices: "⚠️ Stock bas — [Product Name] : [current qty] unité(s) restante(s) (seuil : [threshold]) — Boutique [Store Name]"
**And** simultaneously, a WhatsApp message is sent via `WhatsAppPort` to Simon's number with the same content
**And** the notification link deep-links to the product's stock detail screen when tapped
**And** if both push and WhatsApp fail, an in-app badge appears on the Stock module icon in the navigation bar

**Given** simultaneous threshold breaches for multiple products
**When** more than 3 breaches occur within a 60-second window
**Then** a single batched notification is sent instead of individual ones: "⚠️ [N] produits en stock bas — Boutique [Store Name]. Consultez l'onglet Stock."
**And** the individual product details are listed in a single consolidated WhatsApp message
**And** duplicate alerts for the same product are suppressed for 4 hours after the first notification (cooldown per product per store)

**Given** the system detects a sales trend anomaly (FR62)
**When** the hourly sales momentum for a store drops > 40% compared to the same hour on previous 3 days (calculated server-side)
**Then** a push notification is sent: "📉 Baisse des ventes détectée — [Store Name] : activité inhabituelle depuis [X]h"
**And** when a positive spike is detected (> 60% above average hourly rate)
**Then** a push notification is sent: "🚀 Pic de ventes — [Store Name] : [N] ventes en [X] minutes — continuez sur cette lancée !"
**And** trend notifications have a 2-hour cooldown per store to avoid spam
**And** Simon can toggle trend notifications ON/OFF in Paramètres > Rapports (Story 7.5)

**Given** Simon views his notification history
**When** he navigates to the notifications bell icon in the AppBar
**Then** all notifications from the last 30 days are listed in reverse chronological order: type icon, message, store, timestamp, read/unread status
**And** unread notifications show a bold style; tapping marks them as read
**And** the unread count badge on the bell icon updates in real-time via Riverpod state

**Given** FR64 — alertes de statut colis (Plan Payant, Growth phase)
**When** a Plan Free user opens the alerts configuration screen
**Then** the "Alertes de livraison" section is visible but locked with a "Plan Premium" badge
**And** no shipping API calls are made for Plan Free tenants

---

## Story 8.2: Suivi d'Activité Employés

As a proprietor (Simon),
I want to monitor my employees' activity — their last login, sales volume, and connection status — without intruding on their work,
So that I can identify disengagement, coaching needs, or suspicious inactivity remotely.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Équipe
**When** the team management screen loads
**Then** each employee card shows:
  - Name + assigned store
  - Last login: relative time (e.g., "Il y a 2 heures" / "Hier à 9h15" / "Jamais connecté")
  - Today's sales: count + CA in XAF
  - This week's sales: count + CA
  - Active session indicator: green dot if currently active (last API call < 5 minutes ago)
**And** the data loads from local Drift cache (updated on last pull sync)

**Given** Simon taps an employee card
**When** the employee detail screen opens
**Then** he sees a full activity timeline for the last 7 days: per-day sales count, CA, connection time (first login of day), close time (last activity), and day-close status (✅ manuel / ⏰ auto / ❌ non clôturé)
**And** the total for the period is summarized at the top: total sales, total CA, average daily CA, average basket

**Given** an employee has not logged in for > 48 hours
**When** Simon views the team list
**Then** the employee card shows an orange warning badge: "Inactif depuis [N] jours"
**And** a one-tap "Envoyer un rappel WhatsApp" shortcut is available: sends a templated message via `WhatsAppPort`: "Bonjour [Name] ! N'oubliez pas d'ouvrir Keevo aujourd'hui pour suivre vos ventes 😊"

**Given** Simon is on desktop
**When** he views the team activity screen
**Then** the employee cards are displayed in a 2–3 column grid (responsive breakpoints)
**And** a summary row at the top shows the team aggregates: total team CA today, best performer name + CA, team average basket

---

## Story 8.3: Désactivation Employé & Révocation de Sessions

As a proprietor (Simon),
I want to immediately deactivate an employee and revoke all their active sessions remotely,
So that I can respond instantly to terminations or security incidents without physical access to their device.

**Acceptance Criteria:**

**Given** Simon is on the employee detail screen
**When** he taps "Désactiver l'accès"
**Then** a confirmation dialog appears: "Désactiver [Name] ? Toutes ses sessions actives seront révoquées immédiatement. Cette action peut être annulée."
**And** on confirmation: the employee's `status` is set to `INACTIVE` in the backend
**And** all refresh tokens for that employee are invalidated server-side (added to JWT blacklist / refresh token revocation table)
**And** the next API request from any of the employee's devices returns HTTP 401 `{ "domainCode": "ACCOUNT_INACTIVE" }`
**And** this revocation happens within 5 minutes of Simon's confirmation (NFR12)
**And** an `EmployeeDeactivatedEvent` is emitted in the audit log with `actorId` (Simon), `targetUserId`, `occurredAt`

**Given** Loïc's device is actively using the app when his account is deactivated
**When** his JWT expires or he makes a new API call
**Then** he is redirected to the login screen with the message: "Votre accès a été suspendu. Contactez votre propriétaire pour plus d'informations."
**And** no technical details or error codes are shown to Loïc — only the human-readable message

**Given** Simon wants to re-enable a previously deactivated employee
**When** he taps "Réactiver l'accès" on the inactive employee's card
**Then** the employee's `status` is set to `ACTIVE`
**And** the employee can log in again with their existing credentials (no re-invitation required)
**And** an `EmployeeReactivatedEvent` is emitted in the audit log
**And** a confirmation SnackBar: "[Name] peut à nouveau se connecter à Keevo"

**Given** the JWT blacklist implementation
**When** a revoked refresh token is submitted to `POST /api/v1/auth/refresh`
**Then** the server returns HTTP 401 `{ "domainCode": "TOKEN_REVOKED" }`
**And** the token is NOT re-issued regardless of expiry date
**And** the blacklist entry persists for 30 days (after which the token would have expired anyway)

---

## Story 8.4: Feedback Utilisateur & Signalement de Problème

As any user (Simon or Loïc),
I want to submit feedback or report a problem directly from the app with minimal friction,
So that issues are captured instantly in context without requiring me to find an email or leave the app.

**Acceptance Criteria:**

**Given** any user navigates to Paramètres > Aide > Envoyer un feedback
**When** the feedback form opens
**Then** a form appears with:
  - Type selector: "💡 Suggestion" / "🐛 Signaler un problème" / "👍 J'adore Keevo"
  - Description (required, free text, min 10 characters, max 500)
  - Screen context (auto-populated: current route name + app version + platform)
  - Optional: screenshot attachment (camera or gallery, compressed ≤ 500 KB)
**And** the form is available offline — feedback is queued in `sync_queue` and sent on next sync

**Given** the user submits the feedback form
**When** the submission is processed
**Then** the feedback is sent to `POST /api/v1/feedback` with: `type`, `description`, `tenantId`, `userId`, `appVersion`, `platform`, `screenContext`, `submittedAt`
**And** a success SnackBar: "Merci pour votre retour ! Nous l'examinerons rapidement 🙏"
**And** the form resets to empty after successful submission

**Given** a user reports a problem (type: "🐛 Signaler un problème")
**When** the submission is received by the backend
**Then** the feedback is stored in the `feedback` table (shared admin schema) with `priority: NORMAL`
**And** if the description contains keywords: "bloqué", "erreur critique", "données perdues", "ne fonctionne pas" → `priority: HIGH`
**And** HIGH priority items are flagged in the Super Admin dashboard (Epic 9) for Toor's attention

**Given** the user navigates from a specific screen before submitting feedback
**When** the feedback form auto-populates the screen context
**Then** `screenContext` is set to the current go_router route name (e.g., `/pos`, `/inventory/session/123`)
**And** Toor can filter feedback by screen context in the admin dashboard to identify problematic areas

---

## Story 8.5: Suppression de Compte & Données (RGPD)

As a proprietor (Simon),
I want to request the complete deletion of my account and all associated data,
So that I can leave the platform with full assurance that no personal or business data is retained.

**Acceptance Criteria:**

**Given** Simon navigates to Paramètres > Mon Compte > Supprimer mon compte
**When** the deletion request screen loads
**Then** a clear warning is displayed: "⚠️ Cette action est permanente et irréversible. Toutes vos données — produits, ventes, stock, employés, rapports — seront définitivement supprimées. Vous avez 30 jours pour annuler cette demande."
**And** the warning lists exactly what will be deleted: tenant schema (all tables), user accounts (owner + all employees), WhatsApp history, uploaded photos (S3)

**Given** Simon reads the warning and chooses to proceed
**When** he taps "Demander la suppression"
**Then** a confirmation requires him to type his phone number to confirm intent (not just tap "OK")
**And** on confirmation, an `AccountDeletionRequestedEvent` is created with `requestedAt` and `scheduledDeletionAt` (= `requestedAt` + 30 days)
**And** the tenant status is set to `DELETION_PENDING` — all write operations are blocked, reads remain available during the 30-day grace period
**And** a WhatsApp confirmation is sent: "Votre demande de suppression a été enregistrée. Toutes vos données seront supprimées le [date]. Pour annuler, accédez à Paramètres > Mon Compte dans l'app."

**Given** the 30-day grace period is active
**When** Simon changes his mind and opens the app
**Then** a prominent banner shows: "Suppression programmée le [date] — [N] jours restants. [Annuler la suppression]"
**And** tapping "Annuler la suppression" reverts the tenant status to `ACTIVE` and cancels the scheduled deletion
**And** an `AccountDeletionCancelledEvent` is emitted in the audit log

**Given** the 30-day grace period expires
**When** the backend scheduler runs the deletion job
**Then** the tenant PostgreSQL schema `kv_xxxxxx` is dropped entirely (CASCADE)
**And** all user accounts associated with the tenant are deleted from the shared user table
**And** all S3 objects with the tenant prefix are deleted
**And** the WhatsApp opt-out record is preserved (RGPD — proof of valid consent withdrawal)
**And** a final WhatsApp message is sent: "Votre compte Keevo a été supprimé. Merci d'avoir utilisé Keevo."
**And** Toor (Super Admin) is notified of the deletion in the admin dashboard

**Given** an employee (Loïc) navigates to Paramètres > Mon Compte > Supprimer mon compte
**When** the screen loads
**Then** a message informs him: "Votre compte est lié à [Business Name]. Pour supprimer vos données, demandez à votre propriétaire de vous retirer de l'équipe."
**And** employees cannot initiate full tenant deletion — only the OWNER role can

---
