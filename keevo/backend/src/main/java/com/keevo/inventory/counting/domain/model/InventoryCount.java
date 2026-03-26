package com.keevo.inventory.counting.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * InventoryCount — count row for one product (or variant) in an inventory session.
 *
 * <p>Pure Java — no framework dependencies.
 */
public class InventoryCount {

    private final UUID id;
    private final UUID sessionId;
    private final UUID productId;
    private final UUID variantId;       // null for simple products
    private final String productName;
    private final String variantLabel;  // null if no variant
    private final int theoretical;      // snapshot from stock_levels
    private Integer physical;           // null = not yet counted
    private Instant countedAt;
    private UUID countedBy;
    private Instant updatedAt;

    public InventoryCount(UUID id, UUID sessionId, UUID productId, UUID variantId,
                          String productName, String variantLabel,
                          int theoretical, Integer physical,
                          Instant countedAt, UUID countedBy, Instant updatedAt) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(productName, "productName must not be null");

        this.id = id != null ? id : UUID.randomUUID();
        this.sessionId = sessionId;
        this.productId = productId;
        this.variantId = variantId;
        this.productName = productName;
        this.variantLabel = variantLabel;
        this.theoretical = theoretical;
        this.physical = physical;
        this.countedAt = countedAt;
        this.countedBy = countedBy;
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public static InventoryCount create(UUID sessionId, UUID productId, UUID variantId,
                                        String productName, String variantLabel,
                                        int theoretical, int physical, UUID actorId) {
        return new InventoryCount(
                UUID.randomUUID(), sessionId, productId, variantId,
                productName, variantLabel, theoretical, physical,
                Instant.now(), actorId, Instant.now());
    }

    public int getEcart() {
        if (physical == null) throw new IllegalStateException("Product not counted");
        return physical - theoretical;
    }

    public boolean isCounted() {
        return physical != null;
    }

    public InventoryCount withPhysical(int newPhysical, UUID actorId) {
        return new InventoryCount(
                id, sessionId, productId, variantId,
                productName, variantLabel, theoretical, newPhysical,
                Instant.now(), actorId, Instant.now());
    }

    // ── Getters ───────────────────────────────────────────────────

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public UUID getProductId() { return productId; }
    public UUID getVariantId() { return variantId; }
    public String getProductName() { return productName; }
    public String getVariantLabel() { return variantLabel; }
    public int getTheoretical() { return theoretical; }
    public Integer getPhysical() { return physical; }
    public Instant getCountedAt() { return countedAt; }
    public UUID getCountedBy() { return countedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
