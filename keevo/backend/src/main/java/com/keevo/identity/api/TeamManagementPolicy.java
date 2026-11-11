package com.keevo.identity.api;

import java.util.UUID;

/**
 * Story 19.14 (routed from 19.5b) — who may manage which team members, the SAME rule for the REST team endpoints and
 * the sync push ({@code CREATE_EMPLOYEE}, {@code DEACTIVATE_EMPLOYEE}, {@code REACTIVATE_EMPLOYEE}):
 * the OWNER manages everyone; a store member holding {@code TEAM_MANAGE} (« Gérer l'équipe de sa boutique ») manages
 * only the EMPLOYEE / VIEWER members of his own store who do not hold that permission themselves. Evaluated on the
 * authenticated actor of the current request; every refusal is a {@code DomainException} {@code FORBIDDEN}.
 */
public interface TeamManagementPolicy {

    /** Whether the actor acts as a delegated team manager (not the owner): his deactivations are recorded as such. */
    boolean actsAsDelegate();

    /** The actor may create a member with {@code role} ({@code null} = EMPLOYEE) in {@code storeId}. */
    void requireCreatable(UUID storeId, String role);

    /** The actor may manage (deactivate, update, see the devices of) the member {@code employeeId}. */
    void requireManageable(UUID employeeId);

    /** The actor may reactivate the member {@code employeeId} (a delegate: only a member a delegate deactivated). */
    void requireReactivatable(UUID employeeId);
}
