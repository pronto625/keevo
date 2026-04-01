package com.keevo.reporting.report.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EndOfDayReportTest — Story 7.2, Task 1.1
 * TDD RED -> GREEN: domain model behaviour tests.
 */
class EndOfDayReportTest {

    private EndOfDayReport sample() {
        return EndOfDayReport.createNew(
                "kv_abc123", UUID.randomUUID(), "Boutique Cosmos",
                null,
                ReportType.DAILY, LocalDate.now(),
                "📊 Rapport du jour…", 75000, 12, false
        );
    }

    @Test
    void endOfDayReport_create_setsAllFields() {
        var r = sample();
        assertThat(r.getId()).isNotNull();
        assertThat(r.getTenantId()).isEqualTo("kv_abc123");
        assertThat(r.getStoreName()).isEqualTo("Boutique Cosmos");
        assertThat(r.getReportType()).isEqualTo(ReportType.DAILY);
        assertThat(r.getTotalRevenue()).isEqualTo(75000);
        assertThat(r.getTotalSales()).isEqualTo(12);
        assertThat(r.isAutomatic()).isFalse();
        assertThat(r.getContent()).isEqualTo("📊 Rapport du jour…");
        assertThat(r.getCreatedAt()).isNotNull();
    }

    @Test
    void endOfDayReport_deliveryStatus_defaultsPending() {
        assertThat(sample().getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void endOfDayReport_deliveryAttempts_defaultsZero() {
        assertThat(sample().getDeliveryAttempts()).isZero();
    }

    @Test
    void endOfDayReport_incrementAttempt_updatesCountAndTimestamp() {
        var r = sample();
        assertThat(r.getLastAttemptAt()).isNull();
        r.incrementAttempt();
        assertThat(r.getDeliveryAttempts()).isEqualTo(1);
        assertThat(r.getLastAttemptAt()).isNotNull();
    }

    @Test
    void endOfDayReport_markSent_setsStatusToSent() {
        var r = sample();
        r.markSent();
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(r.getLastAttemptAt()).isNotNull();
    }

    @Test
    void endOfDayReport_markFailed_setsStatusToFailed() {
        var r = sample();
        r.markFailed();
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void endOfDayReport_markInAppOnly_afterMaxAttempts_setsInAppOnly() {
        var r = sample();
        r.incrementAttempt();
        r.incrementAttempt();
        r.incrementAttempt();
        r.markInAppOnly();
        assertThat(r.getDeliveryStatus()).isEqualTo(DeliveryStatus.IN_APP_ONLY);
        assertThat(r.getDeliveryAttempts()).isEqualTo(3);
    }
}
