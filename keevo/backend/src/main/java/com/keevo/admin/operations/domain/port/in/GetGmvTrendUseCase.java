package com.keevo.admin.operations.domain.port.in;

import com.keevo.admin.operations.domain.model.GmvTrendPoint;

import java.util.List;

public interface GetGmvTrendUseCase {
    List<GmvTrendPoint> getTrend(OperationsPeriodQuery query);
}
