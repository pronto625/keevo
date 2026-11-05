package com.keevo.admin;

import com.keevo.identity.api.Role;

import java.util.stream.Collectors;

/**
 * Story 19.5 — the membership roles of a tenant as a SQL literal list for {@code m.role IN (...)}, derived once from
 * {@link Role#MEMBERSHIP_ROLE_NAMES} (OWNER + store members): the admin read models count every member, whatever new
 * store-member role is added. Constant values only (never user input), so inlining is safe.
 */
public final class MemberRolesSql {

    /** e.g. {@code 'OWNER','MANAGER','EMPLOYEE','VIEWER'}. */
    public static final String IN_LIST = Role.MEMBERSHIP_ROLE_NAMES.stream()
            .map(r -> "'" + r + "'").collect(Collectors.joining(","));

    private MemberRolesSql() {
    }
}
