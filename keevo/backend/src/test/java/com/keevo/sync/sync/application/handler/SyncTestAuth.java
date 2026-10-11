package com.keevo.sync.sync.application.handler;

import com.keevo.shared.infrastructure.security.AuthDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/** Puts an OWNER or EMPLOYEE on the security context, as JwtAuthFilter does. */
final class SyncTestAuth {

    private SyncTestAuth() {}

    static void owner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner", null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
    }

    static void employee(UUID assignedStoreId) {
        var auth = new UsernamePasswordAuthenticationToken(
                "employee", null, List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        auth.setDetails(new AuthDetails("Loïc", assignedStoreId));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    static void clear() {
        SecurityContextHolder.clearContext();
    }
}
