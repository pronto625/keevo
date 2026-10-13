package com.keevo.admin.revenue.domain.port.in;

import com.keevo.admin.revenue.domain.model.ChurnReport;

public interface GetChurnReportUseCase {
    /** @param month {@code YYYY-MM}; null/blank → current month */
    ChurnReport getChurn(String month);
}
