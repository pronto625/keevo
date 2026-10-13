package com.keevo.admin.analytics.domain.port.in;

import com.keevo.admin.analytics.domain.model.RetentionReport;

public interface GetRetentionUseCase {
    /** @param weeks number of retention columns (null/blank → 8, 1..12) */
    RetentionReport getRetention(String weeks);
}
