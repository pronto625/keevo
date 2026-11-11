package com.keevo.identity.role.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.auth.domain.port.out.TenantRepository;
import com.keevo.identity.auth.domain.port.out.UserMembershipRepository;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleCommand;
import com.keevo.identity.employee.domain.port.in.ChangeEmployeeRoleUseCase;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.identity.role.domain.model.CustomRole;
import com.keevo.identity.role.domain.port.out.CustomRoleStore;
import com.keevo.shared.application.port.AuditPort;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.application.port.TransactionLockPort;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.access.AccessStateChangePublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Story 19.14 — the owner gives a member one of his roles (or takes it back): a Command service.
 * <ul>
 *   <li>only a store member (MANAGER, EMPLOYEE, VIEWER) holds a custom role — the owner already holds every right;</li>
 *   <li>the role must be valid in the member's store;</li>
 *   <li>when the role's base differs from the member's role, the membership role changes first through
 *       {@link ChangeEmployeeRoleUseCase} (same checks, session revocation and event as a role change);</li>
 *   <li>{@code roleId = null} takes the custom role back: the member keeps his built-in role's defaults.</li>
 * </ul>
 * The membership trigger (V36) evicts the member's cached access state on commit: his next request carries the new
 * permissions. Cost: three keyed reads (employee, membership, role) and one UPDATE.
 */
@Service
public class CustomRoleAssignmentService {

    static final String OWNER_HAS_ALL = "Le propriétaire a déjà tous les droits.";
    static final String NOT_IN_STORE = "Ce rôle n'est pas valable dans la boutique de ce membre.";

    private final CustomRoleStore store;
    private final TenantRepository tenants;
    private final EmployeeRepository employees;
    private final UserMembershipRepository memberships;
    private final ChangeEmployeeRoleUseCase changeRole;
    private final AuditPort audit;
    private final ObjectMapper json;
    private final AccessStateChangePublisher accessStates;
    private final TransactionLockPort locks;

    public CustomRoleAssignmentService(CustomRoleStore store, TenantRepository tenants, EmployeeRepository employees,
                                       UserMembershipRepository memberships, ChangeEmployeeRoleUseCase changeRole,
                                       AuditPort audit, ObjectMapper json, AccessStateChangePublisher accessStates,
                                       TransactionLockPort locks) {
        this.accessStates = accessStates;
        this.locks = locks;
        this.store = store;
        this.tenants = tenants;
        this.employees = employees;
        this.memberships = memberships;
        this.changeRole = changeRole;
        this.audit = audit;
        this.json = json;
    }

    /**
     * Gives the member {@code employeeId} the role {@code roleId}, or takes his custom role back when {@code null}.
     *
     * @throws DomainException EMPLOYEE_NOT_FOUND, MEMBERSHIP_NOT_FOUND, ROLE_NOT_FOUND, VALIDATION_FAILED (French)
     */
    @Transactional
    public void assign(String tenantSchema, UUID actorId, UUID employeeId, UUID roleId) {
        UUID tenantId = tenants.findBySchemaName(tenantSchema)
                .orElseThrow(() -> new DomainException(ErrorCode.TENANT_NOT_FOUND)).getId();
        locks.lock("roles:" + tenantSchema); // serialized with role edits and deletions (no assignment to a deleted role)
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));
        if (employee.getUserId() == null || employee.getUserId().equals(actorId)) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, OWNER_HAS_ALL);
        }
        UserTenantMembership membership = memberships.findByUserIdAndTenantId(employee.getUserId(), tenantId)
                .orElseThrow(() -> new DomainException(ErrorCode.MEMBERSHIP_NOT_FOUND));
        if (!Role.isStoreMember(membership.getRole())) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, OWNER_HAS_ALL);
        }
        if (roleId == null) {
            CustomRole.Assigned previous = store.findAssigned(tenantId, employee.getUserId()).orElse(null);
            if (previous == null) {
                return; // nothing to take back
            }
            store.assign(tenantId, employee.getUserId(), null);
            accessStates.userChanged(employee.getUserId());
            record(actorId, tenantSchema, previous.roleId(), previous.name(), employee, null);
            return;
        }
        CustomRole role = store.findById(tenantId, roleId)
                .orElseThrow(() -> new DomainException(ErrorCode.ROLE_NOT_FOUND));
        if (role.baseRole().equals(membership.getRole()) && store.findAssigned(tenantId, employee.getUserId())
                .map(a -> a.roleId().equals(roleId)).orElse(false)) {
            return; // already his role: nothing written, nothing audited
        }
        if (!role.appliesToStore(employee.getStoreId())) {
            throw DomainException.userFacing(ErrorCode.VALIDATION_FAILED, NOT_IN_STORE);
        }
        if (!role.baseRole().equals(membership.getRole())) {
            changeRole.execute(new ChangeEmployeeRoleCommand(actorId, employeeId, role.baseRole()));
        }
        store.assign(tenantId, employee.getUserId(), role.id());
        accessStates.userChanged(employee.getUserId()); // his next request carries the role (trigger broadcasts too)
        record(actorId, tenantSchema, role.id(), role.name(), employee, role.name());
    }

    private void record(UUID actorId, String tenantSchema, UUID roleId, String roleName, Employee employee,
                        String newRole) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("member", fullName(employee));
        values.put("role", newRole == null ? "Rôle par défaut" : newRole);
        String after;
        try {
            after = json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            after = null;
        }
        audit.record(new AuditPort.AuditEntry(actorId, tenantSchema, "ROLE_ASSIGNED", "Role", roleId, null, after,
                employee.getStoreId(), roleName, null, "ONLINE", null));
    }

    private static String fullName(Employee employee) {
        String first = employee.getFirstName() == null ? "" : employee.getFirstName();
        String last = employee.getLastName() == null ? "" : employee.getLastName();
        String name = (first + " " + last).strip();
        return name.isEmpty() ? "Un membre" : name;
    }
}
