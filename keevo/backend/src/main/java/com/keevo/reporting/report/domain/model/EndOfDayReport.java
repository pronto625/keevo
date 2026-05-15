package com.keevo.reporting.report.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * EndOfDayReport — Aggregate root for a generated end-of-day report.
 * Story 7.2 — Rapport End-of-Day
 *
 * <p>Lifecycle: PENDING → SENT (success) or PENDING → FAILED → IN_APP_ONLY (max retries).
 */
public class EndOfDayReport {

    private final UUID id;
    private final String tenantId;
    private final UUID storeId;
    private final String storeName;
    private final UUID actorId;       // null for auto-scheduled; employee/owner UUID when manually triggered
    private final String actorName;    // nullable; resolved from EmployeeRepository (Story 7.6)
    private final ReportType reportType;
    private final LocalDate reportDate;
    private final String content;
    private DeliveryStatus deliveryStatus;
    private int deliveryAttempts;
    private Instant lastAttemptAt;
    private final int totalRevenue;
    private final int totalSales;
    private final boolean isAutomatic;
    private final Instant createdAt;

    public EndOfDayReport(UUID id, String tenantId, UUID storeId, String storeName,
                          UUID actorId, String actorName,
                          ReportType reportType, LocalDate reportDate, String content,
                          DeliveryStatus deliveryStatus, int deliveryAttempts,
                          Instant lastAttemptAt, int totalRevenue, int totalSales,
                          boolean isAutomatic, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.storeId = storeId;
        this.storeName = storeName;
        this.actorId = actorId;
        this.actorName = actorName;
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

    // ── Factory ────────────────────────────────────────────────────────────────

    public static EndOfDayReport createNew(String tenantId, UUID storeId, String storeName,
                                           UUID actorId, String actorName,
                                           ReportType reportType, LocalDate reportDate,
                                           String content, int totalRevenue, int totalSales,
                                           boolean isAutomatic) {
        return new EndOfDayReport(
                UUID.randomUUID(), tenantId, storeId, storeName, actorId, actorName, reportType,
                reportDate, content, DeliveryStatus.PENDING, 0, null,
                totalRevenue, totalSales, isAutomatic, Instant.now()
        );
    }

    // ── Behaviour ────────────────────────────────────────────────────────────

    public void incrementAttempt() {
        this.deliveryAttempts++;
        this.lastAttemptAt = Instant.now();
    }

    public void markSent() {
        this.deliveryStatus = DeliveryStatus.SENT;
        this.lastAttemptAt = Instant.now();
    }

    public void markFailed() {
        this.deliveryStatus = DeliveryStatus.FAILED;
        this.lastAttemptAt = Instant.now();
    }

    public void markInAppOnly() {
        this.deliveryStatus = DeliveryStatus.IN_APP_ONLY;
        this.lastAttemptAt = Instant.now();
    }

    public void resetForResend() {
        this.deliveryAttempts = 0;
        this.deliveryStatus = DeliveryStatus.PENDING;
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public UUID getStoreId() { return storeId; }
    public String getStoreName() { return storeName; }
    public UUID getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public ReportType getReportType() { return reportType; }
    public LocalDate getReportDate() { return reportDate; }
    public String getContent() { return content; }
    public DeliveryStatus getDeliveryStatus() { return deliveryStatus; }
    public int getDeliveryAttempts() { return deliveryAttempts; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public int getTotalRevenue() { return totalRevenue; }
    public int getTotalSales() { return totalSales; }
    public boolean isAutomatic() { return isAutomatic; }
    public Instant getCreatedAt() { return createdAt; }
}
