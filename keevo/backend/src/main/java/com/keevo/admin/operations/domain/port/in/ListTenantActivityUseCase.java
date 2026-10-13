package com.keevo.admin.operations.domain.port.in;

import com.keevo.admin.operations.domain.model.TenantActivityPage;

public interface ListTenantActivityUseCase {
    TenantActivityPage listTenantActivity(OperationsPeriodQuery query, Integer page, Integer pageSize);
}
