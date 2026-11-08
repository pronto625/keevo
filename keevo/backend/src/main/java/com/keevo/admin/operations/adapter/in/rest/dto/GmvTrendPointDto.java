package com.keevo.admin.operations.adapter.in.rest.dto;

import com.keevo.admin.operations.domain.model.GmvTrendPoint;

/** Contract with keevo/dashboard/src/types/operations.ts (GmvTrendPoint). {@code date} is an ISO date.
 * Story 20.13: the count is the number of revenue documents (POS sales + counted invoices, 20.9 source),
 * not sales only — label it « Documents », never « Ventes ».
 */
public record GmvTrendPointDto(String date, long gmv, long orderCount) {
    public static GmvTrendPointDto from(GmvTrendPoint m) {
        return new GmvTrendPointDto(m.date().toString(), m.gmv(), m.orderCount());
    }
}
