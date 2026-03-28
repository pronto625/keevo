package com.keevo.inventory.counting.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * InventoryGapReport — complete gap analysis report (value object).
 *
 * <p>Pure Java — no framework deps.
 * Story 6.3 — Gap Analysis Report.
 */
public class InventoryGapReport {

    private final UUID sessionId;
    private final UUID storeId;
    private final String storeName;
    private final InventoryScope scope;
    private final InventoryGapSummary summary;
    private final List<InventoryGapRow> concordantRows;
    private final List<InventoryGapRow> surplusRows;
    private final List<InventoryGapRow> shortageRows;
    private final UUID generatedBy;
    private final Instant generatedAt;
    private final String sessionStatus;

    public InventoryGapReport(UUID sessionId, UUID storeId, String storeName,
                              InventoryScope scope, InventoryGapSummary summary,
                              List<InventoryGapRow> concordantRows,
                              List<InventoryGapRow> surplusRows,
                              List<InventoryGapRow> shortageRows,
                              UUID generatedBy, Instant generatedAt,
                              String sessionStatus) {
        this.sessionId = sessionId;
        this.storeId = storeId;
        this.storeName = storeName;
        this.scope = scope;
        this.summary = summary;
        this.concordantRows = List.copyOf(concordantRows);
        this.surplusRows = List.copyOf(surplusRows);
        this.shortageRows = List.copyOf(shortageRows);
        this.generatedBy = generatedBy;
        this.generatedAt = generatedAt;
        this.sessionStatus = sessionStatus;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getStoreId() { return storeId; }
    public String getStoreName() { return storeName; }
    public InventoryScope getScope() { return scope; }
    public InventoryGapSummary getSummary() { return summary; }
    public List<InventoryGapRow> getConcordantRows() { return concordantRows; }
    public List<InventoryGapRow> getSurplusRows() { return surplusRows; }
    public List<InventoryGapRow> getShortageRows() { return shortageRows; }
    public UUID getGeneratedBy() { return generatedBy; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getSessionStatus() { return sessionStatus; }
}
