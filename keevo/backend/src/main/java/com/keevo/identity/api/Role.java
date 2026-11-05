package com.keevo.identity.api;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Role — User roles in the Keevo system.
 *
 * <p>Membership roles: {@code OWNER}, {@code MANAGER} (Story 19.5, Bref « admin »), {@code EMPLOYEE},
 * {@code VIEWER} (Story 19.5, read-only). {@code SUPER_ADMIN} is the platform administrator, not a tenant member.
 * {@link #isStoreMember()} is the single definition of the store-member set; every derived list below comes from it.
 *
 * <p>Pure Java enum — NO framework imports.
 */
public enum Role {
    OWNER,
    MANAGER,
    EMPLOYEE,
    VIEWER,
    SUPER_ADMIN;

    /** Story 19.5 — the store members, in declaration order (MANAGER, EMPLOYEE, VIEWER). */
    public static final List<Role> STORE_MEMBERS = Arrays.stream(values()).filter(Role::isStoreMember).toList();

    /** Names of {@link #STORE_MEMBERS}: the roles whose module permissions are declared per manifest. */
    public static final List<String> STORE_MEMBER_NAMES = STORE_MEMBERS.stream().map(Role::name).toList();

    /** Story 19.5 — every membership role of a tenant: {@code OWNER} and the store members. */
    public static final List<String> MEMBERSHIP_ROLE_NAMES =
            Stream.concat(Stream.of(OWNER.name()), STORE_MEMBER_NAMES.stream()).toList();

    private static final Map<String, Role> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Role::name, Function.identity()));

    /**
     * Story 19.5 — whether the role is pinned to one store like {@code EMPLOYEE}: {@code EMPLOYEE}, {@code MANAGER}
     * and {@code VIEWER}. The single decision used wherever « non-owners » are restricted, so a MANAGER or VIEWER is
     * never less restricted than an EMPLOYEE.
     */
    public boolean isStoreMember() {
        return this == EMPLOYEE || this == MANAGER || this == VIEWER;
    }

    /**
     * Story 19.5 — the role as the V1 2.0.1 apps know it: {@code MANAGER} and {@code VIEWER} show as
     * {@code EMPLOYEE}; the others unchanged. The real role travels in the additive {@code memberRole} field.
     */
    public Role v1Label() {
        return isStoreMember() ? EMPLOYEE : this;
    }

    /** {@link #isStoreMember()} for a role name; {@code false} for {@code null} or an unknown name. */
    public static boolean isStoreMember(String role) {
        Role r = parse(role);
        return r != null && r.isStoreMember();
    }

    /** {@link #v1Label()} for a role name; {@code null} stays {@code null}, an unknown name is returned unchanged. */
    public static String v1Label(String role) {
        Role r = parse(role);
        return r == null ? role : r.v1Label().name();
    }

    /** The role for a name, or {@code null} for {@code null} or an unknown name. */
    public static Role parse(String role) {
        return role == null ? null : BY_NAME.get(role);
    }
}
