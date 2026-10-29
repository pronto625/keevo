package com.keevo.identity.api;

import java.util.UUID;

/**
 * Employee lifecycle commands for other modules (Story 12.2d — the sync push). Each call runs the
 * identity use case (plan-limit checks and events) exactly as the REST endpoints do. The use cases
 * are {@code @Transactional} REQUIRED: a call joins the caller's transaction (opening one only when
 * there is none).
 */
public interface EmployeeCommandPort {

    /** @return the new employee record id */
    UUID create(String tenantSchema, CreateEmployeeInput input);

    void reassignStore(String tenantSchema, UUID actorId, UUID employeeId, UUID newStoreId);

    void deactivate(String tenantSchema, UUID actorId, UUID employeeId);

    void reactivate(String tenantSchema, UUID actorId, UUID employeeId);
}
