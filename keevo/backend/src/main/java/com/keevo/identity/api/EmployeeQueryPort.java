package com.keevo.identity.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read access to a tenant's employees for other modules (Story 12.2d). */
public interface EmployeeQueryPort {

    /** By employee record id. */
    Optional<EmployeeView> findById(String tenantSchema, UUID employeeId);

    /** By platform user id. */
    Optional<EmployeeView> findByUserId(String tenantSchema, UUID userId);

    /** The ACTIVE employees assigned to the store. */
    List<EmployeeView> findActiveByStoreId(String tenantSchema, UUID storeId);
}
