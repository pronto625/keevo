package com.keevo.identity.auth.domain.port.in;

import com.keevo.identity.auth.domain.model.UserMembershipInfo;

import java.util.List;

/**
 * LoginSessionResult — Result of step 1 of two-step login (Story 1.7).
 *
 * <p>Contains a short-lived loginToken and the list of tenant memberships
 * the user belongs to. The Flutter client uses this to either:
 * <ul>
 *   <li>Auto-call step 2 when {@code memberships.size() == 1} (100% of single-tenant owners)</li>
 *   <li>Show a tenant picker when {@code memberships.size() > 1}</li>
 * </ul>
 *
 * @param loginToken  RS256 JWT with scope="login_pending", TTL 5 minutes
 * @param memberships list of tenant memberships the user belongs to
 */
public record LoginSessionResult(
        String loginToken,
        List<UserMembershipInfo> memberships
) {}
