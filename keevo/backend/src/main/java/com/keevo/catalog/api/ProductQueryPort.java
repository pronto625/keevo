package com.keevo.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the catalog's products for other modules; runs inside the caller's transaction,
 * if any. The {@code tenantSchema} parameter is checked against the bound tenant
 * ({@code TenantContext.requireCurrent}, fail-fast); the repositories read the bound tenant.
 */
public interface ProductQueryPort {

    Optional<ProductView> findById(String tenantSchema, UUID productId);

    /** Case-insensitive, trimmed lookup by name. */
    Optional<ProductView> findByName(String tenantSchema, String name);

    /** True when a product (any status) already carries this name — case-insensitive, trimmed. */
    boolean existsByName(String tenantSchema, String name);

    boolean existsBySku(String tenantSchema, String sku);

    /** Non-archived products. */
    List<ProductView> findAllActive(String tenantSchema);

    List<ProductView> findAllByIds(String tenantSchema, List<UUID> productIds);

    /** Active products belonging to any of the given categories. */
    List<ProductView> findByCategoryIds(String tenantSchema, List<UUID> categoryIds);

    /** Photo URL per product id (products without photo are absent). */
    Map<UUID, String> findPhotoUrlsByIds(String tenantSchema, List<UUID> productIds);

    /**
     * Status name of a product ({@code "ACTIVE"}, {@code "DRAFT"}), empty when it does not
     * exist. Loads the product through the catalog's product repository.
     */
    Optional<String> findStatusName(String tenantSchema, UUID productId);
}
