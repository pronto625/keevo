package com.keevo.catalog.product.domain.port.out;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage of product photo thumbnails (FR21, epic 2). Local disk in V1
 * ({@code LocalDiskProductPhotoStorage}); object storage (S3) later is a new adapter of this
 * port. One photo per product, keyed by tenant schema + product id — a tenant can only ever
 * reach its own files because the schema comes from the authenticated session, never from
 * the request.
 */
public interface ProductPhotoStorage {

    /** Store (or replace) the photo of {@code productId}. {@code contentType}: image/jpeg or image/webp. */
    void store(String tenantSchema, UUID productId, byte[] content, String contentType) throws IOException;

    /** The stored photo, or empty when the product has none. */
    Optional<StoredPhoto> read(String tenantSchema, UUID productId) throws IOException;

    /** Delete the photo of {@code productId}; a missing photo is a no-op. Never throws. */
    void delete(String tenantSchema, UUID productId);

    /** Delete every photo of the tenant (account deletion). Never throws. */
    void deleteAll(String tenantSchema);

    record StoredPhoto(byte[] content, String contentType) {}
}
