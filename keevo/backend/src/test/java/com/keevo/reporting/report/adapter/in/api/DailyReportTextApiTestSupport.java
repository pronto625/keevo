package com.keevo.reporting.report.adapter.in.api;

import com.keevo.identity.api.DailyReportTextPort;
import com.keevo.reporting.report.application.service.DailyReportFormatter;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;

/**
 * Test-only access to the package-private reporting implementation of
 * {@code identity.api.DailyReportTextPort} (Story 12.2e), for tests of other packages that drive the
 * real adapter over a mocked builder / formatter.
 */
public final class DailyReportTextApiTestSupport {

    private DailyReportTextApiTestSupport() {}

    public static DailyReportTextPort port(EndOfDayReportBuilder builder, DailyReportFormatter formatter) {
        return new DailyReportTextAdapter(builder, formatter);
    }
}
