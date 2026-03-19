package com.keevo.commerce.sale.domain;

import com.keevo.commerce.sale.domain.model.DayClosedEvent;
import com.keevo.commerce.sale.domain.model.DayClosureSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD RED tests for DayClosedEvent domain event (Java record).
 * Story 4.4 — Clôture Journalière & Historique des Ventes
 *
 * This event is published when a day is closed (manual or automatic).
 * Listeners: DayClosureWhatsAppListener, AuditEventListener
 */
class DayClosedEventTest {

    @Test
    void DayClosedEvent_setsAllRequiredFields() {
        // Given
        UUID closureId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String tenantId = "kv_test";
        Instant occurredAt = Instant.now();

        var summary = new DayClosureSummary(
                8,          // totalSales
                120000,     // totalRevenue
                UUID.randomUUID().toString(),
                "Samsung Galaxy S24",
                5,          // topProductQty
                70000,      // cashAmount
                50000,      // momoAmount
                1,          // pendingSalesCount
                8000        // pendingSalesTotal
        );

        // When
        var event = new DayClosedEvent(
                closureId,
                storeId,
                actorId,
                summary,
                false,      // isAutomatic
                tenantId,
                occurredAt
        );

        // Then
        assertThat(event.closureId()).isEqualTo(closureId);
        assertThat(event.storeId()).isEqualTo(storeId);
        assertThat(event.actorId()).isEqualTo(actorId);
        assertThat(event.summary()).isEqualTo(summary);
        assertThat(event.isAutomatic()).isFalse();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void DayClosedEvent_isAutomatic_falseForManual() {
        // Given - manual closure by employee
        var summary = new DayClosureSummary(5, 50000, null, null, 0, 50000, 0, 0, 0);
        var event = new DayClosedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                summary,
                false,      // manual closure
                "kv_test",
                Instant.now()
        );

        // Then
        assertThat(event.isAutomatic()).isFalse();
    }

    @Test
    void DayClosedEvent_isAutomatic_trueForScheduler() {
        // Given - automatic closure by scheduler at 20h00
        var summary = new DayClosureSummary(3, 25000, null, null, 0, 25000, 0, 0, 0);
        var event = new DayClosedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),  // SYSTEM_UUID for scheduler
                summary,
                true,       // automatic closure
                "kv_test",
                Instant.now()
        );

        // Then
        assertThat(event.isAutomatic()).isTrue();
    }

    @Test
    void DayClosedEvent_includeSummaryWithPendingSales() {
        // Given - closure with pending sales (AC2: 🔶 line in report)
        var summary = new DayClosureSummary(
                7,
                85000,
                "prod-123",
                "AirPods Pro",
                4,
                60000,
                25000,
                3,      // 3 pending sales
                22000   // 22000 XAF pending
        );

        var event = new DayClosedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                summary,
                false,
                "kv_test",
                Instant.now()
        );

        // Then - summary contains pending sales info for WhatsApp report
        assertThat(event.summary().pendingSalesCount()).isEqualTo(3);
        assertThat(event.summary().pendingSalesTotal()).isEqualTo(22000);
    }

    @Test
    void DayClosedEvent_record_equality() {
        // Given
        UUID closureId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        String tenantId = "kv_test";
        Instant occurredAt = Instant.parse("2026-03-19T18:00:00Z");
        var summary = new DayClosureSummary(5, 50000, null, null, 0, 50000, 0, 0, 0);

        var event1 = new DayClosedEvent(closureId, storeId, actorId, summary, false, tenantId, occurredAt);
        var event2 = new DayClosedEvent(closureId, storeId, actorId, summary, false, tenantId, occurredAt);

        // Then
        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
    }
}
