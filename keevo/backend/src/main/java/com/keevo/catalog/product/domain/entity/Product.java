package com.keevo.catalog.product.domain.entity;

import com.keevo.catalog.product.domain.service.ProductKeys;
import com.keevo.shared.domain.model.Money;

import java.util.UUID;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Product domain entity
 * 
 * Domain validation:
 * - name non-empty
 * - SKU format KEV-[A-Z0-9]{6}
 * - description nullable
 * - price/buyPrice/transportCost as Money value objects (non-negative enforced by Money)
 * - archived default false
 * - status default ACTIVE
 * - minimumThreshold non-negative integer (Story 2.3)
 * - trackStock default true (Story 17.12): false = sold without stock (no check, no movement)
 * - barcode nullable, productKey derived from name + barcode (Story 20.1, {@link ProductKeys}), priceManual
 *   (true: price typed by a person), mergedIntoId (the product this one was merged into; written by the merge only)
 */
public class Product {
    private static final Pattern SKU_PATTERN = Pattern.compile("^KEV-[A-Z0-9]{6}$");
    
    private final UUID id;
    private final String name;
    private final String description;
    private final String sku;
    private final UUID categoryId;
    private final Money price;
    private final Money buyPrice;
    private final Money transportCost;
    private final Integer stockQuantity;
    private final Boolean archived;
    private final ProductStatus status;
    private final int minimumThreshold; // Story 2.3 — stock alert threshold
    private final boolean trackStock; // Story 17.12 — per-product stock tracking (default true)
    private final String barcode;      // Story 20.1 — free reference or barcode, null = none
    private final String productKey;   // Story 20.1 — ProductKeys.key(name, barcode), computed when they change
    private final boolean priceManual; // Story 20.1 — price typed by a person (false: automatic creation)
    private final UUID mergedIntoId;   // Story 20.1 — merge target, null when not merged
    private final Instant createdAt;
    private final Instant updatedAt;

