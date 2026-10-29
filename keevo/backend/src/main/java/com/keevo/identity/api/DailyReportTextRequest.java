package com.keevo.identity.api;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Store, sales window and header of a daily report text (Story 12.2e, {@link DailyReportTextPort}).
 *
 * @param windowStart must not be after {@code windowEnd} (an empty window, start = end, is allowed)
 * @param automatic whether the report is labelled as an automatic closure
 */
public record DailyReportTextRequest(UUID storeId, String storeName, Instant windowStart, Instant windowEnd,
                                     LocalDate reportDate, LocalTime closeTime, boolean automatic) {
    public DailyReportTextRequest {
        if (storeId == null || windowStart == null || windowEnd == null
                || reportDate == null || closeTime == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR,
                    "Store id, window, report date and close time are required");
        }
        if (storeName == null || storeName.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Store name is required");
        }
        if (windowStart.isAfter(windowEnd)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "Window start must not be after window end");
        }
    }
}
