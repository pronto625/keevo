package com.keevo.reporting.report.application.service;

import com.keevo.reporting.report.domain.model.WeeklyReportData;

/**
 * WeeklyDataHolder — ThreadLocal bridge between collectData() and formatContent().
 * Story 7.3 — Rapport Hebdomadaire Automatique.
 *
 * <p>AbstractReportGenerator.collectData() returns EndOfDayReportData (for persistence).
 * WeeklyReportGenerator.collectData() builds a full WeeklyReportData, stores it here,
 * then returns a minimal EndOfDayReportData for the persistence step.
 * formatContent() reads from here to produce the WhatsApp-formatted weekly report.
 *
 * <p>Always cleared in a try/finally in WeeklyReportGenerator.generateWeeklyReport()
 * to prevent ThreadLocal leaks between HTTP requests.
 */
public final class WeeklyDataHolder {

    private static final ThreadLocal<WeeklyReportData> HOLDER = new ThreadLocal<>();

    private WeeklyDataHolder() {}

    public static void set(WeeklyReportData data) {
        HOLDER.set(data);
    }

    public static WeeklyReportData get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