    /**
     * Legacy constructor (backward compat) — minimumThreshold defaults to 0.
     */
    public Product(UUID id, String name, String description, String sku,
                  UUID categoryId, Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                  Boolean archived, ProductStatus status,
                  Instant createdAt, Instant updatedAt) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
             archived, status, 0, createdAt, updatedAt);
    }

    /**
     * Constructor with minimumThreshold (Story 2.3) — trackStock defaults to true.
     */
    public Product(UUID id, String name, String description, String sku,
                  UUID categoryId, Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                  Boolean archived, ProductStatus status, Integer minimumThreshold,
                  Instant createdAt, Instant updatedAt) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
             archived, status, minimumThreshold, true, createdAt, updatedAt);
    }

    /**
     * Constructor with trackStock (Story 17.12) — {@code null} means tracked. No barcode, price typed by a person,
     * not merged (Story 20.1 defaults).
     */
    public Product(UUID id, String name, String description, String sku,
                  UUID categoryId, Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                  Boolean archived, ProductStatus status, Integer minimumThreshold, Boolean trackStock,
                  Instant createdAt, Instant updatedAt) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
             archived, status, minimumThreshold, trackStock, null, true, null, createdAt, updatedAt);
    }

    /**
     * Full constructor for a new or renamed / re-coded product (Story 20.1): {@code barcode} trimmed, blank → none; the
     * product key is computed from the name and the barcode ({@link ProductKeys#key}).
     */
    public Product(UUID id, String name, String description, String sku,
                  UUID categoryId, Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                  Boolean archived, ProductStatus status, Integer minimumThreshold, Boolean trackStock,
                  String barcode, boolean priceManual, UUID mergedIntoId,
                  Instant createdAt, Instant updatedAt) {
        this(id, name, description, sku, categoryId, price, buyPrice, transportCost, stockQuantity,
             archived, status, minimumThreshold, trackStock, barcode, ProductKeys.key(name, barcode),
             priceManual, mergedIntoId, createdAt, updatedAt);
    }

    /**
     * Rehydration constructor (Story 20.1): the stored {@code productKey} is kept as is (no NFD / regex on every read;
     * {@code null} for a row the backfill has not keyed yet). Callers that change the name or the barcode use the
     * constructor above, which recomputes it.
     */
    public Product(UUID id, String name, String description, String sku,
                  UUID categoryId, Integer price, Integer buyPrice, Integer transportCost, Integer stockQuantity,
                  Boolean archived, ProductStatus status, Integer minimumThreshold, Boolean trackStock,
                  String barcode, String productKey, boolean priceManual, UUID mergedIntoId,
                  Instant createdAt, Instant updatedAt) {
        // Validate name
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Product name cannot be null or empty");
        }
        
        // Validate SKU
        if (sku == null) {
            throw new IllegalArgumentException("Product SKU cannot be null");
        }
        if (!SKU_PATTERN.matcher(sku).matches()) {
            throw new IllegalArgumentException("Product SKU must follow format KEV-[A-Z0-9]{6}");
        }
        
        // transportCost pre-check before Money wrapping (for backward compat. with Integer API)
        if (transportCost != null && transportCost < 0) {
            throw new IllegalArgumentException("Transport cost cannot be negative");
        }

        // minimumThreshold must be non-negative
        if (minimumThreshold != null && minimumThreshold < 0) {
            throw new IllegalArgumentException("Minimum threshold cannot be negative");
        }

        this.id = id;
        this.name = name.trim();
        this.description = description;
        this.sku = sku;
        this.categoryId = categoryId;
        this.price = new Money(price != null ? price : 0);
        this.buyPrice = new Money(buyPrice != null ? buyPrice : 0);
        this.transportCost = new Money(transportCost != null ? transportCost : 0);
        this.stockQuantity = stockQuantity != null ? stockQuantity : 0;
        this.archived = archived != null ? archived : false;
        this.status = status != null ? status : ProductStatus.ACTIVE;
        this.minimumThreshold = minimumThreshold != null ? minimumThreshold : 0;
        this.trackStock = trackStock == null || trackStock;
        this.barcode = barcode == null || barcode.isBlank() ? null : barcode.trim();
        this.productKey = productKey;
        this.priceManual = priceManual;
        this.mergedIntoId = mergedIntoId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    
    // Getters — expose both Money type and int value for convenience
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSku() { return sku; }
    public UUID getCategoryId() { return categoryId; }
    /** Returns price as Money value object */
    public Money getPrice() { return price; }
    /** Returns price integer value for persistence/DTOs */
    public int getPriceValue() { return price.value(); }
    /** Returns buyPrice as Money value object */
    public Money getBuyPrice() { return buyPrice; }
    /** Returns buyPrice integer value for persistence/DTOs */
    public int getBuyPriceValue() { return buyPrice.value(); }
    /** Returns transportCost as Money value object */
    public Money getTransportCost() { return transportCost; }
    /** Returns transportCost integer value for persistence/DTOs */
    public int getTransportCostValue() { return transportCost.value(); }
    public Integer getStockQuantity() { return stockQuantity; }
    public Boolean getArchived() { return archived; }
    public ProductStatus getStatus() { return status; }
    public int getMinimumThreshold() { return minimumThreshold; }
    /** Story 17.12 — whether the product's stock is tracked (false: sold without stock). */
    public boolean isTrackStock() { return trackStock; }
    /** Story 20.1 — free reference or barcode, {@code null} when none. */
    public String getBarcode() { return barcode; }
    /** Story 20.1 — deduplication key ({@code bc:…} / {@code nm:…}), derived from the name and the barcode. */
    public String getProductKey() { return productKey; }
    /** Story 20.1 — true when the price was typed by a person. */
    public boolean isPriceManual() { return priceManual; }
    /** Story 20.1 — the product this one was merged into, {@code null} when not merged. */
    public UUID getMergedIntoId() { return mergedIntoId; }
    /** Story 20.1 — merged into another product (archived, redirected). */
    public boolean isMerged() { return mergedIntoId != null; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Copy-with: returns a new Product with an updated minimumThreshold (Story 2.3).
     * Preserves all other fields; sets updatedAt to now.
     */
    public Product withMinimumThreshold(int newThreshold) {
        return new Product(id, name, description, sku, categoryId,
            getPriceValue(), getBuyPriceValue(), getTransportCostValue(), stockQuantity,
            archived, status, newThreshold, trackStock, barcode, productKey, priceManual, mergedIntoId, createdAt,
            Instant.now());
    }

    /**
     * Copy-with: returns a new Product with stock tracking on or off (Story 17.12).
     * Preserves all other fields; sets updatedAt to now.
     */
    public Product withTrackStock(boolean tracked) {
        return new Product(id, name, description, sku, categoryId,
            getPriceValue(), getBuyPriceValue(), getTransportCostValue(), stockQuantity,
            archived, status, minimumThreshold, tracked, barcode, productKey, priceManual, mergedIntoId, createdAt,
            Instant.now());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Product product = (Product) obj;
        return id != null ? id.equals(product.id) : product.id == null;
    }
    
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
    
    @Override
    public String toString() {
        return "Product{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", sku='" + sku + '\'' +
                ", price=" + price +
                ", archived=" + archived +
                ", status=" + status +
                '}';
    }
}