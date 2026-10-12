package com.keevo.shared.infrastructure.security;

import com.keevo.identity.auth.domain.model.Role;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * CurrentActor — single source of truth for reading the authenticated actor from the
 * Spring {@link SecurityContextHolder} (v1s-15-9).
 *
 * <p>Replaces the private {@code extractAssignedStoreId} / {@code isOwnerRole} /
 * {@code requireOwner} / {@code requireSuperAdmin} / actor-name copies that had drifted
 * across controllers, services and sync handlers. Role authorities are always derived from
 * the {@link Role} enum ({@code "ROLE_" + Role.X.name()}), never from string literals.
 */
public final class CurrentActor {

    static final String FALLBACK_EMPLOYEE_LABEL = "Un employé";
    static final String FALLBACK_OWNER_LABEL = "Le propriétaire";
    static final String FALLBACK_OTHER_LABEL = "Un utilisateur";

    private CurrentActor() {
    }

    /** The current {@link Authentication}, or {@code null} when unauthenticated. */
    public static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /**
     * The authenticated actor's user id (the JWT principal).
     *
     * @throws DomainException FORBIDDEN when there is no authentication
     */
    public static UUID actorId() {
        Authentication auth = authentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "No authenticated actor");
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID id) {
            return id;
        }
        return UUID.fromString(principal.toString());
    }

    /** Spring authority name for a role: {@code "ROLE_" + role.name()}. */
    public static String authority(Role role) {
        return "ROLE_" + role.name();
    }

    public static boolean hasRole(Role role) {
        Authentication auth = authentication();
        if (auth == null) return false;
        String expected = authority(role);
        return auth.getAuthorities().stream().anyMatch(a -> expected.equals(a.getAuthority()));
    }

    public static boolean isOwner() {
        return hasRole(Role.OWNER);
    }

    public static boolean isEmployee() {
        return hasRole(Role.EMPLOYEE);
    }

    public static boolean isSuperAdmin() {
        return hasRole(Role.SUPER_ADMIN);
    }

    /**
     * The actor's role name as use cases expect it: {@code "OWNER"} &gt; {@code "EMPLOYEE"} &gt;
     * {@code "SUPER_ADMIN"}, or {@code null} when unauthenticated or none is held.
     */
    public static String role() {
        if (isOwner()) return Role.OWNER.name();
        if (isEmployee()) return Role.EMPLOYEE.name();
        if (isSuperAdmin()) return Role.SUPER_ADMIN.name();
        return null;
    }

    /** @throws DomainException FORBIDDEN with {@code message} unless the actor is an OWNER */
    public static void requireOwner(String message) {
        if (!isOwner()) {
            throw new DomainException(ErrorCode.FORBIDDEN, message);
        }
    }

    /**
     * Second barrier behind the {@code /api/v1/admin/**} URL rule in SecurityConfig.
     *
     * @throws DomainException FORBIDDEN unless the actor is a SUPER_ADMIN
     */
    public static void requireSuperAdmin() {
        if (!isSuperAdmin()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "SUPER_ADMIN role required");
        }
    }

    /**
     * The EMPLOYEE's assigned store, or {@code null} for an unscoped actor (OWNER).
     * Fails closed: no authentication, or an EMPLOYEE token without a store, is FORBIDDEN.
     */
    public static UUID assignedStoreId() {
        Authentication auth = authentication();
        if (auth == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "No authenticated actor");
        }
        Object details = auth.getDetails();
        if (details instanceof AuthDetails ad && ad.storeId() != null) {
            return ad.storeId();
        }
        if (isEmployee()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "EMPLOYEE token missing assigned store");
        }
        return null;
    }

    /**
     * The actor's assigned store, or {@code null} when unauthenticated, unscoped (OWNER) or the
     * token carries no store. Never throws — used as the audit store fallback (v1s-18-6).
     */
    public static UUID assignedStoreIdOrNull() {
        Authentication auth = authentication();
        if (auth != null && auth.getDetails() instanceof AuthDetails ad) {
            return ad.storeId();
        }
        return null;
    }

    /**
     * Role-based label used when no name is known (read-time audit actor name, v1s-18-6).
     *
     * @param role role name ({@code "OWNER"}, {@code "EMPLOYEE"}, ...) or {@code null}
     */
    public static String fallbackLabel(String role) {
        if (Role.EMPLOYEE.name().equals(role)) return FALLBACK_EMPLOYEE_LABEL;
        if (Role.OWNER.name().equals(role)) return FALLBACK_OWNER_LABEL;
        return FALLBACK_OTHER_LABEL;
    }

    /**
     * Human-readable actor label for notifications: the JWT {@code firstName} when present,
     * otherwise a role-based fallback. Never returns a raw UUID.
     */
    public static String actorLabel() {
        Authentication auth = authentication();
        if (auth != null && auth.getDetails() instanceof AuthDetails ad
                && ad.firstName() != null && !ad.firstName().isBlank()) {
            return ad.firstName();
        }
        if (isEmployee()) return FALLBACK_EMPLOYEE_LABEL;
        if (isOwner()) return FALLBACK_OWNER_LABEL;
        return FALLBACK_OTHER_LABEL;
    }
}
