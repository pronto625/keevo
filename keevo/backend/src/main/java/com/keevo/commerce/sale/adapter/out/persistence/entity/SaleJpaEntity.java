package com.keevo.commerce.sale.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "sales")
public class SaleJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "UUID")
    private UUID storeId;

    @Column(name = "employee_id", nullable = false, columnDefinition = "UUID")
    private UUID employeeId;

    @Column(name = "client_id", columnDefinition = "UUID")
    private UUID clientId;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Column(name = "discount_amount", nullable = false)
    private int discountAmount = 0;

    @Column(name = "payment_mode", nullable = false, length = 30)
    private String paymentMode;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Delta pull cursor for sales: must move on every write so other devices get the change.
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Story v1s-13-10: optimistic lock. Primitive long so Hibernate still inserts on merge of an
    // assigned-id entity (same as StockLevelJpaEntity). Bulk JPQL status updates bump it explicitly.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<SaleItemJpaEntity> items = new ArrayList<>();

    public SaleJpaEntity() {}

    // Hibernate writes the column explicitly, so the DB default never applies.
    // updated_at is the pull cursor for sales: it must move on every write.
    @PrePersist
    void touchUpdatedAtOnInsert() {
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    void touchUpdatedAtOnUpdate() {
        updatedAt = Instant.now();
    }

    // Getters & setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getStoreId() { return storeId; }
    public void setStoreId(UUID storeId) { this.storeId = storeId; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID employeeId) { this.employeeId = employeeId; }
    public UUID getClientId() { return clientId; }
    public void setClientId(UUID clientId) { this.clientId = clientId; }
    public int getTotalAmount() { return totalAmount; }
    public void setTotalAmount(int totalAmount) { this.totalAmount = totalAmount; }
    public int getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(int discountAmount) { this.discountAmount = discountAmount; }
    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public List<SaleItemJpaEntity> getItems() { return items; }
    public void setItems(List<SaleItemJpaEntity> items) { this.items = items; }
}
