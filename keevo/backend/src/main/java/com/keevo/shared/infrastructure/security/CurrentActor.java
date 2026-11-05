package com.keevo.shared.infrastructure.security;

import com.keevo.identity.api.Role;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
    static final String FALLBACK_MANAGER_LABEL = "Un gérant";
    static final String FALLBACK_VIEWER_LABEL = "Un lecteur";

    private static final String ROLE_PREFIX = "ROLE_";

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
        return ROLE_PREFIX + role.name();
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

    public static boolean isManager() {
        return hasRole(Role.MANAGER);
    }

    public static boolean isViewer() {
        return hasRole(Role.VIEWER);
    }

    /**
     * Story 19.5 — whether the actor is a store member ({@code EMPLOYEE}, {@code MANAGER} or {@code VIEWER},
     * {@link Role#isStoreMember()}): pinned to its store and restricted like an {@code EMPLOYEE}.
     */
    public static boolean isStoreMember() {
        Authentication auth = authentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a != null && a.startsWith(ROLE_PREFIX)
                        && Role.isStoreMember(a.substring(ROLE_PREFIX.length())));
    }

    public static boolean isSuperAdmin() {
        return hasRole(Role.SUPER_ADMIN);
    }

    /**
     * The actor's real role name: {@code "OWNER"} &gt; {@code "MANAGER"} &gt; {@code "EMPLOYEE"} &gt;
     * {@code "VIEWER"} &gt; {@code "SUPER_ADMIN"}, or {@code null} when unauthenticated or none is held.
     * Callers restricting « non-owners » compare with {@link Role#isStoreMember(String)}, never with
     * {@code "EMPLOYEE"} alone (Story 19.5).
     */
    public static String role() {
        if (isOwner()) return Role.OWNER.name();
        if (isManager()) return Role.MANAGER.name();
        if (isEmployee()) return Role.EMPLOYEE.name();
        if (isViewer()) return Role.VIEWER.name();
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
     * The store member's assigned store (EMPLOYEE, MANAGER, VIEWER), or {@code null} for an unscoped actor (OWNER).
     * Fails closed: no authentication, or a store member's token without a store, is FORBIDDEN.
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
        if (isStoreMember()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "Store member token missing assigned store");
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
     * @param role role name ({@code "OWNER"}, {@code "MANAGER"}, {@code "EMPLOYEE"}, {@code "VIEWER"}, ...) or {@code null}
     */
    public static String fallbackLabel(String role) {
        Role r = Role.parse(role);
        return r == null ? FALLBACK_OTHER_LABEL : labelOf(r);
    }

    /**
     * Story 16.11 — the device session ({@code sid}) of the current access token, or {@code null}
     * when unauthenticated or the token predates device sessions.
     */
    public static UUID sessionIdOrNull() {
        Authentication auth = authentication();
        if (auth != null && auth.getDetails() instanceof AuthDetails ad) {
            return ad.sessionId();
        }
        return null;
    }

    /**
     * Human-readable actor label for notifications: the employee first name when known,
     * otherwise a role-based fallback. Never returns a raw UUID.
     */
    public static String actorLabel() {
        Authentication auth = authentication();
        if (auth != null && auth.getDetails() instanceof AuthDetails ad
                && ad.firstName() != null && !ad.firstName().isBlank()) {
            return ad.firstName();
        }
        Role r = Role.parse(role());
        return r == null ? FALLBACK_OTHER_LABEL : labelOf(r);
    }

    /** Story 19.5 — role-based fallback label (« Un gérant », « Un lecteur » beside the existing ones). */
    private static String labelOf(Role role) {
        return switch (role) {
            case OWNER -> FALLBACK_OWNER_LABEL;
            case MANAGER -> FALLBACK_MANAGER_LABEL;
            case EMPLOYEE -> FALLBACK_EMPLOYEE_LABEL;
            case VIEWER -> FALLBACK_VIEWER_LABEL;
            default -> FALLBACK_OTHER_LABEL;
        };
    }
}
