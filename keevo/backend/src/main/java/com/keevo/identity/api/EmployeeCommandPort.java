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

    /**
     * Story 19.14 — as {@link #deactivate(String, UUID, UUID)}, recording whether a delegated team manager made it (he
     * may then reactivate the member; one the owner deactivated stays the owner's).
     */
    default void deactivate(String tenantSchema, UUID actorId, UUID employeeId, boolean byManager) {
        deactivate(tenantSchema, actorId, employeeId);
    }

    void reactivate(String tenantSchema, UUID actorId, UUID employeeId);

    /** Story 20.13 — adds a member (owner creation rules) with an optional role. */
    CreatedMember createMember(String tenantSchema, CreateMemberInput input);

    /** Story 20.13 — updates a member's name / phone / store, then its role when given. */
    void updateMember(String tenantSchema, UpdateMemberInput input);

    /**
     * Story 20.13 — removes a member from the account: the deactivation rules (sessions of this account revoked),
     * then the membership is deleted; the employee record stays (inactive) for the history. Never the owner.
     */
    void remove(String tenantSchema, UUID actorId, UUID employeeId);
}
