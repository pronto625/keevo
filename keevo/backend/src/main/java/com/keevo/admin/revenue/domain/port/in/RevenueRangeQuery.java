package com.keevo.admin.revenue.domain.port.in;

/**
 * Raw range selection as received from the API (Story 9-3): inclusive ISO dates; both null → current month.
 */
public record RevenueRangeQuery(String from, String to) {}
