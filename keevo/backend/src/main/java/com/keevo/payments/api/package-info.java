/**
 * Public API of the {@code payments} module (Story 24.5): the import of a Bref owner's monetisation by the migration
 * tool ({@link com.keevo.payments.api.LegacyMonetisationImportPort}). Only ports and immutable records live here; the
 * implementation is internal ({@code payments.legacyimport}). Platform tables only (no tenant schema).
 */
@org.springframework.modulith.NamedInterface("api")
package com.keevo.payments.api;
