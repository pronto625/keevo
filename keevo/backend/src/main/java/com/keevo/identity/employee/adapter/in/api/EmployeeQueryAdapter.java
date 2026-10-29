package com.keevo.identity.employee.adapter.in.api;

import com.keevo.identity.api.EmployeeQueryPort;
import com.keevo.identity.api.EmployeeView;
import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link EmployeeQueryPort} over the tenant-bound {@link EmployeeRepository} port
 * (Story 12.2d); the given schema must be the current tenant context ({@link TenantContext#requireCurrent}).
 */
@Component
class EmployeeQueryAdapter implements EmployeeQueryPort {

    private final EmployeeRepository employeeRepository;

    EmployeeQueryAdapter(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    @Override
    public Optional<EmployeeView> findById(String tenantSchema, UUID employeeId) {
        TenantContext.requireCurrent(tenantSchema);
        return employeeRepository.findById(employeeId).map(EmployeeViews::of);
    }

    @Override
    public Optional<EmployeeView> findByUserId(String tenantSchema, UUID userId) {
        TenantContext.requireCurrent(tenantSchema);
        return employeeRepository.findByUserId(userId).map(EmployeeViews::of);
    }

    @Override
    public List<EmployeeView> findActiveByStoreId(String tenantSchema, UUID storeId) {
        TenantContext.requireCurrent(tenantSchema);
        // EmployeeRepository#findByStoreId already keeps ACTIVE employees only
        return employeeRepository.findByStoreId(storeId).stream().map(EmployeeViews::of).toList();
    }
}
