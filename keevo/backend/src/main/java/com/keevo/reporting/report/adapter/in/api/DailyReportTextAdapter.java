package com.keevo.reporting.report.adapter.in.api;

import com.keevo.identity.api.DailyReportTextPort;
import com.keevo.identity.api.DailyReportTextRequest;
import com.keevo.reporting.report.application.service.DailyReportFormatter;
import com.keevo.reporting.report.application.service.EndOfDayReportBuilder;
import com.keevo.reporting.report.domain.model.EndOfDayReportData;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import org.springframework.stereotype.Component;

/**
 * Implements {@code identity.api.DailyReportTextPort} (Story 12.2e) with the same builder and
 * formatter as the real daily report. No transaction of its own (the builder's reads are
 * {@code readOnly} and join the caller's transaction).
 *
 * <p>Tenant convention: the builder queries the current tenant schema, so the explicit
 * {@code tenantSchema} must be that schema; a mismatch fails fast ({@link IllegalStateException}).
 */
@Component
class DailyReportTextAdapter implements DailyReportTextPort {

    private final EndOfDayReportBuilder reportBuilder;
    private final DailyReportFormatter formatter;

    DailyReportTextAdapter(EndOfDayReportBuilder reportBuilder, DailyReportFormatter formatter) {
        this.reportBuilder = reportBuilder;
        this.formatter = formatter;
    }

    @Override
    public String dailyReportText(String tenantSchema, DailyReportTextRequest request) {
        TenantContext.requireCurrent(tenantSchema);
        EndOfDayReportData data = reportBuilder.build(
                request.storeId(), request.windowStart(), request.windowEnd(),
                request.storeName(), request.reportDate(), request.closeTime(), request.automatic());
        return formatter.format(data);
    }
}
