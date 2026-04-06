# Deferred Work

## Deferred from: code review of 8-0-fcm-push-notifications-whatsapp-wassender (2026-04-03)

- `DeviceTokenController.deleteToken()` — pas de vérification de propriété (ownership) sur la suppression de token. Tout utilisateur authentifié connaissant un token peut le supprimer. Surface d'attaque faible (tokens opaques, ~512 chars) mais représente un pattern à améliorer quand une liste par user_id sera disponible.

## Deferred from: code review of 7-5-configuration-des-rapports-preferences-whatsapp (2026-04-02)

- `WhatsAppPort.isConfigured()` has a `default return true` — any new adapter that forgets to override would silently send real test reports. Consider making the method abstract, or at minimum adding a code smell lint rule. Pre-existing design decision; not caused by Story 7.5 changes.

## Deferred from: code review of 8-1-alertes-stock-critique-tendances-de-ventes (2026-04-04)

- **D1 — `ConcurrentHashMap<BatchWindow>` never evicted** — `StockAlertNotificationListener.batchWindows` accumulates one entry per `tenantId:storeId`. Growth bounded by tenant×store count (~40 bytes each). Consider Caffeine cache with TTL or periodic cleanup post-MVP.
- **D2 — TOCTOU race between cooldown check and upsert** — Two concurrent `@Async` threads for the same product+store could both pass `existsActiveCooldown` and both dispatch. Narrow window, benign duplicate. Consider DB-level advisory lock if notification dedup becomes critical.
- **D3 — No `@SchedulerLock` for overlapping trend scheduler runs** — If a `detectTrends()` run exceeds 1 hour, the next cron invocation starts concurrently. Consider integrating ShedLock when scaling to many tenants.
