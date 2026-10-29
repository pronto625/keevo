package com.keevo.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the catalog's products for other modules (tenant-scoped by the current
 * {@code TenantContext}; runs inside the caller's transaction, if any).
 */
public interface ProductQueryPort {

    Optional<ProductView> findById(UUID productId);

    /** Case-insensitive, trimmed lookup by name. */
    Optional<ProductView> findByName(String name);

    /** True when a product (any status) already carries this name — case-insensitive, trimmed. */
    boolean existsByName(String name);

    boolean existsBySku(String sku);

    /** Non-archived products. */
    List<ProductView> findAllActive();

    List<ProductView> findAllByIds(List<UUID> productIds);

    /** Active products belonging to any of the given categories. */
    List<ProductView> findByCategoryIds(List<UUID> categoryIds);

    /** Photo URL per product id (products without photo are absent). */
    Map<UUID, String> findPhotoUrlsByIds(List<UUID> productIds);

    /**
     * Status name of a product ({@code "ACTIVE"}, {@code "DRAFT"}), empty when it does not
     * exist. Loads the product through the catalog's product repository.
     */
    Optional<String> findStatusName(UUID productId);
}
