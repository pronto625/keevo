/**
 * Public API of the {@code messaging} module (Story 12.2e).
 *
 * <p>Capabilities other modules consume: {@link com.keevo.messaging.api.WhatsAppPort} (WhatsApp
 * delivery) and {@link com.keevo.messaging.api.NotificationPort} (push, with its
 * {@link com.keevo.messaging.api.NotificationPayload} / {@link com.keevo.messaging.api.PushSendResult}
 * records), plus the draft-product validation tracking the catalog drives
 * ({@link com.keevo.messaging.api.DraftValidationCommandPort},
 * {@link com.keevo.messaging.api.DraftValidationQueryPort}). Implementations stay internal; the draft
 * ports are implemented by a package-private adapter ({@code messaging.notification.adapter.in.api})
 * with no transaction of its own: every call joins the caller's transaction.
 *
 * <p><b>Tenant convention (12.2d/12.2e):</b> every tenant-scoped method takes the tenant schema
 * explicitly as its first {@code String tenantSchema} parameter; on the draft ports it must be the
 * schema of the current tenant context ({@code TenantContext.requireCurrent}): null, blank or another
 * schema fails fast. {@code NotificationPort} keeps its historical {@code tenantId} (schema name)
 * parameter, which it already honours explicitly.
 *
 * <p>Other modules depend on {@code "messaging :: api"}, never on messaging internals.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.messaging.api;
