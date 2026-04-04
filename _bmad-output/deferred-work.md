# Deferred Work

## Deferred from: code review of 8-0-fcm-push-notifications-whatsapp-wassender (2026-04-03)

- `DeviceTokenController.deleteToken()` — pas de vérification de propriété (ownership) sur la suppression de token. Tout utilisateur authentifié connaissant un token peut le supprimer. Surface d'attaque faible (tokens opaques, ~512 chars) mais représente un pattern à améliorer quand une liste par user_id sera disponible.

## Deferred from: code review of 7-5-configuration-des-rapports-preferences-whatsapp (2026-04-02)

- `WhatsAppPort.isConfigured()` has a `default return true` — any new adapter that forgets to override would silently send real test reports. Consider making the method abstract, or at minimum adding a code smell lint rule. Pre-existing design decision; not caused by Story 7.5 changes.
