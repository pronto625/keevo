package com.keevo.admin.operations.domain.port.in;

import com.keevo.admin.operations.domain.model.OperationsOverview;

public interface GetOperationsOverviewUseCase {
    OperationsOverview getOverview(OperationsPeriodQuery query);
}
