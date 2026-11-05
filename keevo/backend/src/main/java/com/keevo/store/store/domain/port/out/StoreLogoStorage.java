package com.keevo.store.store.domain.port.out;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Where store logos live (Story 19.12) — a port: local disk today, an object store tomorrow. Each upload is a new
 * file, named {@code <store id>-<unique hex>.png|jpg}; the tenant schema is validated on every access (no path escape, no
 * cross-tenant read). The store row keeps the file name ({@code stores.logo_path}).
 */
public interface StoreLogoStorage {

    /** A stored logo: {@code image/png} or {@code image/jpeg}. */
    record StoredLogo(byte[] content, String contentType) {
    }

    /**
     * Writes the logo of a store under a new unique name (atomically: a reader never sees a partial file) and returns
     * that name. The previous file is left in place (the caller removes it once its row points to the new one).
     *
     * @param contentType {@code image/png} or {@code image/jpeg}
     */
    String store(String tenantSchema, UUID storeId, byte[] content, String contentType) throws IOException;

    /** The logo stored under {@code fileName}; empty when the file is missing. */
    Optional<StoredLogo> read(String tenantSchema, String fileName) throws IOException;

    /** Removes one logo file; a missing file is a no-op; never throws (failures are logged). */
    void delete(String tenantSchema, String fileName);
}
