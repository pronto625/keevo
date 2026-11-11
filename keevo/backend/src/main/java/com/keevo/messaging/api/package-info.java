/**
 * Public API of the {@code messaging} module (Story 24.6): the import of a Bref owner's in-app notification inbox
 * ({@link com.keevo.messaging.api.NotificationImportPort}), written without any push. Consumer: {@code migration}.
 *
 * <p><b>Tenant convention:</b> tenant-scoped methods take {@code String tenantSchema} first and require it to be the
 * schema of the current tenant context.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.messaging.api;
