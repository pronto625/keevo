package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.EndOfDayReport;
import com.keevo.shared.capability.notification.PlatformMessageKind;
import com.keevo.shared.capability.notification.PlatformWhatsAppMessage;

/**
 * Story 19.9c — the platform WhatsApp message of a stored report: its kind follows the report type (daily, combined
 * daily of every store, weekly); the detail and the free text are the report content, unchanged.
 */
final class ReportWhatsAppMessages {

    private ReportWhatsAppMessages() {
    }

    static PlatformWhatsAppMessage of(EndOfDayReport report) {
        String content = report.getContent();
        return PlatformWhatsAppMessage.detail(kind(report), content == null ? "" : content);
    }

    static PlatformMessageKind kind(EndOfDayReport report) {
        if (report.getReportType() == null) return PlatformMessageKind.DAILY_REPORT;
        return switch (report.getReportType()) {
            case DAILY -> PlatformMessageKind.DAILY_REPORT;
            case DAILY_COMBINED -> PlatformMessageKind.MULTI_STORE_REPORT;
            case WEEKLY -> PlatformMessageKind.WEEKLY_REPORT;
        };
    }
}
