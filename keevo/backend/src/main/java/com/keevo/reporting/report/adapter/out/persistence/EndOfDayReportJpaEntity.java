package com.keevo.reporting.report.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * EndOfDayReportJpaEntity — JPA entity for the reports table.
 * Story 7.2 — Rapport End-of-Day
 *
 * <p>Stored in tenant schema: tenant_xxx.reports
 */
@Entity
@Table(name = "reports")
public class EndOfDayReportJpaEntity {

    @Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "store_id", nullable = false, columnDefinition = "UUID")
    private UUID storeId;

    @Column(name = "store_name", length = 255)
    private String storeName;

    @Column(name = "report_type", nullable = false, length = 30)
    private String reportType;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "delivery_status", nullable = false, length = 30)
    private String deliveryStatus;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "total_revenue", nullable = false)
    private int totalRevenue;

    @Column(name = "total_sales", nullable = false)
    private int totalSales;

    @Column(name = "is_automatic", nullable = false)
    private boolean isAutomatic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EndOfDayReportJpaEntity() {}

    public EndOfDayReportJpaEntity(UUID id, String tenantId, UUID storeId, String storeName,
                                    String reportType, LocalDate reportDate, String content,
                                    String deliveryStatus, int deliveryAttempts, Instant lastAttemptAt,
                                    int totalRevenue, int totalSales, boolean isAutomatic, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.storeId = storeId;
        this.storeName = storeName;
        this.reportType = reportType;
        this.reportDate = reportDate;
        this.content = content;
        this.deliveryStatus = deliveryStatus;
        this.deliveryAttempts = deliveryAttempts;
        this.lastAttemptAt = lastAttemptAt;
        this.totalRevenue = totalRevenue;
        this.totalSales = totalSales;
        this.isAutomatic = isAutomatic;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getStoreId() { return storeId; }
    public String getStoreName() { return storeName; }
    public String getReportType() { return reportType; }
    public LocalDate getReportDate() { return reportDate; }
    public String getContent() { return content; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public int getTotalRevenue() { return totalRevenue; }
    public int getTotalSales() { return totalSales; }
    public boolean isAutomatic() { return isAutomatic; }
    public Instant getCreatedAt() { return createdAt; }
}
