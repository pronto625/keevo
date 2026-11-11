package com.keevo.identity.employee.adapter.in.api;

import com.keevo.identity.api.TeamManagementPolicy;
import com.keevo.identity.employee.adapter.in.rest.EmployeeTeamScope;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Story 19.14 — implements {@link TeamManagementPolicy} with the REST team rule ({@link EmployeeTeamScope}), so the
 * sync push and the REST endpoints decide alike. No state, no transaction of its own.
 */
@Component
class TeamManagementPolicyAdapter implements TeamManagementPolicy {

    private final EmployeeTeamScope teamScope;

    TeamManagementPolicyAdapter(EmployeeTeamScope teamScope) {
        this.teamScope = teamScope;
    }

    @Override
    public boolean actsAsDelegate() {
        return teamScope.actsAsDelegate();
    }

    @Override
    public void requireCreatable(UUID storeId, String role) {
        teamScope.requireCreatable(storeId, role);
    }

    @Override
    public void requireManageable(UUID employeeId) {
        teamScope.requireManageable(employeeId);
    }

    @Override
    public void requireReactivatable(UUID employeeId) {
        teamScope.requireReactivatable(employeeId);
    }
}
