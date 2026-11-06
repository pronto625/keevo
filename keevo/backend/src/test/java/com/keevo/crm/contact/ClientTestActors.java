package com.keevo.crm.contact;

import com.keevo.shared.infrastructure.security.AuthDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/** Story 20.2 — authenticated actors for client tests (clear the context in {@code @AfterEach}). */
public final class ClientTestActors {

    private ClientTestActors() {
    }

    public static void owner(UUID actorId) {
        var auth = new UsernamePasswordAuthenticationToken(actorId, null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        auth.setDetails(AuthDetails.owner("Paul"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    public static void member(UUID actorId, UUID storeId, String role) {
        var auth = new UsernamePasswordAuthenticationToken(actorId, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        auth.setDetails(new AuthDetails("Awa", storeId, null));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
