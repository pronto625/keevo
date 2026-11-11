package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.reporting.report.domain.model.ReportType;
import com.keevo.shared.capability.notification.PlatformMessageKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 19.9c — a stored report's platform WhatsApp kind follows its type; the text is the content. */
@DisplayName("ReportWhatsAppMessages (Story 19.9c)")
class ReportWhatsAppMessagesTest {

    private static EndOfDayReport report(ReportType type, String content) {
        return EndOfDayReport.createNew("kv_a", UUID.randomUUID(), "Boutique", null, null, type, LocalDate.now(),
                content, 0, 0, true);
    }

    @Test
    @DisplayName("DAILY → DAILY_REPORT, DAILY_COMBINED → MULTI_STORE_REPORT, WEEKLY → WEEKLY_REPORT, none → DAILY")
    void kinds() {
        assertThat(ReportWhatsAppMessages.kind(report(ReportType.DAILY, "x"))).isEqualTo(PlatformMessageKind.DAILY_REPORT);
        assertThat(ReportWhatsAppMessages.kind(report(ReportType.DAILY_COMBINED, "x")))
                .isEqualTo(PlatformMessageKind.MULTI_STORE_REPORT);
        assertThat(ReportWhatsAppMessages.kind(report(ReportType.WEEKLY, "x")))
                .isEqualTo(PlatformMessageKind.WEEKLY_REPORT);
        assertThat(ReportWhatsAppMessages.kind(report(null, "x"))).isEqualTo(PlatformMessageKind.DAILY_REPORT);
    }

    @Test
    @DisplayName("the content is both the free text and the detail parameter")
    void content() {
        var message = ReportWhatsAppMessages.of(report(ReportType.WEEKLY, "Semaine : 84 ventes"));
        assertThat(message.text()).isEqualTo("Semaine : 84 ventes");
        assertThat(message.parameters()).containsExactly("Semaine : 84 ventes");
    }
}
