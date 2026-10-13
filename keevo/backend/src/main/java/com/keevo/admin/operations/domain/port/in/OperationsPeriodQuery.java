package com.keevo.admin.operations.domain.port.in;

/**
 * Raw period selection as received from the API (Story 9.7).
 *
 * @param period {@code TODAY | 7D | 30D | 90D | CUSTOM} (null/blank → 7D)
 * @param from   inclusive ISO date, required for CUSTOM
 * @param to     inclusive ISO date, required for CUSTOM
 */
public record OperationsPeriodQuery(String period, String from, String to) {}
