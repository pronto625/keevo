package com.keevo.admin.operations.domain.port.in;

import com.keevo.admin.operations.domain.model.InactiveTenant;

import java.util.List;

public interface ListInactiveTenantsUseCase {
    List<InactiveTenant> listInactiveTenants();
}
