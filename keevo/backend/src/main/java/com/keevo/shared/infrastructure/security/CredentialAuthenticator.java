package com.keevo.shared.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Story 21.9 — SPI of a credential that replaces the JWT on dedicated paths (the personal access tokens of the MCP
 * server). A module implements it as a bean; {@link CredentialAuthFilter} (in the security chain, before
 * {@link JwtAuthFilter}) asks it to resolve the credential to its <b>owner</b>, then applies the same server-side
 * checks as the JWT filter (16-11 access state: membership, role still OWNER, company not deleted, revocation cutoff,
 * foundation) and binds the very session shape of the JWT filter (principal, {@code ROLE_OWNER}, {@link AuthDetails},
 * {@code TenantContext}, MDC) for the request, cleared afterwards.
 *
 * <p>Only owners hold such credentials: they never grant more than the owner's own rights, which are read live on
 * every request.
 */
public interface CredentialAuthenticator {

    /** Whether this authenticator owns the request (its credential replaces the JWT there). Cheap, no I/O. */
    boolean handles(HttpServletRequest request);

    /**
     * Resolves the request's credential. Never throws for a bad credential: answers a {@link Refusal}.
     *
     * @return an {@link Owner} to authenticate, or a {@link Refusal} written as is
     */
    Resolution resolve(HttpServletRequest request);

    /** Domain code answered (401) when the owner's access no longer allows the credential (role, revocation). */
    String revokedCode();

    /**
     * Called once the owner is authenticated and the session bound (tenant, security context), before the request is
     * served: rate limits, module gate… {@code null} lets the request through.
     */
    default Refusal afterAuthentication(HttpServletRequest request, Owner owner) {
        return null;
    }

    /** Outcome of {@link #resolve}. */
    sealed interface Resolution permits Owner, Refusal {
    }

    /**
     * The credential's owner.
     *
     * @param userId   owner's user id
     * @param schema   company schema ({@code kv_xxxxxx}) the credential belongs to
     * @param issuedAt when the credential was created (compared with the revocation cutoff)
     */
    record Owner(UUID userId, String schema, Instant issuedAt) implements Resolution {
    }

    /**
     * A refusal: HTTP status, domain code and French message (null = the catalogue's text), extra headers.
     */
    record Refusal(int status, String domainCode, String message, Map<String, String> headers) implements Resolution {

        public Refusal {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public static Refusal of(int status, String domainCode) {
            return new Refusal(status, domainCode, null, Map.of());
        }
    }
}
