package com.keevo.identity.employee.adapter.in.rest;

import com.keevo.identity.api.Role;
import com.keevo.identity.auth.domain.model.UserTenantMembership;
import com.keevo.identity.employee.domain.model.Employee;
import com.keevo.identity.employee.domain.port.out.EmployeeRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.infrastructure.security.ActorPermissions;
import com.keevo.shared.infrastructure.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Story 19.5 — who may manage which team members on {@code /api/v1/employees} (policy object of the REST adapter):
 * <ul>
 *   <li>{@code OWNER}: every member, unchanged;</li>
 *   <li>a store member holding {@value #PERMISSION} ({@code MANAGER} by default — Story 19.5b, one permission instead
 *       of a role check; 19-14: « Gérer l'équipe de sa boutique »): only the members of his own store whose membership role is {@code EMPLOYEE} or
 *       {@code VIEWER} (a missing membership is refused — fail closed); his lists show those members and himself;
 *       creates only in his store and only {@code EMPLOYEE} or {@code VIEWER}; never changes roles nor reassigns
 *       stores (those endpoints stay {@link CurrentActor#requireOwner owner-only}); Story 19.5b: he also lists and closes
 *       their device sessions, and reactivates only a member that a team manager deactivated (never one the owner
 *       deactivated);</li>
 *   <li>anyone else: 403 {@code FORBIDDEN}.</li>
 * </ul>
 * Cost: O(1) for the owner; one employee row and one membership row by key for a MANAGER's per-member checks; lists
 * are one store-filtered query, filtered in memory with the bulk enrichment already loaded for the response.
 */
@Component
public class EmployeeTeamScope {

    /** Story 19.5b — managing the team of one's own store (declared by the {@code core} manifest). */
    public static final String PERMISSION = "TEAM_MANAGE";

    static final String DEACTIVATED_BY_OWNER =
            "Ce membre a été désactivé par le propriétaire : lui seul peut le réactiver.";
    static final String OWNER_REQUIRED = "OWNER role required";
    static final String OUT_OF_TEAM = "MANAGER may only manage EMPLOYEE or VIEWER members of his own store";
    static final String NO_STORE = "MANAGER token missing assigned store";

    /** The roles a MANAGER may create and manage. */
    private static final Set<String> MANAGEABLE_BY_MANAGER = Set.of(Role.EMPLOYEE.name(), Role.VIEWER.name());

    private final EmployeeRepository employeeRepository;
    private final EmployeeRoleResolver roleResolver;
    private final ActorPermissions permissions;

    public EmployeeTeamScope(EmployeeRepository employeeRepository, EmployeeRoleResolver roleResolver,
                             ActorPermissions permissions) {
        this.employeeRepository = employeeRepository;
        this.roleResolver = roleResolver;
        this.permissions = permissions;
    }

    /**
     * Whether the current actor acts as a delegated team manager (a store member holding {@value #PERMISSION}), not
     * as the owner: his deactivations are recorded so that he may undo them, and only them.
     */
    public boolean actsAsDelegate() {
        return !CurrentActor.isOwner() && CurrentActor.isStoreMember() && permissions.currentActorHolds(PERMISSION);
    }

    /**
     * The store a list is restricted to: {@code null} (every member) for an OWNER, the MANAGER's store for a MANAGER.
     *
     * @throws DomainException FORBIDDEN for any other actor
     */
    public UUID listScope() {
        if (CurrentActor.isOwner()) {
            return null;
        }
        return requireManagerStore();
    }

    /**
     * Whether a row of a MANAGER's store list is shown to him: an {@code EMPLOYEE}/{@code VIEWER} member, or himself
     * (never an OWNER, another MANAGER, or a user without membership).
     */
    public boolean isVisibleTeamMember(UUID userId, EmployeeRoleResolver.Enrichment enrichment) {
        if (userId != null && userId.equals(CurrentActor.actorId())) {
            return true;
        }
        return enrichment != null && enrichment.member() && MANAGEABLE_BY_MANAGER.contains(enrichment.role());
    }

    /**
     * Allows an OWNER on any member; a MANAGER only on an {@code EMPLOYEE}/{@code VIEWER} member of his store.
     *
     * @throws DomainException FORBIDDEN otherwise (missing membership included); EMPLOYEE_NOT_FOUND for an unknown id
     */
    public void requireManageable(UUID employeeId) {
        requireManageableUser(employeeId);
    }

    /**
     * As {@link #requireManageable}; returns the member's user id when it was resolved for the check (a delegate),
     * {@code null} for the owner (nothing read) — callers needing it then read it themselves.
     */
    public UUID requireManageableUser(UUID employeeId) {
        if (CurrentActor.isOwner()) {
            return null;
        }
        return requireManagedMembership(employeeId).getUserId();
    }

    /**
     * Story 19.5b — as {@link #requireManageable}, and a delegated team manager may only reactivate a member whose
     * deactivation was made by a delegated team manager (the flag kept on the membership); a member deactivated by
     * the owner (or before this rule existed) is reactivated by the owner only.
     *
     * @throws DomainException FORBIDDEN « Ce membre a été désactivé par le propriétaire… » otherwise
     */
    public void requireReactivatable(UUID employeeId) {
        if (CurrentActor.isOwner()) {
            return;
        }
        UserTenantMembership membership = requireManagedMembership(employeeId);
        if (!membership.isActive() && !membership.isDeactivatedByManager()) {
            throw DomainException.userFacing(ErrorCode.FORBIDDEN, DEACTIVATED_BY_OWNER);
        }
    }

    /** The target's membership when it is an EMPLOYEE/VIEWER member of the delegate's store (two keyed reads). */
    private UserTenantMembership requireManagedMembership(UUID employeeId) {
        UUID store = requireManagerStore();
        Employee target = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new DomainException(ErrorCode.EMPLOYEE_NOT_FOUND));
        if (!store.equals(target.getStoreId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, OUT_OF_TEAM);
        }
        UserTenantMembership membership = roleResolver.membership(target.getUserId()).orElse(null);
        if (membership == null || !MANAGEABLE_BY_MANAGER.contains(membership.getRole())) {
            throw new DomainException(ErrorCode.FORBIDDEN, OUT_OF_TEAM);
        }
        return membership;
    }

    /**
     * Allows an OWNER to create any member; a MANAGER only in his store and only {@code EMPLOYEE} or {@code VIEWER}
     * ({@code null} role = {@code EMPLOYEE}).
     */
    public void requireCreatable(UUID storeId, String role) {
        if (CurrentActor.isOwner()) {
            return;
        }
        UUID store = requireManagerStore();
        String effective = role == null || role.isBlank() ? Role.EMPLOYEE.name() : role;
        if (!store.equals(storeId) || !MANAGEABLE_BY_MANAGER.contains(effective)) {
            throw new DomainException(ErrorCode.FORBIDDEN, OUT_OF_TEAM);
        }
    }

    /** A MANAGER may update a member's profile but never move him to another store. */
    public void requireNoStoreChange(UUID requestedStoreId) {
        if (CurrentActor.isOwner() || requestedStoreId == null) {
            return;
        }
        if (!requestedStoreId.equals(requireManagerStore())) {
            throw new DomainException(ErrorCode.FORBIDDEN, OUT_OF_TEAM);
        }
    }

    /**
     * The delegate's store; FORBIDDEN for an actor without {@value #PERMISSION} (or not a store member), or a token
     * without store (fails closed).
     */
    private UUID requireManagerStore() {
        if (!CurrentActor.isStoreMember() || !permissions.currentActorHolds(PERMISSION)) {
            throw new DomainException(ErrorCode.FORBIDDEN, OWNER_REQUIRED);
        }
        UUID store = CurrentActor.assignedStoreIdOrNull();
        if (store == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, NO_STORE);
        }
        return store;
    }
}
