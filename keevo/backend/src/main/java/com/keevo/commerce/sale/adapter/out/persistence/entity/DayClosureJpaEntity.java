package com.keevo.commerce.sale.adapter.out.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * DayClosureJpaEntity — JPA entity for the day_closures table.
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * <p>Stored in tenant schema: tenant_xxx.day_closures
 */
@Entity
@Table(name = "day_closures")
public class DayClosureJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "UUID")
    private UUID storeId;

    @Column(name = "actor_id", columnDefinition = "UUID")
    private UUID actorId;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(name = "closure_date", nullable = false)
    private LocalDate closureDate;

    @Column(name = "is_automatic", nullable = false)
    private boolean isAutomatic;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    // ── Summary fields (denormalized for query performance) ──────────────────

    @Column(name = "total_sales", nullable = false)
    private int totalSales;

    @Column(name = "total_revenue", nullable = false)
    private int totalRevenue;

    @Column(name = "top_product_id", length = 64)
    private String topProductId;

    @Column(name = "top_product_name", length = 255)
    private String topProductName;

    @Column(name = "top_product_qty", nullable = false)
    private int topProductQty;

    @Column(name = "cash_amount", nullable = false)
    private int cashAmount;

    @Column(name = "momo_amount", nullable = false)
    private int momoAmount;

    @Column(name = "pending_sales_count", nullable = false)
    private int pendingSalesCount;

    @Column(name = "pending_sales_total", nullable = false)
    private int pendingSalesTotal;

    public DayClosureJpaEntity() {}

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getStoreId() { return storeId; }
    public void setStoreId(UUID storeId) { this.storeId = storeId; }

    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public LocalDate getClosureDate() { return closureDate; }
    public void setClosureDate(LocalDate closureDate) { this.closureDate = closureDate; }

    public boolean isAutomatic() { return isAutomatic; }
    public void setAutomatic(boolean automatic) { isAutomatic = automatic; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public int getTotalSales() { return totalSales; }
    public void setTotalSales(int totalSales) { this.totalSales = totalSales; }

    public int getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(int totalRevenue) { this.totalRevenue = totalRevenue; }

    public String getTopProductId() { return topProductId; }
    public void setTopProductId(String topProductId) { this.topProductId = topProductId; }

    public String getTopProductName() { return topProductName; }
    public void setTopProductName(String topProductName) { this.topProductName = topProductName; }

    public int getTopProductQty() { return topProductQty; }
    public void setTopProductQty(int topProductQty) { this.topProductQty = topProductQty; }

    public int getCashAmount() { return cashAmount; }
    public void setCashAmount(int cashAmount) { this.cashAmount = cashAmount; }

    public int getMomoAmount() { return momoAmount; }
    public void setMomoAmount(int momoAmount) { this.momoAmount = momoAmount; }

    public int getPendingSalesCount() { return pendingSalesCount; }
    public void setPendingSalesCount(int pendingSalesCount) { this.pendingSalesCount = pendingSalesCount; }

    public int getPendingSalesTotal() { return pendingSalesTotal; }
    public void setPendingSalesTotal(int pendingSalesTotal) { this.pendingSalesTotal = pendingSalesTotal; }
}
