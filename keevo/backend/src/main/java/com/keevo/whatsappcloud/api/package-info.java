/**
 * Public API of the {@code whatsappcloud} module (Story 24.6): the import of a Bref owner's WhatsApp Business data
 * ({@link com.keevo.whatsappcloud.api.LegacyWhatsAppImportPort}) — account (token encrypted here, with the Keevo key),
 * templates, campaigns and recipients, inbox messages. Consumer: {@code migration}.
 *
 * <p><b>Tenant convention:</b> tenant-scoped methods take {@code String tenantSchema} first and require it to be the
 * schema of the current tenant context.
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.whatsappcloud.api;
